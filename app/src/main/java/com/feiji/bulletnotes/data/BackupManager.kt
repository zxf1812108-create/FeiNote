package com.feiji.bulletnotes.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** SAF 文件夹授权：每日一个 JSON 文件；同日覆盖；仅清理严格早于三天前的本应用备份。 */
object BackupManager {
    private const val PREFS = "feiji_bullet_notes_prefs"
    private const val KEY_TREE = "saf_backup_tree_uri_v124"
    private const val KEY_CLEANED = "backup_cleanup_date_v124"
    private val gson = Gson()
    private val nameDate = DateTimeFormatter.ofPattern("yyMMdd")
    private val filePattern = Regex("""^飞记备份 (\d{6})(?:\.json)?$""")

    data class BackupFile(val name: String, val uri: Uri)
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

    fun snapshotJson(notes: List<NoteEntity>): String = gson.toJson(notes)

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
            val bytes = snapshotJson(noteDao.getAllNotesForBackup()).toByteArray(Charsets.UTF_8)
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

    suspend fun restoreBackup(context: Context, noteDao: NoteDao, uri: Uri): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // 只允许恢复当前授权文件夹中的、符合命名规则的备份文件。
            if (children(context).none { it.uri == uri && backupDate(it.name) != null })
                return@withContext false to "备份文件不在授权文件夹中"
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return@withContext false to "无法读取备份文件"
            if (!JsonParser.parseString(json).isJsonArray) return@withContext false to "备份格式不正确"
            val type = object : TypeToken<List<NoteEntity>>() {}.type
            val notes: List<NoteEntity> = gson.fromJson(json, type) ?: return@withContext false to "备份内容无效"
            if (notes.any { it.dateString.isBlank() || it.id < 0 })
                return@withContext false to "备份内容校验失败"
            noteDao.restoreAllFromBackup(notes)
            true to "已从备份恢复 ${notes.size} 条笔记"
        } catch (e: Exception) {
            Log.e("FeiJiBackup", "Restore failed", e)
            false to "恢复失败：${e.localizedMessage ?: "未知错误"}"
        }
    }
}
