package io.github.pgodlews.wormhole

/**
 * Diagnostic description of the sender's parameter sets: what the stream says about itself (profile, bit depth,
 * size, colour range, primaries and transfer function) before any decoder interprets it.
 *
 * The core prepends parameter sets to the picture that follows a parameter packet, so only packets that start with
 * one are examined; ordinary pictures cost one header check. The raw bytes are always reported next to the parsed
 * summary, so a field this parser gets wrong can still be decoded from the log.
 */
internal class StreamConfigInspector {
    /**
     * Off unless the dashboard's debug switch is on. While off nothing is parsed or remembered, so the first
     * parameter packet after switching on is reported in full.
     */
    @Volatile var enabled = false
    private var lastConfig: ByteArray? = null
    private var reports = 0

    fun reset() {
        lastConfig = null
        reports = 0
    }

    /** Log lines for a packet that carries parameter sets, or null for any other packet. */
    fun inspect(data: ByteArray, codec: VideoCodec): List<String>? {
        if (!enabled) {
            if (lastConfig != null) reset()
            return null
        }
        val first = payloadStart(data, 0) ?: return null
        if (first >= data.size || !isParameterSet(codec, nalType(codec, data[first]))) return null

        // The sender controls these bytes. A real parameter packet holds a handful of units; stop there, so a
        // crafted packet of thousands cannot make this scan, or the log, grow with its size.
        val units = ArrayList<Nal>()
        var pos = 0
        var truncated = false
        while (true) {
            val start = payloadStart(data, pos) ?: break
            if (units.size == MAX_UNITS) { truncated = true; break }
            val next = startCode(data, start)
            val end = if (next < 0) data.size else next
            if (start < end) units += Nal(nalType(codec, data[start]), start, end)
            if (next < 0) break
            pos = next
        }

        val config = units.filter { isParameterSet(codec, it.type) }
        var configSize = 0
        for (unit in config) configSize += unit.end - unit.start + 1
        val joined = ByteArray(configSize)
        var at = 0
        for (unit in config) {
            joined[at++] = unit.type.toByte()
            data.copyInto(joined, at, unit.start, unit.end)
            at += unit.end - unit.start
        }
        val state = when {
            lastConfig == null -> "first"
            joined.contentEquals(lastConfig) -> "repeated"
            else -> "changed"
        }
        lastConfig = joined

        val tag = "$PREFIX stream-config #${++reports}"
        val listed = units.joinToString(",") { "${nalName(codec, it.type)}:${it.end - it.start}" }
        val more = if (truncated) ",+more" else ""
        val lines = ArrayList<String>()
        lines += "$tag ${codecName(codec)} $state nals=$listed$more bytes=${data.size}"
        if (state == "repeated") return lines

        for (unit in units) {
            val name = nalName(codec, unit.type)
            if (isSps(codec, unit.type)) {
                val nal = data.copyOfRange(unit.start, unit.end)
                lines += "$tag summary: " + if (codec == VideoCodec.HEVC) describeHevcSps(nal) else describeAvcSps(nal)
            }
            if (isParameterSet(codec, unit.type) || isSei(codec, unit.type)) {
                lines += "$tag ${name.lowercase()}=${hex(data, unit.start, unit.end)}"
            }
        }
        return lines
    }

    private class Nal(val type: Int, val start: Int, val end: Int)

    /** MSB-first reader over a NAL payload with emulation-prevention bytes removed. */
    private class BitReader(nal: ByteArray, from: Int) {
        private val rbsp: ByteArray
        private val bitCount: Int
        private var pos = 0

        init {
            val out = ByteArray((nal.size - from).coerceAtLeast(0))
            var n = 0
            var zeros = 0
            for (i in from until nal.size) {
                val b = nal[i].toInt() and 0xFF
                if (zeros >= 2 && b == 3) { zeros = 0; continue }
                zeros = if (b == 0) zeros + 1 else 0
                out[n++] = b.toByte()
            }
            rbsp = out
            bitCount = n * 8
        }

