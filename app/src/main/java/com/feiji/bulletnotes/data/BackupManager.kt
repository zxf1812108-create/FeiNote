package com.feiji.bulletnotes.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** SAF 文件夹授权：每日一个 JSON 文件；同日覆盖；仅清理严格早于三天前的本应用备份。 */
object BackupManager {
    private const val PREFS = "feiji_bullet_notes_prefs"
    private const val KEY_TREE = "saf_backup_tree_uri_v124"
    private const val KEY_CLEANED = "backup_cleanup_date_v124"

    private const val BACKUP_SCHEMA_VERSION = 2
    private const val ENC_VERSION = 1
    private const val ENC_ALG = "AES-256-GCM"
    private const val ENC_KDF = "PBKDF2WithHmacSHA256"
    private const val ENC_ITERATIONS = 100_000
    private const val ENC_KEY_BITS = 256
    private const val ENC_SALT_BYTES = 16
    private const val ENC_IV_BYTES = 12
    private const val ENC_GCM_TAG_BITS = 128

    private val gson = Gson()
    private val nameDate = DateTimeFormatter.ofPattern("yyMMdd")
    private val filePattern = Regex("""^飞记备份 (\d{6})(?:\.json)?$""")

    data class BackupFile(val name: String, val uri: Uri)

    sealed class RestoreResult {
        data class Success(val count: Int, val message: String) : RestoreResult()
        data class PasswordRequired(val wrongPassword: Boolean = false) : RestoreResult()
        data class Failure(val message: String) : RestoreResult()
    }

    private data class BackupSettings(
        val webDavEnabled: Boolean? = null,
        val webDavServerUrl: String? = null,
        val webDavRemoteFolder: String? = null,
        val webDavUsername: String? = null,
        val webDavPassword: String? = null,
        val backupEncryptionPassword: String? = null,
        val wifiAutoSyncEnabled: Boolean? = null
    )

    private data class BackupPayload(
        val feiji_backup_version: Int = BACKUP_SCHEMA_VERSION,
        val notes: List<NoteEntity>? = null,
        val settings: BackupSettings? = null
    )

    private data class EncryptedBackupEnvelope(
        val feiji_encrypted: Boolean = true,
        val v: Int = ENC_VERSION,
        val alg: String = ENC_ALG,
        val kdf: String = ENC_KDF,
        val iter: Int = ENC_ITERATIONS,
        val gzip: Boolean = true,
        val salt: String,
        val iv: String,
        val data: String
    )
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasBackupFolder(context: Context): Boolean {
        val raw = prefs(context).getString(KEY_TREE, null) ?: return false
        return try {
            val uri = Uri.parse(raw)
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        } catch (_: Exception) { false }
    }

    fun setBackupFolder(context: Context, uri: Uri, flags: Int) {
        val grants = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(grants == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) { "需要文件夹读写权限" }
        context.contentResolver.takePersistableUriPermission(uri, grants)
        prefs(context).edit().putString(KEY_TREE, uri.toString()).remove(KEY_CLEANED).commit()
    }

    private fun tree(context: Context): Uri = Uri.parse(
        prefs(context).getString(KEY_TREE, null) ?: error("尚未选择备份文件夹")
    )

