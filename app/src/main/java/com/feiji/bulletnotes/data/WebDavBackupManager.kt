package com.feiji.bulletnotes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.util.Xml
import com.google.gson.Gson
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
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * WebDAV 静默每日备份与多设备近实时同步管理（默认坚果云，纯 Android 标准库实现，零额外第三方依赖）：
 * - 每日历史快照：每天备份一次昨天的数据备份（飞记备份 yyMMdd.json），成功后清理前天及以前文件
 * - 多设备近实时同步：每台设备维护独立的加密同步文件（feiji_sync_<deviceId>.json），互不覆盖写入，
 *   通过 PROPFIND ETag 缓存增量拉取，按 syncId + updatedAt（LWW）及永久删除墓碑双向无冲突合并
 */
object WebDavBackupManager {
    private const val TAG = "FeiJiWebDav"
    private const val PREFS = "feiji_bullet_notes_prefs"

    private const val KEY_SERVER_URL = "webdav_server_url_v1"
    private const val KEY_REMOTE_FOLDER = "webdav_remote_folder_v1"
    private const val KEY_USERNAME = "webdav_username_v1"
    private const val KEY_PASSWORD = "webdav_password_v1"
    private const val KEY_ENCRYPTION_PASSWORD = "webdav_encryption_password_v1"
    private const val KEY_LAST_SUCCESS_DATE = "webdav_last_success_date_v1"
    private const val KEY_SYNC_DEVICE_ID = "webdav_sync_device_id_v1"
    private const val KEY_WIFI_AUTO_SYNC_ENABLED = "wifi_auto_sync_enabled_v1"
    private const val KEY_SETTINGS_UPDATED_AT = "settings_updated_at_v1"
    private const val KEY_FEINOTE_URL_MIGRATED = "webdav_feinote_url_migrated_v1"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "feiji_backup_enc_pwd_key_v1"
    private const val KEYSTORE_VALUE_PREFIX = "ks1:"
    private const val KEYSTORE_GCM_TAG_BITS = 128
    private val keystoreLock = Any()

    const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/FeiNote/"
    private const val LEGACY_DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"
    private const val LEGACY_DEFAULT_REMOTE_FOLDER = "飞记"
    private const val LEGACY_MERGED_DEFAULT_URL = "https://dav.jianguoyun.com/dav/飞记/"
    private val ALERT_TIME: LocalTime = LocalTime.of(21, 0)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    private const val SYNC_FILE_PREFIX = "feiji_sync_"
    private const val SYNC_FILE_SUFFIX = ".json"
    private const val TOMBSTONE_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    private const val RATE_LIMIT_BACKOFF_MS = 3L * 60 * 1000

    private val gson = Gson()
    private val nameDate = DateTimeFormatter.ofPattern("yyMMdd")
    private val filePattern = Regex("""^飞记备份 (\d{6})(?:\.json)?$""")
    private val syncFilePattern = Regex("""^feiji_sync_([a-zA-Z0-9_-]+)\.json$""")

    private val peerFingerprints = ConcurrentHashMap<String, String>()
    @Volatile private var lastUploadedStateKey: String? = null
    @Volatile private var rateLimitBackoffUntilMs: Long = 0L

    data class SyncResult(
        val pulledChanges: Int = 0,
        val settingsChanged: Boolean = false,
        val uploaded: Boolean = false,
        val skipped: Boolean = false
    )

    private data class SyncPayload(
        val feiji_sync_version: Int = 2,
        val deviceId: String = "",
        val syncedAt: Long = 0L,
        val notes: List<NoteEntity>? = null,
        val tombstones: List<DeletedNoteTombstone>? = null,
        val settings: SyncMergeEngine.SyncableSettings? = null
    )

    private data class PropfindEntry(
        val href: String,
        val etag: String,
        val lastModified: String
    )

