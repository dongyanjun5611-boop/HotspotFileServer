package com.lanfileserver.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.Executors

class FileServerService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var server: LanFileServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        startInForeground(buildNotification("正在启动文件站…"))
        if (server == null) {
            stopping = false
            worker.execute(::startServer)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        server?.stop()
        server = null
        releaseLocks()
        running = false
        broadcastState(lastError)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        try {
            val treeUri = AppPreferences.treeUri(this)
                ?: throw IllegalStateException("请先选择共享文件夹")
            val port = AppPreferences.port(this)
            val pin = AppPreferences.pin(this)
            val candidate = LanFileServer(this, treeUri.toUri(), port, pin)
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

            if (stopping) {
                candidate.stop()
                return
            }

            server = candidate
            acquireLocks()
            running = true
            lastError = null
            val url = NetworkAddresses.urls(port).firstOrNull()
                ?: "端口 $port 已启动，等待局域网地址"
            notificationManager().notify(NOTIFICATION_ID, buildNotification(url))
            broadcastState(null)
        } catch (error: Throwable) {
            running = false
            lastError = friendlyError(error)
            broadcastState(lastError)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun shutdown() {
        stopping = true
        worker.execute {
            server?.stop()
            server = null
            releaseLocks()
            running = false
            lastError = null
            broadcastState(null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startInForeground(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            serviceType,
        )
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:file-server",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "$packageName:wifi",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null

        wifiLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wifiLock = null
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FileServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle("热点文件站正在运行")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "文件站运行状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示局域网文件站的运行状态"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun broadcastState(error: String?) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_ERROR, error),
        )
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("Address already in use", ignoreCase = true) ->
                "端口已被占用，请换一个端口"
            message.contains("EACCES", ignoreCase = true) ->
                "没有权限监听这个端口"
            message.isNotBlank() -> message
            else -> "文件站启动失败"
        }
    }

    companion object {
        const val ACTION_START = "com.lanfileserver.app.action.START"
        const val ACTION_STOP = "com.lanfileserver.app.action.STOP"
        const val ACTION_STATE_CHANGED = "com.lanfileserver.app.action.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "file_server"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set
    }
}
