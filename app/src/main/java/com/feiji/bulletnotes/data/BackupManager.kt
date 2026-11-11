package com.feiji.bulletnotes.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 使用用户授权的 SAF 文档 URI 覆盖同一文件，绝不自动创建重复 Download 文件。 */
object BackupManager {
    private const val PREFS_NAME = "feiji_bullet_notes_prefs"
    private const val KEY_LAST_BACKUP_DATE = "last_auto_backup_date"
    private const val KEY_DOCUMENT_URI = "saf_backup_document_uri"
    private const val BACKUP_FILE_NAME = "飞记每日自动备份.json"
    private val gson = Gson()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun privateBackup(context: Context) = File(context.filesDir, BACKUP_FILE_NAME)

    fun hasBackupDocument(context: Context): Boolean {
        val raw = prefs(context).getString(KEY_DOCUMENT_URI, null) ?: return false
        return try {
            val uri = Uri.parse(raw)
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission && it.isReadPermission
            }
        } catch (_: Exception) { false }
    }

    fun setBackupDocument(context: Context, uri: Uri, flags: Int) {
        val grantFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(grantFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "所选文件没有写入权限" }
        context.contentResolver.takePersistableUriPermission(uri, grantFlags)
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) {
            "无法保存文件访问权限"
        }
        prefs(context).edit().putString(KEY_DOCUMENT_URI, uri.toString())
            .remove(KEY_LAST_BACKUP_DATE).commit()
    }

    suspend fun checkAndPerformDailyBackup(context: Context, noteDao: NoteDao): Boolean =
        withContext(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            if (prefs(context).getString(KEY_LAST_BACKUP_DATE, null) == today) return@withContext false
            if (!hasBackupDocument(context)) return@withContext false
            val success = exportBackup(context, noteDao)
            if (success) prefs(context).edit().putString(KEY_LAST_BACKUP_DATE, today).commit()
            success
        }

    suspend fun exportBackup(context: Context, noteDao: NoteDao): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasBackupDocument(context)) return@withContext false
            try {
                val bytes = gson.toJson(noteDao.getAllNotesForBackup()).toByteArray(Charsets.UTF_8)
                // 先覆盖外部授权文件，成功后才记录本日已备份；失败时绝不 insert 新文件。
                val uri = Uri.parse(prefs(context).getString(KEY_DOCUMENT_URI, null)!!)
                context.contentResolver.openOutputStream(uri, "rwt")?.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                } ?: error("无法打开备份文件进行覆盖")
                val atomic = AtomicFile(privateBackup(context))
                val stream = atomic.startWrite()
                try {
                    stream.write(bytes)
                    atomic.finishWrite(stream)
                } catch (e: Exception) {
                    atomic.failWrite(stream)
                    throw e
                }
                true
            } catch (e: Exception) {
                Log.e("FeiJiBackup", "Daily backup failed", e)
                false
            }
        }

    suspend fun restoreTodayBackup(context: Context, noteDao: NoteDao): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val backup = privateBackup(context)
                if (!backup.exists()) return@withContext false to "未找到每日备份；请确认曾成功执行自动备份"
                val json = AtomicFile(backup).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JsonParser.parseString(json)
                if (!root.isJsonArray) return@withContext false to "备份格式无效：应为笔记数组"
                val type = object : TypeToken<List<NoteEntity>>() {}.type
                val notes: List<NoteEntity> = gson.fromJson(json, type)
                    ?: return@withContext false to "备份内容无效"
                if (notes.any { it.dateString.isBlank() || it.id < 0 })
                    return@withContext false to "备份记录校验失败，未修改现有笔记"
                noteDao.restoreAllFromBackup(notes)
                true to "已恢复 ${notes.size} 条笔记（包含空备份）"
            } catch (e: Exception) {
                Log.e("FeiJiBackup", "Restore failed", e)
                false to "恢复失败：${e.localizedMessage ?: "未知错误"}"
            }
        }
}
