package io.github.pgodlews.wormhole

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

data class DisplayInfo(
    val width: Int,
    val height: Int,
    val refreshRate: Int,
    val aspectRatioLabel: String,
    val recommendedResolutions: String
)

fun computeDisplayInfo(rawWidth: Int, rawHeight: Int, refreshRate: Int, isPortrait: Boolean = false): DisplayInfo {
    val longSide = maxOf(rawWidth, rawHeight)
    val shortSide = minOf(rawWidth, rawHeight)
    val w = if (isPortrait) shortSide else longSide
    val h = if (isPortrait) longSide else shortSide
    val fps = if (refreshRate > 0) refreshRate else 60
    val ratio = w.toDouble() / h.toDouble()

    val (label, resolutions) = if (!isPortrait) {
        when {
            // 16:10 (1.60) - e.g. 1280x800
            kotlin.math.abs(ratio - 1.6) < 0.05 -> {
                "16:10" to "$w × $h (Default) • 1440 × 900 • 1680 × 1050 • 1920 × 1200"
            }
            // 3:2 (1.50) - e.g. 2160x1440
            kotlin.math.abs(ratio - 1.5) < 0.05 -> {
                "3:2" to "$w × $h (Default) • 1620 × 1080 • 1344 × 896 • 1080 × 720"
            }
            // 16:9 (1.777...) - e.g. 1920x1080
            kotlin.math.abs(ratio - 16.0 / 9.0) < 0.05 -> {
                "16:9" to if (w >= 3840) "$w × $h (Default) • 2560 × 1440 • 1920 × 1080 • 1280 × 720"
                         else if (w >= 2560) "$w × $h (Default) • 1920 × 1080 • 1600 × 900 • 1280 × 720"
                         else "$w × $h (Default) • 1600 × 900 • 1366 × 768 • 1280 × 720"
            }
            // 4:3 (1.333...) - e.g. 1024x768
            kotlin.math.abs(ratio - 4.0 / 3.0) < 0.05 -> {
                "4:3" to "$w × $h (Default) • 1600 × 1200 • 1400 × 1050 • 1024 × 768"
            }
            else -> {
                fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                val d = gcd(w, h)
                val rw = w / d
                val rh = h / d
                val aspect = if (rw <= 20 && rh <= 20) "$rw:$rh" else String.format(java.util.Locale.US, "%.2f:1", ratio)
                aspect to "$w × $h (Default)"
            }
        }
    } else {
        when {
            // 10:16 (0.625) - e.g. 800x1280 (8" Portal Mini)
            kotlin.math.abs(ratio - 10.0 / 16.0) < 0.05 -> {
                "10:16" to "$w × $h (Default) • 900 × 1440 • 1050 × 1680 • 1200 × 1920"
            }
            // 2:3 (0.666...) - e.g. 1440x2160
            kotlin.math.abs(ratio - 2.0 / 3.0) < 0.05 -> {
                "2:3" to "$w × $h (Default) • 1080 × 1620 • 896 × 1344 • 720 × 1080"
            }
            // 9:16 (0.5625) - e.g. 1080x1920 (Portal+ Gen 1)
            kotlin.math.abs(ratio - 9.0 / 16.0) < 0.05 -> {
                "9:16" to if (h >= 3840) "$w × $h (Default) • 1440 × 2560 • 1080 × 1920 • 720 × 1280"
                         else if (h >= 2560) "$w × $h (Default) • 1080 × 1920 • 900 × 1600 • 720 × 1280"
                         else "$w × $h (Default) • 900 × 1600 • 768 × 1366 • 720 × 1280"
            }
            // 3:4 (0.75) - e.g. 768x1024
            kotlin.math.abs(ratio - 3.0 / 4.0) < 0.05 -> {
                "3:4" to "$w × $h (Default) • 1200 × 1600 • 1050 × 1400 • 768 × 1024"
            }
            else -> {
                fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                val d = gcd(w, h)
                val rw = w / d
                val rh = h / d
                val aspect = if (rw <= 20 && rh <= 20) "$rw:$rh" else String.format(java.util.Locale.US, "1:%.2f", 1.0 / ratio)
                aspect to "$w × $h (Default)"
            }
        }
    }

    return DisplayInfo(
        width = w,
        height = h,
        refreshRate = fps,
        aspectRatioLabel = label,
        recommendedResolutions = resolutions
    )
}

