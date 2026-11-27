package io.github.pgodlews.wormhole

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Shared presentation clock for a mirroring session.
 *
 * The core stamps every audio packet and every video frame with the sender's NTP time converted to this
 * device's CLOCK_REALTIME (`ntp_time_local`). Both streams are presented at that timestamp plus one shared
 * latency [latencyNs], so audio and video that the sender captured together are heard and seen together.
 *
 * The latency is the smallest value both pipelines can meet:
 *  - audio: how late its packets arrive relative to their timestamps ([Lateness]), plus the time from handing
 *    PCM to the AudioTrack until it leaves the speaker ([setAudioOutputLatency]);
 *  - video: how late its frames arrive, plus a decode budget.
 * It rises at once when a stream needs more, and falls only after it has been too high for a while.
 *
 * Audio is never stopped to reach that target. It starts as soon as it can and is brought onto the target
 * by playing a few percent faster or slower ([audioSpeed]); only audio that is hopelessly late is skipped.
 * While it converges it reports how far off it is ([setAudioError]) and video follows the audio as actually
 * heard, so lips stay in sync during the correction.
 *
 * Video follows audio only while audio is flowing. Without audio (switched off, or a sender that sends none)
 * video is shown as soon as it is decoded. The video offset slews at [VIDEO_SLEW_RATE] so a change never
 * freezes or skips the picture.
 *
 * All times are nanoseconds. "real" times are CLOCK_REALTIME, the domain of the core's timestamps.
 * Thread-safe: audio and video call in from their own threads.
 */
class AvSync {
    private val audioLateness = Lateness()
    private val videoLateness = Lateness()
    private var audioOutputNs = DEFAULT_AUDIO_OUTPUT_NS
    private var latency = UNSET
    private var lowerSince = UNSET
    private var lastAudioReal = UNSET
    private var audioError = 0L
    private var audioErrorAt = UNSET
    private var videoOffset = UNSET
    private var videoOffsetAt = UNSET
    private var videoStartReal = UNSET
    private var raises = 0L
    private var lowers = 0L

    /** Current shared latency (timestamp → presentation), or null before audio has arrived. */
    @get:Synchronized val latencyNs: Long? get() = latency.takeIf { it != UNSET }

    @Synchronized fun resetAudio() {
        audioLateness.clear()
        lastAudioReal = UNSET
        audioError = 0L
        audioErrorAt = UNSET
        resetLatency()
    }

    @Synchronized fun resetVideo() {
        videoLateness.clear()
        videoOffset = UNSET
        videoOffsetAt = UNSET
        videoStartReal = UNSET
        resetLatency()
    }

    private fun resetLatency() {
        latency = UNSET
        lowerSince = UNSET
    }

    /** An audio packet stamped [ntpReal] arrived at [nowReal]. */
    @Synchronized fun onAudioArrival(ntpReal: Long, nowReal: Long) {
        if (!plausible(ntpReal, nowReal)) return
        lastAudioReal = nowReal
        audioLateness.add(nowReal, nowReal - ntpReal)
        update(nowReal)
    }

    /** A video frame stamped [ntpReal] arrived at [nowReal]. */
    @Synchronized fun onVideoArrival(ntpReal: Long, nowReal: Long) {
        if (!plausible(ntpReal, nowReal)) return
        videoLateness.add(nowReal, nowReal - ntpReal)
        if (videoStartReal == UNSET) videoStartReal = nowReal
        update(nowReal)
    }

    /** Time from writing a PCM frame to the AudioTrack (at the fill the renderer keeps) until it is heard. */
    @Synchronized fun setAudioOutputLatency(ns: Long) {
        audioOutputNs = ns.coerceIn(0L, MAX_AUDIO_OUTPUT_NS)
    }

    /** Audio is currently heard [errorNs] later (negative: earlier) than its presentation time. */
    @Synchronized fun setAudioError(errorNs: Long, nowReal: Long) {
        audioError = errorNs
        audioErrorAt = nowReal
    }

    /** When the audio stamped [ntpReal] should be heard (CLOCK_REALTIME), or null to play it as it comes. */
    @Synchronized fun audioPresentAt(ntpReal: Long, nowReal: Long): Long? {
        if (latency == UNSET || !plausible(ntpReal, nowReal)) return null
        return ntpReal + latency + AUDIO_OFFSET_NS
    }

