package com.feiji.bulletnotes.ui

import android.app.DatePickerDialog
import android.content.Context
import java.time.LocalDate

object DatePickerHelper {

    /**
     * 显示日期选择框。
     * onDismiss 会在用户选择日期后、点击弹窗外部或按返回键关闭时调用，
     * 由调用方清除待处理的日期修改状态，避免弹窗在界面重组后再次出现。
     */
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

        dialog.setOnDismissListener {
            onDismiss()
        }
        dialog.show()
    }
}
