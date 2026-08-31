package com.amaury.pointage.v2.engine

import kotlin.math.min

/** Couche 2/6 — retraite complémentaire salariale 2026, versionnée. */
object ComplementaryRetirementCatalogV2 {
    data class Line(val id:String,val label:String,val baseAmount:Double,val employeeRate:Double,val amount:Double,val source:String)
    data class Estimate(val lines:List<Line>,val employeeDeductions:Double,val warnings:List<String>)

    private const val PMSS_2026 = 4005.0
    private const val MAX_8_PMSS_2026 = 32040.0
    private const val SOURCE = "Agirc-Arrco — barèmes applicables au 01/01/2026"

    fun estimate(gross:Double, year:Int):Estimate {
        val g=gross.coerceAtLeast(0.0)
        if(year!=2026) return Estimate(emptyList(),0.0,listOf("Agirc-Arrco : barème non intégré pour $year"))
        val t1=min(g,PMSS_2026)
        val t2=(min(g,MAX_8_PMSS_2026)-PMSS_2026).coerceAtLeast(0.0)
        val lines=buildList {
            if(t1>0) add(Line("agirc_t1","Agirc-Arrco tranche 1",t1,0.0315,t1*0.0315,SOURCE))
            if(t2>0) add(Line("agirc_t2","Agirc-Arrco tranche 2",t2,0.0864,t2*0.0864,SOURCE))
            if(t1>0) add(Line("ceg_t1","CEG tranche 1",t1,0.0086,t1*0.0086,SOURCE))
            if(t2>0) add(Line("ceg_t2","CEG tranche 2",t2,0.0108,t2*0.0108,SOURCE))
            if(g>PMSS_2026) {
                val cetBase=min(g,MAX_8_PMSS_2026)
                add(Line("cet","CET",cetBase,0.0014,cetBase*0.0014,SOURCE))
            }
        }
        return Estimate(lines,lines.sumOf{it.amount},listOf("Les répartitions conventionnelles ou d'entreprise supérieures/dérogatoires restent à confirmer lorsqu'elles existent."))
    }
}
