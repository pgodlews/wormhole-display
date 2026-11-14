package io.github.pgodlews.wormhole

import org.junit.Assert.*
import org.junit.Test

/**
 * Parameter sets below were produced by x264/x265 with known colour tags and checked against ffmpeg's
 * trace_headers. [HEVC_SYNTHETIC_SPS] is hand-built to cover scaling lists, PCM, inter-predicted reference picture
 * sets, long-term references and an extended sample aspect ratio; ffmpeg reads the same values from it.
 */
class StreamConfigInspectorTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun annexB(vararg nals: String) = nals.fold(byteArrayOf()) { all, nal -> all + byteArrayOf(0, 0, 0, 1) + bytes(nal) }

    @Test fun `H264 full range BT709 portrait stream`() {
        assertEquals(
            "profile=100(High) level=4.0 chroma=4:2:0 bitDepth=8 size=886x1920 " +
                "vui: range=full primaries=1(BT.709) transfer=1(BT.709) matrix=1(BT.709) => SDR",
            StreamConfigInspector.describeAvcSps(bytes(AVC_FULL_709_SPS))
        )
    }

    @Test fun `H264 stream tagged HLG is reported as HDR`() {
        assertEquals(
            "profile=100(High) level=3.1 chroma=4:2:0 bitDepth=8 size=1280x720 " +
                "vui: range=limited primaries=9(BT.2020) transfer=18(HLG) matrix=9(BT.2020-NCL) => HDR",
            StreamConfigInspector.describeAvcSps(bytes(AVC_LIMITED_HLG_SPS))
        )
    }

    @Test fun `H264 baseline profile skips the high profile fields`() {
        assertEquals(
            "profile=66(Baseline) level=3.0 chroma=4:2:0 bitDepth=8 size=640x360 " +
                "vui: range=limited primaries=1(BT.709) transfer=1(BT.709) matrix=1(BT.709) => SDR",
            StreamConfigInspector.describeAvcSps(bytes(AVC_BASELINE_SPS))
        )
    }

    @Test fun `H264 without a colour description says so`() {
        assertEquals(
            "profile=100(High) level=4.0 chroma=4:2:0 bitDepth=8 size=1920x1080 " +
                "vui: range=limited colour-description=absent => SDR",
            StreamConfigInspector.describeAvcSps(bytes(AVC_NO_COLOUR_SPS))
        )
    }

    @Test fun `HEVC Main10 HLG stream`() {
        assertEquals(
            "profile=2(Main10) level=4.0 chroma=4:2:0 bitDepth=10 size=1080x1920 " +
                "vui: range=limited primaries=9(BT.2020) transfer=18(HLG) matrix=9(BT.2020-NCL) => HDR",
            StreamConfigInspector.describeHevcSps(bytes(HEVC_MAIN10_HLG_SPS))
        )
    }

    @Test fun `HEVC Main10 PQ stream with a conformance window`() {
        assertEquals(
            "profile=2(Main10) level=5.0 chroma=4:2:0 bitDepth=10 size=1166x2532 " +
                "vui: range=limited primaries=9(BT.2020) transfer=16(PQ) matrix=9(BT.2020-NCL) => HDR",
            StreamConfigInspector.describeHevcSps(bytes(HEVC_MAIN10_PQ_SPS))
        )
    }

    @Test fun `HEVC Main full range BT709 stream`() {
        assertEquals(
            "profile=1(Main) level=4.0 chroma=4:2:0 bitDepth=8 size=1920x1080 " +
                "vui: range=full primaries=1(BT.709) transfer=1(BT.709) matrix=1(BT.709) => SDR",
            StreamConfigInspector.describeHevcSps(bytes(HEVC_MAIN_FULL_709_SPS))
        )
    }

    @Test fun `HEVC colour description is found behind scaling lists and reference picture sets`() {
        assertEquals(
            "profile=2(Main10) level=4.1 chroma=4:2:0 bitDepth=10 size=1920x1080 " +
                "vui: range=full primaries=9(BT.2020) transfer=18(HLG) matrix=9(BT.2020-NCL) => HDR",
            StreamConfigInspector.describeHevcSps(bytes(HEVC_SYNTHETIC_SPS))
        )
    }

    @Test fun `truncated parameter sets are described as far as they go`() {
        val avc = StreamConfigInspector.describeAvcSps(bytes(AVC_FULL_709_SPS).copyOf(12))
        assertTrue(avc, avc.startsWith("profile=100(High) level=4.0"))
        assertTrue(avc, avc.endsWith("(parse stopped: truncated)"))
        val hevc = StreamConfigInspector.describeHevcSps(bytes(HEVC_MAIN10_HLG_SPS).copyOf(30))
        assertTrue(hevc, hevc.startsWith("profile=2(Main10) level=4.0 chroma=4:2:0 bitDepth=10 size=1080x1920"))
        assertTrue(hevc, hevc.endsWith("(parse stopped: truncated)"))
        assertEquals("(parse stopped: truncated)", StreamConfigInspector.describeAvcSps(byteArrayOf(0x67)))
        assertEquals("(parse stopped: truncated)", StreamConfigInspector.describeHevcSps(byteArrayOf()))
    }

    @Test fun `ordinary pictures are not inspected`() {
        val inspector = StreamConfigInspector()
        assertNull(inspector.inspect(annexB("65888000"), VideoCodec.AVC))
        assertNull(inspector.inspect(annexB("419a00"), VideoCodec.AVC))
        assertNull(inspector.inspect(annexB("2601af"), VideoCodec.HEVC))
        assertNull(inspector.inspect(byteArrayOf(), VideoCodec.AVC))
        assertNull(inspector.inspect(byteArrayOf(0, 0, 0, 1), VideoCodec.AVC))
        assertNull(inspector.inspect(byteArrayOf(1, 2, 3, 4, 5), VideoCodec.AVC))
    }

    @Test fun `H264 parameter packet reports raw sets once and notes repeats and changes`() {
        val inspector = StreamConfigInspector()
        val packet = annexB(AVC_FULL_709_SPS, AVC_PPS, "0605ffaa80", "65888000")

        val first = inspector.inspect(packet, VideoCodec.AVC)!!
        assertEquals("[diag] stream-config #1 H.264 first nals=SPS:29,PPS:6,SEI:5,IDR:4 bytes=60", first[0])
        assertTrue(first[1], first[1].startsWith("[diag] stream-config #1 summary: profile=100(High)"))
        assertEquals("[diag] stream-config #1 sps=$AVC_FULL_709_SPS", first[2])
        assertEquals("[diag] stream-config #1 pps=$AVC_PPS", first[3])
        assertEquals("[diag] stream-config #1 sei=0605ffaa80", first[4])
        assertEquals(5, first.size)

        assertEquals(
            listOf("[diag] stream-config #2 H.264 repeated nals=SPS:29,PPS:6,IDR:4 bytes=51"),
            inspector.inspect(annexB(AVC_FULL_709_SPS, AVC_PPS, "65888000"), VideoCodec.AVC)
        )

        val changed = inspector.inspect(annexB(AVC_LIMITED_HLG_SPS, AVC_PPS, "65888000"), VideoCodec.AVC)!!
        assertTrue(changed[0], changed[0].startsWith("[diag] stream-config #3 H.264 changed "))
        assertTrue(changed[1], changed[1].endsWith("=> HDR"))

        inspector.reset()
        assertTrue(inspector.inspect(packet, VideoCodec.AVC)!![0].startsWith("[diag] stream-config #1 H.264 first "))
    }

    @Test fun `HEVC parameter packet reports VPS SPS and PPS`() {
        val lines = StreamConfigInspector().inspect(
            annexB(HEVC_VPS, HEVC_MAIN10_HLG_SPS, HEVC_PPS, "2601af"), VideoCodec.HEVC
        )!!
        assertEquals("[diag] stream-config #1 H.265 first nals=VPS:24,SPS:47,PPS:7,IDR:3 bytes=97", lines[0])
        assertEquals("[diag] stream-config #1 vps=$HEVC_VPS", lines[1])
        assertTrue(lines[2], lines[2].startsWith("[diag] stream-config #1 summary: profile=2(Main10)"))
        assertEquals("[diag] stream-config #1 sps=$HEVC_MAIN10_HLG_SPS", lines[3])
        assertEquals("[diag] stream-config #1 pps=$HEVC_PPS", lines[4])
    }

    @Test fun `three byte start codes and long units are handled`() {
        val sps = bytes(AVC_FULL_709_SPS)
        val packet = byteArrayOf(0, 0, 1) + sps + byteArrayOf(0, 0, 1) + bytes(AVC_PPS) +
            byteArrayOf(0, 0, 1, 6) + ByteArray(600) { 0x55 } + byteArrayOf(0, 0, 1, 0x65, 1)
        val lines = StreamConfigInspector().inspect(packet, VideoCodec.AVC)!!
        assertTrue(lines[0], lines[0].contains("nals=SPS:29,PPS:6,SEI:601,IDR:2 "))
        assertTrue(lines.last(), lines.last().endsWith("…(+89 bytes)"))
    }

    private companion object {
        const val AVC_FULL_709_SPS = "67640028acd940e00f1e6f016e02020280000003008000001e078c18cb"
        const val AVC_LIMITED_HLG_SPS = "6764001facd9405005bb016a12241280000003008000001e078c18cb"
        const val AVC_BASELINE_SPS = "6742c01ed900a02ff97016a0202028000003000800000301e078b17240"
        const val AVC_NO_COLOUR_SPS = "67640028acd940780227e5c05a20000003002000000781e30632c0"
        const val AVC_PPS = "68ebe3cb22c0"
        const val HEVC_VPS = "40010c01ffff022000000300900000030000030078959809"
        const val HEVC_PPS = "4401c172b46240"
        const val HEVC_MAIN10_HLG_SPS =
            "420101022000000300900000030000030078a0021c801e04d96566924caf016a12241208000003000800000300f040"
        const val HEVC_MAIN10_PQ_SPS =
            "420101022000000300900000030000030096a002488009e9d5b65959a49395e02d424402410000030001000003001e08"
        const val HEVC_MAIN_FULL_709_SPS =
            "420101016000000300900000030000030078a003c08010e596566924caf016e020202080000003008000000f04"
        const val HEVC_SYNTHETIC_SPS =
            "42010102200000030090000003000003007ba003c0801107cad96579244b99d10ae8857442cce8857442ba214a67442ba215d10a" +
            "533a215d10ae88599d10ae8857442ba215d10ae8857442ba215d10ae8857442ba215d14ce8857442ba215d10ae8857442ba215d" +
            "10ae8857442ba215d10ae8a67442ba215d10ae8857442ba215d10ae8857442ba215d10ae8857451067442ba215d10ae8857442b" +
            "a215d10ae8857442ba215d10ae885751067442ba215d10ae8857442ba215d10ae8857442ba215d10ae8857451067442ba215d10" +
            "ae8857442ba215d10ae8857442ba215d10ae8857451067442ba215d10ae8857442ba215d10ae8857442ba215d10ae885751067" +
            "442ba215d10ae8857442ba215d10ae8857442ba215d10ae88574f77b91ad7f4cb3623c87ff80020001db848904804"
    }
}
