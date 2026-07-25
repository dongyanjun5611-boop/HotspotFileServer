package com.lanfileserver.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.util.Locale

class StorageTree(
    private val context: Context,
    rootUri: Uri,
) {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, rootUri)
        ?: throw IllegalArgumentException("无法打开共享文件夹")
    private val mutationLock = Any()

    val displayName: String
        get() = root.name ?: "共享文件夹"

    val canWrite: Boolean
        get() = root.canWrite()

    data class Entry(
        val name: String,
        val path: String,
        val directory: Boolean,
        val size: Long,
        val modifiedAt: Long,
        val mimeType: String,
    )

    data class OpenedFile(
        val name: String,
        val length: Long,
        val mimeType: String,
        val input: InputStream,
    )

    data class UploadResult(
        val name: String,
        val path: String,
        val size: Long,
    )

    fun list(path: String): List<Entry> {
        val normalized = SafePath.normalize(path)
        val directory = resolve(normalized)
            ?: throw FileNotFoundException("文件夹不存在")
        if (!directory.isDirectory) throw IllegalArgumentException("目标不是文件夹")

        return directory.listFiles()
            .mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                Entry(
                    name = name,
                    path = SafePath.join(normalized, name),
                    directory = document.isDirectory,
                    size = if (document.isDirectory) 0L else document.length().coerceAtLeast(0L),
                    modifiedAt = document.lastModified().coerceAtLeast(0L),
                    mimeType = document.type ?: mimeTypeFor(name),
                )
            }
            .sortedWith(
                compareByDescending<Entry> { it.directory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
    }

    fun open(path: String): OpenedFile {
        val document = resolve(path) ?: throw FileNotFoundException("文件不存在")
        if (!document.isFile) throw IllegalArgumentException("目标不是文件")
        val input = context.contentResolver.openInputStream(document.uri)
            ?: throw IOException("无法读取文件")
        val name = document.name ?: "download"
        return OpenedFile(
            name = name,
            length = document.length().coerceAtLeast(0L),
            mimeType = document.type ?: mimeTypeFor(name),
            input = input,
        )
    }

    fun createDirectory(parentPath: String, requestedName: String): Entry {
        val safeName = SafePath.sanitizeName(requestedName)
        val normalizedParent = SafePath.normalize(parentPath)
        synchronized(mutationLock) {
            val parent = requireDirectory(normalizedParent)
            if (parent.findFile(safeName) != null) {
                throw AlreadyExistsException("同名文件或文件夹已存在")
            }
            val created = parent.createDirectory(safeName)
                ?: throw IOException("无法创建文件夹")
            val entry = Entry(
                name = created.name ?: safeName,
                path = SafePath.join(normalizedParent, created.name ?: safeName),
                directory = true,
                size = 0L,
                modifiedAt = created.lastModified().coerceAtLeast(0L),
                mimeType = "inode/directory",
            )
            FileChangeNotifier.notify(context)
            return entry
        }
    }

    fun upload(
        parentPath: String,
        requestedName: String,
        input: InputStream,
        contentLength: Long,
    ): UploadResult {
        require(contentLength >= 0L) { "文件大小无效" }
        val safeName = SafePath.sanitizeName(requestedName)
        val normalizedParent = SafePath.normalize(parentPath)

        val (document, actualName) = synchronized(mutationLock) {
            val parent = requireDirectory(normalizedParent)
            val availableName = uniqueName(parent, safeName)
            val created = parent.createFile(mimeTypeFor(availableName), availableName)
                ?: throw IOException("无法创建文件")
            created to availableName
        }

        try {
            val output = context.contentResolver.openOutputStream(document.uri, "w")
                ?: throw IOException("无法写入文件")
            output.use { destination ->
                copyExactly(input, destination, contentLength)
                destination.flush()
            }
        } catch (error: Throwable) {
            runCatching { document.delete() }
            throw error
        }

        val finalName = document.name ?: actualName
        val result = UploadResult(
            name = finalName,
            path = SafePath.join(normalizedParent, finalName),
            size = contentLength,
        )
        FileChangeNotifier.notify(context)
        return result
    }

    fun writeFile(
        parentPath: String,
        requestedName: String,
        mimeType: String,
        writer: (OutputStream) -> Long,
    ): UploadResult {
        val safeName = SafePath.sanitizeName(requestedName)
        val safeMimeType = mimeType.ifBlank { mimeTypeFor(safeName) }
        val normalizedParent = SafePath.normalize(parentPath)
        val (document, actualName) = synchronized(mutationLock) {
            val parent = requireDirectory(normalizedParent)
            val availableName = uniqueName(parent, safeName)
            val created = parent.createFile(safeMimeType, availableName)
                ?: throw IOException("无法创建下载文件")
            created to availableName
        }

        val size = try {
            val output = context.contentResolver.openOutputStream(document.uri, "w")
                ?: throw IOException("无法写入下载文件")
            output.use { destination ->
                writer(destination).also { destination.flush() }
            }
        } catch (error: Throwable) {
            runCatching { document.delete() }
            throw error
        }

        val finalName = document.name ?: actualName
        val result = UploadResult(
            name = finalName,
            path = SafePath.join(normalizedParent, finalName),
            size = size,
        )
        FileChangeNotifier.notify(context)
        return result
    }

    fun rename(path: String, requestedName: String): Entry {
        val normalized = SafePath.normalize(path)
        if (normalized.isEmpty()) throw IllegalArgumentException("不能重命名共享根目录")
        val safeName = SafePath.sanitizeName(requestedName)

        synchronized(mutationLock) {
            val document = resolve(normalized) ?: throw FileNotFoundException("目标不存在")
            val parentPath = SafePath.parent(normalized)
            val parent = requireDirectory(parentPath)
            val existing = parent.findFile(safeName)
            if (existing != null && existing.uri != document.uri) {
                throw AlreadyExistsException("同名文件或文件夹已存在")
            }
            if (!document.renameTo(safeName)) throw IOException("重命名失败")

            val renamed = parent.findFile(safeName)
                ?: throw IOException("重命名后无法读取目标")
            val actualName = renamed.name ?: safeName
            val entry = Entry(
                name = actualName,
                path = SafePath.join(parentPath, actualName),
                directory = renamed.isDirectory,
                size = if (renamed.isDirectory) 0L else renamed.length().coerceAtLeast(0L),
                modifiedAt = renamed.lastModified().coerceAtLeast(0L),
                mimeType = renamed.type ?: mimeTypeFor(actualName),
            )
            FileChangeNotifier.notify(context)
            return entry
        }
    }

    fun delete(path: String) {
        val normalized = SafePath.normalize(path)
        if (normalized.isEmpty()) throw IllegalArgumentException("不能删除共享根目录")
        synchronized(mutationLock) {
            val document = resolve(normalized) ?: throw FileNotFoundException("目标不存在")
            if (!document.delete()) throw IOException("删除失败")
            FileChangeNotifier.notify(context)
        }
    }

    fun uriFor(path: String): Uri =
        resolve(path)?.uri ?: throw FileNotFoundException("目标不存在")

    private fun resolve(path: String): DocumentFile? {
        var current = root
        SafePath.segments(path).forEach { segment ->
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun requireDirectory(path: String): DocumentFile {
        val directory = resolve(path) ?: throw FileNotFoundException("文件夹不存在")
        if (!directory.isDirectory) throw IllegalArgumentException("目标不是文件夹")
        if (!directory.canWrite()) throw SecurityException("共享文件夹不可写")
        return directory
    }

    private fun uniqueName(parent: DocumentFile, requestedName: String): String {
        if (parent.findFile(requestedName) == null) return requestedName

        val dot = requestedName.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < requestedName.lastIndex
        val base = if (hasExtension) requestedName.substring(0, dot) else requestedName
        val extension = if (hasExtension) requestedName.substring(dot) else ""
        for (index in 1..9_999) {
            val candidate = "$base ($index)$extension"
            if (parent.findFile(candidate) == null) return candidate
        }
        throw IOException("同名文件过多")
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        var remaining = length
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("上传连接提前中断")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun mimeTypeFor(name: String): String =
        URLConnection.guessContentTypeFromName(name.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
}

class AlreadyExistsException(message: String) : IOException(message)
