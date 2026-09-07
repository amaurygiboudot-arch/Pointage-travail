package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

/** Calcule uniquement le temps PAYÉ d'une session qui tombe sur des dates fériées déjà déterminées. */
object PublicHolidayPremiumPolicyV2 {
    fun paidOverlap(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        holidayDates: Set<LocalDate>
    ): Long {
        if (rangeEndMs <= rangeStartMs || holidayDates.isEmpty()) return 0L
        var total = 0L
        val day = midnight(rangeStartMs)
        val last = midnight(rangeEndMs)
        while (day.timeInMillis <= last.timeInMillis) {
            val date = LocalDate.of(
                day.get(Calendar.YEAR),
                day.get(Calendar.MONTH) + 1,
                day.get(Calendar.DAY_OF_MONTH)
            )
            if (date in holidayDates) {
                val next = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                val from = maxOf(rangeStartMs, day.timeInMillis)
                val to = minOf(rangeEndMs, next.timeInMillis)
                if (to > from) total += PaidWorkAllocationV2.paidOverlap(session, from, to)
            }
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total
    }

    private fun midnight(ms: Long): Calendar = Calendar.getInstance(Locale.FRANCE).apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
