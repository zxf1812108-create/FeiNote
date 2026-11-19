package com.feiji.bulletnotes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.feiji.bulletnotes.data.NoteEntity
import com.feiji.bulletnotes.data.ReviewFamiliarity
import com.feiji.bulletnotes.data.SpacedRepetitionEngine
import java.time.LocalDate

/**
 * 长按五角星知识卡片时弹出的极简熟悉度选择窗：
 * - 展示当前复习进度摘要；
 * - 提供“陌生 / 大概了解 / 很熟悉”三档选项并实时预览下次复习间隔天数，点选即刻生效。
 */
@Composable
fun StarReviewDialog(
    note: NoteEntity,
    onSelectFamiliarity: (ReviewFamiliarity) -> Unit,
    onDismiss: () -> Unit
) {
    val today = remember(note.id) { LocalDate.now() }

    val options = remember(note.id, note.reviewCount, note.reviewIntervalDays, note.easeFactor, today) {
        ReviewFamiliarity.entries.map { familiarity ->
            familiarity to SpacedRepetitionEngine.computeNextReview(note, familiarity, today)
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

    val mutedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)

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
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "复习熟悉度",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
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
}
