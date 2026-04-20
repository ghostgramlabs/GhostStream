package com.ghoststream.core.network.server

/**
 * Patches fragmented MP4 (.m4s) segments to be MSE-compatible.
 *
 * This version supports MULTIPLE tracks (Video + Audio) multiplexed in a single segment.
 *
 * It correctly handles the cumulative byte shift and uses the MOOF-relative baseline for
 * TRUN offsets, required by browsers when 'default-base-is-moof' is set. 
 */
internal object MseTfhdPatcher {

    /**
     * Offsets tfdt.baseMediaDecodeTime in every track fragment by startOffsetMs so that
     * the browser's video.currentTime stays on the original video timeline after a seek
     * restart. Media3 resets timestamps to 0 when clipping starts at an offset, so without
     * this patch the seek bar would reset to 0 after each seek.
     *
     * timescales maps track_id → ticks-per-second from the moov/trak/mdhd box.
     * If startOffsetMs == 0 or timescales is empty this is a no-op.
     */
    fun patchTimestamps(segmentBytes: ByteArray, startOffsetMs: Long, timescales: Map<Int, Long>): ByteArray {
        if (startOffsetMs <= 0L || timescales.isEmpty()) return segmentBytes
        val result = segmentBytes.copyOf()
        val moofPositions = findBoxPositions(result, "moof")
        for (moofStart in moofPositions) {
            val moofSize = readU32(result, moofStart).toInt()
            val trafPositions = findBoxPositions(result, "traf", moofStart + 8, moofStart + moofSize)
            for (trafStart in trafPositions) {
                val trafSize = readU32(result, trafStart).toInt()
                val trafEnd = trafStart + trafSize
                val tfhdStart = findFirstChild(result, trafStart + 8, trafEnd, "tfhd") ?: continue
                val trackId = readU32(result, tfhdStart + 12).toInt()
                val timescale = timescales[trackId] ?: continue
                val timeOffset = startOffsetMs * timescale / 1000L
                val tfdtStart = findFirstChild(result, trafStart + 8, trafEnd, "tfdt") ?: continue
                val version = result[tfdtStart + 8].toInt() and 0xFF
                if (version == 0) {
                    val bdt = readU32(result, tfdtStart + 12)
                    writeU32(result, tfdtStart + 12, (bdt + timeOffset).toInt())
                } else {
                    val bdt = readU64(result, tfdtStart + 12)
                    writeU64(result, tfdtStart + 12, bdt + timeOffset)
                }
            }
        }
        return result
    }

    fun patch(segmentBytes: ByteArray, segmentFileOffset: Long): ByteArray {
        val moofPositions = findBoxPositions(segmentBytes, "moof")
        if (moofPositions.isEmpty()) return segmentBytes

        var currentBytes = segmentBytes
        var cumulativeShiftAcrossMoofs = 0

        for (moofStart in moofPositions) {
            val adjustedMoofStart = moofStart - cumulativeShiftAcrossMoofs
            val result = patchMoof(currentBytes, adjustedMoofStart, segmentFileOffset + moofStart)
            if (result.patchedBytes !== currentBytes) {
                currentBytes = result.patchedBytes
                cumulativeShiftAcrossMoofs += result.bytesRemoved
            }
        }

        return currentBytes
    }

    private data class MoofPatchResult(
        val patchedBytes: ByteArray,
        val bytesRemoved: Int
    )

    /**
     * @param moofAbsoluteOffset  The file-absolute offset of this 'moof' box in the ORIGINAL file.
     */
    private fun patchMoof(input: ByteArray, moofStart: Int, moofAbsoluteOffset: Long): MoofPatchResult {
        val moofSize = readU32(input, moofStart).toInt()
        val moofEnd = moofStart + moofSize
        
        val trafPositions = findBoxPositions(input, "traf", moofStart + 8, moofEnd)
        if (trafPositions.isEmpty()) return MoofPatchResult(input, 0)

        val edits = mutableListOf<TrafEdit>()
        for (trafStart in trafPositions) {
            val edit = buildTrafEdit(input, trafStart, moofAbsoluteOffset)
            if (edit != null) edits.add(edit)
        }

        if (edits.isEmpty()) return MoofPatchResult(input, 0)

        // Total bytes we are going to remove from this moof (8 bytes per tfhd field removal)
        val totalRemovedFromMoof = edits.size * 8
        val out = ByteArray(input.size - totalRemovedFromMoof)

        // Sort edits to copy in sequence
        val sortedEdits = edits.sortedBy { it.removeAtPos }
        
        var inPos = 0
        var outPos = 0
        for (edit in sortedEdits) {
            val count = edit.removeAtPos - inPos
            System.arraycopy(input, inPos, out, outPos, count)
            inPos += count + 8
            outPos += count
        }
        System.arraycopy(input, inPos, out, outPos, input.size - inPos)

        // Function to map original input positions to output positions
        fun getOutPos(inP: Int): Int {
            var shift = 0
            for (e in sortedEdits) if (inP > e.removeAtPos) shift += 8
            return inP - shift
        }

        // Fix sizes and flags
        writeU32(out, getOutPos(moofStart), moofSize - totalRemovedFromMoof)
        
        for (e in edits) {
            writeU32(out, getOutPos(e.trafStart), e.newTrafSize)
            writeU32(out, getOutPos(e.tfhdStart), e.newTfhdSize)
            writeU24(out, getOutPos(e.tfhdFlagsPos), e.newTfhdFlags)
            
            if (e.trunDataOffsetPos >= 0) {
                // IMPORTANT: The trun data_offset is relative to the start of the MOOF box
                // when the 'default-base-is-moof' (0x020000) flag is used.
                //
                // In the original file: sample_pos = baseDataOffset + originalTrunOffset
                // Relative to original moof: dist = (baseDataOffset - moofAbsoluteOffset) + originalTrunOffset
                // In the patched segment: moof is shrunken by 'totalRemovedFromMoof' bytes.
                //
                // So new_trun_relative_offset = dist - totalRemovedFromMoof
                val originalRelativeSamplePos = (e.baseDataOffset - moofAbsoluteOffset) + e.originalTrunOffset
                val newTrunOffset = (originalRelativeSamplePos - totalRemovedFromMoof).toInt()
                writeS32(out, getOutPos(e.trunDataOffsetPos), newTrunOffset)
            }
        }

        return MoofPatchResult(out, totalRemovedFromMoof)
    }

