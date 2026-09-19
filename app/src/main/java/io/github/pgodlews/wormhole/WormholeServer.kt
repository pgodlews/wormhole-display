package io.github.pgodlews.wormhole

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide manager that owns the native AirPlay server, audio/video renderers,
 * connection history, and reactive state. Shared between [WormholeService] and [MainActivity].
 */
object WormholeServer {
    private const val TAG = "WormholeServer"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    lateinit var identity: WormholeIdentity
        private set
    lateinit var history: ConnectionHistory
        private set

    // State flows observed by UI and Service
    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    private val _clientName = MutableStateFlow<String?>(null)
    val clientName: StateFlow<String?> = _clientName.asStateFlow()

    /** An audio-only AirPlay session (Music / iTunes, iOS audio) is playing through the speakers. */
    private val _isAudioStreaming = MutableStateFlow(false)
    val isAudioStreaming: StateFlow<Boolean> = _isAudioStreaming.asStateFlow()

    private val _statusText = MutableStateFlow("Starting receiver…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow<Float?>(null)
    val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio.asStateFlow()

    private val _recentConnections = MutableStateFlow<List<ConnectionEntry>>(emptyList())
    val recentConnections: StateFlow<List<ConnectionEntry>> = _recentConnections.asStateFlow()

    private val _displayInfo = MutableStateFlow(DisplayInfo(1920, 1080, 60, "16:9", "1920 × 1080 (Default)"))
    val displayInfo: StateFlow<DisplayInfo> = _displayInfo.asStateFlow()

    private val _orientationSetting = MutableStateFlow(ScreenOrientation.LANDSCAPE)
    val orientationSetting: StateFlow<ScreenOrientation> = _orientationSetting.asStateFlow()

    private val _isPortrait = MutableStateFlow(false)
    val isPortrait: StateFlow<Boolean> = _isPortrait.asStateFlow()

    data class OrientationNotice(
        val isPortrait: Boolean,
        val width: Int,
        val height: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _orientationNotice = MutableStateFlow<OrientationNotice?>(null)
    val orientationNotice: StateFlow<OrientationNotice?> = _orientationNotice.asStateFlow()

    fun dismissOrientationNotice() {
        _orientationNotice.value = null
    }

    private var lastRawWidth = 1920
    private var lastRawHeight = 1080
    private var lastFps = 60

    var audioEnabled: Boolean = true
        private set
    var debugOverlayEnabled: Boolean = false
        private set
    var hevcEnabled: Boolean = false
        private set
    private val hevcFailedThisProcess = AtomicBoolean(false)
    var runInBackground: Boolean = true
        private set
    var startOnBoot: Boolean = true
        private set

    private var sessionStartTime = 0L
    private var sessionClientName: String? = null
    private var sessionResolution: String = ""
    private var sessionCodec: String = ""
    private var audioSessionStartTime = 0L

    // Renderers
    val renderer = VideoRenderer(
        reconnectRequired = { requestReconnect() },
        onHevcFailure = {
            hevcFailedThisProcess.set(true)
            Log.w(TAG, "HEVC session failed; next connection will offer H.264 only until H.265 is toggled or app restarts")
        },
        onVideoSizeChanged = { w, h ->
            _videoAspectRatio.value = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
            if (w > 0 && h > 0) {
                sessionResolution = "$w × $h"
            }
        }
    )
    val audioRenderer = AudioRenderer(renderer.telemetry)

    // Pushes AirPlay now-playing metadata to Home Assistant (no-op until configured in prefs).
    private lateinit var haPublisher: HomeAssistantPublisher

    // Activity state
    @Volatile
    var isActivityResumed: Boolean = false

    var onIncomingStreamBackgroundCallback: (() -> Unit)? = null

    // Server lifecycle internals
    private val serverExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "Wormhole server") }
    private val serverLifecycle = ServerLifecycle(serverExecutor)
    private var lease: ServerLifecycle.Lease? = null

    private val destroyed = AtomicBoolean(false)
    private val callbacksEnabled = AtomicBoolean(false)
    private val callbackGeneration = AtomicLong()
    private val mirrorGeneration = AtomicLong()

