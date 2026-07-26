package com.lanfileserver.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
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
    private val liteHtml = context.assets.open("web/lite.html").bufferedReader().use { it.readText() }
    private val liteLoginHtml =
        context.assets.open("web/lite-login.html").bufferedReader().use { it.readText() }
    private val liteDeleteHtml =
        context.assets.open("web/lite-delete.html").bufferedReader().use { it.readText() }
    private val sessionToken = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private val legacyCsrfToken = ByteArray(24).also(SecureRandom()::nextBytes).let {
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
                uri == "/lite" || uri.startsWith("/lite/") -> serveLite(session, uri)

                !authenticated(session) -> {
                    if (uri.startsWith("/api/")) {
                        jsonError(Response.Status.UNAUTHORIZED, "请先登录")
                    } else if (uri == "/" && prefersLegacyBrowser(session)) {
                        redirect("/lite")
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
            failure(session, error.status, error.message ?: "请求失败")
        } catch (error: AlreadyExistsException) {
            failure(session, Response.Status.CONFLICT, error.message ?: "目标已存在")
        } catch (error: FileNotFoundException) {
            failure(session, Response.Status.NOT_FOUND, error.message ?: "目标不存在")
        } catch (error: SecurityException) {
            failure(session, Response.Status.FORBIDDEN, error.message ?: "没有访问权限")
        } catch (error: IllegalArgumentException) {
            failure(session, Response.Status.BAD_REQUEST, error.message ?: "请求参数无效")
        } catch (error: IOException) {
            failure(session, Response.Status.INTERNAL_ERROR, error.message ?: "文件操作失败")
        } catch (_: Throwable) {
            failure(session, Response.Status.INTERNAL_ERROR, "服务器内部错误")
        }
    }

    private fun login(session: IHTTPSession): Response {
        val supplied = readRequestBody(session, 64).trim()
        val error = authenticatePin(supplied)
        if (error != null) return jsonError(error.first, error.second)

        return secure(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")).apply {
            addHeader(
                "Set-Cookie",
                "$COOKIE_NAME=$sessionToken; Path=/; HttpOnly; SameSite=Strict",
            )
        }
    }

    private fun authenticatePin(supplied: String): Pair<Response.IStatus, String>? {
        val now = System.currentTimeMillis()
        synchronized(loginLock) {
            if (now < blockedUntil) {
                val waitSeconds = ((blockedUntil - now) / 1_000L).coerceAtLeast(1L)
                return Response.Status.TOO_MANY_REQUESTS to
                    "尝试次数过多，请在 ${waitSeconds} 秒后重试"
            }
        }

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
            return Response.Status.UNAUTHORIZED to "访问码不正确"
        }

        synchronized(loginLock) {
            failedLogins = 0
            blockedUntil = 0L
        }
        return null
    }

    private fun logout(): Response =
        secure(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")).apply {
            addHeader(
                "Set-Cookie",
                "$COOKIE_NAME=deleted; Path=/; Max-Age=0; HttpOnly; SameSite=Strict",
            )
        }

    private fun serveLite(session: IHTTPSession, uri: String): Response {
        if (uri == "/lite/login" && session.method == Method.POST) {
            parseFormBody(session)
            val supplied = formParameter(session, "pin").trim()
            val error = authenticatePin(supplied)
            if (error != null) return liteLogin(error.first, error.second)

            return redirect("/lite").apply {
                addHeader(
                    "Set-Cookie",
                    "$LEGACY_COOKIE_NAME=$sessionToken; Path=/; HttpOnly",
                )
            }
        }

        if (!legacyAuthenticated(session)) {
            return liteLogin(Response.Status.OK, "")
        }

        return when {
            uri == "/lite" && session.method == Method.GET -> liteIndex(session)
            uri == "/lite/download" &&
                (session.method == Method.GET || session.method == Method.HEAD) -> download(session)

            uri == "/lite/upload" && session.method == Method.POST -> liteUpload(session)
            uri == "/lite/mkdir" && session.method == Method.POST -> liteMkdir(session)
            uri == "/lite/rename" && session.method == Method.POST -> liteRename(session)
            uri == "/lite/delete" && session.method == Method.GET -> liteDeleteConfirmation(session)
            uri == "/lite/delete" && session.method == Method.POST -> liteDelete(session)
            uri == "/lite/logout" && session.method == Method.POST -> liteLogout(session)
            else -> liteFailure(Response.Status.NOT_FOUND, "页面不存在")
        }
    }

    private fun liteLogin(status: Response.IStatus, error: String): Response {
        val errorBlock = if (error.isBlank()) {
            ""
        } else {
            """<p class="error" role="alert">${escapeHtml(error)}</p>"""
        }
        return html(status, liteLoginHtml.replace("{{ERROR_BLOCK}}", errorBlock))
    }

    private fun liteIndex(session: IHTTPSession): Response {
        val currentPath = SafePath.normalize(parameter(session, "path", required = false))
        val entries = storage.list(currentPath)
        val notice = parameter(session, "notice", required = false)
        val parent = SafePath.parent(currentPath)
        val parentLink = if (currentPath.isEmpty()) {
            ""
        } else {
            """<a class="button" href="/lite?path=${url(parent)}">返回上一级</a>"""
        }
        val rows = if (entries.isEmpty()) {
            """<tr><td class="empty" colspan="3">这个文件夹是空的</td></tr>"""
        } else {
            entries.joinToString("\n", transform = ::liteRow)
        }
        val noticeBlock = if (notice.isBlank()) {
            ""
        } else {
            """<p class="notice">${escapeHtml(notice)}</p>"""
        }

        val body = liteHtml
            .replace("{{ROOT_NAME}}", escapeHtml(storage.displayName))
            .replace(
                "{{CURRENT_PATH}}",
                escapeHtml(if (currentPath.isEmpty()) "/" else "/$currentPath"),
            )
            .replace("{{ENCODED_PATH}}", escapeHtml(currentPath))
            .replace("{{PARENT_LINK}}", parentLink)
            .replace("{{NOTICE_BLOCK}}", noticeBlock)
            .replace("{{ROWS}}", rows)
            .replace("{{CSRF_TOKEN}}", escapeHtml(legacyCsrfToken))
        return html(Response.Status.OK, body)
    }

    private fun liteRow(entry: StorageTree.Entry): String {
        val path = url(entry.path)
        val name = escapeHtml(entry.name)
        val itemLink = if (entry.directory) {
            """<a class="item folder" href="/lite?path=$path">[文件夹] $name</a>"""
        } else {
            """<a class="item" href="/lite/download?path=$path">$name</a>"""
        }
        val detail = if (entry.directory) {
            "文件夹"
        } else {
            formatLiteSize(entry.size)
        }
        val modified = if (entry.modifiedAt > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.modifiedAt))
        } else {
            "-"
        }
        return """
            <tr>
              <td>$itemLink<div class="meta">${escapeHtml(detail)} · ${escapeHtml(modified)}</div></td>
              <td>
                <form class="rename" method="post" action="/lite/rename" accept-charset="UTF-8">
                  <input type="hidden" name="csrf" value="${escapeHtml(legacyCsrfToken)}">
                  <input type="hidden" name="path" value="${escapeHtml(entry.path)}">
                  <input type="hidden" name="returnPath" value="${escapeHtml(SafePath.parent(entry.path))}">
                  <input class="rename-input" type="text" name="name" value="$name" maxlength="180">
                  <button type="submit">重命名</button>
                </form>
              </td>
              <td class="actions">
                <a class="danger" href="/lite/delete?path=$path&amp;returnPath=${url(SafePath.parent(entry.path))}">删除</a>
              </td>
            </tr>
        """.trimIndent()
    }

    private fun liteUpload(session: IHTTPSession): Response {
        val files = parseFormBody(session)
        requireLiteCsrf(session)
        val parentPath = SafePath.normalize(formParameter(session, "path", required = false))
        val temporaryPath = files["file"]
            ?: throw HttpFailure(Response.Status.BAD_REQUEST, "请选择要上传的文件")
        val requestedName = formParameter(session, "file")
        val temporaryFile = File(temporaryPath)
        if (!temporaryFile.isFile) {
            throw HttpFailure(Response.Status.BAD_REQUEST, "没有收到上传文件")
        }

        val result = FileInputStream(temporaryFile).use { input ->
            storage.upload(parentPath, requestedName, input, temporaryFile.length())
        }
        return redirectLite(parentPath, "已上传 ${result.name}")
    }

    private fun liteMkdir(session: IHTTPSession): Response {
        parseFormBody(session)
        requireLiteCsrf(session)
        val parentPath = SafePath.normalize(formParameter(session, "path", required = false))
        val entry = storage.createDirectory(parentPath, formParameter(session, "name"))
        return redirectLite(parentPath, "已创建 ${entry.name}")
    }

    private fun liteRename(session: IHTTPSession): Response {
        parseFormBody(session)
        requireLiteCsrf(session)
        val returnPath = SafePath.normalize(
            formParameter(session, "returnPath", required = false),
        )
        val entry = storage.rename(
            formParameter(session, "path"),
            formParameter(session, "name"),
        )
        return redirectLite(returnPath, "已重命名为 ${entry.name}")
    }

    private fun liteDeleteConfirmation(session: IHTTPSession): Response {
        val path = SafePath.normalize(parameter(session, "path"))
        if (path.isEmpty()) throw IllegalArgumentException("不能删除共享根目录")
        val returnPath = SafePath.normalize(
            parameter(session, "returnPath", required = false),
        )
        val name = SafePath.segments(path).last()
        val body = liteDeleteHtml
            .replace("{{ITEM_NAME}}", escapeHtml(name))
            .replace("{{ITEM_PATH}}", escapeHtml(path))
            .replace("{{RETURN_PATH}}", escapeHtml(returnPath))
            .replace("{{RETURN_URL}}", url(returnPath))
            .replace("{{CSRF_TOKEN}}", escapeHtml(legacyCsrfToken))
        return html(Response.Status.OK, body)
    }

    private fun liteDelete(session: IHTTPSession): Response {
        parseFormBody(session)
        requireLiteCsrf(session)
        val path = formParameter(session, "path")
        val returnPath = SafePath.normalize(
            formParameter(session, "returnPath", required = false),
        )
        storage.delete(path)
        return redirectLite(returnPath, "已删除")
    }

    private fun liteLogout(session: IHTTPSession): Response {
        parseFormBody(session)
        requireLiteCsrf(session)
        return redirect("/lite").apply {
            addHeader(
                "Set-Cookie",
                "$LEGACY_COOKIE_NAME=deleted; Path=/; Max-Age=0; HttpOnly",
            )
        }
    }

    private fun parseFormBody(session: IHTTPSession): Map<String, String> {
        val contentLength = session.headers["content-length"]?.toLongOrNull()
            ?: throw HttpFailure(Response.Status.LENGTH_REQUIRED, "请求缺少内容长度")
        if (contentLength < 0L) {
            throw HttpFailure(Response.Status.BAD_REQUEST, "请求大小无效")
        }
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (error: ResponseException) {
            throw HttpFailure(error.status, error.message ?: "表单内容无效")
        }
        return files
    }

    private fun formParameter(
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

    private fun requireLiteCsrf(session: IHTTPSession) {
        val supplied = formParameter(session, "csrf")
        if (!MessageDigest.isEqual(
                supplied.toByteArray(StandardCharsets.UTF_8),
                legacyCsrfToken.toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            throw HttpFailure(Response.Status.FORBIDDEN, "页面已失效，请返回后重试")
        }
    }

    private fun redirectLite(path: String, notice: String): Response =
        redirect("/lite?path=${url(path)}&notice=${url(notice)}")

    private fun redirect(location: String): Response =
        secure(newFixedLengthResponse(SEE_OTHER, MIME_PLAINTEXT, "")).apply {
            addHeader("Location", location)
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
        return cookieMatches(session, COOKIE_NAME)
    }

    private fun legacyAuthenticated(session: IHTTPSession): Boolean =
        cookieMatches(session, LEGACY_COOKIE_NAME) || authenticated(session)

    private fun cookieMatches(session: IHTTPSession, name: String): Boolean {
        val cookie = session.headers["cookie"] ?: return false
        val supplied = cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            sessionToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun prefersLegacyBrowser(session: IHTTPSession): Boolean {
        val userAgent = session.headers["user-agent"].orEmpty()
        val major = ANDROID_VERSION.find(userAgent)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return major != null && major <= 5
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

    private fun failure(
        session: IHTTPSession,
        status: Response.IStatus,
        message: String,
    ): Response = if (session.uri == "/lite" || session.uri.startsWith("/lite/")) {
        liteFailure(status, message)
    } else {
        jsonError(status, message)
    }

    private fun liteFailure(status: Response.IStatus, message: String): Response =
        html(
            status,
            """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>请求失败 - 热点文件站</title>
                  <style>
                    body { margin: 24px; color: #172033; background: #f1f5f7;
                      font: 16px/1.6 Arial, sans-serif; }
                    main { max-width: 520px; margin: 40px auto; padding: 22px;
                      border: 1px solid #ccd6df; background: #fff; }
                    a { color: #086c64; }
                  </style>
                </head>
                <body><main>
                  <h1>操作没有完成</h1>
                  <p>${escapeHtml(message)}</p>
                  <p><a href="/lite">返回兼容文件页</a></p>
                </main></body>
                </html>
            """.trimIndent(),
        )

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private fun url(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun formatLiteSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

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
        private const val LEGACY_COOKIE_NAME = "LanFileLegacySession"
        private val RANGE_PATTERN = Regex("""bytes=(\d*)-(\d*)""", RegexOption.IGNORE_CASE)
        private val ANDROID_VERSION =
            Regex("""Android\s+(\d+)(?:[.;\s]|$)""", RegexOption.IGNORE_CASE)
        private val SEE_OTHER = object : Response.IStatus {
            override fun getRequestStatus(): Int = 303
            override fun getDescription(): String = "303 See Other"
        }
    }
}
