package io.github.pgodlews.wormhole

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Plays the session's audio. Decoded ALAC (audio-only senders) is paced by its presentation time in
 * [pcmDue]. AAC-ELD (mirroring, iOS audio) is decoded as it arrives and the PCM waits in [pendingPcm]; it is
 * heard at its sender timestamp plus the latency shared with video ([sync]). The track is kept running on
 * silence from the start of the session, so the first sound lands on its presentation time. After that audio
 * is never stopped to be re-timed: when it is off its presentation time it is played up to 3 % faster or
 * slower until it is back ([PcmStretcher]), and only audio that is hopelessly late is skipped. Without usable
 * timestamps it plays as it arrives, with the track kept lightly filled.
 */
class AudioRenderer(private val telemetry: StreamTelemetry? = null, private val sync: AvSync? = null) {
    private val stateLock = Any()
    private var active = false
    private var closed = false
    private var session = 0L
    private var currentVolumeDb = 0.0f
    private val frameQueue = ArrayDeque<AudioPacket>()

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Wormhole audio") }

    // Confined to worker thread
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private class PcmChunk(val data: ByteArray, val ntpReal: Long)
    private val pendingPcm = ArrayDeque<PcmChunk>()
    private val timestamp = AudioTimestamp()
    private var trackCapacityFrames = 0L
    /** Output-path latency below the track (smoothed), and the fill kept in the track against underruns. */
    private var outputPathNs = AvSync.DEFAULT_AUDIO_OUTPUT_NS - DEFAULT_SAFETY_NS
    private var safetyNs = DEFAULT_SAFETY_NS
    private var lastUnderruns = 0
    private var lastQueuedFrames = 0L
    private var lastHead = -1L
    private var headMovedAt = 0L
    private var syncDropped = 0L
    private var silenceFrames = 0L
    private var lastSyncLog = 0L
    /** Mean of (when a chunk is heard − its timestamp) over the chunks written since the last log line. */
    private var heardOffsetSum = 0L
    private var heardOffsetCount = 0
    private val stretcher = PcmStretcher()
    /** Smoothed (heard − presentation time) of the audio being written, and the speed that corrects it. */
    private var errorNs = 0L
    private var speed = 1.0
    private var minSpeed = 1.0
    private var maxSpeed = 1.0
    /** Skipping hopelessly late audio until it is nearly on time again. */
    private var skipping = false
    /** The last thing written to the track was audio (not silence): it must continue without a gap. */
    private var tailIsAudio = false
    /** A decoded-ALAC packet was seen: this session is paced by [pcmDue], without keep-alive silence. */
    @Volatile private var sawPcm = false

    private val pump = worker.scheduleWithFixedDelay({ tick() }, 0, 5, TimeUnit.MILLISECONDS)

    data class AudioPacket(val data: ByteArray, val ntpTimestamp: Long, val ct: Int)

    private var loggedFrames = 0
    private var fedFrames = 0L
    private var codecLabel = "AAC-ELD"

    // PCM pacing state, confined to the worker thread
    private var pcmFramesWritten = 0L
    private var droppedLate = 0
    private var silence = ByteArray(0)

    var audioEnabled: Boolean = true
        set(value) = synchronized(stateLock) {
            if (field != value) {
                field = value
                telemetry?.onAudioStateChanged(enabled = value, active = active)
                if (active && !closed) {
                    if (value) {
                        worker.execute { setupSession() }
                    } else {
                        frameQueue.clear()
                        worker.execute { teardownSession() }
                    }
                }
            }
        }

    /**
     * Starts an audio session. Both the mirror lifecycle and the audio-stream lifecycle
     * call this (a mirroring session has both), so a second call while active is a no-op
     * apart from refreshing the codec label.
     */
    fun beginSession(codec: String = "AAC-ELD") = synchronized(stateLock) {
        if (closed) return
        codecLabel = codec
        telemetry?.onAudioFormatConfigured(codec, SAMPLE_RATE, 2)
        if (active) return
        session++
        active = true
        loggedFrames = 0
        fedFrames = 0L
        droppedLate = 0
        frameQueue.clear()
        sawPcm = false
        sync?.resetAudio()
        telemetry?.onAudioStateChanged(enabled = audioEnabled, active = true)
        if (audioEnabled) {
            worker.execute { setupSession() }
        }
    }

