package com.feiji.bulletnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.feiji.bulletnotes.data.NoteEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ALARM_HOURS = (0..23).toList()
private val ALARM_MINUTES_STEP_5 = (0..55 step 5).toList()
private val DIALOG_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val DIALOG_HINT_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/**
 * 极简黑白笔记闹钟设置弹窗：
 * - 不设默认时间：未设置过闹钟时，小时和分钟均为空（--:--），需用户显式点选后才能确定。
 * - 分钟采用 5 分钟步进（00, 05, 10, ..., 55），两次点按即可快速完成时间选择。
 */
@Composable
fun NoteAlarmDialog(
    note: NoteEntity,
    noteDates: Set<LocalDate>,
    onConfirm: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val existingActiveMillis = remember(note.id, note.alarmTimeMillis) {
        note.alarmTimeMillis?.takeIf { it > System.currentTimeMillis() }
    }

    val initialDate = remember(note.id, note.dateString, existingActiveMillis) {
        if (existingActiveMillis != null) {
            Instant.ofEpochMilli(existingActiveMillis).atZone(zone).toLocalDate()
        } else {
            val today = LocalDate.now(zone)
            val parsed = runCatching { LocalDate.parse(note.dateString) }.getOrNull()
            if (parsed != null && !parsed.isBefore(today)) parsed else today
        }
    }
    val initialHour = remember(note.id, existingActiveMillis) {
        existingActiveMillis?.let { Instant.ofEpochMilli(it).atZone(zone).hour }
    }
    val initialMinute = remember(note.id, existingActiveMillis) {
        existingActiveMillis?.let { Instant.ofEpochMilli(it).atZone(zone).minute }
    }

    var selectedDate by remember(note.id) { mutableStateOf(initialDate) }
    var selectedHour by remember(note.id) { mutableStateOf<Int?>(initialHour) }
    var selectedMinute by remember(note.id) { mutableStateOf<Int?>(initialMinute) }
    var showDatePicker by remember { mutableStateOf(false) }

    val contentColor = MaterialTheme.colorScheme.onSurface
    val bgColor = MaterialTheme.colorScheme.background

    val candidateZdt: ZonedDateTime? = if (selectedHour != null && selectedMinute != null) {
        ZonedDateTime.of(selectedDate, LocalTime.of(selectedHour!!, selectedMinute!!), zone)
    } else {
        null
    }
    val candidateMillis = candidateZdt?.toInstant()?.toEpochMilli()
    val nowMillis = System.currentTimeMillis()
    val isFutureTime = candidateMillis != null && candidateMillis > nowMillis

    val hourDisplay = selectedHour?.let { String.format(Locale.US, "%02d", it) } ?: "--"
    val minuteDisplay = selectedMinute?.let { String.format(Locale.US, "%02d", it) } ?: "--"

    val today = LocalDate.now(zone)
    val dateSuffix = when (selectedDate) {
        today -> "（今天）"
        today.plusDays(1) -> "（明天）"
        else -> ""
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(332.dp),
            shape = RoundedCornerShape(18.dp),
            color = bgColor,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = contentColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // 标题与当前已选时间预览（未选时显示 --:--）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "笔记闹钟",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    Text(
                        text = "$hourDisplay:$minuteDisplay",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = if (selectedHour != null && selectedMinute != null) {
                            contentColor
                        } else {
                            contentColor.copy(alpha = 0.35f)
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 日期选择行（点击弹出极简日历）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            width = 1.dp,
                            color = contentColor.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { showDatePicker = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${selectedDate.format(DIALOG_DATE_FORMATTER)}$dateSuffix",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = contentColor
                    )
                    Text(
                        text = "修改日期",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.5f)
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 小时选择网格（4行 × 6列，00..23）
                Text(
                    text = "小时",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ALARM_HOURS.chunked(6).forEach { rowHours ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowHours.forEach { hour ->
                                val isSelected = selectedHour == hour
                                TimeSlotCell(
                                    label = String.format(Locale.US, "%02d", hour),
                                    selected = isSelected,
                                    onClick = { selectedHour = hour },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 分钟选择网格（2行 × 6列，5分钟步进：00..55）
                Text(
                    text = "分钟",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ALARM_MINUTES_STEP_5.chunked(6).forEach { rowMinutes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowMinutes.forEach { minute ->
                                val isSelected = selectedMinute == minute
                                TimeSlotCell(
                                    label = String.format(Locale.US, "%02d", minute),
                                    selected = isSelected,
                                    onClick = { selectedMinute = minute },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 轻量状态提示
                val hintText = when {
                    selectedHour == null || selectedMinute == null -> "请分别选择小时和分钟"
                    !isFutureTime -> "所选时间已过，请选择未来的时间"
                    else -> "将在 ${candidateZdt!!.format(DIALOG_HINT_FORMATTER)} 响铃"
                }
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedHour != null && selectedMinute != null && !isFutureTime) {
                        contentColor.copy(alpha = 0.75f)
                    } else {
                        contentColor.copy(alpha = 0.45f)
                    }
                )

                Spacer(Modifier.height(8.dp))

                // 底部操作栏：左侧清除（仅已有闹钟时显示），右侧取消与确定
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (existingActiveMillis != null) {
                        TextButton(onClick = onClear) {
                            Text(
                                text = "清除闹钟",
                                color = contentColor.copy(alpha = 0.65f)
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) {
                            Text("取消")
                        }
                        TextButton(
                            onClick = {
                                val target = candidateMillis ?: return@TextButton
                                if (target > System.currentTimeMillis()) {
                                    onConfirm(target)
                                }
                            },
                            enabled = isFutureTime
                        ) {
                            Text(
                                text = "确定",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DatePickerHelper(
            initialDate = selectedDate,
            noteDates = noteDates,
            onDatePicked = { picked ->
                selectedDate = picked
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

@Composable
private fun TimeSlotCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val bgColor = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) contentColor else bgColor)
            .border(
                width = 1.dp,
                color = if (selected) contentColor else contentColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) bgColor else contentColor.copy(alpha = 0.82f)
        )
    }
}
