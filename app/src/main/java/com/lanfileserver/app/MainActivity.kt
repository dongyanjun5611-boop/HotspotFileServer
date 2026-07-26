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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Wifi
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var remoteRunning by remember { mutableStateOf(RemoteDownloadService.running) }
    var remoteStatus by remember { mutableStateOf(RemoteDownloadService.lastStatus) }
    var remoteError by remember { mutableStateOf(RemoteDownloadService.lastError) }
    var remotePolicy by remember {
        mutableStateOf(AppPreferences.remoteNetworkPolicy(context))
    }
    var remoteDevice by remember { mutableStateOf(AppPreferences.remoteDevice(context)) }
    var selectedTab by remember { mutableStateOf(AppTab.FILES) }

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
    val networkNamePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(selectedTab, remoteDevice?.id) {
        if (selectedTab == AppTab.REMOTE &&
            remoteDevice != null &&
            !DeviceLanStatusReader.hasNetworkNamePermission(context)
        ) {
            networkNamePermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    FileServerService.ACTION_STATE_CHANGED -> {
                        running = intent.getBooleanExtra(FileServerService.EXTRA_RUNNING, false)
                        error = intent.getStringExtra(FileServerService.EXTRA_ERROR)
                        urls = NetworkAddresses.urls(
                            portText.toIntOrNull() ?: AppPreferences.DEFAULT_PORT,
                        )
                    }

                    RemoteDownloadService.ACTION_STATE_CHANGED -> {
                        remoteRunning = intent.getBooleanExtra(
                            RemoteDownloadService.EXTRA_RUNNING,
                            false,
                        )
                        remoteStatus = intent.getStringExtra(
                            RemoteDownloadService.EXTRA_STATUS,
                        ).orEmpty()
                        remoteError = intent.getBooleanExtra(
                            RemoteDownloadService.EXTRA_ERROR,
                            false,
                        )
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(FileServerService.ACTION_STATE_CHANGED)
            addAction(RemoteDownloadService.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(portText, running) {
        urls = NetworkAddresses.urls(portText.toIntOrNull() ?: AppPreferences.DEFAULT_PORT)
    }

    LaunchedEffect(Unit) {
        if (AppPreferences.remoteEnabled(context) &&
            AppPreferences.remoteDevice(context) != null &&
            !RemoteDownloadService.running
        ) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteDownloadService::class.java)
                        .setAction(RemoteDownloadService.ACTION_START),
                )
            }
        }
        val updateDue = System.currentTimeMillis() - AppPreferences.lastUpdateCheck(context) >=
            24L * 60L * 60L * 1_000L
        if (updateDue) {
            runCatching {
                withContext(Dispatchers.IO) { AppUpdateChecker.check() }
            }.onSuccess { available ->
                AppPreferences.markUpdateChecked(context)
                if (available != null) {
                    AppUpdateChecker.notifyAvailable(context, available)
                }
            }
        }
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

    fun startRemoteDownloads() {
        if (treeUri.isNullOrBlank()) {
            remoteStatus = "请先选择一个共享文件夹"
            remoteError = true
            return
        }
        if (remoteDevice == null) {
            remoteStatus = "请先使用后台配对码绑定这台设备"
            remoteError = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        remoteError = false
        AppPreferences.saveRemoteEnabled(context, true)
        ContextCompat.startForegroundService(
            context,
            Intent(context, RemoteDownloadService::class.java)
                .setAction(RemoteDownloadService.ACTION_START),
        )
    }

    fun stopRemoteDownloads() {
        AppPreferences.saveRemoteEnabled(context, false)
        context.startService(
            Intent(context, RemoteDownloadService::class.java)
                .setAction(RemoteDownloadService.ACTION_STOP),
        )
        remoteRunning = false
        remoteStatus = "远程下载未启用"
        remoteError = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("热点文件站", fontWeight = FontWeight.SemiBold)
                        Text(
                            when (selectedTab) {
                                AppTab.FILES -> folderName ?: "本地共享目录"
                                AppTab.LAN -> if (running) {
                                    "局域网服务运行中"
                                } else {
                                    "局域网文件共享"
                                }
                                AppTab.REMOTE -> if (remoteRunning) {
                                    remoteDevice?.name ?: "远程下载运行中"
                                } else {
                                    "远程下载与应用更新"
                                }
                            },
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
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                when (tab) {
                                    AppTab.FILES -> Icons.Default.Folder
                                    AppTab.LAN -> Icons.Default.Wifi
                                    AppTab.REMOTE -> Icons.Default.CloudDownload
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        when (selectedTab) {
            AppTab.FILES -> LocalFileBrowser(
                treeUri = treeUri,
                onChooseFolder = { folderPicker.launch(treeUri?.toUri()) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )

            AppTab.LAN -> Column(
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
                                onChooseFolder = { folderPicker.launch(treeUri?.toUri()) },
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
                                onChooseFolder = { folderPicker.launch(treeUri?.toUri()) },
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

            AppTab.REMOTE -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                RemoteDownloadPane(
                    running = remoteRunning,
                    status = remoteStatus,
                    error = remoteError,
                    policy = remotePolicy,
                    device = remoteDevice,
                    onDeviceChange = { credentials ->
                        remoteDevice = credentials
                    },
                    onPolicyChange = { policy ->
                        remotePolicy = policy
                        AppPreferences.saveRemoteNetworkPolicy(context, policy)
                    },
                    onStart = ::startRemoteDownloads,
                    onStop = ::stopRemoteDownloads,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                )
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
private fun RemoteDownloadPane(
    running: Boolean,
    status: String,
    error: Boolean,
    policy: RemoteNetworkPolicy,
    device: RemoteDeviceCredentials?,
    onDeviceChange: (RemoteDeviceCredentials?) -> Unit,
    onPolicyChange: (RemoteNetworkPolicy) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dashboardUrl = "${RemoteDownloadApi.BASE_URL}/admin/offline-download"
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionTitle("远程离线下载")
                Text(
                    "手机直接从链接下载到当前共享文件夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        when {
                            error -> MaterialTheme.colorScheme.error
                            running -> Color(0xFF16A34A)
                            else -> MaterialTheme.colorScheme.outline
                        },
                        CircleShape,
                    ),
            )
        }

        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        DevicePairingSection(
            device = device,
            running = running,
            onDeviceChange = onDeviceChange,
            onStopRemote = onStop,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            "计费网络",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        RemotePolicyOption(
            selected = policy == RemoteNetworkPolicy.UNMETERED_ONLY,
            text = "仅使用非计费网络",
            onClick = { onPolicyChange(RemoteNetworkPolicy.UNMETERED_ONLY) },
        )
        RemotePolicyOption(
            selected = policy == RemoteNetworkPolicy.ASK,
            text = "使用前询问（推荐）",
            onClick = { onPolicyChange(RemoteNetworkPolicy.ASK) },
        )
        RemotePolicyOption(
            selected = policy == RemoteNetworkPolicy.ALWAYS,
            text = "始终允许",
            onClick = { onPolicyChange(RemoteNetworkPolicy.ALWAYS) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { openUrl(context, dashboardUrl) },
                modifier = Modifier.weight(1f),
            ) {
                Text("打开控制页")
            }
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("停止远程下载")
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = device != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("启用远程下载")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                dashboardUrl,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { copyText(context, dashboardUrl) }) {
                Text("复制")
            }
        }

        AppUpdateSection()
    }
}

@Composable
private fun RemotePolicyOption(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
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

private enum class AppTab(val label: String) {
    FILES("文件"),
    LAN("局域网"),
    REMOTE("远程"),
}
