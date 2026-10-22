package io.github.pgodlews.wormhole

import org.junit.Assert.*
import org.junit.Test

class VideoFrameQueueTest {
    private fun nal(type: Int) = byteArrayOf(0, 0, 0, 1, type.toByte(), 0x55)
    private val config get() = nal(7) + nal(8)

    @Test fun `separate SPS and PPS survive until the IDR`() {
        val q = VideoFrameQueue()
        q.offer(nal(7), 0); q.offer(nal(8), 1); q.offer(nal(1), 2)
        assertNull(q.peek())
        q.offer(nal(5), 3)
        assertArrayEquals(config + nal(5), q.peek()!!.bytes)
    }

    @Test fun `starvation retains the same access unit and preserves order`() {
        val q = VideoFrameQueue()
        q.offer(config + nal(5), 0)
        val first = q.peek()!!
        repeat(10) { assertSame(first, q.peek()) }
        q.offer(nal(1), 1)
        assertSame(first, q.peek())
        q.consumed(first)
        assertArrayEquals(nal(1), q.peek()!!.bytes)
    }

    @Test fun `overflow discards dependent frames and changes decoder generation`() {
        val q = VideoFrameQueue(maxFrames = 2)
        q.offer(config + nal(5), 0); q.offer(nal(1), 1)
        val old = q.peek()!!
        q.offer(nal(1), 2)
        assertNull(q.peek())
        assertNotEquals(old.generation, q.currentGeneration())
        q.offer(nal(1), 3); assertNull(q.peek())
        q.offer(nal(5), 4)
        val recovered = q.peek()!!
        q.consumed(old) // stale owner completion cannot consume a new session's input
        assertSame(recovered, q.peek())
        assertArrayEquals(config + nal(5), recovered.bytes)
    }

    @Test fun `reset never reuses another sender configuration`() {
        val q = VideoFrameQueue()
        q.offer(config + nal(5), 0); q.reset(); q.offer(nal(5), 1)
        assertNull(q.peek())
    }

    @Test fun `missing IDR has a bounded recovery deadline`() {
        val q = VideoFrameQueue()
        q.offer(config + nal(1), 100)
        assertFalse(q.stalled(199, 100))
        q.offer(nal(1), 150) // further dependent input must not postpone recovery
        assertTrue(q.stalled(200, 100))
        q.offer(nal(5), 201); q.consumed(q.peek()!!)
        assertFalse(q.stalled(1000, 100))
    }

    @Test fun `buffer budget includes cached configuration and oversized IDR`() {
        val q = VideoFrameQueue(maxBytes = 16)
        q.offer(config, 0); q.offer(nal(5), 1)
        assertNull(q.peek())
        assertTrue(q.stalled(101, 100))
    }

    @Test fun `truncated start codes are harmless`() {
        val q = VideoFrameQueue()
        for (n in 0..4) q.offer(byteArrayOf(0, 0, 0, 1).copyOf(n), 0)
        assertNull(q.peek())
        q.offer(byteArrayOf(0, 0, 1, 7, 42, 0, 0, 1, 8, 42, 0, 0, 1, 5, 42), 1)
        assertNotNull(q.peek())
    }

    @Test fun `diagnostics count overflow loss separately from recovery rejection`() {
        val q = VideoFrameQueue(maxFrames = 2)
        q.offer(config + nal(5), 10, 123, 456, 9)
        val first = q.peek()!!
        assertEquals(123L, first.ntpLocalNanos)
        assertEquals(456L, first.ntpRemoteNanos)
        assertEquals(9L, first.ingressNanos)
        q.offer(nal(1), 20)
        val before = q.stats(30)
        assertEquals(2, before.depth)
        assertEquals(first.bytes.size + nal(1).size, before.bytes)
        assertEquals(20L, before.oldestAgeNanos)
        q.offer(nal(1), 30)
        q.offer(nal(1), 40)
        val after = q.stats(50)
        assertEquals(0, after.depth)
        assertEquals(0, after.bytes)
        assertEquals(1L, after.overflowEvents)
        assertEquals(2L, after.overflowDiscarded)
        assertEquals(2L, after.rejectedPictures)
        assertEquals(2, after.peakDepth)
        q.reset()
        q.resetStatistics()
        assertEquals(0L, q.stats(60).overflowDiscarded)
    }

