package io.github.pgodlews.wormhole

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AvSyncTest {
    private val ms = 1_000_000L
    private val t0 = 1_800_000_000_000_000_000L   // a CLOCK_REALTIME instant in 2027
    private val jitter = AvSync.START_JITTER_NS

    private fun synced(audioLate: Long = -250 * ms, videoLate: Long = 30 * ms, output: Long = 150 * ms) = AvSync().apply {
        setAudioOutputLatency(output)
        onVideoArrival(t0 - videoLate, t0)
        onAudioArrival(t0 - audioLate, t0)
    }

    @Test fun `lateness starts from the best case, so a stale first packet does not count`() {
        val lateness = AvSync.Lateness(bucketNs = 100, buckets = 8)
        lateness.add(0, 2_600 * ms)   // seen on device: the first packet of a session can be seconds old
        lateness.add(10, -170 * ms)
        lateness.add(120, -175 * ms)
        assertEquals(-175 * ms + jitter, lateness.estimate())
    }

    @Test fun `a burst of late packets does not move the lateness estimate but lasting lateness does`() {
        val lateness = AvSync.Lateness(bucketNs = 100, buckets = 8)
        for (t in 0L until 1000L step 10) lateness.add(t, if (t in 400L..550L) 800L else 0L)
        assertEquals(0L, lateness.estimate())
        for (t in 1000L until 1600L step 10) lateness.add(t, 300L)
        assertEquals(300L, lateness.estimate())
    }

    @Test fun `a pause in the stream does not age the lateness history`() {
        val lateness = AvSync.Lateness(bucketNs = 100, buckets = 8)
        for (t in 0L until 800L step 10) lateness.add(t, 0L)
        // The sender's audio restarts after a minute with stale packets, as seen between clips.
        for (t in 60_000L until 60_090L step 10) lateness.add(t, 775L)
        assertEquals(0L, lateness.estimate())
    }

    @Test fun `no latency and no audio schedule until audio has arrived`() {
        val sync = AvSync()
        assertNull(sync.latencyNs)
        sync.onVideoArrival(t0 - 30 * ms, t0)
        assertNull(sync.latencyNs)
        assertNull(sync.audioPresentAt(t0, t0))
        assertNull("video alone is shown at once", sync.videoPresentAt(t0 - 30 * ms, t0))
    }

    @Test fun `latency covers the slower of the two pipelines`() {
        // Audio arrives 250 ms early and needs 150 ms of output: video (30 ms late + decode) dominates.
        val early = synced()
        assertEquals(30 * ms + jitter + AvSync.VIDEO_PIPELINE_NS + AvSync.HEADROOM_NS, early.latencyNs)
        // Audio arriving 100 ms late with a 150 ms output path dominates.
        val late = synced(audioLate = 100 * ms)
        assertEquals(100 * ms + jitter + 150 * ms + AvSync.AUDIO_MARGIN_NS + AvSync.HEADROOM_NS, late.latencyNs)
        assertEquals(t0 + late.latencyNs!!, late.audioPresentAt(t0, t0))
    }

    @Test fun `implausible timestamps are ignored`() {
        val sync = synced()
        assertNull(sync.audioPresentAt(0, t0))
        assertNull(sync.audioPresentAt(t0 - 6_000 * ms, t0))
        assertNull(sync.videoPresentAt(t0 + 6_000 * ms, t0))
    }

    @Test fun `latency rises when lateness lasts and falls only after staying too high`() {
        val sync = AvSync().apply { setAudioOutputLatency(150 * ms) }
        var now = t0
        fun run(seconds: Int, audioLate: Long) = repeat(seconds * 10) {
            now += 100 * ms
            sync.onVideoArrival(now - 30 * ms, now)
            sync.onAudioArrival(now - audioLate, now)
        }
        run(4, audioLate = 0)
        val base = 150 * ms + AvSync.AUDIO_MARGIN_NS + AvSync.HEADROOM_NS
        run(1, audioLate = 0)
        assertTrue(sync.latencyNs!! - base in 0..jitter)   // set with the start-up allowance, never lowered since
        val before = sync.latencyNs!!
        run(1, audioLate = 200 * ms)   // one second of late audio: two buckets of eight
        assertEquals(before, sync.latencyNs)
        run(2, audioLate = 200 * ms)
        assertEquals(base + 200 * ms, sync.latencyNs)
        run(9, audioLate = 0)
        assertEquals("not lowered before LOWER_AFTER_NS", base + 200 * ms, sync.latencyNs)
        run(8, audioLate = 0)
        assertEquals(base, sync.latencyNs)
    }

    @Test fun `video follows the shared latency while audio flows`() {
        val sync = synced(audioLate = 100 * ms)
        val latency = sync.latencyNs!!
        assertEquals(t0 - 30 * ms + latency, sync.videoPresentAt(t0 - 30 * ms, t0))
    }

    @Test fun `video follows audio as it is actually heard while audio converges`() {
        val sync = synced(audioLate = 100 * ms)
        val latency = sync.latencyNs!!
        sync.setAudioError(80 * ms, t0)   // audio is 80 ms behind its target and catching up
        assertEquals(t0 + latency + 80 * ms, sync.videoPresentAt(t0, t0))
        // Audio gains 30 ms in the next second; video keeps up (its slew rate exceeds the audio's).
        val later = t0 + 1_000 * ms
        sync.onAudioArrival(later - 100 * ms, later)
        sync.setAudioError(50 * ms, later)
        assertEquals(later + latency + 50 * ms, sync.videoPresentAt(later, later))
        // A stale error report is not followed.
        val stale = later + AvSync.AUDIO_ERROR_FRESH_NS + 1_000 * ms
        sync.onAudioArrival(stale - 100 * ms, stale)
        assertEquals(stale + latency, sync.videoPresentAt(stale, stale))
    }

    @Test fun `video hold is capped`() {
        val sync = synced(audioLate = 900 * ms)
        assertTrue(sync.latencyNs!! > AvSync.MAX_VIDEO_LATENCY_NS)
        assertEquals(t0 + AvSync.MAX_VIDEO_LATENCY_NS, sync.videoPresentAt(t0, t0))
        // Audio still waits for its own need.
        assertEquals(t0 + sync.latencyNs!!, sync.audioPresentAt(t0, t0))
    }

    @Test fun `audio starting mid-session slews video instead of freezing it`() {
        val sync = AvSync().apply { setAudioOutputLatency(150 * ms) }
        sync.onVideoArrival(t0 - 30 * ms, t0)
        val now = t0 + 10_000 * ms
        sync.onVideoArrival(now - 30 * ms, now)
        assertNull(sync.videoPresentAt(now - 30 * ms, now))
        sync.onAudioArrival(now - 100 * ms, now)   // needs far more than video
        val own = 30 * ms + jitter + AvSync.VIDEO_PIPELINE_NS
        assertEquals("starts from what video needs", now - 30 * ms + own, sync.videoPresentAt(now - 30 * ms, now))
        val later = now + 1_000 * ms
        sync.onAudioArrival(later - 100 * ms, later)
        val step = (1_000 * ms * AvSync.VIDEO_SLEW_RATE).toLong()
        assertEquals(later - 30 * ms + own + step, sync.videoPresentAt(later - 30 * ms, later))
    }

    @Test fun `video keeps its hold through a pause in audio and releases it after a long one`() {
        val sync = synced(audioLate = 100 * ms)
        val held = sync.videoPresentAt(t0 - 30 * ms, t0)!! - (t0 - 30 * ms)
        var now = t0 + 10_000 * ms   // between clips
        sync.onVideoArrival(now - 30 * ms, now)
        assertEquals(held, sync.videoPresentAt(now - 30 * ms, now)!! - (now - 30 * ms))
        now = t0 + AvSync.AUDIO_IDLE_NS + 1 * ms
        sync.onVideoArrival(now - 30 * ms, now)
        val slewing = sync.videoPresentAt(now - 30 * ms, now)
        assertTrue("still held while slewing back", slewing != null && slewing > now)
        repeat(20) {
            now += 1_000 * ms
            sync.onVideoArrival(now - 30 * ms, now)
            sync.videoPresentAt(now - 30 * ms, now)
        }
        assertNull(sync.videoPresentAt(now - 30 * ms, now))
    }

    @Test fun `audio speed is proportional to the error, capped, and normal near zero`() {
        assertEquals(1.0, AvSync.audioSpeed(0), 0.0)
        assertEquals(1.0, AvSync.audioSpeed(4 * ms), 0.0)
        assertEquals(1.0, AvSync.audioSpeed(-4 * ms), 0.0)
        assertEquals(1.012, AvSync.audioSpeed(12 * ms), 1e-9)    // late: play faster
        assertEquals(0.988, AvSync.audioSpeed(-12 * ms), 1e-9)   // early: play slower
        assertEquals(1.03, AvSync.audioSpeed(150 * ms), 1e-9)
        assertEquals(0.97, AvSync.audioSpeed(-150 * ms), 1e-9)
        assertTrue("video can keep up with audio", AvSync.VIDEO_SLEW_RATE > AvSync.MAX_SPEED_CHANGE)
    }

    @Test fun `heard time comes from the track timestamp, bounded by the output latency after an underrun`() {
        val rate = 44_100
        // Frame 44_100 is presented at 2 s; 88_200 frames written: the next one is heard at 3 s.
        assertEquals(3_000 * ms, AvSync.predictHeardNanos(2_500 * ms, 88_200, 66_150, 44_100, 2_000 * ms, 100 * ms, rate))
        // The track ran dry: the extrapolation lies in the past, so "now + output path" applies.
        assertEquals(5_100 * ms, AvSync.predictHeardNanos(5_000 * ms, 88_200, 88_200, 44_100, 2_000 * ms, 100 * ms, rate))
        // No timestamp yet: queued frames plus the output path.
        assertEquals(1_600 * ms, AvSync.predictHeardNanos(1_000 * ms, 22_050, 0, null, null, 100 * ms, rate))
    }

    @Test fun `output path latency is how long a consumed frame takes to be presented`() {
        // The mixer has consumed frame 48_510 by 2.1 s; frame 44_100 was presented at 2.0 s.
        assertEquals(0L, AvSync.outputPathNanos(2_100 * ms, 48_510, 44_100, 2_000 * ms, 44_100))
        assertEquals(150 * ms, AvSync.outputPathNanos(1_950 * ms, 48_510, 44_100, 2_000 * ms, 44_100))
    }
}

