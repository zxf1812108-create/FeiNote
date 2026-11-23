package com.feiji.bulletnotes.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.feiji.bulletnotes.data.MemoryCurveConfig
import com.feiji.bulletnotes.data.NoteEntity
import com.feiji.bulletnotes.data.ReviewFamiliarity
import com.feiji.bulletnotes.data.SpacedRepetitionEngine
import java.time.LocalDate
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 长按五角星知识卡片时弹出的极简熟悉度选择窗：
 * - 展示当前复习进度摘要；
 * - 右上角提供低存在感的设置入口，用于自定义记忆曲线参数；
 * - 提供“陌生 / 大概了解 / 很熟悉”三档选项并实时预览下次复习间隔天数，点选即刻生效。
 */
@Composable
fun StarReviewDialog(
    note: NoteEntity,
    memoryCurveConfig: MemoryCurveConfig,
    onSaveMemoryCurveConfig: (MemoryCurveConfig) -> Unit,
    onSelectFamiliarity: (ReviewFamiliarity) -> Unit,
    onDismiss: () -> Unit
) {
    val today = remember(note.id) { LocalDate.now() }
    var showCurveSettings by remember { mutableStateOf(false) }

    val options = remember(
        note.id,
        note.reviewCount,
        note.reviewIntervalDays,
        note.easeFactor,
        note.lastReviewedAt,
        today,
        memoryCurveConfig
    ) {
        ReviewFamiliarity.entries.map { familiarity ->
            familiarity to SpacedRepetitionEngine.computeNextReview(
                note = note,
                familiarity = familiarity,
                today = today,
                config = memoryCurveConfig
            )
        }
    }

    val statusText = remember(note.reviewCount, note.reviewIntervalDays, note.lastReviewedAt) {
        when {
            note.lastReviewedAt == null && note.reviewCount == 0 && note.reviewIntervalDays == 0 ->
                "首次复习"
            note.reviewCount > 0 ->
                "已连续记住 ${note.reviewCount} 次 · 上次间隔 ${note.reviewIntervalDays.coerceAtLeast(1)} 天"
            else ->
                "上次间隔 ${note.reviewIntervalDays.coerceAtLeast(1)} 天 · 巩固中"
        }
    }

    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(248.dp)
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "复习熟悉度",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // 右上角低存在感记忆曲线参数设置图标
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showCurveSettings = true }
                            .semantics { contentDescription = "记忆曲线设置" },
                        contentAlignment = Alignment.Center
                    ) {
                        SubtleGearIcon(
                            color = mutedTextColor.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedTextColor
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { (familiarity, schedule) ->
                        val previewText = "+${schedule.nextIntervalDays}天"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = 1.dp,
                                    color = borderColor,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onSelectFamiliarity(familiarity) }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = familiarity.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = previewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCurveSettings) {
        MemoryCurveSettingsDialog(
            initialConfig = memoryCurveConfig,
            onSave = { updated ->
                onSaveMemoryCurveConfig(updated)
                showCurveSettings = false
            },
            onDismiss = { showCurveSettings = false }
        )
    }
}

/**
 * 极简细线齿轮图标：外围为连续封闭的 6 齿梯形折线轮廓，中心为同心轴孔圆。
 */
@Composable
private fun SubtleGearIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.35.dp.toPx()
        val stroke = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = (size.minDimension - strokeWidth) / 2f
        val outerR = maxR * 0.96f
        val rootR = maxR * 0.69f
        val holeR = maxR * 0.34f

        val teethCount = 6
        val stepDeg = 360.0 / teethCount // 60°
        val halfCrestDeg = 9.0
        val flankEndDeg = 16.0
        val nextFlankStartDeg = stepDeg - flankEndDeg // 44°
        val valleyMidDeg = stepDeg / 2.0 // 30°

        val gearPath = Path()
        for (i in 0 until teethCount) {
            val baseDeg = i * stepDeg - 90.0
            val points = arrayOf(
                Pair(baseDeg - halfCrestDeg, outerR),
                Pair(baseDeg + halfCrestDeg, outerR),
                Pair(baseDeg + flankEndDeg, rootR),
                Pair(baseDeg + valleyMidDeg, rootR),
                Pair(baseDeg + nextFlankStartDeg, rootR)
            )
            points.forEachIndexed { idx, (deg, r) ->
                val rad = Math.toRadians(deg)
                val x = cx + r * cos(rad).toFloat()
                val y = cy + r * sin(rad).toFloat()
                if (i == 0 && idx == 0) {
                    gearPath.moveTo(x, y)
                } else {
                    gearPath.lineTo(x, y)
                }
            }
        }
        gearPath.close()

        drawPath(
            path = gearPath,
            color = color,
            style = stroke
        )
        drawCircle(
            color = color,
            radius = holeR,
            center = Offset(cx, cy),
            style = stroke
        )
    }
}