    @Test fun `startup burst drains in order before restoring steady frame limit`() {
        val q = VideoFrameQueue(maxFrames = 2, startupMaxFrames = 6)
        q.offer(config + nal(5), 0)
        repeat(4) { q.offer(nal(1), it + 1L) }
        val generation = q.currentGeneration()
        q.consumed(q.peek()!!)
        q.offer(nal(1), 6) // first submission must not invalidate the startup backlog
        assertEquals(generation, q.currentGeneration())
        repeat(4) { q.consumed(q.peek()!!) }
        assertEquals(1, q.stats(7).depth)
        q.offer(nal(1), 8)
        q.offer(nal(1), 9) // steady-state cap is now two again
        assertNotEquals(generation, q.currentGeneration())
        assertEquals(2L, q.stats(9).overflowDiscarded)
    }

    @Test fun `startup allowance cannot survive its time budget`() {
        val q = VideoFrameQueue(maxFrames = 2, startupMaxFrames = 6, startupWindowNanos = 100)
        q.offer(config + nal(5), 0)
        repeat(3) { q.offer(nal(1), it + 1L) }
        q.offer(nal(1), 100)
        assertNull(q.peek())
        assertEquals(4L, q.stats(100).overflowDiscarded)
        assertTrue(q.stalled(200, 100))
    }

    @Test fun `startup still enforces byte and frame caps`() {
        val frames = VideoFrameQueue(maxFrames = 2, startupMaxFrames = 3)
        frames.offer(config + nal(5), 0)
        repeat(3) { frames.offer(nal(1), it + 1L) }
        assertNull(frames.peek())
        assertEquals(3L, frames.stats(5).overflowDiscarded)

        val bytes = VideoFrameQueue(maxBytes = 35, startupMaxFrames = 60)
        bytes.offer(config + nal(5), 0) // 30 bytes including injected configuration
        bytes.offer(nal(1), 1)
        assertNull(bytes.peek())
        assertEquals(1L, bytes.stats(2).overflowDiscarded)
    }

    @Test fun `renderer queue absorbs a decoder hiccup of under a second at 60 fps`() {
        val frame = 16_666_667L
        val q = VideoFrameQueue(maxFrames = VideoRenderer.STEADY_QUEUE_FRAMES, startupMaxFrames = 60)
        q.offer(config + nal(5), 0)
        q.consumed(q.peek()!!) // past startup: the steady limit applies
        val generation = q.currentGeneration()
        // The decoder accepts nothing for 0.5 s. A 12-frame queue was reset after 0.2 s.
        for (i in 1..30) q.offer(nal(1), i * frame)
        assertEquals(generation, q.currentGeneration())
        assertEquals(0L, q.stats(0).overflowEvents)
        assertFalse(q.stalled(30 * frame, VideoRenderer.STALL_TIMEOUT_NANOS))
        // Beyond the steady budget the chain is discarded as before.
        for (i in 31..VideoRenderer.STEADY_QUEUE_FRAMES) q.offer(nal(1), i * frame)
        assertEquals(generation, q.currentGeneration())
        q.offer(nal(1), (VideoRenderer.STEADY_QUEUE_FRAMES + 1) * frame)
        assertNotEquals(generation, q.currentGeneration())
        assertEquals(1L, q.stats(0).overflowEvents)
    }

    @Test fun `renderer waits longer than two seconds for a keyframe after overflow`() {
        val second = 1_000_000_000L
        val q = VideoFrameQueue(maxFrames = 2)
        q.offer(config + nal(5), 0); q.offer(nal(1), 1); q.offer(nal(1), 2) // overflow at t = 2 ns
        assertNull(q.peek())
        assertFalse(q.stalled(3 * second, VideoRenderer.STALL_TIMEOUT_NANOS))
        q.offer(nal(5), 3 * second)
        assertNotNull(q.peek())
        assertFalse(q.stalled(3 * second, VideoRenderer.STALL_TIMEOUT_NANOS))
    }

    @Test fun `reset restores startup allowance without accepting stale completion`() {
        val q = VideoFrameQueue(maxFrames = 2, startupMaxFrames = 6)
        q.offer(config + nal(5), 0)
        val old = q.peek()!!
        q.consumed(old)
        q.reset()
        q.offer(config + nal(5), 1)
        q.consumed(old)
        repeat(4) { q.offer(nal(1), it + 2L) }
        assertEquals(5, q.stats(7).depth)
        assertEquals(0L, q.stats(7).overflowEvents)
    }
}
