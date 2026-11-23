package com.feiji.bulletnotes.alarm

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiji.bulletnotes.MainActivity
import com.feiji.bulletnotes.ui.theme.FeiJiTheme
import com.feiji.bulletnotes.ui.theme.ThemePreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 锁屏可见的全屏极简黑白闹钟响铃界面。
 * 按需求不提供贪睡功能，仅提供“关闭闹钟”与“查看笔记”。
 */
class NoteAlarmActivity : ComponentActivity() {
    private val initialInfoState = mutableStateOf<RingingAlarmInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLockScreenPresentation()
        initialInfoState.value = extractAlarmInfo(intent) ?: NoteAlarmService.ringingAlarm.value

        setContent {
            val systemDark = isSystemInDarkTheme()
            val darkTheme = remember(systemDark) {
                ThemePreferences.resolveDarkTheme(this@NoteAlarmActivity, systemDark)
            }
            val liveInfo by NoteAlarmService.ringingAlarm.collectAsState()
            var seenActiveService by remember { mutableStateOf(liveInfo != null) }

            LaunchedEffect(liveInfo) {
                if (liveInfo != null) {
                    seenActiveService = true
                } else if (seenActiveService) {
                    finish()
                }
            }

            val displayInfo = liveInfo ?: initialInfoState.value
            FeiJiTheme(darkTheme = darkTheme) {
                NoteAlarmRingingScreen(
                    info = displayInfo,
                    onDismissAlarm = {
                        NoteAlarmService.stopRinging(this)
                        finish()
                    },
                    onOpenNote = { dateString ->
                        NoteAlarmService.stopRinging(this)
                        openMainActivityOnDate(dateString)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractAlarmInfo(intent)?.let { initialInfoState.value = it }
    }

    private fun enableLockScreenPresentation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun extractAlarmInfo(intent: Intent?): RingingAlarmInfo? {
        if (intent == null) return null
        val noteId = intent.getLongExtra(NoteAlarmScheduler.EXTRA_NOTE_ID, -1L)
        if (noteId <= 0L) return null
        val alarmTime = intent.getLongExtra(NoteAlarmScheduler.EXTRA_ALARM_TIME, System.currentTimeMillis())
        val content = intent.getStringExtra(NoteAlarmScheduler.EXTRA_NOTE_CONTENT).orEmpty()
        val date = intent.getStringExtra(NoteAlarmScheduler.EXTRA_NOTE_DATE).orEmpty()
        return RingingAlarmInfo(noteId, alarmTime, content, date)
    }

    private fun openMainActivityOnDate(dateString: String) {
        val launchMain = {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (dateString.isNotBlank()) {
                    putExtra(NoteAlarmScheduler.EXTRA_OPEN_DATE, dateString)
                }
            }
            startActivity(mainIntent)
            finish()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(KeyguardManager::class.java)
            if (km != null && km.isKeyguardLocked) {
                km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        launchMain()
                    }

                    override fun onDismissCancelled() {
                        finish()
                    }

                    override fun onDismissError() {
                        launchMain()
                    }
                })
                return
            }
        }
        launchMain()
    }
}

@Composable
private fun NoteAlarmRingingScreen(
    info: RingingAlarmInfo?,
    onDismissAlarm: () -> Unit,
    onOpenNote: (String) -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onBackground
    val bgColor = MaterialTheme.colorScheme.background

    val triggerMillis = info?.alarmTimeMillis ?: System.currentTimeMillis()
    val zdt = remember(triggerMillis) {
        Instant.ofEpochMilli(triggerMillis).atZone(ZoneId.systemDefault())
    }
    val timeText = remember(zdt) { zdt.format(DateTimeFormatter.ofPattern("HH:mm")) }
    val dateText = remember(zdt) { zdt.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) }
    val noteBody = info?.noteContent?.trim().orEmpty().ifEmpty { "（无正文内容）" }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部极简标题与时间
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        val r = size.minDimension * 0.36f
                        val c = Offset(size.width / 2f, size.height * 0.56f)
                        drawCircle(color = contentColor.copy(alpha = 0.6f), radius = r, center = c, style = stroke)
                        drawLine(
                            color = contentColor.copy(alpha = 0.6f),
                            start = Offset(size.width * 0.16f, size.height * 0.24f),
                            end = Offset(size.width * 0.30f, size.height * 0.12f),
                            strokeWidth = 1.8.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = contentColor.copy(alpha = 0.6f),
                            start = Offset(size.width * 0.84f, size.height * 0.24f),
                            end = Offset(size.width * 0.70f, size.height * 0.12f),
                            strokeWidth = 1.8.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = contentColor.copy(alpha = 0.6f),
                            start = c,
                            end = Offset(c.x, c.y - r * 0.52f),
                            strokeWidth = 1.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = contentColor.copy(alpha = 0.6f),
                            start = c,
                            end = Offset(c.x + r * 0.42f, c.y + r * 0.18f),
                            strokeWidth = 1.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "飞记闹钟",
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor.copy(alpha = 0.65f)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = timeText,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = contentColor
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.55f)
                )
            }

            // 中部笔记内容卡片
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = noteBody,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 26.sp,
                    color = contentColor,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
            }

            // 底部按钮：仅“关闭闹钟”与“查看笔记”，无贪睡
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onDismissAlarm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "关闭闹钟",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                if (!info?.noteDateString.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { onOpenNote(info?.noteDateString.orEmpty()) }) {
                        Text(
                            text = "查看笔记",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