    /** When the video frame stamped [ntpReal] should be shown (CLOCK_REALTIME), or null to show it at once. */
    @Synchronized fun videoPresentAt(ntpReal: Long, nowReal: Long): Long? {
        if (!plausible(ntpReal, nowReal)) return null
        val own = videoLateness.estimate()?.plus(VIDEO_PIPELINE_NS)
        val audioFlowing = lastAudioReal != UNSET && nowReal - lastAudioReal < AUDIO_IDLE_NS
        val synced = if (audioFlowing && latency != UNSET) {
            val error = if (audioErrorAt != UNSET && nowReal - audioErrorAt < AUDIO_ERROR_FRESH_NS) audioError else 0L
            minOf(latency + error, MAX_VIDEO_LATENCY_NS)
        } else null
        if (synced == null && videoOffset == UNSET) return null   // no audio to follow: show at once
        // Without audio, slew back down to what video needs, then stop holding frames.
        val target = synced?.let { if (own != null) maxOf(it, own) else it } ?: own ?: return null
        if (videoOffset == UNSET) {
            // At the start of a session jump straight to the target (the picture has barely started);
            // later, start from "shown at once" and slew, so audio starting mid-session does not freeze video.
            val starting = videoStartReal != UNSET && nowReal - videoStartReal < VIDEO_JUMP_WINDOW_NS
            videoOffset = if (starting) target else own ?: target
        } else {
            val maxStep = ((nowReal - videoOffsetAt).coerceIn(0L, 1_000_000_000L) * VIDEO_SLEW_RATE).toLong()
            videoOffset += (target - videoOffset).coerceIn(-maxStep, maxStep)
        }
        videoOffsetAt = nowReal
        if (synced == null && videoOffset <= target) {
            videoOffset = UNSET
            videoOffsetAt = UNSET
            return null
        }
        return ntpReal + videoOffset
    }

    private fun update(nowReal: Long) {
        val audioNeed = audioLateness.estimate()?.plus(audioOutputNs + AUDIO_MARGIN_NS)
        val videoNeed = videoLateness.estimate()?.plus(VIDEO_PIPELINE_NS)
        val need = when {
            audioNeed != null && videoNeed != null -> maxOf(audioNeed, videoNeed)
            else -> audioNeed ?: return   // video alone has nothing to wait for
        }
        when {
            latency == UNSET || need > latency -> {
                if (latency != UNSET) raises++
                latency = need + HEADROOM_NS
                lowerSince = UNSET
            }
            need + HEADROOM_NS + LOWER_HYSTERESIS_NS < latency -> {
                if (lowerSince == UNSET) lowerSince = nowReal
                else if (nowReal - lowerSince >= LOWER_AFTER_NS) {
                    latency = need + HEADROOM_NS
                    lowerSince = UNSET
                    lowers++
                }
            }
            else -> lowerSince = UNSET
        }
    }

    @Synchronized fun describe(): String {
        fun ms(v: Long?) = v?.let { "%.1f".format(it / 1e6) } ?: "-"
        return "latency=${ms(latencyNs)}ms audioLate=${ms(audioLateness.estimate())}ms " +
            "videoLate=${ms(videoLateness.estimate())}ms audioOutput=${ms(audioOutputNs)}ms " +
            "videoOffset=${ms(videoOffset.takeIf { it != UNSET })}ms raises=$raises lowers=$lowers"
    }

    /**
     * How late a stream's packets arrive relative to their timestamps, robust to stalls and stale packets.
     *
     * Samples are grouped into [bucketNs] buckets of stream time (a bucket opens when a packet arrives, so a
     * pause in the stream does not age the history) and the last [buckets] are kept. The estimate is the 75th
     * percentile of the buckets' worst cases: ordinary burstiness is covered, while one stall or the stale
     * packets a sender flushes when its audio restarts touch too few buckets to move it. Those packets are
     * played late and caught up, or skipped. Lateness that lasts does raise the estimate. Until there are
     * [MIN_BUCKETS] buckets the best case plus [START_JITTER_NS] is used: a session often opens with a stale
     * packet, and guessing low only means a small, gentle correction once the history fills.
     */
    internal class Lateness(private val bucketNs: Long = BUCKET_NS, private val buckets: Int = BUCKETS) {
        private val starts = ArrayDeque<Long>()
        private val maxima = ArrayDeque<Long>()
        private var best = Long.MAX_VALUE
        fun clear() { starts.clear(); maxima.clear(); best = Long.MAX_VALUE }
        fun add(now: Long, value: Long) {
            best = minOf(best, value)
            if (starts.isEmpty() || now - starts.peekLast()!! >= bucketNs) {
                starts.addLast(now)
                maxima.addLast(value)
                if (starts.size > buckets) { starts.pollFirst(); maxima.pollFirst() }
            } else if (value > maxima.peekLast()!!) {
                maxima.pollLast()
                maxima.addLast(value)
            }
        }
        fun estimate(): Long? {
            if (maxima.isEmpty()) return null
            if (maxima.size < MIN_BUCKETS) return best + START_JITTER_NS
            val sorted = maxima.sorted()
            return sorted[(sorted.size - 1) * 3 / 4]
        }
    }

