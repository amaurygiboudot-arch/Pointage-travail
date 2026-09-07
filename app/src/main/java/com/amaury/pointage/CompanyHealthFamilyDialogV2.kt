package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyHealthFamilyStoreV2
import com.amaury.pointage.v2.engine.EmployerHealthFamilyV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Éditeur des taux patronaux maladie et allocations familiales datés. */
object CompanyHealthFamilyDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context,16),dp(context,8),dp(context,16),dp(context,8))
        }
        box.addView(TextView(context).apply {
            text = "Cas général 2026 : maladie 13 % et allocations familiales 5,25 %. Enregistre uniquement les taux confirmés pour l'entreprise et la période : certaines exonérations spécifiques peuvent les modifier."
            textSize = 13f
            setPadding(0,0,0,dp(context,8))
        })

        var listDialog: AlertDialog? = null
        val records = CompanyHealthFamilyStoreV2.list(context,companyId).sortedByDescending { it.effectiveFrom }
        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucune règle maladie / allocations familiales enregistrée."
                textSize = 13f
                setPadding(0,dp(context,4),0,dp(context,8))
            })
        } else {
            records.forEach { record ->
                box.addView(Button(context).apply {
                    isAllCaps=false
                    gravity=Gravity.START or Gravity.CENTER_VERTICAL
                    text=recordLabel(record)
                    setOnClickListener { listDialog?.dismiss(); showEditor(context,companyId,record) }
                },rowParams(context))
            }
        }
        box.addView(Button(context).apply {
            isAllCaps=false
            text="AJOUTER UNE RÈGLE"
            setOnClickListener { listDialog?.dismiss(); showEditor(context,companyId,null) }
        },rowParams(context))

        listDialog=AlertDialog.Builder(context)
            .setTitle("Maladie / allocations familiales employeur")
            .setView(box)
            .setNegativeButton("FERMER",null)
            .create()
        listDialog.show()
    }

    private fun showEditor(context: Context, companyId: String, existing: EmployerHealthFamilyV2.Record?) {
        val box=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(context,20),dp(context,8),dp(context,20),0)
        }
        val health=field(context,"Taux maladie employeur (%) — ex. 13,00",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val family=field(context,"Taux allocations familiales (%) — ex. 5,25",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val start=field(context,"Mois de début — MM/AAAA")
        val end=field(context,"Mois de fin — MM/AAAA (facultatif)")
        val source=field(context,"Source — ex. DSN / Urssaf / dispositif d'exonération")
        listOf(health,family,start,end,source).forEach { box.addView(it,rowParams(context)) }
        box.addView(TextView(context).apply {
            text="Si le régime change, ferme la période précédente puis ajoute une nouvelle règle. Des périodes qui se chevauchent bloqueront le calcul."
            textSize=12f
            setPadding(0,dp(context,6),0,0)
        })

        existing?.let {
            health.setText(percent(it.healthRate));family.setText(percent(it.familyRate))
            start.setText(it.effectiveFrom.format(monthFormatter));end.setText(it.effectiveTo?.format(monthFormatter).orEmpty())
            source.setText(it.source)
        }
        val builder=AlertDialog.Builder(context)
            .setTitle(if(existing==null) "Ajouter maladie / AF" else "Modifier maladie / AF")
            .setView(box).setPositiveButton("ENREGISTRER",null).setNegativeButton("ANNULER",null)
        if(existing!=null) builder.setNeutralButton("SUPPRIMER",null)
        val dialog=builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val healthRate=parseRate(health,"Taux maladie invalide") ?: return@setOnClickListener
                val familyRate=parseRate(family,"Taux allocations familiales invalide") ?: return@setOnClickListener
                val from=parseMonth(start,"Mois de début invalide") ?: return@setOnClickListener
                val to=if(end.text.toString().isBlank()) null else parseMonth(end,"Mois de fin invalide") ?: return@setOnClickListener
                if(to!=null && to<from){end.error="Le mois de fin doit être après le début";return@setOnClickListener}
                val rawSource=source.text.toString().trim()
                if(rawSource.isBlank()){source.error="Indique la source des taux";return@setOnClickListener}
                val record=EmployerHealthFamilyV2.Record(existing?.id ?: "health_family_${UUID.randomUUID()}",healthRate,familyRate,from,to,rawSource)
                if(!CompanyHealthFamilyStoreV2.save(context,companyId,record)){
                    Toast.makeText(context,"Échec de l'enregistrement",Toast.LENGTH_LONG).show();return@setOnClickListener
                }
                dialog.dismiss();Toast.makeText(context,"Taux maladie/AF enregistrés",Toast.LENGTH_SHORT).show();show(context,companyId)
            }
            if(existing!=null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if(CompanyHealthFamilyStoreV2.remove(context,companyId,existing.id)){
                    dialog.dismiss();Toast.makeText(context,"Règle supprimée",Toast.LENGTH_SHORT).show();show(context,companyId)
                } else Toast.makeText(context,"Échec de la suppression",Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun parseRate(field:EditText,error:String):Double?{
        val percent=field.text.toString().trim().replace(',','.').toDoubleOrNull()
        if(percent==null||!percent.isFinite()||percent<0.0||percent>100.0){field.error=error;return null}
        return percent/100.0
    }
    private fun parseMonth(field:EditText,error:String):YearMonth?{
        val parsed=runCatching{YearMonth.parse(field.text.toString().trim(),monthFormatter)}.getOrNull()
        if(parsed==null)field.error=error
        return parsed
    }
    private fun percent(rate:Double)=String.format(Locale.FRANCE,"%.4f",rate*100.0).trimEnd('0').trimEnd(',')
    private fun recordLabel(r:EmployerHealthFamilyV2.Record)=buildString{
        append("Maladie ").append(String.format(Locale.FRANCE,"%.2f %%",r.healthRate*100.0))
        append(" • AF ").append(String.format(Locale.FRANCE,"%.2f %%",r.familyRate*100.0))
        append("\nDu ").append(r.effectiveFrom.format(monthFormatter));r.effectiveTo?.let{append(" au ").append(it.format(monthFormatter))}
        append(" • ").append(r.source)
    }
    private fun field(context:Context,hint:String,type:Int=InputType.TYPE_CLASS_TEXT)=EditText(context).apply{this.hint=hint;inputType=type;isSingleLine=true}
    private fun rowParams(context:Context)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(context,6)}
    private fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density).toInt()
}
