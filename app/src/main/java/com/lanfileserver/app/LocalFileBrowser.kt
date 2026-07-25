package com.lanfileserver.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocalFileBrowser(
    treeUri: String?,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentPath by remember(treeUri) { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<StorageTree.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedEntry by remember { mutableStateOf<StorageTree.Entry?>(null) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }

    val addFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty() || treeUri.isNullOrBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val storage = StorageTree(context, treeUri.toUri())
                    uris.forEach { uri ->
                        val metadata = queryDocumentMetadata(context, uri)
                        val mimeType = context.contentResolver.getType(uri)
                            ?: "application/octet-stream"
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            storage.writeFile(
                                parentPath = currentPath,
                                requestedName = metadata.first,
                                mimeType = mimeType,
                            ) { output ->
                                input.copyTo(output, 64 * 1024)
                            }
                        } ?: error("无法读取 ${metadata.first}")
                    }
                }
            }.onFailure {
                error = it.message ?: "添加文件失败"
            }
            refreshKey += 1
            loading = false
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                refreshKey += 1
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(FileChangeNotifier.ACTION_FILES_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(treeUri, currentPath, refreshKey) {
        if (treeUri.isNullOrBlank()) {
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                StorageTree(context, treeUri.toUri()).list(currentPath)
            }
        }.onSuccess {
            entries = it
        }.onFailure {
            error = it.message ?: "无法读取文件夹"
        }
        loading = false
    }

    fun runMutation(block: (StorageTree) -> Unit) {
        val uri = treeUri ?: return
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    block(StorageTree(context, uri.toUri()))
                }
            }.onFailure {
                error = it.message ?: "文件操作失败"
            }
            selectedEntry = null
            dialog = null
            refreshKey += 1
            loading = false
        }
    }

    if (treeUri.isNullOrBlank()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("尚未选择共享文件夹")
                Button(onClick = onChooseFolder) {
                    Text("选择文件夹")
                }
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { currentPath = SafePath.parent(currentPath) },
                enabled = currentPath.isNotEmpty() && !loading,
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "返回上级目录")
            }
            Text(
                text = if (currentPath.isEmpty()) "共享文件夹" else currentPath,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { refreshKey += 1 }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { dialog = FileDialog.NewFolder }, enabled = !loading) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
            }
            IconButton(
                onClick = { addFiles.launch(arrayOf("*/*")) },
                enabled = !loading,
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = "添加文件")
            }
        }
        HorizontalDivider()

        if (error != null) {
            Text(
                text = error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                entries.isEmpty() && loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                entries.isEmpty() -> {
                    Text(
                        "这个文件夹是空的",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.path }) { entry ->
                            FileEntryRow(
                                entry = entry,
                                menuOpen = selectedEntry?.path == entry.path,
                                onClick = {
                                    if (entry.directory) {
                                        currentPath = entry.path
                                    } else {
                                        openFile(context, treeUri, entry)
                                    }
                                },
                                onMenu = { selectedEntry = entry },
                                onDismissMenu = { selectedEntry = null },
                                onOpen = {
                                    selectedEntry = null
                                    if (entry.directory) currentPath = entry.path
                                    else openFile(context, treeUri, entry)
                                },
                                onShare = {
                                    selectedEntry = null
                                    shareFile(context, treeUri, entry)
                                },
                                onRename = { dialog = FileDialog.Rename(entry) },
                                onDelete = { dialog = FileDialog.Delete(entry) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            if (loading && entries.isNotEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                )
            }
        }
    }

    when (val activeDialog = dialog) {
        FileDialog.NewFolder -> {
            NameDialog(
                title = "新建文件夹",
                initialValue = "",
                confirmLabel = "创建",
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    runMutation { it.createDirectory(currentPath, name) }
                },
            )
        }

        is FileDialog.Rename -> {
            NameDialog(
                title = "重命名",
                initialValue = activeDialog.entry.name,
                confirmLabel = "保存",
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    runMutation { it.rename(activeDialog.entry.path, name) }
                },
            )
        }

        is FileDialog.Delete -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("删除${if (activeDialog.entry.directory) "文件夹" else "文件"}") },
                text = { Text(activeDialog.entry.name) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            runMutation { it.delete(activeDialog.entry.path) }
                        },
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text("取消")
                    }
                },
            )
        }

        null -> Unit
    }
}

@Composable
private fun FileEntryRow(
    entry: StorageTree.Entry,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                if (entry.directory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (entry.directory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        headlineContent = {
            Text(
                entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                if (entry.directory) {
                    formatModified(entry.modifiedAt)
                } else {
                    "${formatFileSize(entry.size)} · ${formatModified(entry.modifiedAt)}"
                },
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = onDismissMenu,
                ) {
                    DropdownMenuItem(
                        text = { Text(if (entry.directory) "打开" else "用其他应用打开") },
                        onClick = onOpen,
                        leadingIcon = {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                        },
                    )
                    if (!entry.directory) {
                        DropdownMenuItem(
                            text = { Text("分享") },
                            onClick = onShare,
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = onRename,
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = onDelete,
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(180) },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.trim().isNotEmpty(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun openFile(
    context: Context,
    treeUri: String,
    entry: StorageTree.Entry,
) {
    runCatching {
        val uri = StorageTree(context, treeUri.toUri()).uriFor(entry.path)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, entry.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

private fun shareFile(
    context: Context,
    treeUri: String,
    entry: StorageTree.Entry,
) {
    runCatching {
        val uri = StorageTree(context, treeUri.toUri()).uriFor(entry.path)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(entry.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "分享文件",
            ),
        )
    }
}

private fun queryDocumentMetadata(
    context: Context,
    uri: android.net.Uri,
): Pair<String, Long?> {
    var name: String? = null
    var size: Long? = null
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return (name ?: "添加的文件") to size
}

private fun formatFileSize(bytes: Long): String {
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

private fun formatModified(timestamp: Long): String =
    if (timestamp <= 0L) "时间未知"
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(timestamp))

private sealed interface FileDialog {
    data object NewFolder : FileDialog
    data class Rename(val entry: StorageTree.Entry) : FileDialog
    data class Delete(val entry: StorageTree.Entry) : FileDialog
}
