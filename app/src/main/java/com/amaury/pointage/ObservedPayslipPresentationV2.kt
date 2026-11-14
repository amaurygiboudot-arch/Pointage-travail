package com.amaury.pointage

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.amaury.pointage.v2.ObservedPayslipDetailsV2
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import java.io.OutputStream
import java.math.BigDecimal

/** Shared source-only rows for the screen and export. No payroll/time engine dependency. */
object ObservedPayslipPresentationV2 {
    data class Section(val title: String, val rows: List<List<String>>)
    private val labels = mapOf(
        PayslipDocumentParserV2.KEY_GROSS to "Brut constaté",
        PayslipDocumentParserV2.KEY_NET_BEFORE_TAX to "Net avant impôt constaté",
        PayslipDocumentParserV2.KEY_NET_TAXABLE to "Net imposable constaté",
        PayslipDocumentParserV2.KEY_OVERTIME_GROSS to "Heures supplémentaires — brut constaté",
        PayslipDocumentParserV2.KEY_PREMIUMS_GROSS to "Primes — brut constaté",
        PayslipDocumentParserV2.KEY_MEAL_BASKETS to "Paniers constatés",
        PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to "Mutuelle — part salariale constatée",
        PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE to "Prévoyance — part salariale constatée",
        PayslipDocumentParserV2.KEY_COMPLEMENTARY_RETIREMENT_EMPLOYEE to "Retraite complémentaire — part salariale constatée")
    fun available(snapshot: ObservedPayslipDetailsV2.Snapshot) = snapshot.reliable && !snapshot.needsReconfirmation && snapshot.document != null
    fun sections(snapshot: ObservedPayslipDetailsV2.Snapshot): List<Section> {
        require(available(snapshot))
        val record = requireNotNull(snapshot.record)
        val aggregates = snapshot.aggregates.toMutableMap()
        record.gross?.takeIf { record.confirmedByUser }?.let { aggregates.putIfAbsent(PayslipDocumentParserV2.KEY_GROSS, it) }
        record.net?.takeIf { record.confirmedByUser }?.let { aggregates.putIfAbsent(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX, it) }
        val summary = aggregates.map { (key,value) -> listOf(labels[key] ?: key, "", "", BigDecimal.valueOf(value).toPlainString().replace('.',','), "", "", "") }
        val detail = snapshot.document!!.rows.map { row -> listOf(row.label) + row.cells().map { it?.replace('.',',') ?: "—" } }
        return listOf(Section("Totaux confirmés à l’import — aucune somme entre les lignes", summary),
            Section("Transcription du document — lignes indépendantes, non recalculées", detail))
    }
    fun write(snapshot: ObservedPayslipDetailsV2.Snapshot, company: String, output: OutputStream) {
        val document = requireNotNull(snapshot.document); val sections = sections(snapshot)
        val pdf = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.BLACK }
            var pageNo=0; var page: PdfDocument.Page?=null; var y=0f
            val x=floatArrayOf(28f,260f,350f,440f,540f,630f,720f); val widths=floatArrayOf(220f,80f,80f,90f,80f,80f,90f)
            fun wrap(text:String,width:Float):List<String> {
                if(text.isEmpty())return listOf("")
                val result=mutableListOf<String>();var remaining=text
                while(remaining.isNotEmpty()) {
                    var count=paint.breakText(remaining,true,width,null).coerceAtLeast(1)
                    if(count<remaining.length) { val space=remaining.substring(0,count).lastIndexOf(' ');if(space>0)count=space }
                    result.add(remaining.take(count));remaining=remaining.drop(count).trimStart()
                };return result
            }
            fun newPage() {
                page?.let { old -> old.canvas.drawText("Page $pageNo — Transcription personnelle, original conservé",28f,578f,paint); pdf.finishPage(old) }
                page=pdf.startPage(PdfDocument.PageInfo.Builder(842,595,++pageNo).create())
                val c=page!!.canvas;paint.textSize=14f;c.drawText("AGKGMG — BULLETIN CONSTATÉ",28f,28f,paint);paint.textSize=9f
                y=44f
                wrap("$company — ${document.sourcePeriod} — montants du document, pas une estimation",780f).forEach { c.drawText(it,28f,y,paint);y+=12f }
                listOf("Rubrique", "Base salarié", "Taux salarié", "Montant salarié", "Base employeur", "Taux employeur", "Montant employeur").forEachIndexed { i,s -> c.drawText(s,x[i],y+8,paint) };y+=24f
            }
            newPage()
            fun fullWidth(text:String) { wrap(text,780f).forEach { line -> if(y>545)newPage();page!!.canvas.drawText(line,28f,y,paint);y+=12f };y+=5f }
            fullWidth("Source : ${document.source}")
            fullWidth("Les cellules absentes restent inconnues. Les totaux et les lignes transcrites ne sont jamais additionnés entre eux. Le calcul AGKGMG et ses blocages restent distincts.")
            sections.forEach { section ->
                if(y>510)newPage();fullWidth(section.title)
                section.rows.forEach { cells ->
                    val wrapped=cells.mapIndexed { i,s -> wrap(s,widths[i]) };val height=wrapped.maxOf { it.size }*12f+8f
                    if(y+height>550)newPage()
                    wrapped.forEachIndexed { i,lines -> lines.forEachIndexed { j,s -> page!!.canvas.drawText(s,x[i],y+j*12,paint) } }
                    y+=height
                }
            }
            page?.let { it.canvas.drawText("Page $pageNo — Transcription personnelle, original conservé",28f,578f,paint);pdf.finishPage(it) }
            pdf.writeTo(output)
        } finally { pdf.close() }
    }
}