    fun endSession() = synchronized(stateLock) {
        if (!active && frameQueue.isEmpty()) return
        session++
        active = false
        loggedFrames = 0
        frameQueue.clear()
        telemetry?.onAudioStateChanged(enabled = audioEnabled, active = false)
        worker.execute { teardownSession() }
    }

    fun onFrame(data: ByteArray, ntpTimestamp: Long, ct: Int) = synchronized(stateLock) {
        if (active && !closed && audioEnabled && data.isNotEmpty()) {
            telemetry?.onAudioFrameReceived(data.size)
            if (loggedFrames < 5) {
                Log.i(TAG, "Audio frame #$loggedFrames: size=${data.size}, ct=$ct, ts=$ntpTimestamp")
                loggedFrames++
            }
            if (ct == NativeBridge.CT_PCM) sawPcm = true else sync?.onAudioArrival(ntpTimestamp, WallClock.nowReal())
            // Compressed AAC is decoded within a tick or two, so this bound only matters if the decoder stalls.
            // Audio-only senders (Music / iTunes) stream about two seconds ahead of the
            // presentation time and rely on the receiver to hold the packets until then.
            val limit = if (ct == NativeBridge.CT_PCM) MAX_PCM_QUEUE_SIZE else MAX_QUEUE_SIZE
            if (frameQueue.size >= limit) {
                frameQueue.pollFirst()
            }
            frameQueue.addLast(AudioPacket(data, ntpTimestamp, ct))
        }
    }

    fun setVolume(volumeDb: Float) = synchronized(stateLock) {
        currentVolumeDb = volumeDb
        val linear = dbToLinear(volumeDb)
        telemetry?.onAudioVolumeChanged(volumeDb)
        worker.execute {
            audioTrack?.setVolume(linear)
        }
    }

    /** Sender paused or seeked (RTSP FLUSH): discard everything queued and buffered. */
    fun flush() = synchronized(stateLock) {
        frameQueue.clear()
        worker.execute {
            pendingPcm.clear()
            runCatching { decoder?.flush() }
            audioTrack?.let { track ->
                // AudioTrack.flush() is a no-op while playing
                runCatching { track.pause() }
                runCatching { track.flush() }
                pcmFramesWritten = 0L
                resetAacState()
                if (synchronized(stateLock) { active && !closed && audioEnabled }) {
                    runCatching { track.play() }
                    primeTrack(track)
                }
            }
        }
    }

    fun close() = synchronized(stateLock) {
        if (!closed) {
            closed = true
            active = false
            frameQueue.clear()
            pump.cancel(false)
            worker.execute {
                teardownSession()
                releaseAudioTrack()
            }
            worker.shutdown()
        }
    }

