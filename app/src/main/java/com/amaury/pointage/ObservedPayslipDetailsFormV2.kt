package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import com.amaury.pointage.v2.ObservedPayslipDetailsV2 as Details
import com.amaury.pointage.v2.SalaryAccountScope

/** Editable rows remain in the form on any invalid or stale save. */
object ObservedPayslipDetailsFormV2 {
    fun show(host: Context, recordId: String, onSaved: () -> Unit) {
        val context=SalaryAccountScope.bindContext(host)
        val initial=Details.read(context,recordId);val record=initial.record ?: return
        fun message(value:String)=Toast.makeText(context,value,Toast.LENGTH_LONG).show()
        if(!initial.reliable){message("Transcription indisponible : données conservées, vérification nécessaire.");return}
        val rows=initial.document?.rows?.toMutableList() ?: mutableListOf()
        val source=EditText(context).apply { hint="Source du bulletin (500 caractères maximum)";setText(initial.document?.source.orEmpty()) }
        val period=EditText(context).apply { hint="Mois inscrit sur le bulletin — MM/AAAA";inputType=android.text.InputType.TYPE_CLASS_DATETIME }
        val consent=CheckBox(context).apply { text="Je confirme cette transcription du bulletin de ${Details.period(record)}, son entreprise et sa source. Elle ne remplace aucun calcul AGKGMG." }
        val content=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val rowBox=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val labels=listOf("Libellé exact", "Base salarié (facultative)", "Taux salarié (facultatif)", "Montant salarié (facultatif)", "Base employeur (facultative)", "Taux employeur (facultatif)", "Montant employeur (facultatif)")
        lateinit var refresh: () -> Unit
        fun edit(index:Int?) {
            if(index==null&&rows.size>=Details.MAX_ROWS){message("150 lignes maximum par bulletin.");return}
            val old=index?.let { rows[it] }
            val values=listOf(old?.label.orEmpty())+(old?.cells() ?: List(6){null}).map { it.orEmpty() }
            val fields=labels.zip(values).mapIndexed { i,(label,value) -> EditText(context).apply {
                hint=label;setText(value);if(i>0)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            } }
            val box=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;fields.forEach { addView(TextView(context).apply { text=it.hint });addView(it) } }
            val dialog=SalaryAccountUi.dialog(context).setTitle(if(index==null)"Ajouter une ligne du bulletin" else "Modifier la ligne")
                .setView(ScrollView(context).apply { addView(box) }).setNegativeButton("ANNULER",null).setPositiveButton("GARDER LA LIGNE",null)
                .apply { if(index!=null)setNeutralButton("RETIRER LA LIGNE",null) }.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val label=fields[0].text.toString().trim();val raw=fields.drop(1).map { it.text.toString().trim() }
                    val cells=raw.mapIndexed { i,s -> if(s.isBlank())null else Details.decimal(s,if(i==2||i==5)2 else 4) }
                    if(label.isBlank()||label.length>160||label.any { it.isISOControl() }||raw.indices.any { raw[it].isNotBlank()&&cells[it]==null }||cells.all { it==null }) { message("Libellé requis ; montants au centime, bases et taux jusqu’à 4 décimales. Au moins une cellule renseignée.");return@setOnClickListener }
                    val row=Details.Row(label,cells[0],cells[1],cells[2],cells[4],cells[5],cells[3]);if(index==null)rows.add(row) else rows[index]=row
                    consent.isChecked=false;refresh();dialog.dismiss()
                }
                if(index!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { rows.removeAt(index);consent.isChecked=false;refresh();dialog.dismiss() }
            };dialog.show()
        }
        refresh={rowBox.removeAllViews();rows.forEachIndexed { i,row -> rowBox.addView(Button(context).apply { text="${i+1}. ${row.label}\n"+row.cells().joinToString(" | "){it?:"—"};setOnClickListener { edit(i) } }) }}
        content.addView(TextView(context).apply { text="BULLETIN CONSTATÉ — ${Details.period(record)}\nRecopie les lignes de ton document. Les totaux importés restent distincts : aucune addition ni estimation. Recopie les signes tels qu’imprimés ; les retenues ne sont pas automatiquement soustraites. Les cellules laissées vides sont inconnues. Le document original est conservé." })
        if(initial.needsReconfirmation)content.addView(TextView(context).apply { text="Le bulletin importé a changé : vérifie chaque ligne avant de confirmer de nouveau." })
        content.addView(TextView(context).apply { text="Mois du document" });content.addView(period);content.addView(source);content.addView(rowBox)
        content.addView(Button(context).apply { text="AJOUTER UNE LIGNE";setOnClickListener { edit(null) } });content.addView(consent);refresh()
        val change=object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){consent.isChecked=false};override fun afterTextChanged(s:Editable?){} }
        source.addTextChangedListener(change);period.addTextChangedListener(change)
        val dialog=SalaryAccountUi.dialog(context).setTitle("Transcrire le bulletin réel")
            .setView(ScrollView(context).apply { addView(content) }).setNegativeButton("ANNULER",null).setPositiveButton("ENREGISTRER",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(SalaryPayslipPeriodSelectionV2.parse(period.text.toString())?.toString()!=Details.period(record)){period.error="Le mois du document doit correspondre à ${Details.period(record)}. Les lignes sont conservées.";return@setOnClickListener}
            val doc=Details.Document(Details.period(record),source.text.toString().trim(),System.currentTimeMillis(),rows.toList())
            if(!consent.isChecked||!Details.valid(doc,record)){message("Confirme le mois, la source et au moins une ligne valide.");return@setOnClickListener}
            if(!Details.save(context,initial,doc,true)){consent.isChecked=false;message("Bulletin, compte ou entreprise modifié : saisie conservée, aucun remplacement effectué.");return@setOnClickListener}
            dialog.dismiss();onSaved()
        } };dialog.show()
    }
}
