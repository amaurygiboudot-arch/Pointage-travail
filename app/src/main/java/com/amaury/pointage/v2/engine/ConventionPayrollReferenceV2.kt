package com.amaury.pointage.v2.engine

import java.time.LocalDate

/** Couche 3/6 — référentiel conventionnel daté. Aucune règle inconnue n'est inventée. */
object ConventionPayrollReferenceV2 {
    enum class ExtensionStatus { EXTENDED, NOT_EXTENDED, UNKNOWN }

    data class Minimum(val coefficient:Int,val monthlyGross:Double)
    data class Snapshot(
        val idcc:String,
        val effectiveFrom:LocalDate,
        val effectiveTo:LocalDate?,
        val minima:List<Minimum>,
        val source:String,
        val extensionStatus:ExtensionStatus,
        val warnings:List<String>
    ) {
        fun canApplyToCompany(companyApplicabilityConfirmed:Boolean):Boolean = when(extensionStatus) {
            ExtensionStatus.EXTENDED -> true
            ExtensionStatus.NOT_EXTENDED -> companyApplicabilityConfirmed
            ExtensionStatus.UNKNOWN -> false
        }
    }

    private val plasturgie2024 = Snapshot(
        idcc="292",
        effectiveFrom=LocalDate.of(2024,3,1),
        effectiveTo=null,
        minima=listOf(
            Minimum(700,1803.0),Minimum(710,1815.0),Minimum(720,1835.0),Minimum(730,1887.0),Minimum(740,1969.0),
            Minimum(750,2088.0),Minimum(800,2226.0),Minimum(810,2381.0),Minimum(820,2605.0),Minimum(830,2791.0),
            Minimum(900,3316.0),Minimum(910,3473.0),Minimum(920,3987.0),Minimum(930,5175.0),Minimum(940,6446.0)
        ),
        source="Légifrance — IDCC 292, accord salaires du 15/02/2024, étendu, effet 01/03/2024",
        extensionStatus=ExtensionStatus.EXTENDED,
        warnings=emptyList()
    )

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
        extensionStatus=ExtensionStatus.NOT_EXTENDED,
        warnings=listOf("Accord salaires Plasturgie du 19/02/2026 : en vigueur non étendu sur Légifrance.")
    )

    private val snapshots=listOf(plasturgie2024,plasturgie2026)

    fun applicable(idcc:String,date:LocalDate,companyApplicabilityConfirmed:Boolean=false):Snapshot? = snapshots
        .filter { it.idcc==idcc && !date.isBefore(it.effectiveFrom) && (it.effectiveTo==null || !date.isAfter(it.effectiveTo)) && it.canApplyToCompany(companyApplicabilityConfirmed) }
        .maxByOrNull{it.effectiveFrom}

    fun minimum(
        idcc:String,
        date:LocalDate,
        coefficient:Int,
        companyApplicabilityConfirmed:Boolean=false
    ):Minimum? = applicable(idcc,date,companyApplicabilityConfirmed)
        ?.minima
        ?.firstOrNull{it.coefficient==coefficient}
}
