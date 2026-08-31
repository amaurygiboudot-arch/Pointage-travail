package com.amaury.pointage.v2.engine

import java.time.LocalDate

/** Couche 3/6 — référentiel conventionnel daté. Aucune règle inconnue n'est inventée. */
object ConventionPayrollReferenceV2 {
    data class Minimum(val coefficient:Int,val monthlyGross:Double)
    data class Snapshot(val idcc:String,val effectiveFrom:LocalDate,val effectiveTo:LocalDate?,val minima:List<Minimum>,val source:String,val warnings:List<String>)

    private val plasturgie2026 = Snapshot(
        idcc="292",
        effectiveFrom=LocalDate.of(2026,3,1),
        effectiveTo=null,
        minima=listOf(
            Minimum(700,1835.0),Minimum(710,1848.0),Minimum(720,1868.0),Minimum(730,1921.0),Minimum(740,2004.0),
            Minimum(750,2126.0),Minimum(800,2266.0),Minimum(810,2424.0),Minimum(820,2652.0),Minimum(830,2841.0),
            Minimum(900,3366.0),Minimum(910,3525.0),Minimum(920,4047.0),Minimum(930,5253.0),Minimum(940,6543.0)
        ),
        source="Légifrance — IDCC 292, accord salaires du 19/02/2026, effet 01/03/2026",
        warnings=listOf("Les primes, ancienneté, nuit et autres dispositions doivent être résolues par leur règle conventionnelle datée avant calcul.")
    )

    fun applicable(idcc:String,date:LocalDate):Snapshot? = listOf(plasturgie2026).filter { it.idcc==idcc && !date.isBefore(it.effectiveFrom) && (it.effectiveTo==null || !date.isAfter(it.effectiveTo)) }.maxByOrNull{it.effectiveFrom}
    fun minimum(idcc:String,date:LocalDate,coefficient:Int):Minimum?=applicable(idcc,date)?.minima?.firstOrNull{it.coefficient==coefficient}
}