    private fun setupSession() {
        if (audioTrack == null) {
            initAudioTrack()
        }
        val linear = dbToLinear(synchronized(stateLock) { currentVolumeDb })
        audioTrack?.setVolume(linear)
        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
            pcmFramesWritten = 0L
            runCatching { audioTrack?.play() }
            audioTrack?.let { primeTrack(it) }
        }
        lastUnderruns = runCatching { audioTrack?.underrunCount ?: 0 }.getOrDefault(0)
        resetAacState()
        syncDropped = 0L
        silenceFrames = 0L
        // The AAC-ELD decoder is created lazily on the first compressed frame; PCM
        // (decoded ALAC from Music / iTunes) sessions never need it.
    }

    /**
     * A streaming AudioTrack starts playing (and restarts after AudioFlinger dropped it for underrunning)
     * only once its buffer is full. Fill it with silence so it runs before the first sound is due.
     */
    private fun primeTrack(track: AudioTrack) {
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        writeSilence(track, trackCapacityFrames - (pcmFramesWritten - head))
    }

    private fun resetAacState() {
        lastHead = -1L
        stretcher.reset()
        errorNs = 0L
        speed = 1.0
        skipping = false
        tailIsAudio = false
    }

    private fun teardownSession() {
        pendingPcm.clear()
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
        }
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // AAC is written only when due, so the fill (and the latency) is set by the sync logic, not by this
            // capacity; it only needs room for that fill plus a burst. ALAC is paced the same way by pcmDue.
            val bufferSize = maxOf(minBufferSize * 2, TRACK_CAPACITY_BYTES)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            trackCapacityFrames = (audioTrack?.bufferSizeInFrames ?: (bufferSize / PCM_FRAME_BYTES)).toLong()
            Log.i(TAG, "AudioTrack initialized (buffer size: $bufferSize, $trackCapacityFrames frames)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    private fun releaseAudioTrack() {
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }

    private fun createAacDecoder(): MediaCodec? {
        return try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                setByteBuffer("csd-0", ByteBuffer.wrap(CSD_AAC_ELD_44100_STEREO_480))
            }
            codec.configure(format, null, null, 0)
            codec.start()
            Log.i(TAG, "AAC-ELD audio decoder started: ${codec.name}")
            codec
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AAC-ELD audio decoder: ${e.message}", e)
            null
        }
    }

    private fun tick() {
        val isActive = synchronized(stateLock) { active && !closed }
        if (!isActive) return
        val currentTrack = audioTrack ?: return

        // PCM packets (decoded ALAC) are written straight to the track, paced by their
        // presentation time; several may fall due in one tick after a sender burst.
        var pcmBudget = MAX_PCM_WRITES_PER_TICK
        while (pcmBudget-- > 0) {
            val head = synchronized(stateLock) { frameQueue.peekFirst() } ?: break
            if (head.ct != NativeBridge.CT_PCM) break
            if (!pcmDue(head, currentTrack)) break
            synchronized(stateLock) { frameQueue.pollFirst() }
            writePcm(head, currentTrack)
        }

        // AAC: feed the decoder as packets arrive; the PCM waits in pendingPcm until it is due.
        var fed = 0
        while (fed < MAX_AAC_INPUTS_PER_TICK) {
            val packet = synchronized(stateLock) {
                frameQueue.peekFirst()?.takeIf { it.ct != NativeBridge.CT_PCM }
            } ?: break
            if (decoder == null) decoder = createAacDecoder()
            val currentDecoder = decoder ?: break
            if (!queueAac(currentDecoder, packet)) break   // decoder input full: retry next tick
            synchronized(stateLock) { if (frameQueue.peekFirst() === packet) frameQueue.pollFirst() }
            fed++
        }
        decoder?.let { drainAac(it) }
        // Unless this is an ALAC session, service the track even when no audio is waiting (keep-alive silence).
        if (currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING && (!sawPcm || pendingPcm.isNotEmpty())) {
            writePendingAac(currentTrack)
        }
    }

    /** Submits one AAC packet; false when the decoder has no free input buffer. */
    private fun queueAac(codec: MediaCodec, packet: AudioPacket): Boolean {
        try {
            val inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex < 0) return false
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return true
            inputBuffer.clear()
            inputBuffer.put(packet.data)
            // The presentation timestamp (CLOCK_REALTIME ns) rides through the decoder in microseconds.
            codec.queueInputBuffer(inputIndex, 0, packet.data.size, packet.ntpTimestamp / 1000, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error queueing audio frame: ${e.javaClass.simpleName}: ${e.message}")
        }
        return true
    }

    private fun drainAac(codec: MediaCodec) {
        try {
            var drained = 0
            while (drained < 8) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcm = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcm)
                            if (pendingPcm.size >= MAX_PENDING_CHUNKS) pendingPcm.pollFirst()
                            pendingPcm.addLast(PcmChunk(pcm, bufferInfo.presentationTimeUs * 1000L))
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        drained++
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "Audio decoder format changed: ${codec.outputFormat}")
                        val rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        telemetry?.onAudioFormatConfigured(codecLabel, rate, channels)
                        break
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error draining audio output: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Writes decoded AAC and keeps the track running.
     *
     * The next frame written is heard when everything queued has played ([AvSync.predictHeardNanos]); a chunk's
     * error is that moment minus its presentation time.
     *  - After silence (start of the stream, or after a pause) an early chunk is preceded by exactly the
     *    silence that puts it on time.
     *  - Audio that continues other audio is never re-timed with a gap. It waits while the track has enough
     *    queued, and otherwise it is written and the error is removed by playing slightly faster or slower.
     *  - Audio more than [AvSync.SKIP_ABOVE_NS] late (stale packets a sender flushes when its audio restarts,
     *    the backlog after a Wi-Fi stall) is skipped until it is nearly on time.
     */
    private fun writePendingAac(track: AudioTrack) {
        val nowMono = System.nanoTime()
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (head != lastHead) { lastHead = head; headMovedAt = nowMono }
        val running = nowMono - headMovedAt < TRACK_STALL_NS
        if (!running) primeTrack(track)
        val hasTimestamp = running && track.getTimestamp(timestamp) && nowMono - timestamp.nanoTime < TIMESTAMP_MAX_AGE_NS
        if (hasTimestamp && pcmFramesWritten > head) {
            val path = AvSync.outputPathNanos(nowMono, head, timestamp.framePosition, timestamp.nanoTime, SAMPLE_RATE)
            if (path in 0L..AvSync.MAX_AUDIO_OUTPUT_NS) outputPathNs += (path - outputPathNs) / 16
        }
        val underruns = runCatching { track.underrunCount }.getOrDefault(lastUnderruns)
        if (underruns > lastUnderruns && lastQueuedFrames > 0L && pendingPcm.isNotEmpty() && safetyNs < MAX_SAFETY_NS) {
            // The track ran dry although it had audio and more was waiting: keep more of it queued.
            safetyNs = minOf(safetyNs + SAFETY_STEP_NS, MAX_SAFETY_NS)
            Log.i(TAG, "av_sync audio underrun with audio pending; track safety now ${safetyNs / 1_000_000} ms")
        }
        lastUnderruns = underruns
        sync?.setAudioOutputLatency(outputPathNs + safetyNs)

        val realMinusMono = WallClock.realMinusMono()
        val nowReal = nowMono + realMinusMono
        val safetyFrames = safetyNs * SAMPLE_RATE / 1_000_000_000L
        // Keep only a little more than the safety fill in the track: speed changes and skips act sooner.
        val fillLimit = minOf(trackCapacityFrames, safetyFrames + FILL_ABOVE_SAFETY_FRAMES)
        var budget = MAX_AAC_WRITES_PER_TICK
        while (budget-- > 0) {
            val chunk = pendingPcm.peekFirst() ?: break
            val chunkFrames = (chunk.data.size / PCM_FRAME_BYTES).toLong()
            val queued = (pcmFramesWritten - (track.playbackHeadPosition.toLong() and 0xffffffffL)).coerceAtLeast(0L)
            val presentAt = sync?.audioPresentAt(chunk.ntpReal, nowReal)
            if (presentAt == null) {
                // No usable timestamp: play as it arrives, keeping only a little in the track.
                if (queued > UNSYNCED_FILL_FRAMES) break
                pendingPcm.pollFirst()
                writeAac(track, chunk.data, 1.0)
                while (pendingPcm.size > UNSYNCED_MAX_PENDING) pendingPcm.pollFirst()
                continue
            }
            val heardAt = AvSync.predictHeardNanos(
                System.nanoTime(), pcmFramesWritten, head,
                if (hasTimestamp) timestamp.framePosition else null, if (hasTimestamp) timestamp.nanoTime else null,
                outputPathNs, SAMPLE_RATE
            ) + realMinusMono
            val error = heardAt - presentAt
            if (error > AvSync.SKIP_ABOVE_NS || (skipping && error > AvSync.SKIP_UNTIL_NS)) {
                if (!skipping) Log.w(TAG, "av_sync skipping audio ${error / 1_000_000} ms late")
                skipping = true
                pendingPcm.pollFirst()
                syncDropped++
                errorNs = 0L
                continue
            }
            skipping = false
            if (queued >= safetyFrames && queued + chunkFrames > fillLimit) break   // enough queued for now
            if (error < -AvSync.AUDIO_EARLY_TOLERANCE_NS) {
                if (tailIsAudio) {
                    // Early, continuing other audio: wait while the track has enough, else write and slow down.
                    if (queued > safetyFrames) break
                } else {
                    // Early, after silence: more silence puts it exactly on time.
                    val gapFrames = -error * SAMPLE_RATE / 1_000_000_000L
                    val room = trackCapacityFrames - queued - chunkFrames
                    if (gapFrames > room) {
                        // Too far ahead to queue yet: keep the track fed and come back.
                        if (queued < fillLimit) writeSilence(track, minOf(room, fillLimit - queued))
                        break
                    }
                    writeSilence(track, gapFrames)
                    pendingPcm.pollFirst()
                    noteWritten(0L, presentAt - chunk.ntpReal, nowReal)
                    writeAac(track, chunk.data, 1.0)
                    continue
                }
            }
            pendingPcm.pollFirst()
            noteWritten(error, heardAt - chunk.ntpReal, nowReal)
            writeAac(track, chunk.data, speed)
        }

        lastQueuedFrames = (pcmFramesWritten - (track.playbackHeadPosition.toLong() and 0xffffffffL)).coerceAtLeast(0L)
        if (pendingPcm.isEmpty() && lastQueuedFrames < safetyFrames) {
            // No audio waiting: keep the safety fill with silence, so a pause in the sender's audio neither
            // lets AudioFlinger drop the track (it would then need a full buffer to restart) nor loses its timing.
            writeSilence(track, safetyFrames - lastQueuedFrames)
            lastQueuedFrames = safetyFrames
        }
        if (nowMono - lastSyncLog >= SYNC_LOG_INTERVAL_NS && (pcmFramesWritten > silenceFrames || pendingPcm.isNotEmpty())) {
            lastSyncLog = nowMono
            val fillMs = lastQueuedFrames * 1000 / SAMPLE_RATE
            val heard = if (heardOffsetCount > 0) "%.1f".format(heardOffsetSum / heardOffsetCount / 1e6) else "-"
            Log.i(TAG, "av_sync audio heardMinusTs=${heard}ms error=${"%.1f".format(errorNs / 1e6)}ms " +
                "speed=${"%.3f".format(minSpeed)}..${"%.3f".format(maxSpeed)} fill=${fillMs}ms pending=${pendingPcm.size} " +
                "outputPath=${outputPathNs / 1_000_000}ms safety=${safetyNs / 1_000_000}ms skipped=$syncDropped " +
                "silence=${silenceFrames * 1000 / SAMPLE_RATE}ms underruns=$lastUnderruns ${sync?.describe() ?: "no-sync"}")
            heardOffsetSum = 0L
            heardOffsetCount = 0
            minSpeed = speed
            maxSpeed = speed
        }
    }

    /** Records the error of the chunk about to be written and derives the speed for it and the next ones. */
    private fun noteWritten(error: Long, heardMinusTs: Long, nowReal: Long) {
        errorNs = if (error == 0L) 0L else errorNs + (error - errorNs) / ERROR_SMOOTHING
        speed = AvSync.audioSpeed(errorNs)
        minSpeed = minOf(minSpeed, speed)
        maxSpeed = maxOf(maxSpeed, speed)
        sync?.setAudioError(errorNs, nowReal)
        heardOffsetSum += heardMinusTs
        heardOffsetCount++
    }

    private fun writeAac(track: AudioTrack, data: ByteArray, speed: Double) {
        try {
            val pcm = stretcher.process(data, speed)
            val written = track.write(pcm, 0, pcm.size)
            if (written > 0) {
                pcmFramesWritten += written / PCM_FRAME_BYTES
                tailIsAudio = true
                telemetry?.onAudioFramePlayed()
                fedFrames++
                if (fedFrames % 500 == 0L) Log.i(TAG, "Audio PCM frames played: $fedFrames")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error writing PCM: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun writeSilence(track: AudioTrack, frames: Long) {
        if (frames <= 0L) return
        val bytes = (frames * PCM_FRAME_BYTES).toInt()
        if (silence.size < bytes) silence = ByteArray(bytes)
        val written = runCatching { track.write(silence, 0, bytes) }.getOrDefault(0)
        if (written > 0) {
            pcmFramesWritten += written / PCM_FRAME_BYTES
            silenceFrames += written / PCM_FRAME_BYTES
            tailIsAudio = false
            stretcher.reset()
        }
    }

    /**
     * Whether a PCM packet should be written now. [AudioPacket.ntpTimestamp] is the
     * CLOCK_REALTIME nanosecond presentation time computed by the core from the sender's
     * sync packets (0 before the first sync). The packet plays once everything already
     * queued in the track has been consumed, so it is due when that moment is within
     * [PCM_EARLY_NS] of its presentation time. Packets more than [PCM_LATE_DROP_NS] late
     * are dropped so playback re-synchronises instead of lagging forever.
     */
    private fun pcmDue(packet: AudioPacket, track: AudioTrack): Boolean {
        val ntp = packet.ntpTimestamp
        if (ntp <= 0L) return true
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val played = track.playbackHeadPosition.toLong() and 0xffffffffL
        val queuedFrames = (pcmFramesWritten - played).coerceAtLeast(0L)
        val playAtNs = nowNs + queuedFrames * 1_000_000_000L / SAMPLE_RATE
        val lead = ntp - playAtNs
        if (lead > PCM_MAX_PLAUSIBLE_LEAD_NS) return true   // clock disagreement; do not stall
        return lead <= PCM_EARLY_NS
    }

    private fun writePcm(packet: AudioPacket, track: AudioTrack) {
        val ntp = packet.ntpTimestamp
        if (ntp > 0L) {
            val late = System.currentTimeMillis() * 1_000_000L - ntp
            if (late > PCM_LATE_DROP_NS) {
                if (droppedLate++ % 100 == 0) Log.w(TAG, "Dropping PCM packet ${late / 1_000_000} ms late")
                return
            }
        }
        try {
            val written = track.write(packet.data, 0, packet.data.size)
            if (written > 0) {
                pcmFramesWritten += written / PCM_FRAME_BYTES
                fedFrames++
                telemetry?.onAudioFramePlayed()
                if (fedFrames % 500 == 0L) {
                    Log.i(TAG, "Audio PCM frames played: $fedFrames")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error writing PCM: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    internal companion object {
        const val TAG = "WormholeAudio"
        const val SAMPLE_RATE = 44100
        const val MAX_QUEUE_SIZE = 30
        const val MAX_AAC_INPUTS_PER_TICK = 4
        const val MAX_AAC_WRITES_PER_TICK = 16
        /** ~2.8 s of 480-frame chunks: AAC may arrive well before it is due. */
        const val MAX_PENDING_CHUNKS = 256
        /** Without timestamps: keep at most ~100 ms in the track and ~330 ms waiting. */
        const val UNSYNCED_FILL_FRAMES = SAMPLE_RATE / 10L
        const val UNSYNCED_MAX_PENDING = 30
        /** 200 ms of 16-bit stereo at 44.1 kHz. */
        const val TRACK_CAPACITY_BYTES = SAMPLE_RATE / 5 * 4
        const val DEFAULT_SAFETY_NS = 40_000_000L
        const val SAFETY_STEP_NS = 10_000_000L
        const val MAX_SAFETY_NS = 150_000_000L
        const val TIMESTAMP_MAX_AGE_NS = 500_000_000L
        /** The track counts as not playing when its position has not moved for this long (mixer period: 40 ms). */
        const val TRACK_STALL_NS = 400_000_000L
        const val SYNC_LOG_INTERVAL_NS = 5_000_000_000L
        /** ~60 ms of audio kept in the track on top of the safety fill. */
        const val FILL_ABOVE_SAFETY_FRAMES = SAMPLE_RATE * 6L / 100
        const val ERROR_SMOOTHING = 8
        /** ~4 s of 352-frame ALAC packets: audio-only senders run about 2 s ahead. */
        const val MAX_PCM_QUEUE_SIZE = 512
        const val MAX_PCM_WRITES_PER_TICK = 16
        const val PCM_FRAME_BYTES = 2 * 2   // 16-bit stereo
        const val PCM_EARLY_NS = 60_000_000L
        const val PCM_LATE_DROP_NS = 1_000_000_000L
        const val PCM_MAX_PLAUSIBLE_LEAD_NS = 10_000_000_000L

        /** AudioSpecificConfig for AAC-ELD: 44.1 kHz, 2 channels, 480 samples/frame */
        val CSD_AAC_ELD_44100_STEREO_480 = byteArrayOf(
            0xF8.toByte(), // 11111 (escape 31) + 000 (part of 39-32=7)
            0xE8.toByte(), // 111 (rest of 7) + 0100 (44.1kHz index 4) + 0 (part of 2ch)
            0x50.toByte(), // 010 (rest of 2ch) + 1 (480 spf) + 0000 (resilience flags)
            0x00.toByte()  // 0000 (ELDEXT_TERM) + 00 (epConfig) + 00 (padding)
        )

        /** AudioSpecificConfig for AAC-ELD: 44.1 kHz, 2 channels, 512 samples/frame */
        val CSD_AAC_ELD_44100_STEREO_512 = byteArrayOf(
            0xF8.toByte(),
            0xE8.toByte(),
            0x20.toByte(), // 010 (rest of 2ch) + 0 (512 spf) + 0000 (resilience flags)
            0x00.toByte()  // 0000 (ELDEXT_TERM) + 00 (epConfig) + 00 (padding)
        )

        fun dbToLinear(volumeDb: Float): Float {
            return if (volumeDb <= -144.0f) 0.0f
            else 10.0f.pow(volumeDb / 20.0f).coerceIn(0.0f, 1.0f)
        }
    }
}
