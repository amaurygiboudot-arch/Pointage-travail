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
import com.amaury.pointage.v2.CompanyEmployerReductionStoreV2
import com.amaury.pointage.v2.engine.EmployerReductionAdjustmentV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Saisie mensuelle d'un total de réductions/exonérations patronales confirmé. */
object CompanyEmployerReductionDialogV2 {
    private val monthFormatter=DateTimeFormatter.ofPattern("MM/uuuu",Locale.FRANCE)

    fun show(context:Context,companyId:String){
        if(companyId.isBlank())return
        val box=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(context,16),dp(context,8),dp(context,16),dp(context,8))}
        box.addView(TextView(context).apply{
            text="Saisis le total mensuel réellement confirmé des réductions/exonérations patronales (RGDU, Lodeom, etc.) depuis la DSN, le bulletin ou un calcul employeur validé. Saisis 0 € pour confirmer qu'aucune réduction ne s'applique ce mois."
            textSize=13f;setPadding(0,0,0,dp(context,8))
        })
        val selectedMonth=selectedPayrollMonth(context)
        val resolved=CompanyEmployerReductionStoreV2.resolve(context,companyId,selectedMonth)
        box.addView(TextView(context).apply{
            text=buildString{
                append("RÉSULTAT ").append(selectedMonth.format(monthFormatter)).append("\n")
                if(resolved.reliable&&resolved.amount!=null){
                    append(String.format(Locale.FRANCE,"%.2f €",resolved.amount))
                    resolved.source?.takeIf{it.isNotBlank()}?.let{append(" — ").append(it)}
                    resolved.note?.takeIf{it.isNotBlank()}?.let{append("\n").append(it)}
                }else{
                    append("À confirmer")
                    if(resolved.warnings.isNotEmpty())append("\n• ").append(resolved.warnings.joinToString("\n• "))
                }
            }
            textSize=13f
            setPadding(0,dp(context,4),0,dp(context,8))
        })
        var listDialog:AlertDialog?=null
        box.addView(Button(context).apply{
            isAllCaps=false
            text="CONTEXTE RGDU AUTOMATIQUE"
            setOnClickListener{
                listDialog?.dismiss()
                CompanyEmployerGeneralReductionContextDialogV2.show(context,companyId)
            }
        },rowParams(context))
        val records=CompanyEmployerReductionStoreV2.list(context,companyId).sortedByDescending{it.month}
        if(records.isEmpty()) box.addView(TextView(context).apply{text="Aucun total manuel confirmé.";textSize=13f})
        else records.forEach{r->
            box.addView(Button(context).apply{
                isAllCaps=false;gravity=Gravity.START or Gravity.CENTER_VERTICAL;text=recordLabel(r)
                setOnClickListener{listDialog?.dismiss();showEditor(context,companyId,r)}
            },rowParams(context))
        }
        box.addView(Button(context).apply{isAllCaps=false;text="AJOUTER / CONFIRMER UN TOTAL MANUEL";setOnClickListener{listDialog?.dismiss();showEditor(context,companyId,null)}},rowParams(context))
        listDialog=AlertDialog.Builder(context).setTitle("Réductions / exonérations employeur").setView(box).setNegativeButton("FERMER",null).create()
        listDialog.show()
    }

    private fun showEditor(context:Context,companyId:String,existing:EmployerReductionAdjustmentV2.Record?){
        val box=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(context,20),dp(context,8),dp(context,20),0)}
        val month=field(context,"Mois — MM/AAAA")
        val amount=field(context,"Total réduction (€) — 0 si aucune",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val source=field(context,"Source — ex. DSN / bulletin")
        val note=field(context,"Note — ex. RGDU (facultatif)")
        listOf(month,amount,source,note).forEach{box.addView(it,rowParams(context))}
        existing?.let{
            month.setText(it.month.format(monthFormatter));amount.setText(String.format(Locale.FRANCE,"%.2f",it.totalReductionAmount));source.setText(it.source);note.setText(it.note)
        }
        val builder=AlertDialog.Builder(context).setTitle(if(existing==null)"Confirmer un mois" else "Modifier le mois").setView(box).setPositiveButton("ENREGISTRER",null).setNegativeButton("ANNULER",null)
        if(existing!=null)builder.setNeutralButton("SUPPRIMER",null)
        val dialog=builder.create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val parsedMonth=runCatching{YearMonth.parse(month.text.toString().trim(),monthFormatter)}.getOrNull()
                if(parsedMonth==null){month.error="Mois invalide";return@setOnClickListener}
                val parsedAmount=amount.text.toString().trim().replace(',','.').toDoubleOrNull()
                if(parsedAmount==null||!parsedAmount.isFinite()||parsedAmount<0.0){amount.error="Montant invalide";return@setOnClickListener}
                val rawSource=source.text.toString().trim();if(rawSource.isBlank()){source.error="Indique la source";return@setOnClickListener}
                val record=EmployerReductionAdjustmentV2.Record(existing?.id?:"reduction_${UUID.randomUUID()}",parsedMonth,parsedAmount,rawSource,note.text.toString().trim())
                if(!CompanyEmployerReductionStoreV2.save(context,companyId,record)){Toast.makeText(context,"Échec de l'enregistrement",Toast.LENGTH_LONG).show();return@setOnClickListener}
                dialog.dismiss();Toast.makeText(context,"Réduction employeur enregistrée",Toast.LENGTH_SHORT).show();show(context,companyId)
            }
            if(existing!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{
                if(CompanyEmployerReductionStoreV2.remove(context,companyId,existing.id)){dialog.dismiss();show(context,companyId)}
                else Toast.makeText(context,"Échec de la suppression",Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun recordLabel(r:EmployerReductionAdjustmentV2.Record)=buildString{
        append(r.month.format(monthFormatter)).append(" — ").append(String.format(Locale.FRANCE,"%.2f €",r.totalReductionAmount))
        append("\n").append(r.source);if(r.note.isNotBlank())append(" • ").append(r.note)
    }
    private fun selectedPayrollMonth(context:Context):YearMonth{
        val ms=context.getSharedPreferences("navigation_state",Context.MODE_PRIVATE).getLong("report_month_ms",-1L)
        val calendar=Calendar.getInstance(Locale.FRANCE)
        if(ms>0L)calendar.timeInMillis=ms
        return YearMonth.of(calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH)+1)
    }
    private fun field(context:Context,hint:String,type:Int=InputType.TYPE_CLASS_TEXT)=EditText(context).apply{this.hint=hint;inputType=type;isSingleLine=true}
    private fun rowParams(context:Context)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(context,6)}
    private fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density).toInt()
}
