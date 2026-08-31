package com.amaury.pointage.v2.engine

import kotlin.math.abs

/** Couche 6/6 — comparaison déterministe avant explication IA. L'IA n'invente jamais les montants. */
object PayslipLineComparisonEngineV2 {
    enum class Status { MATCH, DIFFERENCE, MISSING_EXPECTED, MISSING_OBSERVED }
    data class Line(val key:String,val label:String,val expected:Double?,val observed:Double?,val delta:Double?,val status:Status)
    data class Result(val lines:List<Line>,val conforming:Boolean,val warnings:List<String>)

    fun compare(expected:Map<String,Double?>,observed:Map<String,Double?>,toleranceEuro:Double=0.02):Result {
        val keys=(expected.keys+observed.keys).toSortedSet()
        val lines=keys.map { key ->
            val e=expected[key];val o=observed[key]
            when {
                e==null -> Line(key,key,null,o,null,Status.MISSING_EXPECTED)
                o==null -> Line(key,key,e,null,null,Status.MISSING_OBSERVED)
                else -> { val d=o-e;Line(key,key,e,o,d,if(abs(d)<=toleranceEuro)Status.MATCH else Status.DIFFERENCE) }
            }
        }
        val warnings=lines.filter{it.status==Status.MISSING_EXPECTED}.map{"${it.label} : référence HoraTrack à confirmer"}+lines.filter{it.status==Status.MISSING_OBSERVED}.map{"${it.label} : ligne non extraite du bulletin"}
        return Result(lines,lines.all{it.status==Status.MATCH},warnings)
    }
}
