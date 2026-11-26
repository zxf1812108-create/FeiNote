package com.feiji.bulletnotes.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.feiji.bulletnotes.MainActivity
import com.feiji.bulletnotes.R
import com.feiji.bulletnotes.alarm.NoteAlarmScheduler
import com.feiji.bulletnotes.ui.theme.ThemePreferences
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网电脑/设备直连 HTTP 服务（纯 Android/JDK 标准库，零新增第三方依赖）：
 * - 默认关闭，手动开启；通过前台服务支持锁屏/切后台持续连接；无操作 30 分钟自动停服
 * - 强制随机 6 位配对码认证 + 输错 5 次锁定 5 分钟 + HttpOnly/SameSite=Strict Cookie + CSRF Token
 * - 校验私有局域网来源 IP 与 Host 头，防跨站请求与 DNS Rebinding
 */
object LanWebServer {
    private const val TAG = "FeiJiLanServer"
    private const val BASE_PORT = 8686
    private const val PORT_SEARCH_COUNT = 20
    private const val SOCKET_TIMEOUT_MS = 15_000
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_NOTE_CONTENT_LENGTH = 200_000
    private const val IDLE_TIMEOUT_MS = 30 * 60_000L
    private const val MAX_PIN_FAILURES = 5
    private const val LOCKOUT_DURATION_MS = 5 * 60_000L
    private const val TRASH_RETENTION_MS = 3L * 24L * 60L * 60L * 1000L

    private val gson = Gson()
    private val secureRandom = SecureRandom()
    private val dateRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")

    data class LanServerState(
        val running: Boolean = false,
        val port: Int = BASE_PORT,
        val urls: List<String> = emptyList(),
        val pin: String = "",
        val lastClientIp: String? = null,
        val activeSessions: Int = 0,
        val errorMessage: String? = null
    ) {
        val primaryUrl: String
            get() = urls.firstOrNull() ?: "http://127.0.0.1:$port"
    }

    private data class SessionInfo(
        val csrfToken: String,
        val clientIp: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    private data class FailureRecord(
        val count: Int,
        val lockedUntilMs: Long
    )

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private data class AuthPayload(val pin: String? = null)

    private data class CreateNotePayload(
        val dateString: String? = null,
        val content: String? = null,
        val bulletType: String? = null,
        val afterNoteId: Long? = null,
        val allowBlank: Boolean? = null
    )

    private data class UpdateNotePayload(
        val content: String? = null,
        val dateString: String? = null,
        val expectedUpdatedAt: Long? = null,
        val deleteIfBlank: Boolean? = null
    )

    private data class AlarmPayload(
        val alarmTimeMillis: Long? = null
    )

    private data class ReviewPayload(
        val familiarity: String? = null
    )

    private data class UndoReviewPayload(
        val dateString: String? = null,
        val orderIndex: Int? = null,
        val alarmTimeMillis: Long? = null,
        val reviewCount: Int? = null,
        val reviewIntervalDays: Int? = null,
        val easeFactor: Float? = null,
        val lastReviewedAt: Long? = null
    )

    private data class ReorderPayload(
        val dateString: String? = null,
        val orderedIds: List<Long>? = null
    )

    private data class MemoryCurvePayload(
        val unfamiliarDays: Int? = null,
        val initialSomewhatDays: Int? = null,
        val initialVeryDays: Int? = null,
        val initialEaseFactor: Float? = null,
        val somewhatMultiplier: Float? = null,
        val maxIntervalDays: Int? = null
    )

    private data class KeywordHintPayload(
        val previousText: String? = null,
        val currentText: String? = null,
        val cursorPosition: Int? = null,
        val excludeNoteId: Long? = null,
        val dismissedKeywords: List<String>? = null
    )

    private val _state = MutableStateFlow(LanServerState())
    val state: StateFlow<LanServerState> = _state.asStateFlow()

    private val lifecycleLock = Any()
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var backupDebounceJob: Job? = null
    private var onIdleTimeoutCallback: (() -> Unit)? = null
    private var htmlCache: ByteArray? = null

    @Volatile private var currentPin: String = ""
    @Volatile private var lastActivityMs: Long = 0L

    private val sessions = ConcurrentHashMap<String, SessionInfo>()
    private val failureRecords = ConcurrentHashMap<String, FailureRecord>()

    fun start(context: Context, onIdleTimeout: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (_state.value.running && serverSocket != null) {
            onIdleTimeoutCallback = onIdleTimeout
            return true
        }

        val appContext = context.applicationContext
        val socket = bindAvailableSocket()
        if (socket == null) {
            _state.value = LanServerState(
                running = false,
                errorMessage = "无法绑定局域网端口，请稍后重试"
            )
            return false
        }

        val port = socket.localPort
        val pin = generatePin()
        currentPin = pin
        sessions.clear()
        failureRecords.clear()
        lastActivityMs = System.currentTimeMillis()
        onIdleTimeoutCallback = onIdleTimeout
        serverSocket = socket

        if (htmlCache == null) {
            htmlCache = runCatching {
                appContext.assets.open("lan_editor.html").use { it.readBytes() }
            }.getOrElse {
                "<h1>无法加载编辑器页面</h1>".toByteArray(Charsets.UTF_8)
            }
        }

        val localIps = detectLocalIpv4Addresses()
        val urls = localIps.map { "http://$it:$port" }
        _state.value = LanServerState(
            running = true,
            port = port,
            urls = urls,
            pin = pin,
            lastClientIp = null,
            activeSessions = 0,
            errorMessage = null
        )

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        val noteDao = AppDatabase.getInstance(appContext).noteDao()

        // 主监听循环
        serverScope.launch {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Accept error", e)
                    continue
                }
                launch {
                    handleClient(appContext, noteDao, client)
                }
            }
        }