/**
 * 记忆曲线参数自定义弹窗：
 * 支持调整初次/重学间隔、熟练倍率、大概了解系数与最大间隔上限，并实时预览曲线节奏。
 */
@Composable
private fun MemoryCurveSettingsDialog(
    initialConfig: MemoryCurveConfig,
    onSave: (MemoryCurveConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var unfamiliarText by remember(initialConfig) { mutableStateOf(initialConfig.unfamiliarDays.toString()) }
    var initialSomewhatText by remember(initialConfig) { mutableStateOf(initialConfig.initialSomewhatDays.toString()) }
    var initialVeryText by remember(initialConfig) { mutableStateOf(initialConfig.initialVeryDays.toString()) }
    var easeFactorText by remember(initialConfig) { mutableStateOf(formatDecimal(initialConfig.initialEaseFactor, 2)) }
    var somewhatMultText by remember(initialConfig) { mutableStateOf(formatDecimal(initialConfig.somewhatMultiplier, 2)) }
    var maxIntervalText by remember(initialConfig) { mutableStateOf(initialConfig.maxIntervalDays.toString()) }

    val draftConfig = remember(
        unfamiliarText,
        initialSomewhatText,
        initialVeryText,
        easeFactorText,
        somewhatMultText,
        maxIntervalText
    ) {
        MemoryCurveConfig(
            unfamiliarDays = unfamiliarText.toIntOrNull() ?: initialConfig.unfamiliarDays,
            initialSomewhatDays = initialSomewhatText.toIntOrNull() ?: initialConfig.initialSomewhatDays,
            initialVeryDays = initialVeryText.toIntOrNull() ?: initialConfig.initialVeryDays,
            initialEaseFactor = easeFactorText.toFloatOrNull() ?: initialConfig.initialEaseFactor,
            somewhatMultiplier = somewhatMultText.toFloatOrNull() ?: initialConfig.somewhatMultiplier,
            maxIntervalDays = maxIntervalText.toIntOrNull() ?: initialConfig.maxIntervalDays
        ).normalized()
    }

    val veryPreview = remember(draftConfig) {
        SpacedRepetitionEngine.simulateIntervals(draftConfig, ReviewFamiliarity.VERY_FAMILIAR, 4)
            .joinToString(" → ") { "${it}天" }
    }
    val somewhatPreview = remember(draftConfig) {
        SpacedRepetitionEngine.simulateIntervals(draftConfig, ReviewFamiliarity.SOMEWHAT_FAMILIAR, 4)
            .joinToString(" → ") { "${it}天" }
    }

    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(316.dp)
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp)
            ) {
                Text(
                    text = "记忆曲线参数",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "自定义五角星知识卡片的间隔递增规则",
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedTextColor
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IntParameterRow(
                        label = "陌生重学间隔",
                        unit = "天",
                        text = unfamiliarText,
                        min = 1,
                        max = 30,
                        step = 1,
                        fallback = draftConfig.unfamiliarDays,
                        onTextChange = { unfamiliarText = it }
                    )
                    IntParameterRow(
                        label = "首次大概了解",
                        unit = "天",
                        text = initialSomewhatText,
                        min = 1,
                        max = 60,
                        step = 1,
                        fallback = draftConfig.initialSomewhatDays,
                        onTextChange = { initialSomewhatText = it }
                    )
                    IntParameterRow(
                        label = "首次很熟悉",
                        unit = "天",
                        text = initialVeryText,
                        min = 1,
                        max = 90,
                        step = 1,
                        fallback = draftConfig.initialVeryDays,
                        onTextChange = { initialVeryText = it }
                    )

                    HorizontalDivider(color = borderColor.copy(alpha = 0.7f))

                    FloatParameterRow(
                        label = "初始熟练倍率",
                        unit = "×",
                        text = easeFactorText,
                        min = SpacedRepetitionEngine.MIN_EASE_FACTOR,
                        max = SpacedRepetitionEngine.MAX_EASE_FACTOR,
                        step = 0.1f,
                        fallback = draftConfig.initialEaseFactor,
                        onTextChange = { easeFactorText = it }
                    )
                    FloatParameterRow(
                        label = "大概了解系数",
                        unit = "×",
                        text = somewhatMultText,
                        min = 0.30f,
                        max = 1.00f,
                        step = 0.05f,
                        fallback = draftConfig.somewhatMultiplier,
                        onTextChange = { somewhatMultText = it }
                    )
                    IntParameterRow(
                        label = "最大间隔上限",
                        unit = "天",
                        text = maxIntervalText,
                        min = SpacedRepetitionEngine.MIN_MAX_INTERVAL_DAYS,
                        max = SpacedRepetitionEngine.HARD_MAX_INTERVAL_DAYS,
                        step = 30,
                        fallback = draftConfig.maxIntervalDays,
                        onTextChange = { maxIntervalText = it }
                    )

                    // 实时间隔推演预览
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "新卡节奏预览",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = mutedTextColor
                        )
                        Text(
                            text = "很熟悉：$veryPreview",
                            fontSize = 12.sp,
                            color = contentColor.copy(alpha = 0.88f)
                        )
                        Text(
                            text = "大概了解：$somewhatPreview",
                            fontSize = 12.sp,
                            color = mutedTextColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            val defaults = MemoryCurveConfig()
                            unfamiliarText = defaults.unfamiliarDays.toString()
                            initialSomewhatText = defaults.initialSomewhatDays.toString()
                            initialVeryText = defaults.initialVeryDays.toString()
                            easeFactorText = formatDecimal(defaults.initialEaseFactor, 2)
                            somewhatMultText = formatDecimal(defaults.somewhatMultiplier, 2)
                            maxIntervalText = defaults.maxIntervalDays.toString()
                        }
                    ) {
                        Text(
                            text = "恢复默认",
                            color = mutedTextColor
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "取消",
                                color = mutedTextColor
                            )
                        }
                        TextButton(onClick = { onSave(draftConfig) }) {
                            Text(
                                text = "保存",
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntParameterRow(
    label: String,
    unit: String,
    text: String,
    min: Int,
    max: Int,
    step: Int,
    fallback: Int,
    onTextChange: (String) -> Unit
) {
    ParameterStepperRow(
        label = label,
        unit = unit,
        text = text,
        keyboardType = KeyboardType.Number,
        onTextChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(4)
            onTextChange(filtered)
        },
        onDecrement = {
            val current = text.toIntOrNull() ?: fallback
            val next = (current - step).coerceIn(min, max)
            onTextChange(next.toString())
        },
        onIncrement = {
            val current = text.toIntOrNull() ?: fallback
            val next = (current + step).coerceIn(min, max)
            onTextChange(next.toString())
        }
    )
}

@Composable
private fun FloatParameterRow(
    label: String,
    unit: String,
    text: String,
    min: Float,
    max: Float,
    step: Float,
    fallback: Float,
    onTextChange: (String) -> Unit
) {
    ParameterStepperRow(
        label = label,
        unit = unit,
        text = text,
        keyboardType = KeyboardType.Decimal,
        onTextChange = { raw ->
            var dotSeen = false
            val filtered = buildString {
                for (ch in raw) {
                    if (ch.isDigit()) {
                        append(ch)
                    } else if (ch == '.' && !dotSeen) {
                        dotSeen = true
                        append(ch)
                    }
                }
            }.take(5)
            onTextChange(filtered)
        },
        onDecrement = {
            val current = text.toFloatOrNull() ?: fallback
            val next = (((current - step) * 100f).roundToInt() / 100f).coerceIn(min, max)
            onTextChange(formatDecimal(next, 2))
        },
        onIncrement = {
            val current = text.toFloatOrNull() ?: fallback
            val next = (((current + step) * 100f).roundToInt() / 100f).coerceIn(min, max)
            onTextChange(formatDecimal(next, 2))
        }
    )
}

@Composable
private fun ParameterStepperRow(
    label: String,
    unit: String,
    text: String,
    keyboardType: KeyboardType,
    onTextChange: (String) -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(7.dp))
                    .clickable(onClick = onDecrement),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "−",
                    fontSize = 15.sp,
                    color = contentColor
                )
            }

            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, borderColor, RoundedCornerShape(7.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = contentColor,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(7.dp))
                    .clickable(onClick = onIncrement),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    fontSize = 15.sp,
                    color = contentColor
                )
            }

            Text(
                text = unit,
                fontSize = 12.sp,
                color = mutedColor,
                modifier = Modifier.width(16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatDecimal(value: Float, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value).trimEnd('0').trimEnd('.')
