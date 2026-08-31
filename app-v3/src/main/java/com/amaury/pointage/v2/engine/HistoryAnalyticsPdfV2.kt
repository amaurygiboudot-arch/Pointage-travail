package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2

data class HistoryEntryV2(val id:String,val type:String,val atMs:Long,val revision:Int,val summary:String)
object HistoryEngineV2 {
    fun append(history:List<HistoryEntryV2>,entry:HistoryEntryV2):List<HistoryEntryV2> = (history+entry).sortedBy{it.atMs}
    fun revise(history:List<HistoryEntryV2>,id:String,summary:String):List<HistoryEntryV2> = history + HistoryEntryV2(id,"REVISION",System.currentTimeMillis(),(history.filter{it.id==id}.maxOfOrNull{it.revision}?:0)+1,summary)
}

data class AnalyticsV2(val totalPresenceMs:Long,val totalPaidMs:Long,val sessions:Int,val warnings:Int)
object AnalyticsEngineV2 {
    fun summarize(sessions:List<WorkSessionV2>,timeEngine:TimeEngineV2,nowMs:Long):AnalyticsV2 {
        val r=sessions.map{timeEngine.calculate(it,nowMs)}
        return AnalyticsV2(r.sumOf{it.presenceMs},r.sumOf{it.paidWorkMs},sessions.size,r.sumOf{it.warnings.size})
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
