package com.feiji.bulletnotes.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.feiji.bulletnotes.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class RingingAlarmInfo(
    val noteId: Long,
    val alarmTimeMillis: Long,
    val noteContent: String,
    val noteDateString: String
)

/**
 * 笔记闹钟响铃前台服务：
 * 负责播放系统闹钟铃声（USAGE_ALARM）、持续震动、唤醒全屏闹钟页面以及展示高优先级通知。
 * 按需求不提供贪睡功能，仅提供关闭闹钟。
 */
class NoteAlarmService : Service() {
    companion object {
        private const val CHANNEL_ID = "feiji_note_alarm_v1"
        private const val NOTIFICATION_ID = 4209
        private const val AUTO_STOP_MS = 120_000L
        private const val WAKELOCK_TIMEOUT_MS = 180_000L

        private val _ringingAlarm = MutableStateFlow<RingingAlarmInfo?>(null)
        val ringingAlarm: StateFlow<RingingAlarmInfo?> = _ringingAlarm.asStateFlow()

        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun startRinging(context: Context, info: RingingAlarmInfo) {
            val intent = Intent(context, NoteAlarmService::class.java).apply {
                action = NoteAlarmScheduler.ACTION_TRIGGER_NOTE_ALARM
                putExtra(NoteAlarmScheduler.EXTRA_NOTE_ID, info.noteId)
                putExtra(NoteAlarmScheduler.EXTRA_ALARM_TIME, info.alarmTimeMillis)
                putExtra(NoteAlarmScheduler.EXTRA_NOTE_CONTENT, info.noteContent)
                putExtra(NoteAlarmScheduler.EXTRA_NOTE_DATE, info.noteDateString)
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun stopRinging(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, NoteAlarmService::class.java).apply {
                action = NoteAlarmScheduler.ACTION_DISMISS_ALARM
            }
            runCatching { appContext.stopService(intent) }
            _ringingAlarm.value = null
        }

        fun stopIfMatching(context: Context, noteId: Long) {
            if (_ringingAlarm.value?.noteId == noteId) {
                stopRinging(context)
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        stopAlarmAndSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == NoteAlarmScheduler.ACTION_DISMISS_ALARM) {
            stopAlarmAndSelf()
            return START_NOT_STICKY
        }

        val noteId = intent?.getLongExtra(NoteAlarmScheduler.EXTRA_NOTE_ID, -1L) ?: -1L
        val alarmTime = intent?.getLongExtra(NoteAlarmScheduler.EXTRA_ALARM_TIME, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val noteContent = intent?.getStringExtra(NoteAlarmScheduler.EXTRA_NOTE_CONTENT).orEmpty()
        val noteDate = intent?.getStringExtra(NoteAlarmScheduler.EXTRA_NOTE_DATE).orEmpty()

        val info = RingingAlarmInfo(
            noteId = noteId,
            alarmTimeMillis = alarmTime,
            noteContent = noteContent,
            noteDateString = noteDate
        )
        _ringingAlarm.value = info

        ensureNotificationChannel()
        val notification = buildAlarmNotification(info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        stopAudioAndVibration()
        startAlarmAudio()
        startAlarmVibration()
        scheduleAutoStop()

        // 兜底：若屏幕处于解锁亮屏状态，部分机型会只弹横幅而不自动拉起 fullScreenIntent，尝试直接打开响铃页
        launchAlarmActivity(info)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoStopRunnable)
        stopAudioAndVibration()
        releaseWakeLock()
        _ringingAlarm.value = null
        super.onDestroy()
    }

    private fun stopAlarmAndSelf() {
        mainHandler.removeCallbacks(autoStopRunnable)
        stopAudioAndVibration()
        releaseWakeLock()
        _ringingAlarm.value = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun scheduleAutoStop() {
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
    }

    private fun launchAlarmActivity(info: RingingAlarmInfo) {
        val activityIntent = buildAlarmActivityIntent(info)
        runCatching { startActivity(activityIntent) }
    }

    private fun buildAlarmActivityIntent(info: RingingAlarmInfo): Intent {
        return Intent(this, NoteAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NoteAlarmScheduler.EXTRA_NOTE_ID, info.noteId)
            putExtra(NoteAlarmScheduler.EXTRA_ALARM_TIME, info.alarmTimeMillis)
            putExtra(NoteAlarmScheduler.EXTRA_NOTE_CONTENT, info.noteContent)
            putExtra(NoteAlarmScheduler.EXTRA_NOTE_DATE, info.noteDateString)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "笔记闹钟",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于笔记定时闹钟响铃提醒"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildAlarmNotification(info: RingingAlarmInfo): Notification {
        val fullScreenIntent = buildAlarmActivityIntent(info)
        val fullScreenPending = PendingIntent.getActivity(
            this,
            5101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, NoteAlarmReceiver::class.java).apply {
            action = NoteAlarmScheduler.ACTION_DISMISS_ALARM
        }
        val dismissPending = PendingIntent.getBroadcast(
            this,
            5102,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeText = Instant.ofEpochMilli(info.alarmTimeMillis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
        val preview = info.noteContent.trim().ifEmpty { "飞记笔记闹钟提醒" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("飞记闹钟 · $timeText")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(0, "关闭", dismissPending)
            .build()
    }

    private fun resolveAlarmUri(): Uri? {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    private fun startAlarmAudio() {
        val alarmUri = resolveAlarmUri() ?: return
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val started = runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(this@NoteAlarmService, alarmUri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = player
            true
        }.getOrDefault(false)

        if (!started) {
            runCatching {
                val ringtone = RingtoneManager.getRingtone(this, alarmUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.isLooping = true
                }
                ringtone?.audioAttributes = audioAttributes
                ringtone?.play()
                fallbackRingtone = ringtone
            }
        }
    }

    private fun startAlarmVibration() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return
            val pattern = longArrayOf(0L, 600L, 400L, 600L, 800L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        }
    }

    private fun stopAudioAndVibration() {
        runCatching {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        mediaPlayer = null

        runCatching {
            fallbackRingtone?.stop()
        }
        fallbackRingtone = null

        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.cancel()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FeiJi:NoteAlarmLock")?.apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