        // 闲置 30 分钟自动关闭，并刷新可用局域网 IP
        serverScope.launch {
            while (isActive) {
                delay(60_000L)
                val now = System.currentTimeMillis()
                if (now - lastActivityMs >= IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "LAN server idle timeout reached, stopping automatically")
                    onIdleTimeoutCallback?.invoke()
                    break
                }
                val refreshedUrls = detectLocalIpv4Addresses().map { "http://$it:$port" }
                if (refreshedUrls != _state.value.urls) {
                    _state.value = _state.value.copy(urls = refreshedUrls)
                }
            }
        }

        true
    }

    fun stop() = synchronized(lifecycleLock) {
        onIdleTimeoutCallback = null
        backupDebounceJob?.cancel()
        backupDebounceJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        sessions.clear()
        failureRecords.clear()
        currentPin = ""
        _state.value = LanServerState(running = false)
    }

    fun refreshPin() = synchronized(lifecycleLock) {
        if (!_state.value.running) return
        val nextPin = generatePin()
        currentPin = nextPin
        sessions.clear()
        failureRecords.clear()
        lastActivityMs = System.currentTimeMillis()
        _state.value = _state.value.copy(
            pin = nextPin,
            activeSessions = 0,
            lastClientIp = null
        )
    }

    /** 读取本机当前局域网访问链接预览（未启动服务时也能直接展示给用户）。 */
    fun previewLanUrls(port: Int = BASE_PORT): List<String> {
        return detectLocalIpv4Addresses().map { "http://$it:$port" }
    }

    private fun bindAvailableSocket(): ServerSocket? {
        for (offset in 0 until PORT_SEARCH_COUNT) {
            val port = BASE_PORT + offset
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(port))
                }
            } catch (_: Exception) {
                // 端口占用时顺延尝试下一个
            }
        }
        return null
    }

    private fun generatePin(): String {
        val number = secureRandom.nextInt(1_000_000)
        return String.format("%06d", number)
    }

    private fun generateRandomHex(bytesLen: Int = 24): String {
        val bytes = ByteArray(bytesLen)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder(bytesLen * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun isAllowedClientAddress(addr: InetAddress?): Boolean {
        if (addr == null) return false
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
        val raw = addr.address
        // 允许 CGNAT / 部分热点网段 100.64.0.0/10
        if (raw.size == 4) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            if (b0 == 100 && b1 in 64..127) return true
        }
        // 允许 IPv6 ULA fc00::/7
        if (raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }

    private fun isAllowedHostHeader(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        val trimmed = hostHeader.trim().lowercase()
        val hostOnly = if (trimmed.startsWith("[")) {
            trimmed.substringBefore(']') + "]"
        } else {
            trimmed.substringBefore(':')
        }
        if (hostOnly == "localhost" || hostOnly == "127.0.0.1" || hostOnly == "[::1]") return true
        val localIps = detectLocalIpv4Addresses()
        return localIps.any { it.equals(hostOnly, ignoreCase = true) }
    }

    private fun detectLocalIpv4Addresses(): List<String> {
        val result = linkedSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (addr.isSiteLocalAddress || ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            result.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate local IPs", e)
        }
        // 优先排 192.168.*（常见 Wi-Fi / 热点）
        return result.sortedByDescending { it.startsWith("192.168.") }
    }

    private fun parseValidDate(raw: String?): LocalDate? {
        if (raw == null || !dateRegex.matches(raw)) return null
        return runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    private suspend fun handleClient(
        appContext: Context,
        noteDao: NoteDao,
        socket: Socket
    ) {
        socket.use { sock ->
            try {
                sock.soTimeout = SOCKET_TIMEOUT_MS
                val clientIp = sock.inetAddress?.hostAddress ?: "unknown"
                if (!isAllowedClientAddress(sock.inetAddress)) {
                    writeJson(sock, 403, mapOf("error" to "仅允许局域网设备访问"))
                    return
                }

                val request = readHttpRequest(BufferedInputStream(sock.getInputStream())) ?: return
                if (!isAllowedHostHeader(request.headers["host"])) {
                    writeJson(sock, 403, mapOf("error" to "非法 Host 访问"))
                    return
                }

                routeRequest(appContext, noteDao, sock, clientIp, request)
            } catch (_: Exception) {
                // 客户端提前断开或超时属正常现象，静默忽略
            }
        }
    }

    private suspend fun routeRequest(
        appContext: Context,
        noteDao: NoteDao,
        sock: Socket,
        clientIp: String,
        req: HttpRequest
    ) {
        val method = req.method.uppercase()
        val path = req.path
        val isPoll = req.query["poll"] == "1"

        // 1. 静态入口页
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val html = htmlCache ?: "<h1>飞记</h1>".toByteArray(Charsets.UTF_8)
            writeResponse(
                sock,
                200,
                "text/html; charset=utf-8",
                html,
                extraHeaders = listOf(
                    "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
                )
            )
            return
        }

        // 2. 会话检查
        if (method == "GET" && path == "/api/session") {
            val session = authenticateSession(req)
            if (session != null) {
                writeJson(sock, 200, mapOf("authenticated" to true, "csrfToken" to session.csrfToken))
            } else {
                writeJson(sock, 200, mapOf("authenticated" to false))
            }
            return
        }

        // 3. 配对码登录
        if (method == "POST" && path == "/api/auth") {
            handleAuth(sock, clientIp, req)
            return
        }

        // 以下所有 API 均需有效会话
        val session = authenticateSession(req)
        if (session == null) {
            writeJson(sock, 401, mapOf("error" to "未登录或会话已过期"))
            return
        }

        // 所有写操作强制校验 CSRF Token
        if (method != "GET") {
            val headerToken = req.headers["x-feinote-token"] ?: ""
            if (!constantTimeEquals(headerToken, session.csrfToken)) {
                writeJson(sock, 403, mapOf("error" to "请求令牌校验失败"))
                return
            }
        }

        // 仅真实操作刷新闲置计时（忽略后台 4 秒轮询，确保离开后 30 分钟仍会自动停服）
        if (!isPoll) {
            lastActivityMs = System.currentTimeMillis()
        }

        if (method == "POST" && path == "/api/logout") {
            val sid = extractCookie(req.headers["cookie"], "feiji_sid")
            if (sid != null) {
                sessions.remove(sid)
                updateSessionMetrics(null)
            }
            writeJson(
                sock,
                200,
                mapOf("ok" to true),
                extraHeaders = listOf("Set-Cookie: feiji_sid=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0")
            )
            return
        }

        // 4. 元数据（日历笔记日期圆点、回收站数量、记忆曲线参数、最早五角星日期、手机主题偏好）
        if (method == "GET" && path == "/api/meta") {
            enforceTrashLimit(appContext, noteDao)
            val activeNotes = noteDao.getAllActiveNotes()
            val activeDates = activeNotes.map { it.dateString }.distinct().sorted()
            val earliestStarDate = activeNotes
                .asSequence()
                .filter { it.bulletType == BulletType.STAR }
                .map { it.dateString }
                .minOrNull()
            val trashCount = noteDao.getAllDeletedNotesOnce().size
            val memoryCurveConfig = MemoryCurvePreferences.load(appContext)
            val phoneDarkTheme = ThemePreferences.resolveDarkTheme(appContext, false)
            writeJson(
                sock,
                200,
                mapOf(
                    "today" to LocalDate.now().toString(),
                    "activeDates" to activeDates,
                    "earliestStarDate" to earliestStarDate,
                    "trashCount" to trashCount,
                    "memoryCurveConfig" to memoryCurveConfig,
                    "phoneDarkTheme" to phoneDarkTheme,
                    "canScheduleExactAlarms" to NoteAlarmScheduler.canScheduleExactAlarms(appContext),
                    "serverTimeMillis" to System.currentTimeMillis()
                )
            )
            return
        }

        // 5. 按日期查询笔记
        if (method == "GET" && path == "/api/notes") {
            val parsedDate = parseValidDate(req.query["date"]) ?: LocalDate.now()
            val dateStr = parsedDate.toString()
            val list = noteDao.getNotesByDate(dateStr).first()
            writeJson(
                sock,
                200,
                mapOf(
                    "date" to dateStr,
                    "today" to LocalDate.now().toString(),
                    "notes" to list
                )
            )
            return
        }

        // 6. 全文搜索（与手机端一致：支持 scope=normal|star|all 及旧版 bullet 参数）
        if (method == "GET" && path == "/api/search") {
            val rawQuery = req.query["q"]?.trim().orEmpty()
            val scopeParam = req.query["scope"]?.trim()?.lowercase().orEmpty()
            val legacyBullet = req.query["bullet"]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { BulletType.valueOf(it) }.getOrNull() }
            val allowedTypes: Set<BulletType>? = when {
                scopeParam == "normal" -> setOf(BulletType.SQUARE, BulletType.CHECKED, BulletType.BULLET_DOT)
                scopeParam == "star" -> setOf(BulletType.STAR)
                scopeParam == "all" -> null
                legacyBullet != null -> setOf(legacyBullet)
                else -> null
            }
            val words = rawQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
            val matched = if (words.isEmpty()) {
                emptyList()
            } else {
                noteDao.getAllActiveNotes()
                    .asSequence()
                    .filter { note ->
                        (allowedTypes == null || note.bulletType in allowedTypes) &&
                            words.all { w -> note.content.contains(w, ignoreCase = true) }
                    }
                    .sortedWith(compareByDescending<NoteEntity> { it.dateString }.thenBy { it.orderIndex })
                    .toList()
            }
            writeJson(sock, 200, mapOf("notes" to matched))
            return
        }

        // 7. 关键字即时提示（@关键词@ / #关键词#）
        if (method == "POST" && path == "/api/hints") {
            val payload = parseJsonBody(req.body, KeywordHintPayload::class.java)
            val prev = payload?.previousText ?: ""
            val curr = payload?.currentText ?: ""
            val cursor = payload?.cursorPosition ?: curr.length
            val excludeId = payload?.excludeNoteId ?: -1L
            val dismissed = payload?.dismissedKeywords?.map { it.lowercase() }?.toSet() ?: emptySet()
            val candidates = noteDao.getKeywordCandidateNotes().first()
            val index = KeywordHintEngine.buildIndex(candidates)
            val match = KeywordHintEngine.findMatchOnEdit(
                previousText = prev,
                currentText = curr,
                cursorPosition = cursor,
                index = index,
                excludeNoteId = excludeId,
                dismissedKeywords = dismissed
            )
            if (match == null) {
                writeJson(sock, 200, mapOf("match" to null))
            } else {
                writeJson(
                    sock,
                    200,
                    mapOf(
                        "match" to mapOf(
                            "displayKeyword" to match.displayKeyword,
                            "normalizedKeyword" to match.normalizedKeyword,
                            "notes" to match.notes.take(12)
                        )
                    )
                )
            }
            return
        }

        // 8. 记忆曲线参数读取/保存
        if (path == "/api/memory-curve") {
            if (method == "GET") {
                writeJson(sock, 200, mapOf("config" to MemoryCurvePreferences.load(appContext)))
                return
            }
            if (method == "PUT") {
                val payload = parseJsonBody(req.body, MemoryCurvePayload::class.java)
                val current = MemoryCurvePreferences.load(appContext)
                val updated = MemoryCurvePreferences.save(
                    appContext,
                    MemoryCurveConfig(
                        unfamiliarDays = payload?.unfamiliarDays ?: current.unfamiliarDays,
                        initialSomewhatDays = payload?.initialSomewhatDays ?: current.initialSomewhatDays,
                        initialVeryDays = payload?.initialVeryDays ?: current.initialVeryDays,
                        initialEaseFactor = payload?.initialEaseFactor ?: current.initialEaseFactor,
                        somewhatMultiplier = payload?.somewhatMultiplier ?: current.somewhatMultiplier,
                        maxIntervalDays = payload?.maxIntervalDays ?: current.maxIntervalDays
                    )
                )
                scheduleLocalBackup(appContext, noteDao)
                writeJson(sock, 200, mapOf("ok" to true, "config" to updated))
                return
            }
        }

        // 9. 回收站管理（3 天自动彻底清理 + 仅单条恢复 + 一键清空）
        if (method == "GET" && path == "/api/trash") {
            enforceTrashLimit(appContext, noteDao)
            val deleted = noteDao.getAllDeletedNotesOnce()
                .sortedByDescending { it.deletedAt ?: it.updatedAt }
            writeJson(
                sock,
                200,
                mapOf(
                    "notes" to deleted,
                    "serverTimeMillis" to System.currentTimeMillis()
                )
            )
            return
        }

        if (method == "POST" && path == "/api/trash/empty") {
            val deletedIds = noteDao.emptyTrashWithTombstones(System.currentTimeMillis())
            deletedIds.forEach { NoteAlarmScheduler.cancelAlarm(appContext, it) }
            if (deletedIds.isNotEmpty()) {
                scheduleLocalBackup(appContext, noteDao)
            }
            writeJson(sock, 200, mapOf("ok" to true, "count" to deletedIds.size))
            return
        }

        if (path.startsWith("/api/trash/")) {
            val tail = path.removePrefix("/api/trash/")
            val parts = tail.split('/').filter { it.isNotEmpty() }
            val noteId = parts.firstOrNull()?.toLongOrNull()
            if (noteId == null || noteId <= 0L) {
                writeJson(sock, 400, mapOf("error" to "无效的笔记 ID"))
                return
            }
            if (parts.size == 2 && parts[1] == "restore" && method == "POST") {
                val existing = noteDao.getNoteById(noteId)
                if (existing == null || !existing.isDeleted) {
                    writeJson(sock, 404, mapOf("error" to "回收站中未找到该笔记"))
                    return
                }
                noteDao.restoreNote(noteId)
                val restored = noteDao.getNoteById(noteId)
                if (restored != null) {
                    NoteAlarmScheduler.syncNoteAlarm(appContext, restored)
                }
                scheduleLocalBackup(appContext, noteDao)
                writeJson(sock, 200, mapOf("ok" to true, "note" to restored))
                return
            }
        }

        // 10. 拖拽重排序
        if (method == "POST" && path == "/api/notes/reorder") {
            val payload = parseJsonBody(req.body, ReorderPayload::class.java)
            val date = parseValidDate(payload?.dateString)
            val orderedIds = payload?.orderedIds?.filter { it > 0L }?.distinct().orEmpty()
            if (date == null || orderedIds.isEmpty()) {
                writeJson(sock, 400, mapOf("error" to "无效的排序参数"))
                return
            }
            val dateStr = date.toString()
            val dayNotes = noteDao.getActiveNotesByDateOnce(dateStr)
            val byId = dayNotes.associateBy { it.id }
            val arranged = ArrayList<NoteEntity>(dayNotes.size)
            val seen = HashSet<Long>(orderedIds.size)
            for (id in orderedIds) {
                val item = byId[id]
                if (item != null && seen.add(id)) {
                    arranged.add(item)
                }
            }
            for (item in dayNotes) {
                if (seen.add(item.id)) {
                    arranged.add(item)
                }
            }
            val now = System.currentTimeMillis()
            val updates = arranged.mapIndexedNotNull { index, item ->
                if (item.orderIndex != index) item.copy(orderIndex = index, updatedAt = now) else null
            }
            if (updates.isNotEmpty()) {
                noteDao.updateNotes(updates)
                scheduleLocalBackup(appContext, noteDao)
            }
            writeJson(sock, 200, mapOf("ok" to true))
            return
        }

        // 11. 新建笔记（支持指定符号、在某条笔记后插入、以及空白编辑创建）
        if (method == "POST" && path == "/api/notes") {
            val payload = parseJsonBody(req.body, CreateNotePayload::class.java)
            val allowBlank = payload?.allowBlank == true
            val content = payload?.content?.trimEnd() ?: ""
            if (!allowBlank && content.isBlank()) {
                writeJson(sock, 400, mapOf("error" to "笔记内容不能为空"))
                return
            }
            if (content.length > MAX_NOTE_CONTENT_LENGTH) {
                writeJson(sock, 400, mapOf("error" to "笔记内容过长"))
                return
            }
            val dateStr = (parseValidDate(payload?.dateString) ?: LocalDate.now()).toString()
            val bullet = payload?.bulletType
                ?.let { runCatching { BulletType.valueOf(it) }.getOrNull() }
                ?.takeIf { it != BulletType.CHECKED }
                ?: BulletType.SQUARE

            val existing = noteDao.getActiveNotesByDateOnce(dateStr)
            val now = System.currentTimeMillis()
            val afterId = payload?.afterNoteId
            val referenceNote = afterId?.let { refId -> existing.firstOrNull { it.id == refId } }
            val nextOrder = if (referenceNote != null) {
                referenceNote.orderIndex + 1
            } else {
                (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
            }

            if (referenceNote != null) {
                val shifted = existing.mapNotNull { item ->
                    if (item.orderIndex >= nextOrder) {
                        item.copy(orderIndex = item.orderIndex + 1, updatedAt = now)
                    } else {
                        null
                    }
                }
                if (shifted.isNotEmpty()) {
                    noteDao.updateNotes(shifted)
                }
            }

            val newNote = NoteEntity(
                content = content,
                dateString = dateStr,
                bulletType = bullet,
                orderIndex = nextOrder,
                createdAt = now,
                updatedAt = now
            )
            val id = noteDao.insertNote(newNote)
            scheduleLocalBackup(appContext, noteDao)
            writeJson(sock, 201, mapOf("ok" to true, "note" to newNote.copy(id = id)))
            return
        }

        // 12. 单条笔记操作：PUT /api/notes/{id}, DELETE /api/notes/{id},
        // POST /api/notes/{id}/cycle, /alarm, /review, /undo-review
        if (path.startsWith("/api/notes/")) {
            val tail = path.removePrefix("/api/notes/")
            val parts = tail.split('/').filter { it.isNotEmpty() }
            val noteId = parts.firstOrNull()?.toLongOrNull()
            if (noteId == null || noteId <= 0L) {
                writeJson(sock, 400, mapOf("error" to "无效的笔记 ID"))
                return
            }

            if (parts.size == 2 && parts[1] == "cycle" && method == "POST") {
                val note = noteDao.getNoteById(noteId)
                if (note == null || note.isDeleted) {
                    writeJson(sock, 404, mapOf("error" to "笔记不存在"))
                    return
                }
                val nextType = when (note.bulletType) {
                    BulletType.SQUARE, BulletType.CHECKED -> BulletType.BULLET_DOT
                    BulletType.BULLET_DOT -> BulletType.STAR
                    BulletType.STAR -> BulletType.SQUARE
                }
                val updated = note.copy(bulletType = nextType, updatedAt = System.currentTimeMillis())
                noteDao.updateNote(updated)
                scheduleLocalBackup(appContext, noteDao)
                writeJson(sock, 200, mapOf("ok" to true, "note" to updated))
                return
            }

            if (parts.size == 2 && parts[1] == "alarm" && method == "POST") {
                val note = noteDao.getNoteById(noteId)
                if (note == null || note.isDeleted) {
                    writeJson(sock, 404, mapOf("error" to "笔记不存在"))
                    return
                }
                val payload = parseJsonBody(req.body, AlarmPayload::class.java)
                val targetMillis = payload?.alarmTimeMillis
                val now = System.currentTimeMillis()
                if (targetMillis != null) {
                    if (targetMillis <= now) {
                        writeJson(sock, 400, mapOf("error" to "所选时间已过，请选择晚于当前的时间"))
                        return
                    }
                    val zone = ZoneId.systemDefault()
                    val alarmDateStr = Instant.ofEpochMilli(targetMillis).atZone(zone).toLocalDate().toString()
                    if (alarmDateStr != note.dateString) {
                        writeJson(sock, 400, mapOf("error" to "闹钟日期需与当前笔记日期一致"))
                        return
                    }
                }
                noteDao.updateNoteAlarm(noteId, targetMillis, now)
                val updated = note.copy(alarmTimeMillis = targetMillis, updatedAt = now)
                val scheduled = if (targetMillis != null) {
                    NoteAlarmScheduler.scheduleAlarm(
                        context = appContext,
                        noteId = noteId,
                        triggerAtMillis = targetMillis,
                        noteContent = updated.content,
                        noteDateString = updated.dateString
                    )
                } else {
                    NoteAlarmScheduler.cancelAlarm(appContext, noteId)
                    true
                }
                scheduleLocalBackup(appContext, noteDao)
                val message = when {
                    targetMillis == null -> "已清除闹钟"
                    scheduled -> "已设置闹钟：${NoteAlarmScheduler.formatReadableAlarmTime(targetMillis)}"
                    else -> "闹钟时间已保存，请在手机端开启精确闹钟权限以确保准时响铃"
                }
                writeJson(
                    sock,
                    200,
                    mapOf("ok" to true, "note" to updated, "scheduled" to scheduled, "message" to message)
                )
                return
            }

            if (parts.size == 2 && parts[1] == "review" && method == "POST") {
                val note = noteDao.getNoteById(noteId)
                if (note == null || note.isDeleted) {
                    writeJson(sock, 404, mapOf("error" to "笔记不存在"))
                    return
                }
                val payload = parseJsonBody(req.body, ReviewPayload::class.java)
                val familiarity = payload?.familiarity
                    ?.let { runCatching { ReviewFamiliarity.valueOf(it) }.getOrNull() }
                if (familiarity == null) {
                    writeJson(sock, 400, mapOf("error" to "无效的熟悉度选项"))
                    return
                }
                val today = LocalDate.now()
                val config = MemoryCurvePreferences.load(appContext)
                val schedule = SpacedRepetitionEngine.computeNextReview(
                    note = note,
                    familiarity = familiarity,
                    today = today,
                    config = config
                )
                val targetDateStr = schedule.nextDate.toString()
                val targetOrderIndex = if (targetDateStr != note.dateString) {
                    val targetDayNotes = noteDao.getActiveNotesByDateOnce(targetDateStr)
                    (targetDayNotes.maxOfOrNull { it.orderIndex } ?: -1) + 1
                } else {
                    note.orderIndex
                }
                val now = System.currentTimeMillis()
                val shiftedAlarm = NoteAlarmScheduler.retargetAlarmToDate(
                    note.alarmTimeMillis,
                    schedule.nextDate
                )?.takeIf { it > now }
                val updated = note.copy(
                    dateString = targetDateStr,
                    orderIndex = targetOrderIndex,
                    alarmTimeMillis = shiftedAlarm,
                    reviewCount = schedule.nextReviewCount,
                    reviewIntervalDays = schedule.nextIntervalDays,
                    easeFactor = schedule.nextEaseFactor,
                    lastReviewedAt = now,
                    updatedAt = now
                )
                noteDao.updateNote(updated)
                NoteAlarmScheduler.syncNoteAlarm(appContext, updated)
                scheduleLocalBackup(appContext, noteDao)
                writeJson(
                    sock,
                    200,
                    mapOf(
                        "ok" to true,
                        "note" to updated,
                        "previousNote" to note,
                        "targetDateString" to targetDateStr,
                        "intervalDays" to schedule.nextIntervalDays
                    )
                )
                return
            }

            if (parts.size == 2 && parts[1] == "undo-review" && method == "POST") {
                val current = noteDao.getNoteById(noteId)
                if (current == null || current.isDeleted) {
                    writeJson(sock, 404, mapOf("error" to "笔记不存在"))
                    return
                }
                val payload = parseJsonBody(req.body, UndoReviewPayload::class.java)
                val prevDate = parseValidDate(payload?.dateString)
                if (prevDate == null) {
                    writeJson(sock, 400, mapOf("error" to "无效的撤销参数"))
                    return
                }
                val now = System.currentTimeMillis()
                val restoredAlarm = payload?.alarmTimeMillis?.takeIf { it > now }
                val restored = current.copy(
                    dateString = prevDate.toString(),
                    orderIndex = payload?.orderIndex ?: current.orderIndex,
                    alarmTimeMillis = restoredAlarm,
                    reviewCount = (payload?.reviewCount ?: 0).coerceAtLeast(0),
                    reviewIntervalDays = (payload?.reviewIntervalDays ?: 0).coerceIn(0, SpacedRepetitionEngine.HARD_MAX_INTERVAL_DAYS),
                    easeFactor = SpacedRepetitionEngine.normalizeEaseFactor(payload?.easeFactor ?: SpacedRepetitionEngine.DEFAULT_EASE_FACTOR),
                    lastReviewedAt = payload?.lastReviewedAt?.takeIf { it > 0L },
                    updatedAt = now
                )
                noteDao.updateNote(restored)
                NoteAlarmScheduler.syncNoteAlarm(appContext, restored)
                scheduleLocalBackup(appContext, noteDao)
                writeJson(sock, 200, mapOf("ok" to true, "note" to restored))
                return
            }

            if (parts.size == 1 && method == "PUT") {
                val note = noteDao.getNoteById(noteId)
                if (note == null || note.isDeleted) {
                    writeJson(sock, 404, mapOf("error" to "笔记不存在"))
                    return
                }
                val payload = parseJsonBody(req.body, UpdateNotePayload::class.java)
                if (payload?.expectedUpdatedAt != null && payload.expectedUpdatedAt != note.updatedAt) {
                    writeJson(sock, 409, mapOf("error" to "笔记已在手机端被更新", "note" to note))
                    return
                }

                // 若传入了新内容且允许清空即永久删除（与手机端编辑逻辑一致）
                val hasContentField = payload?.content != null
                val nextContent = payload?.content ?: note.content
                if (nextContent.length > MAX_NOTE_CONTENT_LENGTH) {
                    writeJson(sock, 400, mapOf("error" to "笔记内容过长"))
                    return
                }
                if (hasContentField && nextContent.isBlank()) {
                    if (payload?.deleteIfBlank == true) {
                        noteDao.deletePermanentlyWithTombstone(noteId)
                        NoteAlarmScheduler.cancelAlarm(appContext, noteId)
                        scheduleLocalBackup(appContext, noteDao)
                        writeJson(sock, 200, mapOf("ok" to true, "deleted" to true))
                        return
                    } else {
                        writeJson(sock, 400, mapOf("error" to "笔记内容不能为空"))
                        return
                    }
                }

                val now = System.currentTimeMillis()
                var nextDateStr = note.dateString
                var nextOrderIndex = note.orderIndex
                var nextAlarmMillis = note.alarmTimeMillis
                var dateChanged = false

                if (!payload?.dateString.isNullOrBlank() && payload?.dateString != note.dateString) {
                    val parsedNewDate = parseValidDate(payload?.dateString)
                    if (parsedNewDate == null) {
                        writeJson(sock, 400, mapOf("error" to "无效的日期格式"))
                        return
                    }
                    nextDateStr = parsedNewDate.toString()
                    val targetNotes = noteDao.getActiveNotesByDateOnce(nextDateStr)
                    nextOrderIndex = (targetNotes.maxOfOrNull { it.orderIndex } ?: -1) + 1
                    nextAlarmMillis = NoteAlarmScheduler.retargetAlarmToDate(
                        note.alarmTimeMillis,
                        parsedNewDate
                    )?.takeIf { it > now }
                    dateChanged = true
                }

                val updated = note.copy(
                    content = nextContent,
                    dateString = nextDateStr,
                    orderIndex = nextOrderIndex,
                    alarmTimeMillis = nextAlarmMillis,
                    updatedAt = now
                )
                noteDao.updateNote(updated)
                if (dateChanged || updated.alarmTimeMillis != null) {
                    NoteAlarmScheduler.syncNoteAlarm(appContext, updated)
                }
                scheduleLocalBackup(appContext, noteDao)
                writeJson(
                    sock,
                    200,
                    mapOf(
                        "ok" to true,
                        "note" to updated,
                        "dateChanged" to dateChanged,
                        "hadActiveAlarm" to (dateChanged && nextAlarmMillis != null)
                    )
                )
                return
            }

            if (parts.size == 1 && method == "DELETE") {
                val note = noteDao.getNoteById(noteId)
                if (note == null || note.isDeleted) {
                    writeJson(sock, 404, mapOf("error" to "笔记不存在"))
                    return
                }
                noteDao.softDeleteNote(noteId)
                NoteAlarmScheduler.cancelAlarm(appContext, noteId)
                enforceTrashLimit(appContext, noteDao)
                scheduleLocalBackup(appContext, noteDao)
                writeJson(sock, 200, mapOf("ok" to true))
                return
            }
        }

        writeJson(sock, 404, mapOf("error" to "Not Found"))
    }

    private fun handleAuth(sock: Socket, clientIp: String, req: HttpRequest) {
        val now = System.currentTimeMillis()
        val failRecord = failureRecords[clientIp]
        if (failRecord != null && failRecord.lockedUntilMs > now) {
            val remainSec = ((failRecord.lockedUntilMs - now) / 1000L).coerceAtLeast(1L)
            writeJson(sock, 429, mapOf("error" to "输错次数过多，请 ${remainSec} 秒后再试"))
            return
        }

        val payload = parseJsonBody(req.body, AuthPayload::class.java)
        val submittedPin = payload?.pin?.trim().orEmpty()
        if (currentPin.isNotEmpty() && constantTimeEquals(submittedPin, currentPin)) {
            failureRecords.remove(clientIp)
            val sid = generateRandomHex(24)
            val csrf = generateRandomHex(24)
            sessions[sid] = SessionInfo(csrfToken = csrf, clientIp = clientIp)
            lastActivityMs = now
            updateSessionMetrics(clientIp)
            writeJson(
                sock,
                200,
                mapOf("ok" to true, "csrfToken" to csrf),
                extraHeaders = listOf("Set-Cookie: feiji_sid=$sid; HttpOnly; SameSite=Strict; Path=/")
            )
        } else {
            val nextCount = (failRecord?.count ?: 0) + 1
            val lockedUntil = if (nextCount >= MAX_PIN_FAILURES) now + LOCKOUT_DURATION_MS else 0L
            failureRecords[clientIp] = FailureRecord(nextCount, lockedUntil)
            val msg = if (lockedUntil > 0L) {
                "连续输错 $MAX_PIN_FAILURES 次，已临时锁定 5 分钟"
            } else {
                "配对码错误（还可尝试 ${MAX_PIN_FAILURES - nextCount} 次）"
            }
            writeJson(sock, 401, mapOf("error" to msg))
        }
    }

    private fun authenticateSession(req: HttpRequest): SessionInfo? {
        val sid = extractCookie(req.headers["cookie"], "feiji_sid") ?: return null
        return sessions[sid]
    }

    private fun updateSessionMetrics(lastIp: String?) {
        val cur = _state.value
        if (!cur.running) return
        _state.value = cur.copy(
            lastClientIp = lastIp ?: cur.lastClientIp,
            activeSessions = sessions.size
        )
    }

    private suspend fun enforceTrashLimit(appContext: Context, noteDao: NoteDao) {
        val now = System.currentTimeMillis()
        val cutoff = now - TRASH_RETENTION_MS
        val purgedIds = noteDao.deleteTrashOlderThanWithTombstones(cutoff = cutoff, now = now)
        purgedIds.forEach { NoteAlarmScheduler.cancelAlarm(appContext, it) }
    }

    private fun scheduleLocalBackup(appContext: Context, noteDao: NoteDao) {
        WifiAutoSyncManager.notifyLocalDataChanged()
        if (!BackupManager.hasBackupFolder(appContext)) return
        val serverScope = scope ?: return
        backupDebounceJob?.cancel()
        backupDebounceJob = serverScope.launch {
            delay(3_000L)
            runCatching {
                BackupManager.exportBackup(appContext, noteDao)
            }.onFailure { e ->
                Log.w(TAG, "Debounced local backup after LAN edit failed", e)
            }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    private fun extractCookie(cookieHeader: String?, name: String): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val prefix = "$name="
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            if (trimmed.startsWith(prefix)) {
                return trimmed.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun <T> parseJsonBody(body: ByteArray, clazz: Class<T>): T? {
        return try {
            gson.fromJson(String(body, Charsets.UTF_8), clazz)
        } catch (_: Exception) {
            null
        }
    }

    private fun readHttpRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        val rawTarget = parts[1]
        val pathPart = rawTarget.substringBefore('?')
        val queryPart = rawTarget.substringAfter('?', "")
        val query = parseQuery(queryPart)

        val headers = linkedMapOf<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) return null
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
        val body = if (contentLength > 0) readFixed(input, contentLength) else ByteArray(0)
        return HttpRequest(method, pathPart, query, headers, body)
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        val map = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            val v = if (idx >= 0) pair.substring(idx + 1) else ""
            val dk = runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
            val dv = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            map[dk] = dv
        }
        return map
    }

    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (baos.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("ISO-8859-1")
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val len = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.ISO_8859_1)
            }
            baos.write(b)
            prev = b
        }
        return null
    }

    private fun readFixed(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n == -1) break
            offset += n
        }
        return if (offset == length) buf else buf.copyOf(offset)
    }

    private fun writeJson(
        sock: Socket,
        status: Int,
        payload: Any,
        extraHeaders: List<String> = emptyList()
    ) {
        val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        writeResponse(sock, status, "application/json; charset=utf-8", bytes, extraHeaders)
    }

    private fun writeResponse(
        sock: Socket,
        status: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<String> = emptyList()
    ) {
        val reason = when (status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Connection: close\r\n")
            for (h in extraHeaders) {
                append(h).append("\r\n")
            }
            append("\r\n")
        }
        val out = sock.getOutputStream()
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }
}