class MainActivity : ComponentActivity() {
    private var networkDetails by mutableStateOf(NetworkInfoHelper.NetworkDetails("...", "...", false))
    // Android 10+ only lets the service bring this activity forward with "Display over other apps".
    private var canAutoOpen by mutableStateOf(true)
 
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        WormholeServer.init(applicationContext)

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = windowManager.defaultDisplay.mode?.refreshRate?.toInt()
            ?: windowManager.defaultDisplay.refreshRate.toInt()
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        WormholeServer.onConfigurationChanged(metrics.widthPixels, metrics.heightPixels, fps, isPortrait)

        updateOrientationMode(WormholeServer.orientationSetting.value)

        WormholeService.start(this)

        setContent {
            val mirroring by WormholeServer.isMirroring.collectAsState()
            val client by WormholeServer.clientName.collectAsState()
            val status by WormholeServer.statusText.collectAsState()
            val videoAspectRatio by WormholeServer.videoAspectRatio.collectAsState()
            val recentConnections by WormholeServer.recentConnections.collectAsState()
            val displayInfo by WormholeServer.displayInfo.collectAsState()
            val orientationSetting by WormholeServer.orientationSetting.collectAsState()
            val orientationNotice by WormholeServer.orientationNotice.collectAsState()

            LaunchedEffect(orientationSetting) {
                updateOrientationMode(orientationSetting)
            }

            var audioEnabled by remember { mutableStateOf(WormholeServer.audioEnabled) }
            var debugOverlayEnabled by remember { mutableStateOf(WormholeServer.debugOverlayEnabled) }
            var hevcEnabled by remember { mutableStateOf(WormholeServer.hevcEnabled) }
            var runInBackground by remember { mutableStateOf(WormholeServer.runInBackground) }
            var startOnBoot by remember { mutableStateOf(WormholeServer.startOnBoot) }
            var serviceName by remember { mutableStateOf(WormholeServer.identity.serviceName) }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7DE2CE),
                    background = Color(0xFF09121E),
                    surface = Color(0xFF142131),
                    surfaceVariant = Color(0xFF1B2B3E),
                    onSurface = Color(0xFFF1F5F9),
                    onSurfaceVariant = Color(0xFF94A3B8)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (mirroring) {
                        BackHandler { WormholeServer.disconnectClient("back") }
                        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                            val surfaceModifier = if (videoAspectRatio != null && videoAspectRatio!! > 0f) {
                                Modifier.aspectRatio(videoAspectRatio!!)
                            } else {
                                Modifier.fillMaxSize()
                            }
                            AndroidView(
                                factory = { context ->
                                    SurfaceView(context).apply {
                                        holder.addCallback(object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                WormholeServer.setSurface(holder.surface)
                                            }
                                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                WormholeServer.setSurface(null)
                                            }
                                        })
                                    }
                                },
                                modifier = surfaceModifier.background(Color.Black)
                            )
                            if (debugOverlayEnabled) {
                                TelemetryOverlay(
                                    telemetry = WormholeServer.renderer.telemetry,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(16.dp)
                                )
                            }
                        }
                    } else {
                        DashboardScreen(
                            serviceName = serviceName,
                            onServiceNameChanged = { newName ->
                                WormholeServer.updateServiceName(newName)
                                serviceName = WormholeServer.identity.serviceName
                                networkDetails = NetworkInfoHelper.getNetworkDetails(this@MainActivity)
                            },
                            status = status,
                            networkDetails = networkDetails,
                            displayInfo = displayInfo,
                            audioEnabled = audioEnabled,
                            onAudioEnabledChanged = { enabled ->
                                audioEnabled = enabled
                                WormholeServer.setAudioEnabled(enabled)
                            },
                            debugOverlayEnabled = debugOverlayEnabled,
                            hevcEnabled = hevcEnabled,
                            onHevcEnabledChanged = { enabled ->
                                hevcEnabled = enabled
                                WormholeServer.setHevcEnabled(enabled)
                            },
                            onDebugOverlayEnabledChanged = { enabled ->
                                debugOverlayEnabled = enabled
                                WormholeServer.setDebugOverlayEnabled(enabled)
                            },
                            runInBackground = runInBackground,
                            onRunInBackgroundChanged = { enabled ->
                                runInBackground = enabled
                                WormholeServer.setRunInBackground(enabled)
                                if (enabled) {
                                    WormholeService.start(this@MainActivity)
                                }
                            },
                            startOnBoot = startOnBoot,
                            onStartOnBootChanged = { enabled ->
                                startOnBoot = enabled
                                WormholeServer.setStartOnBoot(enabled)
                            },
                            canAutoOpen = canAutoOpen,
                            onRequestAutoOpen = { openOverlaySettings() },
                            onRestartServer = {
                                WormholeServer.restartServer()
                                networkDetails = NetworkInfoHelper.getNetworkDetails(this@MainActivity)
                            },
                            recentConnections = recentConnections,
                            orientationSetting = orientationSetting,
                            onOrientationSettingChanged = { WormholeServer.setOrientationSetting(it) }
                        )

                        orientationNotice?.let { notice ->
                            OrientationNoticeDialog(
                                notice = notice,
                                onDismiss = { WormholeServer.dismissOrientationNotice() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WormholeServer.isActivityResumed = true
        networkDetails = NetworkInfoHelper.getNetworkDetails(this)
        canAutoOpen = Settings.canDrawOverlays(this)
    }

    private fun openOverlaySettings() {
        val perApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(perApp) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            .onFailure { android.util.Log.w("Wormhole", "No overlay permission settings screen", it) }
    }

    override fun onPause() {
        super.onPause()
        WormholeServer.isActivityResumed = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val fps = windowManager.defaultDisplay.mode?.refreshRate?.toInt()
            ?: windowManager.defaultDisplay.refreshRate.toInt()
        val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        WormholeServer.onConfigurationChanged(metrics.widthPixels, metrics.heightPixels, fps, isPortrait)
    }

    private fun updateOrientationMode(setting: ScreenOrientation) {
        val effectiveSetting = if (!WormholeIdentity.hasOrientationSensor(this) && setting == ScreenOrientation.AUTO) {
            ScreenOrientation.LANDSCAPE
        } else {
            setting
        }
        requestedOrientation = when (effectiveSetting) {
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    // Home is a deliberate exit; not called when the service brings this activity forward.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        WormholeServer.disconnectClient("home")
    }

    override fun onDestroy() {
        WormholeServer.setSurface(null)
        if (!WormholeServer.runInBackground) {
            WormholeService.stop(this)
            WormholeServer.stopServer()
        }
        super.onDestroy()
    }
}

@Composable
private fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(8.dp),
    focusedBorderColor: Color = Color(0xFF7DE2CE),
    focusedBackgroundColor: Color = Color(0xFF1E324A),
    unfocusedBackgroundColor: Color = Color.Transparent,
    unfocusedBorderColor: Color = Color.Transparent,
    borderWidth: Dp = 2.dp,
    onClick: (() -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { isFocused = it.isFocused }
        .then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier.focusable()
            }
        )
        .background(if (isFocused) focusedBackgroundColor else unfocusedBackgroundColor, shape)
        .border(
            borderWidth,
            if (isFocused) focusedBorderColor else unfocusedBorderColor,
            shape
        )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    fontSize: TextUnit = 14.sp,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = fontSize, color = Color(0xFF94A3B8))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFE2E8F0),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun DashboardScreen(
    serviceName: String,
    onServiceNameChanged: (String) -> Unit,
    status: String,
    networkDetails: NetworkInfoHelper.NetworkDetails,
    displayInfo: DisplayInfo,
    audioEnabled: Boolean,
    onAudioEnabledChanged: (Boolean) -> Unit,
    debugOverlayEnabled: Boolean,
    hevcEnabled: Boolean,
    onHevcEnabledChanged: (Boolean) -> Unit,
    onDebugOverlayEnabledChanged: (Boolean) -> Unit,
    runInBackground: Boolean,
    onRunInBackgroundChanged: (Boolean) -> Unit,
    startOnBoot: Boolean,
    onStartOnBootChanged: (Boolean) -> Unit,
    canAutoOpen: Boolean,
    onRequestAutoOpen: () -> Unit,
    onRestartServer: () -> Unit,
    recentConnections: List<ConnectionEntry>,
    orientationSetting: ScreenOrientation,
    onOrientationSettingChanged: (ScreenOrientation) -> Unit
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var showHaDialog by remember { mutableStateOf(false) }
    // Re-read on each dialog close so the row summary reflects saved config.
    var haConfigTick by remember { mutableStateOf(0) }
    val haEnabled = remember(haConfigTick) { WormholeServer.haEnabled }
    val haEntity = remember(haConfigTick) { WormholeServer.haEntity }
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isAutoSupported = remember(context) { WormholeIdentity.hasOrientationSensor(context) }
    val availableOrientations = remember(isAutoSupported) {
        if (isAutoSupported) {
            ScreenOrientation.values().toList()
        } else {
            listOf(ScreenOrientation.LANDSCAPE, ScreenOrientation.PORTRAIT)
        }
    }
    val screenHeightDp = configuration.screenHeightDp
    val isTvOrCompact = screenHeightDp <= 650
    val isCompactScreen = displayInfo.height <= 900 || screenHeightDp <= 800

    val horizontalPadding = if (isTvOrCompact) 20.dp else if (isCompactScreen) 28.dp else 48.dp
    val verticalPadding = if (isTvOrCompact) 8.dp else if (isCompactScreen) 16.dp else 36.dp
    val cardSpacing = if (isTvOrCompact) 5.dp else if (isCompactScreen) 10.dp else 20.dp
    val cardPadding = if (isTvOrCompact) 6.dp else if (isCompactScreen) 14.dp else 20.dp
    val dividerSpacing = if (isTvOrCompact) 1.dp else if (isCompactScreen) 8.dp else 12.dp
    val columnSpacing = if (isTvOrCompact) 14.dp else if (isCompactScreen) 20.dp else 28.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Wormhole Display",
                    fontSize = if (isTvOrCompact) 20.sp else if (isCompactScreen) 26.sp else 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(if (isTvOrCompact) 2.dp else if (isCompactScreen) 3.dp else 6.dp))
                Text(
                    text = "On your Mac or iPhone: Control Center → Screen Mirroring → $serviceName",
                    fontSize = if (isTvOrCompact) 12.sp else if (isCompactScreen) 14.sp else 18.sp,
                    color = Color(0xFFE2E8F0)
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0E2A24),
                border = BorderStroke(1.dp, Color(0xFF1E5246))
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = if (isTvOrCompact) 10.dp else 14.dp,
                        vertical = if (isTvOrCompact) 4.dp else 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isTvOrCompact) 6.dp else 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isTvOrCompact) 8.dp else 10.dp)
                            .background(Color(0xFF7DE2CE), shape = CircleShape)
                    )
                    Text(
                        text = if (status.startsWith("Visible")) "Ready to Connect" else status,
                        fontSize = if (isTvOrCompact) 12.sp else 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF7DE2CE)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isTvOrCompact) 4.dp else if (isCompactScreen) 12.dp else 28.dp))

        val isPortraitLayout = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val controlsCard = @Composable {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Color(0xFF1F3146))
            ) {
                Column(modifier = Modifier.padding(cardPadding)) {
                    Text(
                        text = "Controls & Playback",
                        fontSize = if (isTvOrCompact) 13.sp else if (isCompactScreen) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF1F5F9)
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 2.dp else if (isCompactScreen) 8.dp else 14.dp))

                    // Row 0: Screen Orientation
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Screen Orientation",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = when (orientationSetting) {
                                    ScreenOrientation.LANDSCAPE -> if (!isAutoSupported) "Landscape (wide)" else "Forced landscape (wide)"
                                    ScreenOrientation.PORTRAIT -> if (!isAutoSupported) "Portrait (tall)" else "Forced portrait (tall)"
                                    ScreenOrientation.AUTO -> "Auto-detect from tilt sensor"
                                },
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            availableOrientations.forEach { option ->
                                val isSelected = orientationSetting == option
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) Color(0xFF1E5246) else Color(0xFF0F1A26),
                                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF7DE2CE) else Color(0xFF1F3146)),
                                    modifier = Modifier
                                        .tvFocusable(
                                            shape = RoundedCornerShape(6.dp),
                                            onClick = { onOrientationSettingChanged(option) }
                                        )
                                        .clickable { onOrientationSettingChanged(option) }
                                ) {
                                    Text(
                                        text = option.label,
                                        fontSize = if (isTvOrCompact) 9.sp else 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFF7DE2CE) else Color(0xFF94A3B8),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 1: Speaker Output
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onAudioEnabledChanged(!audioEnabled) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Speaker Output",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = if (audioEnabled) "Play sound through device speakers" else "Device speakers muted",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = audioEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF09121E),
                                checkedTrackColor = Color(0xFF7DE2CE),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF1B2B3E)
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 2: H.265 / HEVC Video (Experimental)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onHevcEnabledChanged(!hevcEnabled) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "H.265 video (Experimental)",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = "Allow compatible senders to use H.265. Reconnect after changing. H.264 remains available.",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = hevcEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF09121E),
                                checkedTrackColor = Color(0xFF7DE2CE),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF1B2B3E)
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 2b: Telemetry Overlay (Debug)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onDebugOverlayEnabledChanged(!debugOverlayEnabled) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Telemetry Overlay (Debug)",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = "Display real-time stream stats (FPS, bitrate, resolution, codec) in top-left corner",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = debugOverlayEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF09121E),
                                checkedTrackColor = Color(0xFF7DE2CE),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF1B2B3E)
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 3: Run in Background
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onRunInBackgroundChanged(!runInBackground) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Run in Background",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = "Keep receiver discoverable when app is closed or minimized",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = runInBackground,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF09121E),
                                checkedTrackColor = Color(0xFF7DE2CE),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF1B2B3E)
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 3b: Auto-open on connect
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = if (!canAutoOpen) onRequestAutoOpen else null
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-open on connect",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (canAutoOpen) Color(0xFFE2E8F0) else Color(0xFFF59E0B)
                            )
                            Text(
                                text = if (canAutoOpen)
                                    "App brings itself to front when a device starts streaming"
                                else
                                    "Allow \"Display over other apps\" so the receiver can show the stream automatically",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        if (!canAutoOpen) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                                color = Color(0xFF261D0C),
                                modifier = Modifier
                                    .clickable { onRequestAutoOpen() }
                                    .padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "Allow",
                                    fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFF59E0B),
                                    modifier = Modifier.padding(
                                        horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                        vertical = if (isTvOrCompact) 3.dp else 6.dp
                                    )
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF0E2A24),
                                border = BorderStroke(1.dp, Color(0xFF1E5246)),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "Enabled",
                                    fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF7DE2CE),
                                    modifier = Modifier.padding(
                                        horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                        vertical = if (isTvOrCompact) 3.dp else 6.dp
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 4: Start on Boot
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { onStartOnBootChanged(!startOnBoot) }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start on Boot",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = if (isTvOrCompact) "Start receiver when device powers on" else "Automatically start receiver when device powers on",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF09121E),
                                checkedTrackColor = Color(0xFF7DE2CE),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF1B2B3E)
                            ),
                            modifier = if (isTvOrCompact) Modifier.scale(0.8f) else Modifier
                        )
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 5: Bonjour Service Name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { showNameDialog = true }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bonjour Service Name",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = "Advertised in Screen Mirroring as: \"$serviceName\"",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF7DE2CE)),
                            color = Color(0xFF0E2A24)
                        ) {
                            Text(
                                text = "Rename",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF7DE2CE),
                                modifier = Modifier.padding(
                                    horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                    vertical = if (isTvOrCompact) 3.dp else 6.dp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 5b: Home Assistant now-playing
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = { showHaDialog = true }
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Home Assistant",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = if (haEnabled)
                                    "Publishing now-playing to $haEntity"
                                else
                                    "Publish now-playing metadata to a Home Assistant entity",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (haEnabled) Color(0xFF7DE2CE) else Color(0xFF1E5246)),
                            color = if (haEnabled) Color(0xFF0E2A24) else Color(0xFF0F1A26)
                        ) {
                            Text(
                                text = if (haEnabled) "On" else "Configure",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (haEnabled) Color(0xFF7DE2CE) else Color(0xFF94A3B8),
                                modifier = Modifier.padding(
                                    horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                    vertical = if (isTvOrCompact) 3.dp else 6.dp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(dividerSpacing))
                    HorizontalDivider(color = Color(0xFF1F3146), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(dividerSpacing))

                    // Row 6: Receiver Service
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                onClick = onRestartServer
                            )
                            .padding(
                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                vertical = if (isTvOrCompact) 1.dp else 6.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Receiver Service",
                                fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = "Restart if receiver does not appear on your device",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF1E5246)),
                            color = Color(0xFF0F1A26)
                        ) {
                            Text(
                                text = "Restart",
                                fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(
                                    horizontal = if (isTvOrCompact) 8.dp else 12.dp,
                                    vertical = if (isTvOrCompact) 3.dp else 6.dp
                                )
                            )
                        }
                    }
                }
            }
        }

        val networkCard = @Composable {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusable(
                        shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                        unfocusedBorderColor = Color(0xFF1F3146),
                        borderWidth = 1.dp
                    ),
                shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(cardPadding)) {
                    Text(
                        text = "Network & Diagnostics",
                        fontSize = if (isTvOrCompact) 12.sp else if (isCompactScreen) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF1F5F9)
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 2.dp else if (isCompactScreen) 8.dp else 14.dp))
                    if (isTvOrCompact) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                InfoRow(label = "Wi-Fi", value = networkDetails.wifiSsid, fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(1.dp))
                                InfoRow(label = "Bonjour", value = "$serviceName (7000)", fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                InfoRow(label = "Device IP", value = networkDetails.ipAddress, fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(1.dp))
                                InfoRow(label = "Display", value = "${displayInfo.width}×${displayInfo.height}@${displayInfo.refreshRate}Hz", fontSize = 11.sp)
                            }
                        }
                    } else {
                        InfoRow(label = "Wi-Fi Network", value = networkDetails.wifiSsid)
                        Spacer(modifier = Modifier.height(if (isCompactScreen) 4.dp else 8.dp))
                        InfoRow(label = "Device IP", value = networkDetails.ipAddress)
                        Spacer(modifier = Modifier.height(if (isCompactScreen) 4.dp else 8.dp))
                        InfoRow(label = "Bonjour Service", value = "$serviceName (Port 7000)")
                        Spacer(modifier = Modifier.height(if (isCompactScreen) 4.dp else 8.dp))
                        InfoRow(label = "Display", value = "${displayInfo.width} × ${displayInfo.height} @ ${displayInfo.refreshRate}Hz (Auto-fit)")
                    }
                }
            }
        }

        val resolutionsCard = @Composable {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusable(
                        shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                        unfocusedBorderColor = Color(0xFF1F3146),
                        borderWidth = 1.dp
                    ),
                shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(cardPadding)) {
                    Text(
                        text = "Preferred Resolutions (${displayInfo.aspectRatioLabel})",
                        fontSize = if (isTvOrCompact) 12.sp else if (isCompactScreen) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF1F5F9)
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 2.dp else if (isCompactScreen) 6.dp else 12.dp))
                    Text(
                        text = displayInfo.recommendedResolutions,
                        fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF7DE2CE)
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 1.dp else if (isCompactScreen) 4.dp else 8.dp))
                    Text(
                        text = if (isTvOrCompact)
                            "In macOS Settings → Displays, select $serviceName and toggle \"Show all resolutions\"."
                        else
                            "In macOS System Settings → Displays, select $serviceName and toggle \"Show all resolutions\" to pick from these ${displayInfo.aspectRatioLabel} modes (both Extended & Mirroring supported).",
                        fontSize = if (isTvOrCompact) 10.sp else 12.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = if (isTvOrCompact) 12.sp else 16.sp
                    )
                }
            }
        }

        val connectionsCard = @Composable { isFillMaxHeight: Boolean, modifier: Modifier ->
            Card(
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(if (isTvOrCompact) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Color(0xFF1F3146))
            ) {
                val contentModifier = if (isFillMaxHeight) {
                    Modifier
                        .padding(if (isTvOrCompact) 10.dp else if (isCompactScreen) 16.dp else 20.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                        .padding(if (isTvOrCompact) 10.dp else if (isCompactScreen) 16.dp else 20.dp)
                        .fillMaxWidth()
                }
                Column(modifier = contentModifier) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Connections",
                            fontSize = if (isTvOrCompact) 14.sp else 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF1F5F9)
                        )
                        Text(
                            text = "Last 5 sessions",
                            fontSize = if (isTvOrCompact) 11.sp else 13.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 6.dp else 16.dp))

                    if (recentConnections.isEmpty()) {
                        val boxModifier = if (isFillMaxHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        Box(
                            modifier = boxModifier,
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No recent connections",
                                    fontSize = if (isTvOrCompact) 13.sp else 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(modifier = Modifier.height(if (isTvOrCompact) 3.dp else 6.dp))
                                Text(
                                    text = "Mirrored sessions from iPhone, iPad, or Mac will appear here.",
                                    fontSize = if (isTvOrCompact) 11.sp else 13.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(if (isTvOrCompact) 4.dp else 10.dp)
                        ) {
                            recentConnections.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable(
                                            shape = RoundedCornerShape(if (isTvOrCompact) 8.dp else 10.dp),
                                            unfocusedBackgroundColor = Color(0xFF1B2B3E)
                                        )
                                        .padding(
                                            horizontal = if (isTvOrCompact) 10.dp else 16.dp,
                                            vertical = if (isTvOrCompact) 4.dp else 12.dp
                                        ),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.clientName,
                                            fontSize = if (isTvOrCompact) 12.sp else 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFF1F5F9)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val details = buildList {
                                            add(ConnectionHistory.formatTimestamp(entry.timestamp))
                                            if (entry.resolution.isNotBlank()) add(entry.resolution)
                                            if (entry.codec.isNotBlank()) add(entry.codec)
                                        }.joinToString(" • ")
                                        Text(
                                            text = details,
                                            fontSize = if (isTvOrCompact) 10.sp else 13.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF142131)
                                    ) {
                                        Text(
                                            text = ConnectionHistory.formatDuration(entry.durationSeconds),
                                            fontSize = if (isTvOrCompact) 10.sp else 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF7DE2CE),
                                            modifier = Modifier.padding(
                                                horizontal = if (isTvOrCompact) 8.dp else 10.dp,
                                                vertical = if (isTvOrCompact) 3.dp else 5.dp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isPortraitLayout) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(cardSpacing)
            ) {
                controlsCard()
                networkCard()
                resolutionsCard()
                connectionsCard(false, Modifier)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(columnSpacing)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(cardSpacing)
                ) {
                    controlsCard()
                    networkCard()
                    resolutionsCard()
                }
                connectionsCard(true, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }

    if (showNameDialog) {
        var tempName by remember { mutableStateOf(serviceName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = Color(0xFF142131),
            title = {
                Text(
                    text = "Change Bonjour Name",
                    fontSize = if (isTvOrCompact) 16.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF1F5F9)
                )
            },
            text = {
                Column {
                    Text(
                        text = "Customize the name shown in Screen Mirroring on macOS and iOS. Useful if you have multiple displays on the same Wi-Fi network.",
                        fontSize = if (isTvOrCompact) 12.sp else 14.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = if (isTvOrCompact) 16.sp else 20.sp
                    )
                    Spacer(modifier = Modifier.height(if (isTvOrCompact) 10.dp else 16.dp))
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { if (it.length <= 60) tempName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFF1F5F9),
                            unfocusedTextColor = Color(0xFFE2E8F0),
                            focusedBorderColor = Color(0xFF7DE2CE),
                            unfocusedBorderColor = Color(0xFF1F3146),
                            focusedLabelColor = Color(0xFF7DE2CE),
                            unfocusedLabelColor = Color(0xFF94A3B8)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val saveInteraction = remember { MutableInteractionSource() }
                val isSaveFocused by saveInteraction.collectIsFocusedAsState()
                Button(
                    onClick = {
                        val trimmed = tempName.trim()
                        if (trimmed.isNotBlank() && trimmed != serviceName) {
                            onServiceNameChanged(trimmed)
                        }
                        showNameDialog = false
                    },
                    interactionSource = saveInteraction,
                    enabled = tempName.trim().isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSaveFocused) Color(0xFF9EFFEB) else Color(0xFF7DE2CE),
                        contentColor = Color(0xFF09121E)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(
                        if (isSaveFocused) 2.dp else 0.dp,
                        Color.White,
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Text("Save & Apply")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (serviceName != WormholeIdentity.DEFAULT_SERVICE_NAME) {
                        val resetInteraction = remember { MutableInteractionSource() }
                        val isResetFocused by resetInteraction.collectIsFocusedAsState()
                        TextButton(
                            onClick = {
                                onServiceNameChanged(WormholeIdentity.DEFAULT_SERVICE_NAME)
                                showNameDialog = false
                            },
                            interactionSource = resetInteraction,
                            modifier = Modifier
                                .background(
                                    if (isResetFocused) Color(0xFF1E3A5F) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    if (isResetFocused) 1.5.dp else 0.dp,
                                    Color(0xFF7DE2CE),
                                    RoundedCornerShape(8.dp)
                                )
                        ) {
                            Text("Reset", color = if (isResetFocused) Color.White else Color(0xFF94A3B8))
                        }
                    }
                    val cancelInteraction = remember { MutableInteractionSource() }
                    val isCancelFocused by cancelInteraction.collectIsFocusedAsState()
                    TextButton(
                        onClick = { showNameDialog = false },
                        interactionSource = cancelInteraction,
                        modifier = Modifier
                            .background(
                                if (isCancelFocused) Color(0xFF1E3A5F) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                if (isCancelFocused) 1.5.dp else 0.dp,
                                Color(0xFF7DE2CE),
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        Text("Cancel", color = if (isCancelFocused) Color.White else Color(0xFF94A3B8))
                    }
                }
            }
        )
    }

    if (showHaDialog) {
        HaConfigDialog(onDismiss = {
            showHaDialog = false
            haConfigTick++
        })
    }
}

@Composable
private fun HaConfigDialog(onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(WormholeServer.haEnabled) }
    var url by remember { mutableStateOf(WormholeServer.haUrl) }
    var token by remember { mutableStateOf(WormholeServer.haToken) }
    var entity by remember { mutableStateOf(WormholeServer.haEntity) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF7DE2CE),
        unfocusedBorderColor = Color(0xFF1E5246),
        focusedTextColor = Color(0xFFF1F5F9),
        unfocusedTextColor = Color(0xFFF1F5F9),
        cursorColor = Color(0xFF7DE2CE),
        focusedLabelColor = Color(0xFF7DE2CE),
        unfocusedLabelColor = Color(0xFF94A3B8)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF142131),
        title = { Text("Home Assistant now-playing", color = Color(0xFFF1F5F9)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Publishes AirPlay title, artist, album and cover art to a Home Assistant " +
                        "entity over the REST API. Create a Long-Lived Access Token in your HA " +
                        "profile. Everything stays on your local network.",
                    fontSize = 12.sp, color = Color(0xFF94A3B8)
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable", color = Color(0xFFE2E8F0))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF09121E),
                            checkedTrackColor = Color(0xFF7DE2CE),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF1B2B3E)
                        )
                    )
                }
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Base URL (e.g. http://homeassistant.local:8123)") },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Long-Lived Access Token") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = entity, onValueChange = { entity = it },
                    label = { Text("Entity ID") },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    WormholeServer.setHaConfig(enabled, url.trim(), token.trim(), entity.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7DE2CE), contentColor = Color(0xFF09121E)
                ),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Save & Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
