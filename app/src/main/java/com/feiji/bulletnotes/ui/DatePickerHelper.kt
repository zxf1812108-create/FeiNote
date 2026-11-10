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
        dialog.show()
    }
}
