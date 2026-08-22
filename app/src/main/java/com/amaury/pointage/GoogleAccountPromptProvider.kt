package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth

/**
 * Ouvre automatiquement l'ecran Compte Google lorsqu'aucun compte Firebase
 * n'est connecte. Une seule proposition est affichee par lancement de l'app
 * afin d'eviter les boucles si l'utilisateur ferme la fenetre.
 */
class GoogleAccountPromptProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var promptedThisProcess = false

            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity) return
                if (promptedThisProcess) return
                if (FirebaseAuth.getInstance().currentUser != null) return

                promptedThisProcess = true
                activity.window.decorView.post {
                    if (!activity.isFinishing && !activity.isDestroyed && FirebaseAuth.getInstance().currentUser == null) {
                        activity.startActivity(Intent(activity, FirebaseAccountActivity::class.java))
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
