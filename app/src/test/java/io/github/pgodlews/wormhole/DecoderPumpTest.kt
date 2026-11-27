package io.github.pgodlews.wormhole

import org.junit.Assert.*
import org.junit.Test

class DecoderPumpTest {
    private val queue = VideoFrameQueue()
    private val pump = DecoderPump(queue)
    private val first = byteArrayOf(0,0,1,7,42,0,0,1,8,42,0,0,1,5,42)
    private class FakeDecoder : DecoderPump.Decoder {
        var inputAvailable = true
        var outputAvailable = false
        var displayed = 0
        var closed = false
        val submitted = mutableListOf<VideoFrameQueue.Frame>()
        override fun drain(render: Boolean) {
            if (outputAvailable) { if (render) displayed++; outputAvailable = false }
        }
        override fun submit(frame: VideoFrameQueue.Frame): Boolean {
            if (!inputAvailable) return false
            submitted.add(frame)
            return true
        }
        override fun close() { closed = true }
    }

    @Test fun `late decoded output is released without another network frame`() {
        val codec = FakeDecoder()
        queue.offer(first, 0); pump.tick { codec }
        assertNull(queue.peek())
        codec.outputAvailable = true // decoding completes AFTER the first drain pass
        pump.tick { fail("must not recreate codec"); null }
        assertEquals(1, codec.displayed)
    }

    @Test fun `input starvation drains output and retries original access unit`() {
        val codec = FakeDecoder().apply { inputAvailable = false }
        queue.offer(first, 0)
        val pending = queue.peek()
        assertFalse(pump.tick { codec })
        codec.outputAvailable = true
        assertFalse(pump.tick { codec })
        assertEquals(1, codec.displayed)
        assertSame(pending, queue.peek())
        codec.inputAvailable = true
        assertTrue(pump.tick { codec })
        assertSame(pending, codec.submitted.single())
    }

    @Test fun `new session closes old decoder and never renders its queued output`() {
        val codec = FakeDecoder()
        queue.offer(first, 0); pump.tick { codec }
        codec.outputAvailable = true
        queue.reset()
        pump.tick { fail("no new session input"); null }
        assertTrue(codec.closed)
        assertEquals(0, codec.displayed)
    }

    @Test fun `failed decoder creation preserves initial configuration and IDR for retry`() {
        queue.offer(first, 0)
        val pending = queue.peek()
        assertFalse(pump.tick { null })
        val codec = FakeDecoder()
        assertTrue(pump.tick { codec })
        assertSame(pending, codec.submitted.single())
    }
    @Test fun `surface or session reset during codec creation cannot submit old input`() {
        queue.offer(first, 0)
        val codec = FakeDecoder()
        assertFalse(pump.tick { queue.reset(); queue.offer(first, 1); codec })
        assertTrue(codec.closed)
        assertTrue(codec.submitted.isEmpty())
        assertNotNull(queue.peek())
    }

    @Test fun `frames arriving during slow decoder creation preserve startup IDR`() {
        val startupQueue = VideoFrameQueue(startupMaxFrames = 60)
        val startupPump = DecoderPump(startupQueue)
        val codec = FakeDecoder()
        startupQueue.offer(first, 0)
        val idr = startupQueue.peek()!!
        assertTrue(startupPump.tick {
            // Simulate 30 arrivals while surface/codec creation takes half a second.
            repeat(30) { startupQueue.offer(byteArrayOf(0, 0, 1, 1, 42), (it + 1L) * 16_000_000) }
            codec
        })
        repeat(30) { assertTrue(startupPump.tick { fail("must reuse startup codec"); null }) }
        assertSame(idr, codec.submitted.first())
        assertEquals(31, codec.submitted.size)
        assertFalse(codec.closed)
        assertNull(startupQueue.peek())
    }


    @Test fun `a frame that is not yet due is held after the decoder is created`() {
        val codec = FakeDecoder()
        var due = false
        var created = 0
        queue.offer(first, 0)
        assertFalse(pump.tick(ready = { due }) { created++; codec })
        assertEquals("decoder is created while the frame waits", 1, created)
        assertTrue(codec.submitted.isEmpty())
        assertNotNull(queue.peek())
        due = true
        assertTrue(pump.tick(ready = { due }) { fail("must not recreate codec"); null })
        assertEquals(1, codec.submitted.size)
        assertNull(queue.peek())
    }
}
