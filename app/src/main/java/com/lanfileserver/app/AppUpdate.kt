package com.lanfileserver.app

import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val changelog: List<String>,
)

object AppUpdateChecker {
    private const val UPDATE_URL =
        "https://api.dongyanjun.xyz/api/hotspot-file-server/update"

    @Volatile
    var cachedUpdate: AppUpdateInfo? = null
        private set

    fun check(): AppUpdateInfo? {
        val connection = URI(UPDATE_URL).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "HotspotFileServer/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299 || !json.optBoolean("success", false)) {
                throw IOException(
                    json.optString("message").ifBlank { "更新检查失败（$status）" },
                )
            }

            val versionCode = json.getLong("versionCode")
            if (versionCode <= BuildConfig.VERSION_CODE.toLong()) {
                cachedUpdate = null
                return null
            }
            val changes = json.optJSONArray("changelog")
            return AppUpdateInfo(
                versionCode = versionCode,
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256").lowercase(),
                size = json.optLong("size", 0L),
                changelog = buildList {
                    if (changes != null) {
                        for (index in 0 until changes.length()) {
                            add(changes.optString(index))
                        }
                    }
                },
            ).also { cachedUpdate = it }
        } finally {
            connection.disconnect()
        }
    }

    fun notifyAvailable(context: Context, update: AppUpdateInfo) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                "app_update_available",
                "可用更新",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(
            context,
            40,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            3002,
            NotificationCompat.Builder(context, "app_update_available")
                .setSmallIcon(R.drawable.ic_server_notification)
                .setContentTitle("热点文件站 ${update.versionName} 可用")
                .setContentText("打开应用下载并安装更新")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

object UpdateInstaller {
    fun readyApk(context: Context, versionCode: Long): File =
        File(updateDirectory(context), "hotspot-file-server-$versionCode.apk")

    fun validatedReadyApk(context: Context, expectedVersionCode: Long): File? {
        val file = readyApk(context, expectedVersionCode)
        if (!file.isFile) return null
        return if (runCatching {
                verifyPackage(context, file, expectedVersionCode)
            }.isSuccess
        ) {
            file
        } else {
            file.delete()
            null
        }
    }

    fun discardStaleReadyApks(context: Context, expectedVersionCode: Long? = null) {
        val keep = expectedVersionCode?.let { validatedReadyApk(context, it) }
        updateDirectory(context).listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            .filterNot { file -> keep != null && file == keep }
            .forEach(File::delete)
    }

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        ) {
            return
        }
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    fun openInstaller(context: Context, file: File, expectedVersionCode: Long) {
        require(file.isFile) { "更新文件不存在" }
        verifyPackage(context, file, expectedVersionCode)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun updateDirectory(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    @Suppress("DEPRECATION")
    fun verifyPackage(context: Context, file: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("无法识别更新安装包")
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) {
            throw IOException("更新安装包的应用标识不一致")
        }

        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        if (archiveVersion != expectedVersionCode ||
            archiveVersion <= BuildConfig.VERSION_CODE.toLong()
        ) {
            throw IOException("更新安装包版本不正确")
        }

        val archiveSignatures = signatureDigests(archive)
        val currentSignatures = signatureDigests(current)
        if (archiveSignatures.isEmpty() ||
            currentSignatures.isEmpty() ||
            archiveSignatures.intersect(currentSignatures).isEmpty()
        ) {
            throw IOException("更新安装包签名与当前应用不一致")
        }
    }

    @Suppress("DEPRECATION")
    private fun signatureDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
