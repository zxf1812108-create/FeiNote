package com.feiji.bulletnotes.ui

import android.app.DatePickerDialog
import android.content.Context
import java.time.LocalDate

object DatePickerHelper {

    fun show(
        context: Context,
        initialDate: LocalDate = LocalDate.now(),
        onDatePicked: (LocalDate) -> Unit
    ) {
        val dialog = DatePickerDialog(
            context,
            { _, year, month, day ->
                onDatePicked(LocalDate.of(year, month + 1, day))
            },
            initialDate.year,
            initialDate.monthValue - 1,
            initialDate.dayOfMonth
        )

        // 仅保留日历。日期发生选择时立即提交，不再要求点击“确定”。
        // init() 设置的回调不会在初始化时触发。
        dialog.datePicker.init(
            initialDate.year,
            initialDate.monthValue - 1,
            initialDate.dayOfMonth
        ) { _, year, month, day ->
            onDatePicked(LocalDate.of(year, month + 1, day))
            dialog.dismiss()
        }
        dialog.show()
        // show() 后清除按钮，避免系统在显示时重新创建按钮。
        dialog.getButton(DatePickerDialog.BUTTON_POSITIVE)?.visibility = android.view.View.GONE
        dialog.getButton(DatePickerDialog.BUTTON_NEGATIVE)?.visibility = android.view.View.GONE
    }
}
