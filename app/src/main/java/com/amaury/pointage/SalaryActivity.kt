package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SalaryActivity : Activity() {
    private lateinit var hourlyRateInput: EditText
    private lateinit var salaryMonthText: TextView
    private lateinit var salaryResultContainer: LinearLayout
    private lateinit var selectedConventionText: TextView
    private lateinit var conventionRuleStatusText: TextView
    private lateinit var employmentStartDateText: TextView
    private var selectedConvention = ConventionCatalog.conventions.first { it.idcc == "0292" }
    private val selectedMonth = Calendar.getInstance(Locale.FRANCE).apply { set(Calendar.DAY_OF_MONTH,1); set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
    private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
    private val prefs by lazy { getSharedPreferences("salary_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_salary)
        hourlyRateInput=findViewById(R.id.hourlyRateInput); salaryMonthText=findViewById(R.id.salaryMonthText); salaryResultContainer=findViewById(R.id.salaryResultContainer)
        selectedConventionText=findViewById(R.id.selectedConventionText); conventionRuleStatusText=findViewById(R.id.conventionRuleStatusText); employmentStartDateText=findViewById(R.id.employmentStartDateText)
        val backButton=findViewById<Button>(R.id.salaryBackButton); val chooseMonthButton=findViewById<Button>(R.id.chooseSalaryMonthButton); val calculateButton=findViewById<Button>(R.id.calculateSalaryButton); val searchConventionButton=findViewById<Button>(R.id.searchConventionButton); val chooseStartDateButton=findViewById<Button>(R.id.chooseEmploymentStartDateButton)
        hourlyRateInput.setText(prefs.getString("hourly_rate","") ?: "")
        selectedConvention=ConventionCatalog.findByIdcc(prefs.getString("convention_idcc","0292")) ?: ConventionCatalog.conventions.first{it.idcc=="0292"}
        updateConventionDisplay(); updateMonthLabel(); updateStartDateLabel(); showInitialState()
        hourlyRateInput.addTextChangedListener(object:TextWatcher{ override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ val value=s?.toString().orEmpty().trim().replace(',','.'); prefs.edit().putString("hourly_rate",value).apply(); calculateSalary(false)}; override fun afterTextChanged(s:Editable?)=Unit })
        backButton.setOnClickListener{finish()}; chooseMonthButton.setOnClickListener{showMonthDialog()}; searchConventionButton.setOnClickListener{showConventionSearchDialog()}; selectedConventionText.setOnClickListener{showConventionSearchDialog()}; calculateButton.setOnClickListener{calculateSalary()}; chooseStartDateButton.setOnClickListener{showStartDatePicker()}
        calculateSalary(false)
    }

    private fun updateConventionDisplay(){ selectedConventionText.text=selectedConvention.displayName; conventionRuleStatusText.text=if(selectedConvention.rulesIntegrated) "✓ Règles intégrées dans le calcul" else "⚠ Règles détaillées non intégrées : calcul légal provisoire" }
    private fun updateStartDateLabel(){ val ms=prefs.getLong("employment_start_date",0L); employmentStartDateText.text=if(ms>0) "Date d'entrée : ${dateFormat.format(ms)}" else "Date d'entrée : non renseignée" }
    private fun showStartDatePicker(){ val saved=prefs.getLong("employment_start_date",0L); val c=Calendar.getInstance(Locale.FRANCE); if(saved>0)c.timeInMillis=saved; DatePickerDialog(this,{_,y,m,d-> c.set(y,m,d,12,0,0); c.set(Calendar.MILLISECOND,0); prefs.edit().putLong("employment_start_date",c.timeInMillis).apply(); updateStartDateLabel(); calculateSalary(false)},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show() }

    private fun showConventionSearchDialog(){
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,12,28,12)}; val search=EditText(this).apply{hint="🔎 Nom ou IDCC (ex. plasturgie, 292…)";isSingleLine=true}; val list=ListView(this)
        container.addView(search,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)); container.addView(list,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,720))
        var filtered=ConventionCatalog.conventions.toMutableList(); var adapter=ArrayAdapter(this,android.R.layout.simple_list_item_2,android.R.id.text1,filtered.map{"${it.displayName}\n${it.fullName}"}); list.adapter=adapter
        val dialog=AlertDialog.Builder(this).setTitle("Choisir la convention collective").setView(container).setNegativeButton("Annuler",null).create()
        fun refresh(q:String){filtered=ConventionCatalog.conventions.filter{it.matches(q)}.toMutableList();adapter=ArrayAdapter(this,android.R.layout.simple_list_item_2,android.R.id.text1,filtered.map{"${it.displayName}\n${it.fullName}"});list.adapter=adapter}
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh(s?.toString().orEmpty())};override fun afterTextChanged(s:Editable?)=Unit})
        list.setOnItemClickListener{_,_,position,_->val convention=filtered.getOrNull(position)?:return@setOnItemClickListener;selectedConvention=convention;prefs.edit().putString("convention_idcc",convention.idcc).apply();updateConventionDisplay();calculateSalary(false);dialog.dismiss()};dialog.show()
    }
    private fun updateMonthLabel(){salaryMonthText.text="Mois : ${monthFormat.format(selectedMonth.time).replaceFirstChar{it.uppercase()}}"}
    private fun showMonthDialog(){val labels=ArrayList<String>();val months=ArrayList<Calendar>();val cursor=Calendar.getInstance(Locale.FRANCE).apply{set(Calendar.DAY_OF_MONTH,1);set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};repeat(36){months.add(cursor.clone() as Calendar);labels.add(monthFormat.format(cursor.time).replaceFirstChar{it.uppercase()});cursor.add(Calendar.MONTH,-1)};val idx=months.indexOfFirst{it.get(Calendar.YEAR)==selectedMonth.get(Calendar.YEAR)&&it.get(Calendar.MONTH)==selectedMonth.get(Calendar.MONTH)}.coerceAtLeast(0);AlertDialog.Builder(this).setTitle("Choisir le mois").setSingleChoiceItems(labels.toTypedArray(),idx){d,w->selectedMonth.timeInMillis=months[w].timeInMillis;updateMonthLabel();calculateSalary(false);d.dismiss()}.setNegativeButton("Annuler",null).show()}

    private fun calculateSalary(showError:Boolean=true){
        val rateText=hourlyRateInput.text.toString().trim().replace(',','.');val hourlyRate=rateText.toDoubleOrNull();if(hourlyRate==null||hourlyRate<=0){showInitialState();if(showError)Toast.makeText(this,"Entre un taux horaire brut valide",Toast.LENGTH_LONG).show();return}
        prefs.edit().putString("hourly_rate",rateText).putString("convention_idcc",selectedConvention.idcc).apply()
        val result=SalaryCalculator.calculate(PointageStore.load(this),selectedMonth.get(Calendar.YEAR),selectedMonth.get(Calendar.MONTH),hourlyRate,selectedConvention);val euro=NumberFormat.getCurrencyInstance(Locale.FRANCE);salaryResultContainer.removeAllViews()
        addSection("CONVENTION ET ANCIENNETÉ");addCard("Convention collective",selectedConvention.displayName);addCard("Intitulé",selectedConvention.fullName);addCard("Statut des règles",if(selectedConvention.rulesIntegrated)"Règles intégrées" else "Calcul légal provisoire")
        val start=prefs.getLong("employment_start_date",0L);if(start>0){addCard("Date d'entrée dans l'entreprise",dateFormat.format(start));addCard("Ancienneté au mois sélectionné",formatSeniority(start))}else addCard("Ancienneté","Renseigne ta date d'entrée pour calculer l'ancienneté et les primes associées.")
        addSection("HEURES DU MOIS");addCard("Heures normales",formatDuration(result.regularMs));result.overtimeTiers.forEach{addCard(it.label,formatDuration(it.durationMs))};addCard("Total pointé",formatDuration(result.totalWorkedMs));addCard("Sessions terminées",result.completedSessions.toString())
        addSection("ESTIMATION BRUTE");addCard("Taux horaire brut",euro.format(hourlyRate));addCard("Valeur des heures pointées",euro.format(result.workedGross));addCard("Base mensualisée 151,67 h",euro.format(result.monthlyBaseGross));addCard("Heures supplémentaires payées",euro.format(result.overtimeGross));addCard("Salaire mensualisé estimé",euro.format(result.monthlyEstimatedGross),true)
        addSection("AVANTAGES / GARANTIES");if(selectedConvention.advantages.isEmpty())addCard("Informations intégrées","Aucun avantage spécifique intégré pour le moment.")else selectedConvention.advantages.forEachIndexed{i,v->addCard("Avantage ${i+1}",v)}
        addSection("POINTS DE VIGILANCE");if(selectedConvention.cautions.isEmpty())addCard("Informations intégrées","Aucun point particulier enregistré.")else selectedConvention.cautions.forEachIndexed{i,v->addCard("Point ${i+1}",v)}
    }
    private fun formatSeniority(startMs:Long):String{val start=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=startMs};val end=(selectedMonth.clone() as Calendar).apply{add(Calendar.MONTH,1);add(Calendar.MILLISECOND,-1)};if(start.after(end))return "0 mois";var years=end.get(Calendar.YEAR)-start.get(Calendar.YEAR);var months=end.get(Calendar.MONTH)-start.get(Calendar.MONTH);if(end.get(Calendar.DAY_OF_MONTH)<start.get(Calendar.DAY_OF_MONTH))months--;if(months<0){years--;months+=12};return when{years>0&&months>0->"$years an${if(years>1)"s" else ""} et $months mois";years>0->"$years an${if(years>1)"s" else ""}";else->"${months.coerceAtLeast(0)} mois"}}
    private fun showInitialState(){if(!::salaryResultContainer.isInitialized)return;salaryResultContainer.removeAllViews();addCard("Calcul automatique","Entre ou modifie ton taux horaire : les résultats se mettront à jour immédiatement.")}
    private fun addSection(title:String){salaryResultContainer.addView(TextView(this).apply{text=title;setTextColor(getColor(R.color.hp_gold));textSize=15f;setTypeface(typeface,Typeface.BOLD);setPadding(2,dp(16),2,dp(5))})}
    private fun addCard(label:String,value:String,highlight:Boolean=false){val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.hp_panel);setPadding(dp(14),dp(11),dp(14),dp(11))};card.addView(TextView(this).apply{text=label;setTextColor(getColor(R.color.hp_grey));textSize=12f});card.addView(TextView(this).apply{text=value;setTextColor(getColor(if(highlight)R.color.hp_gold_light else R.color.hp_white));textSize=if(highlight)21f else 17f;if(highlight)setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(3),0,0)});salaryResultContainer.addView(card,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)})}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt();private fun formatDuration(ms:Long):String{val min=ms.coerceAtLeast(0)/60000;return String.format(Locale.FRANCE,"%02dh %02dm",min/60,min%60)}
}
