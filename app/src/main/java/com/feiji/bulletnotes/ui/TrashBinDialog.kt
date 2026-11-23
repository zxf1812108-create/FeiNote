package com.feiji.bulletnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feiji.bulletnotes.data.NoteEntity

private const val TRASH_RETENTION_DAYS_MS = 3L * 24L * 60L * 60L * 1000L

/**
 * 极简编辑风（FeiArticle 样式）回收站弹窗：
 * - 顶部栏展示“回收站”、保留 3 天自动清理提示、以及一键“清空”与关闭按钮；
 * - 列表采用细线轻边框卡片，展示内容摘要、原日期与剩余保留时间，支持单条恢复或彻底删除。
 */
@Composable
fun TrashBinDialog(
    trashNotes: List<NoteEntity>,
    onDismiss: () -> Unit,
    onRestore: (Long) -> Unit,
    onDeletePermanently: (Long) -> Unit,
    onEmptyTrash: () -> Unit
) {
    var showEmptyConfirm by remember { mutableStateOf(false) }

    val contentColor = MaterialTheme.colorScheme.onSurface
    val dialogSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.84f)
                .border(
                    width = 1.dp,
                    color = hairlineBorder,
                    shape = RoundedCornerShape(18.dp)
                ),
            shape = RoundedCornerShape(18.dp),
            color = dialogSurfaceColor,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // 顶部标题栏：左侧标题 + 自动清理规则说明；右侧清空按钮 + 关闭按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (trashNotes.isEmpty()) "回收站" else "回收站 · ${trashNotes.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "进入超过 3 天后自动彻底清除",
                            fontSize = 12.sp,
                            color = mutedText
                        )
                    }

                    if (trashNotes.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = 1.dp,
                                    color = hairlineBorder,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { showEmptyConfirm = true }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "清空",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = contentColor.copy(alpha = 0.72f)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                HorizontalDivider(color = hairlineBorder)

                Spacer(modifier = Modifier.height(10.dp))

                if (trashNotes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "回收站为空",
                            style = MaterialTheme.typography.bodyMedium,
                            color = mutedText
                        )
                    }
                } else {
                    val nowMillis = System.currentTimeMillis()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(trashNotes, key = { it.id }) { note ->
                            val deletedAt = note.deletedAt ?: note.updatedAt
                            val remainingText = formatTrashRemainingTime(deletedAt, nowMillis)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = 1.dp,
                                        color = hairlineBorder,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (note.content.isBlank()) "（空笔记）" else note.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = contentColor
                                    )
                                    Spacer(modifier = Modifier.height(5.dp))
                                    Text(
                                        text = "${note.dateString} · $remainingText",
                                        fontSize = 11.sp,
                                        color = mutedText
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // 恢复按钮
                                val restoreTint = contentColor.copy(alpha = 0.75f)
                                IconButton(
                                    onClick = { onRestore(note.id) },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .semantics { contentDescription = "恢复" }
                                ) {
                                    Canvas(modifier = Modifier.size(18.dp)) {
                                        val strokePx = 1.7.dp.toPx()
                                        val stroke = Stroke(
                                            width = strokePx,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                        val inset = strokePx + 1.2.dp.toPx()
                                        val arcSize = size.width - inset * 2f
                                        drawArc(
                                            color = restoreTint,
                                            startAngle = -145f,
                                            sweepAngle = 290f,
                                            useCenter = false,
                                            topLeft = Offset(inset, inset),
                                            size = Size(arcSize, arcSize),
                                            style = stroke
                                        )
                                        val arrowTipX = inset + arcSize * 0.08f
                                        val arrowTipY = inset + arcSize * 0.24f
                                        val arrowLen = arcSize * 0.28f
                                        val arrow = Path().apply {
                                            moveTo(arrowTipX, arrowTipY - arrowLen * 0.95f)
                                            lineTo(arrowTipX, arrowTipY)
                                            lineTo(arrowTipX + arrowLen, arrowTipY)
                                        }
                                        drawPath(path = arrow, color = restoreTint, style = stroke)
                                    }
                                }

                                // 单条彻底删除按钮
                                IconButton(
                                    onClick = { onDeletePermanently(note.id) },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .semantics { contentDescription = "彻底删除" }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "彻底删除",
                                        tint = contentColor.copy(alpha = 0.42f),
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            containerColor = dialogSurfaceColor,
            titleContentColor = contentColor,
            textContentColor = contentColor.copy(alpha = 0.82f),
            title = {
                Text(
                    text = "清空回收站",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("确定彻底删除回收站中的全部 ${trashNotes.size} 条笔记吗？清空后无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyConfirm = false
                        onEmptyTrash()
                    }
                ) {
                    Text(
                        text = "清空",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) {
                    Text(
                        text = "取消",
                        color = mutedText
                    )
                }
            }
        )
    }
}

private fun formatTrashRemainingTime(deletedAtMillis: Long, nowMillis: Long): String {
    val remainMs = (deletedAtMillis + TRASH_RETENTION_DAYS_MS - nowMillis).coerceAtLeast(0L)
    val remainHours = remainMs / (60L * 60L * 1000L)
    return when {
        remainHours <= 1L -> "即将自动清理"
        remainHours < 24L -> "约 ${remainHours} 小时后清理"
        else -> {
            val days = ((remainHours + 23L) / 24L).coerceIn(1L, 3L)
            "约 ${days} 天后清理"
        }
    }
}
