package com.feiji.bulletnotes.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 同 Wi-Fi 前台自动双向同步管理器（纯 Android / JDK 标准库实现，零第三方依赖）：
 * - 仅在前台激活且已开启「局域网自动同步」并设置备份加密密码时运行，退到后台立即释放所有端口与锁。
 * - 直接复用「备份加密密码」作为设备身份与端到端加密口令：
 *   1. UDP 广播仅携带密码派生的 groupHash 与数据状态摘要 stateHash，不暴露密码与任何明文内容；
 *   2. stateHash 一致时直接跳过连接，零流量打扰；
 *   3. stateHash 不一致时由 deviceId 较大的一端单向发起加密 TCP 连接（PBKDF2 + AES-256-GCM + 随机 Nonce 防重放）；
 *   4. 单次往返完成「笔记 + 回收站 + 删除墓碑 + WebDAV/同步设置」的确定性双向合并，确保两端收敛到完全一致状态。
 */
object WifiAutoSyncManager {
    private const val TAG = "FeiJiWifiSync"

    private const val DISCOVERY_PORT = 45678
    private const val PROTOCOL_VERSION = 1
    private const val BEACON_INTERVAL_MS = 2_500L
    private const val PEER_SYNC_COOLDOWN_MS = 3_000L
    private const val UNICAST_REPLY_COOLDOWN_MS = 2_000L
    private const val TCP_CONNECT_TIMEOUT_MS = 4_000
    private const val TCP_READ_TIMEOUT_MS = 15_000
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    private const val GROUP_HMAC_DOMAIN = "feiji-wifi-sync-group-v1"

    private val gson = Gson()
    private val secureRandom = SecureRandom()
    private val lifecycleLock = Any()
    private val syncMutex = Mutex()

    private val running = AtomicBoolean(false)
    @Volatile private var activeScope: CoroutineScope? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var tcpServerSocket: ServerSocket? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var appContext: Context? = null
    @Volatile private var activeNoteDao: NoteDao? = null
    @Volatile private var activeWithDbLock: (suspend (suspend () -> Unit) -> Unit)? = null
    @Volatile private var activeIsEditingProvider: (() -> Boolean)? = null
    @Volatile private var activeOnSyncApplied: ((notesChanged: Boolean, settingsChanged: Boolean) -> Unit)? = null

    @Volatile private var activeDeviceId: String = ""
    @Volatile private var activeEncryptionPassword: String = ""
    @Volatile private var activeGroupHash: String = ""
    @Volatile private var activeTcpPort: Int = 0
    @Volatile private var cachedStateHash: String = ""
    private val stateDirty = AtomicBoolean(true)

    private val peerCooldowns = ConcurrentHashMap<String, Pair<Long, String>>()
    private val unicastReplyTimes = ConcurrentHashMap<String, Long>()

    private data class DiscoveryBeacon(
        val v: Int = PROTOCOL_VERSION,
        val dev: String = "",
        val grp: String = "",
        val port: Int = 0,
        val hash: String = ""
    )

    private data class WifiSyncPacket(
        val v: Int = PROTOCOL_VERSION,
        val deviceId: String = "",
        val nonce: String = "",
        val deferred: Boolean = false,
        val notes: List<NoteEntity>? = null,
        val tombstones: List<DeletedNoteTombstone>? = null,
        val settings: SyncMergeEngine.SyncableSettings? = null
    )

