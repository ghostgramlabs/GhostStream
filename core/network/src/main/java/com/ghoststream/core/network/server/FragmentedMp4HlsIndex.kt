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

            val firstMoof = boxes.firstOrNull { it.type == "moof" } ?: return null
            if (firstMoof.offset <= 0L) return null

            val segments = mutableListOf<HlsMediaSegment>()
            var nextIndex = 0
            var cursor = boxes.indexOf(firstMoof)
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

            return FragmentedMp4HlsIndex(
                initSegmentLength = firstMoof.offset,
                segments = segments,
                fileLength = fileLength,
            )
        }
    }

    private fun readType(raf: RandomAccessFile): String {
        val buffer = ByteArray(4)
        raf.readFully(buffer)
        return buffer.toString(Charsets.US_ASCII)
    }

    private data class Mp4TopLevelBox(
        val type: String,
        val offset: Long,
        val size: Long,
    )

    private const val MIN_BOX_HEADER_SIZE = 8L
    private const val LARGE_BOX_HEADER_SIZE = 16L
}
