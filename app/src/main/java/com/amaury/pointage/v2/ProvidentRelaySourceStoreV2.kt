package com.amaury.pointage.v2

import android.content.Context

/**
 * Liens locaux vers les documents sources des contrôles prévoyance.
 * Ce fichier n'est pas inclus dans la sauvegarde V2 : un content:// URI n'est valable
 * que sur l'appareil qui a accordé la permission persistante.
 */
object ProvidentRelaySourceStoreV2 {
    private const val PREFS = "horatrack_local_provident_documents"

    data class Source(val uri:String,val mime:String?,val displayName:String)

    fun get(context:Context,absenceId:String):Source? {
        val p=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val uri=p.getString("$absenceId:uri",null)?.takeIf{it.isNotBlank()}?:return null
        return Source(uri,p.getString("$absenceId:mime",null),p.getString("$absenceId:name",null).orEmpty().ifBlank{"Décompte prévoyance"})
    }

    fun put(context:Context,absenceId:String,source:Source){
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString("$absenceId:uri",source.uri)
            .putString("$absenceId:mime",source.mime)
            .putString("$absenceId:name",source.displayName)
            .apply()
    }

    fun remove(context:Context,absenceId:String){
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .remove("$absenceId:uri").remove("$absenceId:mime").remove("$absenceId:name").apply()
    }
}
