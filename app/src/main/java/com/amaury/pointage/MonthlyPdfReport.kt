package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object MonthlyPdfReport {
    private const val W=842; private const val H=595; private const val M=24f
    fun write(context:Context,data:JSONArray,year:Int,month:Int,output:OutputStream){
        val days=WorkReportCalculator.month(context,data,year,month)
        val pdf=PdfDocument(); var pageNo=0; var page:PdfDocument.Page?=null; var y=0f
        val title=Paint(1).apply{color=Color.rgb(35,35,35);textSize=18f;typeface=Typeface.DEFAULT_BOLD}
        val head=Paint(1).apply{color=Color.rgb(35,35,35);textSize=7.5f;typeface=Typeface.DEFAULT_BOLD}
        val normal=Paint(1).apply{color=Color.rgb(45,45,45);textSize=7.2f}
        val bold=Paint(1).apply{color=Color.rgb(25,25,25);textSize=7.2f;typeface=Typeface.DEFAULT_BOLD}
        val muted=Paint(1).apply{color=Color.rgb(105,105,105);textSize=7f}
        val line=Paint(1).apply{color=Color.rgb(205,205,205);strokeWidth=.7f}
        val fill=Paint().apply{color=Color.rgb(243,239,230)}
        val time=SimpleDateFormat("HH:mm",Locale.FRANCE)
        val monthLabel=SimpleDateFormat("MMMM yyyy",Locale.FRANCE).format(Calendar.getInstance(Locale.FRANCE).apply{set(year,month,1)}.time).replaceFirstChar{it.uppercase()}
        fun startPage(){page?.let{pdf.finishPage(it)};pageNo++;page=pdf.startPage(PdfDocument.PageInfo.Builder(W,H,pageNo).create());val c=page!!.canvas;y=M;c.drawText("RELEVÉ MENSUEL DE TRAVAIL — $monthLabel",M,y+16,title);c.drawText("Généré le ${SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.FRANCE).format(Date())}",W-155f,y+15,muted);y+=30;c.drawRect(M,y,W-M,y+22,fill);val xs=floatArrayOf(M+3,78f,137f,187f,237f,287f,337f,397f,457f,517f,577f,637f,697f,757f);val hs=arrayOf("Date","Poste","Arrivée","Embauche","Sortie","Présence","Pause payée","Pause déduite","Temps payé","Panier","Sessions","Mode","Lieu","Info");hs.forEachIndexed{i,s->c.drawText(s,xs[i],y+14,head)};y+=26}
        startPage()
        var totalPaid=0L;var totalPresence=0L;var paidPause=0L;var unpaid=0L;var meals=0
        days.forEach{d->if(y>H-48)startPage();val c=page!!.canvas;val xs=floatArrayOf(M+3,78f,137f,187f,237f,287f,337f,397f,457f,517f,577f,637f,697f,757f);val vals=arrayOf(d.dateLabel,d.shiftLabel,time.format(d.firstArrival),time.format(d.firstCountedEntry),time.format(d.lastExit),dur(d.presenceMs),dur(d.paidTeamPauseMs),dur(d.unpaidPauseMs),dur(d.paidWorkMs),d.mealCount.toString(),d.sessions.toString(),if(d.manual)"Manuel" else "GPS",short(d.places.joinToString(" / "),9),if(d.paidTeamPauseMs>0)"pause rémun." else "");vals.forEachIndexed{i,s->c.drawText(s,xs[i],y+13,if(i==8)bold else normal)};y+=20;c.drawLine(M,y,W-M,y,line);totalPaid+=d.paidWorkMs;totalPresence+=d.presenceMs;paidPause+=d.paidTeamPauseMs;unpaid+=d.unpaidPauseMs;meals+=d.mealCount}
        if(y>H-78)startPage();val c=page!!.canvas;y+=10;c.drawRect(M,y,W-M,y+42,fill);c.drawText("TOTAL MOIS",M+8,y+14,head);c.drawText("Présence ${dur(totalPresence)}   •   Temps payé ${dur(totalPaid)}   •   Pauses équipe payées ${dur(paidPause)}   •   Pauses déduites ${dur(unpaid)}   •   Paniers $meals   •   Jours ${days.size}",M+8,y+31,bold);y+=48;c.drawText("Les pauses d'équipe Matin / Après-midi / Nuit restent rémunérées et ne sont pas déduites du temps payé.",M,y+12,muted)
        page?.let{pdf.finishPage(it)};pdf.writeTo(output);pdf.close()
    }
    private fun dur(ms:Long):String{val m=ms.coerceAtLeast(0)/60000;return String.format(Locale.FRANCE,"%02dh%02d",m/60,m%60)}
    private fun short(s:String,n:Int)=if(s.length<=n)s else s.take((n-1).coerceAtLeast(1))+"…"
}
