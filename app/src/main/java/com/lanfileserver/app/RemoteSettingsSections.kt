package com.lanfileserver.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DevicePairingSection(
    device: RemoteDeviceCredentials?,
    running: Boolean,
    onDeviceChange: (RemoteDeviceCredentials?) -> Unit,
    onStopRemote: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "设备配对",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (device == null) {
            Text(
                "先在远程控制页生成 8 位配对码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(8) },
                    label = { Text("配对码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        busy = true
                        error = false
                        message = "正在配对…"
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    RemoteDownloadApi().pair(code)
                                }
                            }.onSuccess { credentials ->
                                AppPreferences.saveRemoteDevice(context, credentials)
                                onDeviceChange(credentials)
                                code = ""
                                message = "已配对：${credentials.name}"
                            }.onFailure {
                                error = true
                                message = it.message ?: "配对失败"
                            }
                            busy = false
                        }
                    },
                    enabled = code.length == 8 && !busy,
                ) {
                    Text("配对")
                }
            }
        } else {
            Text(device.name, fontWeight = FontWeight.SemiBold)
            Text(
                "设备 ID ${device.id.take(12)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { confirmUnpair = true },
                enabled = !busy,
            ) {
                Text("解除本机配对")
            }
        }
        if (message != null) {
            Text(
                message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("解除本机配对？") },
            text = { Text("后台仍会保留设备记录，需在控制页中解除绑定。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (running) onStopRemote()
                        AppPreferences.clearRemoteDevice(context)
                        onDeviceChange(null)
                        confirmUnpair = false
                        message = "本机配对信息已清除"
                    },
                ) {
                    Text("解除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
fun AppUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf(AppUpdateChecker.cachedUpdate) }
    var status by remember { mutableStateOf(UpdateDownloadService.lastStatus) }
    var error by remember { mutableStateOf(UpdateDownloadService.lastError) }
    var progress by remember { mutableStateOf(UpdateDownloadService.progressPercent) }
    var downloading by remember { mutableStateOf(UpdateDownloadService.running) }
    var readyFile by remember {
        mutableStateOf(UpdateInstaller.readyApk(context).takeIf(File::isFile))
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun checkForUpdates(manual: Boolean) {
        if (checking) return
        checking = true
        error = false
        status = "正在检查更新…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppUpdateChecker.check() }
            }.onSuccess { available ->
                AppPreferences.markUpdateChecked(context)
                update = available
                status = if (available == null) {
                    "当前已是最新版本 ${BuildConfig.VERSION_NAME}"
                } else {
                    "发现新版本 ${available.versionName}"
                }
            }.onFailure {
                if (manual) {
                    error = true
                    status = it.message ?: "检查更新失败"
                }
            }
            checking = false
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                downloading = UpdateDownloadService.running
                status = intent?.getStringExtra(UpdateDownloadService.EXTRA_STATUS)
                    ?: UpdateDownloadService.lastStatus
                error = intent?.getBooleanExtra(UpdateDownloadService.EXTRA_ERROR, false)
                    ?: false
                val reported = intent?.getIntExtra(UpdateDownloadService.EXTRA_PROGRESS, -1)
                    ?: -1
                progress = reported.takeIf { it >= 0 }
                readyFile = UpdateInstaller.readyApk(context).takeIf(File::isFile)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(UpdateDownloadService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        val due = System.currentTimeMillis() - AppPreferences.lastUpdateCheck(context) >=
            24L * 60L * 60L * 1_000L
        if (due) checkForUpdates(manual = false)
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "应用更新",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "当前版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        update?.let { info ->
            Text("可更新到 ${info.versionName}", fontWeight = FontWeight.SemiBold)
            info.changelog.filter(String::isNotBlank).forEach { item ->
                Text(
                    "- $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (downloading) {
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { (progress ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            OutlinedButton(
                onClick = { UpdateInstaller.openInstallPermission(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("允许安装应用更新")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { checkForUpdates(manual = true) },
                enabled = !checking && !downloading,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (checking) "正在检查" else "检查更新")
            }

            when {
                readyFile != null -> {
                    Button(
                        onClick = {
                            runCatching {
                                UpdateInstaller.openInstaller(context, requireNotNull(readyFile))
                            }.onFailure {
                                error = true
                                status = it.message ?: "无法打开安装程序"
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("安装更新")
                    }
                }

                update != null -> {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            val info = requireNotNull(update)
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, UpdateDownloadService::class.java)
                                    .setAction(UpdateDownloadService.ACTION_START)
                                    .putExtra(UpdateDownloadService.EXTRA_URL, info.apkUrl)
                                    .putExtra(UpdateDownloadService.EXTRA_SHA256, info.sha256)
                                    .putExtra(
                                        UpdateDownloadService.EXTRA_VERSION_CODE,
                                        info.versionCode,
                                    )
                                    .putExtra(
                                        UpdateDownloadService.EXTRA_VERSION_NAME,
                                        info.versionName,
                                    ),
                            )
                            downloading = true
                            status = "正在启动更新下载…"
                        },
                        enabled = !downloading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("下载更新")
                    }
                }

                else -> {
                    Button(
                        onClick = { checkForUpdates(manual = true) },
                        enabled = false,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text("暂无更新")
                    }
                }
            }
        }
    }
}