    private fun children(context: Context): List<BackupFile> {
        val root = tree(context)
        val treeId = DocumentsContract.getTreeDocumentId(root)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, treeId)
        val result = mutableListOf<BackupFile>()
        context.contentResolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameCol) ?: continue
                val id = cursor.getString(idCol) ?: continue
                result.add(BackupFile(name, DocumentsContract.buildDocumentUriUsingTree(root, id)))
            }
        } ?: error("无法读取备份文件夹")
        return result
    }

    private fun backupDate(name: String): LocalDate? {
        val match = filePattern.matchEntire(name) ?: return null
        return try { LocalDate.parse(match.groupValues[1], nameDate) } catch (_: Exception) { null }
    }

    suspend fun listBackups(context: Context): List<BackupFile> = withContext(Dispatchers.IO) {
        if (!hasBackupFolder(context)) return@withContext emptyList()
        children(context).filter { backupDate(it.name) != null }
            .sortedByDescending { backupDate(it.name) }
    }

    /** 查找当前日期的备份；仅匹配严格的“飞记备份 yyMMdd(.json)”命名。 */
    suspend fun findTodayBackup(context: Context): BackupFile? = withContext(Dispatchers.IO) {
        if (!hasBackupFolder(context)) return@withContext null
        val expected = "飞记备份 ${LocalDate.now().format(nameDate)}"
        children(context).firstOrNull { it.name == "$expected.json" || it.name == expected }
    }

    /** 按日期读取本地备份文件原始字节（供 WebDAV 备份昨天数据使用）。 */
    suspend fun readBackupBytesForDate(context: Context, date: LocalDate): Pair<String, ByteArray>? =
        withContext(Dispatchers.IO) {
            if (!hasBackupFolder(context)) return@withContext null
            try {
                val baseName = "飞记备份 ${date.format(nameDate)}"
                val match = children(context).firstOrNull {
                    it.name == "$baseName.json" || it.name == baseName
                } ?: return@withContext null
                val bytes = context.contentResolver.openInputStream(match.uri)?.use { it.readBytes() }
                    ?: return@withContext null
                "$baseName.json" to bytes
            } catch (e: Exception) {
                Log.w("FeiJiBackup", "Read backup for $date failed", e)
                null
            }
        }

    /** 仅首次打开时清理：保留今天及过去三天的日期，其他文件绝不触碰。 */
    suspend fun cleanOldBackupsOncePerDay(context: Context): Int = withContext(Dispatchers.IO) {
        if (!hasBackupFolder(context)) return@withContext 0
        val today = LocalDate.now()
        if (prefs(context).getString(KEY_CLEANED, null) == today.toString()) return@withContext 0
        var deleted = 0
        for (file in children(context)) {
            val date = backupDate(file.name) ?: continue
            if (date.isBefore(today.minusDays(3))) {
                if (DocumentsContract.deleteDocument(context.contentResolver, file.uri)) deleted++
            }
        }
        prefs(context).edit().putString(KEY_CLEANED, today.toString()).commit()
        deleted
    }

    fun snapshotJson(context: Context, notes: List<NoteEntity>): String {
        val config = WebDavBackupManager.getConfig(context)
        val payload = BackupPayload(
            feiji_backup_version = BACKUP_SCHEMA_VERSION,
            notes = notes,
            settings = BackupSettings(
                webDavEnabled = config.enabled,
                webDavServerUrl = config.serverUrl,
                webDavRemoteFolder = config.remoteFolder,
                webDavUsername = config.username,
                webDavPassword = config.password,
                backupEncryptionPassword = config.encryptionPassword,
                wifiAutoSyncEnabled = config.wifiAutoSyncEnabled
            )
        )
        return gson.toJson(payload)
    }

    /** 生成标准备份字节（若已设置加密密码则自动加密，否则输出明文 JSON）。 */
    suspend fun buildBackupBytes(context: Context, noteDao: NoteDao): ByteArray = withContext(Dispatchers.IO) {
        val plainBytes = snapshotJson(context, noteDao.getAllNotesForBackup()).toByteArray(Charsets.UTF_8)
        val encryptionPassword = WebDavBackupManager.getEncryptionPassword(context)
        if (encryptionPassword.isNotEmpty()) {
            encryptBackupBytes(plainBytes, encryptionPassword)
        } else {
            plainBytes
        }
    }

    suspend fun exportBackup(context: Context, noteDao: NoteDao): Boolean = withContext(Dispatchers.IO) {
        if (!hasBackupFolder(context)) return@withContext false
        try {
            val name = "飞记备份 ${LocalDate.now().format(nameDate)}.json"
            val matches = children(context).filter { it.name == name || it.name == name.removeSuffix(".json") }
            val destination = matches.firstOrNull()?.uri ?: run {
                val root = tree(context)
                val parent = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
                DocumentsContract.createDocument(context.contentResolver, parent, "application/json", name)
                    ?: error("无法创建备份文件")
            }
            val bytes = buildBackupBytes(context, noteDao)
            context.contentResolver.openOutputStream(destination, "rwt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("无法覆盖备份文件")
            true
        } catch (e: Exception) {
            Log.e("FeiJiBackup", "Backup failed", e)
            false
        }
    }

    /** 判断字节内容是否已经是飞记加密备份格式，避免重复加密。 */
    fun isEncryptedBackupBytes(bytes: ByteArray): Boolean {
        return try {
            val text = bytes.toString(Charsets.UTF_8)
            val element = JsonParser.parseString(text)
            element.isJsonObject && element.asJsonObject.get("feiji_encrypted")?.asBoolean == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 若字节内容为飞记加密格式则使用传入密码解密并返回明文 JSON；
     * 若未加密则直接返回 UTF-8 文本；若加密但密码为空/错误或内容损坏则返回 null。
     */
    fun decryptBackupBytesIfNeeded(bytes: ByteArray, password: String?): String? {
        return try {
            val rawText = bytes.toString(Charsets.UTF_8)
            val parsedRoot = JsonParser.parseString(rawText)
            val isEncrypted = parsedRoot.isJsonObject &&
                parsedRoot.asJsonObject.get("feiji_encrypted")?.asBoolean == true
            if (!isEncrypted) {
                rawText
            } else {
                if (password.isNullOrEmpty()) return null
                val envelope = gson.fromJson(parsedRoot, EncryptedBackupEnvelope::class.java)
                    ?: return null
                if (envelope.salt.isBlank() || envelope.iv.isBlank() || envelope.data.isBlank()) {
                    return null
                }
                decryptEnvelope(envelope, password)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 从明文备份 JSON（兼容 v1 数组与 v2 payload 对象）提取笔记列表，供每日备份防覆盖合并使用。 */
    fun extractNotesFromPlainBackupJson(plainJson: String): List<NoteEntity>? {
        return try {
            val payloadElement = JsonParser.parseString(plainJson)
            when {
                payloadElement.isJsonArray -> {
                    val type = object : TypeToken<List<NoteEntity>>() {}.type
                    gson.fromJson<List<NoteEntity>>(payloadElement, type)
                }

                payloadElement.isJsonObject && payloadElement.asJsonObject.get("notes")?.isJsonArray == true -> {
                    gson.fromJson(payloadElement, BackupPayload::class.java)?.notes
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 使用 PBKDF2-HMAC-SHA256 + AES-256-GCM 对备份字节进行 GZIP 压缩后加密，输出标准 JSON 外壳。 */
    fun encryptBackupBytes(plainBytes: ByteArray, password: String): ByteArray {
        val compressed = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { gzip ->
                gzip.write(plainBytes)
            }
            bos.toByteArray()
        }

        val random = SecureRandom()
        val salt = ByteArray(ENC_SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(ENC_IV_BYTES).also { random.nextBytes(it) }
        val keyBytes = deriveAesKey(password, salt, ENC_ITERATIONS)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(ENC_GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(compressed)

        val envelope = EncryptedBackupEnvelope(
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            data = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        )
        return gson.toJson(envelope).toByteArray(Charsets.UTF_8)
    }

    private fun decryptEnvelope(envelope: EncryptedBackupEnvelope, password: String): String {
        val salt = Base64.decode(envelope.salt, Base64.DEFAULT)
        val iv = Base64.decode(envelope.iv, Base64.DEFAULT)
        val cipherBytes = Base64.decode(envelope.data, Base64.DEFAULT)
        val iterations = envelope.iter.coerceIn(1, 2_000_000)

        val keyBytes = deriveAesKey(password, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(ENC_GCM_TAG_BITS, iv))
        val decryptedBytes = cipher.doFinal(cipherBytes)

        val plainBytes = if (envelope.gzip) {
            GZIPInputStream(ByteArrayInputStream(decryptedBytes)).use { it.readBytes() }
        } else {
            decryptedBytes
        }
        return plainBytes.toString(Charsets.UTF_8)
    }

    private fun deriveAesKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        return try {
            val factory = SecretKeyFactory.getInstance(ENC_KDF)
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, ENC_KEY_BITS)
            try {
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } catch (_: NoSuchAlgorithmException) {
            pbkdf2HmacSha256Fallback(password.toByteArray(Charsets.UTF_8), salt, iterations, ENC_KEY_BITS / 8)
        }
    }

    private fun pbkdf2HmacSha256Fallback(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blockCount = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blockCount * hLen)
        val saltBlock = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, saltBlock, 0, salt.size)

        for (blockIndex in 1..blockCount) {
            saltBlock[salt.size] = (blockIndex ushr 24).toByte()
            saltBlock[salt.size + 1] = (blockIndex ushr 16).toByte()
            saltBlock[salt.size + 2] = (blockIndex ushr 8).toByte()
            saltBlock[salt.size + 3] = blockIndex.toByte()

            var u = mac.doFinal(saltBlock)
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, out, (blockIndex - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
    }

    /**
     * 恢复备份：自动识别明文 JSON 数组与 WebDAV 加密备份外壳，
     * 遇到加密备份时使用传入密码自动解密，未提供密码或密码错误时返回 PasswordRequired。
     */
    suspend fun restoreBackup(
        context: Context,
        noteDao: NoteDao,
        uri: Uri,
        password: String? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            // 只允许恢复当前授权文件夹中的、符合命名规则的备份文件。
            if (children(context).none { it.uri == uri && backupDate(it.name) != null }) {
                return@withContext RestoreResult.Failure("备份文件不在授权文件夹中")
            }
            val rawText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return@withContext RestoreResult.Failure("无法读取备份文件")

            restoreBackupFromText(context, noteDao, rawText, password)
        } catch (e: Exception) {
            Log.e("FeiJiBackup", "Restore failed", e)
            RestoreResult.Failure("恢复失败：${e.localizedMessage ?: "未知错误"}")
        }
    }

    /** 从备份 JSON 文本恢复（供本地 SAF 与局域网直连共用；自动识别加密外壳与新旧版本格式）。 */
    suspend fun restoreBackupFromText(
        context: Context,
        noteDao: NoteDao,
        rawText: String,
        password: String? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val parsedRoot = JsonParser.parseString(rawText)
            val isEncrypted = parsedRoot.isJsonObject &&
                parsedRoot.asJsonObject.get("feiji_encrypted")?.asBoolean == true

            val plainJson = if (isEncrypted) {
                if (password.isNullOrEmpty()) {
                    return@withContext RestoreResult.PasswordRequired(wrongPassword = false)
                }
                val envelope = gson.fromJson(parsedRoot, EncryptedBackupEnvelope::class.java)
                    ?: return@withContext RestoreResult.Failure("加密备份格式不正确")
                if (envelope.salt.isBlank() || envelope.iv.isBlank() || envelope.data.isBlank()) {
                    return@withContext RestoreResult.Failure("加密备份内容不完整")
                }
                try {
                    decryptEnvelope(envelope, password)
                } catch (_: BadPaddingException) {
                    return@withContext RestoreResult.PasswordRequired(wrongPassword = true)
                }
            } else {
                rawText
            }

            val payloadElement = JsonParser.parseString(plainJson)
            val notes: List<NoteEntity>
            val restoredSettings: BackupSettings?

            when {
                // 兼容旧版备份：顶层直接是笔记 JSON 数组
                payloadElement.isJsonArray -> {
                    val type = object : TypeToken<List<NoteEntity>>() {}.type
                    notes = gson.fromJson(payloadElement, type)
                        ?: return@withContext RestoreResult.Failure("备份内容无效")
                    restoredSettings = null
                }

                // 新版备份：包含 notes 与 settings
                payloadElement.isJsonObject && payloadElement.asJsonObject.get("notes")?.isJsonArray == true -> {
                    val payload = gson.fromJson(payloadElement, BackupPayload::class.java)
                        ?: return@withContext RestoreResult.Failure("备份内容无效")
                    notes = payload.notes ?: return@withContext RestoreResult.Failure("备份内容无效")
                    restoredSettings = payload.settings
                }

                else -> {
                    return@withContext RestoreResult.Failure("备份格式不正确")
                }
            }

            if (notes.any { it.dateString.isBlank() || it.id < 0 }) {
                return@withContext RestoreResult.Failure("备份内容校验失败")
            }
            noteDao.restoreAllFromBackup(notes)

            val hasSettings = restoredSettings != null
            if (restoredSettings != null) {
                val restoredEncPwd = restoredSettings.backupEncryptionPassword ?: ""
                WebDavBackupManager.restoreConfig(
                    context = context,
                    config = WebDavBackupManager.WebDavConfig(
                        serverUrl = restoredSettings.webDavServerUrl ?: WebDavBackupManager.DEFAULT_SERVER_URL,
                        remoteFolder = restoredSettings.webDavRemoteFolder ?: WebDavBackupManager.DEFAULT_REMOTE_FOLDER,
                        username = restoredSettings.webDavUsername ?: "",
                        password = restoredSettings.webDavPassword ?: "",
                        encryptionPassword = restoredEncPwd,
                        wifiAutoSyncEnabled = (restoredSettings.wifiAutoSyncEnabled == true) && restoredEncPwd.isNotEmpty()
                    )
                )
            }

            val msg = when {
                isEncrypted && hasSettings -> "已解密并恢复 ${notes.size} 条笔记及设置"
                isEncrypted -> "已解密并恢复 ${notes.size} 条笔记"
                hasSettings -> "已从备份恢复 ${notes.size} 条笔记及设置"
                else -> "已从备份恢复 ${notes.size} 条笔记"
            }
            RestoreResult.Success(notes.size, msg)
        } catch (e: Exception) {
            Log.e("FeiJiBackup", "Restore from text failed", e)
            RestoreResult.Failure("恢复失败：${e.localizedMessage ?: "未知错误"}")
        }
    }
}