        fun bit(): Int {
            if (pos >= bitCount) throw IllegalStateException("truncated")
            val value = (rbsp[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return value
        }

        fun flag(): Boolean = bit() == 1

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }

        fun skip(count: Int) {
            if (count < 0 || pos + count > bitCount) throw IllegalStateException("truncated")
            pos += count
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                if (++zeros > 30) throw IllegalStateException("exp-golomb overflow")
            }
            return (1 shl zeros) - 1 + bits(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    internal companion object {
        /** Marks every line added for diagnostics, in Kotlin and in the JNI shim: `grep -F '[diag]'`. */
        const val PREFIX = "[diag]"
        private const val MAX_UNITS = 12
        private const val MAX_HEX_BYTES = 512
        private val HEX = "0123456789abcdef".toCharArray()

        fun describeAvcSps(nal: ByteArray): String {
            val out = StringBuilder()
            try {
                val r = BitReader(nal, 1)
                val profile = r.bits(8)
                r.skip(8)
                val level = r.bits(8)
                r.ue() // seq_parameter_set_id
                var chroma = 1
                var depthLuma = 8
                var depthChroma = 8
                if (profile in AVC_HIGH_PROFILES) {
                    chroma = r.ue()
                    if (chroma == 3) r.skip(1)
                    depthLuma = r.ue() + 8
                    depthChroma = r.ue() + 8
                    r.skip(1) // qpprime_y_zero_transform_bypass_flag
                    if (r.flag()) {
                        for (i in 0 until if (chroma != 3) 8 else 12) {
                            if (r.flag()) skipAvcScalingList(r, if (i < 6) 16 else 64)
                        }
                    }
                }
                out.append("profile=").append(profile).append('(').append(avcProfileName(profile)).append(')')
                    .append(" level=").append(level / 10).append('.').append(level % 10)
                    .append(" chroma=").append(chromaName(chroma))
                    .append(" bitDepth=").append(depthLuma)
                if (depthChroma != depthLuma) out.append('/').append(depthChroma)
                r.ue() // log2_max_frame_num_minus4
                when (r.ue()) { // pic_order_cnt_type
                    0 -> r.ue()
                    1 -> {
                        r.skip(1)
                        r.se()
                        r.se()
                        repeat(r.ue().coerceAtMost(255)) { r.se() }
                    }
                }
                r.ue() // max_num_ref_frames
                r.skip(1)
                val widthMbs = r.ue() + 1
                val heightUnits = r.ue() + 1
                val frameMbsOnly = r.flag()
                if (!frameMbsOnly) r.skip(1)
                r.skip(1) // direct_8x8_inference_flag
                var cropX = 0
                var cropY = 0
                if (r.flag()) {
                    val left = r.ue()
                    val right = r.ue()
                    val top = r.ue()
                    val bottom = r.ue()
                    val fieldFactor = if (frameMbsOnly) 1 else 2
                    cropX = (left + right) * if (chroma == 1 || chroma == 2) 2 else 1
                    cropY = (top + bottom) * fieldFactor * if (chroma == 1) 2 else 1
                }
                val height = heightUnits * 16 * (if (frameMbsOnly) 1 else 2) - cropY
                out.append(" size=").append(widthMbs * 16 - cropX).append('x').append(height)
                out.append(describeVui(r, depthLuma))
            } catch (e: RuntimeException) {
                out.append(" (parse stopped: ").append(e.message).append(')')
            }
            return out.toString().trim()
        }

        fun describeHevcSps(nal: ByteArray): String {
            val out = StringBuilder()
            try {
                val r = BitReader(nal, 2)
                r.skip(4) // sps_video_parameter_set_id
                val maxSubLayers = r.bits(3)
                r.skip(1)
                r.skip(3) // general_profile_space, general_tier_flag
                val profile = r.bits(5)
                r.skip(32 + 4 + 44)
                val level = r.bits(8)
                val subProfile = BooleanArray(maxSubLayers)
                val subLevel = BooleanArray(maxSubLayers)
                for (i in 0 until maxSubLayers) {
                    subProfile[i] = r.flag()
                    subLevel[i] = r.flag()
                }
                if (maxSubLayers > 0) r.skip(2 * (8 - maxSubLayers))
                for (i in 0 until maxSubLayers) {
                    if (subProfile[i]) r.skip(88)
                    if (subLevel[i]) r.skip(8)
                }
                r.ue() // sps_seq_parameter_set_id
                val chroma = r.ue()
                if (chroma == 3) r.skip(1)
                var width = r.ue()
                var height = r.ue()
                if (r.flag()) {
                    val left = r.ue()
                    val right = r.ue()
                    val top = r.ue()
                    val bottom = r.ue()
                    width -= (left + right) * if (chroma == 1 || chroma == 2) 2 else 1
                    height -= (top + bottom) * if (chroma == 1) 2 else 1
                }
                val depthLuma = r.ue() + 8
                val depthChroma = r.ue() + 8
                out.append("profile=").append(profile).append('(').append(hevcProfileName(profile)).append(')')
                    .append(" level=").append(level / 30).append('.').append(level % 30 / 3)
                    .append(" chroma=").append(chromaName(chroma))
                    .append(" bitDepth=").append(depthLuma)
                if (depthChroma != depthLuma) out.append('/').append(depthChroma)
                out.append(" size=").append(width).append('x').append(height)

                val pocBits = r.ue() + 4
                val orderingInfoPresent = r.flag()
                for (i in (if (orderingInfoPresent) 0 else maxSubLayers)..maxSubLayers) {
                    r.ue()
                    r.ue()
                    r.ue()
                }
                repeat(6) { r.ue() } // coding and transform block sizes, transform hierarchy depths
                if (r.flag() && r.flag()) skipHevcScalingLists(r)
                r.skip(2) // amp_enabled_flag, sample_adaptive_offset_enabled_flag
                if (r.flag()) { // pcm_enabled_flag
                    r.skip(8)
                    r.ue()
                    r.ue()
                    r.skip(1)
                }
                val shortTermSets = r.ue()
                if (shortTermSets > 64) throw IllegalStateException("bad short-term RPS count")
                val deltaPocs = IntArray(shortTermSets)
                for (i in 0 until shortTermSets) {
                    if (i != 0 && r.flag()) { // inter_ref_pic_set_prediction_flag
                        r.skip(1)
                        r.ue()
                        var count = 0
                        for (j in 0..deltaPocs[i - 1]) {
                            val used = r.flag()
                            val useDelta = if (used) true else r.flag()
                            if (useDelta) count++
                        }
                        deltaPocs[i] = count
                    } else {
                        val negative = r.ue()
                        val positive = r.ue()
                        if (negative > 16 || positive > 16) throw IllegalStateException("bad short-term RPS")
                        repeat(negative + positive) {
                            r.ue()
                            r.skip(1)
                        }
                        deltaPocs[i] = negative + positive
                    }
                }
                if (r.flag()) { // long_term_ref_pics_present_flag
                    repeat(r.ue().coerceAtMost(32)) { r.skip(pocBits + 1) }
                }
                r.skip(2) // sps_temporal_mvp_enabled_flag, strong_intra_smoothing_enabled_flag
                out.append(describeVui(r, depthLuma))
            } catch (e: RuntimeException) {
                out.append(" (parse stopped: ").append(e.message).append(')')
            }
            return out.toString().trim()
        }

        /** The leading VUI fields are laid out identically in H.264 and HEVC. */
        private fun describeVui(r: BitReader, bitDepth: Int): String {
            if (!r.flag()) return " vui=absent" + hdrVerdict(bitDepth, -1, -1)
            if (r.flag() && r.bits(8) == 255) r.skip(32) // aspect_ratio_idc, extended SAR
            if (r.flag()) r.skip(1) // overscan
            if (!r.flag()) return " vui: video-signal-type=absent" + hdrVerdict(bitDepth, -1, -1)
            r.skip(3) // video_format
            val out = StringBuilder(" vui: range=").append(if (r.flag()) "full" else "limited")
            if (!r.flag()) return out.append(" colour-description=absent").append(hdrVerdict(bitDepth, -1, -1)).toString()
            val primaries = r.bits(8)
            val transfer = r.bits(8)
            val matrix = r.bits(8)
            return out.append(" primaries=").append(primaries).append('(').append(primariesName(primaries)).append(')')
                .append(" transfer=").append(transfer).append('(').append(transferName(transfer)).append(')')
                .append(" matrix=").append(matrix).append('(').append(matrixName(matrix)).append(')')
                .append(hdrVerdict(bitDepth, primaries, transfer)).toString()
        }

        private fun hdrVerdict(bitDepth: Int, primaries: Int, transfer: Int): String = when {
            transfer == 16 || transfer == 18 -> " => HDR"
            primaries == 9 || primaries == 11 || primaries == 12 -> " => WIDE-GAMUT"
            bitDepth > 8 -> " => 10-BIT"
            else -> " => SDR"
        }

        private fun skipAvcScalingList(r: BitReader, size: Int) {
            var last = 8
            var next = 8
            repeat(size) {
                if (next != 0) next = (last + r.se() + 256) % 256
                if (next != 0) last = next
            }
        }

        private fun skipHevcScalingLists(r: BitReader) {
            for (sizeId in 0..3) {
                var matrixId = 0
                while (matrixId < 6) {
                    if (!r.flag()) {
                        r.ue()
                    } else {
                        if (sizeId > 1) r.se()
                        repeat(minOf(64, 1 shl (4 + (sizeId shl 1)))) { r.se() }
                    }
                    matrixId += if (sizeId == 3) 3 else 1
                }
            }
        }

        private val AVC_HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

        private fun avcProfileName(profile: Int) = when (profile) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High10"
            122 -> "High4:2:2"
            244 -> "High4:4:4"
            else -> "?"
        }

        private fun hevcProfileName(profile: Int) = when (profile) {
            1 -> "Main"
            2 -> "Main10"
            3 -> "MainStillPicture"
            4 -> "RangeExtensions"
            else -> "?"
        }

        private fun chromaName(chroma: Int) = when (chroma) {
            0 -> "4:0:0"
            1 -> "4:2:0"
            2 -> "4:2:2"
            3 -> "4:4:4"
            else -> "?"
        }

        private fun primariesName(value: Int) = when (value) {
            1 -> "BT.709"
            2 -> "unspecified"
            5 -> "BT.601-625"
            6 -> "BT.601-525"
            9 -> "BT.2020"
            11 -> "DCI-P3"
            12 -> "Display-P3"
            else -> "?"
        }

        private fun transferName(value: Int) = when (value) {
            1 -> "BT.709"
            2 -> "unspecified"
            6 -> "BT.601"
            13 -> "sRGB"
            14 -> "BT.2020-10bit"
            15 -> "BT.2020-12bit"
            16 -> "PQ"
            18 -> "HLG"
            else -> "?"
        }

        private fun matrixName(value: Int) = when (value) {
            0 -> "identity"
            1 -> "BT.709"
            2 -> "unspecified"
            5 -> "BT.601-625"
            6 -> "BT.601-525"
            9 -> "BT.2020-NCL"
            else -> "?"
        }

        private fun codecName(codec: VideoCodec) = if (codec == VideoCodec.HEVC) "H.265" else "H.264"

        private fun nalType(codec: VideoCodec, header: Byte): Int =
            if (codec == VideoCodec.HEVC) (header.toInt() ushr 1) and 63 else header.toInt() and 31

        private fun isSps(codec: VideoCodec, type: Int) = type == if (codec == VideoCodec.HEVC) 33 else 7

        private fun isSei(codec: VideoCodec, type: Int) = if (codec == VideoCodec.HEVC) type == 39 || type == 40 else type == 6

        private fun isParameterSet(codec: VideoCodec, type: Int) =
            if (codec == VideoCodec.HEVC) type in 32..34 else type == 7 || type == 8

        private fun nalName(codec: VideoCodec, type: Int): String = if (codec == VideoCodec.HEVC) when (type) {
            32 -> "VPS"
            33 -> "SPS"
            34 -> "PPS"
            35 -> "AUD"
            39, 40 -> "SEI"
            19, 20 -> "IDR"
            21 -> "CRA"
            in 16..18 -> "BLA"
            in 0..9 -> "slice"
            else -> "nal$type"
        } else when (type) {
            1 -> "slice"
            5 -> "IDR"
            6 -> "SEI"
            7 -> "SPS"
            8 -> "PPS"
            9 -> "AUD"
            else -> "nal$type"
        }

        /** Index of the NAL header that follows a start code located exactly at [at], or null. */
        private fun payloadStart(data: ByteArray, at: Int): Int? {
            if (at < 0 || at + 2 >= data.size || data[at].toInt() != 0 || data[at + 1].toInt() != 0) return null
            if (data[at + 2].toInt() == 1) return at + 3
            if (at + 3 < data.size && data[at + 2].toInt() == 0 && data[at + 3].toInt() == 1) return at + 4
            return null
        }

        private fun startCode(data: ByteArray, from: Int): Int {
            for (i in from until data.size - 2) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                    (data[i + 2].toInt() == 1 ||
                     (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))) return i
            }
            return -1
        }

        private fun hex(data: ByteArray, from: Int, to: Int): String {
            val end = minOf(to, from + MAX_HEX_BYTES)
            val out = StringBuilder((end - from) * 2 + 16)
            for (i in from until end) {
                val b = data[i].toInt() and 0xFF
                out.append(HEX[b ushr 4]).append(HEX[b and 15])
            }
            if (end < to) out.append("…(+").append(to - end).append(" bytes)")
            return out.toString()
        }
    }
}
