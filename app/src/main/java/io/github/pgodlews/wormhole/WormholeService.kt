package io.github.pgodlews.wormhole

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Persistent Android Foreground Service that keeps the native UxPlay AirPlay
 * receiver alive and discoverable via mDNS even when MainActivity is closed or minimized.
 */
class WormholeService : Service() {
    companion object {
        private const val TAG = "WormholeService"
        const val CHANNEL_ID = "receiver_service"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "io.github.pgodlews.wormhole.action.START"
        const val ACTION_STOP = "io.github.pgodlews.wormhole.action.STOP"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WormholeService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WormholeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "Creating WormholeService")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing receiver…"))

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wormhole:receiver_wakelock")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        WormholeServer.init(applicationContext)
        // Single launch path: WormholeServer fires this when a mirroring or audio-only session starts while the activity isn't resumed.
        WormholeServer.onIncomingStreamBackgroundCallback = {
            launchMainActivity()
        }
        WormholeServer.startServer()

        // Observe server state changes to update notification
        WormholeServer.isMirroring.onEach { mirroring ->
            val client = WormholeServer.clientName.value
            val text = if (mirroring) {
                "Mirroring ${client ?: "client"}"
            } else {
                WormholeServer.idleStatus
            }
            updateNotification(text, isMirroring = mirroring)
        }.launchIn(serviceScope)

        WormholeServer.statusText.onEach { status ->
            if (!WormholeServer.isMirroring.value) {
                updateNotification(status, isMirroring = false)
            }
        }.launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Received ACTION_STOP -> stopping service")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        WormholeServer.init(applicationContext)
        WormholeServer.startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying WormholeService")
        isRunning = false
        serviceScope.cancel()
        WormholeServer.onIncomingStreamBackgroundCallback = null

        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun launchMainActivity() {
        Log.i(TAG, "Incoming stream active in background: launching MainActivity")
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "\"Display over other apps\" not granted; Android 10+ will block this background launch")
        }
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity from background", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wormhole Display Receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Wormhole Display active and discoverable in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, isMirroring: Boolean = false): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("Wormhole Display Receiver")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (isMirroring) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        return builder.build()
    }

    private fun updateNotification(contentText: String, isMirroring: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(contentText, isMirroring))
    }
}
