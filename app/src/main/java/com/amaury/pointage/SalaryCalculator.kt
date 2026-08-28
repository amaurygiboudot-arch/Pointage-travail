package com.amaury.pointage

import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.OvertimeTierV2
import com.amaury.pointage.v2.engine.PayrollEngineV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.engine.PayrollWeekV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.SessionStatusV2
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

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

    private data class Session(val entry:Long,val exit:Long,val workedDuration:Long)
    private data class WeekKey(val year:Int,val week:Int)

    fun calculate(data:JSONArray,year:Int,month:Int,hourlyRate:Double,convention:ConventionCatalog.Convention):Result {
        if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.PAYROLL)) {
            return calculateV2(year, month, hourlyRate, convention)
        }
        return calculateLegacy(data, year, month, hourlyRate, convention)
    }

    private fun calculateV2(year:Int, month:Int, hourlyRate:Double, convention:ConventionCatalog.Convention):Result {
        val sessions = V2RuntimeStore.allSessionsBound()
            .filter { s ->
                val entry = s.realArrivalMs ?: return@filter false
                val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entry }
                c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month
            }
        if (sessions.isEmpty() || hourlyRate <= 0.0) return emptyResult(convention)

        val weekly = linkedMapOf<WeekKey, Int>()
        val totalPaidMs = sessions.sumOf { s -> HoraTrackV2.time.calculate(s).paidWorkMs }
        sessions.forEach { s ->
            val entry = s.realArrivalMs ?: return@forEach
            val paidMinutes = (HoraTrackV2.time.calculate(s).paidWorkMs / 60_000L).toInt()
            val c = Calendar.getInstance(Locale.FRANCE).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                timeInMillis = entry
            }
            val key = WeekKey(c.getWeekYear(), c.get(Calendar.WEEK_OF_YEAR))
            weekly[key] = (weekly[key] ?: 0) + paidMinutes
        }

        val sourceTiers = convention.overtimeTiers
        val weeklyRegular = ((sourceTiers.firstOrNull()?.fromHour ?: 35.0) * 60.0).roundToInt()
        val v2Tiers = sourceTiers.map {
            OvertimeTierV2((it.fromHour * 60.0).roundToInt(), it.toHour?.let { h -> (h * 60.0).roundToInt() }, it.multiplier)
        }
        val rules = PayrollRulesV2(
            weeklyRegularMinutes = weeklyRegular,
            overtimeTiers = v2Tiers,
            nightMultiplier = null,
            saturdayMultiplier = null,
            sundayMultiplier = null
        )
        val payroll = PayrollEngineV2.calculate(
            ContractV2("v2-salary", "v2-employer", ContractTypeV2.FULL_TIME, weeklyRegular, hourlyRate, null),
            weekly.values.map { PayrollWeekV2(it) },
            rules
        )

        val tierMinutes = LongArray(v2Tiers.size)
        weekly.values.forEach { paid ->
            v2Tiers.forEachIndexed { index, tier ->
                val end = tier.toMinutes ?: Int.MAX_VALUE
                val minutes = (minOf(paid, end) - maxOf(weeklyRegular, tier.fromMinutes)).coerceAtLeast(0)
                tierMinutes[index] += minutes.toLong()
            }
        }
        val tiers = v2Tiers.mapIndexed { index, tier ->
            TierResult("Heures sup. +${((tier.multiplier - 1.0) * 100).roundToInt()} %", tierMinutes[index] * 60_000L, tier.multiplier)
        }
        val overtimeMs = tierMinutes.sum() * 60_000L
        val regularMs = (totalPaidMs - overtimeMs).coerceAtLeast(0L)
        return Result(
            regularMs = regularMs,
            overtimeTiers = tiers,
            totalWorkedMs = totalPaidMs,
            workedGross = payroll.grossEstimate,
            monthlyBaseGross = payroll.regularGross,
            overtimeGross = payroll.overtimeGross,
            nightMs = 0L,
            nightPremiumGross = 0.0,
            saturdayMs = 0L,
            saturdayPremiumGross = 0.0,
            sundayMs = 0L,
            sundayPremiumGross = 0.0,
            monthlyEstimatedGross = payroll.grossEstimate,
            completedSessions = sessions.count { it.status == SessionStatusV2.CLOSED }
        )
    }

    private fun emptyResult(convention:ConventionCatalog.Convention):Result = Result(
        0L,
        convention.overtimeTiers.map { TierResult("Heures sup. +${((it.multiplier-1.0)*100).roundToInt()} %",0L,it.multiplier) },
        0L,0.0,0.0,0.0,0L,0.0,0L,0.0,0L,0.0,0.0,0
    )

    private fun calculateLegacy(data:JSONArray,year:Int,month:Int,hourlyRate:Double,convention:ConventionCatalog.Convention):Result {
        val sessions=mutableListOf<Session>()
        for(i in 0 until data.length()){
            val item=data.optJSONObject(i)?:continue
            if(item.isNull("exit"))continue
            val entry=item.optLong("entry",-1L);val exit=item.optLong("exit",-1L)
            if(entry>0L&&exit>entry) sessions+=Session(entry,exit,PointageStore.workedDuration(item,exit))
        }
        sessions.sortBy{it.entry}

        val weeks=linkedMapOf<WeekKey,MutableList<Session>>()
        sessions.forEach{s->val c=Calendar.getInstance(Locale.FRANCE).apply{firstDayOfWeek=Calendar.MONDAY;minimalDaysInFirstWeek=4;timeInMillis=s.entry};weeks.getOrPut(WeekKey(c.getWeekYear(),c.get(Calendar.WEEK_OF_YEAR))){mutableListOf()}.add(s)}

        val hourMs=3_600_000L;val normalLimit=35L*hourMs
        var regularMs=0L;var completed=0;val overtime=LongArray(convention.overtimeTiers.size)
        var nightMs=0L;var saturdayMs=0L;var sundayMs=0L
        var saturdayPremium=0.0;var sundayPremium=0.0;var nightPremium=0.0

        weeks.values.forEach{weekSessions->
            var cumulative=0L
            weekSessions.sortedBy{it.entry}.forEach{s->
                val duration=s.workedDuration
                if(duration<=0L){return@forEach}
                val startCum=cumulative;val endCum=cumulative+duration
                val cal=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=s.entry}
                val inMonth=cal.get(Calendar.YEAR)==year&&cal.get(Calendar.MONTH)==month
                if(inMonth){
                    val regularPart=overlap(startCum,endCum,0L,normalLimit)
                    regularMs+=regularPart
                    var sessionValue=(regularPart/hourMs.toDouble())*hourlyRate
                    convention.overtimeTiers.forEachIndexed{idx,tier->
                        val from=(tier.fromHour*hourMs).toLong();val to=tier.toHour?.let{(it*hourMs).toLong()}?:Long.MAX_VALUE
                        val d=overlap(startCum,endCum,from,to);overtime[idx]+=d;sessionValue+=(d/hourMs.toDouble())*hourlyRate*tier.multiplier
                    }
                    val dayFactor=when(cal.get(Calendar.DAY_OF_WEEK)){Calendar.SATURDAY->1.25;Calendar.SUNDAY->1.50;else->1.0}
                    when(cal.get(Calendar.DAY_OF_WEEK)){
                        Calendar.SATURDAY->{saturdayMs+=duration;saturdayPremium+=sessionValue*.25}
                        Calendar.SUNDAY->{sundayMs+=duration;sundayPremium+=sessionValue*.50}
                    }
                    val raw=s.exit-s.entry
                    val rawNight=nightOverlap(s.entry,s.exit,21*60,6*60)
                    val paidNight=if(raw<=0L)0L else ((rawNight.toDouble()/raw.toDouble())*duration).toLong().coerceIn(0L,duration)
                    nightMs+=paidNight
                    val nightShare=paidNight/duration.toDouble()
                    nightPremium+=sessionValue*dayFactor*nightShare*.25
                    completed++
                }
                cumulative=endCum
            }
        }

        val tiers=convention.overtimeTiers.mapIndexed{idx,t->TierResult("Heures sup. +${((t.multiplier-1.0)*100).toInt()} %",overtime[idx],t.multiplier)}
        val overtimeGross=tiers.sumOf{(it.durationMs/hourMs.toDouble())*hourlyRate*it.multiplier}
        val totalOvertime=tiers.sumOf{it.durationMs}
        val workedGross=(regularMs/hourMs.toDouble())*hourlyRate+overtimeGross+saturdayPremium+sundayPremium+nightPremium
        val monthlyBaseGross=hourlyRate*151.67
        val monthlyEstimatedGross=monthlyBaseGross+overtimeGross+saturdayPremium+sundayPremium+nightPremium
        return Result(regularMs,tiers,regularMs+totalOvertime,workedGross,monthlyBaseGross,overtimeGross,nightMs,nightPremium,saturdayMs,saturdayPremium,sundayMs,sundayPremium,monthlyEstimatedGross,completed)
    }

    private fun nightOverlap(entry:Long,exit:Long,startMinute:Int,endMinute:Int):Long{
        if(exit<=entry)return 0L;var total=0L
        val day=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=entry;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DAY_OF_YEAR,-1)}
        val last=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=exit;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DAY_OF_YEAR,1)}
        while(day.timeInMillis<=last.timeInMillis){val start=(day.clone() as Calendar).apply{set(Calendar.HOUR_OF_DAY,startMinute/60);set(Calendar.MINUTE,startMinute%60)};val end=(day.clone() as Calendar).apply{set(Calendar.HOUR_OF_DAY,endMinute/60);set(Calendar.MINUTE,endMinute%60);if(endMinute<=startMinute)add(Calendar.DAY_OF_YEAR,1)};total+=overlap(entry,exit,start.timeInMillis,end.timeInMillis);day.add(Calendar.DAY_OF_YEAR,1)}
        return total
    }
    private fun overlap(start:Long,end:Long,rangeStart:Long,rangeEnd:Long):Long{val from=maxOf(start,rangeStart);val to=minOf(end,rangeEnd);return(to-from).coerceAtLeast(0L)}
}
