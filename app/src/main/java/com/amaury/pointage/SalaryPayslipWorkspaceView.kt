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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.V2PayslipStore
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Espace de comparaison : estimation HoraTrack + nombre illimité de bulletins réels. */
class SalaryPayslipWorkspaceView(context:Context,private val company:SalaryCompanyStore.Company):LinearLayout(context){
 private var page=0; private val pageBox=LinearLayout(context); private val indicator=TextView(context)
 private val gesture=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){override fun onDown(e:MotionEvent)=true;override fun onFling(e1:MotionEvent?,e2:MotionEvent,velocityX:Float,velocityY:Float):Boolean{if(e1==null||abs(e2.x-e1.x)<80)return false;if(e2.x<e1.x)next() else previous();return true}})
 init{orientation=VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(12));addView(TextView(context).apply{text="FICHE DE SALAIRE";textSize=18f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER});addView(TextView(context).apply{text="Glisse à gauche/droite pour comparer l’estimation HoraTrack aux bulletins réels de cette entreprise.";textSize=12f;setPadding(0,dp(5),0,dp(8))});pageBox.orientation=VERTICAL;pageBox.setOnTouchListener{_,e->gesture.onTouchEvent(e)};addView(pageBox,LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));indicator.gravity=Gravity.CENTER;indicator.textSize=14f;indicator.setPadding(0,dp(8),0,dp(8));indicator.setOnClickListener{choosePage()};addView(indicator);render()}
 private fun records()=V2PayslipStore.forCompany(context,company.id)
 private fun render(){val total=records().size+1;page=page.coerceIn(0,total-1);pageBox.removeAllViews();if(page==0)renderEstimate() else renderReal(records()[page-1]);indicator.text="${page+1} / $total"}
 private fun renderEstimate(){val prefs=SalaryCompanyStore.prefs(context,company.id);val rate=prefs.getString("hourly_rate","").orEmpty();val weekly=prefs.getString("contract_weekly_hours","").orEmpty();val meal=prefs.getString("meal_amount","").orEmpty();pageBox.addView(TextView(context).apply{text="FICHE DE PAIE ESTIMATIVE";textSize=17f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(10))});pageBox.addView(TextView(context).apply{text="Entreprise : ${company.name.ifBlank{"Non renseignée"}}\nSIRET : ${company.siret.ifBlank{"Non renseigné"}}\n\nTaux horaire brut : ${rate.ifBlank{"à compléter"}}\nDurée hebdomadaire : ${weekly.ifBlank{"à compléter"}}\nPanier unitaire : ${meal.ifBlank{"à compléter"}}\n\nL’estimation détaillée reste calculée par le moteur Salaire HoraTrack. Cette page est une estimation et non un bulletin officiel.";textSize=14f})}
 private fun renderReal(r:V2PayslipStore.Record){val month=DateFormatSymbols(Locale.FRANCE).months.getOrNull(r.month).orEmpty().replaceFirstChar{it.uppercase()};val gross=r.gross?.let{String.format(Locale.FRANCE,"%.2f €",it)}?:"à confirmer";val net=r.net?.let{String.format(Locale.FRANCE,"%.2f €",it)}?:"non renseigné";pageBox.addView(TextView(context).apply{text="BULLETIN RÉEL — $month ${r.year}";textSize=17f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(10))});pageBox.addView(TextView(context).apply{text="Brut : $gross\nNet : $net\nDocument original conservé.";textSize=14f});pageBox.addView(button("OUVRIR LE DOCUMENT"){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(r.sourceUri)).apply{setDataAndType(Uri.parse(r.sourceUri),r.sourceMime?:"*/*");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}.onFailure{Toast.makeText(context,"Impossible d’ouvrir le document",Toast.LENGTH_LONG).show()}});pageBox.addView(button("ANALYSER / COMPARER AVEC L’IA"){askAiConsent(r)});pageBox.addView(button("SUPPRIMER CE BULLETIN"){AlertDialog.Builder(context).setTitle("Supprimer ce bulletin ?").setMessage("Le bulletin sera retiré de l’historique HoraTrack. Le fichier original extérieur à HoraTrack n’est pas modifié.").setNegativeButton("ANNULER",null).setPositiveButton("SUPPRIMER"){_,_->V2PayslipStore.remove(context,r.id);page=(page-1).coerceAtLeast(0);render()}.show()})}
 private fun emptyImportActions(){pageBox.addView(button("PRENDRE UNE PHOTO"){launchImport()});pageBox.addView(button("IMPORTER UN FICHIER"){launchImport()})}
 private fun launchImport(){val a=context as? Activity?:return;a.startActivity(Intent(context,V2PayslipImportActivity::class.java).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID,company.id).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_NAME,company.name))}
 private fun askAiConsent(r:V2PayslipStore.Record){AlertDialog.Builder(context).setTitle("Analyse avec l’IA").setMessage("Autoriser l’analyse comparative de ce bulletin avec l’estimation HoraTrack ? Les résultats devront distinguer calcul certain, estimation et anomalie potentielle.").setNegativeButton("ANNULER",null).setPositiveButton("AUTORISER"){_,_->val comparison=V2PayslipStore.comparison(context,r);val message=when{comparison==null->"Comparaison automatique insuffisante : complète d’abord les données Salaire/Convention. Aucune conclusion juridique n’est inventée.";comparison.conforming->"Les montants contrôlés concordent avec l’estimation HoraTrack dans la tolérance du moteur.";else->"Un ou plusieurs écarts potentiels sont détectés. Ils doivent être vérifiés avant toute conclusion."};AlertDialog.Builder(context).setTitle("Comparaison").setMessage(message).setPositiveButton("OK",null).show()}.show()}
 private fun next(){if(page<records().size){page++;render()}};private fun previous(){if(page>0){page--;render()}}
 private fun choosePage(){val rs=records();val labels=ArrayList<String>();labels+="Estimation HoraTrack";rs.forEach{r->val m=DateFormatSymbols(Locale.FRANCE).months.getOrNull(r.month).orEmpty().replaceFirstChar{it.uppercase()};labels+="Bulletin réel — $m ${r.year}"};AlertDialog.Builder(context).setTitle("Choisir une page").setItems(labels.toTypedArray()){_,which->page=which;render()}.setNegativeButton("ANNULER",null).show()}
 private fun button(label:String,click:()->Unit)=Button(context).apply{text=label;isAllCaps=false;setBackgroundResource(R.drawable.hp_panel);setOnClickListener{click()}}.also{pageBox.addView(it,LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52)).apply{topMargin=dp(7)})}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
