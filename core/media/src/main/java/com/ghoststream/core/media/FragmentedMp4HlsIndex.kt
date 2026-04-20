package com.ghoststream.core.media

import java.io.File
import java.io.RandomAccessFile

data class FragmentedMp4HlsIndex(
    val initSegmentLength: Long,
    val segments: List<HlsMediaSegment>,
    val fileLength: Long,
    /**
     * RFC 6381 codec string read from the moov box (e.g. "avc1.640028,mp4a.40.2").
     * Null if the codec could not be detected. Used in the HLS master playlist CODECS
     * attribute so MSE opens the right SourceBuffer type instead of guessing.
     */
    val videoCodecString: String? = null,
    /**
     * RFC 6381 audio codec string read from the moov box when an audio track exists.
     * Null for video-only outputs or when the audio codec could not be detected.
     */
    val audioCodecString: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Diagnostic info for why indexing might have failed or found zero segments. */
    val diagnosticInfo: String? = null,
    /**
     * Maps track_id → media timescale (ticks per second), read from each trak/mdia/mdhd box.
     * Used by MseTfhdPatcher to offset tfdt.baseMediaDecodeTime when serving seek-restarted
     * streams, so that browser currentTime stays on the original video timeline.
     */
    val timescales: Map<Int, Long> = emptyMap(),
)

data class HlsMediaSegment(
    val index: Int,
    val offset: Long,
    val length: Long,
    val durationSeconds: Double,
)

object FragmentedMp4HlsIndexer {
    /**
     * Parse a (possibly still-growing) fragmented MP4 file and return an HLS segment
     * index describing the init segment and all complete moof+mdat fragments found.
     *
     * Returns null if the file does not exist, is too small to parse, or does not
     * contain the expected box structure (e.g. non-fragmented MP4).
     *
     * This function is safe to call concurrently or while Media3 is still writing to
     * the file — it only counts fragments whose mdat box is fully written to disk.
     * Any I/O exception is caught and treated as a null result.
     */
    fun read(file: File, fragmentDurationSeconds: Double = 2.0): FragmentedMp4HlsIndex? {
        if (!file.exists()) return null
        return try {
            readInternal(file, fragmentDurationSeconds)
        } catch (e: Exception) {
            FragmentedMp4HlsIndex(
                initSegmentLength = 0,
                segments = emptyList(),
                fileLength = file.length(),
                diagnosticInfo = "Exception during parse: ${e.message}"
            )
        }
    }

    private fun readInternal(file: File, fragmentDurationSeconds: Double): FragmentedMp4HlsIndex? {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength < 16L) return null

            val boxes = mutableListOf<Mp4TopLevelBox>()
            var offset = 0L
            while (offset + MIN_BOX_HEADER_SIZE <= fileLength) {
                raf.seek(offset)
                val size32 = raf.readInt().toLong() and 0xffffffffL
                val type = readType(raf)
                val boxSize = when (size32) {
                    0L -> break
                    1L -> {
                        if (offset + LARGE_BOX_HEADER_SIZE > fileLength) break
                        raf.readLong()
                    }

                    else -> size32
                }
                if (boxSize < MIN_BOX_HEADER_SIZE) break
                val endExclusive = offset + boxSize
                if (endExclusive > fileLength) break
                boxes += Mp4TopLevelBox(
                    type = type,
                    offset = offset,
                    size = boxSize,
                )
                offset = endExclusive
            }

            // Detect the actual video codec by parsing the moov box so that the HLS master
            // playlist CODECS attribute is always accurate. This prevents bufferAppendError
            // in hls.js when the Android encoder produces a different H.264 profile than the
            // hardcoded Baseline string, or produces HEVC/VP9 via DefaultEncoderFactory fallback.
            val moovBox = boxes.firstOrNull { it.type == "moov" }
            val moovMetadata = if (moovBox != null) {
                runCatching { readTrackMetadataFromMoov(raf, moovBox) }.getOrNull() ?: MoovMetadata()
            } else {
                MoovMetadata()
            }

            val moovIndex = boxes.indexOfLast { it.type == "moov" }
            if (moovIndex < 0) {
                return FragmentedMp4HlsIndex(
                    initSegmentLength = 0,
                    segments = emptyList(),
                    fileLength = fileLength,
                    diagnosticInfo = "No 'moov' box found. Not a valid MP4 or init segment still being written."
                )
            }

