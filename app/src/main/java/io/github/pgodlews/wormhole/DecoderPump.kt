package io.github.pgodlews.wormhole

/** Called only by the renderer worker; kept independent of Android for timing regression tests. */
internal class DecoderPump(private val input: VideoFrameQueue) {
    interface Decoder {
        fun drain(render: Boolean)
        /** false means input pressure: caller keeps the access unit and retries. */
        fun submit(frame: VideoFrameQueue.Frame): Boolean
        fun close()
    }
    private var decoder: Decoder? = null
    private var generation = -1L

    /** [ready] holds the head frame back (false) until it is due for decoding; the decoder is created first. */
    fun tick(
        expectedGeneration: Long = input.currentGeneration(),
        ready: (VideoFrameQueue.Frame) -> Boolean = { true },
        create: () -> Decoder?
    ): Boolean {
        val current = expectedGeneration
        if (input.currentGeneration() != current) return false
        if (current != generation) { close(); generation = current }
        decoder?.drain(input.currentGeneration() == current)
        val frame = input.peek() ?: return false
        if (frame.generation != current) return false
        val target = decoder ?: create()?.also { decoder = it } ?: return false
        if (input.currentGeneration() != current) { close(); return false }
        if (!ready(frame)) return false
        if (!target.submit(frame)) return false
        input.consumed(frame)
        target.drain(input.currentGeneration() == current)
        return true
    }

    fun close() {
        val old = decoder
        decoder = null
        old?.close()
    }
}
