package com.amaury.pointage.v2.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DiagnosticV2(val id:String,val type:String,val function:String,val atMs:Long,val technicalMessage:String,val context:String?=null)

object V2DiagnosticReporter {
    fun from(function:String,error:Throwable,context:String?=null)=DiagnosticV2(
        id=UUID.randomUUID().toString(),type=error::class.java.simpleName,function=function,atMs=System.currentTimeMillis(),technicalMessage=error.message ?: "Erreur interne sans message",context=context
    )

    fun show(activity:Activity, diagnostic:DiagnosticV2) {
        val date=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.FRANCE).format(Date(diagnostic.atMs))
        val text=buildString {
            appendLine("HoraTrack — diagnostic développeur")
            appendLine("Identifiant : ${diagnostic.id}")
            appendLine("Type : ${diagnostic.type}")
            appendLine("Fonction : ${diagnostic.function}")
            appendLine("Date : $date")
            appendLine("Message : ${diagnostic.technicalMessage}")
            diagnostic.context?.takeIf{it.isNotBlank()}?.let{appendLine("Contexte : $it")}
        }
        AlertDialog.Builder(activity)
            .setTitle("Erreur interne HoraTrack")
            .setMessage(text)
            .setPositiveButton("COPIER LE DIAGNOSTIC") { _,_ ->
                val clip=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Diagnostic HoraTrack",text))
            }
            .setNegativeButton("FERMER",null)
            .show()
    }
}

enum class V2Action { OPEN_HISTORY, OPEN_ANALYTICS, OPEN_SALARY, OPEN_SETTINGS, MANUAL_PAUSE, GENERATE_PDF, IMPORT_PAYSLIP, SAVE_COMPANY, SAVE_CONTRACT, SAVE_GPS, BACKUP_NOW }
class V2ActionDispatcher {
    private val actions=mutableMapOf<V2Action,()->Unit>()
    fun bind(action:V2Action, handler:()->Unit){ actions[action]=handler }
    fun isBound(action:V2Action)=actions.containsKey(action)
    fun requireAllBound():List<V2Action> = V2Action.entries.filterNot(::isBound)
    fun perform(action:V2Action){ requireNotNull(actions[action]){"Bouton/action HoraTrack non branché: $action"}.invoke() }
}
