package com.feiji.bulletnotes.ui

import android.app.DatePickerDialog
import android.content.Context
import java.time.LocalDate

object DatePickerHelper {

    fun show(
        context: Context,
        initialDate: LocalDate = LocalDate.now(),
        onDatePicked: (LocalDate) -> Unit,
        onDismiss: () -> Unit = {}
    ) {
        val year = initialDate.year
        val month = initialDate.monthValue - 1
        val day = initialDate.dayOfMonth

        val dialog = DatePickerDialog(
            context,
            { _, pickedYear, pickedMonth, pickedDay ->
                val selected = LocalDate.of(pickedYear, pickedMonth + 1, pickedDay)
                onDatePicked(selected)
            },
            year,
            month,
            day
        )
        // 用户点击日期选择框外部或按返回键关闭时，通知调用方清除弹窗状态。
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }
}
