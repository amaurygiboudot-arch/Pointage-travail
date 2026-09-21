package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.util.LinkedHashMap

data class HistoryEntryV2(val id:String,val type:String,val atMs:Long,val revision:Int,val summary:String)
object HistoryEngineV2 {
    fun append(history:List<HistoryEntryV2>,entry:HistoryEntryV2):List<HistoryEntryV2> = (history+entry).sortedBy{it.atMs}
    fun revise(history:List<HistoryEntryV2>,id:String,summary:String):List<HistoryEntryV2> = history + HistoryEntryV2(id,"REVISION",System.currentTimeMillis(),(history.filter{it.id==id}.maxOfOrNull{it.revision}?:0)+1,summary)
}

data class PlaceAnalyticsV2(
    val label:String,
    val paidMs:Long,
    val presenceMs:Long,
    val sessions:Int,
    /** Faux si une durée incluse dans ce sous-total n'est pas certifiable. */
    val reliable:Boolean = true
)
data class AnalyticsV2(
    val totalPresenceMs:Long,
    val totalPaidMs:Long,
    val totalUnpaidPauseMs:Long,
    val sessions:Int,
    val completedSessions:Int,
    val openSessions:Int,
    val warnings:Int,
    val places:List<PlaceAnalyticsV2>,
    /** Les totaux de temps ne doivent être affichés que si toutes leurs sessions sont fiables. */
    val timeTotalsReliable:Boolean = true,
    /** Les totaux par lieu exigent aussi que chaque session possède un lieu explicite. */
    val placeTotalsReliable:Boolean = true
)
object AnalyticsEngineV2 {
    /** Source unique de vérité pour les vues Analyses et leurs dérivés. */
    fun summarize(sessions:List<WorkSessionV2>,timeEngine:TimeEngineV2,nowMs:Long):AnalyticsV2 {
        data class PlaceAcc(
            var paid:Long=0L,
            var presence:Long=0L,
            var sessions:Int=0,
            var reliable:Boolean=true
        )
        val places=LinkedHashMap<String,PlaceAcc>()
        var presence=0L;var paid=0L;var unpaid=0L;var warnings=0;var completed=0;var open=0
        val overlappingSessions=WorkSessionOverlapV2.hasSameEmployerOverlap(
            sessions=sessions,
            rangeStartMs=1L,
            rangeEndMs=Long.MAX_VALUE,
            openEndMs=nowMs
        )
        var timeTotalsReliable=!overlappingSessions
        var placeTotalsReliable=!overlappingSessions
        if(overlappingSessions) warnings++
        sessions.forEach { session ->
            val result=timeEngine.calculate(session,nowMs)
            presence+=result.presenceMs.coerceAtLeast(0L)
            paid+=result.paidWorkMs.coerceAtLeast(0L)
            unpaid+=result.unpaidPauseMs.coerceAtLeast(0L)
            warnings+=result.warnings.size
            if(!result.reliable) {
                timeTotalsReliable=false
                placeTotalsReliable=false
            }
            if(session.realExitMs==null) open++ else completed++
            val confirmedPlace=session.placeLabel?.trim()?.takeIf{it.isNotBlank()}
            if(confirmedPlace==null) placeTotalsReliable=false
            val label=confirmedPlace?:"Lieu à confirmer"
            val acc=places.getOrPut(label){PlaceAcc()}
            acc.paid+=result.paidWorkMs.coerceAtLeast(0L)
            acc.presence+=result.presenceMs.coerceAtLeast(0L)
            acc.sessions++
            if(!result.reliable) acc.reliable=false
        }
        if(overlappingSessions) places.values.forEach { it.reliable=false }
        return AnalyticsV2(
            totalPresenceMs=presence,
            totalPaidMs=paid,
            totalUnpaidPauseMs=unpaid,
            sessions=sessions.size,
            completedSessions=completed,
            openSessions=open,
            warnings=warnings,
            places=places.map{(label,a)->PlaceAnalyticsV2(label,a.paid,a.presence,a.sessions,a.reliable)},
            timeTotalsReliable=timeTotalsReliable,
            placeTotalsReliable=placeTotalsReliable
        )
    }

    /**
     * Sous-total attribué à une adresse enregistrée.
     *
     * Une session sans lieu rend l'ensemble des sous-totaux par lieu incomplet : elle pourrait
     * appartenir à n'importe lequel d'entre eux. Le résultat reste donc non fiable même si les
     * sessions déjà rattachées à l'adresse sont calculables.
     */
    fun placeTotalForAddress(analytics:AnalyticsV2,address:String):PlaceAnalyticsV2 {
        val matches=analytics.places.filter { matchesAddress(it.label,address) }
        return PlaceAnalyticsV2(
            label=address.trim(),
            paidMs=matches.sumOf { it.paidMs },
            presenceMs=matches.sumOf { it.presenceMs },
            sessions=matches.sumOf { it.sessions },
            reliable=analytics.placeTotalsReliable && matches.all { it.reliable }
        )
    }

    internal fun matchesAddress(placeLabel:String,address:String):Boolean {
        val stored=placeLabel.trim()
        val wanted=address.trim()
        if(stored.equals(wanted,ignoreCase=true)) return true
        val marker=" — "
        return stored.contains(marker) &&
            stored.substringAfterLast(marker).trim().equals(wanted,ignoreCase=true)
    }
}

enum class PdfFieldV2 { IDENTITY, COMPANY, PERIOD, HOURS, PAUSES, PREMIUMS, BASKETS, CONTRIBUTIONS, DEDUCTIONS, COUNTERS, DISCREPANCIES, SOURCES }
data class PdfSelectionV2(val fields:Set<PdfFieldV2>)
data class PdfDocumentV2(val title:String,val subtitle:String,val sections:List<Pair<String,String>>,val footer:String="© HoraTrack")
object PdfEngineV2 {
    fun salaryExample(selection:PdfSelectionV2,sections:Map<PdfFieldV2,String>):PdfDocumentV2 {
        val content=selection.fields.mapNotNull{field->sections[field]?.let{field.name to it}}
        return PdfDocumentV2("FICHE DE PAIE EXEMPLE","ESTIMATION HORATRACK",content)
    }
    fun report(title:String,selection:PdfSelectionV2,sections:Map<PdfFieldV2,String>)=PdfDocumentV2(title,"Document généré par HoraTrack",selection.fields.mapNotNull{f->sections[f]?.let{f.name to it}})
}
