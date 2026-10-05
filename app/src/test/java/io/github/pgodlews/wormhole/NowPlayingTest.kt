package io.github.pgodlews.wormhole

import org.junit.Assert.*
import org.junit.Test

class NowPlayingTest {

    @Test
    fun `position advances only while playing and is clamped to the duration`() {
        val track = NowPlaying().withProgress(10.0, 200.0, nowMillis = 1_000L)
        assertFalse(track.playing)
        assertEquals(10.0, track.positionAt(6_000L), 1e-9)

        val playing = track.resumed(nowMillis = 2_000L)
        assertEquals(10.0, playing.positionAt(2_000L), 1e-9)
        assertEquals(15.0, playing.positionAt(7_000L), 1e-9)
        assertEquals(200.0, playing.positionAt(1_000_000L), 1e-9)
        assertEquals(10.0, playing.positionAt(0L), 1e-9)
    }

    @Test
    fun `pause freezes the position and resume continues from it`() {
        val playing = NowPlaying().withProgress(30.0, 180.0, nowMillis = 0L).resumed(nowMillis = 0L)
        val paused = playing.paused(nowMillis = 4_000L)
        assertFalse(paused.playing)
        assertEquals(34.0, paused.positionAt(60_000L), 1e-9)

        val resumed = paused.resumed(nowMillis = 60_000L)
        assertEquals(36.0, resumed.positionAt(62_000L), 1e-9)
        assertSame(resumed, resumed.resumed(nowMillis = 70_000L))
    }

    @Test
    fun `progress report keeps play state and metadata keeps position`() {
        val playing = NowPlaying().resumed(nowMillis = 0L)
        val seeked = playing.withProgress(90.0, 240.0, nowMillis = 5_000L)
        assertTrue(seeked.playing)
        assertEquals(91.0, seeked.positionAt(6_000L), 1e-9)

        val tagged = seeked.withMetadata(" Title ", "Artist", "", 2024)
        assertEquals("Title", tagged.title)
        assertEquals(2024, tagged.year)
        assertEquals(91.0, tagged.positionAt(6_000L), 1e-9)
    }

    @Test
    fun `formatTime switches to hours past sixty minutes`() {
        assertEquals("0:00", NowPlaying.formatTime(-3.0))
        assertEquals("0:07", NowPlaying.formatTime(7.9))
        assertEquals("3:25", NowPlaying.formatTime(205.0))
        assertEquals("1:02:03", NowPlaying.formatTime(3723.0))
    }
}