    val idleStatus: String
        get() = "Visible in Screen Mirroring and as an AirPlay speaker as “${identity.serviceName}”"

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

        identity = WormholeIdentity(appContext)
        history = ConnectionHistory(appContext)
        _recentConnections.value = history.getRecent()

        audioEnabled = prefs.getBoolean("audio_enabled", true)
        debugOverlayEnabled = prefs.getBoolean("debug_overlay_enabled", false)
        hevcEnabled = prefs.getBoolean("hevc_enabled", false)
        runInBackground = prefs.getBoolean("run_in_background", true)
        startOnBoot = prefs.getBoolean("start_on_boot", true)

        audioRenderer.audioEnabled = audioEnabled

        haPublisher = HomeAssistantPublisher(prefs)

        val autoSupported = WormholeIdentity.hasOrientationSensor(appContext)
        val defaultOrientation = ScreenOrientation.LANDSCAPE
        val savedSetting = ScreenOrientation.fromId(
            prefs.getString("orientation_setting", defaultOrientation.id)
        )
        _orientationSetting.value = if (!autoSupported && savedSetting == ScreenOrientation.AUTO) {
            defaultOrientation
        } else {
            savedSetting
        }

        _displayInfo.value = queryDisplayInfo(appContext)
    }

    fun setSurface(surface: Surface?) {
        renderer.setSurface(surface)
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        audioRenderer.audioEnabled = enabled
        prefs.edit().putBoolean("audio_enabled", enabled).apply()
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        debugOverlayEnabled = enabled
        prefs.edit().putBoolean("debug_overlay_enabled", enabled).apply()
    }

    fun setHevcEnabled(enabled: Boolean) {
        if (hevcEnabled == enabled) return
        hevcEnabled = enabled
        hevcFailedThisProcess.set(false)
        prefs.edit().putBoolean("hevc_enabled", enabled).apply()
        restartServer()
    }

    fun setRunInBackground(enabled: Boolean) {
        runInBackground = enabled
        prefs.edit().putBoolean("run_in_background", enabled).apply()
    }

    fun setStartOnBoot(enabled: Boolean) {
        startOnBoot = enabled
        prefs.edit().putBoolean("start_on_boot", enabled).apply()
    }

    fun updateServiceName(newName: String) {
        identity.serviceName = newName
        restartServer()
    }

    // --- Home Assistant now-playing config (read by HomeAssistantPublisher) ---
    val haUrl: String get() = if (::prefs.isInitialized) prefs.getString("ha_url", "")!! else ""
    val haToken: String get() = if (::prefs.isInitialized) prefs.getString("ha_token", "")!! else ""
    val haEntity: String get() = if (::prefs.isInitialized) prefs.getString("ha_entity", HomeAssistantPublisher.DEFAULT_ENTITY)!! else HomeAssistantPublisher.DEFAULT_ENTITY
    // Enabled defaults to on when a URL and token are already present (back-compat with
    // setups configured before this toggle existed).
    val haEnabled: Boolean get() = ::prefs.isInitialized &&
        prefs.getBoolean("ha_enabled", haUrl.isNotBlank() && haToken.isNotBlank())

    fun setHaConfig(enabled: Boolean, url: String, token: String, entity: String) {
        prefs.edit()
            .putBoolean("ha_enabled", enabled)
            .putString("ha_url", url)
            .putString("ha_token", token)
            .putString("ha_entity", entity.ifBlank { HomeAssistantPublisher.DEFAULT_ENTITY })
            .apply()
        // Apply immediately if the receiver is running.
        haPublisher.stop()
        if (callbacksEnabled.get()) {
            haPublisher.start(NetworkInfoHelper.getLocalIpAddress() ?: "")
        }
    }

    fun setOrientationSetting(setting: ScreenOrientation) {
        val autoSupported = WormholeIdentity.hasOrientationSensor(appContext, lastRawWidth, lastRawHeight)
        val targetSetting = if (!autoSupported && setting == ScreenOrientation.AUTO) {
            ScreenOrientation.LANDSCAPE
        } else {
            setting
        }
        if (_orientationSetting.value == targetSetting) return
        _orientationSetting.value = targetSetting
        prefs.edit().putString("orientation_setting", targetSetting.id).apply()

        val isSysPortrait = appContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
                lastRawWidth < lastRawHeight
        val targetPortrait = when (targetSetting) {
            ScreenOrientation.PORTRAIT -> true
            ScreenOrientation.LANDSCAPE -> false
            ScreenOrientation.AUTO -> isSysPortrait
        }
        applyOrientation(targetPortrait)
    }

    fun onConfigurationChanged(width: Int, height: Int, fps: Int, isPortrait: Boolean) {
        lastRawWidth = width
        lastRawHeight = height
        lastFps = fps
        val autoSupported = WormholeIdentity.hasOrientationSensor(appContext, width, height)
        val targetPortrait = when (_orientationSetting.value) {
            ScreenOrientation.PORTRAIT -> true
            ScreenOrientation.LANDSCAPE -> false
            ScreenOrientation.AUTO -> if (!autoSupported) false else isPortrait
        }
        applyOrientation(targetPortrait)
    }

    private fun applyOrientation(targetPortrait: Boolean) {
        val orientationChanged = _isPortrait.value != targetPortrait
        _isPortrait.value = targetPortrait
        val newDisplay = computeDisplayInfo(lastRawWidth, lastRawHeight, lastFps, targetPortrait)
        val displayChanged = newDisplay != _displayInfo.value
        if (displayChanged || orientationChanged) {
            _displayInfo.value = newDisplay
            if (_isMirroring.value) {
                Log.i(TAG, "Disconnecting client: orientation_changed (${if (targetPortrait) "portrait" else "landscape"})")
                _orientationNotice.value = OrientationNotice(targetPortrait, newDisplay.width, newDisplay.height)
            }
            if (isServerRunning()) {
                restartServer()
            }
        }
    }

    fun isServerRunning(): Boolean = callbacksEnabled.get()

    fun startServer() {
        check(::appContext.isInitialized) { "WormholeServer must be initialized with context" }
        if (callbacksEnabled.get()) return

        val keyFile = File(appContext.filesDir, "server-key.pem").absolutePath

        lease = ServerLifecycle.Lease(
            start = {
                val currentDisplay = _displayInfo.value
                val requestHevc = hevcEnabled
                callbackGeneration.incrementAndGet()
                val startEvent = mirrorGeneration.incrementAndGet()
                if (mirrorGeneration.get() == startEvent) {
                    _isMirroring.value = false
                    _clientName.value = null
                    _statusText.value = "Starting receiver…"
                }
                identity.holdMulticastLock()
                callbacksEnabled.set(true)
                NativeBridge.nativeSetListener(listener)
                renderer.hevcDecoder = if (requestHevc && !hevcFailedThisProcess.get()) VideoDecoderSupport.findHevc(
                    currentDisplay.width, currentDisplay.height, currentDisplay.refreshRate
                ) else null
                Log.i(TAG, "HEVC requested=$requestHevc hardware=${renderer.hevcDecoder}")
                Log.i(TAG, "Starting receiver advertising ${currentDisplay.width}x${currentDisplay.height}@${currentDisplay.refreshRate}Hz (portrait=${_isPortrait.value}, setting=${_orientationSetting.value})")
                val port = NativeBridge.nativeStart(
                    keyFile, identity.deviceIdHex, identity.serviceName,
                    currentDisplay.width, currentDisplay.height, currentDisplay.refreshRate,
                    renderer.hevcDecoder != null
                )
                check(port > 0) { "Server failed to start ($port)" }
                haPublisher.start(NetworkInfoHelper.getLocalIpAddress() ?: "")
                if (mirrorGeneration.get() == startEvent) {
                    _isMirroring.value = false
                    _clientName.value = null
                    _statusText.value = "$idleStatus — select it to connect"
                }
            },
            stop = {
                callbacksEnabled.set(false)
                callbackGeneration.incrementAndGet()
                renderer.endSession()
                audioRenderer.endSession()
                NativeBridge.nativeSetListener(null)
                try {
                    NativeBridge.nativeStop()
                } finally {
                    haPublisher.stop()
                    identity.releaseMulticastLock()
                }
            },
            failed = { error ->
                _isMirroring.value = false
                _statusText.value = error.message ?: "Server failed"
            }
        )
        lease?.let { serverLifecycle.start(it) }
    }

    fun stopServer() {
        callbacksEnabled.set(false)
        callbackGeneration.incrementAndGet()
        lease?.let { serverLifecycle.stop(it) }
        lease = null
        _isMirroring.value = false
        _isAudioStreaming.value = false
        _clientName.value = null
        _statusText.value = "Receiver stopped"
    }

    fun restartServer() {
        val currentLease = lease
        if (currentLease != null && callbacksEnabled.get()) {
            recordSessionEnd()
            renderer.endSession()
            audioRenderer.endSession()
            mirrorGeneration.incrementAndGet()
            _isMirroring.value = false
            _isAudioStreaming.value = false
            _videoAspectRatio.value = null
            _clientName.value = null
            _statusText.value = "Restarting receiver…"
            serverLifecycle.restart(currentLease)
        } else {
            startServer()
        }
    }

    /** The user left the stream on the device (Back/Home): drop the sender by restarting the receiver. */
    fun disconnectClient(reason: String) {
        if (!_isMirroring.value && !_isAudioStreaming.value) return
        Log.i("Wormhole", "Disconnecting client: $reason")
        restartServer()
    }

    private fun requestReconnect() {
        if (destroyed.get() || !callbacksEnabled.compareAndSet(true, false)) return
        recordSessionEnd()
        renderer.endSession()
        audioRenderer.endSession()
        mirrorGeneration.incrementAndGet()
        _isMirroring.value = false
        _isAudioStreaming.value = false
        _videoAspectRatio.value = null
        _clientName.value = null
        _statusText.value = "Stream interrupted — reconnect from Screen Mirroring"
        lease?.let { serverLifecycle.restart(it) }
    }

    private fun recordSessionEnd() {
        if (sessionStartTime > 0L) {
            val duration = (System.currentTimeMillis() - sessionStartTime) / 1000
            val name = sessionClientName ?: _clientName.value ?: "Unknown device"
            val res = sessionResolution.ifBlank { "${_displayInfo.value.width} × ${_displayInfo.value.height}" }
            val codec = sessionCodec.ifBlank { "H.264" }
            history.recordConnection(name, sessionStartTime, duration, res, codec)
            _recentConnections.value = history.getRecent()
            sessionStartTime = 0L
            sessionClientName = null
            sessionResolution = ""
            sessionCodec = ""
        }
    }

    private val listener = object : NativeBridge.Listener {
        override fun onVideoFrame(data: ByteArray, ingressNanos: Long, ntpLocalNanos: Long, ntpRemoteNanos: Long, hevc: Boolean) {
            sessionCodec = if (hevc) "H.265" else "H.264"
            if (callbacksEnabled.get()) renderer.onFrame(data, ingressNanos, ntpLocalNanos, ntpRemoteNanos, hevc)
        }

        override fun onAudioFrame(data: ByteArray, ntpTimestamp: Long, ct: Int) {
            if (callbacksEnabled.get()) audioRenderer.onFrame(data, ntpTimestamp, ct)
        }

        override fun onAudioVolume(volume: Float) {
            if (callbacksEnabled.get()) audioRenderer.setVolume(volume)
        }

        override fun onAudioFlush() {
            if (callbacksEnabled.get()) audioRenderer.flush()
        }

        override fun onNowPlaying(title: String, artist: String, album: String, year: Int) {
            if (callbacksEnabled.get()) haPublisher.onMetadata(title, artist, album, year)
        }

        override fun onCoverArt(data: ByteArray, isPng: Boolean) {
            if (callbacksEnabled.get()) haPublisher.onCoverArt(data, isPng)
        }

        override fun onProgress(positionSec: Double, durationSec: Double) {
            if (callbacksEnabled.get()) haPublisher.onProgress(positionSec, durationSec)
        }

        override fun onClientConnected(name: String) {
            sessionClientName = name
            if (callbacksEnabled.get()) {
                _clientName.value = name
                _statusText.value = "Connected: $name"
            }
        }

        override fun onClientDisconnected() {
            // Socket close is diagnostic only
        }

        override fun onAudioRunning(running: Boolean, ct: Int) {
            if (!callbacksEnabled.get()) return
            val codec = when (ct) {
                NativeBridge.CT_ALAC -> "ALAC"
                NativeBridge.CT_AAC_ELD -> "AAC-ELD"
                else -> "ct=$ct"
            }
            if (running) {
                audioRenderer.beginSession(codec)
                haPublisher.onSessionStart()
                // Audio arriving outside a mirroring session is an AirPlay speaker session
                // (Music / iTunes on a Mac, or iOS audio-only). Mirroring owns the UI otherwise.
                if (!_isMirroring.value) {
                    audioSessionStartTime = System.currentTimeMillis()
                    _isAudioStreaming.value = true
                    _statusText.value = "Playing audio from ${_clientName.value ?: "AirPlay"}"
                    Log.i(TAG, "Audio-only session started ($codec)")
                }
            } else {
                if (_isAudioStreaming.value) {
                    if (audioSessionStartTime > 0L) {
                        val duration = (System.currentTimeMillis() - audioSessionStartTime) / 1000
                        history.recordConnection(
                            sessionClientName ?: _clientName.value ?: "AirPlay audio",
                            audioSessionStartTime, duration, "Audio only", codec
                        )
                        _recentConnections.value = history.getRecent()
                    }
                    audioSessionStartTime = 0L
                    _isAudioStreaming.value = false
                    sessionClientName = null
                    _clientName.value = null
                    _statusText.value = idleStatus
                    Log.i(TAG, "Audio-only session ended")
                }
                haPublisher.onSessionEnd()
                if (!_isMirroring.value) {
                    audioRenderer.endSession()
                }
            }
        }

        override fun onMirrorRunning(running: Boolean) {
            if (!callbacksEnabled.get()) return
            if (running) {
                sessionStartTime = System.currentTimeMillis()
                sessionResolution = ""
                sessionCodec = ""
                dismissOrientationNotice()
                renderer.beginSession()
                audioRenderer.beginSession()
            } else {
                recordSessionEnd()
                renderer.endSession()
                audioRenderer.endSession()
                _videoAspectRatio.value = null
            }
            val event = mirrorGeneration.incrementAndGet()
            if (event == mirrorGeneration.get()) {
                _isMirroring.value = running
                if (running) {
                    _isAudioStreaming.value = false
                    _statusText.value = "Mirroring ${_clientName.value ?: "client"}"
                    if (!isActivityResumed) {
                        onIncomingStreamBackgroundCallback?.invoke()
                    }
                } else {
                    _clientName.value = null
                    _statusText.value = idleStatus
                }
            }
        }

        override fun onStreamError() = requestReconnect()
    }

    private fun queryDisplayInfo(context: Context): DisplayInfo {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay
        }
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = display?.mode?.refreshRate?.toInt()
            ?: display?.refreshRate?.toInt()
            ?: 60
        lastRawWidth = if (metrics.widthPixels > 0) metrics.widthPixels else 1920
        lastRawHeight = if (metrics.heightPixels > 0) metrics.heightPixels else 1080
        lastFps = fps

        val autoSupported = WormholeIdentity.hasOrientationSensor(context, lastRawWidth, lastRawHeight)
        val isSysPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
                lastRawWidth < lastRawHeight
        val targetPortrait = when (_orientationSetting.value) {
            ScreenOrientation.PORTRAIT -> true
            ScreenOrientation.LANDSCAPE -> false
            ScreenOrientation.AUTO -> if (!autoSupported) false else isSysPortrait
        }
        _isPortrait.value = targetPortrait
        return computeDisplayInfo(lastRawWidth, lastRawHeight, lastFps, targetPortrait)
    }
}
