package com.feiji.bulletnotes.data

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * WebDAV 静默每日备份管理（默认坚果云）：
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
    private const val KEY_LAST_SUCCESS_DATE = "webdav_last_success_date_v1"

    const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"
    const val DEFAULT_REMOTE_FOLDER = "飞记"
    private val ALERT_TIME: LocalTime = LocalTime.of(21, 0)

    private val nameDate = DateTimeFormatter.ofPattern("yyMMdd")
    private val filePattern = Regex("""^飞记备份 (\d{6})(?:\.json)?$""")
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val xmlMediaType = "application/xml; charset=utf-8".toMediaType()
    private val emptyBody = ByteArray(0).toRequestBody(null)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    data class WebDavConfig(
        val enabled: Boolean = false,
        val serverUrl: String = DEFAULT_SERVER_URL,
        val remoteFolder: String = DEFAULT_REMOTE_FOLDER,
        val username: String = "",
        val password: String = ""
    ) {
        fun isConfigured(): Boolean =
            serverUrl.trim().isNotEmpty() &&
                username.trim().isNotEmpty() &&
                password.isNotEmpty()
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
            password = p.getString(KEY_PASSWORD, "") ?: ""
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
        password: String
    ): WebDavConfig {
        val normalizedUrl = serverUrl.trim().ifEmpty { DEFAULT_SERVER_URL }
        val normalizedFolder = remoteFolder.trim().trim('/')
        prefs(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_REMOTE_FOLDER, normalizedFolder)
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .commit()
        return getConfig(context)
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

        val baseUrl = normalizeBaseUrl(config.serverUrl) ?: return@withContext false
        val authHeader = Credentials.basic(config.username.trim(), config.password, Charsets.UTF_8)

        try {
            val folderUrl = ensureRemoteFolder(baseUrl, config.remoteFolder, authHeader)
                ?: return@withContext false

            val yesterday = today.minusDays(1)
            val localYesterday = BackupManager.readBackupBytesForDate(context, yesterday)
            val fileName = localYesterday?.first ?: "飞记备份 ${yesterday.format(nameDate)}.json"
            val payloadBytes = localYesterday?.second
                ?: BackupManager.snapshotJson(noteDao.getAllNotesForBackup()).toByteArray(Charsets.UTF_8)

            val fileUrl = folderUrl.newBuilder()
                .addPathSegment(fileName)
                .build()

            val putRequest = Request.Builder()
                .url(fileUrl)
                .header("Authorization", authHeader)
                .put(payloadBytes.toRequestBody(jsonMediaType))
                .build()

            val uploadSucceeded = httpClient.newCall(putRequest).execute().use { response ->
                response.isSuccessful
            }

            if (!uploadSucceeded) return@withContext false

            // 标记今日备份成功，立即消除 21:00 后的感叹号提示。
            prefs(context).edit().putString(KEY_LAST_SUCCESS_DATE, today.toString()).commit()

            // 备份成功后，静默清理云端前天及以前（<= today - 2）的飞记备份。
            runCatching {
                deleteOldBackupsOnServer(folderUrl, authHeader, today.minusDays(2))
            }.onFailure { e ->
                Log.w(TAG, "Cleanup old WebDAV backups failed", e)
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Silent WebDAV backup failed", e)
            false
        }
    }

    private fun normalizeBaseUrl(rawUrl: String): HttpUrl? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val withTrailingSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        return withTrailingSlash.toHttpUrlOrNull()
    }

    /** 逐级确保远程备份目录存在；目录已存在（405/301/2xx）视为成功。 */
    private fun ensureRemoteFolder(
        baseUrl: HttpUrl,
        remoteFolder: String,
        authHeader: String
    ): HttpUrl? {
        val segments = remoteFolder.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        var current = baseUrl
        for (segment in segments) {
            val nextUrl = current.newBuilder()
                .addPathSegment(segment)
                .addPathSegment("") // trailing slash for collection
                .build()

            val mkcolRequest = Request.Builder()
                .url(nextUrl)
                .header("Authorization", authHeader)
                .method("MKCOL", emptyBody)
                .build()

            val ok = httpClient.newCall(mkcolRequest).execute().use { response ->
                response.isSuccessful || response.code == 405 || response.code == 301 || response.code == 302
            }
            if (!ok) return null
            current = nextUrl
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

    private fun decodeSegment(encoded: String): String {
        return try {
            URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            encoded
        }
    }

    /** 使用 PROPFIND 列出目录中的飞记备份，删除日期 <= cutoffDate（前天及以前）的文件。 */
    private fun deleteOldBackupsOnServer(
        folderUrl: HttpUrl,
        authHeader: String,
        cutoffDate: LocalDate
    ) {
        val propfindXml =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val propfindRequest = Request.Builder()
            .url(folderUrl)
            .header("Authorization", authHeader)
            .header("Depth", "1")
            .method("PROPFIND", propfindXml.toRequestBody(xmlMediaType))
            .build()

        val hrefs: List<String>? = httpClient.newCall(propfindRequest).execute().use { response ->
            if (response.isSuccessful || response.code == 207) {
                parseHrefsFromPropfind(response.body?.string().orEmpty())
            } else {
                null
            }
        }

        if (hrefs != null) {
            val folderPath = folderUrl.encodedPath.trimEnd('/')
            for (rawHref in hrefs) {
                val resolved = folderUrl.resolve(rawHref.trim()) ?: continue
                val resolvedPath = resolved.encodedPath.trimEnd('/')
                if (resolvedPath == folderPath) continue

                val lastSegment = resolved.encodedPathSegments.lastOrNull { it.isNotEmpty() } ?: continue
                val fileName = decodeSegment(lastSegment)
                val fileDate = backupDate(fileName) ?: continue

                if (!fileDate.isAfter(cutoffDate)) {
                    deleteRemoteFile(resolved, authHeader)
                }
            }
        } else {
            // 兜底：若服务端不支持 PROPFIND，至少尝试删除前天的备份文件。
            val fallbackName = "飞记备份 ${cutoffDate.format(nameDate)}.json"
            val fallbackUrl = folderUrl.newBuilder().addPathSegment(fallbackName).build()
            deleteRemoteFile(fallbackUrl, authHeader)
        }
    }

    private fun deleteRemoteFile(fileUrl: HttpUrl, authHeader: String) {
        val deleteRequest = Request.Builder()
            .url(fileUrl)
            .header("Authorization", authHeader)
            .delete()
            .build()
        runCatching {
            httpClient.newCall(deleteRequest).execute().close()
        }
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
