package com.lanfileserver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UpdateDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val canceled = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            canceled.set(true)
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        val sha256 = intent?.getStringExtra(EXTRA_SHA256).orEmpty()
        val versionCode = intent?.getLongExtra(EXTRA_VERSION_CODE, -1L) ?: -1L
        val versionName = intent?.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        if (url.isBlank() || sha256.length != 64 || versionCode <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        canceled.set(false)
        startInForeground(buildProgressNotification("准备下载更新", 0, true))
        executor.execute {
            download(url, sha256.lowercase(Locale.ROOT), versionCode, versionName)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        canceled.set(true)
        executor.shutdownNow()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun download(
        url: String,
        expectedSha256: String,
        versionCode: Long,
        versionName: String,
    ) {
        val updateDirectory = File(cacheDir, "updates").apply { mkdirs() }
        val partial = File(updateDirectory, "hotspot-file-server.pending.apk")
        val ready = UpdateInstaller.readyApk(this)
        partial.delete()
        ready.delete()
        var connection: HttpURLConnection? = null
        try {
            val uri = URI(url)
            if (uri.scheme != "https") throw IOException("更新地址必须使用 HTTPS")
            connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 25_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("User-Agent", "HotspotFileServer/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("更新服务器返回 HTTP $status")
            val total = connection.contentLengthLong.takeIf { it > 0L }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L

            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (canceled.get()) throw UpdateCanceledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        val percent = total?.let {
                            (downloaded * 100L / it).toInt().coerceIn(0, 100)
                        }
                        publishProgress(
                            "正在下载 $versionName · ${formatBytes(downloaded)}",
                            percent ?: 0,
                            percent == null,
                        )
                    }
                }
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != expectedSha256) {
                throw IOException("更新文件哈希校验失败")
            }
            UpdateInstaller.verifyPackage(this, partial, versionCode)
            if (!partial.renameTo(ready)) {
                partial.copyTo(ready, overwrite = true)
                partial.delete()
            }
            readyFilePath = ready.absolutePath
            lastStatus = "更新已下载，点击安装"
            lastError = false
            broadcastState()
            notificationManager().notify(
                NOTIFICATION_ID,
                buildReadyNotification(ready, versionName),
            )
        } catch (_: UpdateCanceledException) {
            partial.delete()
            lastStatus = "更新下载已取消"
            lastError = false
            broadcastState()
        } catch (error: Throwable) {
            partial.delete()
            lastStatus = error.message ?: "更新下载失败"
            lastError = true
            broadcastState()
            notificationManager().notify(
                NOTIFICATION_ID,
                buildResultNotification("更新下载失败", lastStatus),
            )
        } finally {
            connection?.disconnect()
            running = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun publishProgress(message: String, progress: Int, indeterminate: Boolean) {
        lastStatus = message
        lastError = false
        progressPercent = if (indeterminate) null else progress
        broadcastState()
        notificationManager().notify(
            NOTIFICATION_ID,
            buildProgressNotification(message, progress, indeterminate),
        )
    }

    private fun buildProgressNotification(
        message: String,
        progress: Int,
        indeterminate: Boolean,
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle("正在下载应用更新")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .addAction(0, "取消", serviceAction(ACTION_CANCEL, 31))
            .build()

    private fun buildReadyNotification(file: File, versionName: String): Notification {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val install = PendingIntent.getActivity(
            this,
            32,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle("热点文件站 $versionName 已下载")
            .setContentText("点击继续安装")
            .setContentIntent(install)
            .setAutoCancel(true)
            .addAction(0, "安装", install)
            .build()
    }

    private fun buildResultNotification(title: String, message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, UpdateDownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun startInForeground(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun createNotificationChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "应用更新",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "显示应用更新下载和安装状态"
                setShowBadge(false)
            },
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun broadcastState() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, lastStatus)
                .putExtra(EXTRA_ERROR, lastError)
                .putExtra(EXTRA_PROGRESS, progressPercent ?: -1),
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index += 1
        } while (value >= 1024.0 && index < units.lastIndex)
        return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
    }

    companion object {
        const val ACTION_START = "com.lanfileserver.app.action.UPDATE_START"
        const val ACTION_CANCEL = "com.lanfileserver.app.action.UPDATE_CANCEL"
        const val ACTION_STATE_CHANGED = "com.lanfileserver.app.action.UPDATE_STATE_CHANGED"
        const val EXTRA_URL = "update_url"
        const val EXTRA_SHA256 = "update_sha256"
        const val EXTRA_VERSION_CODE = "update_version_code"
        const val EXTRA_VERSION_NAME = "update_version_name"
        const val EXTRA_STATUS = "update_status"
        const val EXTRA_ERROR = "update_error"
        const val EXTRA_PROGRESS = "update_progress"

        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 3001

        @Volatile
        var running = false
            private set

        @Volatile
        var lastStatus = "尚未下载更新"
            private set

        @Volatile
        var lastError = false
            private set

        @Volatile
        var progressPercent: Int? = null
            private set

        @Volatile
        var readyFilePath: String? = null
            private set
    }
}

private class UpdateCanceledException : IOException("更新下载已取消")
