package com.feiji.bulletnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class AlarmDialMode {
    Hour,
    Minute
}

private val DIAL_OUTER_HOURS = listOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
private val DIAL_INNER_HOURS = listOf(0, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)
private val DIAL_MINUTES_STEP_5 = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55)

private val DIALOG_HINT_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/**
 * 极简黑白表盘式笔记闹钟设置弹窗：
 * - 日期固定为当前笔记所属日期（不可在此修改，笔记调整日期时闹钟日期同步跟随）。
 * - 不设默认时间：未设闹钟时小时与分钟均为空（--:--），表盘无默认指针，需用户显式点选。
 * - 支持 24 小时双圈表盘点选/滑动（选完小时自动切到分钟），分钟按 5 分钟步进。
 */
@Composable
fun NoteAlarmDialog(
    note: NoteEntity,
    onConfirm: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val noteDate = remember(note.id, note.dateString) {
        runCatching { LocalDate.parse(note.dateString) }.getOrDefault(today)
    }
    val existingActiveMillis = remember(note.id, note.alarmTimeMillis) {
        note.alarmTimeMillis?.takeIf { it > System.currentTimeMillis() }
    }

    val initialHour = remember(note.id, existingActiveMillis) {
        existingActiveMillis?.let { Instant.ofEpochMilli(it).atZone(zone).hour }
    }
    val initialMinute = remember(note.id, existingActiveMillis) {
        existingActiveMillis?.let { Instant.ofEpochMilli(it).atZone(zone).minute }
    }

    var selectedHour by remember(note.id) { mutableStateOf<Int?>(initialHour) }
    var selectedMinute by remember(note.id) { mutableStateOf<Int?>(initialMinute) }
    var dialMode by remember(note.id) { mutableStateOf(AlarmDialMode.Hour) }

    val contentColor = MaterialTheme.colorScheme.onSurface
    val bgColor = MaterialTheme.colorScheme.background

    val candidateZdt: ZonedDateTime? = if (selectedHour != null && selectedMinute != null) {
        ZonedDateTime.of(noteDate, LocalTime.of(selectedHour!!, selectedMinute!!), zone)
    } else {
        null
    }
    val candidateMillis = candidateZdt?.toInstant()?.toEpochMilli()
    val nowMillis = System.currentTimeMillis()
    val isPastNoteDate = noteDate.isBefore(today)
    val isFutureTime = !isPastNoteDate && candidateMillis != null && candidateMillis > nowMillis

    val hourDisplay = selectedHour?.let { String.format(Locale.US, "%02d", it) } ?: "--"
    val minuteDisplay = selectedMinute?.let { String.format(Locale.US, "%02d", it) } ?: "--"

    val hintText = when {
        isPastNoteDate -> "该笔记日期已过，调整笔记日期后可设闹钟"
        selectedHour == null && selectedMinute == null -> "请点选表盘设置小时和分钟"
        selectedHour == null -> "请点选表盘设置小时"
        selectedMinute == null -> "请点选表盘设置分钟（5分钟步进）"
        !isFutureTime -> "所选时间已过，请选择晚于当前的时间"
        else -> "将在 ${candidateZdt!!.format(DIALOG_HINT_FORMATTER)} 响铃"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(336.dp),
            shape = RoundedCornerShape(22.dp),
            color = bgColor,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = contentColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部居中淡化引导提示（替代原“设置闹钟”与日期标签）
                Text(
                    text = hintText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    letterSpacing = 0.3.sp,
                    textAlign = TextAlign.Center,
                    color = if (isPastNoteDate || (selectedHour != null && selectedMinute != null && !isFutureTime)) {
                        contentColor.copy(alpha = 0.72f)
                    } else {
                        contentColor.copy(alpha = 0.45f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                // 居中大号时间显示（可点击小时/分钟切换表盘）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    TimeSegmentChip(
                        text = hourDisplay,
                        active = dialMode == AlarmDialMode.Hour,
                        hasValue = selectedHour != null,
                        onClick = { dialMode = AlarmDialMode.Hour }
                    )
                    Text(
                        text = ":",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                        color = contentColor.copy(alpha = 0.45f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    TimeSegmentChip(
                        text = minuteDisplay,
                        active = dialMode == AlarmDialMode.Minute,
                        hasValue = selectedMinute != null,
                        onClick = { dialMode = AlarmDialMode.Minute }
                    )
                }

                Spacer(Modifier.height(20.dp))

                // 极简黑白模拟表盘
                MinimalistClockDial(
                    mode = dialMode,
                    selectedHour = selectedHour,
                    selectedMinute = selectedMinute,
                    onSelectHour = { hour -> selectedHour = hour },
                    onHourSelectionFinished = { dialMode = AlarmDialMode.Minute },
                    onSelectMinute = { minute -> selectedMinute = minute }
                )

                Spacer(Modifier.height(16.dp))

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
                                if (!isPastNoteDate && target > System.currentTimeMillis()) {
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
}

@Composable
private fun TimeSegmentChip(
    text: String,
    active: Boolean,
    hasValue: Boolean,
    onClick: () -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val bgColor = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .width(78.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) contentColor.copy(alpha = 0.08f) else bgColor)
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) contentColor.copy(alpha = 0.75f) else contentColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = if (hasValue) contentColor else contentColor.copy(alpha = 0.32f)
        )
    }
}

@Composable
private fun MinimalistClockDial(
    mode: AlarmDialMode,
    selectedHour: Int?,
    selectedMinute: Int?,
    onSelectHour: (Int) -> Unit,
    onHourSelectionFinished: () -> Unit,
    onSelectMinute: (Int) -> Unit
) {
    val dialSize = 264.dp
    val outerRadiusDp = 102.dp
    val innerRadiusDp = 64.dp
    val minuteRadiusDp = 100.dp
    val density = LocalDensity.current

    val contentColor = MaterialTheme.colorScheme.onSurface
    val bgColor = MaterialTheme.colorScheme.background

    val dialSizePx = with(density) { dialSize.toPx() }
    val centerPx = dialSizePx / 2f
    val outerRadiusPx = with(density) { outerRadiusDp.toPx() }
    val innerRadiusPx = with(density) { innerRadiusDp.toPx() }
    val minuteRadiusPx = with(density) { minuteRadiusDp.toPx() }
    val ringSplitPx = (outerRadiusPx + innerRadiusPx) / 2f
    val minTouchRadiusPx = with(density) { 10.dp.toPx() }
    val selectorRadiusPx = with(density) { 17.dp.toPx() }
    val labelBoxSize = 36.dp
    val halfLabelBoxPx = with(density) { (labelBoxSize / 2).toPx() }

    // 当前表盘指针目标角度（弧度，12点为 -PI/2）与半径；未选择时为 null，不绘制指针
    val handTarget: Pair<Double, Float>? = when (mode) {
        AlarmDialMode.Hour -> selectedHour?.let { hour ->
            val sector = hour % 12
            val isInner = (hour == 0 || hour >= 13)
            val rad = Math.toRadians(sector * 30.0 - 90.0)
            rad to if (isInner) innerRadiusPx else outerRadiusPx
        }

        AlarmDialMode.Minute -> selectedMinute?.let { minute ->
            val rad = Math.toRadians(minute * 6.0 - 90.0)
            rad to minuteRadiusPx
        }
    }

    fun pickFromOffset(position: Offset): Boolean {
        val dx = position.x - centerPx
        val dy = position.y - centerPx
        val dist = hypot(dx, dy)
        if (dist < minTouchRadiusPx) return false
        val rawDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
        val deg = ((rawDeg % 360.0) + 360.0) % 360.0
        val sector = ((deg + 15.0) / 30.0).toInt() % 12

        if (mode == AlarmDialMode.Hour) {
            val isInner = dist < ringSplitPx
            val pickedHour = if (isInner) DIAL_INNER_HOURS[sector] else DIAL_OUTER_HOURS[sector]
            onSelectHour(pickedHour)
        } else {
            val pickedMinute = DIAL_MINUTES_STEP_5[sector]
            onSelectMinute(pickedMinute)
        }
        return true
    }

    Box(
        modifier = Modifier
            .size(dialSize)
            .clip(CircleShape)
            .pointerInput(mode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var picked = pickFromOffset(down.position)
                    var pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (change.pressed) {
                            if (pickFromOffset(change.position)) {
                                picked = true
                            }
                            change.consume()
                        } else {
                            change.consume()
                            break
                        }
                    }
                    if (picked && mode == AlarmDialMode.Hour) {
                        onHourSelectionFinished()
                    }
                }
            }
    ) {
        // 表盘底面、刻度线、指针与选中圆点
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val faceRadius = size.minDimension / 2f

            // 极淡表盘底色与外圈细线
            drawCircle(
                color = contentColor.copy(alpha = 0.045f),
                radius = faceRadius,
                center = center
            )
            drawCircle(
                color = contentColor.copy(alpha = 0.14f),
                radius = faceRadius - 0.8.dp.toPx(),
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )

            // 12 个极细整点刻度
            val tickOuter = faceRadius - 4.dp.toPx()
            val tickInner = faceRadius - 8.dp.toPx()
            for (i in 0 until 12) {
                val rad = Math.toRadians(i * 30.0 - 90.0)
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()
                drawLine(
                    color = contentColor.copy(alpha = if (i % 3 == 0) 0.24f else 0.12f),
                    start = Offset(center.x + tickInner * cosA, center.y + tickInner * sinA),
                    end = Offset(center.x + tickOuter * cosA, center.y + tickOuter * sinA),
                    strokeWidth = if (i % 3 == 0) 1.4.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 小时模式下绘制极淡内圈分隔环，清晰区分 1..12 与 00/13..23
            if (mode == AlarmDialMode.Hour) {
                drawCircle(
                    color = contentColor.copy(alpha = 0.07f),
                    radius = ringSplitPx,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.8.dp.toPx())
                )
            }

            // 选中指针与端点实心圆
            if (handTarget != null) {
                val (rad, radius) = handTarget
                val tip = Offset(
                    x = center.x + radius * cos(rad).toFloat(),
                    y = center.y + radius * sin(rad).toFloat()
                )
                drawLine(
                    color = contentColor,
                    start = center,
                    end = tip,
                    strokeWidth = 1.8.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = contentColor,
                    radius = selectorRadiusPx,
                    center = tip
                )
            }

            // 表盘中心轴点
            drawCircle(
                color = contentColor.copy(alpha = if (handTarget != null) 1f else 0.35f),
                radius = 3.5.dp.toPx(),
                center = center
            )
        }

        // 数字刻度层
        if (mode == AlarmDialMode.Hour) {
            // 外圈：12, 1..11
            DIAL_OUTER_HOURS.forEachIndexed { sector, hour ->
                val rad = Math.toRadians(sector * 30.0 - 90.0)
                val x = (centerPx + outerRadiusPx * cos(rad) - halfLabelBoxPx).roundToInt()
                val y = (centerPx + outerRadiusPx * sin(rad) - halfLabelBoxPx).roundToInt()
                val selected = selectedHour == hour
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x, y) }
                        .size(labelBoxSize),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = hour.toString(),
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) bgColor else contentColor.copy(alpha = 0.88f)
                    )
                }
            }

            // 内圈：00, 13..23
            DIAL_INNER_HOURS.forEachIndexed { sector, hour ->
                val rad = Math.toRadians(sector * 30.0 - 90.0)
                val x = (centerPx + innerRadiusPx * cos(rad) - halfLabelBoxPx).roundToInt()
                val y = (centerPx + innerRadiusPx * sin(rad) - halfLabelBoxPx).roundToInt()
                val selected = selectedHour == hour
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x, y) }
                        .size(labelBoxSize),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(Locale.US, "%02d", hour),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) bgColor else contentColor.copy(alpha = 0.56f)
                    )
                }
            }
        } else {
            // 分钟表盘：5 分钟步进（00, 05, 10, ..., 55）
            DIAL_MINUTES_STEP_5.forEachIndexed { sector, minute ->
                val rad = Math.toRadians(sector * 30.0 - 90.0)
                val x = (centerPx + minuteRadiusPx * cos(rad) - halfLabelBoxPx).roundToInt()
                val y = (centerPx + minuteRadiusPx * sin(rad) - halfLabelBoxPx).roundToInt()
                val selected = selectedMinute == minute
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x, y) }
                        .size(labelBoxSize),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(Locale.US, "%02d", minute),
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) bgColor else contentColor.copy(alpha = 0.86f)
                    )
                }
            }
        }
    }
}
