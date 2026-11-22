package com.feiji.bulletnotes.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.feiji.bulletnotes.MainActivity
import com.feiji.bulletnotes.data.AppDatabase
import com.feiji.bulletnotes.data.NoteEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 笔记闹钟调度器：
 * 使用 AlarmManager.setAlarmClock 注册系统最高优先级精确闹钟，
 * 确保应用被彻底关闭、手机锁屏待机（Doze）以及重启后都能准时唤醒并响铃。
 */
object NoteAlarmScheduler {
    const val ACTION_TRIGGER_NOTE_ALARM = "com.feiji.bulletnotes.action.TRIGGER_NOTE_ALARM"
    const val ACTION_DISMISS_ALARM = "com.feiji.bulletnotes.action.DISMISS_ALARM"

    const val EXTRA_NOTE_ID = "extra_alarm_note_id"
    const val EXTRA_ALARM_TIME = "extra_alarm_time_millis"
    const val EXTRA_NOTE_CONTENT = "extra_alarm_note_content"
    const val EXTRA_NOTE_DATE = "extra_alarm_note_date"
    const val EXTRA_OPEN_DATE = "extra_open_date"

    private const val PREFS_NAME = "feiji_alarm_prefs"
    private const val KEY_SCHEDULED_IDS = "scheduled_note_ids"

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val monthDayTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
    private val fullDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    private val cnDateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    private val isoDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    fun buildExactAlarmSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * 为指定笔记注册精确闹钟。
     * 返回 true 表示系统闹钟注册成功；返回 false 表示时间已过或缺少精确闹钟权限。
     */
    fun scheduleAlarm(
        context: Context,
        noteId: Long,
        triggerAtMillis: Long,
        noteContent: String,
        noteDateString: String
    ): Boolean {
        val appContext = context.applicationContext
        if (noteId <= 0L || triggerAtMillis <= System.currentTimeMillis()) {
            cancelAlarm(appContext, noteId)
            return false
        }
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        if (!canScheduleExactAlarms(appContext)) {
            return false
        }

        val triggerPending = buildTriggerPendingIntent(
            context = appContext,
            noteId = noteId,
            triggerAtMillis = triggerAtMillis,
            noteContent = noteContent,
            noteDateString = noteDateString,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: return false

        val showIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_DATE, noteDateString)
        }
        val showPending = PendingIntent.getActivity(
            appContext,
            showRequestCodeForNote(noteId),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showPending)
            alarmManager.setAlarmClock(info, triggerPending)
            addTrackedNoteId(appContext, noteId)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun cancelAlarm(context: Context, noteId: Long) {
        if (noteId <= 0L) return
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val existing = buildTriggerPendingIntent(
            context = appContext,
            noteId = noteId,
            triggerAtMillis = 0L,
            noteContent = "",
            noteDateString = "",
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) {
            runCatching { alarmManager?.cancel(existing) }
            runCatching { existing.cancel() }
        }
        removeTrackedNoteId(appContext, noteId)
        NoteAlarmService.stopIfMatching(appContext, noteId)
    }

    /**
     * 重新同步数据库中所有未删除且未来的笔记闹钟，并清理已失效/已删除笔记的旧系统闹钟。
     * 用于开机广播、时间/时区变化、备份恢复、WebDAV/Wi-Fi 同步以及应用启动。
     */
    suspend fun rescheduleAllActiveAlarms(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val activeNotes = runCatching {
            AppDatabase.getInstance(appContext).noteDao().getActiveNotesWithFutureAlarm(now)
        }.getOrElse { emptyList() }

        val activeIds = activeNotes.map { it.id }.toSet()
        val trackedIds = getTrackedNoteIds(appContext)
        for (staleId in trackedIds) {
            if (staleId !in activeIds) {
                cancelAlarm(appContext, staleId)
            }
        }

        if (!canScheduleExactAlarms(appContext)) return
        for (note in activeNotes) {
            val trigger = note.alarmTimeMillis ?: continue
            if (trigger > now) {
                scheduleAlarm(
                    context = appContext,
                    noteId = note.id,
                    triggerAtMillis = trigger,
                    noteContent = note.content,
                    noteDateString = note.dateString
                )
            }
        }
    }

