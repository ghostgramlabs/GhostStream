package com.ghoststream.core.network.server

import java.io.File
import java.io.RandomAccessFile

internal data class FragmentedMp4HlsIndex(
    val initSegmentLength: Long,
    val segments: List<HlsMediaSegment>,
    val fileLength: Long,
)

internal data class HlsMediaSegment(
    val index: Int,
    val offset: Long,
    val length: Long,
    val durationSeconds: Double,
)

internal object FragmentedMp4HlsIndexer {
    fun read(file: File, fragmentDurationSeconds: Double = 2.0): FragmentedMp4HlsIndex? {
        if (!file.exists()) return null
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

            val firstMoofIndex = boxes.indexOfFirst { it.type == "moof" }
            if (firstMoofIndex < 0) return null

            val moovIndex = boxes.indexOfLast { it.type == "moov" }
            if (moovIndex < 0) return null

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
            )
        }
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

    private const val MIN_BOX_HEADER_SIZE = 8L
    private const val LARGE_BOX_HEADER_SIZE = 16L
    private val INIT_TRAILING_BOX_TYPES = setOf("sidx", "ssix")
    private val SEGMENT_PREFIX_BOX_TYPES = setOf("styp", "prft", "emsg")
}
