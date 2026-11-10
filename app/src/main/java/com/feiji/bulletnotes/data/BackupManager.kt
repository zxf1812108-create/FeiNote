package com.feiji.bulletnotes.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日快照的权威副本存储于应用私有目录，使用 AtomicFile 避免覆盖时损坏旧备份。
 * Android 10+ 另使用 MediaStore 尽力同步一份到公共 Download 目录。
 * 公共副本同步失败不影响私有快照恢复；卸载应用会删除私有副本。
 */
object BackupManager {
    private const val PREFS_NAME = "feiji_bullet_notes_prefs"
    private const val KEY_LAST_BACKUP_DATE = "last_auto_backup_date"
    private const val BACKUP_FILE_NAME = "飞记每日自动备份.json"
    private const val KEY_PUBLIC_BACKUP_URI = "public_backup_uri"
    private val gson = Gson()

    private fun privateBackup(context: Context) = File(context.filesDir, BACKUP_FILE_NAME)

    suspend fun checkAndPerformDailyBackup(context: Context, noteDao: NoteDao): Boolean =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            if (prefs.getString(KEY_LAST_BACKUP_DATE, null) == today) return@withContext false
            val success = exportBackup(context, noteDao)
            if (success) prefs.edit().putString(KEY_LAST_BACKUP_DATE, today).commit()
            success
        }

    suspend fun exportBackup(context: Context, noteDao: NoteDao): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bytes = gson.toJson(noteDao.getAllNotesForBackup()).toByteArray(Charsets.UTF_8)
                val atomic = AtomicFile(privateBackup(context))
                val stream = atomic.startWrite()
                try {
                    stream.write(bytes)
                    atomic.finishWrite(stream)
                } catch (e: Exception) {
                    atomic.failWrite(stream)
                    throw e
                }
                // 外部文件只是副本，写入失败不破坏已成功保存的私有快照。
                try { mirrorToDownloads(context, bytes) } catch (e: Exception) {
                    android.util.Log.w("FeiJiBackup", "Download mirror failed", e)
                }
                true
            } catch (e: Exception) {
                android.util.Log.e("FeiJiBackup", "Daily backup failed", e)
                false
            }
        }

    private fun mirrorToDownloads(context: Context, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val resolver = context.contentResolver
            val saved = prefs.getString(KEY_PUBLIC_BACKUP_URI, null)?.let(Uri::parse)
            var uri: Uri? = saved
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("Cannot open existing backup")
                    return
                } catch (_: Exception) {
                    // Previously saved URI may have been deleted or become inaccessible.
                    uri = null
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Download backup")
            try {
                resolver.openOutputStream(created, "w")?.use { it.write(bytes) }
                    ?: error("Cannot write Download backup")
                resolver.update(created, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                prefs.edit().putString(KEY_PUBLIC_BACKUP_URI, created.toString()).apply()
            } catch (e: Exception) {
                resolver.delete(created, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, BACKUP_FILE_NAME).writeBytes(bytes)
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
                if (notes.any { it.dateString.isBlank() || it.id < 0 }) {
                    return@withContext false to "备份记录校验失败，未修改现有笔记"
                }
                noteDao.restoreAllFromBackup(notes)
                true to "已恢复 ${notes.size} 条笔记（包含空备份）"
            } catch (e: Exception) {
                android.util.Log.e("FeiJiBackup", "Restore failed", e)
                false to "恢复失败：${e.localizedMessage ?: "未知错误"}"
            }
        }
}
