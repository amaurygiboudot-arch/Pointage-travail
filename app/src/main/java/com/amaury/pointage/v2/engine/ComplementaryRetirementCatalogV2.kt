package com.amaury.pointage.v2.engine

import kotlin.math.min

/** Couche 2/6 — retraite complémentaire salariale et patronale 2026, versionnée. */
object ComplementaryRetirementCatalogV2 {
    data class Line(
        val id:String,
        val label:String,
        val baseAmount:Double,
        val employeeRate:Double,
        val amount:Double,
        val source:String,
        val employerRate:Double=0.0,
        val employerAmount:Double=0.0
    )
    data class Estimate(
        val lines:List<Line>,
        val employeeDeductions:Double,
        val warnings:List<String>,
        val employerContributions:Double=0.0
    )

    private const val SOURCE = "Agirc-Arrco — barèmes applicables au 01/01/2026"

    /**
     * Wrapper de compatibilité pendant la migration du moteur conventionnel.
     * Le calcul commun ne dépend plus du type Plasturgie.
     */
    fun estimate(
        gross:Double,
        year:Int,
        professionalStatus:String?=null,
        ceiling:SocialSecurityCeilingV2.Snapshot?=null,
        protectionCategory:PlasturgieProtectionCategoryV2.Result?=null
    ):Estimate = estimateGeneric(
        gross = gross,
        year = year,
        professionalStatus = professionalStatus,
        ceiling = ceiling,
        protectionCategory = protectionCategory?.let(PlasturgieProtectionCategoryV2::toGeneric)
    )

    /**
     * La catégorie ANI conventionnelle confirmée prime sur le simple libellé CADRE/NON_CADRE.
     * Sans override conventionnel, le statut professionnel explicite reste le repli prudent historique.
     */
    fun estimateGeneric(
        gross:Double,
        year:Int,
        professionalStatus:String?=null,
        ceiling:SocialSecurityCeilingV2.Snapshot?=null,
        protectionCategory:ProtectionCategoryV2.Result?=null
    ):Estimate {
        val g=gross.coerceAtLeast(0.0)
        val full=SocialSecurityCeilingV2.fullMonthly(year)
            ?: return Estimate(emptyList(),0.0,listOf("Agirc-Arrco : barème non intégré pour $year"))
        val status=professionalStatus?.trim()?.uppercase()
        val category=protectionCategory?.aniCategory
        val categoryControlsApec=protectionCategory?.conventionControlsAni==true
        val apecApplicable=when {
            !categoryControlsApec -> status=="CADRE"
            protectionCategory.confirmed!=true -> false
            category==ProtectionCategoryV2.AniCategory.ARTICLE_2_1 -> true
            category==ProtectionCategoryV2.AniCategory.ARTICLE_2_2 -> true
            else -> false
        }
        val applicable=ceiling?.applicableMonthly ?: full
        val max4=ceiling?.fourTimesApplicable ?: full*4.0
        val max8=ceiling?.eightTimesApplicable ?: full*8.0
        val t1=min(g,applicable)
        val t2=(min(g,max8)-applicable).coerceAtLeast(0.0)
        val lines=buildList {
            if(t1>0) add(Line("agirc_t1","Agirc-Arrco tranche 1",t1,0.0315,t1*0.0315,SOURCE,0.0472,t1*0.0472))
            if(t2>0) add(Line("agirc_t2","Agirc-Arrco tranche 2",t2,0.0864,t2*0.0864,SOURCE,0.1295,t2*0.1295))
            if(t1>0) add(Line("ceg_t1","CEG tranche 1",t1,0.0086,t1*0.0086,SOURCE,0.0129,t1*0.0129))
            if(t2>0) add(Line("ceg_t2","CEG tranche 2",t2,0.0108,t2*0.0108,SOURCE,0.0162,t2*0.0162))
            if(g>applicable) {
                val cetBase=min(g,max8)
                add(Line("cet","CET",cetBase,0.0014,cetBase*0.0014,SOURCE,0.0021,cetBase*0.0021))
            }
            if(apecApplicable && g>0.0) {
                val apecBase=min(g,max4)
                add(Line("apec","APEC cadre / assimilé cadre",apecBase,0.00024,apecBase*0.00024,SOURCE,0.00036,apecBase*0.00036))
            }
        }
        val warnings=buildList {
            add("Les répartitions conventionnelles ou d'entreprise supérieures/dérogatoires restent à confirmer lorsqu'elles existent.")
            when {
                categoryControlsApec && protectionCategory?.confirmed!=true ->
                    add("Catégorie ANI 2.1/2.2 à confirmer : APEC non appliquée automatiquement.")
                category==ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE ->
                    add("Extension régime cadres possible : APEC non appliquée automatiquement car cette catégorie reste hors ANI 2.1/2.2.")
                !categoryControlsApec && status!="CADRE" && status!="NON_CADRE" ->
                    add("Statut professionnel à préciser : APEC non appliquée tant que le statut cadre n'est pas confirmé.")
            }
            ceiling?.warnings?.let(::addAll)
        }.distinct()
        return Estimate(
            lines=lines,
            employeeDeductions=lines.sumOf{it.amount},
            warnings=warnings,
            employerContributions=lines.sumOf{it.employerAmount}
        )
    }
}
