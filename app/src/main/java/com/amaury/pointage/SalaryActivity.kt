package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
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
        selectedConvention=ConventionCatalog.findByIdcc(prefs.getString("company_idcc", prefs.getString("convention_idcc","0292"))) ?: ConventionCatalog.conventions.first{it.idcc=="0292"}
        updateConventionDisplay(); updateMonthLabel(); updateStartDateLabel(); showInitialState(); applySalaryTheme(); showPrincipalEnterpriseOnly()
        hourlyRateInput.addTextChangedListener(object:TextWatcher{ override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ val value=s?.toString().orEmpty().trim().replace(',','.'); prefs.edit().putString("hourly_rate",value).apply(); calculateSalary(false)}; override fun afterTextChanged(s:Editable?)=Unit })
        backButton.setOnClickListener{finish()}; chooseMonthButton.setOnClickListener{showMonthDialog()}; searchConventionButton.setOnClickListener{showConventionSearchDialog()}; selectedConventionText.setOnClickListener{showConventionDetailsDialog()}; calculateButton.setOnClickListener{calculateSalary()}; chooseStartDateButton.setOnClickListener{showStartDatePicker()}
        calculateSalary(false)
    }

    override fun onResume() {
        super.onResume()
        val principalIdcc = prefs.getString("company_idcc", "").orEmpty()
        if (principalIdcc.isNotBlank()) {
            ConventionCatalog.findByIdcc(principalIdcc)?.let { selectedConvention = it }
            prefs.edit().putString("convention_idcc", selectedConvention.idcc).apply()
            updateConventionDisplay()
        }
        showPrincipalEnterpriseOnly()
        applySalaryTheme()
        calculateSalary(false)
    }

    private fun showPrincipalEnterpriseOnly() {
        val enterpriseView = findViewById<EnterpriseLookupView>(R.id.enterpriseLookupView)
        enterpriseView.post {
            if (enterpriseView.childCount >= 4) enterpriseView.getChildAt(3)?.visibility = View.GONE
            (enterpriseView.getChildAt(0) as? TextView)?.text = "ENTREPRISE PRINCIPALE"
            (enterpriseView.getChildAt(1) as? TextView)?.text = "Le salaire, la convention et les règles affichés ci-dessous utilisent uniquement l'Entreprise 1 (principale)."
        }
    }

    private fun themeColors(): Triple<Int,Int,Int> {
        val appearance=getSharedPreferences("appearance_settings",Context.MODE_PRIVATE)
        val mode=appearance.getString("mode","auto")?:"auto"
        val systemDark=(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
        val dark=mode=="dark" || (mode=="auto" && systemDark)
        val defaultBg=if(dark) "#080808" else "#F3F0E8"
        val bg=runCatching{Color.parseColor(appearance.getString("app_bg",null)?:defaultBg)}.getOrElse{Color.parseColor(defaultBg)}
        val custom=appearance.getBoolean("custom_bg",false)
        val panel=if(custom) mix(bg, if(AppearanceManager.bestTextColor(bg)==Color.WHITE) Color.WHITE else Color.BLACK, if(AppearanceManager.bestTextColor(bg)==Color.WHITE) 0.16f else 0.07f) else if(dark) Color.parseColor("#1B1B1B") else Color.WHITE
        val text=AppearanceManager.bestTextColor(panel)
        val secondary=mix(text,panel,0.68f)
        return Triple(panel,text,secondary)
    }

    private fun mix(a:Int,b:Int,bAmount:Float):Int=Color.rgb(
        (Color.red(a)*(1f-bAmount)+Color.red(b)*bAmount).toInt().coerceIn(0,255),
        (Color.green(a)*(1f-bAmount)+Color.green(b)*bAmount).toInt().coerceIn(0,255),
        (Color.blue(a)*(1f-bAmount)+Color.blue(b)*bAmount).toInt().coerceIn(0,255)
    )

    private fun applySalaryTheme(){
        val (panel,text,secondary)=themeColors()
        (hourlyRateInput.parent as? LinearLayout)?.backgroundTintList=ColorStateList.valueOf(panel)
        hourlyRateInput.setTextColor(text);hourlyRateInput.setHintTextColor(secondary)
        selectedConventionText.backgroundTintList=ColorStateList.valueOf(panel);selectedConventionText.setTextColor(text)
        employmentStartDateText.setTextColor(text);salaryMonthText.setTextColor(text);conventionRuleStatusText.setTextColor(secondary)
        AppearanceManager.apply(this)
    }

    private fun updateConventionDisplay(){
        selectedConventionText.text=selectedConvention.displayName
        conventionRuleStatusText.text=(if(selectedConvention.rulesIntegrated) "✓ Règles intégrées dans le calcul" else "⚠ Règles détaillées non intégrées : calcul légal provisoire") + "  •  Appuie sur la convention pour tout voir"
    }

    private fun showConventionDetailsDialog() {
        val (_, textColor, secondaryColor) = themeColors()
        val companyName = prefs.getString("company_name", "").orEmpty().ifBlank { "Entreprise principale" }
        val companySiret = prefs.getString("company_siret", "").orEmpty()
        val companyAgreements = prefs.getString("company_agreement_summary", "").orEmpty()
        val travelRights = TravelRightsCatalog.forIdcc(selectedConvention.idcc)

        val details = buildString {
            append("ENTREPRISE PRINCIPALE\n")
            append(companyName)
            if (companySiret.isNotBlank()) append("\nSIRET : ").append(companySiret)
            append("\n\nCONVENTION COLLECTIVE\n")
            append(selectedConvention.fullName)
            if (selectedConvention.idcc.isNotBlank()) append("\nIDCC : ").append(selectedConvention.idcc)
            append("\nStatut : ").append(if (selectedConvention.rulesIntegrated) "règles intégrées au calcul" else "règles salariales spécifiques non intégrées — régime légal provisoire")

            append("\n\nHEURES SUPPLÉMENTAIRES\n")
            selectedConvention.overtimeTiers.forEach { tier ->
                val from = String.format(Locale.FRANCE, "%.0f", tier.fromHour)
                val to = tier.toHour?.let { String.format(Locale.FRANCE, "%.0f", it) }
                val rate = ((tier.multiplier - 1.0) * 100.0).toInt()
                if (to != null) append("• De ").append(from).append(" h à ").append(to).append(" h : +").append(rate).append(" %\n")
                else append("• Au-delà de ").append(from).append(" h : +").append(rate).append(" %\n")
            }

            selectedConvention.nightMultiplier?.let {
                append("\nTRAVAIL DE NUIT\n• Majoration intégrée : +").append(((it - 1.0) * 100.0).toInt()).append(" %\n")
            }
            selectedConvention.sundayHolidayMultiplier?.let {
                append("\nDIMANCHE / JOURS FÉRIÉS\n• Majoration intégrée : +").append(((it - 1.0) * 100.0).toInt()).append(" %\n")
            }

            append("\nAVANTAGES / GARANTIES\n")
            if (selectedConvention.advantages.isEmpty()) append("• Aucun avantage spécifique détaillé dans l'application.\n")
            else selectedConvention.advantages.forEach { append("• ").append(it).append('\n') }

            if (travelRights.isNotEmpty()) {
                append("\nDÉPLACEMENTS & FRAIS\n")
                travelRights.forEach { append("• ").append(it.title).append(" : ").append(it.detail).append('\n') }
            }

            append("\nPOINTS DE VIGILANCE\n")
            if (selectedConvention.cautions.isEmpty()) append("• Aucun point particulier enregistré.\n")
            else selectedConvention.cautions.forEach { append("• ").append(it).append('\n') }

            if (companyAgreements.isNotBlank()) {
                append("\nACCORDS DE L'ENTREPRISE PRINCIPALE\n")
                append(companyAgreements)
            }
        }

        val textView = TextView(this).apply {
            text = details.trim()
            textSize = 15f
            setTextColor(textColor)
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(textView) }

        AlertDialog.Builder(this)
            .setTitle(selectedConvention.displayName)
            .setView(scroll)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Changer de convention") { _, _ -> showConventionSearchDialog() }
            .show()
    }

    private fun updateStartDateLabel(){ val ms=prefs.getLong("employment_start_date",0L); employmentStartDateText.text=if(ms>0) "Date d'entrée : ${dateFormat.format(ms)}" else "Date d'entrée : non renseignée" }
    private fun showStartDatePicker(){ val saved=prefs.getLong("employment_start_date",0L); val c=Calendar.getInstance(Locale.FRANCE); if(saved>0)c.timeInMillis=saved; DatePickerDialog(this,{_,y,m,d-> c.set(y,m,d,12,0,0); c.set(Calendar.MILLISECOND,0); prefs.edit().putLong("employment_start_date",c.timeInMillis).apply(); updateStartDateLabel(); calculateSalary(false)},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show() }

    private fun showConventionSearchDialog(){
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,12,28,12)}; val search=EditText(this).apply{hint="🔎 Nom ou IDCC (ex. plasturgie, 292…)";isSingleLine=true}; val list=ListView(this)
        container.addView(search,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)); container.addView(list,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,720))
        var filtered=ConventionCatalog.conventions.toMutableList(); var adapter=ArrayAdapter(this,android.R.layout.simple_list_item_2,android.R.id.text1,filtered.map{"${it.displayName}\n${it.fullName}"}); list.adapter=adapter
        val dialog=AlertDialog.Builder(this).setTitle("Choisir la convention collective de l'entreprise principale").setView(container).setNegativeButton("Annuler",null).create()
        fun refresh(q:String){filtered=ConventionCatalog.conventions.filter{it.matches(q)}.toMutableList();adapter=ArrayAdapter(this,android.R.layout.simple_list_item_2,android.R.id.text1,filtered.map{"${it.displayName}\n${it.fullName}"});list.adapter=adapter}
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh(s?.toString().orEmpty())};override fun afterTextChanged(s:Editable?)=Unit})
        list.setOnItemClickListener{_,_,position,_->val convention=filtered.getOrNull(position)?:return@setOnItemClickListener;selectedConvention=convention;prefs.edit().putString("convention_idcc",convention.idcc).putString("company_idcc", convention.idcc).apply();updateConventionDisplay();calculateSalary(false);dialog.dismiss()};dialog.show()
    }
    private fun updateMonthLabel(){salaryMonthText.text="Mois : ${monthFormat.format(selectedMonth.time).replaceFirstChar{it.uppercase()}}"}
    private fun showMonthDialog(){val labels=ArrayList<String>();val months=ArrayList<Calendar>();val cursor=Calendar.getInstance(Locale.FRANCE).apply{set(Calendar.DAY_OF_MONTH,1);set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)};repeat(36){months.add(cursor.clone() as Calendar);labels.add(monthFormat.format(cursor.time).replaceFirstChar{it.uppercase()});cursor.add(Calendar.MONTH,-1)};val idx=months.indexOfFirst{it.get(Calendar.YEAR)==selectedMonth.get(Calendar.YEAR)&&it.get(Calendar.MONTH)==selectedMonth.get(Calendar.MONTH)}.coerceAtLeast(0);AlertDialog.Builder(this).setTitle("Choisir le mois").setSingleChoiceItems(labels.toTypedArray(),idx){d,w->selectedMonth.timeInMillis=months[w].timeInMillis;updateMonthLabel();calculateSalary(false);d.dismiss()}.setNegativeButton("Annuler",null).show()}

    private fun calculateSalary(showError:Boolean=true){
        val rateText=hourlyRateInput.text.toString().trim().replace(',','.');val hourlyRate=rateText.toDoubleOrNull();if(hourlyRate==null||hourlyRate<=0){showInitialState();if(showError)Toast.makeText(this,"Entre un taux horaire brut valide",Toast.LENGTH_LONG).show();return}
        prefs.edit().putString("hourly_rate",rateText).putString("convention_idcc",selectedConvention.idcc).putString("company_idcc", selectedConvention.idcc).apply()
        val result=SalaryCalculator.calculate(PointageStore.load(this),selectedMonth.get(Calendar.YEAR),selectedMonth.get(Calendar.MONTH),hourlyRate,selectedConvention);val euro=NumberFormat.getCurrencyInstance(Locale.FRANCE);salaryResultContainer.removeAllViews()
        val principalName=prefs.getString("company_name","").orEmpty().ifBlank{"Entreprise principale"}
        addSection("ENTREPRISE PRINCIPALE");addCard("Employeur utilisé pour le calcul",principalName);addCard("Convention collective",selectedConvention.displayName);addCard("Détails de la convention","Appuie sur la convention en haut de l'écran pour afficher toutes les informations.")
        val start=prefs.getLong("employment_start_date",0L);if(start>0){addCard("Date d'entrée dans l'entreprise",dateFormat.format(start));addCard("Ancienneté au mois sélectionné",formatSeniority(start))}else addCard("Ancienneté","Renseigne ta date d'entrée pour calculer l'ancienneté et les primes associées.")
        addSection("HEURES DU MOIS");addCard("Heures normales",formatDuration(result.regularMs));result.overtimeTiers.forEach{addCard(it.label,formatDuration(it.durationMs))};addCard("Total pointé",formatDuration(result.totalWorkedMs));addCard("Sessions terminées",result.completedSessions.toString())
        addSection("ESTIMATION BRUTE");addCard("Taux horaire brut",euro.format(hourlyRate));addCard("Valeur des heures pointées",euro.format(result.workedGross));addCard("Base mensualisée 151,67 h",euro.format(result.monthlyBaseGross));addCard("Heures supplémentaires payées",euro.format(result.overtimeGross));addCard("Salaire mensualisé estimé",euro.format(result.monthlyEstimatedGross),true)
    }
    private fun formatSeniority(startMs:Long):String{val start=Calendar.getInstance(Locale.FRANCE).apply{timeInMillis=startMs};val end=(selectedMonth.clone() as Calendar).apply{add(Calendar.MONTH,1);add(Calendar.MILLISECOND,-1)};if(start.after(end))return "0 mois";var years=end.get(Calendar.YEAR)-start.get(Calendar.YEAR);var months=end.get(Calendar.MONTH)-start.get(Calendar.MONTH);if(end.get(Calendar.DAY_OF_MONTH)<start.get(Calendar.DAY_OF_MONTH))months--;if(months<0){years--;months+=12};return when{years>0&&months>0->"$years an${if(years>1)"s" else ""} et $months mois";years>0->"$years an${if(years>1)"s" else ""}";else->"${months.coerceAtLeast(0)} mois"}}
    private fun showInitialState(){if(!::salaryResultContainer.isInitialized)return;salaryResultContainer.removeAllViews();addCard("Calcul automatique","Entre ou modifie ton taux horaire : les résultats se mettront à jour immédiatement.")}
    private fun addSection(title:String){val (_,text,_)=themeColors();val gold=Color.parseColor("#D6A84B");salaryResultContainer.addView(TextView(this).apply{this.text=title;setTextColor(if(AppearanceManager.contrastRatio(gold,Color.TRANSPARENT)>0) gold else text);textSize=15f;setTypeface(typeface,Typeface.BOLD);setPadding(2,dp(16),2,dp(5))})}
    private fun addCard(label:String,value:String,highlight:Boolean=false){val(panel,text,secondary)=themeColors();val gold=Color.parseColor("#F3D58A");val highlightColor=if(AppearanceManager.contrastRatio(gold,panel)>=4.5)gold else text;val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.hp_panel);backgroundTintList=ColorStateList.valueOf(panel);setPadding(dp(14),dp(11),dp(14),dp(11))};card.addView(TextView(this).apply{this.text=label;setTextColor(secondary);textSize=12f});card.addView(TextView(this).apply{this.text=value;setTextColor(if(highlight)highlightColor else text);textSize=if(highlight)21f else 17f;if(highlight)setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(3),0,0)});salaryResultContainer.addView(card,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)})}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt();private fun formatDuration(ms:Long):String{val min=ms.coerceAtLeast(0)/60000;return String.format(Locale.FRANCE,"%02dh %02dm",min/60,min%60)}
}
