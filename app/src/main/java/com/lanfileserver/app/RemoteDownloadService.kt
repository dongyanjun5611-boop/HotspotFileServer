package com.lanfileserver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class RemoteDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private val cancelCurrent = AtomicBoolean(false)
    private val wakeSignal = Object()
    private var loopStarted = false
    @Volatile
    private var currentJobId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val api = RemoteDownloadApi()
    private var p2pTransferManager: P2pTransferManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        p2pTransferManager = runCatching {
            P2pTransferManager(
                context = this,
                onStatus = { message, error -> publishStatus(message, error) },
                onMeteredApproval = { sessionId ->
                    publishStatus(
                        "P2P 直传等待确认是否使用计费网络",
                        notification = buildP2pMeteredNotification(sessionId),
                    )
                },
            )
        }.getOrElse {
            publishStatus("P2P 模块不可用，其他远程功能仍可使用", error = true)
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppPreferences.saveRemoteEnabled(this, false)
            stopRequested.set(true)
            cancelCurrent.set(true)
            signalLoop()
            stopSelf()
            return START_NOT_STICKY
        }

        AppPreferences.saveRemoteEnabled(this, true)
        startInForeground(buildIdleNotification("正在连接远程任务服务…"))
        p2pTransferManager?.start()
        if (!loopStarted) {
            loopStarted = true
            executor.execute(::runLoop)
        }

        when (intent?.action) {
            ACTION_CANCEL_CURRENT -> {
                currentJobId?.let { AppPreferences.requestCancelJob(this, it) }
                cancelCurrent.set(true)
                signalLoop()
            }

            ACTION_ALLOW_METERED -> {
                intent.getStringExtra(EXTRA_JOB_ID)?.let { jobId ->
                    AppPreferences.approveMeteredJob(this, jobId)
                }
                signalLoop()
            }

            ACTION_ALLOW_P2P_METERED -> {
                intent.getStringExtra(EXTRA_P2P_SESSION_ID)?.let { sessionId ->
                    AppPreferences.approveP2pSession(this, sessionId)
                }
                p2pTransferManager?.wake()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        cancelCurrent.set(true)
        signalLoop()
        p2pTransferManager?.stop()
        releaseWakeLock()
        running = false
        currentJobId = null
        broadcastState(lastStatus)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runLoop() {
        running = true
        publishStatus("远程服务已启用，等待链接任务或 P2P 直传")
        while (!stopRequested.get() && AppPreferences.remoteEnabled(this)) {
            try {
                val credentials = AppPreferences.remoteDevice(this)
                if (credentials == null) {
                    publishStatus("请先在远程页面配对这台设备", error = true)
                    waitForNextPoll(60_000L)
                    continue
                }

                val treeUri = AppPreferences.treeUri(this)
                if (treeUri.isNullOrBlank()) {
                    publishStatus("请先在应用中选择共享文件夹", error = true)
                    waitForNextPoll(15_000L)
                    continue
                }

                val job = api.poll(this, credentials).firstOrNull()
                if (job == null) {
                    currentJobId = null
                    cancelCurrent.set(false)
                    publishStatus("远程服务已启用，等待链接任务或 P2P 直传")
                    waitForNextPoll(POLL_INTERVAL_MS)
                    continue
                }

                val locallyCanceled = AppPreferences.pendingCanceledJob(this) == job.id
                currentJobId = job.id
                cancelCurrent.set(job.status == STATUS_CANCEL_REQUESTED || locallyCanceled)
                if (cancelCurrent.get()) {
                    reportCanceled(job, credentials)
                    continue
                }

                val locallyCompleted = AppPreferences.locallyCompleted(this, job.id)
                if (locallyCompleted != null) {
                    val (fileName, size) = locallyCompleted
                    api.report(
                        credentials,
                        job.id,
                        RemoteProgress(
                            status = STATUS_COMPLETED,
                            downloadedBytes = size,
                            totalBytes = size,
                            fileName = fileName,
                            statusMessage = "文件已保存",
                        ),
                    )
                    AppPreferences.clearLocallyCompleted(this, job.id)
                    AppPreferences.clearApprovedMeteredJob(this, job.id)
                    currentJobId = null
                    continue
                }

                if (!hasInternetConnection()) {
                    reportWaiting(job, credentials, "等待可用网络")
                    continue
                }

                if (isMeteredNetwork()) {
                    when (AppPreferences.remoteNetworkPolicy(this)) {
                        RemoteNetworkPolicy.UNMETERED_ONLY -> {
                            reportWaiting(
                                job,
                                credentials,
                                "当前是计费网络，等待非计费网络",
                            )
                            continue
                        }

                        RemoteNetworkPolicy.ASK -> {
                            if (AppPreferences.approvedMeteredJob(this) != job.id) {
                                reportMeteredApproval(job, credentials)
                                continue
                            }
                        }

                        RemoteNetworkPolicy.ALWAYS -> Unit
                    }
                }

                download(job, treeUri, credentials)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (error: Throwable) {
                publishStatus(friendlyMessage(error), error = true)
                if (error is RemoteApiException && error.statusCode == 401) {
                    AppPreferences.saveRemoteEnabled(this, false)
                    break
                }
                waitForNextPoll(if (error is RemoteApiException && error.statusCode == 401) 60_000L else 15_000L)
            }
        }

        running = false
        currentJobId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reportWaiting(
        job: RemoteDownloadJob,
        credentials: RemoteDeviceCredentials,
        message: String,
    ) {
        api.report(
            credentials,
            job.id,
            RemoteProgress(
                status = STATUS_WAITING_NETWORK,
                statusMessage = message,
            ),
        )
        publishStatus(message)
        waitForNextPoll(POLL_INTERVAL_MS)
    }

    private fun reportMeteredApproval(
        job: RemoteDownloadJob,
        credentials: RemoteDeviceCredentials,
    ) {
        val canceled = api.report(
            credentials,
            job.id,
            RemoteProgress(
                status = STATUS_AWAITING_METERED,
                statusMessage = "等待手机确认使用计费网络",
            ),
        )
        if (canceled) {
            reportCanceled(job, credentials)
            return
        }

        publishStatus(
            "任务等待确认是否使用计费网络",
            notification = buildMeteredNotification(job.id),
        )
        waitForNextPoll(POLL_INTERVAL_MS)
    }

    private fun download(
        job: RemoteDownloadJob,
        treeUri: String,
        credentials: RemoteDeviceCredentials,
    ) {
        cancelCurrent.set(false)
        acquireWakeLock()
        var connection: HttpURLConnection? = null
        try {
            publishStatus("正在连接下载源…")
            val initialCanceled = api.report(
                credentials,
                job.id,
                RemoteProgress(
                    status = STATUS_DOWNLOADING,
                    statusMessage = "正在连接下载源",
                ),
            )
            if (initialCanceled) throw DownloadCanceledException()

            connection = URI(job.url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 25_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("User-Agent", "HotspotFileServer/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("下载源返回 HTTP $status")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it >= 0L }
            val fileName = resolveFileName(job, connection)
            val mimeType = connection.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: URLConnection.guessContentTypeFromName(fileName)
                ?: "application/octet-stream"
            val storage = StorageTree(this, treeUri.toUri())

            val result = connection.inputStream.use { input ->
                storage.writeFile("", fileName, mimeType) { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReportedBytes = 0L
                    var lastReportedAt = System.nanoTime()
                    while (true) {
                        if (cancelCurrent.get()) throw DownloadCanceledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count

                        val now = System.nanoTime()
                        val elapsedMs = (now - lastReportedAt) / 1_000_000L
                        if (elapsedMs >= PROGRESS_INTERVAL_MS) {
                            val speed = ((downloaded - lastReportedBytes) * 1_000L / elapsedMs)
                                .coerceAtLeast(0L)
                            val canceled = api.report(
                                credentials,
                                job.id,
                                RemoteProgress(
                                    status = STATUS_DOWNLOADING,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes,
                                    speedBytesPerSecond = speed,
                                    fileName = fileName,
                                    statusMessage = "正在下载",
                                ),
                            )
                            notifyProgress(fileName, downloaded, totalBytes, speed)
                            if (canceled) {
                                cancelCurrent.set(true)
                                throw DownloadCanceledException()
                            }
                            lastReportedBytes = downloaded
                            lastReportedAt = now
                        }
                    }
                    downloaded
                }
            }

            AppPreferences.markLocallyCompleted(this, job.id, result.name, result.size)
            api.report(
                credentials,
                job.id,
                RemoteProgress(
                    status = STATUS_COMPLETED,
                    downloadedBytes = result.size,
                    totalBytes = result.size,
                    fileName = result.name,
                    statusMessage = "文件已保存",
                ),
            )
            AppPreferences.clearLocallyCompleted(this, job.id)
            AppPreferences.clearApprovedMeteredJob(this, job.id)
            publishStatus("下载完成：${result.name}")
        } catch (_: DownloadCanceledException) {
            reportCanceled(job, credentials)
        } catch (error: Throwable) {
            val message = friendlyMessage(error)
            runCatching {
                api.report(
                    credentials,
                    job.id,
                    RemoteProgress(
                        status = STATUS_FAILED,
                        error = message,
                        statusMessage = "下载失败",
                    ),
                )
            }
            AppPreferences.clearApprovedMeteredJob(this, job.id)
            publishStatus("下载失败：$message", error = true)
        } finally {
            connection?.disconnect()
            releaseWakeLock()
            currentJobId = null
            cancelCurrent.set(false)
        }
    }

    private fun reportCanceled(
        job: RemoteDownloadJob,
        credentials: RemoteDeviceCredentials,
    ) {
        val reported = runCatching {
            api.report(
                credentials,
                job.id,
                RemoteProgress(
                    status = STATUS_CANCELED,
                    statusMessage = "已取消",
                ),
            )
        }.isSuccess
        if (reported) {
            AppPreferences.clearApprovedMeteredJob(this, job.id)
            AppPreferences.clearPendingCanceledJob(this, job.id)
            publishStatus("任务已取消")
            currentJobId = null
            cancelCurrent.set(false)
        } else {
            AppPreferences.requestCancelJob(this, job.id)
            publishStatus("取消请求将在网络恢复后同步")
            waitForNextPoll(POLL_INTERVAL_MS)
        }
    }

    private fun resolveFileName(
        job: RemoteDownloadJob,
        connection: HttpURLConnection,
    ): String {
        if (job.requestedFileName.isNotBlank()) {
            return SafePath.sanitizeName(job.requestedFileName)
        }

        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        val encodedMatch = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.getOrNull(1)
        if (!encodedMatch.isNullOrBlank()) {
            val decoded = runCatching {
                URLDecoder.decode(encodedMatch, StandardCharsets.UTF_8.name())
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return SafePath.sanitizeName(decoded)
        }

        val plainMatch = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!plainMatch.isNullOrBlank()) return SafePath.sanitizeName(plainMatch)

        val pathName = runCatching {
            URLDecoder.decode(
                URI(job.url).path.substringAfterLast('/'),
                StandardCharsets.UTF_8.name(),
            )
        }.getOrNull()
        return if (pathName.isNullOrBlank()) {
            "download-${job.id.take(8)}"
        } else {
            SafePath.sanitizeName(pathName)
        }
    }

    private fun hasInternetConnection(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isMeteredNetwork(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.isActiveNetworkMetered
    }

    private fun notifyProgress(
        fileName: String,
        downloaded: Long,
        total: Long?,
        speed: Long,
    ) {
        val progress = if (total != null && total > 0L) {
            (downloaded * 100.0 / total).roundToInt().coerceIn(0, 100)
        } else {
            null
        }
        val detail = buildString {
            append(formatBytes(downloaded))
            if (total != null) append(" / ${formatBytes(total)}")
            if (speed > 0L) append(" · ${formatBytes(speed)}/s")
        }
        val notification = baseNotification("正在下载", "$fileName\n$detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$fileName\n$detail"))
            .setProgress(100, progress ?: 0, progress == null)
            .addAction(0, "取消任务", serviceAction(ACTION_CANCEL_CURRENT, 22))
            .build()
        notificationManager().notify(NOTIFICATION_ID, notification)
        publishStatus("正在下载：$fileName", notification = notification)
    }

    private fun buildIdleNotification(message: String): Notification =
        baseNotification("远程文件服务", message)
            .addAction(0, "停止", serviceAction(ACTION_STOP, 20))
            .build()

    private fun buildMeteredNotification(jobId: String): Notification {
        val approveIntent = Intent(this, RemoteDownloadService::class.java)
            .setAction(ACTION_ALLOW_METERED)
            .putExtra(EXTRA_JOB_ID, jobId)
        val approve = PendingIntent.getService(
            this,
            21,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return baseNotification("是否使用计费网络？", "有远程下载任务正在等待确认")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "立即下载", approve)
            .addAction(0, "取消任务", serviceAction(ACTION_CANCEL_CURRENT, 22))
            .build()
    }

    private fun buildP2pMeteredNotification(sessionId: String): Notification {
        val approveIntent = Intent(this, RemoteDownloadService::class.java)
            .setAction(ACTION_ALLOW_P2P_METERED)
            .putExtra(EXTRA_P2P_SESSION_ID, sessionId)
        val approve = PendingIntent.getService(
            this,
            23,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return baseNotification("是否使用计费网络？", "有 P2P 文件直传正在等待确认")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "允许直传", approve)
            .addAction(0, "停止服务", serviceAction(ACTION_STOP, 20))
            .build()
    }

    private fun baseNotification(title: String, message: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RemoteDownloadService::class.java).setAction(action),
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "远程文件服务",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "显示远程任务、P2P 直传、计费网络确认和传输进度"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun publishStatus(
        message: String,
        error: Boolean = false,
        notification: Notification? = null,
    ) {
        lastStatus = message
        lastError = error
        notificationManager().notify(
            NOTIFICATION_ID,
            notification ?: buildIdleNotification(message),
        )
        broadcastState(message)
    }

    private fun broadcastState(message: String?) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_STATUS, message)
                .putExtra(EXTRA_ERROR, lastError),
        )
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:remote-download",
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1_000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun waitForNextPoll(milliseconds: Long) {
        if (stopRequested.get()) return
        synchronized(wakeSignal) {
            if (!stopRequested.get()) wakeSignal.wait(milliseconds)
        }
    }

    private fun signalLoop() {
        synchronized(wakeSignal) { wakeSignal.notifyAll() }
    }

    private fun friendlyMessage(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is RemoteApiException && error.statusCode == 401 -> "远程设备认证失败"
            message.contains("ENOSPC", ignoreCase = true) -> "存储空间不足"
            message.contains("Unable to resolve host", ignoreCase = true) -> "无法解析下载地址"
            message.isNotBlank() -> message.take(240)
            else -> "远程下载失败"
        }
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
        const val ACTION_START = "com.lanfileserver.app.action.REMOTE_START"
        const val ACTION_STOP = "com.lanfileserver.app.action.REMOTE_STOP"
        const val ACTION_CANCEL_CURRENT = "com.lanfileserver.app.action.REMOTE_CANCEL_CURRENT"
        const val ACTION_ALLOW_METERED = "com.lanfileserver.app.action.REMOTE_ALLOW_METERED"
        const val ACTION_ALLOW_P2P_METERED =
            "com.lanfileserver.app.action.REMOTE_ALLOW_P2P_METERED"
        const val ACTION_STATE_CHANGED = "com.lanfileserver.app.action.REMOTE_STATE_CHANGED"
        const val EXTRA_RUNNING = "remote_running"
        const val EXTRA_STATUS = "remote_status"
        const val EXTRA_ERROR = "remote_error"
        const val EXTRA_JOB_ID = "remote_job_id"
        const val EXTRA_P2P_SESSION_ID = "p2p_session_id"

        private const val CHANNEL_ID = "remote_download"
        private const val NOTIFICATION_ID = 2001
        private const val POLL_INTERVAL_MS = 15_000L
        private const val PROGRESS_INTERVAL_MS = 2_000L
        private const val STATUS_WAITING_NETWORK = "waiting_network"
        private const val STATUS_AWAITING_METERED = "awaiting_metered_approval"
        private const val STATUS_DOWNLOADING = "downloading"
        private const val STATUS_COMPLETED = "completed"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_CANCEL_REQUESTED = "cancel_requested"
        private const val STATUS_CANCELED = "canceled"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastStatus: String = "远程文件服务未启用"
            private set

        @Volatile
        var lastError: Boolean = false
            private set
    }
}

private class DownloadCanceledException : IOException("下载已取消")
