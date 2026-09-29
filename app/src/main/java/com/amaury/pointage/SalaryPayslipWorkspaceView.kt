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
import com.amaury.pointage.v2.NetSalaryReferencePolicyV2
import com.amaury.pointage.v2.V2PayslipStore
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2SegmentedSalaryCanonicalBridge
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.CompanyAgreementPayrollBridgeV2
import com.amaury.pointage.v2.engine.PayrollPeriodV2
import com.amaury.pointage.v2.engine.SalaryExamplePdfV2
import com.amaury.pointage.v2.engine.SicknessPaymentFlowV2
import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import java.io.File
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Espace V2 : estimation HoraTrack + nombre illimité de bulletins réels. */
class SalaryPayslipWorkspaceView(context:Context,private val company:SalaryCompanyStore.Company):LinearLayout(context){
 private var page=0
 private val pageBox=LinearLayout(context)
 private val indicator=TextView(context)
 private val gesture=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){override fun onDown(e:MotionEvent)=true;override fun onFling(e1:MotionEvent?,e2:MotionEvent,velocityX:Float,velocityY:Float):Boolean{if(e1==null||abs(e2.x-e1.x)<80)return false;if(e2.x<e1.x)next() else previous();return true}})
 init{orientation=VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(12));addView(TextView(context).apply{text="FICHE DE SALAIRE";textSize=18f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER});addView(TextView(context).apply{text="L’estimation utilise le moteur V2 et les données de cette entreprise uniquement.";textSize=12f;setPadding(0,dp(5),0,dp(8))});addButtonTop("CHOISIR LE MOIS"){choosePeriod()};addButtonTop("CRÉER UNE FICHE DE PAIE EXEMPLE"){choosePdfFields()};pageBox.orientation=VERTICAL;pageBox.setOnTouchListener{_,e->gesture.onTouchEvent(e)};addView(pageBox,LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));indicator.gravity=Gravity.CENTER;indicator.textSize=14f;indicator.setPadding(0,dp(8),0,dp(8));indicator.setOnClickListener{choosePage()};addView(indicator);render()}
 private fun records()=V2PayslipStore.forCompany(context,company.id)
 private fun render(){val total=records().size+1;page=page.coerceIn(0,total-1);pageBox.removeAllViews();if(page==0)renderEstimate() else renderReal(records()[page-1]);indicator.text="${page+1} / $total"}
 private fun selectedPeriod():Pair<Int,Int>{val ms=context.getSharedPreferences("navigation_state",Context.MODE_PRIVATE).getLong("report_month_ms",-1L);val c=Calendar.getInstance(Locale.FRANCE);if(ms>0)c.timeInMillis=ms;return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)}
 private fun choosePeriod(){val labels=ArrayList<String>();val values=ArrayList<Long>();val c=Calendar.getInstance(Locale.FRANCE).apply{set(Calendar.DAY_OF_MONTH,1);set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};repeat(36){labels+=SimpleDateFormat("MMMM yyyy",Locale.FRANCE).format(c.time).replaceFirstChar{it.uppercase()};values+=c.timeInMillis;c.add(Calendar.MONTH,-1)};AlertDialog.Builder(context).setTitle("Choisir le mois").setItems(labels.toTypedArray()){_,which->context.getSharedPreferences("navigation_state",Context.MODE_PRIVATE).edit().putLong("report_month_ms",values[which]).apply();render()}.setNegativeButton("ANNULER",null).show()}
 private fun choosePdfFields(){
  val activity=context as? Activity?:return
  val fields=SalaryExamplePdfV2.Field.entries
  val labels=arrayOf("Entreprise et convention","Contrat et taux horaire","Heures normales / supplémentaires","Pauses déduites","Estimation brute","Compteurs de droits","Sources et éléments à vérifier")
  val selected=BooleanArray(fields.size){true}
  AlertDialog.Builder(activity)
   .setTitle("Éléments à afficher sur le PDF")
   .setMessage("Ces cases servent uniquement à choisir le contenu. Elles ne seront pas imprimées sur la fiche.")
   .setMultiChoiceItems(labels,selected){_,which,checked->selected[which]=checked}
   .setPositiveButton("OUVRIR LE PDF"){_,_->
    val chosen=fields.filterIndexed{index,_->selected[index]}.toSet()
    if(chosen.isEmpty())Toast.makeText(activity,"Choisis au moins un élément",Toast.LENGTH_LONG).show() else generatePdf(activity,chosen)
   }
   .setNegativeButton("ANNULER",null)
   .show()
 }
 private fun generatePdf(activity:Activity,fields:Set<SalaryExamplePdfV2.Field>){
  val(year,month)=selectedPeriod()
  val companyToken=company.siret.ifBlank{company.id}.replace(Regex("[^A-Za-z0-9_-]"),"_").take(32).ifBlank{"entreprise"}
  val fileName="Fiche_paie_exemple_HoraTrack_${companyToken}_${year}_${month+1}.pdf"
  runCatching{
   val file=File(activity.cacheDir,fileName)
   file.outputStream().use{SalaryExamplePdfV2.write(activity,company,year,month,fields,it)}
   activity.startActivity(Intent(activity,PdfPreviewActivity::class.java).apply{putExtra("pdf_path",file.absolutePath);putExtra("pdf_name",fileName)})
  }.onFailure{Toast.makeText(activity,"Impossible de générer la fiche exemple",Toast.LENGTH_LONG).show()}
 }
 private fun renderEstimate(){
  val prefs=SalaryCompanyStore.prefs(context,company.id);val weekly=prefs.getString("contract_weekly_hours","").orEmpty();val(year,month)=selectedPeriod();val period=SimpleDateFormat("MMMM yyyy",Locale.FRANCE).format(Calendar.getInstance(Locale.FRANCE).apply{set(year,month,1)}.time).replaceFirstChar{it.uppercase()}
  add(TextView(context).apply{text="FICHE DE PAIE ESTIMATIVE";textSize=17f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(4))})
  val seniority=seniority(prefs.getString("entry_date","").orEmpty(),year,month);add(TextView(context).apply{text="Période : $period\nEntreprise : ${company.name.ifBlank{"Non renseignée"}}\nSIRET : ${company.siret.ifBlank{"Non renseigné"}}\nAncienneté : $seniority";textSize=14f;setPadding(0,0,0,dp(10))})
  val conventionId=company.idcc.ifBlank{prefs.getString("company_idcc","").orEmpty()}
  val convention=ConventionCatalog.findByIdcc(context,conventionId)
  val canonicalResult=if(HoraTrackV2.ENABLED)runCatching{
   V2SegmentedSalaryCanonicalBridge.calculateForCompany(
    context=context,
    company=company,
    year=year,
    monthZeroBased=month,
    timeZoneId=ZoneId.systemDefault().id
   )
  }.getOrNull() else null
  val canonical=canonicalResult?.output
  if(canonical==null){
   val warnings=canonicalResult?.warnings.orEmpty()
   add(TextView(context).apply{
    text=buildString{
     append("Calcul détaillé indisponible : complète le contrat, les règles et les preuves de cette entreprise. HoraTrack n’invente aucun montant.")
     if(warnings.isNotEmpty())append("\n\nÀ vérifier :\n• ").append(warnings.distinct().joinToString("\n• "))
    }
    textSize=14f
   })
  }else{
   val payroll=canonical.net.projection?.payroll
   val payrollPeriod=PayrollPeriodV2.month(year,month)
   val referenceDate=payrollPeriod.referenceDate
   val agreementRules=CompanyAgreementPayrollBridgeV2.load(context,company.id,referenceDate,payrollPeriod)
   val coefficient=prefs.getString("convention_coefficient","").orEmpty().trim().toIntOrNull()
   val socialGross=payroll?.takeIf{canonical.cashGrossReliable&&it.grossReliable}?.let(NetSalaryReferencePolicyV2::socialGross)
   val lines=buildString{
    append("Convention : ").append(convention?.displayName?:"Non renseignée").append('\n')
    coefficient?.let{append("Coefficient : ").append(it).append('\n')}

    if(canonical.paidTimeReliable&&canonical.paidMinutes!=null){
     append("Temps payé : ").append(hours(canonical.paidMinutes.toLong()*60_000L)).append('\n')
     append("Heures supplémentaires variables : ")
      .append(canonical.variableOvertimeMinutes?.let{hours(it.toLong()*60_000L)}?:"À confirmer").append('\n')
     append("Heures complémentaires : ")
      .append(canonical.complementaryMinutes?.let{hours(it.toLong()*60_000L)}?:"À confirmer").append('\n')
    }else{
     append("Temps payé : À confirmer\n")
     append("Heures supplémentaires variables : À confirmer\n")
     append("Heures complémentaires : À confirmer\n")
    }

    if(canonical.premiumTimeReliable){
     append("Heures de nuit : ").append(canonical.nightMinutes?.let{hours(it.toLong()*60_000L)}?:"À confirmer").append('\n')
     append("Samedi : ").append(canonical.saturdayMinutes?.let{hours(it.toLong()*60_000L)}?:"À confirmer").append('\n')
     append("Dimanche : ").append(canonical.sundayMinutes?.let{hours(it.toLong()*60_000L)}?:"À confirmer").append('\n')
     append("Jours fériés : ").append(canonical.publicHolidayMinutes?.let{hours(it.toLong()*60_000L)}?:"À confirmer").append('\n')
    }else{
     append("Heures de nuit : À confirmer\n")
     append("Samedi : À confirmer\n")
     append("Dimanche : À confirmer\n")
     append("Jours fériés : À confirmer\n")
    }

    append("Paniers : À confirmer\n")
    if(year==2026){
     append("PLAFOND SS APPLIQUÉ : ")
      .append(payroll?.socialSecurityCeiling?.let{eur(it)}?:"À confirmer")
     if(payroll?.socialSecurityCeilingComplete!=true)append(" (à vérifier)")
     append('\n')
    }

    append("Majoration heures supplémentaires : ")
     .append(canonical.overtimeGross?.takeIf{canonical.workedGrossReliable}?.let{eur(it)}?:"À confirmer").append('\n')
    append("Autres primes variables : ")
     .append(canonical.premiumGross?.takeIf{canonical.workedGrossReliable}?.let{eur(it)}?:"À confirmer").append('\n')
    append("BRUT DE TRAVAIL : ")
     .append(canonical.workedGross?.takeIf{canonical.workedGrossReliable}?.let{eur(it)}?:"À confirmer").append('\n')
    append("BRUT EN ESPÈCES : ")
     .append(canonical.cashGross?.takeIf{canonical.cashGrossReliable}?.let{eur(it)}?:"À confirmer").append('\n')
    append("BRUT SOCIAL ESTIMÉ HORS PANIERS : ").append(socialGross?.let{eur(it)}?:"À confirmer").append('\n')

    if(payroll!=null&&canonical.cashGrossReliable){
     if(payroll.benefitsInKindDeduction>0.0)append("DONT AVANTAGES EN NATURE : ").append(eur(payroll.benefitsInKindDeduction)).append('\n')
     append("COTISATIONS LÉGALES : -").append(eur(payroll.statutory)).append('\n')
     append("RETRAITE COMPLÉMENTAIRE AGIRC-ARRCO / CEG / CET : -").append(eur(payroll.complementaryRetirement)).append('\n')
     if(payroll.companyEmployeeDeductions>0)append("RETENUES ENTREPRISE/SALARIÉ CONNUES : -").append(eur(payroll.companyEmployeeDeductions)).append('\n')
     if(payroll.benefitsInKindDeduction>0.0)append("AVANTAGES EN NATURE NON VERSÉS EN ESPÈCES : -").append(eur(payroll.benefitsInKindDeduction)).append('\n')
    }else{
     append("COTISATIONS / RETENUES : À confirmer\n")
    }

    if(canonical.netBeforeIncomeTaxComplete){
     append("NET ESTIMÉ AVANT IMPÔT : ").append(canonical.netBeforeIncomeTax?.let{eur(it)}?:"À confirmer").append('\n')
     append("NET IMPOSABLE ESTIMÉ : ").append(canonical.netTaxable?.let{eur(it)}?:"À confirmer").append('\n')
     append("PRÉLÈVEMENT À LA SOURCE : ").append(canonical.incomeTax?.let{"-"+eur(it)}?:"À confirmer").append('\n')
     append("NET ESTIMÉ APRÈS PAS : ").append(canonical.netAfterIncomeTax?.let{eur(it)}?:"À confirmer").append('\n')
    }else{
     append("NET ESTIMÉ AVANT IMPÔT : À confirmer\n")
     append("Cotisations ou paramètres de paie à confirmer : aucun net salarié final n'est affiché.\n")
    }
   }
   add(TextView(context).apply{text=lines;textSize=14f})

   if(agreementRules.hasApplicableRules)add(TextView(context).apply{text=buildString{
    append("\nRÈGLES D’ENTREPRISE APPLICABLES\n• ")
    append(agreementRules.applicableRules.joinToString("\n• "){"${it.category.name} : ${it.excerpt}"})
    append('\n')
    when{
     agreementRules.hasOvertimeConflicts->append("Les règles d’heures supplémentaires en conflit sont bloquées et exclues du calcul.")
     agreementRules.hasPeriodChanges->append("Une règle change pendant le mois : la chaîne segmentée conserve les périodes distinctes et bloque toute valeur non prouvée.")
     agreementRules.hasSafeOvertimeRules->append("Les taux d’heures supplémentaires vérifiés sont pris en compte par la chaîne canonique lorsque leur preuve est complète.")
     else->append("Aucune règle d’entreprise n’est actuellement assez structurée pour modifier automatiquement le calcul.")
    }
   };textSize=12f})

   if(agreementRules.hasPeriodChanges)add(TextView(context).apply{text="\n⚠ CHANGEMENT DE RÈGLE PENDANT LE MOIS\n"+agreementRules.periodSegments.joinToString("\n"){segment->"• ${segment.start.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))} → ${segment.endInclusive.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))} : ${segment.applicableRules.size} règle(s) applicable(s)"}+"\nLa sortie canonique conserve la segmentation et ne fusionne pas arbitrairement les règles.";textSize=12f;setTypeface(typeface,Typeface.BOLD)})

   if(agreementRules.hasOvertimeConflicts)add(TextView(context).apply{text="\n⚠ CONFLIT D’HEURES SUPPLÉMENTAIRES — BLOQUÉ\n"+agreementRules.conflictingOvertimeRules.joinToString("\n"){rule->val end=rule.band.toHourInclusive?.let{"${it}e heure"}?:"sans limite déterminée";"• ${rule.band.fromHourInclusive}e → $end : +${String.format(Locale.FRANCE,"%.2f",rule.percent)} %\n  ${rule.source.source.excerpt}"}+"\nCes règles restent exclues du calcul tant que le conflit n’est pas résolu.";textSize=12f;setTypeface(typeface,Typeface.BOLD)})

   ConventionNightRules.forIdcc(convention?.idcc.orEmpty())?.let{rule->
    add(TextView(context).apply{text="\nRègle nuit : ${rule.note}";textSize=12f})
   }
   if(canonical.warnings.isNotEmpty())add(TextView(context).apply{
    text="\nÀ vérifier :\n• "+canonical.warnings.distinct().joinToString("\n• ")
    textSize=12f
   })
   val monthStartMs=LocalDate.of(year,month+1,1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();val monthEndMs=LocalDate.of(year,month+1,1).plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();val sicknessAbsences=V2RightsStore.absencesForCompany(context,company.id).filter{it.type==AbsencePayrollImpactV2.TYPE_SICKNESS&&it.endMs>monthStartMs&&it.startMs<monthEndMs};if(sicknessAbsences.isNotEmpty())add(TextView(context).apply{text=buildString{append("\nARRÊT MALADIE / IJSS\n");sicknessAbsences.forEach{absence->val start=Instant.ofEpochMilli(absence.startMs).atZone(ZoneId.systemDefault()).toLocalDate();val end=Instant.ofEpochMilli(absence.endMs-1L).atZone(ZoneId.systemDefault()).toLocalDate();val allowance=V2PayslipStore.sicknessAllowanceForAbsence(context,company.id,absence);val flow=SicknessPaymentFlowV2.resolve(absence,allowance);append("• ").append(start.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))).append(" → ").append(end.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))).append(" • ").append(absenceTreatmentLabel(absence.salaryTreatment)).append('\n');when(flow.ijssRecipient){SicknessPaymentFlowV2.IjssRecipient.EMPLOYER->{append("  IJSS → employeur");flow.employerIjssReimbursementGross?.let{append(" : ").append(eur(it)).append(" brut estimés")};append(" — non ajoutées une 2e fois au salarié\n")};SicknessPaymentFlowV2.IjssRecipient.EMPLOYEE->{append("  IJSS → salarié séparément");flow.directEmployeeIjssGross?.let{append(" : ").append(eur(it)).append(" brut estimés")};append(" — hors net de la fiche employeur\n")};SicknessPaymentFlowV2.IjssRecipient.TO_CONFIRM->append("  Destination IJSS : à confirmer\n")};flow.warnings.firstOrNull()?.let{append("  ⚠ ").append(it).append('\n')}}};textSize=12f})
  }
  add(TextView(context).apply{text="\nDurée hebdomadaire contractuelle : ${weekly.ifBlank{"à compléter"}}\nCette fiche est une estimation HoraTrack, pas un bulletin officiel.";textSize=12f});addButton("PRENDRE UNE PHOTO"){launchPhoto()};addButton("IMPORTER UN FICHIER"){launchImport()}
 }
 private fun absenceTreatmentLabel(value:AbsenceSalaryTreatmentV2)=when(value){AbsenceSalaryTreatmentV2.FULLY_MAINTAINED->"maintien complet";AbsenceSalaryTreatmentV2.PARTIALLY_MAINTAINED->"maintien partiel à chiffrer";AbsenceSalaryTreatmentV2.UNPAID->"sans maintien employeur";AbsenceSalaryTreatmentV2.TO_CONFIRM->"maintien à confirmer"}
 private fun seniority(raw:String,year:Int,month:Int):String{val start=runCatching{LocalDate.parse(raw.trim(),DateTimeFormatter.ofPattern("dd/MM/yyyy",Locale.FRANCE))}.getOrNull()?:return "à compléter";val end=LocalDate.of(year,month+1,1).withDayOfMonth(LocalDate.of(year,month+1,1).lengthOfMonth());if(start.isAfter(end))return "0 mois";val months=ChronoUnit.MONTHS.between(start.withDayOfMonth(1),end.withDayOfMonth(1)).toInt();val y=months/12;val m=months%12;return when{y>0&&m>0->"$y an${if(y>1)"s" else ""} et $m mois";y>0->"$y an${if(y>1)"s" else ""}";else->"$m mois"}}
 private fun renderReal(r:V2PayslipStore.Record){val month=DateFormatSymbols(Locale.FRANCE).months.getOrNull(r.month).orEmpty().replaceFirstChar{it.uppercase()};val gross=r.gross?.let{eur(it)}?:"à confirmer";val net=r.net?.let{eur(it)}?:"non renseigné";add(TextView(context).apply{text="BULLETIN RÉEL — $month ${r.year}";textSize=17f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(10))});add(TextView(context).apply{text="Brut : $gross\nNet : $net\nDocument original conservé.";textSize=14f});addButton("OUVRIR LE DOCUMENT"){val uri=Uri.parse(r.sourceUri);runCatching{context.startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,r.sourceMime?:"*/*");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}.onFailure{Toast.makeText(context,"Impossible d’ouvrir le document",Toast.LENGTH_LONG).show()}};addButton("ANALYSER / COMPARER AVEC L’IA"){askAiConsent(r)};addButton("SUPPRIMER CE BULLETIN"){AlertDialog.Builder(context).setTitle("Supprimer ce bulletin ?").setMessage("Le bulletin sera retiré de l’historique HoraTrack. Le fichier original extérieur à HoraTrack n’est pas modifié.").setNegativeButton("ANNULER",null).setPositiveButton("SUPPRIMER"){_,_->V2PayslipStore.remove(context,r.id);page=(page-1).coerceAtLeast(0);render()}.show()}}
 private fun launchPhoto(){val a=context as? Activity?:return;a.startActivity(Intent(context,SalaryPayslipPhotoActivity::class.java).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID,company.id).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_NAME,company.name))}
 private fun launchImport(){val a=context as? Activity?:return;a.startActivity(Intent(context,V2PayslipImportActivity::class.java).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID,company.id).putExtra(V2PayslipImportActivity.EXTRA_COMPANY_NAME,company.name))}
 private fun askAiConsent(r:V2PayslipStore.Record){AlertDialog.Builder(context).setTitle("Analyse avec l’IA").setMessage("Autoriser l’analyse comparative de ce bulletin avec l’estimation HoraTrack ? Les résultats distinguent calcul certain, estimation et anomalie potentielle.").setNegativeButton("ANNULER",null).setPositiveButton("AUTORISER"){_,_->val comparison=V2PayslipStore.comparison(context,r);val message=when{comparison==null->"Comparaison insuffisante : complète les données Salaire/Convention. Aucune conclusion juridique n’est inventée.";comparison.conforming->"Calcul : les montants contrôlés concordent avec l’estimation HoraTrack dans la tolérance du moteur.";else->"Anomalie potentielle : un ou plusieurs écarts sont détectés. Vérification nécessaire avant toute conclusion."};AlertDialog.Builder(context).setTitle("Comparaison").setMessage(message).setPositiveButton("OK",null).show()}.show()}
 private fun next(){if(page<records().size){page++;render()}};private fun previous(){if(page>0){page--;render()}}
 private fun choosePage(){val rs=records();val labels=ArrayList<String>();labels+="Estimation HoraTrack";rs.forEach{r->val m=DateFormatSymbols(Locale.FRANCE).months.getOrNull(r.month).orEmpty().replaceFirstChar{it.uppercase()};labels+="Bulletin réel — $m ${r.year}"};AlertDialog.Builder(context).setTitle("Choisir une page").setItems(labels.toTypedArray()){_,which->page=which;render()}.setNegativeButton("ANNULER",null).show()}
 private fun add(v:android.view.View){pageBox.addView(v)}
 private fun addButtonTop(label:String,click:()->Unit){addView(Button(context).apply{text=label;isAllCaps=false;setBackgroundResource(R.drawable.hp_panel);setOnClickListener{click()}},LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52)).apply{bottomMargin=dp(7)})}
 private fun addButton(label:String,click:()->Unit){pageBox.addView(Button(context).apply{text=label;isAllCaps=false;setBackgroundResource(R.drawable.hp_panel);setOnClickListener{click()}},LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52)).apply{topMargin=dp(7)})}
 private fun hours(ms:Long)=String.format(Locale.FRANCE,"%.2f h",ms/3_600_000.0)
 private fun eur(v:Double)=String.format(Locale.FRANCE,"%.2f €",v)
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
