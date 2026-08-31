package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Button
import android.widget.Toast
import java.io.File
import java.util.Calendar

/** Bouton d'aperçu du bilan annuel des heures. */
class AnnualWorkPdfButton @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null,defStyleAttr:Int=android.R.attr.buttonStyle):Button(context,attrs,defStyleAttr){
    init{setOnClickListener{open(false)}}
    private fun open(salary:Boolean){val a=context as? MainActivity?:return;val year=Calendar.getInstance().get(Calendar.YEAR);runCatching{val file=File(a.cacheDir,"HoraTrack_Bilan_travail_$year.pdf");file.outputStream().use{AnnualPdfReports.writeWork(a,PointageStore.load(a),year,it)};a.startActivity(Intent(a,PdfPreviewActivity::class.java).apply{putExtra("pdf_path",file.absolutePath);putExtra("pdf_name","HoraTrack_Bilan_travail_$year.pdf")})}.onFailure{Toast.makeText(a,"Impossible de générer le bilan annuel",Toast.LENGTH_LONG).show()}}
}

/** Bouton d'aperçu de l'estimation annuelle de rémunération. */
class AnnualSalaryPdfButton @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null,defStyleAttr:Int=android.R.attr.buttonStyle):Button(context,attrs,defStyleAttr){
    init{setOnClickListener{val a=context as? MainActivity?:return@setOnClickListener;val year=Calendar.getInstance().get(Calendar.YEAR);runCatching{val file=File(a.cacheDir,"HoraTrack_Estimation_salaire_$year.pdf");file.outputStream().use{AnnualPdfReports.writeSalary(a,PointageStore.load(a),year,it)};a.startActivity(Intent(a,PdfPreviewActivity::class.java).apply{putExtra("pdf_path",file.absolutePath);putExtra("pdf_name","HoraTrack_Estimation_salaire_$year.pdf")})}.onFailure{Toast.makeText(a,"Impossible de générer l'estimation annuelle",Toast.LENGTH_LONG).show()}}}
}