    internal companion object {
        private const val UNSET = Long.MIN_VALUE

        /** Timestamps further than this from the local clock are treated as missing (no sync yet, bad clock). */
        const val MAX_PLAUSIBLE_SKEW_NS = 5_000_000_000L
        const val BUCKET_NS = 500_000_000L
        const val BUCKETS = 8
        const val MIN_BUCKETS = 4
        const val START_JITTER_NS = 30_000_000L
        /** Until the AudioTrack reports a timestamp: a typical output latency for a speaker path. */
        const val DEFAULT_AUDIO_OUTPUT_NS = 150_000_000L
        const val MAX_AUDIO_OUTPUT_NS = 1_000_000_000L
        /** Slack on top of the audio need, so ordinary jitter does not make packets late. */
        const val AUDIO_MARGIN_NS = 20_000_000L
        /** Decode and surface hand-off for one video frame (measured 20–30 ms ingress → release on Portal+). */
        const val VIDEO_PIPELINE_NS = 40_000_000L
        /** Added whenever the latency is raised, so a slowly growing need does not raise it on every packet. */
        const val HEADROOM_NS = 20_000_000L
        const val LOWER_HYSTERESIS_NS = 40_000_000L
        const val LOWER_AFTER_NS = 10_000_000_000L
        /** Video is never held longer than this: the compressed-frame queue must cover the hold. */
        const val MAX_VIDEO_LATENCY_NS = 700_000_000L
        /**
         * Video keeps its hold for this long after the last audio packet: senders stop sending audio while
         * nothing plays (between clips), and releasing the hold each time would put the next clip out of sync.
         */
        const val AUDIO_IDLE_NS = 30_000_000_000L
        /** Video offset changes by at most this fraction of elapsed time; it must outrun [MAX_SPEED_CHANGE]. */
        const val VIDEO_SLEW_RATE = 0.06
        /** Within this long of the first video frame the video offset is set directly instead of slewed. */
        const val VIDEO_JUMP_WINDOW_NS = 2_000_000_000L
        const val AUDIO_ERROR_FRESH_NS = 500_000_000L
        /** Lip-sync trim: positive plays audio later relative to video. */
        const val AUDIO_OFFSET_NS = 0L

        /** Audio within this of its presentation time plays at normal speed. */
        const val SPEED_DEADBAND_NS = 5_000_000L
        /** The error is removed with this time constant, at no more than [MAX_SPEED_CHANGE]. */
        const val SPEED_TIME_CONSTANT_NS = 1_000_000_000L
        /** 3 %: a pitch change of half a semitone for the few seconds a correction takes. */
        const val MAX_SPEED_CHANGE = 0.03
        /** Audio later than this is skipped ahead (until it is within [SKIP_UNTIL_NS]) instead of sped up. */
        const val SKIP_ABOVE_NS = 200_000_000L
        const val SKIP_UNTIL_NS = 20_000_000L
        /** A chunk is written once it would be heard no earlier than this before its presentation time. */
        const val AUDIO_EARLY_TOLERANCE_NS = 5_000_000L

        fun plausible(ntpReal: Long, nowReal: Long): Boolean =
            ntpReal > 0L && abs(nowReal - ntpReal) < MAX_PLAUSIBLE_SKEW_NS

        /**
         * Playback speed that brings audio heard [errorNs] late (negative: early) back onto its presentation
         * time: proportional to the error, capped, in steps of 0.1 %.
         */
        fun audioSpeed(errorNs: Long): Double {
            if (abs(errorNs) < SPEED_DEADBAND_NS) return 1.0
            val change = (errorNs.toDouble() / SPEED_TIME_CONSTANT_NS).coerceIn(-MAX_SPEED_CHANGE, MAX_SPEED_CHANGE)
            return 1.0 + (change * 1000).roundToLong() / 1000.0
        }

        /**
         * When a PCM frame written now would be heard (CLOCK_MONOTONIC).
         *
         * With an AudioTrack timestamp (frame [tsFrame] presented at [tsNanos]) the next written frame,
         * [writtenFrames], is heard (written − tsFrame) frames after tsNanos. That extrapolation assumes the
         * track never ran dry; after an underrun it lies in the past, so it is bounded below by "now plus
         * the output path's own latency". Without a timestamp the queued frames plus [outputNs] are used.
         */
        fun predictHeardNanos(
            nowNanos: Long, writtenFrames: Long, headFrames: Long,
            tsFrame: Long?, tsNanos: Long?, outputNs: Long, sampleRate: Int
        ): Long {
            val floor = nowNanos + outputNs
            if (tsFrame != null && tsNanos != null) {
                return maxOf(tsNanos + framesToNanos(writtenFrames - tsFrame, sampleRate), floor)
            }
            return floor + framesToNanos((writtenFrames - headFrames).coerceAtLeast(0L), sampleRate)
        }

        /**
         * Latency of the output path below the AudioTrack: how long after the mixer consumed a frame
         * ([headFrames], consumed by now) it is presented, from a timestamp (frame [tsFrame] at [tsNanos]).
         */
        fun outputPathNanos(nowNanos: Long, headFrames: Long, tsFrame: Long, tsNanos: Long, sampleRate: Int): Long =
            tsNanos + framesToNanos(headFrames - tsFrame, sampleRate) - nowNanos

        fun framesToNanos(frames: Long, sampleRate: Int): Long = frames * 1_000_000_000L / sampleRate
    }
}

