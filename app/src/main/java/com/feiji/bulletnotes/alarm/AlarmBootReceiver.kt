package com.feiji.bulletnotes.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 在设备重启、应用更新、系统时间/时区修改或精确闹钟权限恢复后，
 * 自动从 Room 数据库重新注册所有未过期的笔记闹钟。
 */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val supported = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        if (!supported) return

        val appContext = context.applicationContext
        // Direct Boot 期间加密存储尚未解锁，等待解锁后的标准 BOOT_COMPLETED 再读取 Room 数据库
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = appContext.getSystemService(UserManager::class.java)
            if (userManager != null && !userManager.isUserUnlocked) {
                return
            }
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NoteAlarmScheduler.rescheduleAllActiveAlarms(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