            val firstMoofIndex = boxes.indexOfFirst { it.type == "moof" }
            if (firstMoofIndex < 0) {
                return FragmentedMp4HlsIndex(
                    initSegmentLength = boxes[moovIndex].offset + boxes[moovIndex].size,
                    segments = emptyList(),
                    fileLength = fileLength,
                    diagnosticInfo = "Found 'moov' but no 'moof' fragments yet. Encoding in progress?"
                )
            }

            var initSegmentLength = boxes[moovIndex].offset + boxes[moovIndex].size
            var cursor = moovIndex + 1
            while (cursor in boxes.indices && boxes[cursor].type in INIT_TRAILING_BOX_TYPES) {
                initSegmentLength = boxes[cursor].offset + boxes[cursor].size
                cursor += 1
            }

            val segments = mutableListOf<HlsMediaSegment>()
            var nextIndex = 0
            while (cursor in boxes.indices) {
                val segmentStartIndex = findSegmentStartIndex(boxes, cursor)
                if (segmentStartIndex < 0) {
                    break
                }

                val moofIndex = boxes.indexOfFirstFrom(segmentStartIndex) { it.type == "moof" }
                if (moofIndex < 0) {
                    break
                }

                val mdatIndex = boxes.indexOfFirstFrom(moofIndex + 1) { it.type == "mdat" }
                if (mdatIndex < 0) {
                    break
                }

                val segmentStartOffset = boxes[segmentStartIndex].offset
                val mdat = boxes[mdatIndex]
                val segmentEndExclusive = mdat.offset + mdat.size
                if (segmentEndExclusive > fileLength || segmentStartOffset >= segmentEndExclusive) {
                    break
                }

                if (segments.isEmpty()) {
                    initSegmentLength = segmentStartOffset
                }

                segments += HlsMediaSegment(
                    index = nextIndex,
                    offset = segmentStartOffset,
                    length = segmentEndExclusive - segmentStartOffset,
                    durationSeconds = fragmentDurationSeconds,
                )
                nextIndex += 1
                cursor = mdatIndex + 1
            }

            if (initSegmentLength <= 0L || segments.isEmpty()) {
                val firstMoof = boxes[firstMoofIndex]
                if (firstMoof.offset <= 0L) return null
                initSegmentLength = firstMoof.offset
                segments.clear()
                cursor = firstMoofIndex
                nextIndex = 0
                while (cursor in boxes.indices) {
                    val moof = boxes[cursor]
                    if (moof.type != "moof") {
                        cursor += 1
                        continue
                    }
                    val mdat = boxes.getOrNull(cursor + 1)?.takeIf { it.type == "mdat" } ?: break
                    val segmentEndExclusive = mdat.offset + mdat.size
                    if (segmentEndExclusive > fileLength) break
                    segments += HlsMediaSegment(
                        index = nextIndex,
                        offset = moof.offset,
                        length = segmentEndExclusive - moof.offset,
                        durationSeconds = fragmentDurationSeconds,
                    )
                    nextIndex += 1
                    cursor += 2
                }
            }

