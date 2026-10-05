package io.github.pgodlews.wormhole

import java.util.Locale

/**
 * Track metadata and playback position of an AirPlay audio session, as reported by the
 * sender (DMAP metadata and "progress" SET_PARAMETER). Senders report progress only on
 * track changes, seeks and resumes, so the position is extrapolated from the last report
 * while audio frames are flowing and frozen on a flush (pause or seek).
 *
 * Times are monotonic milliseconds (`SystemClock.elapsedRealtime()` in the app).
 */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: Int = 0,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val sampledAtMillis: Long = 0L,
    val playing: Boolean = false
) {
    fun positionAt(nowMillis: Long): Double {
        val elapsed = if (playing) (nowMillis - sampledAtMillis).coerceAtLeast(0L) / 1000.0 else 0.0
        val position = positionSec + elapsed
        return if (durationSec > 0.0) position.coerceIn(0.0, durationSec) else position.coerceAtLeast(0.0)
    }

    fun withMetadata(title: String, artist: String, album: String, year: Int) =
        copy(title = title.trim(), artist = artist.trim(), album = album.trim(), year = year)

    /** Keeps the play/pause state: a seek while paused stays paused until audio flows again. */
    fun withProgress(positionSec: Double, durationSec: Double, nowMillis: Long) =
        copy(positionSec = positionSec, durationSec = durationSec, sampledAtMillis = nowMillis)

    fun paused(nowMillis: Long) =
        if (!playing) this else copy(positionSec = positionAt(nowMillis), sampledAtMillis = nowMillis, playing = false)

    fun resumed(nowMillis: Long) =
        if (playing) this else copy(sampledAtMillis = nowMillis, playing = true)

    companion object {
        /** "m:ss", or "h:mm:ss" from an hour up. */
        fun formatTime(seconds: Double): String {
            val total = seconds.coerceAtLeast(0.0).toLong()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
