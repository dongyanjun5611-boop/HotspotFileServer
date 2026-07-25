package com.lanfileserver.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

class LanFileServer(
    context: Context,
    treeUri: Uri,
    port: Int,
    private val accessPin: String,
) : NanoHTTPD("0.0.0.0", port) {
    private val storage = StorageTree(context, treeUri)
    private val indexHtml = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
    private val loginHtml = context.assets.open("web/login.html").bufferedReader().use { it.readText() }
    private val sessionToken = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private val loginLock = Any()
    private var failedLogins = 0
    private var blockedUntil = 0L

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/').ifEmpty { "/" }
            when {
                uri == "/health" && session.method == Method.GET -> {
                    json(Response.Status.OK, JSONObject().put("ok", true))
                }

                uri == "/login" && session.method == Method.POST -> login(session)

                !authenticated(session) -> {
                    if (uri.startsWith("/api/")) {
                        jsonError(Response.Status.UNAUTHORIZED, "请先登录")
                    } else {
                        html(Response.Status.OK, loginHtml)
                    }
                }

                uri == "/" && session.method == Method.GET -> html(Response.Status.OK, indexHtml)
                uri == "/logout" && session.method == Method.POST -> logout()
                uri == "/api/info" && session.method == Method.GET -> info()
                uri == "/api/list" && session.method == Method.GET -> list(session)
                uri == "/api/download" &&
                    (session.method == Method.GET || session.method == Method.HEAD) -> download(session)

                uri == "/api/upload" && session.method == Method.PUT -> upload(session)
                uri == "/api/mkdir" && session.method == Method.POST -> mkdir(session)
                uri == "/api/rename" && session.method == Method.POST -> rename(session)
                uri == "/api/delete" && session.method == Method.POST -> delete(session)
                else -> jsonError(Response.Status.NOT_FOUND, "页面或接口不存在")
            }
        } catch (error: HttpFailure) {
            jsonError(error.status, error.message ?: "请求失败")
        } catch (error: AlreadyExistsException) {
            jsonError(Response.Status.CONFLICT, error.message ?: "目标已存在")
        } catch (error: FileNotFoundException) {
            jsonError(Response.Status.NOT_FOUND, error.message ?: "目标不存在")
        } catch (error: SecurityException) {
            jsonError(Response.Status.FORBIDDEN, error.message ?: "没有访问权限")
        } catch (error: IllegalArgumentException) {
            jsonError(Response.Status.BAD_REQUEST, error.message ?: "请求参数无效")
        } catch (error: IOException) {
            jsonError(Response.Status.INTERNAL_ERROR, error.message ?: "文件操作失败")
        } catch (_: Throwable) {
            jsonError(Response.Status.INTERNAL_ERROR, "服务器内部错误")
        }
    }

    private fun login(session: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        synchronized(loginLock) {
            if (now < blockedUntil) {
                val waitSeconds = ((blockedUntil - now) / 1_000L).coerceAtLeast(1L)
                return jsonError(Response.Status.TOO_MANY_REQUESTS, "尝试次数过多，请在 ${waitSeconds} 秒后重试")
            }
        }

        val supplied = readRequestBody(session, 64).trim()
        val matches = MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            accessPin.toByteArray(StandardCharsets.UTF_8),
        )
        if (!matches) {
            synchronized(loginLock) {
                failedLogins += 1
                if (failedLogins >= 5) {
                    blockedUntil = now + 30_000L
                    failedLogins = 0
                }
            }
            return jsonError(Response.Status.UNAUTHORIZED, "访问码不正确")
        }

        synchronized(loginLock) {
            failedLogins = 0
            blockedUntil = 0L
        }
        return secure(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")).apply {
            addHeader(
                "Set-Cookie",
                "$COOKIE_NAME=$sessionToken; Path=/; HttpOnly; SameSite=Strict",
            )
        }
    }

    private fun logout(): Response =
        secure(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")).apply {
            addHeader(
                "Set-Cookie",
                "$COOKIE_NAME=deleted; Path=/; Max-Age=0; HttpOnly; SameSite=Strict",
            )
        }

    private fun info(): Response {
        val body = JSONObject()
            .put("rootName", storage.displayName)
            .put("canWrite", storage.canWrite)
        return json(Response.Status.OK, body)
    }

    private fun list(session: IHTTPSession): Response {
        val path = parameter(session, "path", required = false)
        val entries = storage.list(path)
        val items = JSONArray()
        entries.forEach { entry ->
            items.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("path", entry.path)
                    .put("directory", entry.directory)
                    .put("size", entry.size)
                    .put("modifiedAt", entry.modifiedAt)
                    .put("mimeType", entry.mimeType),
            )
        }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("path", SafePath.normalize(path))
                .put("items", items),
        )
    }

    private fun download(session: IHTTPSession): Response {
        val opened = storage.open(parameter(session, "path"))
        val range = parseRange(session.headers["range"], opened.length)
        val response = if (range != null) {
            skipFully(opened.input, range.first)
            val count = range.last - range.first + 1L
            newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                opened.mimeType,
                LimitedInputStream(opened.input, count),
                count,
            ).apply {
                addHeader("Content-Range", "bytes ${range.first}-${range.last}/${opened.length}")
            }
        } else if (opened.length > 0L) {
            newFixedLengthResponse(
                Response.Status.OK,
                opened.mimeType,
                opened.input,
                opened.length,
            )
        } else {
            newChunkedResponse(Response.Status.OK, opened.mimeType, opened.input)
        }

        return secure(response).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Disposition", contentDisposition(opened.name))
        }
    }

    private fun upload(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: throw HttpFailure(Response.Status.LENGTH_REQUIRED, "上传请求缺少文件大小")
        if (length < 0L) throw HttpFailure(Response.Status.BAD_REQUEST, "文件大小无效")

        val result = storage.upload(
            parentPath = parameter(session, "path", required = false),
            requestedName = parameter(session, "name"),
            input = session.inputStream,
            contentLength = length,
        )
        return json(
            Response.Status.CREATED,
            JSONObject()
                .put("name", result.name)
                .put("path", result.path)
                .put("size", result.size),
        )
    }

    private fun mkdir(session: IHTTPSession): Response {
        val entry = storage.createDirectory(
            parentPath = parameter(session, "path", required = false),
            requestedName = parameter(session, "name"),
        )
        return json(
            Response.Status.CREATED,
            JSONObject().put("name", entry.name).put("path", entry.path),
        )
    }

    private fun rename(session: IHTTPSession): Response {
        val entry = storage.rename(
            path = parameter(session, "path"),
            requestedName = parameter(session, "name"),
        )
        return json(
            Response.Status.OK,
            JSONObject().put("name", entry.name).put("path", entry.path),
        )
    }

    private fun delete(session: IHTTPSession): Response {
        storage.delete(parameter(session, "path"))
        return json(Response.Status.OK, JSONObject().put("deleted", true))
    }

    private fun parameter(
        session: IHTTPSession,
        name: String,
        required: Boolean = true,
    ): String {
        val value = session.parameters[name]?.firstOrNull()
        if (required && value.isNullOrBlank()) {
            throw HttpFailure(Response.Status.BAD_REQUEST, "缺少参数：$name")
        }
        return value.orEmpty()
    }

    private fun authenticated(session: IHTTPSession): Boolean {
        val cookie = session.headers["cookie"] ?: return false
        val supplied = cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$COOKIE_NAME=") }
            ?.substringAfter('=')
            ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            sessionToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun readRequestBody(session: IHTTPSession, maxBytes: Int): String {
        val length = session.headers["content-length"]?.toIntOrNull()
            ?: throw HttpFailure(Response.Status.LENGTH_REQUIRED, "请求缺少内容长度")
        if (length !in 0..maxBytes) {
            throw HttpFailure(Response.Status.PAYLOAD_TOO_LARGE, "请求内容过长")
        }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(bytes, offset, length - offset)
            if (read < 0) throw HttpFailure(Response.Status.BAD_REQUEST, "请求内容不完整")
            offset += read
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun parseRange(header: String?, totalLength: Long): LongRange? {
        if (header.isNullOrBlank() || totalLength <= 0L) return null
        val match = RANGE_PATTERN.matchEntire(header.trim())
            ?: throw HttpFailure(Response.Status.RANGE_NOT_SATISFIABLE, "无效的下载范围")
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]

        val range = if (startText.isEmpty()) {
            val suffixLength = endText.toLongOrNull()
                ?: throw HttpFailure(Response.Status.RANGE_NOT_SATISFIABLE, "无效的下载范围")
            val start = (totalLength - suffixLength).coerceAtLeast(0L)
            start..(totalLength - 1L)
        } else {
            val start = startText.toLongOrNull()
                ?: throw HttpFailure(Response.Status.RANGE_NOT_SATISFIABLE, "无效的下载范围")
            val end = if (endText.isEmpty()) {
                totalLength - 1L
            } else {
                endText.toLongOrNull()
                    ?: throw HttpFailure(Response.Status.RANGE_NOT_SATISFIABLE, "无效的下载范围")
            }
            start..minOf(end, totalLength - 1L)
        }

        if (range.first < 0L || range.first >= totalLength || range.last < range.first) {
            throw HttpFailure(Response.Status.RANGE_NOT_SATISFIABLE, "下载范围超出文件大小")
        }
        return range
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining -= 1L
            } else {
                throw IOException("无法定位到下载位置")
            }
        }
    }

    private fun contentDisposition(name: String): String {
        val ascii = name
            .map { char -> if (char.code in 32..126 && char != '"' && char != '\\') char else '_' }
            .joinToString("")
            .ifEmpty { "download" }
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private fun html(status: Response.IStatus, body: String): Response =
        secure(newFixedLengthResponse(status, "text/html; charset=utf-8", body))

    private fun json(status: Response.IStatus, body: JSONObject): Response =
        secure(
            newFixedLengthResponse(
                status,
                "application/json; charset=utf-8",
                body.toString(),
            ),
        )

    private fun jsonError(status: Response.IStatus, message: String): Response =
        json(status, JSONObject().put("error", message))

    private fun secure(response: Response): Response = response.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; " +
                "script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
        )
    }

    private class HttpFailure(
        val status: Response.IStatus,
        override val message: String,
    ) : IOException(message)

    private class LimitedInputStream(
        private val delegate: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = delegate.read()
            if (value >= 0) remaining -= 1L
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count.toLong()
            return count
        }

        override fun close() = delegate.close()
    }

    companion object {
        private const val COOKIE_NAME = "LanFileSession"
        private val RANGE_PATTERN = Regex("""bytes=(\d*)-(\d*)""", RegexOption.IGNORE_CASE)
    }
}