    fun start(
        context: Context,
        noteDao: NoteDao,
        withDbLock: suspend (suspend () -> Unit) -> Unit,
        isEditingProvider: () -> Boolean,
        onSyncApplied: (notesChanged: Boolean, settingsChanged: Boolean) -> Unit
    ) {
        val app = context.applicationContext
        val config = WebDavBackupManager.getConfig(app)
        if (!config.wifiAutoSyncEnabled || config.encryptionPassword.isEmpty()) {
            stop()
            return
        }

        val deviceId = WebDavBackupManager.getSyncDeviceId(app)
        val groupHash = computeGroupHash(config.encryptionPassword)

        synchronized(lifecycleLock) {
            if (running.get() &&
                activeEncryptionPassword == config.encryptionPassword &&
                activeDeviceId == deviceId
            ) {
                activeNoteDao = noteDao
                activeWithDbLock = withDbLock
                activeIsEditingProvider = isEditingProvider
                activeOnSyncApplied = onSyncApplied
                notifyLocalDataChanged()
                return
            }

            stopLocked()

            appContext = app
            activeNoteDao = noteDao
            activeWithDbLock = withDbLock
            activeIsEditingProvider = isEditingProvider
            activeOnSyncApplied = onSyncApplied
            activeDeviceId = deviceId
            activeEncryptionPassword = config.encryptionPassword
            activeGroupHash = groupHash
            stateDirty.set(true)
            peerCooldowns.clear()
            unicastReplyTimes.clear()

            val tcpSock = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
            }.getOrNull()

            val udpSock = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2_000
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
            }.getOrNull()

            if (tcpSock == null || udpSock == null) {
                Log.w(TAG, "Failed to bind Wi-Fi auto-sync sockets (tcp=${tcpSock != null}, udp=${udpSock != null})")
                runCatching { tcpSock?.close() }
                runCatching { udpSock?.close() }
                return
            }

            tcpServerSocket = tcpSock
            udpSocket = udpSock
            activeTcpPort = tcpSock.localPort
            acquireMulticastLock(app)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            activeScope = scope
            running.set(true)

            scope.launch { runTcpAcceptLoop(tcpSock) }
            scope.launch { runUdpReceiveLoop(udpSock) }
            scope.launch { runBeaconBroadcastLoop(udpSock) }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    /** 本地笔记或设置发生变化、或刚退出编辑时调用，立即刷新摘要并发送广播加速同步。 */
    fun notifyLocalDataChanged() {
        stateDirty.set(true)
        peerCooldowns.clear()
        val scope = activeScope ?: return
        val sock = udpSocket ?: return
        if (!running.get() || sock.isClosed) return
        scope.launch {
            refreshStateHashIfNeeded()
            sendBroadcastBeacon(sock)
        }
    }

    private fun stopLocked() {
        running.set(false)
        runCatching { activeScope?.cancel() }
        activeScope = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { tcpServerSocket?.close() }
        tcpServerSocket = null
        activeTcpPort = 0
        releaseMulticastLock()
        peerCooldowns.clear()
        unicastReplyTimes.clear()
    }

