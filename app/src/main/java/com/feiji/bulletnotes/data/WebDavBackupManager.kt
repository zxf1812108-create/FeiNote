package com.feiji.bulletnotes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * WebDAV 静默每日备份管理（默认坚果云，纯 Android 标准库实现，零额外第三方依赖）：
 * - 每天备份一次昨天的数据备份（飞记备份 yyMMdd.json）
 * - 备份全程静默无提示；仅当开启功能且 21:00 后仍未备份成功时显示顶栏感叹号
 * - 备份成功后删除云端前天及以前（<= today - 2 days）的飞记备份文件
 */
object WebDavBackupManager {
    private const val TAG = "FeiJiWebDav"
    private const val PREFS = "feiji_bullet_notes_prefs"

    private const val KEY_ENABLED = "webdav_enabled_v1"
    private const val KEY_SERVER_URL = "webdav_server_url_v1"
    private const val KEY_REMOTE_FOLDER = "webdav_remote_folder_v1"
    private const val KEY_USERNAME = "webdav_username_v1"
    private const val KEY_PASSWORD = "webdav_password_v1"
    private const val KEY_ENCRYPTION_PASSWORD = "webdav_encryption_password_v1"
    private const val KEY_LAST_SUCCESS_DATE = "webdav_last_success_date_v1"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "feiji_backup_enc_pwd_key_v1"
    private const val KEYSTORE_VALUE_PREFIX = "ks1:"
    private const val KEYSTORE_GCM_TAG_BITS = 128
    private val keystoreLock = Any()

    const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"
    const val DEFAULT_REMOTE_FOLDER = "飞记"
    private val ALERT_TIME: LocalTime = LocalTime.of(21, 0)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    private val nameDate = DateTimeFormatter.ofPattern("yyMMdd")
    private val filePattern = Regex("""^飞记备份 (\d{6})(?:\.json)?$""")

    data class WebDavConfig(
        val enabled: Boolean = false,
        val serverUrl: String = DEFAULT_SERVER_URL,
        val remoteFolder: String = DEFAULT_REMOTE_FOLDER,
        val username: String = "",
        val password: String = "",
        val encryptionPassword: String = ""
    ) {
        fun isConfigured(): Boolean =
            serverUrl.trim().isNotEmpty() &&
                username.trim().isNotEmpty() &&
                password.isNotEmpty()
    }