            return FragmentedMp4HlsIndex(
                initSegmentLength = initSegmentLength,
                segments = segments,
                fileLength = fileLength,
                videoCodecString = moovMetadata.videoCodecString,
                audioCodecString = moovMetadata.audioCodecString,
                width = moovMetadata.width,
                height = moovMetadata.height,
                timescales = moovMetadata.timescales,
            )
        }
    }

    // ── Codec detection ────────────────────────────────────────────────────────────────────────

    private fun readTrackMetadataFromMoov(raf: RandomAccessFile, moovBox: Mp4TopLevelBox): MoovMetadata {
        val moovEnd = moovBox.offset + moovBox.size
        var videoCodec: String? = null
        var audioCodec: String? = null
        var width: Int? = null
        var height: Int? = null
        val timescales = mutableMapOf<Int, Long>()
        for (trak in readChildBoxes(raf, moovBox.offset + 8, moovEnd)) {
            if (trak.type != "trak") continue
            val metadata = readTrakMetadata(raf, trak) ?: continue
            if (metadata.trackId > 0 && metadata.timescale > 0) {
                timescales[metadata.trackId] = metadata.timescale
            }
            when (metadata.handlerType) {
                "vide" -> {
                    if (videoCodec == null) videoCodec = metadata.codecString
                    if (width == null) width = metadata.width
                    if (height == null) height = metadata.height
                }

                "soun" -> {
                    if (audioCodec == null) audioCodec = metadata.codecString
                }
            }
        }
        return MoovMetadata(
            videoCodecString = videoCodec,
            audioCodecString = audioCodec,
            width = width,
            height = height,
            timescales = timescales,
        )
    }

    private fun readTrakMetadata(raf: RandomAccessFile, trak: Mp4TopLevelBox): TrackMetadata? {
        val trakEnd = trak.offset + trak.size
        val trakChildren = readChildBoxes(raf, trak.offset + 8, trakEnd)

        // Read track_id from tkhd box (mandatory in every trak)
        val tkhd = trakChildren.firstOrNull { it.type == "tkhd" }
        val trackId = if (tkhd != null) {
            runCatching {
                raf.seek(tkhd.offset + 8)
                val version = raf.read() and 0xFF
                // version 0: creation(4) + modification(4) + track_id at offset 20
                // version 1: creation(8) + modification(8) + track_id at offset 28
                val trackIdOffset = tkhd.offset + 8 + 4 + if (version == 0) 8L else 16L
                raf.seek(trackIdOffset)
                raf.readInt()
            }.getOrDefault(0)
        } else 0

        val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: return null
        val mdiaEnd = mdia.offset + mdia.size
        val mdiaChildren = readChildBoxes(raf, mdia.offset + 8, mdiaEnd)

        // Read timescale from mdhd box
        val timescale = mdiaChildren.firstOrNull { it.type == "mdhd" }?.let { mdhd ->
            runCatching {
                raf.seek(mdhd.offset + 8)
                val version = raf.read() and 0xFF
                // version 0: creation(4) + modification(4) + timescale at offset 20
                // version 1: creation(8) + modification(8) + timescale at offset 28
                val timescaleOffset = mdhd.offset + 8 + 4 + if (version == 0) 8L else 16L
                raf.seek(timescaleOffset)
                raf.readInt().toLong() and 0xFFFFFFFFL
            }.getOrDefault(0L)
        } ?: 0L

        val hdlr = mdiaChildren.firstOrNull { it.type == "hdlr" } ?: return null
        val handlerTypeOffset = hdlr.offset + 8 + 4 + 4
        if (handlerTypeOffset + 4 > hdlr.offset + hdlr.size) return null
        raf.seek(handlerTypeOffset)
        val handlerBuf = ByteArray(4)
        raf.readFully(handlerBuf)
        val handlerType = handlerBuf.toString(Charsets.US_ASCII)
        if (handlerType != "vide" && handlerType != "soun") return null

        val minf = mdiaChildren.firstOrNull { it.type == "minf" } ?: return null
        val minfEnd = minf.offset + minf.size
        val stbl = readChildBoxes(raf, minf.offset + 8, minfEnd).firstOrNull { it.type == "stbl" } ?: return null
        val stblEnd = stbl.offset + stbl.size
        val stsd = readChildBoxes(raf, stbl.offset + 8, stblEnd).firstOrNull { it.type == "stsd" } ?: return null

        val firstEntryStart = stsd.offset + 8 + 8
        if (firstEntryStart + 8 > stsd.offset + stsd.size) return null
        raf.seek(firstEntryStart)
        val entrySize = raf.readInt().toLong() and 0xffffffffL
        val entryTypeBuf = ByteArray(4)
        raf.readFully(entryTypeBuf)
        val entryType = entryTypeBuf.toString(Charsets.US_ASCII)
        val entryEnd = firstEntryStart + entrySize

        val codecMetadata = when (handlerType) {
            "vide" -> readVideoSampleDescription(raf, entryType, firstEntryStart, entryEnd)
            "soun" -> readAudioSampleDescription(entryType)
            else -> null
        } ?: return null

        return codecMetadata.copy(trackId = trackId, timescale = timescale)
    }

    private fun readVideoSampleDescription(
        raf: RandomAccessFile,
        entryType: String,
        entryStart: Long,
        entryEnd: Long,
    ): TrackMetadata? {
        return when (entryType) {
            "avc1", "avc3" -> readH264Metadata(raf, entryStart, entryEnd)
            "hvc1", "hev1" -> readH265Metadata(raf, entryStart, entryEnd)
            "vp09" -> TrackMetadata(handlerType = "vide", codecString = "vp09.00.40.08")
            "av01" -> TrackMetadata(handlerType = "vide", codecString = "av01.0.04M.08")
            else -> null
        }
    }

    private fun readAudioSampleDescription(entryType: String): TrackMetadata? {
        val codec = when (entryType) {
            "mp4a" -> "mp4a.40.2"
            "ac-3" -> "ac-3"
            "ec-3" -> "ec-3"
            "Opus" -> "opus"
            else -> null
        }
        return TrackMetadata(handlerType = "soun", codecString = codec)
    }

    private fun readH264Metadata(raf: RandomAccessFile, avc1Start: Long, avc1End: Long): TrackMetadata {
        raf.seek(avc1Start + 32)
        val width = raf.readUnsignedShort()
        val height = raf.readUnsignedShort()

        val childStart = avc1Start + 86L
        var pos = childStart
        var codec: String? = null
        while (pos + 8 <= avc1End) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xffffffffL
            if (size32 < 8L) break
            val typeBuf = ByteArray(4)
            raf.readFully(typeBuf)
            if (typeBuf.toString(Charsets.US_ASCII) == "avcC" && pos + 12 <= avc1End) {
                raf.seek(pos + 9)
                val profile = raf.read()
                val compat = raf.read()
                val level = raf.read()
                if (profile >= 0 && compat >= 0 && level >= 0) {
                    codec = "avc1.%02X%02X%02X".format(profile, compat, level)
                    break
                }
            }
            pos += size32
        }
        return TrackMetadata(
            handlerType = "vide",
            codecString = codec ?: "avc1.640028",
            width = width,
            height = height,
        )
    }

    private fun readH265Metadata(raf: RandomAccessFile, hvc1Start: Long, hvc1End: Long): TrackMetadata {
        raf.seek(hvc1Start + 32)
        val width = raf.readUnsignedShort()
        val height = raf.readUnsignedShort()
        return TrackMetadata(
            handlerType = "vide",
            codecString = "hvc1.1.6.L120.90",
            width = width,
            height = height,
        )
    }

    private fun readChildBoxes(raf: RandomAccessFile, from: Long, to: Long): List<Mp4TopLevelBox> {
        val result = mutableListOf<Mp4TopLevelBox>()
        var pos = from
        while (pos + MIN_BOX_HEADER_SIZE <= to) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xffffffffL
            val typeBuf = ByteArray(4)
            raf.readFully(typeBuf)
            val type = typeBuf.toString(Charsets.US_ASCII)
            val size = when (size32) {
                0L -> to - pos
                1L -> if (pos + LARGE_BOX_HEADER_SIZE <= to) raf.readLong() else break
                else -> size32
            }
            if (size < MIN_BOX_HEADER_SIZE) break
            val endExclusive = pos + size
            if (endExclusive > to) break
            result += Mp4TopLevelBox(type = type, offset = pos, size = size)
            pos = endExclusive
        }
        return result
    }

    private fun findSegmentStartIndex(
        boxes: List<Mp4TopLevelBox>,
        fromIndex: Int,
    ): Int {
        var cursor = fromIndex
        while (cursor in boxes.indices) {
            val box = boxes[cursor]
            when {
                box.type == "styp" || box.type == "moof" -> return cursor
                box.type in SEGMENT_PREFIX_BOX_TYPES -> {
                    val nextMoof = boxes.indexOfFirstFrom(cursor + 1) { it.type == "moof" }
                    if (nextMoof > cursor) {
                        return cursor
                    }
                }

                else -> {
                    cursor += 1
                }
            }
        }
        return -1
    }

    private fun readType(raf: RandomAccessFile): String {
        val buffer = ByteArray(4)
        raf.readFully(buffer)
        return buffer.toString(Charsets.US_ASCII)
    }

    private inline fun List<Mp4TopLevelBox>.indexOfFirstFrom(
        startIndex: Int,
        predicate: (Mp4TopLevelBox) -> Boolean,
    ): Int {
        for (index in startIndex until size) {
            if (predicate(this[index])) {
                return index
            }
        }
        return -1
    }

    private data class Mp4TopLevelBox(
        val type: String,
        val offset: Long,
        val size: Long,
    )

    private data class TrackMetadata(
        val handlerType: String,
        val codecString: String?,
        val width: Int? = null,
        val height: Int? = null,
        val trackId: Int = 0,
        val timescale: Long = 0,
    )

    private data class MoovMetadata(
        val videoCodecString: String? = null,
        val audioCodecString: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val timescales: Map<Int, Long> = emptyMap(),
    )

    private const val MIN_BOX_HEADER_SIZE = 8L
    private const val LARGE_BOX_HEADER_SIZE = 16L
    private val INIT_TRAILING_BOX_TYPES = setOf("sidx", "ssix")
    private val SEGMENT_PREFIX_BOX_TYPES = setOf("styp", "prft", "emsg")
}
