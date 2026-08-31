package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2PayslipStore
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Espace de comparaison : estimation HoraTrack + nombre illimité de bulletins réels. */
class SalaryPayslipWorkspaceView(context:Context,private val company:SalaryCompanyStore.Company):LinearLayout(context){
 private var page=0
 private val pageBox=LinearLayout(context)
 private val indicator=TextView(context)
 private val gesture=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){override fun onDown(e:MotionEvent)=true;override fun onFling(e1:MotionEvent?,e2:MotionEvent,velocityX:Float,velocityY:Float):Boolean{if(e1==null||abs(e2.x-e1.x)<80)return false;if(e2.x<e1.x)next() else previous();return true}})
 init{
  orientation=VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(12))
  addView(TextView(context).apply{text="FICHE DE SALAIRE";textSize=18f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER})
  addView(TextView(context).apply{text="Glisse à gauche/droite pour comparer l’estimation HoraTrack aux bulletins réels de cette entreprise.";textSize=12f;setPadding(0,dp(5),0,dp(8))})
  pageBox.orientation=VERTICAL;pageBox.setOnTouchListener{_,e->gesture.onTouchEvent(e)};addView(pageBox,LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
  indicator.gravity=Gravity.CENTER;indicator.textSize=14f;indicator.setPadding(0,dp(8),0,dp(8));indicator.setOnClickListener{choosePage()};addView(indicator);render()
 }
 private fun records()=V2PayslipStore.forCompany(context,company.id)
 private fun render(){val total=records().size+1;page=page.coerceIn(0,total-1);pageBox.removeAllViews();if(page==0)renderEstimate() else renderReal(records()[page-1]);indicator.text="${page+1} / $total"}

 private fun selectedPeriod():Pair<Int,Int>{
  val ms=context.getSharedPreferences("navigation_state",Context.MODE_PRIVATE).getLong("report_month_ms",-1L)
  val c=Calendar.getInstance(Locale.FRANCE);if(ms>0)c.timeInMillis=ms
  return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
 }
 private fun renderEstimate(){
  val prefs=SalaryCompanyStore.prefs(context,company.id);val rate=prefs.getString("hourly_rate","").orEmpty().toDoubleOrNull();val weekly=prefs.getString("contract_weekly_hours","").orEmpty();val meal=prefs.getString("meal_amount","").orEmpty();val(year,month)=selectedPeriod();val period=SimpleDateFormat("MMMM yyyy",Locale.FRANCE).format(Calendar.getInstance(Locale.FRANCE).apply{set(year,month,1)}.time).replaceFirstChar{it.uppercase()}
  add(TextView(context).apply{text="FICHE DE PAIE ESTIMATIVE";textSize=17f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(4))})
  add(TextView(context).apply{text="Période : $period\nEntreprise : ${company.name.ifBlank{"Non renseignée"}}\nSIRET : ${company.siret.ifBlank{"Non renseigné"}}";textSize=14f;setPadding(0,0,0,dp(10))})
  val conventionId=company.idcc.ifBlank{prefs.getString("company_idcc","").orEmpty()};val convention=ConventionCatalog.findByIdcc(context,conventionId)
  val calc=if(HoraTrackV2.ENABLED&&rate!=null&&rate>0&&convention!=null)runCatching{V2SalaryAdapter.calculate(context,year,month,rate,convention)}.getOrNull() else null
  if(calc==null){
   add(TextView(context).apply{text="Calcul détaillé indisponible : complète le taux horaire et la convention de cette entreprise. HoraTrack n’invente aucune majoration.";textSize=14f})
  }else{
   val lines=buildString{
    append("Heures normales : ").append(hours(calc.regularMs)).append(" — ").append(eur(calc.regularGross)).append('\n')
    calc.overtimeTiers.filter{it.durationMs>0}.forEach{append(it.label).append(" : ").append(hours(it.durationMs)).append('\n')}
    append("Heures de nuit : ").append(hours(calc.nightMs)).append('\n')
    append("Samedi : ").append(hours(calc.saturdayMs)).append('\n')
    append("Dimanche : ").append(hours(calc.sundayMs)).append('\n')
    append("Majoration / primes calculées : ").append(eur(calc.premiumsGross)).append('\n')
    append("Panier unitaire : ").append(meal.ifBlank{"à compléter"}).append(if(meal.isBlank())"" else " €").append('\n')
    append("Total paniers : À confirmer selon les jours ouvrant droit").append("\n\n")
    append("BRUT ESTIMÉ : ").append(eur(calc.monthlyEstimatedGross)).append('\n')
    append("NET ESTIMÉ : non calculé tant que le moteur de cotisations fiable n’est pas disponible")
   }
   add(TextView(context).apply{text=lines;textSize=14f})
   if(calc.warnings.isNotEmpty())add(TextView(context).apply{text="\nÀ vérifier :\n• "+calc.warnings.joinToString("\n• ");textSize=12f})
  }
  add(TextView(context).apply{text="\nDurée hebdomadaire contractuelle : ${weekly.ifBlank{"à compléter"}}\nCette fiche est une estimation HoraTrack, pas un bulletin officiel.";textSize=12f})
  addButton("PRENDRE UNE PHOTO"){launchPhoto()};addButton("IMPORTER UN FICHIER"){launchImport()}
 }
 private fun renderReal(r:V2PayslipStore.Record){
  val month=DateFormatSymbols(Locale.FRANCE).months.getOrNull(r.month).orEmpty().replaceFirstChar{it.uppercase()};val gross=r.gross?.let{eur(it)}?:"à confirmer";val net=r.net?.let{eur(it)}?:"non renseigné"
  add(TextView(context).apply{text="BULLETIN RÉEL — $month ${r.year}";textSize=17f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(10))})
  add(TextView(context).apply{text="Brut : $gross\nNet : $net\nDocument original conservé.";textSize=14f})
  addButton("OUVRIR LE DOCUMENT"){val uri=Uri.parse(r.sourceUri);runCatching{context.startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,r.sourceMime?:"*/*");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}.onFailure{Toast.makeText(context,"Impossible d’ouvrir le document",Toast.LENGTH_LONG).show()}}
  addButton("ANALYSER / COMPARER AVEC L’IA"){askAiConsent(r)}
  addButton("SUPPRIMER CE BULLETIN"){AlertDialog.Builder(context).setTitle("Supprimer ce bulletin ?").setMessage("Le bulletin sera retiré de l’historique HoraTrack. Le fichier original extérieur à HoraTrack n’est pas modifié.").setNegativeButton("ANNULER",null).setPositiveButton("SUPPRIMER"){_,_->V2PayslipStore.remove(context,r.id);page=(page-1).coerceAtLeast(0);render()}.show()}
 }
 private fun launchPhoto(){val a=context as? Activity?:return;a.startActivity(Intent(context,SalaryPayslipPhotoActivity::class.java).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID,company.id).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_NAME,company.name))}
 private fun launchImport(){val a=context as? Activity?:return;a.startActivity(Intent(context,V2PayslipImportActivity::class.java).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID,company.id).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_NAME,company.name))}
 private fun askAiConsent(r:V2PayslipStore.Record){AlertDialog.Builder(context).setTitle("Analyse avec l’IA").setMessage("Autoriser l’analyse comparative de ce bulletin avec l’estimation HoraTrack ? Les résultats distinguent calcul certain, estimation et anomalie potentielle.").setNegativeButton("ANNULER",null).setPositiveButton("AUTORISER"){_,_->val comparison=V2PayslipStore.comparison(context,r);val message=when{comparison==null->"Comparaison insuffisante : complète les données Salaire/Convention. Aucune conclusion juridique n’est inventée.";comparison.conforming->"Calcul : les montants contrôlés concordent avec l’estimation HoraTrack dans la tolérance du moteur.";else->"Anomalie potentielle : un ou plusieurs écarts sont détectés. Vérification nécessaire avant toute conclusion."};AlertDialog.Builder(context).setTitle("Comparaison").setMessage(message).setPositiveButton("OK",null).show()}.show()}
 private fun next(){if(page<records().size){page++;render()}};private fun previous(){if(page>0){page--;render()}}
 private fun choosePage(){val rs=records();val labels=ArrayList<String>();labels+="Estimation HoraTrack";rs.forEach{r->val m=DateFormatSymbols(Locale.FRANCE).months.getOrNull(r.month).orEmpty().replaceFirstChar{it.uppercase()};labels+="Bulletin réel — $m ${r.year}"};AlertDialog.Builder(context).setTitle("Choisir une page").setItems(labels.toTypedArray()){_,which->page=which;render()}.setNegativeButton("ANNULER",null).show()}
 private fun add(v:android.view.View){pageBox.addView(v)}
 private fun addButton(label:String,click:()->Unit){pageBox.addView(Button(context).apply{text=label;isAllCaps=false;setBackgroundResource(R.drawable.hp_panel);setOnClickListener{click()}},LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52)).apply{topMargin=dp(7)})}
 private fun hours(ms:Long)=String.format(Locale.FRANCE,"%.2f h",ms/3_600_000.0)
 private fun eur(v:Double)=String.format(Locale.FRANCE,"%.2f €",v)
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
