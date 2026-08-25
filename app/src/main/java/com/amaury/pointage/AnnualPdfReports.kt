package com.amaury.pointage

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import java.io.OutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object AnnualPdfReports {
    fun writeWork(context:Context,data:JSONArray,year:Int,out:OutputStream){
        val pdf=PdfDocument();val p=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val c=p.canvas;PdfVisualStyle.header(c,595,"BILAN ANNUEL DU TEMPS DE TRAVAIL — $year","HoraTrack • Synthèse annuelle")
        val h=PdfVisualStyle.boldPaint(9.2f);val n=PdfVisualStyle.bodyPaint(8.8f);val fill=Paint().apply{color=PdfVisualStyle.panel};var y=82f;c.drawRect(30f,y,565f,y+24,fill)
        arrayOf("Mois","Jours","Présence","Temps payé","Pause équipe payée","Pause déduite","Paniers").forEachIndexed{i,s->c.drawText(s,floatArrayOf(34f,145f,195f,275f,365f,465f,530f)[i],y+16,h)};y+=30
        var yd=0;var pr=0L;var paid=0L;var pp=0L;var up=0L;var meals=0
        for(m in 0..11){val days=WorkReportCalculator.month(context,data,year,m);val presence=days.sumOf{it.presenceMs};val work=days.sumOf{it.paidWorkMs};val ppaid=days.sumOf{it.paidTeamPauseMs};val punpaid=days.sumOf{it.unpaidPauseMs};val pm=days.sumOf{it.mealCount};val label=SimpleDateFormat("MMMM",Locale.FRANCE).format(Calendar.getInstance(Locale.FRANCE).apply{set(year,m,1)}.time).replaceFirstChar{it.uppercase()};val v=arrayOf(label,days.size.toString(),dur(presence),dur(work),dur(ppaid),dur(punpaid),pm.toString());v.forEachIndexed{i,s->c.drawText(s,floatArrayOf(34f,150f,195f,275f,365f,465f,535f)[i],y+13,n)};y+=23;yd+=days.size;pr+=presence;paid+=work;pp+=ppaid;up+=punpaid;meals+=pm}
        y+=8;c.drawRect(30f,y,565f,y+48,fill);c.drawText("TOTAL ANNÉE",38f,y+17,h);c.drawText("$yd jours  •  présence ${dur(pr)}  •  payé ${dur(paid)}  •  pauses équipe payées ${dur(pp)}  •  pauses déduites ${dur(up)}  •  $meals paniers",38f,y+36,h);PdfVisualStyle.footer(c,595,842,1);pdf.finishPage(p);pdf.writeTo(out);pdf.close()
    }

    fun writeSalary(context:Context,data:JSONArray,year:Int,out:OutputStream){
        val prefs=context.getSharedPreferences("salary_settings",Context.MODE_PRIVATE);val rate=prefs.getString("hourly_rate","")?.replace(',','.')?.toDoubleOrNull()?:0.0;val meal=prefs.getString("meal_amount","")?.replace(',','.')?.toDoubleOrNull()?:0.0;val idcc=prefs.getString("company_idcc","").orEmpty();val convention=ConventionCatalog.findByIdcc(idcc)?:ConventionCatalog.conventions.first();val euro=NumberFormat.getCurrencyInstance(Locale.FRANCE)
        val pdf=PdfDocument();val p=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val c=p.canvas;PdfVisualStyle.header(c,595,"ESTIMATION ANNUELLE DE RÉMUNÉRATION — $year","Document indicatif • HoraTrack");val h=PdfVisualStyle.boldPaint(9.2f);val n=PdfVisualStyle.bodyPaint(8.7f);val fill=Paint().apply{color=PdfVisualStyle.panel};var y=82f;c.drawText("Cette estimation ne remplace pas un bulletin de paie.",30f,y,n);y+=18;c.drawRect(30f,y,565f,y+24,fill);arrayOf("Mois","Heures payées","Heures sup.","Nuit","Paniers","Brut estimé").forEachIndexed{i,s->c.drawText(s,floatArrayOf(34f,145f,245f,335f,410f,485f)[i],y+16,h)};y+=30
        var annualGross=0.0;var annualMeals=0.0;var annualPaid=0L
        for(m in 0..11){val days=WorkReportCalculator.month(context,data,year,m);val paid=days.sumOf{it.paidWorkMs};val salary=if(rate>0)SalaryCalculator.calculate(data,year,m,rate,convention) else null;val mealCount=days.sumOf{it.mealCount};val mealTotal=mealCount*meal;val gross=(salary?.monthlyEstimatedGross?:0.0)+mealTotal;val overtime=salary?.overtimeTiers?.sumOf{it.durationMs}?:0L;val night=salary?.nightMs?:0L;val label=SimpleDateFormat("MMMM",Locale.FRANCE).format(Calendar.getInstance(Locale.FRANCE).apply{set(year,m,1)}.time).replaceFirstChar{it.uppercase()};val v=arrayOf(label,dur(paid),dur(overtime),dur(night),"$mealCount / ${euro.format(mealTotal)}",if(rate>0)euro.format(gross) else "Taux manquant");v.forEachIndexed{i,s->c.drawText(s,floatArrayOf(34f,145f,245f,335f,410f,485f)[i],y+13,n)};y+=23;annualGross+=gross;annualMeals+=mealTotal;annualPaid+=paid}
        y+=8;c.drawRect(30f,y,565f,y+58,fill);c.drawText("TOTAL ANNÉE",38f,y+17,h);c.drawText("Temps payé : ${dur(annualPaid)}",38f,y+35,h);c.drawText("Paniers : ${euro.format(annualMeals)}   •   Brut annuel estimé : ${if(rate>0)euro.format(annualGross) else "taux horaire non renseigné"}",250f,y+35,h);y+=75;c.drawText("Calcul basé sur les paramètres Salaire, la convention connue, les majorations intégrées et les paniers enregistrés.",30f,y,n);PdfVisualStyle.footer(c,595,842,1);pdf.finishPage(p);pdf.writeTo(out);pdf.close()
    }
    private fun dur(ms:Long):String{val min=ms.coerceAtLeast(0)/60000;return String.format(Locale.FRANCE,"%02dh %02dm",min/60,min%60)}
}