    private fun acquireMulticastLock(context: Context) {
        runCatching {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("feiji_wifi_sync_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }
        multicastLock = null
    }

    // ==================== UDP 发现循环 ====================

    private suspend fun runBeaconBroadcastLoop(sock: DatagramSocket) {
        // 启动后先快速广播两次，提高首秒发现率
        refreshStateHashIfNeeded()
        sendBroadcastBeacon(sock)
        delay(600L)
        while (running.get() && !sock.isClosed && activeScope?.isActive == true) {
            refreshStateHashIfNeeded()
            sendBroadcastBeacon(sock)
            delay(BEACON_INTERVAL_MS)
        }
    }

    private suspend fun runUdpReceiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(1024)
        while (running.get() && !sock.isClosed && activeScope?.isActive == true) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val senderAddr = packet.address ?: continue
                if (!isPrivateLanIpv4(senderAddr)) continue

                val rawText = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val beacon = runCatching { gson.fromJson(rawText, DiscoveryBeacon::class.java) }.getOrNull()
                    ?: continue
                handleIncomingBeacon(sock, senderAddr, beacon)
            } catch (_: SocketTimeoutException) {
                // 正常超时轮询，检查 running 标记
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                if (running.get()) {
                    Log.d(TAG, "UDP receive error: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleIncomingBeacon(
        sock: DatagramSocket,
        senderAddr: InetAddress,
        beacon: DiscoveryBeacon
    ) {
        if (beacon.v != PROTOCOL_VERSION) return
        if (beacon.dev.isBlank() || beacon.dev == activeDeviceId) return
        if (beacon.grp != activeGroupHash) return
        if (beacon.port !in 1..65535 || beacon.hash.isBlank()) return

        val localHash = refreshStateHashIfNeeded()
        if (localHash.isEmpty() || beacon.hash == localHash) {
            return
        }

        // 若本机正在编辑正文，先不主动打断，等退出编辑后通过 notifyLocalDataChanged 立即触发
        if (activeIsEditingProvider?.invoke() == true) {
            return
        }

        val senderHost = senderAddr.hostAddress ?: return
        if (activeDeviceId > beacon.dev) {
            val now = System.currentTimeMillis()
            val prev = peerCooldowns[beacon.dev]
            if (prev != null && prev.second == beacon.hash && now - prev.first < PEER_SYNC_COOLDOWN_MS) {
                return
            }
            peerCooldowns[beacon.dev] = now to beacon.hash
            activeScope?.launch {
                initiateTcpSync(senderHost, beacon.port, beacon.dev)
            }
        } else {
            // 从属端收到主控端差异广播时，立即单播回发自身摘要，帮助主控端在 1 秒内发现并连接
            val now = System.currentTimeMillis()
            val lastReply = unicastReplyTimes[senderHost] ?: 0L
            if (now - lastReply >= UNICAST_REPLY_COOLDOWN_MS) {
                unicastReplyTimes[senderHost] = now
                sendBeaconToAddress(sock, senderAddr)
            }
        }
    }

    private fun sendBroadcastBeacon(sock: DatagramSocket) {
        if (!running.get() || sock.isClosed) return
        val targets = collectLanBroadcastAddresses()
        if (targets.isEmpty()) return
        val payload = buildBeaconBytes() ?: return
        for (target in targets) {
            runCatching {
                sock.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
            }
        }
    }

    private fun sendBeaconToAddress(sock: DatagramSocket, target: InetAddress) {
        if (!running.get() || sock.isClosed) return
        val payload = buildBeaconBytes() ?: return
        runCatching {
            sock.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
        }
    }

    private fun buildBeaconBytes(): ByteArray? {
        val port = activeTcpPort
        val hash = cachedStateHash
        if (port !in 1..65535 || hash.isEmpty() || activeGroupHash.isEmpty()) return null
        val beacon = DiscoveryBeacon(
            v = PROTOCOL_VERSION,
            dev = activeDeviceId,
            grp = activeGroupHash,
            port = port,
            hash = hash
        )
        return gson.toJson(beacon).toByteArray(Charsets.UTF_8)
    }

    private fun collectLanBroadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        var hasLanInterface = false
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") ||
                    name.startsWith("ccmni") ||
                    name.startsWith("pdp") ||
                    name.startsWith("tun") ||
                    name.startsWith("ppp")
                ) {
                    continue
                }
                for (ifaceAddr in nif.interfaceAddresses) {
                    val addr = ifaceAddr.address
                    if (addr is Inet4Address && isPrivateLanIpv4(addr)) {
                        hasLanInterface = true
                        ifaceAddr.broadcast?.let { bcast ->
                            if (bcast is Inet4Address && !bcast.isLoopbackAddress) {
                                result.add(bcast)
                            }
                        }
                    }
                }
            }
            if (hasLanInterface) {
                runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to enumerate LAN broadcast addresses: ${e.message}")
        }
        return result.toList()
    }

    // ==================== 加密 TCP 双向交换 ====================

