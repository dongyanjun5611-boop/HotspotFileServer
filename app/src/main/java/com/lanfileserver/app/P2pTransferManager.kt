package com.lanfileserver.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class P2pTransferManager(
    context: Context,
    private val onStatus: (String, Boolean) -> Unit,
    private val onMeteredApproval: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val api = RemoteDownloadApi()
    private val executor = Executors.newSingleThreadExecutor()
    private val transferExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(128),
        java.util.concurrent.RejectedExecutionHandler { task, worker ->
            if (!worker.isShutdown) {
                worker.queue.put(task)
            }
        },
    )
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private val wakeSignal = Object()
    private var started = false
    private var peerFactory: PeerConnectionFactory? = null
    private var activeSession: DevicePeerSession? = null
    private var approvalNotifiedFor: String? = null

    fun start() {
        if (started) return
        started = true
        stopRequested.set(false)
        initializeWebRtc()
        executor.execute(::runLoop)
    }

    fun wake() {
        synchronized(wakeSignal) { wakeSignal.notifyAll() }
    }

    fun stop() {
        stopRequested.set(true)
        wake()
        activeSession?.close("P2P 服务已停止", failed = false)
        activeSession = null
        executor.shutdownNow()
        transferExecutor.shutdownNow()
        downloadExecutor.shutdownNow()
        runCatching { peerFactory?.dispose() }
        peerFactory = null
    }

    private fun initializeWebRtc() {
        if (webRtcInitialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(appContext)
                    .createInitializationOptions(),
            )
        }
        peerFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private fun runLoop() {
        while (!stopRequested.get()) {
            try {
                val credentials = AppPreferences.remoteDevice(appContext)
                val treeUri = AppPreferences.treeUri(appContext)
                if (credentials == null || treeUri.isNullOrBlank()) {
                    waitForNextPoll(IDLE_POLL_MS)
                    continue
                }

                val current = activeSession
                if (current != null) {
                    if (current.isClosed()) {
                        runCatching { api.closeP2pSession(credentials, current.sessionId) }
                        AppPreferences.clearApprovedP2pSession(
                            appContext,
                            current.sessionId,
                        )
                        current.closeReason()?.let {
                            onStatus(it, current.failed())
                        }
                        activeSession = null
                        approvalNotifiedFor = null
                    } else {
                        try {
                            current.tick()
                        } catch (error: RemoteApiException) {
                            if (error.statusCode == 404) {
                                current.close("P2P 会话已过期", failed = true)
                            } else {
                                throw error
                            }
                        }
                    }
                    waitForNextPoll(if (current.isConnected()) ACTIVE_POLL_MS else SIGNAL_POLL_MS)
                    continue
                }

                val pending = api.listP2pSessions(credentials).firstOrNull()
                if (pending == null) {
                    approvalNotifiedFor = null
                    waitForNextPoll(IDLE_POLL_MS)
                    continue
                }

                if (!allowSessionOnCurrentNetwork(credentials, pending.id)) {
                    waitForNextPoll(SIGNAL_POLL_MS)
                    continue
                }

                approvalNotifiedFor = null
                activeSession = DevicePeerSession(
                    context = appContext,
                    sessionId = pending.id,
                    credentials = credentials,
                    treeUri = treeUri,
                    factory = requireNotNull(peerFactory),
                    api = api,
                    transferExecutor = transferExecutor,
                    downloadExecutor = downloadExecutor,
                    onStatus = onStatus,
                )
                onStatus("正在建立 P2P 直连…", false)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (error: Throwable) {
                if (error is RemoteApiException && error.statusCode == 401) {
                    break
                }
                waitForNextPoll(ERROR_POLL_MS)
            }
        }
    }

    private fun allowSessionOnCurrentNetwork(
        credentials: RemoteDeviceCredentials,
        sessionId: String,
    ): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        if (!manager.isActiveNetworkMetered) return true

        return when (AppPreferences.remoteNetworkPolicy(appContext)) {
            RemoteNetworkPolicy.ALWAYS -> true
            RemoteNetworkPolicy.UNMETERED_ONLY -> {
                api.sendP2pSignal(
                    credentials,
                    sessionId,
                    "error",
                    JSONObject()
                        .put("message", "当前是计费网络，设备仅允许非计费网络直传")
                        .toString(),
                )
                api.closeP2pSession(credentials, sessionId)
                false
            }

            RemoteNetworkPolicy.ASK -> {
                if (AppPreferences.approvedP2pSession(appContext) == sessionId) {
                    true
                } else {
                    if (approvalNotifiedFor != sessionId) {
                        approvalNotifiedFor = sessionId
                        api.sendP2pSignal(
                            credentials,
                            sessionId,
                            "status",
                            JSONObject()
                                .put("message", "等待手机确认是否使用计费网络")
                                .toString(),
                        )
                        onMeteredApproval(sessionId)
                    }
                    false
                }
            }
        }
    }

    private fun waitForNextPoll(milliseconds: Long) {
        if (stopRequested.get()) return
        synchronized(wakeSignal) {
            if (!stopRequested.get()) wakeSignal.wait(milliseconds)
        }
    }

    companion object {
        private const val IDLE_POLL_MS = 3_000L
        private const val SIGNAL_POLL_MS = 600L
        private const val ACTIVE_POLL_MS = 1_000L
        private const val ERROR_POLL_MS = 5_000L
        private val webRtcInitialized = AtomicBoolean(false)
    }
}