private fun TelemetryOverlay(
    telemetry: StreamTelemetry,
    modifier: Modifier = Modifier
) {
    var snapshot by remember { mutableStateOf(telemetry.getSnapshot()) }

    LaunchedEffect(telemetry) {
        while (true) {
            kotlinx.coroutines.delay(500)
            snapshot = telemetry.getSnapshot()
        }
    }

    Surface(
        modifier = modifier.wrapContentSize(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xDD09121E),
        border = BorderStroke(1.dp, Color(0x667DE2CE))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Video row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF7DE2CE), shape = CircleShape)
                )
                Text(
                    text = "VIDEO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF7DE2CE)
                )
                val codecShort = if (snapshot.codecName.isNotBlank()) {
                    " (" + snapshot.codecName.removePrefix("OMX.qcom.video.decoder.").removePrefix("OMX.google.") + ")"
                } else ""
                Text(
                    text = String.format(
                        java.util.Locale.US,
                        "%.1f fps • %s • %s%s",
                        snapshot.fps,
                        snapshot.formattedThroughput,
                        snapshot.formattedResolution,
                        codecShort
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFF1F5F9)
                )
            }

            // Audio row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val audioActive = snapshot.audioActive && snapshot.audioEnabled
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (audioActive) Color(0xFF7DE2CE) else Color(0xFF64748B), shape = CircleShape)
                )
                Text(
                    text = "AUDIO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (audioActive) Color(0xFF7DE2CE) else Color(0xFF94A3B8)
                )
                val audioText = if (snapshot.audioEnabled) {
                    "${snapshot.audioCodec} • Vol ${snapshot.formattedAudioVolume} • ${snapshot.formattedAudioStatus}"
                } else {
                    "Disabled / Muted"
                }
                Text(
                    text = audioText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE2E8F0)
                )
            }

            // Frames row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(6.dp))
                Text(
                    text = "SYNC ",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF64748B)
                )
                Text(
                    text = "${snapshot.totalFramesRendered} video frames • ${snapshot.audioFramesPlayed} audio frames",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
private fun OrientationNoticeDialog(
    notice: WormholeServer.OrientationNotice,
    onDismiss: () -> Unit
) {
    var remainingSeconds by remember(notice.timestamp) { mutableStateOf(30) }

    LaunchedEffect(notice.timestamp) {
        remainingSeconds = 30
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        onDismiss()
    }

    val mode = if (notice.isPortrait) "Portrait" else "Landscape"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF142131),
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Display Rotated",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF1F5F9)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Display orientation changed to $mode (${notice.width} × ${notice.height}).",
                    fontSize = 14.sp,
                    color = Color(0xFFF1F5F9),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "AirPlay streaming was disconnected because Apple devices do not adapt to geometry changes mid-stream. Reconnect from Screen Mirroring on your Mac or iPhone to resume.",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            val okInteraction = remember { MutableInteractionSource() }
            val isOkFocused by okInteraction.collectIsFocusedAsState()
            Button(
                onClick = onDismiss,
                interactionSource = okInteraction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOkFocused) Color(0xFF9EFFEB) else Color(0xFF7DE2CE),
                    contentColor = Color(0xFF09121E)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.border(
                    if (isOkFocused) 2.dp else 0.dp,
                    Color.White,
                    RoundedCornerShape(10.dp)
                )
            ) {
                Text("OK (${remainingSeconds}s)", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

