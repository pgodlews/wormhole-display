package io.github.pgodlews.wormhole

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/** Only advertise optional HEVC when a hardware decoder covers the requested display mode. */
internal object VideoDecoderSupport {
    data class Hevc(val name: String, val width: Int, val height: Int)

    fun findHevc(width: Int, height: Int, fps: Int): Hevc? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                // API 28 has no hardware flag; only opt in the known Qualcomm decoder.
                (if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
                 else info.name == "OMX.qcom.video.decoder.hevc") &&
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities
                        .areSizeAndRateSupported(width, height, fps.toDouble())
                }.getOrDefault(false)
        }?.let { Hevc(it.name, width, height) }
    }.onFailure { Log.w("Wormhole", "HEVC capability query failed", it) }.getOrNull()

    /** Diagnostics: every AVC/HEVC decoder on the device with its profiles (HEVC 2 = Main10, 4096 = Main10 HDR10). */
    fun describeDecoders(): List<String> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { info ->
            info.supportedTypes.filter {
                it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) || it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)
            }.map { type ->
                val profiles = runCatching {
                    info.getCapabilitiesForType(type).profileLevels.map { it.profile }.distinct().sorted()
                }.getOrDefault(emptyList())
                "${info.name} $type profiles=$profiles"
            }
        }
    }.getOrDefault(emptyList())
}
