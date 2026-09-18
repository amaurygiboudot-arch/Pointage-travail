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
        /**
         * Compatibilité API : true signifie désormais qu'au moins une tranche n'a pas pu être
         * valorisée faute de taux confirmé. Aucun taux de secours n'est injecté dans le montant.
         */
        val provisionalRateUsed:Boolean,
        val unresolvedStructuralOvertimeMinutes:Double,
        val unresolvedVariableOvertimeMinutes:Double,
        val warnings:List<String>
    )

    fun calculate(
        contractualWeeklyMinutes:Int,
        regularWeeklyLimit:Int,
        paidWeeks:List<Int>,
        grossHourlyRate:Double,
        overtimeTiers:List<OvertimeTierV2>
    ):Result {
        require(contractualWeeklyMinutes>0)
        require(regularWeeklyLimit>0)
        require(grossHourlyRate>0.0)

        // Réutilise la même barrière canonique que PayrollEngineV2 : un jeu de paliers ambigu
        // ou invalide ne doit jamais alimenter directement un montant, quelle que soit la plateforme.
        val tiersStructurallyValid=OvertimeCoverageV2.isStructurallyValid(regularWeeklyLimit,overtimeTiers)
        val safeOvertimeTiers=OvertimeCoverageV2.calculationSafeTiers(regularWeeklyLimit,overtimeTiers)

        val factor=52.0/12.0
        val regularContractMinutes=minOf(contractualWeeklyMinutes,regularWeeklyLimit)
        val structuralWeekly=ratedBetween(
            upper=contractualWeeklyMinutes,
            lower=regularWeeklyLimit,
            rate=grossHourlyRate,
            tiers=safeOvertimeTiers
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
                tiers=safeOvertimeTiers
            )
        }
        val variableGross=variableParts.sumOf{it.gross}
        val allRated=listOf(structuralWeekly)+variableParts
        val warnings=buildList {
            addAll(allRated.flatMap{it.warnings})
            if(!tiersStructurallyValid&&overtimeTiers.isNotEmpty()) {
                add("Paliers d'heures supplémentaires ambigus ou invalides : ils sont neutralisés et aucune valorisation n'est inventée pour les tranches concernées.")
            }
        }.distinct()

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
            provisionalRateUsed=allRated.any{it.provisionalRateUsed},
            unresolvedStructuralOvertimeMinutes=structuralWeekly.unresolvedMinutes*factor,
            unresolvedVariableOvertimeMinutes=variableParts.sumOf{it.unresolvedMinutes},
            warnings=warnings
        )
    }

    private data class Piece(val multiplier:Double,val minutes:Double,val gross:Double)
    private data class Rated(
        val minutes:Double,
        val gross:Double,
        val tiers:List<Piece>,
        val provisionalRateUsed:Boolean,
        val unresolvedMinutes:Double,
        val warnings:List<String>
    )

    /**
     * Analyse la tranche (lower, upper] sans jamais laisser disparaître une minute.
     * Une minute sans palier confirmé reste comptée mais n'alimente aucun montant.
     */
    private fun ratedBetween(
        upper:Int,
        lower:Int,
        rate:Double,
        tiers:List<OvertimeTierV2>
    ):Rated {
        if(upper<=lower)return Rated(0.0,0.0,emptyList(),false,0.0,emptyList())
        val sorted=tiers.sortedBy{it.fromMinutes}
        var cursor=lower
        var gross=0.0
        var unresolvedMinutes=0.0
        val pieces=mutableListOf<Piece>()
        val warnings=mutableListOf<String>()

        fun add(from:Int,to:Int,multiplier:Double){
            if(to<=from)return
            val minutes=(to-from).toDouble()
            val amount=minutes/60.0*rate*multiplier
            pieces+=Piece(multiplier,minutes,amount)
            gross+=amount
        }

        fun addUnresolved(from:Int,to:Int){
            if(to<=from)return
            unresolvedMinutes+=(to-from).toDouble()
            warnings+="Palier d'heures supplémentaires non confirmé : aucune valorisation n'est injectée pour les minutes non couvertes ; le brut reste à confirmer."
        }

        sorted.forEach{tier->
            if(cursor>=upper)return@forEach
            val tierStart=maxOf(lower,tier.fromMinutes)
            val tierEnd=minOf(upper,tier.toMinutes?:Int.MAX_VALUE)
            if(tierEnd<=cursor||tierEnd<=tierStart)return@forEach
            if(tierStart>cursor){
                addUnresolved(cursor,minOf(tierStart,upper))
                cursor=minOf(tierStart,upper)
            }
            if(cursor<upper&&tierEnd>cursor){
                add(cursor,tierEnd,tier.multiplier)
                cursor=tierEnd
            }
        }
        if(cursor<upper)addUnresolved(cursor,upper)
        return Rated(
            minutes=(upper-lower).toDouble(),
            gross=gross,
            tiers=pieces,
            provisionalRateUsed=unresolvedMinutes>0.0,
            unresolvedMinutes=unresolvedMinutes,
            warnings=warnings.distinct()
        )
    }
}