    private data class DavEndpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val encodedPath: String
    ) {
        fun hostHeader(): String {
            val isDefaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
            return if (isDefaultPort) host else "$host:$port"
        }

        fun appendSegment(segment: String, trailingSlash: Boolean): DavEndpoint {
            val base = if (encodedPath.endsWith("/")) encodedPath else "$encodedPath/"
            val encodedSeg = encodePathSegment(segment)
            val nextPath = if (trailingSlash) "$base$encodedSeg/" else "$base$encodedSeg"
            return copy(encodedPath = nextPath)
        }

        fun toUrlString(): String = "$scheme://${hostHeader()}$encodedPath"
    }

    private data class HttpResult(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getConfig(context: Context): WebDavConfig {
        val p = prefs(context)
        return WebDavConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            serverUrl = p.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
            remoteFolder = p.getString(KEY_REMOTE_FOLDER, DEFAULT_REMOTE_FOLDER) ?: DEFAULT_REMOTE_FOLDER,
            username = p.getString(KEY_USERNAME, "") ?: "",
            password = p.getString(KEY_PASSWORD, "") ?: "",
            encryptionPassword = readEncryptionPassword(context)
        )
    }

    fun setEnabled(context: Context, enabled: Boolean): WebDavConfig {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        return getConfig(context)
    }

    fun saveSettings(
        context: Context,
        serverUrl: String,
        remoteFolder: String,
        username: String,
        password: String,
        encryptionPassword: String = ""
    ): WebDavConfig {
        val normalizedUrl = serverUrl.trim().ifEmpty { DEFAULT_SERVER_URL }
        val normalizedFolder = remoteFolder.trim().trim('/')
        val editor = prefs(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_REMOTE_FOLDER, normalizedFolder)
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)

        if (encryptionPassword.isEmpty()) {
            editor.remove(KEY_ENCRYPTION_PASSWORD)
        } else {
            val encrypted = encryptPasswordWithKeystore(encryptionPassword)
            if (encrypted != null) {
                editor.putString(KEY_ENCRYPTION_PASSWORD, encrypted)
            } else {
                Log.e(TAG, "Keystore unavailable; refusing to store backup encryption password in plaintext")
                editor.remove(KEY_ENCRYPTION_PASSWORD)
            }
        }
        editor.commit()
        return getConfig(context)
    }

    private fun readEncryptionPassword(context: Context): String {
        val p = prefs(context)
        val raw = p.getString(KEY_ENCRYPTION_PASSWORD, "") ?: ""
        if (raw.isEmpty()) return ""
        if (raw.startsWith(KEYSTORE_VALUE_PREFIX)) {
            return decryptPasswordWithKeystore(raw)
        }
        // 兼容旧版明文配置：读取后静默迁移到 AndroidKeyStore 加密存储。
        encryptPasswordWithKeystore(raw)?.let { migrated ->
            p.edit().putString(KEY_ENCRYPTION_PASSWORD, migrated).apply()
        }
        return raw
    }

    private fun getOrCreateKeystoreKey(forceRecreate: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (forceRecreate && keyStore.containsAlias(KEYSTORE_ALIAS)) {
            runCatching { keyStore.deleteEntry(KEYSTORE_ALIAS) }
        }
        val existing = (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptPasswordWithKeystore(plain: String): String? = synchronized(keystoreLock) {
        fun doEncrypt(forceRecreate: Boolean): String {
            val key = getOrCreateKeystoreKey(forceRecreate)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            return "$KEYSTORE_VALUE_PREFIX$ivB64:$ctB64"
        }

        try {
            doEncrypt(forceRecreate = false)
        } catch (first: Exception) {
            Log.w(TAG, "Keystore encrypt failed, recreating key and retrying", first)
            try {
                doEncrypt(forceRecreate = true)
            } catch (second: Exception) {
                Log.w(TAG, "Keystore encrypt retry failed", second)
                null
            }
        }
    }

    private fun decryptPasswordWithKeystore(stored: String): String = synchronized(keystoreLock) {
        try {
            val payload = stored.removePrefix(KEYSTORE_VALUE_PREFIX)
            val sep = payload.indexOf(':')
            if (sep <= 0 || sep >= payload.length - 1) return ""
            val iv = Base64.decode(payload.substring(0, sep), Base64.NO_WRAP)
            val ciphertext = Base64.decode(payload.substring(sep + 1), Base64.NO_WRAP)
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val key = (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(KEYSTORE_GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt backup encryption password from Keystore", e)
            ""
        }
    }

    fun isTodayBackupCompleted(
        context: Context,
        today: LocalDate = LocalDate.now()
    ): Boolean {
        return prefs(context).getString(KEY_LAST_SUCCESS_DATE, null) == today.toString()
    }

    fun shouldShowAlert(
        context: Context,
        nowDate: LocalDate = LocalDate.now(),
        nowTime: LocalTime = LocalTime.now()
    ): Boolean {
        val config = getConfig(context)
        if (!config.enabled) return false
        if (nowTime.isBefore(ALERT_TIME)) return false
        return !isTodayBackupCompleted(context, nowDate)
    }

    /**
     * 每天静默备份一次昨天的数据备份；成功后删除云端前天及以前的备份文件。
     * @return 今日 WebDAV 备份是否已处于完成状态
     */
    suspend fun backupYesterdayIfNeeded(
        context: Context,
        noteDao: NoteDao
    ): Boolean = withContext(Dispatchers.IO) {
        val config = getConfig(context)
        if (!config.enabled) return@withContext false

        val today = LocalDate.now()
        if (isTodayBackupCompleted(context, today)) return@withContext true
        if (!config.isConfigured()) return@withContext false

        val baseEndpoint = parseBaseEndpoint(config.serverUrl) ?: return@withContext false
        val authHeader = buildBasicAuth(config.username.trim(), config.password)

        try {
            val folderEndpoint = ensureRemoteFolder(baseEndpoint, config.remoteFolder, authHeader)
                ?: return@withContext false

            val yesterday = today.minusDays(1)
            val localYesterday = BackupManager.readBackupBytesForDate(context, yesterday)
            val fileName = localYesterday?.first ?: "飞记备份 ${yesterday.format(nameDate)}.json"
            val rawPayloadBytes = localYesterday?.second
                ?: BackupManager.snapshotJson(noteDao.getAllNotesForBackup()).toByteArray(Charsets.UTF_8)

            // 设置了加密密码时，上传前自动加密（若文件已加密则不重复加密），文件名沿用现有规则。
            val payloadBytes = if (
                config.encryptionPassword.isNotEmpty() &&
                !BackupManager.isEncryptedBackupBytes(rawPayloadBytes)
            ) {
                BackupManager.encryptBackupBytes(rawPayloadBytes, config.encryptionPassword)
            } else {
                rawPayloadBytes
            }

            val fileEndpoint = folderEndpoint.appendSegment(fileName, trailingSlash = false)
            val putRes = executeRequest(
                method = "PUT",
                endpoint = fileEndpoint,
                authHeader = authHeader,
                contentType = "application/json; charset=utf-8",
                body = payloadBytes
            )
            if (!putRes.isSuccessful) return@withContext false

            // 标记今日备份成功，立即消除 21:00 后的感叹号提示。
            prefs(context).edit().putString(KEY_LAST_SUCCESS_DATE, today.toString()).commit()

            // 备份成功后，静默清理云端前天及以前（<= today - 2）的飞记备份。
            runCatching {
                deleteOldBackupsOnServer(folderEndpoint, authHeader, today.minusDays(2))
            }.onFailure { e ->
                Log.w(TAG, "Cleanup old WebDAV backups failed", e)
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Silent WebDAV backup failed", e)
            false
        }
    }

    private fun buildBasicAuth(username: String, password: String): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(raw, Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun parseBaseEndpoint(rawUrl: String): DavEndpoint? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val url = URL(if (trimmed.endsWith("/")) trimmed else "$trimmed/")
            val scheme = url.protocol.lowercase()
            if (scheme != "https" && scheme != "http") return null
            val host = url.host
            if (host.isNullOrBlank()) return null
            val port = if (url.port > 0) url.port else if (scheme == "https") 443 else 80
            val rawPath = url.path.ifEmpty { "/" }
            val normalizedPath = buildString {
                append('/')
                rawPath.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                    append(encodeAlreadyOrRawSegment(seg))
                    append('/')
                }
            }
            DavEndpoint(scheme, host, port, normalizedPath)
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeAlreadyOrRawSegment(segment: String): String {
        val decoded = decodeSegment(segment)
        return encodePathSegment(decoded)
    }

    private fun encodePathSegment(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val isUnreserved = (c in 'A'.code..'Z'.code) ||
                (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c ushr 4])
                sb.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun decodeSegment(encoded: String): String {
        return try {
            URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            encoded
        }
    }

    /** 逐级确保远程备份目录存在；目录已存在（405/301/302/2xx）视为成功。 */
    private fun ensureRemoteFolder(
        baseEndpoint: DavEndpoint,
        remoteFolder: String,
        authHeader: String
    ): DavEndpoint? {
        val segments = remoteFolder.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        var current = baseEndpoint
        for (segment in segments) {
            val next = current.appendSegment(segment, trailingSlash = true)
            val res = executeRequest(
                method = "MKCOL",
                endpoint = next,
                authHeader = authHeader,
                contentType = null,
                body = ByteArray(0),
                followRedirects = false
            )
            val ok = res.isSuccessful || res.code == 405 || res.code == 301 || res.code == 302
            if (!ok) return null
            current = next
        }
        return current
    }

    private fun backupDate(name: String): LocalDate? {
        val match = filePattern.matchEntire(name) ?: return null
        return try {
            LocalDate.parse(match.groupValues[1], nameDate)
        } catch (_: Exception) {
            null
        }
    }

    /** 使用 PROPFIND 列出目录中的飞记备份，删除日期 <= cutoffDate（前天及以前）的文件。 */
    private fun deleteOldBackupsOnServer(
        folderEndpoint: DavEndpoint,
        authHeader: String,
        cutoffDate: LocalDate
    ) {
        val propfindXml =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val res = executeRequest(
            method = "PROPFIND",
            endpoint = folderEndpoint,
            authHeader = authHeader,
            extraHeaders = mapOf("Depth" to "1"),
            contentType = "application/xml; charset=utf-8",
            body = propfindXml.toByteArray(Charsets.UTF_8)
        )

        val hrefs: List<String>? = if (res.isSuccessful || res.code == 207) {
            parseHrefsFromPropfind(String(res.body, Charsets.UTF_8))
        } else {
            null
        }

        if (hrefs != null) {
            val folderPath = folderEndpoint.encodedPath.trimEnd('/')
            for (rawHref in hrefs) {
                val resolved = resolveEndpoint(folderEndpoint, rawHref.trim()) ?: continue
                val resolvedPath = resolved.encodedPath.trimEnd('/')
                if (resolvedPath == folderPath) continue

                val lastSegment = resolvedPath.split('/').lastOrNull { it.isNotEmpty() } ?: continue
                val fileName = decodeSegment(lastSegment)
                val fileDate = backupDate(fileName) ?: continue

                if (!fileDate.isAfter(cutoffDate)) {
                    deleteRemoteFile(resolved, authHeader)
                }
            }
        } else {
            // 兜底：若服务端不支持 PROPFIND，至少尝试删除前天的备份文件。
            val fallbackName = "飞记备份 ${cutoffDate.format(nameDate)}.json"
            val fallbackEndpoint = folderEndpoint.appendSegment(fallbackName, trailingSlash = false)
            deleteRemoteFile(fallbackEndpoint, authHeader)
        }
    }

    private fun resolveEndpoint(base: DavEndpoint, rawHref: String): DavEndpoint? {
        if (rawHref.isEmpty()) return null
        return try {
            val safeHref = rawHref.replace(" ", "%20")
            val resolvedUri = URI(base.toUrlString()).resolve(safeHref)
            val scheme = (resolvedUri.scheme ?: base.scheme).lowercase()
            if (scheme != "https" && scheme != "http") return null
            val host = resolvedUri.host ?: base.host
            val port = if (resolvedUri.port > 0) resolvedUri.port else if (scheme == "https") 443 else 80
            val rawPath = resolvedUri.rawPath?.takeIf { it.isNotEmpty() } ?: return null
            DavEndpoint(scheme, host, port, rawPath)
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteRemoteFile(endpoint: DavEndpoint, authHeader: String) {
        runCatching {
            executeRequest(
                method = "DELETE",
                endpoint = endpoint,
                authHeader = authHeader,
                contentType = null,
                body = null
            )
        }
    }

    private fun executeRequest(
        method: String,
        endpoint: DavEndpoint,
        authHeader: String,
        extraHeaders: Map<String, String> = emptyMap(),
        contentType: String? = null,
        body: ByteArray? = null,
        followRedirects: Boolean = true,
        redirectsLeft: Int = 3
    ): HttpResult {
        val result = executeSingleRequest(method, endpoint, authHeader, extraHeaders, contentType, body)
        if (followRedirects && redirectsLeft > 0 && result.code in setOf(301, 302, 307, 308)) {
            val location = result.headers["location"] ?: return result
            val nextEndpoint = resolveEndpoint(endpoint, location) ?: return result
            val sameHost = nextEndpoint.host.equals(endpoint.host, ignoreCase = true) &&
                nextEndpoint.scheme == endpoint.scheme
            if (!sameHost) return result
            return executeRequest(
                method = method,
                endpoint = nextEndpoint,
                authHeader = authHeader,
                extraHeaders = extraHeaders,
                contentType = contentType,
                body = body,
                followRedirects = true,
                redirectsLeft = redirectsLeft - 1
            )
        }
        return result
    }

    private fun executeSingleRequest(
        method: String,
        endpoint: DavEndpoint,
        authHeader: String,
        extraHeaders: Map<String, String>,
        contentType: String?,
        body: ByteArray?
    ): HttpResult {
        val socket = openSocket(endpoint)
        socket.use { sock ->
            val headerBlock = buildString {
                append(method).append(' ').append(endpoint.encodedPath).append(" HTTP/1.1\r\n")
                append("Host: ").append(endpoint.hostHeader()).append("\r\n")
                append("Authorization: ").append(authHeader).append("\r\n")
                append("User-Agent: FeiNote/1.4\r\n")
                append("Connection: close\r\n")
                for ((k, v) in extraHeaders) {
                    append(k).append(": ").append(v).append("\r\n")
                }
                if (contentType != null) {
                    append("Content-Type: ").append(contentType).append("\r\n")
                }
                if (body != null) {
                    append("Content-Length: ").append(body.size).append("\r\n")
                }
                append("\r\n")
            }

            val out = sock.getOutputStream()
            out.write(headerBlock.toByteArray(Charsets.ISO_8859_1))
            if (body != null && body.isNotEmpty()) {
                out.write(body)
            }
            out.flush()

            val input = BufferedInputStream(sock.getInputStream())
            val statusLine = readHeaderLine(input) ?: error("Empty HTTP response")
            val statusParts = statusLine.split(' ', limit = 3)
            val statusCode = statusParts.getOrNull(1)?.toIntOrNull() ?: error("Invalid status line: $statusLine")

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readHeaderLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val responseBody = when {
                statusCode == 204 || statusCode == 304 || method.equals("HEAD", ignoreCase = true) -> ByteArray(0)
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunkedBody(input)
                headers["content-length"]?.toIntOrNull() != null -> {
                    val len = headers["content-length"]!!.toInt().coerceIn(0, MAX_RESPONSE_BYTES)
                    readFixedBody(input, len)
                }
                else -> readUntilEof(input)
            }

            return HttpResult(statusCode, headers, responseBody)
        }
    }

    private fun openSocket(endpoint: DavEndpoint): Socket {
        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
        rawSocket.soTimeout = READ_TIMEOUT_MS
        if (endpoint.scheme == "https") {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(rawSocket, endpoint.host, endpoint.port, true) as SSLSocket
            sslSocket.soTimeout = READ_TIMEOUT_MS
            sslSocket.startHandshake()
            val hostnameValid = HttpsURLConnection.getDefaultHostnameVerifier()
                .verify(endpoint.host, sslSocket.session)
            if (!hostnameValid) {
                sslSocket.close()
                throw javax.net.ssl.SSLPeerUnverifiedException("Hostname verification failed for ${endpoint.host}")
            }
            return sslSocket
        }
        return rawSocket
    }

    private fun readHeaderLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
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
    }

    private fun readFixedBody(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == length) result else result.copyOf(offset)
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readHeaderLine(input) ?: break
            val hexSize = sizeLine.substringBefore(';').trim()
            val chunkSize = hexSize.toIntOrNull(16) ?: break
            if (chunkSize == 0) {
                // Consume trailing headers until empty line
                while (true) {
                    val trailer = readHeaderLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            var remaining = chunkSize
            val buf = ByteArray(minOf(chunkSize, 8192))
            while (remaining > 0) {
                val read = input.read(buf, 0, minOf(remaining, buf.size))
                if (read == -1) break
                if (baos.size() < MAX_RESPONSE_BYTES) {
                    val toWrite = minOf(read, MAX_RESPONSE_BYTES - baos.size())
                    baos.write(buf, 0, toWrite)
                }
                remaining -= read
            }
            // Consume CRLF after chunk data
            readHeaderLine(input)
        }
        return baos.toByteArray()
    }

    private fun readUntilEof(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (baos.size() < MAX_RESPONSE_BYTES) {
            val read = input.read(buf)
            if (read == -1) break
            val toWrite = minOf(read, MAX_RESPONSE_BYTES - baos.size())
            baos.write(buf, 0, toWrite)
        }
        return baos.toByteArray()
    }

    private fun parseHrefsFromPropfind(xmlText: String): List<String> {
        if (xmlText.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xmlText))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name.equals("href", ignoreCase = true)) {
                val href = runCatching { parser.nextText() }.getOrNull()
                if (!href.isNullOrBlank()) {
                    result.add(href)
                }
            }
            eventType = parser.next()
        }
        return result
    }
}