/**
 * Plays 16-bit interleaved PCM slightly faster or slower by linear-interpolation resampling: at speed s every
 * input frame becomes 1/s output frames (pitch moves with it, which at a few percent for a few seconds is not
 * noticed, unlike a gap or a skip). Phase and the last frame carry over between chunks, so the output is
 * continuous. Back at speed 1 the phase is walked to zero within one chunk and chunks then pass through
 * untouched.
 */
internal class PcmStretcher(private val channels: Int = 2) {
    private var pos = 0.0   // position of the next output frame, in input frames from the chunk start; in (-1, 0]
    private val last = ShortArray(channels)
    private val frameBytes = 2 * channels

    fun reset() { pos = 0.0; last.fill(0) }

    fun process(input: ByteArray, speed: Double): ByteArray {
        val frames = input.size / frameBytes
        if (frames == 0) return input
        if (speed == 1.0 && pos == 0.0) {
            remember(input, frames)
            return input
        }
        val step = if (speed == 1.0) (frames - pos) / frames else speed
        val count = floor((frames - 1 - pos) / step).toInt() + 1
        val out = ByteArray(count * frameBytes)
        var p = pos
        for (n in 0 until count) {
            val i = floor(p).toInt()
            val frac = p - i
            for (c in 0 until channels) {
                val a = sample(input, i, c)
                val b = if (frac == 0.0) a else sample(input, minOf(i + 1, frames - 1), c)
                val v = (a + (b - a) * frac).toInt()
                val o = (n * channels + c) * 2
                out[o] = v.toByte()
                out[o + 1] = (v shr 8).toByte()
            }
            p += step
        }
        pos = p - frames
        if (abs(pos) < 1e-9 || speed == 1.0) pos = 0.0
        remember(input, frames)
        return out
    }

    private fun sample(input: ByteArray, frame: Int, channel: Int): Int {
        if (frame < 0) return last[channel].toInt()
        val o = (frame * channels + channel) * 2
        return (input[o].toInt() and 0xFF) or (input[o + 1].toInt() shl 8)
    }

    private fun remember(input: ByteArray, frames: Int) {
        for (c in 0 until channels) last[c] = sample(input, frames - 1, c).toShort()
    }
}

/**
 * CLOCK_REALTIME ↔ System.nanoTime (CLOCK_MONOTONIC). The core's timestamps are wall-clock; MediaCodec render
 * times and AudioTrack timestamps are monotonic. Java exposes wall time only in milliseconds, so the offset is
 * re-sampled once a second and centred on the millisecond (error ≤ 0.5 ms, common to audio and video).
 */
internal object WallClock {
    @Volatile private var offset = 0L
    @Volatile private var sampledAt = Long.MIN_VALUE

    fun realMinusMono(): Long {
        val mono = System.nanoTime()
        if (sampledAt == Long.MIN_VALUE || mono - sampledAt > 1_000_000_000L) {
            offset = System.currentTimeMillis() * 1_000_000L + 500_000L - System.nanoTime()
            sampledAt = mono
        }
        return offset
    }

    fun nowReal(): Long = System.nanoTime() + realMinusMono()
}
