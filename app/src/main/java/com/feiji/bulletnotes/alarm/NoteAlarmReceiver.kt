package com.feiji.bulletnotes.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.feiji.bulletnotes.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 接收系统 AlarmManager 精确闹钟触发广播以及通知栏关闭广播。
 * 即使飞记已被彻底杀进程退出，系统也会拉起本 Receiver 并转交前台服务响铃。
 */
class NoteAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext

        if (action == NoteAlarmScheduler.ACTION_DISMISS_ALARM) {
            NoteAlarmService.stopRinging(appContext)
            return
        }

        if (action != NoteAlarmScheduler.ACTION_TRIGGER_NOTE_ALARM) return

        val noteId = intent.getLongExtra(NoteAlarmScheduler.EXTRA_NOTE_ID, -1L)
        val expectedAlarmTime = intent.getLongExtra(NoteAlarmScheduler.EXTRA_ALARM_TIME, -1L)
        if (noteId <= 0L || expectedAlarmTime <= 0L) return

        val pendingResult = goAsync()
        val wakeLock = runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FeiJi:AlarmReceiverLock")?.apply {
                setReferenceCounted(false)
                acquire(15_000L)
            }
        }.getOrNull()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(appContext).noteDao()
                val note = dao.getNoteById(noteId)
                val storedAlarm = note?.alarmTimeMillis
                val isValid = note != null &&
                    !note.isDeleted &&
                    storedAlarm != null &&
                    abs(storedAlarm - expectedAlarmTime) <= 60_000L

                if (isValid && note != null && storedAlarm != null) {
                    dao.clearNoteAlarmIfMatches(
                        id = noteId,
                        expectedAlarmTime = storedAlarm,
                        updatedAt = System.currentTimeMillis()
                    )
                    NoteAlarmScheduler.removeTrackedNoteId(appContext, noteId)
                    NoteAlarmService.startRinging(
                        appContext,
                        RingingAlarmInfo(
                            noteId = note.id,
                            alarmTimeMillis = storedAlarm,
                            noteContent = note.content,
                            noteDateString = note.dateString
                        )
                    )
                } else {
                    NoteAlarmScheduler.removeTrackedNoteId(appContext, noteId)
                }
            } finally {
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}
