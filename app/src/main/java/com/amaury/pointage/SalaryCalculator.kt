package com.amaury.pointage

import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.SessionStatusV2
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Façade historique de l'écran Salaire.
 * Quand V2 est actif, tous les résultats viennent des sessions V2 du contrat
 * demandé. Aucun autre employeur n'est mélangé au calcul.
 */
object SalaryCalculator {
    data class TierResult(val label:String,val durationMs:Long,val multiplier:Double)

    data class Result(
        val regularMs:Long,
        val overtimeTiers:List<TierResult>,
        val totalWorkedMs:Long,
        val workedGross:Double,
        val monthlyBaseGross:Double,
        val overtimeGross:Double,
        val nightMs:Long,
        val nightPremiumGross:Double,
        val saturdayMs:Long,
        val saturdayPremiumGross:Double,
        val sundayMs:Long,
        val sundayPremiumGross:Double,
        val monthlyEstimatedGross:Double,
        val completedSessions:Int
    )

    private data class WeekKey(val year:Int,val week:Int)

    fun calculate(
        data: JSONArray,
        year: Int,
        month: Int,
        hourlyRate: Double,
        convention: ConventionCatalog.Convention,
        companySlot: Int = 1
    ): Result {
        return if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.PAYROLL)) {
            calculateV2(year, month, hourlyRate, convention, companySlot.coerceIn(1, 2))
        } else {
            calculateLegacy(data, year, month, hourlyRate, convention)
        }
    }

    private fun calculateV2(
        year: Int,
        month: Int,
        fallbackRate: Double,
        convention: ConventionCatalog.Convention,
        companySlot: Int
    ): Result {
        val profile = V2ProfileStore.loadBound(companySlot)
        val contract = profile?.contract ?: return emptyResult(convention)
        val hourlyRate = contract.grossHourlyRate ?: fallbackRate.takeIf { it > 0.0 } ?: return emptyResult(convention)

        val sessions = V2RuntimeStore.allSessionsBound()
            .filter { session ->
                if (session.employerId != contract.employerId) return@filter false
                val entry = session.countedEntryMs ?: session.realArrivalMs ?: return@filter false
                val exit = session.realExitMs ?: return@filter false
                if (exit <= entry) return@filter false
                val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entry }
                cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month
            }
        if (sessions.isEmpty()) return emptyResult(convention)

        val weekly = linkedMapOf<WeekKey, Long>()
        sessions.forEach { session ->
            val entry = session.countedEntryMs ?: session.realArrivalMs ?: return@forEach
            val cal = Calendar.getInstance(Locale.FRANCE).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                timeInMillis = entry
            }
            val key = WeekKey(cal.getWeekYear(), cal.get(Calendar.WEEK_OF_YEAR))
            weekly[key] = (weekly[key] ?: 0L) + HoraTrackV2.time.calculate(session).paidWorkMs
        }

        val totalPaid = weekly.values.sum()
        val contractualMinutes = contract.contractualWeeklyMinutes
        val sourceTiers = if (convention.rulesIntegrated) convention.overtimeTiers else emptyList()
        val overtimeConfirmed = contract.type == ContractTypeV2.FULL_TIME &&
            contractualMinutes != null &&
            sourceTiers.firstOrNull()?.let { (it.fromHour * 60.0).roundToInt() == contractualMinutes } == true

        val tiers = if (overtimeConfirmed) sourceTiers else emptyList()
        val tierDurations = LongArray(tiers.size)
        var regularMs = 0L

        if (overtimeConfirmed && contractualMinutes != null) {
            val regularLimitMs = contractualMinutes * 60_000L
            weekly.values.forEach { paidMs ->
                regularMs += minOf(paidMs, regularLimitMs)
                tiers.forEachIndexed { index, tier ->
                    val from = (tier.fromHour * 3_600_000.0).toLong()
                    val to = tier.toHour?.let { (it * 3_600_000.0).toLong() } ?: Long.MAX_VALUE
                    tierDurations[index] += overlap(0L, paidMs, from, to)
                }
            }
        } else {
            regularMs = totalPaid
        }

        val tierResults = tiers.mapIndexed { index, tier ->
            TierResult(
                label = "Heures sup. +${((tier.multiplier - 1.0) * 100.0).roundToInt()} %",
                durationMs = tierDurations[index],
                multiplier = tier.multiplier
            )
        }
        val overtimeGross = tierResults.sumOf { it.durationMs / 3_600_000.0 * hourlyRate * it.multiplier }
        val regularGross = regularMs / 3_600_000.0 * hourlyRate

        val nightRule = ConventionNightRules.forIdcc(convention.idcc)
        var nightMs = 0L
        var saturdayMs = 0L
        var sundayMs = 0L
        sessions.forEach { session ->
            val start = session.countedEntryMs ?: session.realArrivalMs ?: return@forEach
            val end = session.countedExitMs ?: session.realExitMs ?: return@forEach
            if (end <= start) return@forEach
            val paid = HoraTrackV2.time.calculate(session).paidWorkMs
            val raw = (end - start).coerceAtLeast(1L)
            val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = start }
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SATURDAY -> saturdayMs += paid
                Calendar.SUNDAY -> sundayMs += paid
            }
            nightRule?.let { rule ->
                val rawNight = nightOverlap(start, end, rule.startMinute, rule.endMinute)
                nightMs += ((rawNight.toDouble() / raw.toDouble()) * paid).toLong().coerceIn(0L, paid)
            }
        }

        val nightPremium = nightRule?.let { nightMs / 3_600_000.0 * hourlyRate * (it.premiumMultiplier - 1.0) } ?: 0.0
        val monthlyBaseGross = regularGross
        val totalGross = regularGross + overtimeGross + nightPremium

        return Result(
            regularMs = regularMs,
            overtimeTiers = tierResults,
            totalWorkedMs = totalPaid,
            workedGross = totalGross,
            monthlyBaseGross = monthlyBaseGross,
            overtimeGross = overtimeGross,
            nightMs = nightMs,
            nightPremiumGross = nightPremium,
            saturdayMs = saturdayMs,
            saturdayPremiumGross = 0.0,
            sundayMs = sundayMs,
            sundayPremiumGross = 0.0,
            monthlyEstimatedGross = totalGross,
            completedSessions = sessions.count { it.status == SessionStatusV2.CLOSED }
        )
    }

    /** Rollback uniquement : jamais appelé lorsque V2 est actif. */
    private fun calculateLegacy(data:JSONArray, year:Int, month:Int, hourlyRate:Double, convention:ConventionCatalog.Convention):Result {
        var total = 0L
        var completed = 0
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.isNull("exit")) continue
            val entry = item.optLong("entry", -1L)
            val exit = item.optLong("exit", -1L)
            if (entry <= 0L || exit <= entry) continue
            val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entry }
            if (c.get(Calendar.YEAR) != year || c.get(Calendar.MONTH) != month) continue
            total += PointageStore.workedDuration(item, exit)
            completed++
        }
        val gross = total / 3_600_000.0 * hourlyRate.coerceAtLeast(0.0)
        return Result(total, emptyList(), total, gross, gross, 0.0, 0L, 0.0, 0L, 0.0, 0L, 0.0, gross, completed)
    }

    private fun emptyResult(convention:ConventionCatalog.Convention):Result = Result(
        regularMs = 0L,
        overtimeTiers = if (convention.rulesIntegrated) convention.overtimeTiers.map {
            TierResult("Heures sup. +${((it.multiplier - 1.0) * 100).roundToInt()} %", 0L, it.multiplier)
        } else emptyList(),
        totalWorkedMs = 0L,
        workedGross = 0.0,
        monthlyBaseGross = 0.0,
        overtimeGross = 0.0,
        nightMs = 0L,
        nightPremiumGross = 0.0,
        saturdayMs = 0L,
        saturdayPremiumGross = 0.0,
        sundayMs = 0L,
        sundayPremiumGross = 0.0,
        monthlyEstimatedGross = 0.0,
        completedSessions = 0
    )

    private fun nightOverlap(entry:Long, exit:Long, startMinute:Int, endMinute:Int):Long {
        if (exit <= entry) return 0L
        var total = 0L
        val day = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = entry
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val last = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = exit
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        while (day.timeInMillis <= last.timeInMillis) {
            val start = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, startMinute / 60); set(Calendar.MINUTE, startMinute % 60)
            }
            val end = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, endMinute / 60); set(Calendar.MINUTE, endMinute % 60)
                if (endMinute <= startMinute) add(Calendar.DAY_OF_YEAR, 1)
            }
            total += overlap(entry, exit, start.timeInMillis, end.timeInMillis)
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total
    }

    private fun overlap(start:Long, end:Long, rangeStart:Long, rangeEnd:Long):Long {
        val from = maxOf(start, rangeStart)
        val to = minOf(end, rangeEnd)
        return (to - from).coerceAtLeast(0L)
    }
}
