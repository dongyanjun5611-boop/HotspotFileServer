package com.lanfileserver.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lanfileserver.app.ui.HotspotFileServerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HotspotFileServerTheme {
                ServerControlScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerControlScreen() {
    val context = LocalContext.current
    var treeUri by remember { mutableStateOf(AppPreferences.treeUri(context)) }
    var portText by remember { mutableStateOf(AppPreferences.port(context).toString()) }
    var pinText by remember { mutableStateOf(AppPreferences.pin(context)) }
    var running by remember { mutableStateOf(FileServerService.running) }
    var error by remember { mutableStateOf(FileServerService.lastError) }
    var urls by remember { mutableStateOf(NetworkAddresses.urls(portText.toIntOrNull() ?: 0)) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }.onSuccess {
                AppPreferences.saveTreeUri(context, uri.toString())
                treeUri = uri.toString()
                error = null
            }.onFailure {
                error = "无法保存文件夹权限：${it.message.orEmpty()}"
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                running = intent?.getBooleanExtra(FileServerService.EXTRA_RUNNING, false) == true
                error = intent?.getStringExtra(FileServerService.EXTRA_ERROR)
                urls = NetworkAddresses.urls(portText.toIntOrNull() ?: AppPreferences.DEFAULT_PORT)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(FileServerService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(portText, running) {
        urls = NetworkAddresses.urls(portText.toIntOrNull() ?: AppPreferences.DEFAULT_PORT)
    }

    val folderName = remember(treeUri) {
        treeUri?.let { value ->
            runCatching {
                DocumentFile.fromTreeUri(context, value.toUri())?.name
            }.getOrNull()
        }
    }

    fun refreshAddresses() {
        urls = NetworkAddresses.urls(portText.toIntOrNull() ?: AppPreferences.DEFAULT_PORT)
    }

    fun startServer() {
        val port = portText.toIntOrNull()
        when {
            treeUri.isNullOrBlank() -> error = "请先选择一个共享文件夹"
            port == null || port !in 1024..65535 -> error = "端口需要填写 1024 到 65535 之间的数字"
            pinText.length !in 4..12 -> error = "访问码需要 4 到 12 位"
            else -> {
                AppPreferences.saveConfiguration(context, port, pinText)
                error = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FileServerService::class.java)
                        .setAction(FileServerService.ACTION_START),
                )
            }
        }
    }

    fun stopServer() {
        context.startService(
            Intent(context, FileServerService::class.java)
                .setAction(FileServerService.ACTION_STOP),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("热点文件站", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (running) "局域网服务运行中" else "局域网文件共享",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            StatusBand(running = running, error = error)
            HorizontalDivider()
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                val wide = maxWidth >= 760.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        ConfigurationPane(
                            folderName = folderName,
                            treeUri = treeUri,
                            portText = portText,
                            onPortChange = { portText = it.filter(Char::isDigit).take(5) },
                            pinText = pinText,
                            onPinChange = { pinText = it.trim().take(12) },
                            running = running,
                            onChooseFolder = {
                                folderPicker.launch(treeUri?.toUri())
                            },
                            onGeneratePin = { pinText = AppPreferences.generatePin() },
                            onStart = ::startServer,
                            onStop = ::stopServer,
                            onOpenHotspotSettings = { openHotspotSettings(context) },
                            modifier = Modifier.weight(1f),
                        )
                        AccessPane(
                            urls = urls,
                            pin = pinText,
                            running = running,
                            onRefresh = ::refreshAddresses,
                            modifier = Modifier.width(330.dp),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        ConfigurationPane(
                            folderName = folderName,
                            treeUri = treeUri,
                            portText = portText,
                            onPortChange = { portText = it.filter(Char::isDigit).take(5) },
                            pinText = pinText,
                            onPinChange = { pinText = it.trim().take(12) },
                            running = running,
                            onChooseFolder = {
                                folderPicker.launch(treeUri?.toUri())
                            },
                            onGeneratePin = { pinText = AppPreferences.generatePin() },
                            onStart = ::startServer,
                            onStop = ::stopServer,
                            onOpenHotspotSettings = { openHotspotSettings(context) },
                        )
                        HorizontalDivider()
                        AccessPane(
                            urls = urls,
                            pin = pinText,
                            running = running,
                            onRefresh = ::refreshAddresses,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBand(running: Boolean, error: String?) {
    val container = when {
        error != null -> MaterialTheme.colorScheme.error.copy(alpha = 0.09f)
        running -> Color(0xFF16A34A).copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val dot = when {
        error != null -> MaterialTheme.colorScheme.error
        running -> Color(0xFF16A34A)
        else -> MaterialTheme.colorScheme.outline
    }
    val text = error ?: if (running) {
        "服务已启动，连接同一热点或 Wi-Fi 的设备现在可以访问"
    } else {
        "服务已停止"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(dot, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConfigurationPane(
    folderName: String?,
    treeUri: String?,
    portText: String,
    onPortChange: (String) -> Unit,
    pinText: String,
    onPinChange: (String) -> Unit,
    running: Boolean,
    onChooseFolder: () -> Unit,
    onGeneratePin: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle("共享范围")
        OutlinedButton(
            onClick = onChooseFolder,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Text(folderName ?: "选择共享文件夹")
        }
        if (treeUri != null) {
            Text(
                "网页只能访问这个文件夹及其子目录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("访问设置")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = portText,
                onValueChange = onPortChange,
                enabled = !running,
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.42f),
            )
            OutlinedTextField(
                value = pinText,
                onValueChange = onPinChange,
                enabled = !running,
                label = { Text("访问码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(0.58f),
                trailingIcon = {
                    TextButton(
                        onClick = onGeneratePin,
                        enabled = !running,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("换一个")
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onOpenHotspotSettings,
                modifier = Modifier.weight(1f),
            ) {
                Text("热点设置")
            }
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("停止服务")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("启动服务")
                }
            }
        }

        Text(
            "仅在可信热点或 Wi-Fi 中使用。网页使用 HTTP，访问码可防止误入，但不能替代无线网络密码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccessPane(
    urls: List<String>,
    pin: String,
    running: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val primaryUrl = urls.firstOrNull()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("浏览器访问")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh) {
                Text("刷新地址")
            }
        }

        if (running && primaryUrl != null) {
            val bitmap = remember(primaryUrl) { QrCode.create(primaryUrl) }
            Surface(
                modifier = Modifier
                    .size(224.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                color = Color.White,
                shape = MaterialTheme.shapes.small,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "文件站访问二维码",
                    modifier = Modifier.padding(10.dp),
                )
            }
            Text(
                "访问码  $pin",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (urls.isEmpty()) {
            Text(
                if (running) "尚未发现局域网 IPv4 地址，请打开热点或连接 Wi-Fi 后刷新"
                else "启动服务后显示访问地址和二维码",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            urls.forEach { url ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        url,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { copyText(context, url) }) {
                        Text("复制")
                    }
                }
            }
        }

        if (running) {
            OutlinedButton(
                onClick = {
                    val port = AppPreferences.port(context)
                    openUrl(context, "http://127.0.0.1:$port")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("在本机浏览器预览")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("热点文件站地址", text))
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

private fun openHotspotSettings(context: Context) {
    val tetherIntent = Intent("android.settings.TETHER_SETTINGS")
    runCatching {
        context.startActivity(tetherIntent)
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }
}