/**
 * 前台服务：确保锁屏或切到后台时，局域网直连服务持续稳定可用，并在通知栏提供一键停止入口。
 */
class LanServerService : Service() {
    companion object {
        private const val CHANNEL_ID = "feiji_lan_server_channel"
        private const val NOTIFICATION_ID = 4108
        const val ACTION_START = "com.feiji.bulletnotes.action.LAN_START"
        const val ACTION_STOP = "com.feiji.bulletnotes.action.LAN_STOP"
        const val ACTION_REFRESH_PIN = "com.feiji.bulletnotes.action.LAN_REFRESH_PIN"

        fun startService(context: Context) {
            val intent = Intent(context, LanServerService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LanServerService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.stopService(intent) }
            LanWebServer.stop()
        }

        fun refreshPin(context: Context) {
            LanWebServer.refreshPin()
            val state = LanWebServer.state.value
            if (state.running) {
                val intent = Intent(context, LanServerService::class.java).apply {
                    action = ACTION_REFRESH_PIN
                }
                runCatching { context.startService(intent) }
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LanWebServer.stop()
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_PIN -> {
                val state = LanWebServer.state.value
                if (state.running) {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(state.primaryUrl, state.pin))
                }
                return START_NOT_STICKY
            }
        }

        ensureNotificationChannel()
        // Android 8+ 要求 startForegroundService 后尽快调用 startForeground
        val started = LanWebServer.start(applicationContext) {
            stopForegroundCompat()
            stopSelf()
        }
        val state = LanWebServer.state.value
        val notification = buildNotification(state.primaryUrl, state.pin.ifEmpty { "------" })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!started) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LanWebServer.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FeiJi:LanServerLock")?.apply {
                setReferenceCounted(false)
                // 安全兜底超时（60 分钟），服务停止时也会立即释放
                acquire(60 * 60_000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "局域网电脑直连",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持锁屏或后台时局域网直连写作可用"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(url: String, pin: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LanServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("飞记 · 电脑直连运行中")
            .setContentText("$url  配对码：$pin")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "停止直连", stopPending)
            .build()
    }
}