    data class WebDavConfig(
        val serverUrl: String = DEFAULT_SERVER_URL,
        val username: String = "",
        val password: String = "",
        val encryptionPassword: String = "",
        val wifiAutoSyncEnabled: Boolean = false
    ) {
        /** 当应用密码不为空时即启用 WebDAV 上传备份。 */
        val enabled: Boolean get() = password.isNotEmpty()

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

        fun parentEndpoint(): DavEndpoint? {
            val segments = encodedPath.split('/').filter { it.isNotEmpty() }
            if (segments.size <= 1) return null
            val parentPath = "/" + segments.dropLast(1).joinToString("/") + "/"
            return copy(encodedPath = parentPath)
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

    fun normalizeServerUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
        return trimmed.trimEnd('/') + "/"
    }

    /**
     * 兼容旧版分离的 serverUrl + remoteFolder：
     * - 仍为旧版默认坚果云地址 + 默认目录「飞记」时，统一升级为默认地址 https://dav.jianguoyun.com/dav/FeiNote/；
     * - 自定义服务器地址或自定义目录则自动合并为完整目录地址。
     */
    fun mergeLegacyServerUrl(
        rawUrl: String?,
        rawRemoteFolder: String?
    ): String {
        val url = rawUrl?.trim().orEmpty()
        val folder = rawRemoteFolder?.trim()?.trim('/').orEmpty()
        if (url.isEmpty() && folder.isEmpty()) return DEFAULT_SERVER_URL
        if (folder.isEmpty()) return normalizeServerUrl(url)

        val isLegacyDefaultUrl = url.isEmpty() ||
            url.trimEnd('/').equals(LEGACY_DEFAULT_SERVER_URL.trimEnd('/'), ignoreCase = true)
        if (isLegacyDefaultUrl && folder == LEGACY_DEFAULT_REMOTE_FOLDER) {
            return DEFAULT_SERVER_URL
        }

        val base = url.ifEmpty { LEGACY_DEFAULT_SERVER_URL }.trimEnd('/')
        val suffix = "/$folder"
        return if (base.endsWith(suffix)) {
            "$base/"
        } else {
            "$base$suffix/"
        }
    }

    fun getConfig(context: Context): WebDavConfig {
        val p = prefs(context)
        val encPwd = readProtectedSecret(context, KEY_ENCRYPTION_PASSWORD)
        val autoSyncRaw = p.getBoolean(KEY_WIFI_AUTO_SYNC_ENABLED, false)
        val username = p.getString(KEY_USERNAME, "") ?: ""
        val password = readProtectedSecret(context, KEY_PASSWORD)
        val storedUrl = p.getString(KEY_SERVER_URL, null)
        val storedFolder = p.getString(KEY_REMOTE_FOLDER, null)
        var effectiveUrl = mergeLegacyServerUrl(
            rawUrl = storedUrl,
            rawRemoteFolder = storedFolder
        )

        if (!p.getBoolean(KEY_FEINOTE_URL_MIGRATED, false)) {
            val migratedUrl = if (
                effectiveUrl.trimEnd('/').equals(LEGACY_MERGED_DEFAULT_URL.trimEnd('/'), ignoreCase = true) ||
                effectiveUrl.trimEnd('/').equals(LEGACY_DEFAULT_SERVER_URL.trimEnd('/'), ignoreCase = true)
            ) {
                DEFAULT_SERVER_URL
            } else {
                effectiveUrl
            }
            val editor = p.edit().putBoolean(KEY_FEINOTE_URL_MIGRATED, true)
            if (storedUrl != null || storedFolder != null) {
                editor.putString(KEY_SERVER_URL, migratedUrl).remove(KEY_REMOTE_FOLDER)
            }
            editor.apply()
            effectiveUrl = migratedUrl
        }

        return WebDavConfig(
            serverUrl = effectiveUrl,
            username = username,
            password = password,
            encryptionPassword = encPwd,
            wifiAutoSyncEnabled = autoSyncRaw && encPwd.isNotEmpty()
        )
    }

    fun getEncryptionPassword(context: Context): String =
        readProtectedSecret(context, KEY_ENCRYPTION_PASSWORD)

    fun getSettingsUpdatedAt(context: Context): Long =
        prefs(context).getLong(KEY_SETTINGS_UPDATED_AT, 0L)

    fun saveSettings(
        context: Context,
        serverUrl: String,
        username: String,
        password: String,
        encryptionPassword: String = "",
        wifiAutoSyncEnabled: Boolean = getConfig(context).wifiAutoSyncEnabled
    ): WebDavConfig {
        val current = getConfig(context)
        val candidateUrl = normalizeServerUrl(serverUrl)
        val normalizedUrl = if (isAllowedServerUrl(candidateUrl)) candidateUrl else current.serverUrl
        val effectiveAutoSync = wifiAutoSyncEnabled && encryptionPassword.isNotEmpty()
        val now = System.currentTimeMillis()
        val editor = prefs(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .remove(KEY_REMOTE_FOLDER)
            .putBoolean(KEY_FEINOTE_URL_MIGRATED, true)
            .putString(KEY_USERNAME, username.trim())
            .putBoolean(KEY_WIFI_AUTO_SYNC_ENABLED, effectiveAutoSync)
            .putLong(KEY_SETTINGS_UPDATED_AT, now)

        if (password.isEmpty()) {
            editor.remove(KEY_PASSWORD)
        } else {
            val encryptedDavPwd = encryptPasswordWithKeystore(password)
            if (encryptedDavPwd != null) {
                editor.putString(KEY_PASSWORD, encryptedDavPwd)
            } else {
                Log.e(TAG, "Keystore unavailable; refusing to store WebDAV password in plaintext")
                editor.remove(KEY_PASSWORD)
            }
        }

        if (encryptionPassword.isEmpty()) {
            editor.remove(KEY_ENCRYPTION_PASSWORD)
        } else {
            val encrypted = encryptPasswordWithKeystore(encryptionPassword)
            if (encrypted != null) {
                editor.putString(KEY_ENCRYPTION_PASSWORD, encrypted)
            } else {
                Log.e(TAG, "Keystore unavailable; refusing to store backup encryption password in plaintext")
                editor.remove(KEY_ENCRYPTION_PASSWORD)
                editor.putBoolean(KEY_WIFI_AUTO_SYNC_ENABLED, false)
            }
        }
        editor.commit()
        resetSyncCaches()
        return getConfig(context)
    }

    /**
     * 应用来自远端设备的同步设置（按 settingsUpdatedAt LWW 决策；加密密码两端已一致，保持本地不变）。
     * 若远端未携带 WebDAV 密码（例如未加密同步中已剥离凭证），同服务器同账号时保留本地密码。
     */
    fun applySyncedSettings(
        context: Context,
        serverUrl: String,
        remoteFolder: String = "",
        username: String,
        password: String,
        wifiAutoSyncEnabled: Boolean,
        remoteUpdatedAt: Long
    ): Boolean {
        val localUpdatedAt = getSettingsUpdatedAt(context)
        if (remoteUpdatedAt <= localUpdatedAt) return false

        val current = getConfig(context)
        val normalizedUsername = username.trim()
        val normalizedUrl = mergeLegacyServerUrl(
            rawUrl = serverUrl,
            rawRemoteFolder = remoteFolder
        )
        if (!isAllowedServerUrl(normalizedUrl)) return false

        val effectivePassword = if (
            password.isEmpty() &&
            current.password.isNotEmpty() &&
            normalizedUrl == current.serverUrl &&
            normalizedUsername == current.username
        ) {
            current.password
        } else {
            password
        }
        val effectiveAutoSync = wifiAutoSyncEnabled && current.encryptionPassword.isNotEmpty()

        val valueChanged = current.serverUrl != normalizedUrl ||
            current.username != normalizedUsername ||
            current.password != effectivePassword ||
            current.wifiAutoSyncEnabled != effectiveAutoSync

        val editor = prefs(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .remove(KEY_REMOTE_FOLDER)
            .putBoolean(KEY_FEINOTE_URL_MIGRATED, true)
            .putString(KEY_USERNAME, normalizedUsername)
            .putBoolean(KEY_WIFI_AUTO_SYNC_ENABLED, effectiveAutoSync)
            .putLong(KEY_SETTINGS_UPDATED_AT, remoteUpdatedAt)

        if (effectivePassword.isEmpty()) {
            editor.remove(KEY_PASSWORD)
        } else {
            val encryptedDavPwd = encryptPasswordWithKeystore(effectivePassword)
            if (encryptedDavPwd != null) {
                editor.putString(KEY_PASSWORD, encryptedDavPwd)
            } else {
                Log.e(TAG, "Keystore unavailable; refusing to store synced WebDAV password in plaintext")
                editor.remove(KEY_PASSWORD)
            }
        }
        editor.commit()

        if (valueChanged) {
            resetSyncCaches()
        }
        return valueChanged
    }

    fun resetSyncCaches() {
        peerFingerprints.clear()
        lastUploadedStateKey = null
        rateLimitBackoffUntilMs = 0L
    }

    fun getSyncDeviceId(context: Context): String = getOrCreateSyncDeviceId(context)

    private fun getOrCreateSyncDeviceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_SYNC_DEVICE_ID, null)?.trim()
        if (!existing.isNullOrEmpty() && existing.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return existing
        }
        val generated = UUID.randomUUID().toString().replace("-", "").take(12)
        p.edit().putString(KEY_SYNC_DEVICE_ID, generated).commit()
        return generated
    }

    fun restoreConfig(context: Context, config: WebDavConfig): WebDavConfig {
        return saveSettings(
            context = context,
            serverUrl = config.serverUrl,
            username = config.username,
            password = config.password,
            encryptionPassword = config.encryptionPassword,
            wifiAutoSyncEnabled = config.wifiAutoSyncEnabled
        )
    }

    private fun readProtectedSecret(context: Context, key: String): String {
        val p = prefs(context)
        val raw = p.getString(key, "") ?: ""
        if (raw.isEmpty()) return ""
        if (raw.startsWith(KEYSTORE_VALUE_PREFIX)) {
            return decryptPasswordWithKeystore(raw)
        }
        // 兼容旧版明文配置：读取后静默迁移到 AndroidKeyStore 加密存储。
        encryptPasswordWithKeystore(raw)?.let { migrated ->
            p.edit().putString(key, migrated).apply()
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

        val targetFolderEndpoint = parseBaseEndpoint(config.serverUrl) ?: return@withContext false
        val authHeader = buildBasicAuth(config.username.trim(), config.password)

        try {
            val folderEndpoint = ensureDirectoryEndpoint(targetFolderEndpoint, authHeader)
                ?: return@withContext false

            val yesterday = today.minusDays(1)
            val localYesterday = BackupManager.readBackupBytesForDate(context, yesterday)
            val fileName = localYesterday?.first ?: "飞记备份 ${yesterday.format(nameDate)}.json"
            val fileEndpoint = folderEndpoint.appendSegment(fileName, trailingSlash = false)

            // 上传前先检查云端是否已有昨日备份，防止多设备场景下用旧快照覆盖较新的云端昨日备份。
            var skipUploadBecauseRemoteUpToDate = false
            var finalPlainOrEncryptedBytes: ByteArray? = null

            val existingRes = executeRequest(
                method = "GET",
                endpoint = fileEndpoint,
                authHeader = authHeader
            )
            if (isRateLimited(existingRes.code)) {
                rateLimitBackoffUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                return@withContext false
            }

            if (existingRes.isSuccessful && existingRes.body.isNotEmpty()) {
                val remotePlainJson = BackupManager.decryptBackupBytesIfNeeded(
                    existingRes.body,
                    config.encryptionPassword
                )
                if (remotePlainJson == null) {
                    // 云端已存在无法用当前密码解密的同名备份，绝不盲目覆盖。
                    Log.w(TAG, "Remote daily backup exists but cannot be decrypted; refusing to overwrite")
                    return@withContext false
                }
                val remoteNotes = BackupManager.extractNotesFromPlainBackupJson(remotePlainJson)
                if (remoteNotes != null) {
                    val localNotes = localYesterday?.second
                        ?.let { BackupManager.decryptBackupBytesIfNeeded(it, config.encryptionPassword) }
                        ?.let { BackupManager.extractNotesFromPlainBackupJson(it) }
                        ?: noteDao.getAllNotesForBackup()

                    val merged = SyncMergeEngine.mergeForDailyBackup(
                        rawRemoteNotes = remoteNotes,
                        rawLocalNotes = localNotes
                    )
                    if (merged.remoteWasAlreadyUpToDate) {
                        skipUploadBecauseRemoteUpToDate = true
                    } else {
                        val mergedPlain = BackupManager.snapshotJson(context, merged.mergedNotes)
                            .toByteArray(Charsets.UTF_8)
                        finalPlainOrEncryptedBytes = if (config.encryptionPassword.isNotEmpty()) {
                            BackupManager.encryptBackupBytes(mergedPlain, config.encryptionPassword)
                        } else {
                            mergedPlain
                        }
                    }
                }
            }

            if (!skipUploadBecauseRemoteUpToDate) {
                val payloadBytes = finalPlainOrEncryptedBytes ?: run {
                    val rawPayloadBytes = localYesterday?.second
                        ?: BackupManager.snapshotJson(context, noteDao.getAllNotesForBackup())
                            .toByteArray(Charsets.UTF_8)
                    if (
                        config.encryptionPassword.isNotEmpty() &&
                        !BackupManager.isEncryptedBackupBytes(rawPayloadBytes)
                    ) {
                        BackupManager.encryptBackupBytes(rawPayloadBytes, config.encryptionPassword)
                    } else {
                        rawPayloadBytes
                    }
                }

                val putRes = executeRequest(
                    method = "PUT",
                    endpoint = fileEndpoint,
                    authHeader = authHeader,
                    contentType = "application/json; charset=utf-8",
                    body = payloadBytes
                )
                if (isRateLimited(putRes.code)) {
                    rateLimitBackoffUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                    return@withContext false
                }
                if (!putRes.isSuccessful) return@withContext false
            }

            // 标记今日备份成功，立即消除 21:00 后的感叹号提示。
            prefs(context).edit().putString(KEY_LAST_SUCCESS_DATE, today.toString()).commit()

            // 备份成功后，静默清理云端前天及以前（<= today - 2）的飞记备份（不触碰 feiji_sync_* 同步文件）。
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

    /**
     * 多设备静默近实时双向同步（基于每设备独立文件 feiji_sync_<deviceId>.json）：
     * 1. 先用单次 PROPFIND 获取目录内其他设备的同步文件及其 ETag/LastModified，仅在变化时下载解密；
     * 2. 持有数据库写锁并在非编辑态下，通过统一合并内核 SyncMergeEngine 执行无冲突合并；
     * 3. 仅当本机状态摘要变化或云端尚无本机同步文件时，才上传更新自己的 feiji_sync_<myDeviceId>.json。
     */
    suspend fun syncWithServer(
        context: Context,
        noteDao: NoteDao,
        withDbLock: suspend (suspend () -> Unit) -> Unit,
        isEditingProvider: () -> Boolean = { false }
    ): SyncResult = withContext(Dispatchers.IO) {
        val config = getConfig(context)
        if (!config.enabled || !config.isConfigured()) {
            return@withContext SyncResult(skipped = true)
        }
        if (isEditingProvider()) {
            return@withContext SyncResult(skipped = true)
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs < rateLimitBackoffUntilMs) {
            return@withContext SyncResult(skipped = true)
        }

        val folderEndpoint = parseBaseEndpoint(config.serverUrl)
            ?: return@withContext SyncResult(skipped = true)
        val authHeader = buildBasicAuth(config.username.trim(), config.password)

        try {
            val propfindXml =
                """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>"""
            var propRes = executeRequest(
                method = "PROPFIND",
                endpoint = folderEndpoint,
                authHeader = authHeader,
                extraHeaders = mapOf("Depth" to "1"),
                contentType = "application/xml; charset=utf-8",
                body = propfindXml.toByteArray(Charsets.UTF_8)
            )
            if (isRateLimited(propRes.code)) {
                rateLimitBackoffUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                return@withContext SyncResult(skipped = true)
            }
            if (propRes.code == 404 || propRes.code == 409) {
                ensureDirectoryEndpoint(folderEndpoint, authHeader)
                    ?: return@withContext SyncResult(skipped = true)
                propRes = executeRequest(
                    method = "PROPFIND",
                    endpoint = folderEndpoint,
                    authHeader = authHeader,
                    extraHeaders = mapOf("Depth" to "1"),
                    contentType = "application/xml; charset=utf-8",
                    body = propfindXml.toByteArray(Charsets.UTF_8)
                )
                if (isRateLimited(propRes.code)) {
                    rateLimitBackoffUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                    return@withContext SyncResult(skipped = true)
                }
            }
            if (!(propRes.isSuccessful || propRes.code == 207)) {
                return@withContext SyncResult(skipped = true)
            }

            val myDeviceId = getOrCreateSyncDeviceId(context)
            val myFileName = "$SYNC_FILE_PREFIX$myDeviceId$SYNC_FILE_SUFFIX"
            val encHash = sha256Hex(config.encryptionPassword.toByteArray(Charsets.UTF_8))
            val remoteConfigKey =
                "${normalizeServerUrl(config.serverUrl)}|${config.username.trim()}|$encHash"

            val entries = parseEntriesFromPropfind(String(propRes.body, Charsets.UTF_8))
            val folderPath = folderEndpoint.encodedPath.trimEnd('/')
            var myFileExistsOnServer = false

            data class DownloadedPeer(
                val cacheKey: String,
                val fingerprint: String,
                val notes: List<NoteEntity>,
                val tombstones: List<DeletedNoteTombstone>,
                val settings: SyncMergeEngine.SyncableSettings?
            )

            val downloadedPeers = mutableListOf<DownloadedPeer>()

            for (entry in entries) {
                val resolved = resolveEndpoint(folderEndpoint, entry.href.trim()) ?: continue
                val resolvedPath = resolved.encodedPath.trimEnd('/')
                if (resolvedPath == folderPath) continue

                val lastSegment = resolvedPath.split('/').lastOrNull { it.isNotEmpty() } ?: continue
                val fileName = decodeSegment(lastSegment)
                if (!syncFilePattern.matches(fileName)) continue

                if (fileName == myFileName) {
                    myFileExistsOnServer = true
                    continue
                }

                val fingerprint = listOf(entry.etag.trim(), entry.lastModified.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString("|")
                val cacheKey = "$remoteConfigKey|$fileName"
                if (fingerprint.isNotEmpty() && peerFingerprints[cacheKey] == fingerprint) {
                    continue
                }

                val getRes = executeRequest(
                    method = "GET",
                    endpoint = resolved,
                    authHeader = authHeader
                )
                if (isRateLimited(getRes.code)) {
                    rateLimitBackoffUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                    return@withContext SyncResult(skipped = true)
                }
                if (!getRes.isSuccessful || getRes.body.isEmpty()) continue

                // 解密失败（例如两端加密密码不一致）时不写入指纹缓存，待密码一致后自动重试。
                val jsonText = BackupManager.decryptBackupBytesIfNeeded(getRes.body, config.encryptionPassword)
                    ?: continue
                val payload = runCatching { gson.fromJson(jsonText, SyncPayload::class.java) }.getOrNull()
                    ?: continue

                val sanitizedNotes = SyncMergeEngine.sanitizeRemoteNotes(payload.notes.orEmpty())
                val sanitizedTombstones = SyncMergeEngine.sanitizeRemoteTombstones(payload.tombstones.orEmpty())
                val sanitizedSettings = SyncMergeEngine.sanitizeRemoteSettings(payload.settings)
                downloadedPeers.add(
                    DownloadedPeer(
                        cacheKey = cacheKey,
                        fingerprint = fingerprint,
                        notes = sanitizedNotes,
                        tombstones = sanitizedTombstones,
                        settings = sanitizedSettings
                    )
                )
            }

            var pulledChanges = 0
            var settingsChanged = false
            var skippedDueToEditing = false
            var bytesToUpload: ByteArray? = null
            var pendingUploadKey: String? = null

            withDbLock {
                // 若用户在网络拉取期间进入编辑态，立即暂缓写库且不更新指纹缓存，待退出编辑后自动补齐。
                if (isEditingProvider()) {
                    skippedDueToEditing = true
                    return@withDbLock
                }

                val mergeNow = System.currentTimeMillis()
                val tombstoneCutoff = mergeNow - TOMBSTONE_RETENTION_MS

                if (downloadedPeers.isNotEmpty()) {
                    val mergeRes = SyncMergeEngine.mergeIntoLocalDb(
                        context = context,
                        noteDao = noteDao,
                        remoteNoteLists = downloadedPeers.map { it.notes },
                        remoteTombstoneLists = downloadedPeers.map { it.tombstones },
                        remoteSettingsList = downloadedPeers.mapNotNull { it.settings }
                    )
                    pulledChanges = mergeRes.changedNoteCount
                    settingsChanged = mergeRes.settingsChanged

                    for (peer in downloadedPeers) {
                        if (peer.fingerprint.isNotEmpty()) {
                            peerFingerprints[peer.cacheKey] = peer.fingerprint
                        }
                    }
                }

                val finalNotes = noteDao.getAllNotesForBackup()
                val finalTombstones = noteDao.getAllTombstones().filter { it.deletedAt >= tombstoneCutoff }
                val latestEncPwd = getEncryptionPassword(context)
                // 未启用加密密码时绝不将 WebDAV 应用密码以明文写入云端同步文件。
                val finalSettings = SyncMergeEngine.readLocalSyncableSettings(
                    context = context,
                    includeCredentials = latestEncPwd.isNotEmpty()
                )
                val stateHash = SyncMergeEngine.computeFullStateHash(finalNotes, finalTombstones, finalSettings)
                val uploadKey = "$remoteConfigKey|$stateHash"
                val hasDataOrTombstones = finalNotes.isNotEmpty() || finalTombstones.isNotEmpty()
                val needsUpload = (!myFileExistsOnServer && hasDataOrTombstones) ||
                    (myFileExistsOnServer && lastUploadedStateKey != uploadKey) ||
                    (lastUploadedStateKey != null && lastUploadedStateKey != uploadKey)

                if (needsUpload) {
                    val payload = SyncPayload(
                        feiji_sync_version = 2,
                        deviceId = myDeviceId,
                        syncedAt = mergeNow,
                        notes = finalNotes.sortedBy { SyncMergeEngine.normalizeSyncId(it) },
                        tombstones = finalTombstones.sortedBy { it.syncId },
                        settings = finalSettings
                    )
                    val rawBytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
                    bytesToUpload = if (latestEncPwd.isNotEmpty()) {
                        BackupManager.encryptBackupBytes(rawBytes, latestEncPwd)
                    } else {
                        rawBytes
                    }
                    pendingUploadKey = uploadKey
                }
            }

            if (skippedDueToEditing) {
                return@withContext SyncResult(skipped = true)
            }

            var uploaded = false
            val uploadBody = bytesToUpload
            if (uploadBody != null) {
                val myFileEndpoint = folderEndpoint.appendSegment(myFileName, trailingSlash = false)
                val putRes = executeRequest(
                    method = "PUT",
                    endpoint = myFileEndpoint,
                    authHeader = authHeader,
                    contentType = "application/json; charset=utf-8",
                    body = uploadBody
                )
                if (isRateLimited(putRes.code)) {
                    rateLimitBackoffUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                } else if (putRes.isSuccessful) {
                    lastUploadedStateKey = pendingUploadKey
                    uploaded = true
                }
            }

            SyncResult(
                pulledChanges = pulledChanges,
                settingsChanged = settingsChanged,
                uploaded = uploaded
            )
        } catch (e: Exception) {
            Log.w(TAG, "Silent WebDAV sync failed", e)
            SyncResult(skipped = true)
        }
    }

    private fun isRateLimited(statusCode: Int): Boolean = statusCode == 429 || statusCode == 503

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    private fun buildBasicAuth(username: String, password: String): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(raw, Base64.NO_WRAP)
        return "Basic $encoded"
    }

    /**
     * 校验 WebDAV 服务器地址是否符合传输安全要求：
     * - 公网域名/IP 强制要求 https:// 加密传输，避免账号密码在明文 HTTP 中泄露；
     * - 仅对本机或内网私有网段（如家庭 NAS）允许 http://。
     */
    fun isAllowedServerUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return true
        return parseBaseEndpoint(trimmed) != null
    }

    private fun isLocalOrPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 127 ||
            a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    private fun parseBaseEndpoint(rawUrl: String): DavEndpoint? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val url = URL(if (trimmed.endsWith("/")) trimmed else "$trimmed/")
            val scheme = url.protocol.lowercase()
            val host = url.host
            if (host.isNullOrBlank()) return null
            if (scheme != "https" && !(scheme == "http" && isLocalOrPrivateHost(host))) {
                return null
            }
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

    /**
     * 确保目标远程目录存在（自底向上按需创建，避免对坚果云 /dav/ 等挂载根目录发起多余 MKCOL）：
     * - 目录已存在（2xx / 405 / 301 / 302）直接视为成功；
     * - 返回 409（父目录缺失）时先递归创建父目录再重试一次；
     * - 返回 403 时用 PROPFIND(Depth:0) 校验目标目录是否本身已存在且可访问（兼容挂载根目录）。
     */
    private fun ensureDirectoryEndpoint(
        targetEndpoint: DavEndpoint,
        authHeader: String,
        depthLimit: Int = 8
    ): DavEndpoint? {
        if (targetEndpoint.encodedPath == "/") return targetEndpoint
        val res = executeRequest(
            method = "MKCOL",
            endpoint = targetEndpoint,
            authHeader = authHeader,
            contentType = null,
            body = ByteArray(0),
            followRedirects = false
        )
        if (res.isSuccessful || res.code == 405 || res.code == 301 || res.code == 302) {
            return targetEndpoint
        }
        if (res.code == 403) {
            val probe = executeRequest(
                method = "PROPFIND",
                endpoint = targetEndpoint,
                authHeader = authHeader,
                extraHeaders = mapOf("Depth" to "0"),
                contentType = "application/xml; charset=utf-8",
                body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
                    .toByteArray(Charsets.UTF_8)
            )
            return if (probe.isSuccessful || probe.code == 207) targetEndpoint else null
        }
        if (res.code == 409 && depthLimit > 0) {
            val parent = targetEndpoint.parentEndpoint() ?: return null
            ensureDirectoryEndpoint(parent, authHeader, depthLimit - 1) ?: return null
            val retry = executeRequest(
                method = "MKCOL",
                endpoint = targetEndpoint,
                authHeader = authHeader,
                contentType = null,
                body = ByteArray(0),
                followRedirects = false
            )
            if (retry.isSuccessful || retry.code == 405 || retry.code == 301 || retry.code == 302) {
                return targetEndpoint
            }
        }
        return null
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
            val host = resolvedUri.host ?: base.host
            if (base.scheme == "https" && scheme != "https") return null
            if (scheme != "https" && !(scheme == "http" && isLocalOrPrivateHost(host))) return null
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

    private fun parseHrefsFromPropfind(xmlText: String): List<String> =
        parseEntriesFromPropfind(xmlText).map { it.href }

    private fun parseEntriesFromPropfind(xmlText: String): List<PropfindEntry> {
        if (xmlText.isBlank()) return emptyList()
        val result = mutableListOf<PropfindEntry>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xmlText))
        var inResponse = false
        var currentHref = ""
        var currentEtag = ""
        var currentLastModified = ""
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tagName = parser.name ?: ""
                    when {
                        tagName.equals("response", ignoreCase = true) -> {
                            inResponse = true
                            currentHref = ""
                            currentEtag = ""
                            currentLastModified = ""
                        }
                        inResponse && tagName.equals("href", ignoreCase = true) -> {
                            val href = runCatching { parser.nextText() }.getOrNull().orEmpty().trim()
                            if (href.isNotEmpty() && currentHref.isEmpty()) {
                                currentHref = href
                            }
                        }
                        inResponse && tagName.equals("getetag", ignoreCase = true) -> {
                            val etag = runCatching { parser.nextText() }.getOrNull().orEmpty().trim()
                            if (etag.isNotEmpty()) {
                                currentEtag = etag
                            }
                        }
                        inResponse && tagName.equals("getlastmodified", ignoreCase = true) -> {
                            val lm = runCatching { parser.nextText() }.getOrNull().orEmpty().trim()
                            if (lm.isNotEmpty()) {
                                currentLastModified = lm
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("response", ignoreCase = true)) {
                        if (currentHref.isNotEmpty()) {
                            result.add(
                                PropfindEntry(
                                    href = currentHref,
                                    etag = currentEtag,
                                    lastModified = currentLastModified
                                )
                            )
                        }
                        inResponse = false
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }
}