class PcmStretcherTest {
    /** Stereo chunk whose left channel is a ramp continuing at [start] and whose right channel is its negative. */
    private fun ramp(start: Int, frames: Int): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val l = start + i
            val r = -l
            out[i * 4] = l.toByte(); out[i * 4 + 1] = (l shr 8).toByte()
            out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = (r shr 8).toByte()
        }
        return out
    }
    private fun left(pcm: ByteArray) = IntArray(pcm.size / 4) { (pcm[it * 4].toInt() and 0xFF) or (pcm[it * 4 + 1].toInt() shl 8) }
    private fun right(pcm: ByteArray) = IntArray(pcm.size / 4) { (pcm[it * 4 + 2].toInt() and 0xFF) or (pcm[it * 4 + 3].toInt() shl 8) }

    @Test fun `normal speed passes chunks through untouched`() {
        val stretcher = PcmStretcher()
        val chunk = ramp(0, 480)
        assertSame(chunk, stretcher.process(chunk, 1.0))
    }

    @Test fun `faster playback yields proportionally fewer frames and slower more`() {
        for (speed in listOf(1.03, 0.97)) {
            val stretcher = PcmStretcher()
            var frames = 0
            repeat(100) { frames += stretcher.process(ramp(it * 480, 480), speed).size / 4 }
            assertEquals(48_000 / speed, frames.toDouble(), 1.0)
        }
    }

    @Test fun `output is continuous across chunks and speed changes`() {
        val stretcher = PcmStretcher()
        val speeds = listOf(1.0, 1.03, 1.03, 0.97, 1.0, 1.0, 1.012, 1.0, 1.0)
        val l = mutableListOf<Int>()
        val r = mutableListOf<Int>()
        speeds.forEachIndexed { n, speed ->
            val out = stretcher.process(ramp(n * 480, 480), speed)
            l += left(out).toList(); r += right(out).toList()
        }
        // A ramp stays a ramp: steps between consecutive output frames are 0, 1 or 2, never a jump.
        for (i in 1 until l.size) assertTrue("step at $i: ${l[i - 1]} -> ${l[i]}", l[i] - l[i - 1] in 0..2)
        for (i in l.indices) assertTrue(kotlin.math.abs(l[i] + r[i]) <= 1)
        assertEquals("nothing lost at the end", speeds.size * 480 - 1, l.last())
    }

    @Test fun `back at normal speed the phase is walked out and chunks pass through again`() {
        val stretcher = PcmStretcher()
        stretcher.process(ramp(0, 480), 1.03)
        val settling = stretcher.process(ramp(480, 480), 1.0)
        assertEquals(480, settling.size / 4)
        val chunk = ramp(960, 480)
        assertSame(chunk, stretcher.process(chunk, 1.0))
        assertArrayEquals(left(ramp(960, 480)), left(chunk))
    }
}

class RenderScheduleTest {
    @Test fun `render times match outputs and skip frames the decoder dropped`() {
        val schedule = RenderSchedule(capacity = 4)
        schedule.submitted(10, 100)
        schedule.submitted(20, 200)
        schedule.submitted(30, 300)
        assertEquals(100L, schedule.take(10))
        assertEquals(300L, schedule.take(30))   // 20 never came out
        assertEquals(0L, schedule.take(40))
    }

    @Test fun `an unknown timestamp does not consume later entries`() {
        val schedule = RenderSchedule(capacity = 4)
        schedule.submitted(50, 500)
        assertEquals(0L, schedule.take(40))
        assertEquals(500L, schedule.take(50))
    }

    @Test fun `overflow keeps the newest entries`() {
        val schedule = RenderSchedule(capacity = 2)
        schedule.submitted(1, 10); schedule.submitted(2, 20); schedule.submitted(3, 30)
        assertEquals(0L, schedule.take(1))
        assertEquals(20L, schedule.take(2))
    }
}
