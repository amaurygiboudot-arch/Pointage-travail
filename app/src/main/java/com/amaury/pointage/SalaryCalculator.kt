package com.amaury.pointage

import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

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

                    // Nuit utilisateur : 21:00 -> 06:00, majoration +25 %.
                    // On rapporte l'overlap réel à la durée payée pour ne jamais majorer plus de temps que payé.
                    val raw=s.exit-s.entry
                    val rawNight=nightOverlap(s.entry,s.exit,21*60,6*60)
                    val paidNight=if(raw<=0L)0L else ((rawNight.toDouble()/raw.toDouble())*duration).toLong().coerceIn(0L,duration)
                    nightMs+=paidNight
                    val nightShare=paidNight/duration.toDouble()
                    // Les heures sup sont valorisées d'abord, puis la majoration de jour, puis la nuit.
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
