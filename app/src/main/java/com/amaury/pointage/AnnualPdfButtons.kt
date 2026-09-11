package com.amaury.pointage

import android.app.AlertDialog
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
    init{setOnClickListener{chooseCompanyAndOpen()}}

    private fun chooseCompanyAndOpen(){
        val a=context as? MainActivity?:return
        val stored=SalaryCompanyStore.readConfirmed(a)
        if(!stored.reliable){
            Toast.makeText(a,"Entreprises Salaire indisponibles : vérifie le stockage avant de générer l'estimation annuelle",Toast.LENGTH_LONG).show()
            return
        }
        val companies=stored.companies
        when{
            companies.isEmpty()->Toast.makeText(a,"Ajoute d'abord une entreprise dans Salaire",Toast.LENGTH_LONG).show()
            companies.size==1->open(a,companies.single())
            else->AlertDialog.Builder(a)
                .setTitle("Entreprise pour l'estimation annuelle")
                .setItems(companies.map{companyLabel(it)}.toTypedArray()){_,which->open(a,companies[which])}
                .setNegativeButton("ANNULER",null)
                .show()
        }
    }

    private fun open(a:MainActivity,company:SalaryCompanyStore.Company){
        val year=Calendar.getInstance().get(Calendar.YEAR)
        val token=company.siret.ifBlank{company.id}.replace(Regex("[^A-Za-z0-9_-]"),"_").take(32).ifBlank{"entreprise"}
        val name="HoraTrack_Estimation_salaire_${token}_$year.pdf"
        runCatching{
            val file=File(a.cacheDir,name)
            file.outputStream().use{AnnualPdfReports.writeSalary(a,PointageStore.load(a),year,it,company)}
            a.startActivity(Intent(a,PdfPreviewActivity::class.java).apply{putExtra("pdf_path",file.absolutePath);putExtra("pdf_name",name)})
        }.onFailure{Toast.makeText(a,"Impossible de générer l'estimation annuelle",Toast.LENGTH_LONG).show()}
    }

    private fun companyLabel(company:SalaryCompanyStore.Company)=buildString{
        append(company.name.ifBlank{"Entreprise"})
        if(company.siret.isNotBlank())append("\nSIRET : ").append(company.siret)
    }
}
