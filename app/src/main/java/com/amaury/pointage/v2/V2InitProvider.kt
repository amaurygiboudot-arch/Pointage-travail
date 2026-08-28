package com.amaury.pointage.v2

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Initialisation V2 indépendante des écrans : aucune donnée utilisateur n'est effacée. */
class V2InitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return true
        if (HoraTrackV2.ENABLED) {
            V2RuntimeStore.bind(app)
            V2TestDataPolicy.ensurePreservation(app)
            V2ProfileStore.bind(app)
            V2MigrationManager.ensureMigrated(app)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
