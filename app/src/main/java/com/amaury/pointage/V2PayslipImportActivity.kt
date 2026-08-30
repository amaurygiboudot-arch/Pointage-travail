package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.amaury.pointage.v2.V2PayslipStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class V2PayslipImportActivity : Activity() {
    companion object { private const val REQUEST_FILE = 9601; const val EXTRA_COMPANY_ID="company_id"; const val EXTRA_COMPANY_NAME="company_name" }
    private val companyId by lazy { intent.getStringExtra(EXTRA_COMPANY_ID).orEmpty() }
    private val companyName by lazy { intent.getStringExtra(EXTRA_COMPANY_NAME).orEmpty() }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*"; putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/pdf","image/jpeg","image/png","image/webp")) },REQUEST_FILE) }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST_FILE)return;if(resultCode!=RESULT_OK){finish();return};val uri=data?.data?:run{finish();return};runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};askConfirmedValues(uri,contentResolver.getType(uri))}
    private fun askConfirmedValues(uri:Uri,mime:String?){
        val month=Calendar.getInstance(Locale.FRANCE).apply{set(Calendar.DAY_OF_MONTH,1)}
        val monthButton=android.widget.Button(this).apply{text=SimpleDateFormat("MMMM yyyy",Locale.FRANCE).format(month.time).replaceFirstChar{it.uppercase()};isAllCaps=false;setBackgroundResource(R.drawable.hp_panel)}
        val gross=EditText(this).apply{hint="Brut du bulletin (€)";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL}
        val net=EditText(this).apply{hint="Net du bulletin (€) — facultatif";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;val d=resources.displayMetrics.density;setPadding((18*d).toInt(),(8*d).toInt(),(18*d).toInt(),0);addView(monthButton);addView(gross);addView(net)}
        monthButton.setOnClickListener{chooseMonth(month,monthButton)}
        val title=if(companyName.isBlank())"Contrôle du bulletin" else "Bulletin — $companyName"
        val dialog=AlertDialog.Builder(this).setTitle(title).setMessage("Le document original est conservé. Confirme la période et les montants visibles : HoraTrack ne doit pas inventer une extraction incertaine.").setView(box).setPositiveButton("Enregistrer",null).setNegativeButton("Annuler"){_,_->finish()}.setOnCancelListener{finish()}.create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{val grossValue=gross.text.toString().trim().replace(',','.').toDoubleOrNull();val netValue=net.text.toString().trim().replace(',','.').toDoubleOrNull();if(grossValue==null||grossValue<0.0){gross.error="Montant brut requis";return@setOnClickListener};V2PayslipStore.add(this,month.get(Calendar.YEAR),month.get(Calendar.MONTH),uri,mime,grossValue,netValue,true,companyId);Toast.makeText(this,"Bulletin importé • comparaison disponible",Toast.LENGTH_LONG).show();dialog.setOnCancelListener(null);dialog.dismiss();finish()}}
        dialog.show()
    }
    private fun chooseMonth(selected:Calendar,button:android.widget.Button){val labels=ArrayList<String>();val months=ArrayList<Calendar>();val format=SimpleDateFormat("MMMM yyyy",Locale.FRANCE);val cursor=Calendar.getInstance(Locale.FRANCE).apply{set(Calendar.DAY_OF_MONTH,1)};repeat(36){months+=cursor.clone() as Calendar;labels+=format.format(cursor.time).replaceFirstChar{it.uppercase()};cursor.add(Calendar.MONTH,-1)};AlertDialog.Builder(this).setTitle("Période du bulletin").setItems(labels.toTypedArray()){_,which->selected.timeInMillis=months[which].timeInMillis;button.text=labels[which]}.setNegativeButton("Annuler",null).show()}
}