    /**
     * 当笔记日期变更时，保留原有闹钟的“时:分”，将其日期同步平移至新的笔记日期。
     */
    fun retargetAlarmToDate(existingAlarmMillis: Long?, newDate: LocalDate): Long? {
        if (existingAlarmMillis == null || existingAlarmMillis <= 0L) return null
        val zone = ZoneId.systemDefault()
        val localTime = Instant.ofEpochMilli(existingAlarmMillis).atZone(zone).toLocalTime()
        return ZonedDateTime.of(newDate, localTime, zone).toInstant().toEpochMilli()
    }

    /**
     * 按笔记最新状态同步系统闹钟：若未删除且存在未来闹钟则注册/更新，否则取消系统闹钟。
     */
    fun syncNoteAlarm(context: Context, note: NoteEntity) {
        val trigger = note.alarmTimeMillis
        if (!note.isDeleted && trigger != null && trigger > System.currentTimeMillis()) {
            scheduleAlarm(
                context = context,
                noteId = note.id,
                triggerAtMillis = trigger,
                noteContent = note.content,
                noteDateString = note.dateString
            )
        } else {
            cancelAlarm(context, note.id)
        }
    }

    fun removeTrackedNoteId(context: Context, noteId: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(noteId.toString())) {
            prefs.edit().putStringSet(KEY_SCHEDULED_IDS, current).apply()
        }
    }

    private fun addTrackedNoteId(context: Context, noteId: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.add(noteId.toString())) {
            prefs.edit().putStringSet(KEY_SCHEDULED_IDS, current).apply()
        }
    }

    private fun getTrackedNoteIds(context: Context): Set<Long> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty()
        return raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    private fun buildTriggerPendingIntent(
        context: Context,
        noteId: Long,
        triggerAtMillis: Long,
        noteContent: String,
        noteDateString: String,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, NoteAlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_NOTE_ALARM
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_ALARM_TIME, triggerAtMillis)
            putExtra(EXTRA_NOTE_CONTENT, noteContent)
            putExtra(EXTRA_NOTE_DATE, noteDateString)
        }
        return PendingIntent.getBroadcast(
            context,
            triggerRequestCodeForNote(noteId),
            intent,
            flags
        )
    }

    private fun triggerRequestCodeForNote(noteId: Long): Int {
        return 300_000 + (noteId.hashCode() and 0x0FFF_FFFF) % 100_000
    }

    private fun showRequestCodeForNote(noteId: Long): Int {
        return 400_000 + (noteId.hashCode() and 0x0FFF_FFFF) % 100_000
    }

    /**
     * 列表中紧贴笔记显示的闹钟时间格式：
     * 若闹钟日期与笔记日期相同，仅显示 HH:mm；跨日显示 MM/dd HH:mm（跨年显示 yyyy/MM/dd HH:mm）。
     */
    fun formatCompactAlarmTime(alarmTimeMillis: Long, noteDateString: String): String {
        val zone = ZoneId.systemDefault()
        val zdt = Instant.ofEpochMilli(alarmTimeMillis).atZone(zone)
        val alarmDateStr = zdt.toLocalDate().format(isoDateFormatter)
        if (alarmDateStr == noteDateString) {
            return zdt.format(timeFormatter)
        }
        val todayYear = LocalDate.now(zone).year
        return if (zdt.year == todayYear) {
            zdt.format(monthDayTimeFormatter)
        } else {
            zdt.format(fullDateTimeFormatter)
        }
    }

    fun formatReadableAlarmTime(alarmTimeMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val zdt = Instant.ofEpochMilli(alarmTimeMillis).atZone(zone)
        val today = LocalDate.now(zone)
        return when (zdt.toLocalDate()) {
            today -> "今天 ${zdt.format(timeFormatter)}"
            today.plusDays(1) -> "明天 ${zdt.format(timeFormatter)}"
            else -> zdt.format(cnDateTimeFormatter)
        }
    }
}
