package com.amaury.pointage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Point d'initialisation conservé pour compatibilité avec le manifeste.
 *
 * La localisation n'est plus demandée automatiquement au premier lancement.
 * Les permissions sont demandées uniquement lorsque l'utilisateur active ou
 * configure explicitement le pointage GPS depuis l'interface dédiée.
 */
class ForegroundLocationInitProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