    private data class TrafEdit(
        val trafStart: Int,
        val newTrafSize: Int,
        val tfhdStart: Int,
        val newTfhdSize: Int,
        val tfhdFlagsPos: Int,
        val newTfhdFlags: Int,
        val removeAtPos: Int,
        val trunDataOffsetPos: Int,
        val baseDataOffset: Long,
        val originalTrunOffset: Int
    )

    private fun buildTrafEdit(input: ByteArray, trafStart: Int, moofAbsoluteOffset: Long): TrafEdit? {
        val trafSize = readU32(input, trafStart).toInt()
        val trafEnd = trafStart + trafSize
        val tfhdStart = findFirstChild(input, trafStart + 8, trafEnd, "tfhd") ?: return null
        val tfhdSize = readU32(input, tfhdStart).toInt()
        val tfhdFlags = readU24(input, tfhdStart + 9)

        if (tfhdFlags and 0x000001 == 0) return null // No base-data-offset

        val baseDataOffset = readU64(input, tfhdStart + 16)
        val trunStart = findFirstChild(input, trafStart + 8, trafEnd, "trun")
        var trunDataOffsetPos = -1
        var originalTrunOffset = 0
        if (trunStart != null) {
            val trunFlags = readU24(input, trunStart + 9)
            if (trunFlags and 0x000001 != 0) {
                trunDataOffsetPos = trunStart + 16
                originalTrunOffset = readS32(input, trunDataOffsetPos)
            }
        }

        return TrafEdit(
            trafStart = trafStart,
            newTrafSize = trafSize - 8,
            tfhdStart = tfhdStart,
            newTfhdSize = tfhdSize - 8,
            tfhdFlagsPos = tfhdStart + 9,
            newTfhdFlags = (tfhdFlags and 0x00FFFFFE) or 0x020000,
            removeAtPos = tfhdStart + 16,
            trunDataOffsetPos = trunDataOffsetPos,
            baseDataOffset = baseDataOffset,
            originalTrunOffset = originalTrunOffset
        )
    }

    private fun findBoxPositions(buf: ByteArray, type: String, from: Int = 0, to: Int = buf.size): List<Int> {
        val result = mutableListOf<Int>()
        var pos = from
        while (pos + 8 <= to) {
            val sz = readU32(buf, pos).toInt()
            if (sz < 8) break
            if (readType(buf, pos + 4) == type) result.add(pos)
            pos += sz
        }
        return result
    }

    private fun findFirstChild(buf: ByteArray, from: Int, to: Int, type: String): Int? {
        return findBoxPositions(buf, type, from, to).firstOrNull()
    }

    private fun readType(buf: ByteArray, at: Int) = String(buf, at, 4, Charsets.US_ASCII)

    private fun readU32(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xFF) shl 24) or
        ((buf[at + 1].toLong() and 0xFF) shl 16) or
        ((buf[at + 2].toLong() and 0xFF) shl 8) or
        (buf[at + 3].toLong() and 0xFF)

    private fun readU24(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 16) or
        ((buf[at + 1].toInt() and 0xFF) shl 8) or
        (buf[at + 2].toInt() and 0xFF)

    private fun readU64(buf: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
        return v
    }

    private fun readS32(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or
        ((buf[at + 1].toInt() and 0xFF) shl 16) or
        ((buf[at + 2].toInt() and 0xFF) shl 8) or
        (buf[at + 3].toInt() and 0xFF)

    private fun writeU32(buf: ByteArray, at: Int, v: Int) {
        buf[at]     = (v ushr 24).toByte()
        buf[at + 1] = (v ushr 16).toByte()
        buf[at + 2] = (v ushr  8).toByte()
        buf[at + 3] = v.toByte()
    }

    private fun writeU24(buf: ByteArray, at: Int, v: Int) {
        buf[at]     = (v ushr 16).toByte()
        buf[at + 1] = (v ushr  8).toByte()
        buf[at + 2] = v.toByte()
    }

    private fun writeS32(buf: ByteArray, at: Int, v: Int) = writeU32(buf, at, v)

    private fun writeU64(buf: ByteArray, at: Int, v: Long) {
        for (i in 7 downTo 0) buf[at + (7 - i)] = ((v ushr (i * 8)) and 0xFF).toByte()
    }
}
