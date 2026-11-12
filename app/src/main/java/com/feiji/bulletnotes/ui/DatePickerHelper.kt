package com.feiji.bulletnotes.ui

import android.app.DatePickerDialog
import android.content.Context
import android.view.View
import java.time.LocalDate

object DatePickerHelper {

    /**
     * 显示日期选择框。
     * 用户点选日期后立即回调并关闭弹窗，不需要再点击“确定”。
     * 点击弹窗外部或按返回键关闭时，调用 onDismiss 清除待处理状态。
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

        // 不使用默认的日期确认回调；日期变化时通过 DatePicker 监听器立即处理。
        val dialog = DatePickerDialog(
            context,
            null,
            year,
            month,
            day
        )

        dialog.setOnDismissListener {
            onDismiss()
        }

        dialog.setOnShowListener {
            // 隐藏系统默认的“确定”和“取消”按钮。
            dialog.getButton(DatePickerDialog.BUTTON_POSITIVE)?.visibility = View.GONE
            dialog.getButton(DatePickerDialog.BUTTON_NEGATIVE)?.visibility = View.GONE

            // 用户每次选中日期时立即更新笔记日期，并关闭日期选择框。
            dialog.datePicker.init(year, month, day) { _, pickedYear, pickedMonth, pickedDay ->
                val selected = LocalDate.of(
                    pickedYear,
                    pickedMonth + 1,
                    pickedDay
                )
                onDatePicked(selected)
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
