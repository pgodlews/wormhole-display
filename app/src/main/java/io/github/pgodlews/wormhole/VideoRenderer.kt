package io.github.pgodlews.wormhole

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Trace
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** All codec operations belong to one worker. Network callbacks only enqueue owned bytes. */
class VideoRenderer(
    private val reconnectRequired: () -> Unit,
    private val onHevcFailure: () -> Unit = {},
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    val telemetry: StreamTelemetry = StreamTelemetry()
) {
    // Some devices take ~500 ms to create the surface/decoder. The 12-frame steady
    // budget alone can discard the startup IDR before the first codec submission.
    // The 12-frame default is ~0.2 s at 60 fps. A short decoder hiccup (observed when the sender restarts its audio
    // stream and an AAC decoder is created next to the video decoder) overflowed it, which discards the decoder and then
    // waits for a keyframe the sender may not send for seconds. 48 frames absorb such a hiccup.
    private val input = VideoFrameQueue(maxFrames = STEADY_QUEUE_FRAMES, startupMaxFrames = 60)
    private val stateLock = Any()
    private var surface: Surface? = null
    private var active = false
    private var closed = false
    private var sessionHevc = false
    private var session = 0L
    private var diagnostics = VideoDiagnostics(session)
    @Volatile internal var hevcDecoder: VideoDecoderSupport.Hevc? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Wormhole decoder") }
    // Fields below are confined to worker.
    private val decoderPump = DecoderPump(input)
    private var codecGeneration = -1L
    private var createFailures = 0
    private var nextCreateAttempt = 0L
    private var lastLog = 0L
    private var lastWidth = 0
    private var lastHeight = 0
    private val info = MediaCodec.BufferInfo()
    private val pump = worker.scheduleWithFixedDelay({ tick() }, 0, 4, TimeUnit.MILLISECONDS)

    fun setSurface(value: Surface?) = synchronized(stateLock) {
        if (!closed && surface !== value) {
            // Keep input received before the first surface; it can contain the only startup IDR.
            if (surface != null) input.reset(clearConfig = false)
            surface = value
        }
    }

    fun beginSession() = synchronized(stateLock) {
        if (!closed) {
            session++
            active = true
            sessionHevc = false
            input.reset()
            input.resetStatistics()
            diagnostics = VideoDiagnostics(session)
            telemetry.reset()
        }
    }

    fun endSession() = synchronized(stateLock) {
        if (active) logDiagnostics("session_end")
        session++
        active = false
        input.reset()
        telemetry.reset()
        worker.execute {
            lastWidth = 0
            lastHeight = 0
        }
        onVideoSizeChanged(0, 0)
    }

    fun onFrame(data: ByteArray, ingressNanos: Long, ntpLocalNanos: Long, ntpRemoteNanos: Long, hevc: Boolean = false) = synchronized(stateLock) {
        if (active && !closed) {
            sessionHevc = hevc
            val now = System.nanoTime()
            telemetry.onFrameReceived(data.size, now)
            input.offer(data, now, ntpLocalNanos, ntpRemoteNanos, ingressNanos, if (hevc) VideoCodec.HEVC else VideoCodec.AVC)
            // Includes JNI allocation/copy, listener dispatch and queue bookkeeping;
            // excludes the few JNI cleanup instructions after Kotlin returns.
            diagnostics.received(data.size, ingressNanos, ntpLocalNanos, ntpRemoteNanos, System.nanoTime())
        }
    }

    fun close() = synchronized(stateLock) {
        if (!closed) {
            if (active) logDiagnostics("close")
            closed = true
            active = false
            input.reset()
            pump.cancel(false)
            worker.execute { decoderPump.close() }
            worker.shutdown()
        }
    }

    private data class Snapshot(val surface: Surface?, val active: Boolean, val session: Long, val generation: Long, val diagnostics: VideoDiagnostics)

    private fun tick() {
        val snapshot = synchronized(stateLock) { Snapshot(surface, active && !closed, session, input.currentGeneration(), diagnostics) }
        val generation = snapshot.generation
        if (codecGeneration != generation || !snapshot.active || snapshot.surface == null) {
            decoderPump.close()
            if (codecGeneration != generation) { createFailures = 0; nextCreateAttempt = 0L }
            codecGeneration = generation
        }
        val now = System.nanoTime()
        if (snapshot.active && now - lastLog >= 5_000_000_000L) {
            synchronized(stateLock) {
                if (diagnostics === snapshot.diagnostics) logDiagnostics("periodic")
            }
            lastLog = now
        }
        val target = snapshot.surface ?: return
        if (!snapshot.active || !target.isValid) return
        try {
            decoderPump.tick(generation) {
                if (now >= nextCreateAttempt) createDecoder(target, snapshot.diagnostics) else null
            }
            if (input.stalled(now, STALL_TIMEOUT_NANOS)) requestReconnect(snapshot.session)
        } catch (e: Exception) {
            Log.w(TAG, "decoder failed; reconnect required: ${e.message}")
            decoderPump.close()
            requestReconnect(snapshot.session)
        }
    }

    private fun requestReconnect(expectedSession: Long) {
        var failedHevc = false
        val notify = synchronized(stateLock) {
            if (active && !closed && session == expectedSession) {
                failedHevc = sessionHevc
                logDiagnostics("reconnect")
                active = false
                input.reset()
                true
            } else false
        }
        // No periodic IDR/refresh request is guaranteed by this core. End the broken
        // session instead of feeding P pictures to a fresh decoder or waiting forever.
        if (notify) {
            if (failedHevc) onHevcFailure()
            reconnectRequired()
        }
    }

    // Caller holds stateLock; reports at lifecycle boundaries and once per five seconds.
    private fun logDiagnostics(reason: String) {
        val now = System.nanoTime()
        val report = diagnostics.captureReport(now, input.stats(now), reason)
        worker.execute { Log.i(TAG, report.format()) }
    }

    private fun createDecoder(target: Surface, measurements: VideoDiagnostics): DecoderPump.Decoder? {
        var candidate: MediaCodec? = null
        return try {
            val frameCodec = input.peek()?.codec ?: return null
            val support = if (frameCodec == VideoCodec.HEVC) checkNotNull(hevcDecoder) { "HEVC was not enabled" } else null
            candidate = if (support != null) MediaCodec.createByCodecName(support.name)
                        else MediaCodec.createDecoderByType(frameCodec.mime)
            val format = MediaFormat.createVideoFormat(frameCodec.mime, support?.width ?: 1920, support?.height ?: 1080)
            candidate.configure(format, target, null, 0)
            candidate.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            candidate.start()
            createFailures = 0
            nextCreateAttempt = 0L
            val hardware = if (Build.VERSION.SDK_INT >= 29) runCatching { candidate.codecInfo.isHardwareAccelerated.toString() }.getOrDefault("unknown") else "unknown(api28)"
            val software = if (Build.VERSION.SDK_INT >= 29) runCatching { candidate.codecInfo.isSoftwareOnly.toString() }.getOrDefault("unknown") else "unknown(api28)"
            Log.i(TAG, "decoder started: ${candidate.name} hardware=$hardware software=$software configured=$format")
            telemetry.onCodecStarted(candidate.name)
            val started = candidate
            object : DecoderPump.Decoder {
                override fun drain(render: Boolean) = drainOutput(started, render, measurements)
                override fun submit(frame: VideoFrameQueue.Frame): Boolean {
                    Trace.beginSection("Wormhole video input")
                    try {
                        val before = System.nanoTime()
                        val index = started.dequeueInputBuffer(0)
                        if (index < 0) {
                            measurements.inputAttempt(System.nanoTime() - before)
                            return false
                        }
                        val buffer = checkNotNull(started.getInputBuffer(index))
                        buffer.clear()
                        val capacity = buffer.remaining()
                        check(capacity >= frame.bytes.size) { "Access unit exceeds codec input capacity ($capacity < ${frame.bytes.size})" }
                        buffer.put(frame.bytes)
                        started.queueInputBuffer(index, 0, frame.bytes.size, frame.receivedNanos / 1000, 0)
                        val submittedAt = System.nanoTime()
                        measurements.inputAttempt(submittedAt - before, capacity)
                        measurements.submitted(frame, submittedAt)
                        return true
                    } finally { Trace.endSection() }
                }
                override fun close() {
                    runCatching { started.stop() }
                    runCatching { started.release() }
                    measurements.codecClosed()
                }
            }
        } catch (e: Exception) {
            runCatching { candidate?.release() }
            val delayMs = minOf(100L shl createFailures.coerceAtMost(6), 5_000L)
            createFailures++
            nextCreateAttempt = System.nanoTime() + delayMs * 1_000_000L
            Log.w(TAG, "decoder create failed: ${e.message}; retry in $delayMs ms")
            null
        }
    }

    private fun drainOutput(decoder: MediaCodec, render: Boolean, measurements: VideoDiagnostics) {
        // Bound a pass so lifecycle work and input cannot be starved by continuous output.
        repeat(32) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    val available = System.nanoTime()
                    decoder.releaseOutputBuffer(index, render)
                    val releasedAt = System.nanoTime()
                    measurements.output(info.presentationTimeUs, render, available, releasedAt)
                    // This counts release-to-surface requests, not physical panel presentation.
                    if (render) telemetry.onFrameRendered(releasedAt)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    Log.i(TAG, "decoder format: $format")
                    val (w, h) = parseVideoDimensions(format)
                    if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
                        lastWidth = w
                        lastHeight = h
                        telemetry.onDimensionsChanged(w, h)
                        onVideoSizeChanged(w, h)
                    }
                }
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> return
            }
        }
    }

    internal companion object {
        const val TAG = "Wormhole"
        const val STEADY_QUEUE_FRAMES = 48
        /** How long to wait for a keyframe after the queue was reset before giving the session up. */
        const val STALL_TIMEOUT_NANOS = 5_000_000_000L

        fun parseDimensions(hasKey: (String) -> Boolean, getInt: (String) -> Int): Pair<Int, Int> {
            val hasCrop = hasKey("crop-left") && hasKey("crop-right") &&
                          hasKey("crop-top") && hasKey("crop-bottom")
            val cropWidth = if (hasCrop) getInt("crop-right") - getInt("crop-left") + 1 else 0
            val cropHeight = if (hasCrop) getInt("crop-bottom") - getInt("crop-top") + 1 else 0

            val width = if (cropWidth > 0) {
                cropWidth
            } else if (hasKey(MediaFormat.KEY_WIDTH)) {
                getInt(MediaFormat.KEY_WIDTH)
            } else 0

            val height = if (cropHeight > 0) {
                cropHeight
            } else if (hasKey(MediaFormat.KEY_HEIGHT)) {
                getInt(MediaFormat.KEY_HEIGHT)
            } else 0

            return Pair(width, height)
        }

        fun parseVideoDimensions(format: MediaFormat): Pair<Int, Int> {
            return parseDimensions(
                hasKey = { format.containsKey(it) },
                getInt = { format.getInteger(it) }
            )
        }
    }
}
