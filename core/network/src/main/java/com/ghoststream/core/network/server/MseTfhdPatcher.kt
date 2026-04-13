package com.ghoststream.core.network.server

/**
 * Patches fragmented MP4 (.m4s) segments to be MSE-compatible.
 *
 * The Android Media3 [InAppMuxer] writes TFHD boxes with an absolute `base-data-offset`
 * field that points into the source file. The MSE spec (ISO BMFF byte stream format,
 * https://www.w3.org/TR/mse-byte-stream-format-isobmff/#movie-fragment-relative-addressing)
 * forbids `base-data-offset` entirely; browsers reject such segments with:
 *
 *   "TFHD base-data-offset not allowed by MSE"
 *
 * This patcher rewrites each affected `moof → traf → tfhd` box in a segment's bytes to:
 *   1. Remove the 8-byte `base-data-offset` field.
 *   2. Clear the `base-data-offset-present` flag  (0x000001).
 *   3. Set  the `default-base-is-moof`        flag  (0x020000).
 *   4. Fix `trun.data_offset` so it remains relative to the start of the moof box.
 *   5. Update box sizes: tfhd -8, traf -8, moof -8.
 *
 * The output byte stream is 8 bytes shorter per patched moof box.  Content length and
 * all other fields are preserved unchanged.
 */
internal object MseTfhdPatcher {

    // ── Public API ────────────────────────────────────────────────────────────────────────────────

    /**
     * @param segmentBytes  Raw bytes of the `.m4s` segment as read from the fMP4 file.
     * @param segmentFileOffset  The byte offset of the first byte of this segment within the
     *   source file on disk (i.e. the start of the `moof` box in file-absolute terms).
     * @return Patched byte array (new allocation), or [segmentBytes] unchanged if no patch
     *   was needed (no `tfhd` with `base-data-offset` was found).
     */
    fun patch(segmentBytes: ByteArray, segmentFileOffset: Long): ByteArray {
        val edit = findEdit(segmentBytes, segmentFileOffset) ?: return segmentBytes
        return applyEdit(segmentBytes, edit)
    }

    // ── Internal data ─────────────────────────────────────────────────────────────────────────────

    /**
     * Describes the single edit needed: remove 8 bytes at [removeAt], and overwrite a handful
     * of integer fields at positions computed relative to the *output* buffer.
     */
    private data class PatchEdit(
        /** Start of the 8-byte base-data-offset field to remove (input-relative position). */
        val removeAt: Int,

        /** Position in the *input* where the 3-byte tfhd flags field lives. */
        val tfhdFlagsPos: Int,
        /** New 24-bit flags value for tfhd. */
        val newTfhdFlags: Int,

        /** Position in the *input* where the 4-byte tfhd box size lives. */
        val tfhdSizePos: Int,
        /** New tfhd box size (original minus 8). */
        val newTfhdSize: Int,

        /** Position in the *input* where the 4-byte traf box size lives. */
        val trafSizePos: Int,
        /** New traf box size (original minus 8). */
        val newTrafSize: Int,

        /** Position in the *input* where the 4-byte moof box size lives. */
        val moofSizePos: Int,
        /** New moof box size (original minus 8). */
        val newMoofSize: Int,

        /** Position in the *input* of the trun data-offset field, -1 if absent. */
        val trunDataOffsetPos: Int,
        /** New signed 32-bit trun.data_offset value (ignored if [trunDataOffsetPos] == -1). */
        val newTrunDataOffset: Int,
    )

    // ── Edit discovery ────────────────────────────────────────────────────────────────────────────

    private fun findEdit(input: ByteArray, segmentFileOffset: Long): PatchEdit? {
        // Walk top-level boxes to find the first 'moof'.
        var pos = 0
        while (pos + 8 <= input.size) {
            val boxSize = readU32(input, pos)
            val boxType = readType(input, pos + 4)
            if (boxType == "moof") {
                return buildEdit(input, moofStart = pos, moofSize = boxSize.toInt(), segmentFileOffset)
            }
            if (boxSize < 8L) break
            pos += boxSize.toInt()
        }
        return null
    }

