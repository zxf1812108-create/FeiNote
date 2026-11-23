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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.YearMonth

/**
 * 带笔记日期圆点标记的日历。
 * 支持点击年份快速选择年份、点击月份快速选择月份；
 * 点选日期后立即回调并关闭；点按外部或返回键则只关闭弹窗。
 */
@Composable
fun DatePickerHelper(
    initialDate: LocalDate,
    noteDates: Set<LocalDate>,
    onDatePicked: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var displayedMonth by remember(initialDate) { mutableStateOf(YearMonth.from(initialDate)) }
    var showYearPicker by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }

    val noteYears = remember(noteDates) { noteDates.map { it.year }.toSet() }
    val noteYearMonths = remember(noteDates) { noteDates.map { YearMonth.from(it) }.toSet() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(18.dp)
                ),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { displayedMonth = displayedMonth.minusMonths(1) }) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showYearPicker = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${displayedMonth.year}年",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showMonthPicker = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${displayedMonth.monthValue}月",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    TextButton(onClick = { displayedMonth = displayedMonth.plusMonths(1) }) {
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                text = day,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                val firstDay = displayedMonth.atDay(1)
                val leadingBlanks = firstDay.dayOfWeek.value - 1 // 周一作为每周第一天
                val cells: List<LocalDate?> = buildList {
                    repeat(leadingBlanks) { add(null) }
                    for (day in 1..displayedMonth.lengthOfMonth()) add(displayedMonth.atDay(day))
                    while (size < 42) add(null) // 固定为6行，避免不同月份改变弹窗高度
                }

                cells.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (date != null) {
                                    val isSelected = date == initialDate
                                    val isToday = date == LocalDate.now()
                                    Column(
                                        modifier = Modifier
                                            .size(width = 38.dp, height = 44.dp)
                                            .clickable { onDatePicked(date) },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .then(
                                                    if (isSelected) Modifier
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                    else Modifier
                                                )
                                                .then(
                                                    if (isToday && !isSelected) Modifier.border(
                                                        width = 1.dp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = CircleShape
                                                    ) else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = date.dayOfMonth.toString(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        if (date in noteDates) {
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.onSurface)
                                            )
                                        } else {
                                            Spacer(Modifier.size(4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { displayedMonth = YearMonth.from(LocalDate.now()) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        "回到本月",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // 年份选择弹窗
    if (showYearPicker) {
        val currentYear = LocalDate.now().year
        val minYear = minOf(2000, displayedMonth.year - 20, noteYears.minOrNull() ?: currentYear)
        val maxYear = maxOf(currentYear + 20, displayedMonth.year + 20, noteYears.maxOrNull() ?: currentYear)
        val yearRows = remember(minYear, maxYear) {
            (minYear..maxYear).toList().chunked(3)
        }
        val selectedRowIndex = remember(yearRows, displayedMonth.year) {
            yearRows.indexOfFirst { displayedMonth.year in it }.coerceAtLeast(0)
        }
        val yearListState = rememberLazyListState(
            initialFirstVisibleItemIndex = (selectedRowIndex - 2).coerceAtLeast(0)
        )

        Dialog(onDismissRequest = { showYearPicker = false }) {
            Surface(
                modifier = Modifier
                    .width(280.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(18.dp)
                    ),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "选择年份",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    LazyColumn(
                        state = yearListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 264.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(yearRows) { rowYears ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowYears.forEach { year ->
                                    val isSelected = year == displayedMonth.year
                                    val isCurrent = year == currentYear
                                    val hasNotes = year in noteYears
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .then(
                                                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                                else Modifier
                                            )
                                            .then(
                                                if (isCurrent) Modifier.border(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(10.dp)
                                                ) else Modifier
                                            )
                                            .clickable {
                                                displayedMonth = YearMonth.of(year, displayedMonth.monthValue)
                                                showYearPicker = false
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "${year}年",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected || isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        if (hasNotes) {
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                        else MaterialTheme.colorScheme.onSurface
                                                    )
                                            )
                                        } else {
                                            Spacer(Modifier.size(4.dp))
                                        }
                                    }
                                }
                                repeat(3 - rowYears.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { showYearPicker = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }

    // 月份选择弹窗
    if (showMonthPicker) {
        val currentYm = YearMonth.from(LocalDate.now())
        val monthRows = remember { (1..12).toList().chunked(3) }

        Dialog(onDismissRequest = { showMonthPicker = false }) {
            Surface(
                modifier = Modifier
                    .width(280.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(18.dp)
                    ),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "${displayedMonth.year}年 · 选择月份",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        monthRows.forEach { rowMonths ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowMonths.forEach { month ->
                                    val ym = YearMonth.of(displayedMonth.year, month)
                                    val isSelected = month == displayedMonth.monthValue
                                    val isCurrent = ym == currentYm
                                    val hasNotes = ym in noteYearMonths
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .then(
                                                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                                else Modifier
                                            )
                                            .then(
                                                if (isCurrent) Modifier.border(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(10.dp)
                                                ) else Modifier
                                            )
                                            .clickable {
                                                displayedMonth = ym
                                                showMonthPicker = false
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "${month}月",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected || isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        if (hasNotes) {
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                        else MaterialTheme.colorScheme.onSurface
                                                    )
                                            )
                                        } else {
                                            Spacer(Modifier.size(4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { showMonthPicker = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
