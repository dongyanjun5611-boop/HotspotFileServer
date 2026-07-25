package com.lanfileserver.app

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RemoteDownloadJob(
    val id: String,
    val url: String,
    val requestedFileName: String,
    val status: String,
)

data class RemoteProgress(
    val status: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0L,
    val fileName: String = "",
    val error: String = "",
    val statusMessage: String = "",
)

class RemoteDownloadApi {
    fun poll(): List<RemoteDownloadJob> {
        val deviceName = URLEncoder.encode(
            "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            StandardCharsets.UTF_8.name(),
        )
        val appVersion = URLEncoder.encode(BuildConfig.VERSION_NAME, StandardCharsets.UTF_8.name())
        val response = request(
            method = "GET",
            path = "/api/offline-download/device/poll?deviceName=$deviceName&appVersion=$appVersion",
        )
        val jobs = response.optJSONArray("jobs") ?: JSONArray()
        return buildList {
            for (index in 0 until jobs.length()) {
                val item = jobs.getJSONObject(index)
                add(
                    RemoteDownloadJob(
                        id = item.getString("id"),
                        url = item.getString("url"),
                        requestedFileName = item.optString("requestedFileName"),
                        status = item.getString("status"),
                    ),
                )
            }
        }
    }

    fun report(jobId: String, progress: RemoteProgress): Boolean {
        val body = JSONObject()
            .put("status", progress.status)
            .put("downloadedBytes", progress.downloadedBytes)
            .put("speedBytesPerSecond", progress.speedBytesPerSecond)
            .put("fileName", progress.fileName)
            .put("error", progress.error)
            .put("statusMessage", progress.statusMessage)
        if (progress.totalBytes != null) {
            body.put("totalBytes", progress.totalBytes)
        } else {
            body.put("totalBytes", JSONObject.NULL)
        }

        return request(
            method = "POST",
            path = "/api/offline-download/device/jobs/${encodePath(jobId)}/progress",
            body = body,
        ).optBoolean("cancelRequested", false)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject {
        if (BuildConfig.REMOTE_DEVICE_TOKEN.isBlank()) {
            throw RemoteApiException("当前安装包未配置远程设备令牌")
        }

        val connection = URI("$BASE_URL$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.REMOTE_DEVICE_TOKEN}")
            connection.setRequestProperty("User-Agent", "HotspotFileServer/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(bytes) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299 || json.optBoolean("success", true).not()) {
                val message = json.optString("message").ifBlank { "远程服务请求失败（$status）" }
                throw RemoteApiException(message, status)
            }
            return json
        } catch (error: RemoteApiException) {
            throw error
        } catch (error: IOException) {
            throw RemoteApiException(error.message ?: "无法连接远程服务", cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val BASE_URL = "https://api.dongyanjun.xyz"
    }
}

class RemoteApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