    private fun buildEdit(
        input: ByteArray,
        moofStart: Int,
        moofSize: Int,
        segmentFileOffset: Long,
    ): PatchEdit? {
        val moofEnd = moofStart + moofSize

        // Find 'traf' inside 'moof'.
        val trafStart = findFirstChild(input, moofStart + 8, moofEnd, "traf") ?: return null
        val trafSize = readU32(input, trafStart).toInt()
        val trafEnd = trafStart + trafSize

        // Find 'tfhd' inside 'traf'.
        val tfhdStart = findFirstChild(input, trafStart + 8, trafEnd, "tfhd") ?: return null
        val tfhdSize = readU32(input, tfhdStart).toInt()

        // Check flags.
        val tfhdFlags = readU24(input, tfhdStart + 9)
        if (tfhdFlags and 0x000001 == 0) return null  // no base-data-offset → nothing to do

        // Read the 8-byte absolute base-data-offset.
        val baseDataOffset = readU64(input, tfhdStart + 16)

        // The base-data-offset field starts right after the full-box header (12 bytes) + track_ID (4 bytes).
        // full-box header = size(4) + type(4) + version(1) + flags(3) = 12
        // track_ID = 4
        // → base-data-offset at tfhdStart + 16
        val removeAt = tfhdStart + 16

        // Find 'trun' inside 'traf' (may follow tfhd or tfdt).
        val trunStart = findFirstChild(input, trafStart + 8, trafEnd, "trun")
        var trunDataOffsetPos = -1
        var trunDataOffset = 0
        if (trunStart != null) {
            val trunFlags = readU24(input, trunStart + 9)
            if (trunFlags and 0x000001 != 0) {
                // trun data-offset field is at: size(4)+type(4)+version(1)+flags(3)+sample_count(4) = offset 16
                trunDataOffsetPos = trunStart + 16
                trunDataOffset = readS32(input, trunDataOffsetPos)
            }
        }

        // Compute new trun.data_offset.
        //
        // With original addressing:
        //   firstSamplePos (in segment bytes) = (baseDataOffset − segmentFileOffset) + trunDataOffset
        //
        // After we remove 8 bytes from the tfhd body, everything that was at position P in the
        // input is at position P−8 in the output (for P ≥ removeAt).  So the mdat box (which
        // follows the moof) shifts 8 bytes earlier in the output stream.
        //
        //   newFirstSamplePos = firstSamplePos − 8
        //
        // With default-base-is-moof the base is the start of the moof box = position 0 in the
        // segment (moof is always the first box in a media segment).
        //
        //   newTrunDataOffset = newFirstSamplePos − 0 = (baseDataOffset − segmentFileOffset + trunDataOffset) − 8
        val firstSamplePos = (baseDataOffset - segmentFileOffset) + trunDataOffset
        val newTrunDataOffset = (firstSamplePos - 8L).toInt()

        // New tfhd flags: clear 0x000001 (base-data-offset-present), set 0x020000 (default-base-is-moof).
        val newTfhdFlags = (tfhdFlags and 0x00FFFFFE) or 0x020000

        return PatchEdit(
            removeAt = removeAt,
            tfhdFlagsPos = tfhdStart + 9,
            newTfhdFlags = newTfhdFlags,
            tfhdSizePos = tfhdStart,
            newTfhdSize = tfhdSize - 8,
            trafSizePos = trafStart,
            newTrafSize = trafSize - 8,
            moofSizePos = moofStart,
            newMoofSize = moofSize - 8,
            trunDataOffsetPos = trunDataOffsetPos,
            newTrunDataOffset = newTrunDataOffset,
        )
    }

    // ── Edit application ──────────────────────────────────────────────────────────────────────────

    private fun applyEdit(input: ByteArray, edit: PatchEdit): ByteArray {
        val out = ByteArray(input.size - 8)

        // Copy bytes before the removal point.
        System.arraycopy(input, 0, out, 0, edit.removeAt)
        // Copy bytes after the 8-byte field we are removing.
        System.arraycopy(input, edit.removeAt + 8, out, edit.removeAt, input.size - edit.removeAt - 8)

        // Positions of fields that are BEFORE removeAt are unchanged in the output.
        // Positions of fields that are AFTER  removeAt shift by −8 in the output.

        fun outPos(inputPos: Int) = if (inputPos < edit.removeAt) inputPos else inputPos - 8

        // Fix box sizes.
        writeU32(out, outPos(edit.moofSizePos), edit.newMoofSize)
        writeU32(out, outPos(edit.trafSizePos), edit.newTrafSize)
        writeU32(out, outPos(edit.tfhdSizePos), edit.newTfhdSize)

        // Fix tfhd flags.
        writeU24(out, outPos(edit.tfhdFlagsPos), edit.newTfhdFlags)

        // Fix trun data-offset (the trun box comes after tfhd, so its position shifts).
        if (edit.trunDataOffsetPos >= 0) {
            writeS32(out, outPos(edit.trunDataOffsetPos), edit.newTrunDataOffset)
        }

        return out
    }

    // ── Box walking helpers ───────────────────────────────────────────────────────────────────────

    /** Returns the start position of the first child box with [type], or null. */
    private fun findFirstChild(buf: ByteArray, from: Int, to: Int, type: String): Int? {
        var pos = from
        while (pos + 8 <= to) {
            val sz = readU32(buf, pos).toInt()
            if (readType(buf, pos + 4) == type) return pos
            if (sz < 8) break
            pos += sz
        }
        return null
    }

    private fun readType(buf: ByteArray, at: Int) = String(buf, at, 4, Charsets.US_ASCII)

    // ── Byte-level I/O ────────────────────────────────────────────────────────────────────────────

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
}
