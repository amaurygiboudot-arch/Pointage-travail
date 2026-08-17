package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class EnterpriseLookupView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : LinearLayout(context, attrs, defStyleAttr) {
    private val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
    private val titleText = TextView(context)
    private val helpText = TextView(context)
    private val siretInput = EditText(context)
    private val searchButton = Button(context)
    private val companyText = TextView(context)
    private val advantagesText = TextView(context)
    private val agreementsButton = Button(context)
    private var currentSiren: String? = null
    private var currentCompanyName: String? = null

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundResource(R.drawable.hp_panel)
        titleText.apply { text="ENTREPRISE — RECHERCHE PAR SIRET"; textSize=15f; setTypeface(typeface,Typeface.BOLD) }; addView(titleText)
        helpText.apply { text="Entre le SIRET de ton établissement pour récupérer automatiquement l'entreprise et sa convention collective déclarée."; textSize=12f; setPadding(0,dp(5),0,dp(8)) }; addView(helpText)
        siretInput.apply { hint="SIRET — 14 chiffres"; inputType=android.text.InputType.TYPE_CLASS_NUMBER; isSingleLine=true; setText(prefs.getString("company_siret","")?:"") }; addView(siretInput,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        searchButton.apply { text="RECHERCHER L'ENTREPRISE"; setBackgroundResource(R.drawable.hp_panel); setOnClickListener{lookup()} }; addView(searchButton,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply{topMargin=dp(8)})
        companyText.apply { textSize=14f;setPadding(0,dp(10),0,0);visibility=View.GONE };addView(companyText)
        advantagesText.apply { textSize=13f;setPadding(0,dp(8),0,0);visibility=View.GONE };addView(advantagesText)
        agreementsButton.apply { text="VOIR LES ACCORDS D'ENTREPRISE SUR LÉGIFRANCE";setBackgroundResource(R.drawable.hp_panel);visibility=View.GONE;setOnClickListener{openLegifranceAgreements()} };addView(agreementsButton,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply{topMargin=dp(10)})
        applyTheme();restoreSavedCompany()
    }

    override fun onAttachedToWindow(){super.onAttachedToWindow();applyTheme()}

    private fun themeColors():Triple<Int,Int,Int>{
        val appearance=context.getSharedPreferences("appearance_settings",Context.MODE_PRIVATE)
        val mode=appearance.getString("mode","auto")?:"auto"
        val systemDark=(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
        val dark=mode=="dark"||(mode=="auto"&&systemDark)
        val defaultBg=if(dark)"#080808" else "#F3F0E8"
        val bg=runCatching{Color.parseColor(appearance.getString("app_bg",null)?:defaultBg)}.getOrElse{Color.parseColor(defaultBg)}
        val custom=appearance.getBoolean("custom_bg",false)
        val panel=if(custom) mix(bg,if(AppearanceManager.bestTextColor(bg)==Color.WHITE)Color.WHITE else Color.BLACK,if(AppearanceManager.bestTextColor(bg)==Color.WHITE)0.16f else 0.07f) else if(dark)Color.parseColor("#1B1B1B") else Color.WHITE
        val text=AppearanceManager.bestTextColor(panel)
        val secondary=mix(text,panel,0.68f)
        return Triple(panel,text,secondary)
    }
    private fun mix(a:Int,b:Int,bAmount:Float)=Color.rgb((Color.red(a)*(1f-bAmount)+Color.red(b)*bAmount).toInt().coerceIn(0,255),(Color.green(a)*(1f-bAmount)+Color.green(b)*bAmount).toInt().coerceIn(0,255),(Color.blue(a)*(1f-bAmount)+Color.blue(b)*bAmount).toInt().coerceIn(0,255))
    private fun applyTheme(){
        val(panel,text,secondary)=themeColors();val gold=Color.parseColor("#D6A84B");val accent=if(AppearanceManager.contrastRatio(gold,panel)>=4.5)gold else text
        backgroundTintList=ColorStateList.valueOf(panel);titleText.setTextColor(accent);helpText.setTextColor(secondary);siretInput.setTextColor(text);siretInput.setHintTextColor(secondary);companyText.setTextColor(text);advantagesText.setTextColor(secondary)
        searchButton.backgroundTintList=ColorStateList.valueOf(panel);searchButton.setTextColor(text);agreementsButton.backgroundTintList=ColorStateList.valueOf(panel);agreementsButton.setTextColor(text)
    }

    private fun restoreSavedCompany(){val name=prefs.getString("company_name","").orEmpty();val siret=prefs.getString("company_siret","").orEmpty();val siren=prefs.getString("company_siren","").orEmpty();val address=prefs.getString("company_address","").orEmpty();val ape=prefs.getString("company_ape","").orEmpty();val idcc=prefs.getString("company_idcc","").orEmpty();if(name.isNotBlank()||siret.isNotBlank()){currentCompanyName=name;currentSiren=siren.ifBlank{siret.take(9)};showCompanyInformation(buildCompanySummary(name,siret,address,ape,idcc));showAdvantages(buildAdvantagesSummary(idcc));agreementsButton.visibility=if(currentSiren.isNullOrBlank()&&currentCompanyName.isNullOrBlank())View.GONE else View.VISIBLE}}

    private fun lookup(){
        val siret=siretInput.text.toString().filter(Char::isDigit);if(siret.length!=14){Toast.makeText(context,"Le SIRET doit contenir exactement 14 chiffres",Toast.LENGTH_LONG).show();return}
        searchButton.isEnabled=false;searchButton.text="RECHERCHE…";companyText.text="Recherche dans les données publiques…";companyText.visibility=View.VISIBLE;advantagesText.visibility=View.GONE;agreementsButton.visibility=View.GONE
        Thread{try{
            val encoded=URLEncoder.encode(siret,StandardCharsets.UTF_8.name());val connection=URL("https://recherche-entreprises.api.gouv.fr/search?q=$encoded&per_page=1").openConnection() as HttpURLConnection;connection.requestMethod="GET";connection.connectTimeout=10000;connection.readTimeout=15000;connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","HP-Travail-Android")
            val code=connection.responseCode;val stream=if(code in 200..299)connection.inputStream else connection.errorStream;val body=stream?.bufferedReader()?.use{it.readText()}.orEmpty();connection.disconnect();if(code !in 200..299)throw IllegalStateException("service indisponible ($code)")
            val root=JSONObject(body);val results=root.optJSONArray("results")?:JSONArray();if(results.length()==0)throw IllegalStateException("aucune entreprise trouvée pour ce SIRET");val result=results.getJSONObject(0)
            val companyName=firstNonBlank(result.optString("nom_complet"),result.optString("nom_raison_sociale"),result.optString("denomination"),result.optString("nom"));val siren=result.optString("siren").ifBlank{siret.take(9)};val establishment=findMatchingEstablishment(result,siret)
            val address=firstNonBlank(establishment?.optString("adresse"),establishment?.optString("adresse_complete"),result.optString("adresse"));val ape=firstNonBlank(establishment?.optString("activite_principale"),result.optString("activite_principale"));val conventions=findConventionObjects(result);val idcc=conventions.firstOrNull()?.let{extractIdcc(it)}.orEmpty();val conventionName=conventions.firstOrNull()?.let{extractConventionName(it)}.orEmpty()
            prefs.edit().putString("company_siret",siret).putString("company_siren",siren).putString("company_name",companyName).putString("company_address",address).putString("company_ape",ape).putString("company_idcc",idcc).putString("company_convention_name",conventionName).apply();val localConvention=ConventionCatalog.findByIdcc(idcc);if(localConvention!=null)prefs.edit().putString("convention_idcc",localConvention.idcc).apply()
            post{currentSiren=siren;currentCompanyName=companyName;applyTheme();showCompanyInformation(buildCompanySummary(companyName,siret,address,ape,idcc,conventionName));showAdvantages(buildAdvantagesSummary(idcc));agreementsButton.visibility=if(siren.isBlank()&&companyName.isBlank())View.GONE else View.VISIBLE;searchButton.isEnabled=true;searchButton.text="RECHERCHER L'ENTREPRISE";if(localConvention!=null){Toast.makeText(context,"Entreprise trouvée — convention ${localConvention.displayName} sélectionnée",Toast.LENGTH_LONG).show();(context as? Activity)?.recreate()}else if(idcc.isNotBlank())Toast.makeText(context,"Entreprise trouvée — IDCC $idcc détecté",Toast.LENGTH_LONG).show()else Toast.makeText(context,"Entreprise trouvée",Toast.LENGTH_SHORT).show()}
        }catch(e:Exception){post{companyText.text="Impossible de récupérer l'entreprise : ${e.message?:"erreur inconnue"}";companyText.visibility=View.VISIBLE;advantagesText.visibility=View.GONE;agreementsButton.visibility=View.GONE;searchButton.isEnabled=true;searchButton.text="RECHERCHER L'ENTREPRISE";applyTheme()}}}.start()
    }

    private fun showCompanyInformation(value:String){companyText.text=value;companyText.visibility=if(value.isBlank())View.GONE else View.VISIBLE}
    private fun showAdvantages(value:String){advantagesText.text=value;advantagesText.visibility=if(value.isBlank())View.GONE else View.VISIBLE}
    private fun buildCompanySummary(name:String,siret:String,address:String,ape:String,idcc:String,conventionName:String=prefs.getString("company_convention_name","").orEmpty()):String{val lines=mutableListOf<String>();if(name.isNotBlank())lines+="🏢 $name";if(siret.isNotBlank())lines+="SIRET : $siret";if(address.isNotBlank())lines+="Adresse : $address";if(ape.isNotBlank())lines+="APE/NAF : $ape";if(idcc.isNotBlank())lines+="Convention : ${conventionName.ifBlank{"IDCC $idcc"}}${if(conventionName.isNotBlank())" — IDCC $idcc" else ""}";return lines.joinToString("\n")}
    private fun buildAdvantagesSummary(idcc:String):String{if(idcc.isBlank())return "";val convention=ConventionCatalog.findByIdcc(idcc)?:return "";if(convention.advantages.isEmpty()&&convention.cautions.isEmpty())return "";val items=mutableListOf<String>();if(convention.advantages.isNotEmpty()){items+="Rémunération / avantages connus dans l'application :";convention.advantages.forEach{items+="• $it"}};if(convention.cautions.isNotEmpty()){if(items.isNotEmpty())items+="";items+="À vérifier dans les accords de l'entreprise :";convention.cautions.take(4).forEach{items+="• $it"}};return items.joinToString("\n")}
    private fun openLegifranceAgreements(){val query=listOf(currentCompanyName.orEmpty(),currentSiren.orEmpty()).filter{it.isNotBlank()}.joinToString(" ");runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.legifrance.gouv.fr/liste/acco?query=${Uri.encode(query)}&searchField=ALL&tab_selection=acco"))}.onFailure{Toast.makeText(context,"Impossible d'ouvrir Légifrance",Toast.LENGTH_LONG).show()}}
    private fun findMatchingEstablishment(result:JSONObject,siret:String):JSONObject?{val siege=result.optJSONObject("siege");if(siege!=null&&siege.optString("siret")==siret)return siege;val matching=result.optJSONArray("matching_etablissements");if(matching!=null){for(i in 0 until matching.length()){val item=matching.optJSONObject(i)?:continue;if(item.optString("siret")==siret)return item};if(matching.length()>0)return matching.optJSONObject(0)};return siege}
    private fun findConventionObjects(root:JSONObject):List<JSONObject>{val found=mutableListOf<JSONObject>();val stack=mutableListOf<Any>(root);while(stack.isNotEmpty()){when(val current=stack.removeAt(stack.lastIndex)){is JSONObject->{val keys=current.keys();while(keys.hasNext()){val key=keys.next();val value=current.opt(key);if(key.equals("conventions_collectives",true)&&value is JSONArray){for(i in 0 until value.length())value.optJSONObject(i)?.let(found::add)}else if(value is JSONObject||value is JSONArray)stack+=value}};is JSONArray->{for(i in 0 until current.length()){val value=current.opt(i);if(value is JSONObject||value is JSONArray)stack+=value}}}};return found.distinctBy{extractIdcc(it)+"|"+extractConventionName(it)}}
    private fun extractIdcc(obj:JSONObject):String{val raw=firstNonBlank(obj.optString("idcc"),obj.optString("numero_idcc"),obj.optString("id_convention_collective"),obj.optString("id")).filter(Char::isDigit);return if(raw.isBlank())"" else raw.padStart(4,'0').takeLast(4)}
    private fun extractConventionName(obj:JSONObject)=firstNonBlank(obj.optString("nom"),obj.optString("titre"),obj.optString("libelle"),obj.optString("short_name"),obj.optString("title"))
    private fun firstNonBlank(vararg values:String?)=values.firstOrNull{!it.isNullOrBlank()}?.trim().orEmpty()
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