private class DevicePeerSession(
    private val context: Context,
    val sessionId: String,
    private val credentials: RemoteDeviceCredentials,
    treeUri: String,
    private val factory: PeerConnectionFactory,
    private val api: RemoteDownloadApi,
    private val transferExecutor: ThreadPoolExecutor,
    private val downloadExecutor: java.util.concurrent.ExecutorService,
    private val onStatus: (String, Boolean) -> Unit,
) {
    private val storage = StorageTree(context, treeUri.toUri())
    private val outboundSignals = ConcurrentLinkedQueue<OutboundSignal>()
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val candidateLock = Any()
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val downloadCanceled = AtomicBoolean(false)
    private val transferLock = Any()
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var lastSignalSequence = 0L
    private var remoteDescriptionSet = false
    private var upload: UploadState? = null
    private var downloadId: String? = null
    @Volatile
    private var reason: String? = null
    @Volatile
    private var failure = false

    init {
        val iceServers = listOf(
            PeerConnection.IceServer
                .builder("stun:stun.cloudflare.com:3478")
                .createIceServer(),
            PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer(),
        )
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        peerConnection = factory.createPeerConnection(
            configuration,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(
                    state: PeerConnection.IceConnectionState?,
                ) = Unit

                override fun onConnectionChange(
                    newState: PeerConnection.PeerConnectionState?,
                ) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            connected.set(true)
                            onStatus("P2P 已直连，文件不经过服务器", false)
                        }

                        PeerConnection.PeerConnectionState.FAILED -> {
                            close("当前网络无法建立 P2P 直连", failed = true)
                        }

                        PeerConnection.PeerConnectionState.CLOSED -> {
                            close("P2P 连接已结束", failed = false)
                        }

                        else -> Unit
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(
                    state: PeerConnection.IceGatheringState?,
                ) = Unit

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate == null) return
                    outboundSignals.add(
                        OutboundSignal(
                            type = "candidate",
                            payload = JSONObject()
                                .put("candidate", candidate.sdp)
                                .put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                .toString(),
                        ),
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(channel: DataChannel?) {
                    if (channel != null) registerDataChannel(channel)
                }

                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    mediaStreams: Array<out MediaStream>?,
                ) = Unit
            },
        ) ?: throw IOException("无法创建 WebRTC 连接")
    }

    fun tick() {
        if (closed.get() || connected.get()) return
        flushOutboundSignals()
        val signals = api.pollP2pSignals(
            credentials,
            sessionId,
            lastSignalSequence,
        )
        signals.forEach { signal ->
            lastSignalSequence = max(lastSignalSequence, signal.sequence)
            when (signal.type) {
                "offer" -> acceptOffer(signal.payload)
                "candidate" -> acceptCandidate(signal.payload)
                "cancel" -> close("网页已取消直连", failed = false)
            }
        }
        flushOutboundSignals()
    }

    fun isClosed(): Boolean = closed.get()

    fun isConnected(): Boolean = connected.get()

    fun closeReason(): String? = reason

    fun failed(): Boolean = failure

    fun close(message: String, failed: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        reason = message
        failure = failed
        connected.set(false)
        downloadCanceled.set(true)
        synchronized(transferLock) {
            upload?.file?.abort()
            upload = null
            downloadId = null
        }
        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.close() }
        runCatching { dataChannel?.dispose() }
        dataChannel = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
    }

    private fun acceptOffer(payload: String) {
        val description = JSONObject(payload)
        val sdp = description.getString("sdp")
        val peer = peerConnection ?: return
        peer.setRemoteDescription(
            CallbackSdpObserver(
                onSetSuccess = {
                    remoteDescriptionSet = true
                    synchronized(candidateLock) {
                        pendingCandidates.forEach(peer::addIceCandidate)
                        pendingCandidates.clear()
                    }
                    createAnswer()
                },
                onFailure = {
                    queueError("无法接受网页端连接信息：$it")
                    close("P2P 握手失败", failed = true)
                },
            ),
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun createAnswer() {
        val peer = peerConnection ?: return
        peer.createAnswer(
            CallbackSdpObserver(
                onCreateSuccess = { answer ->
                    peer.setLocalDescription(
                        CallbackSdpObserver(
                            onSetSuccess = {
                                outboundSignals.add(
                                    OutboundSignal(
                                        type = "answer",
                                        payload = JSONObject()
                                            .put("type", "answer")
                                            .put("sdp", answer.description)
                                            .toString(),
                                    ),
                                )
                            },
                            onFailure = {
                                queueError("无法保存设备端连接信息：$it")
                            },
                        ),
                        answer,
                    )
                },
                onFailure = { queueError("无法创建设备端应答：$it") },
            ),
            MediaConstraints(),
        )
    }

    private fun acceptCandidate(payload: String) {
        val item = JSONObject(payload)
        val candidate = IceCandidate(
            item.optString("sdpMid").takeIf { it.isNotBlank() },
            item.optInt("sdpMLineIndex", 0),
            item.getString("candidate"),
        )
        val peer = peerConnection ?: return
        if (remoteDescriptionSet) {
            peer.addIceCandidate(candidate)
        } else {
            synchronized(candidateLock) { pendingCandidates.add(candidate) }
        }
    }

    private fun flushOutboundSignals() {
        while (true) {
            val signal = outboundSignals.poll() ?: break
            api.sendP2pSignal(
                credentials,
                sessionId,
                signal.type,
                signal.payload,
            )
        }
    }

    private fun queueError(message: String) {
        outboundSignals.add(
            OutboundSignal(
                type = "error",
                payload = JSONObject().put("message", message.take(240)).toString(),
            ),
        )
    }

    private fun registerDataChannel(channel: DataChannel) {
        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.dispose() }
        dataChannel = channel
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    when (channel.state()) {
                        DataChannel.State.OPEN -> {
                            connected.set(true)
                            onStatus("P2P 已直连，文件不经过服务器", false)
                        }

                        DataChannel.State.CLOSED -> {
                            close("P2P 数据通道已关闭", failed = false)
                        }

                        else -> Unit
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (buffer == null || closed.get()) return
                    val source = buffer.data
                    val bytes = ByteArray(source.remaining())
                    source.get(bytes)
                    if (buffer.binary) {
                        transferExecutor.execute { handleUploadBytes(bytes) }
                    } else {
                        val text = String(bytes, StandardCharsets.UTF_8)
                        val command = runCatching { JSONObject(text) }.getOrNull() ?: return
                        when (command.optString("type")) {
                            "cancel" -> {
                                downloadCanceled.set(true)
                                transferExecutor.execute { cancelTransfer(command) }
                            }

                            "disconnect" -> {
                                sendText(JSONObject().put("type", "disconnected"))
                                close("网页已断开 P2P 连接", failed = false)
                            }

                            else -> transferExecutor.execute { handleCommand(command) }
                        }
                    }
                }
            },
        )
    }

    private fun handleCommand(command: JSONObject) {
        try {
            when (command.optString("type")) {
                "list" -> listFiles(command)
                "uploadStart" -> beginUpload(command)
                "uploadFinish" -> finishUpload(command)
                "downloadStart" -> beginDownload(command)
            }
        } catch (error: Throwable) {
            sendTransferError(
                command.optString("transferId"),
                friendlyMessage(error),
            )
        }
    }

    private fun listFiles(command: JSONObject) {
        val path = SafePath.normalize(command.optString("path"))
        val values = JSONArray()
        storage.list(path).take(MAX_LIST_ENTRIES).forEach { entry ->
            values.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("path", entry.path)
                    .put("directory", entry.directory)
                    .put("size", entry.size)
                    .put("modifiedAt", entry.modifiedAt)
                    .put("mimeType", entry.mimeType),
            )
        }
        sendText(
            JSONObject()
                .put("type", "listResult")
                .put("requestId", command.optString("requestId"))
                .put("path", path)
                .put("entries", values),
        )
    }

    private fun beginUpload(command: JSONObject) {
        val transferId = command.getString("transferId")
        val size = command.getLong("size")
        require(size >= 0L) { "文件大小无效" }
        synchronized(transferLock) {
            check(upload == null && downloadId == null) { "已有文件正在传输" }
            val file = storage.openWritableFile(
                parentPath = command.optString("path"),
                requestedName = command.getString("name"),
                mimeType = command.optString("mimeType"),
            )
            upload = UploadState(
                id = transferId,
                expectedSize = size,
                file = file,
            )
        }
        sendText(
            JSONObject()
                .put("type", "uploadReady")
                .put("transferId", transferId),
        )
        onStatus("P2P 正在接收：${command.getString("name")}", false)
    }

    private fun handleUploadBytes(bytes: ByteArray) {
        val current = synchronized(transferLock) { upload }
        if (current == null) {
            sendTransferError("", "没有等待接收的上传任务")
            return
        }
        try {
            if (current.received + bytes.size > current.expectedSize) {
                throw IOException("接收数据超过声明的文件大小")
            }
            current.file.write(bytes)
            current.received += bytes.size
            val now = System.nanoTime()
            if (current.received == current.expectedSize
                || current.received - current.lastReportedBytes >= PROGRESS_BYTES
                || now - current.lastReportedAt >= PROGRESS_NANOS
            ) {
                sendText(
                    JSONObject()
                        .put("type", "progress")
                        .put("transferId", current.id)
                        .put("bytes", current.received),
                )
                current.lastReportedBytes = current.received
                current.lastReportedAt = now
            }
        } catch (error: Throwable) {
            synchronized(transferLock) {
                current.file.abort()
                if (upload === current) upload = null
            }
            sendTransferError(current.id, friendlyMessage(error))
        }
    }

    private fun finishUpload(command: JSONObject) {
        val transferId = command.getString("transferId")
        val current = synchronized(transferLock) { upload }
            ?: throw IOException("上传任务不存在")
        check(current.id == transferId) { "上传任务不匹配" }
        if (current.received != current.expectedSize) {
            synchronized(transferLock) {
                current.file.abort()
                upload = null
            }
            throw IOException(
                "文件接收不完整：${current.received}/${current.expectedSize}",
            )
        }
        val result = current.file.finish(current.received)
        synchronized(transferLock) { upload = null }
        sendText(
            JSONObject()
                .put("type", "complete")
                .put("transferId", transferId)
                .put("name", result.name)
                .put("size", result.size),
        )
        onStatus("P2P 接收完成：${result.name}", false)
    }

    private fun beginDownload(command: JSONObject) {
        val transferId = command.getString("transferId")
        synchronized(transferLock) {
            check(upload == null && downloadId == null) { "已有文件正在传输" }
            downloadId = transferId
            downloadCanceled.set(false)
        }
        val path = command.getString("path")
        downloadExecutor.execute {
            try {
                storage.open(path).let { opened ->
                    opened.input.use { input ->
                        sendText(
                            JSONObject()
                                .put("type", "downloadMeta")
                                .put("transferId", transferId)
                                .put("name", opened.name)
                                .put("size", opened.length)
                                .put("mimeType", opened.mimeType),
                        )
                        onStatus("P2P 正在发送：${opened.name}", false)
                        val buffer = ByteArray(FILE_CHUNK_SIZE)
                        var sent = 0L
                        var lastReported = 0L
                        var lastReportedAt = System.nanoTime()
                        while (true) {
                            if (downloadCanceled.get() || closed.get()) {
                                throw TransferCanceledException()
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            sendBinary(buffer.copyOf(count))
                            sent += count
                            val now = System.nanoTime()
                            if (sent - lastReported >= PROGRESS_BYTES
                                || now - lastReportedAt >= PROGRESS_NANOS
                            ) {
                                sendText(
                                    JSONObject()
                                        .put("type", "progress")
                                        .put("transferId", transferId)
                                        .put("bytes", sent),
                                )
                                lastReported = sent
                                lastReportedAt = now
                            }
                        }
                        if (opened.length > 0L && sent != opened.length) {
                            throw IOException("文件读取不完整：$sent/${opened.length}")
                        }
                        sendText(
                            JSONObject()
                                .put("type", "downloadComplete")
                                .put("transferId", transferId)
                                .put("name", opened.name)
                                .put("size", sent),
                        )
                        onStatus("P2P 发送完成：${opened.name}", false)
                    }
                }
            } catch (_: TransferCanceledException) {
                sendTransferError(transferId, "传输已取消")
            } catch (error: Throwable) {
                sendTransferError(transferId, friendlyMessage(error))
            } finally {
                synchronized(transferLock) {
                    if (downloadId == transferId) downloadId = null
                }
                downloadCanceled.set(false)
            }
        }
    }

    private fun cancelTransfer(command: JSONObject) {
        val transferId = command.optString("transferId")
        synchronized(transferLock) {
            upload?.takeIf { transferId.isBlank() || it.id == transferId }?.let {
                it.file.abort()
                upload = null
            }
        }
    }

    private fun sendBinary(bytes: ByteArray) {
        val channel = dataChannel ?: throw IOException("P2P 数据通道已关闭")
        while (channel.bufferedAmount() > MAX_BUFFERED_BYTES) {
            if (downloadCanceled.get() || closed.get()) throw TransferCanceledException()
            Thread.sleep(15L)
        }
        if (!channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))) {
            throw IOException("P2P 数据发送失败")
        }
    }

    private fun sendText(value: JSONObject) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
        if (!channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))) {
            throw IOException("P2P 控制消息发送失败")
        }
    }

    private fun sendTransferError(transferId: String, message: String) {
        runCatching {
            sendText(
                JSONObject()
                    .put("type", "error")
                    .put("transferId", transferId)
                    .put("message", message.take(240)),
            )
        }
        onStatus("P2P 传输失败：${message.take(160)}", true)
    }

    private fun friendlyMessage(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("ENOSPC", ignoreCase = true) -> "存储空间不足"
            text.isNotBlank() -> text.take(240)
            else -> "P2P 文件传输失败"
        }
    }

    companion object {
        private const val FILE_CHUNK_SIZE = 32 * 1024
        private const val MAX_LIST_ENTRIES = 300
        private const val MAX_BUFFERED_BYTES = 4L * 1024L * 1024L
        private const val PROGRESS_BYTES = 1024L * 1024L
        private const val PROGRESS_NANOS = 750_000_000L
    }
}

private data class OutboundSignal(
    val type: String,
    val payload: String,
)

private data class UploadState(
    val id: String,
    val expectedSize: Long,
    val file: StorageTree.WritableFile,
    var received: Long = 0L,
    var lastReportedBytes: Long = 0L,
    var lastReportedAt: Long = System.nanoTime(),
)

private class TransferCanceledException : IOException("传输已取消")

private class CallbackSdpObserver(
    private val onCreateSuccess: (SessionDescription) -> Unit = {},
    private val onSetSuccess: () -> Unit = {},
    private val onFailure: (String) -> Unit = {},
) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) {
        if (description == null) {
            onFailure("连接信息为空")
        } else {
            onCreateSuccess.invoke(description)
        }
    }

    override fun onSetSuccess() = onSetSuccess.invoke()

    override fun onCreateFailure(message: String?) =
        onFailure(message ?: "创建连接信息失败")

    override fun onSetFailure(message: String?) =
        onFailure(message ?: "设置连接信息失败")
}
