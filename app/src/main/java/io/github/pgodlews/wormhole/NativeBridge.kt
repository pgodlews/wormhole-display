package io.github.pgodlews.wormhole

object NativeBridge {
    interface Listener {
        /** ingressNanos is monotonic; core NTP nanoseconds are diagnostic metadata only. */
        fun onVideoFrame(data: ByteArray, ingressNanos: Long, ntpLocalNanos: Long, ntpRemoteNanos: Long, hevc: Boolean)
        fun onAudioFrame(data: ByteArray, ntpTimestamp: Long, ct: Int) {}
        fun onAudioVolume(volume: Float) {}
        fun onAudioFlush() {}
        fun onClientConnected(name: String)

        /** Any control socket closed — diagnostic only, not a mirror-stop signal. */
        fun onClientDisconnected()

        /** Verified mirror-stream lifetime; owns renderer state. */
        fun onMirrorRunning(running: Boolean)

        /**
         * Verified audio-stream lifetime (the audio RTP thread), for both the audio of a
         * mirroring session and audio-only sessions (Music / iTunes, iOS audio-only).
         * [ct] is the negotiated AirPlay codec: [CT_ALAC] or [CT_AAC_ELD]. ALAC is decoded
         * natively; its frames reach [onAudioFrame] as 16-bit stereo PCM tagged [CT_PCM].
         */
        fun onAudioRunning(running: Boolean, ct: Int) {}

        /** Now-playing metadata from the RAOP stream (DMAP). Empty strings mean "absent". */
        fun onNowPlaying(title: String, artist: String, album: String, year: Int) {}

        /** Cover art bytes for the current track ([isPng] false = JPEG). */
        fun onCoverArt(data: ByteArray, isPng: Boolean) {}

        /** Playback position/duration in seconds (0 if unknown). */
        fun onProgress(positionSec: Double, durationSec: Double) {}

        /** Unexpected mirror failure; the controller resets the server outside native callbacks. */
        fun onStreamError()
    }

    /** Audio codec tags shared with wormhole_jni.c (AirPlay "ct" values; 0 = decoded PCM). */
    const val CT_PCM = 0
    const val CT_ALAC = 2
    const val CT_AAC_ELD = 8

    init { System.loadLibrary("wormhole") }

    external fun nativeSetListener(listener: Listener?)

    /** Starts the AirPlay server; returns the RTSP port (7000) or a negative error. */
    external fun nativeStart(
        keyFile: String,
        deviceId: String,
        serviceName: String,
        width: Int,
        height: Int,
        maxFps: Int,
        allowHevc: Boolean
    ): Int

    /** Hex ed25519 public key owned by the server; advertise it as pk=. */
    external fun nativePublicKey(): String?

    external fun nativeStop()
}