    private suspend fun runTcpAcceptLoop(serverSock: ServerSocket) {
        while (running.get() && !serverSock.isClosed && activeScope?.isActive == true) {
            try {
                val client = serverSock.accept()
                activeScope?.launch {
                    handleIncomingTcpClient(client)
                }
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                if (running.get()) {
                    Log.d(TAG, "TCP accept error: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleIncomingTcpClient(socket: Socket) {
        socket.use { sock ->
            try {
                val remoteAddr = sock.inetAddress
                if (remoteAddr == null || !isPrivateLanIpv4(remoteAddr)) return
                sock.soTimeout = TCP_READ_TIMEOUT_MS

                val password = activeEncryptionPassword
                if (password.isEmpty()) return

                val input = DataInputStream(sock.getInputStream())
                val output = DataOutputStream(sock.getOutputStream())

                val reqBytes = readFrame(input) ?: return
                val reqJson = BackupManager.decryptBackupBytesIfNeeded(reqBytes, password) ?: return
                val reqPacket = runCatching { gson.fromJson(reqJson, WifiSyncPacket::class.java) }.getOrNull()
                    ?: return
                if (reqPacket.v != PROTOCOL_VERSION || reqPacket.nonce.isBlank()) return
                if (reqPacket.deviceId == activeDeviceId) return

                // 若本机正在编辑中，告知对方稍后重试，绝不打断输入光标
                if (activeIsEditingProvider?.invoke() == true) {
                    val deferResp = WifiSyncPacket(
                        v = PROTOCOL_VERSION,
                        deviceId = activeDeviceId,
                        nonce = reqPacket.nonce,
                        deferred = true
                    )
                    val deferBytes = BackupManager.encryptBackupBytes(
                        gson.toJson(deferResp).toByteArray(Charsets.UTF_8),
                        password
                    )
                    writeFrame(output, deferBytes)
                    return
                }

                var outcome = SyncMergeEngine.MergeResult()
                var responseBytes: ByteArray? = null

                syncMutex.withLock {
                    if (!running.get()) return
                    if (activeIsEditingProvider?.invoke() == true) {
                        val deferResp = WifiSyncPacket(
                            v = PROTOCOL_VERSION,
                            deviceId = activeDeviceId,
                            nonce = reqPacket.nonce,
                            deferred = true
                        )
                        responseBytes = BackupManager.encryptBackupBytes(
                            gson.toJson(deferResp).toByteArray(Charsets.UTF_8),
                            password
                        )
                        return@withLock
                    }
                    outcome = mergeIncomingPacket(reqPacket)
                    val postMergePacket = buildLocalPacket(reqPacket.nonce)
                    responseBytes = BackupManager.encryptBackupBytes(
                        gson.toJson(postMergePacket).toByteArray(Charsets.UTF_8),
                        password
                    )
                    stateDirty.set(true)
                    refreshStateHashLocked()
                }

                responseBytes?.let { writeFrame(output, it) }

                if (outcome.notesChanged || outcome.settingsChanged) {
                    activeOnSyncApplied?.invoke(outcome.notesChanged, outcome.settingsChanged)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Handle incoming TCP sync ended: ${e.message}")
            }
        }
    }

    private suspend fun initiateTcpSync(host: String, port: Int, peerDeviceId: String) {
        val password = activeEncryptionPassword
        if (!running.get() || password.isEmpty()) return

        var outcome = SyncMergeEngine.MergeResult()
        var syncedOk = false

        syncMutex.withLock {
            if (!running.get() || activeIsEditingProvider?.invoke() == true) return
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)
                socket.soTimeout = TCP_READ_TIMEOUT_MS
                socket.use { sock ->
                    val output = DataOutputStream(sock.getOutputStream())
                    val input = DataInputStream(sock.getInputStream())

                    val nonce = generateNonce()
                    val localPacket = buildLocalPacket(nonce)
                    val encryptedReq = BackupManager.encryptBackupBytes(
                        gson.toJson(localPacket).toByteArray(Charsets.UTF_8),
                        password
                    )
                    writeFrame(output, encryptedReq)

                    val respBytes = readFrame(input) ?: return@use
                    val respJson = BackupManager.decryptBackupBytesIfNeeded(respBytes, password) ?: return@use
                    val respPacket = runCatching { gson.fromJson(respJson, WifiSyncPacket::class.java) }.getOrNull()
                        ?: return@use
                    if (respPacket.v != PROTOCOL_VERSION || respPacket.nonce != nonce) return@use
                    if (respPacket.deferred) return@use

                    if (activeIsEditingProvider?.invoke() == true) return@use
                    outcome = mergeIncomingPacket(respPacket)
                    stateDirty.set(true)
                    refreshStateHashLocked()
                    syncedOk = true
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP sync to $peerDeviceId ($host:$port) failed: ${e.message}")
            }
        }

        if (syncedOk && (outcome.notesChanged || outcome.settingsChanged)) {
            activeOnSyncApplied?.invoke(outcome.notesChanged, outcome.settingsChanged)
        }
    }

    private fun readFrame(input: DataInputStream): ByteArray? {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME_BYTES) return null
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }

    private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    // ==================== 数据快照、摘要与统一双向合并 ====================

    private suspend fun buildLocalPacket(nonce: String): WifiSyncPacket {
        val ctx = appContext ?: error("Context unavailable")
        val dao = activeNoteDao ?: error("NoteDao unavailable")
        val withDbLock = activeWithDbLock ?: error("DbLock unavailable")

        var notes: List<NoteEntity> = emptyList()
        var tombstones: List<DeletedNoteTombstone> = emptyList()
        var settings = SyncMergeEngine.SyncableSettings()

        withDbLock {
            val cutoff = System.currentTimeMillis() - SyncMergeEngine.TOMBSTONE_RETENTION_MS
            notes = dao.getAllNotesForBackup()
            tombstones = dao.getAllTombstones().filter { it.syncId.isNotBlank() && it.deletedAt >= cutoff }
            settings = SyncMergeEngine.readLocalSyncableSettings(ctx)
        }

        return WifiSyncPacket(
            v = PROTOCOL_VERSION,
            deviceId = activeDeviceId,
            nonce = nonce,
            deferred = false,
            notes = notes,
            tombstones = tombstones,
            settings = settings
        )
    }

    private suspend fun mergeIncomingPacket(packet: WifiSyncPacket): SyncMergeEngine.MergeResult {
        val ctx = appContext ?: return SyncMergeEngine.MergeResult()
        val dao = activeNoteDao ?: return SyncMergeEngine.MergeResult()
        val withDbLock = activeWithDbLock ?: return SyncMergeEngine.MergeResult()

        val remoteNotes = SyncMergeEngine.sanitizeRemoteNotes(packet.notes.orEmpty())
        val remoteTombstones = SyncMergeEngine.sanitizeRemoteTombstones(packet.tombstones.orEmpty())
        val remoteSettings = listOfNotNull(SyncMergeEngine.sanitizeRemoteSettings(packet.settings))

        var result = SyncMergeEngine.MergeResult()
        withDbLock {
            if (activeIsEditingProvider?.invoke() == true) return@withDbLock
            result = SyncMergeEngine.mergeIntoLocalDb(
                context = ctx,
                noteDao = dao,
                remoteNoteLists = listOf(remoteNotes),
                remoteTombstoneLists = listOf(remoteTombstones),
                remoteSettingsList = remoteSettings
            )
        }
        return result
    }

    private suspend fun refreshStateHashIfNeeded(): String {
        if (!stateDirty.get() && cachedStateHash.isNotEmpty()) {
            return cachedStateHash
        }
        return refreshStateHashLocked()
    }

    private suspend fun refreshStateHashLocked(): String {
        val ctx = appContext ?: return ""
        val dao = activeNoteDao ?: return ""
        val withDbLock = activeWithDbLock ?: return ""
        var hash = ""
        withDbLock {
            val cutoff = System.currentTimeMillis() - SyncMergeEngine.TOMBSTONE_RETENTION_MS
            val notes = dao.getAllNotesForBackup()
            val tombstones = dao.getAllTombstones().filter { it.syncId.isNotBlank() && it.deletedAt >= cutoff }
            val settings = SyncMergeEngine.readLocalSyncableSettings(ctx)
            hash = SyncMergeEngine.computeFullStateHash(notes, tombstones, settings)
        }
        cachedStateHash = hash
        stateDirty.set(false)
        return hash
    }

    private fun computeGroupHash(password: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(GROUP_HMAC_DOMAIN.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun isPrivateLanIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address || address.isLoopbackAddress) return false
        val bytes = address.address
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return b0 == 10 ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 192 && b1 == 168) ||
            (b0 == 169 && b1 == 254)
    }
}
