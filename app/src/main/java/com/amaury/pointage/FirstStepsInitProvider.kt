package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Branche le tutoriel et initialise l'assistant intelligent dès la première installation. */
class FirstStepsInitProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        SmartSetupManager.init(app)
        CompanyNameUiBinder.init(app)

        CustomBackgroundStore.protectPreference(app)
        if (CustomBackgroundStore.isEnabled(app)) {
            CustomBackgroundStore.resolve(app)
            CustomBackgroundStore.saveBackup(app)
        }

        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        CompanyNameUiBinder.bind(activity)
        PrimaryButtonIsolation.install(activity)
        if (CustomBackgroundStore.isEnabled(activity)) {
            CustomBackgroundStore.resolve(activity)
            CustomBackgroundStore.saveBackup(activity)
        }
        activity.window.decorView.post {
            PrimaryButtonIsolation.install(activity)
            installOwnerShortcut(activity)
            installReplayButton(activity)
            removeLegacyGpsTestButton(activity)
            removeVisibleDeveloperButton(activity)
            FirstStepsTutorial.showIfNeeded(activity)
            WorkplaceProposalLimiter.showIfAllowed(activity)
            CompanyNameUiBinder.bind(activity)
            PrimaryButtonIsolation.install(activity)
        }
    }

    /** Entrée secrète : appui long sur Paramètres, puis authentification biométrique. */
    private fun installOwnerShortcut(activity: MainActivity) {
        val settingsTab = activity.findViewById<TextView>(R.id.tabSettings) ?: return
        settingsTab.setOnLongClickListener {
            if (!AdminDiagnosticsGate.isEnabled(activity)) {
                activity.startActivity(Intent(activity, OwnerEnrollmentActivity::class.java))
            } else {
                authenticateOwner(activity)
            }
            true
        }
    }

    private fun authenticateOwner(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(activity, "Authentification biométrique requise sur Android 9 ou plus.", Toast.LENGTH_LONG).show()
            return
        }

        val cancellationSignal = CancellationSignal()
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Développeur")
            .setSubtitle("Confirme ton empreinte pour ouvrir la zone privée")
            .setDescription("Accès réservé au propriétaire de HoraTrack")
            .setNegativeButton("Annuler", activity.mainExecutor) { _, _ -> cancellationSignal.cancel() }
            .build()

        prompt.authenticate(cancellationSignal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                super.onAuthenticationSucceeded(result)
                DeveloperToolsDialog.show(activity)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(activity, "Empreinte non reconnue", Toast.LENGTH_SHORT).show()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                // Le bouton négatif peut remonter un code dépendant de la version Android.
                // On ignore uniquement les annulations standard et on affiche les vraies erreurs.
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                    errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                ) {
                    Toast.makeText(activity, errString ?: "Authentification impossible", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun installReplayButton(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        if (panel.findViewWithTag<View>("first_steps_replay") != null) return
        val button = Button(activity).apply {
            tag = "first_steps_replay"
            text = "🎓 REVOIR LE TUTORIEL PREMIERS PAS"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { FirstStepsTutorial.restart(activity) }
            setOnLongClickListener(null)
        }
        panel.addView(button, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Le test GPS est disponible uniquement dans le menu Développeur. */
    private fun removeLegacyGpsTestButton(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        panel.findViewWithTag<View>("gps_workplace_test")?.let { panel.removeView(it) }
    }

    /** Supprime toute ancienne version visible du bouton Développeur. */
    private fun removeVisibleDeveloperButton(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        panel.findViewWithTag<View>("developer_tools")?.let { panel.removeView(it) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
