package com.amaury.pointage.v2.engine

/** Mensualisation d'un temps plein dont la durée contractuelle peut dépasser la durée légale/référence. */
object FullTimeStructuralOvertimeV2 {
    data class TierAmount(val multiplier:Double,val minutes:Double,val gross:Double)
    data class Result(
        val monthlyBaseGross:Double,
        val monthlyRegularMinutes:Double,
        val monthlyStructuralOvertimeMinutes:Double,
        val structuralOvertimeGross:Double,
        val variableOvertimeGross:Double,
        val structuralTiers:List<TierAmount>,
        val variableTiers:List<TierAmount>,
        val warnings:List<String>
    )

    fun calculate(
        contractualWeeklyMinutes:Int,
        regularWeeklyLimit:Int,
        paidWeeks:List<Int>,
        grossHourlyRate:Double,
        overtimeTiers:List<OvertimeTierV2>,
        minimumFallbackMultiplier:Double=1.10
    ):Result {
        require(contractualWeeklyMinutes>0)
        require(regularWeeklyLimit>0)
        require(grossHourlyRate>0.0)
        require(minimumFallbackMultiplier>=1.10)

        val factor=52.0/12.0
        val regularContractMinutes=minOf(contractualWeeklyMinutes,regularWeeklyLimit)
        val structuralWeekly=ratedBetween(
            upper=contractualWeeklyMinutes,
            lower=regularWeeklyLimit,
            rate=grossHourlyRate,
            tiers=overtimeTiers,
            fallbackMultiplier=minimumFallbackMultiplier
        )
        val monthlyRegularMinutes=regularContractMinutes*factor
        val monthlyStructuralMinutes=structuralWeekly.minutes*factor
        val structuralGrossMonthly=structuralWeekly.gross*factor
        val monthlyBaseGross=monthlyRegularMinutes/60.0*grossHourlyRate+structuralGrossMonthly

        val variableParts=paidWeeks.map { paid ->
            ratedBetween(
                upper=paid.coerceAtLeast(0),
                lower=maxOf(contractualWeeklyMinutes,regularWeeklyLimit),
                rate=grossHourlyRate,
                tiers=overtimeTiers,
                fallbackMultiplier=minimumFallbackMultiplier
            )
        }
        val variableGross=variableParts.sumOf{it.gross}
        val warnings=(listOf(structuralWeekly)+variableParts).flatMap{it.warnings}.distinct()

        fun aggregate(parts:List<Rated>,monthly:Boolean):List<TierAmount> = parts
            .flatMap{it.tiers}
            .groupBy{it.multiplier}
            .map{(multiplier,items)->
                val minutes=items.sumOf{it.minutes}*(if(monthly)factor else 1.0)
                TierAmount(multiplier,minutes,items.sumOf{it.gross}*(if(monthly)factor else 1.0))
            }
            .sortedBy{it.multiplier}

        return Result(
            monthlyBaseGross=monthlyBaseGross,
            monthlyRegularMinutes=monthlyRegularMinutes,
            monthlyStructuralOvertimeMinutes=monthlyStructuralMinutes,
            structuralOvertimeGross=structuralGrossMonthly,
            variableOvertimeGross=variableGross,
            structuralTiers=aggregate(listOf(structuralWeekly),true),
            variableTiers=aggregate(variableParts,false),
            warnings=warnings
        )
    }

    private data class Piece(val multiplier:Double,val minutes:Double,val gross:Double)
    private data class Rated(val minutes:Double,val gross:Double,val tiers:List<Piece>,val warnings:List<String>)

    /** Rémunère la tranche (lower, upper] sans jamais laisser disparaître une minute. */
    private fun ratedBetween(
        upper:Int,
        lower:Int,
        rate:Double,
        tiers:List<OvertimeTierV2>,
        fallbackMultiplier:Double
    ):Rated {
        if(upper<=lower)return Rated(0.0,0.0,emptyList(),emptyList())
        val sorted=tiers.sortedBy{it.fromMinutes}
        var cursor=lower
        var gross=0.0
        val pieces=mutableListOf<Piece>()
        val warnings=mutableListOf<String>()

        fun add(from:Int,to:Int,multiplier:Double){
            if(to<=from)return
            val minutes=(to-from).toDouble()
            val amount=minutes/60.0*rate*multiplier
            pieces+=Piece(multiplier,minutes,amount)
            gross+=amount
        }

        fun warnFallback(){
            warnings+="Palier d'heures supplémentaires incomplet : valorisation provisoire au plancher de +10 % autorisé pour un accord collectif. Ce plancher n'est pas le barème supplétif de +25 % puis +50 % ; le taux exact reste à vérifier."
        }

        sorted.forEach{tier->
            if(cursor>=upper)return@forEach
            val tierStart=maxOf(lower,tier.fromMinutes)
            val tierEnd=minOf(upper,tier.toMinutes?:Int.MAX_VALUE)
            if(tierEnd<=cursor||tierEnd<=tierStart)return@forEach
            if(tierStart>cursor){
                add(cursor,minOf(tierStart,upper),fallbackMultiplier)
                warnFallback()
                cursor=minOf(tierStart,upper)
            }
            if(cursor<upper&&tierEnd>cursor){
                add(cursor,tierEnd,tier.multiplier)
                cursor=tierEnd
            }
        }
        if(cursor<upper){
            add(cursor,upper,fallbackMultiplier)
            warnFallback()
        }
        return Rated((upper-lower).toDouble(),gross,pieces,warnings.distinct())
    }
}
