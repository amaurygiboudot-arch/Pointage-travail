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
import com.amaury.pointage.v2.HoraTrackV2

/** Branche le tutoriel et les composants V2 sur l'interface normale. */
class FirstStepsInitProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    companion object {
        private const val LIGHT_PREFS = "light_tracking_settings"
        private const val LIGHT_ENABLED = "light_tracking_enabled"
        private const val LIGHT_BUTTON_TAG = "light_tracking_toggle"
    }

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
            V2ManualEntryInstaller.install(activity)
            installEmployerSelector(activity)
            installOwnerShortcut(activity)
            installGpsZoneTypeSelector(activity)
            installBackupRestore(activity)
            installSecuritySettings(activity)
            installLightTracking(activity)
            installV2PdfExport(activity)
            installReplayButton(activity)
            removeLegacyGpsTestButton(activity)
            removeVisibleDeveloperButton(activity)
            FirstStepsTutorial.showIfNeeded(activity)
            WorkplaceProposalLimiter.showIfAllowed(activity)
            CompanyNameUiBinder.bind(activity)
            PrimaryButtonIsolation.install(activity)
            V2ManualEntryInstaller.install(activity)
            installEmployerSelector(activity)
        }
    }

    private fun installLightTracking(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        val prefs = activity.getSharedPreferences(LIGHT_PREFS, Activity.MODE_PRIVATE)

        fun isEnabled() = prefs.getBoolean(LIGHT_ENABLED, true)
        fun updateButton(button: Button) {
            button.text = if (isEnabled()) "☀ SUIVI DE LUMIÈRE : ACTIVÉ" else "☀ SUIVI DE LUMIÈRE : DÉSACTIVÉ"
        }
        fun applyState() {
            if (isEnabled()) {
                LightDirectionController.attach(activity) { }
            } else {
                LightDirectionController.detach(activity)
            }
        }

        var button = panel.findViewWithTag<Button>(LIGHT_BUTTON_TAG)
        if (button == null) {
            button = Button(activity).apply {
                tag = LIGHT_BUTTON_TAG
                isAllCaps = false
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener {
                    val enabled = !isEnabled()
                    prefs.edit().putBoolean(LIGHT_ENABLED, enabled).apply()
                    updateButton(this)
                    applyState()
                }
            }
            updateButton(button)
            panel.addView(button, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            updateButton(button)
        }
        applyState()
    }

    private fun installEmployerSelector(activity: MainActivity) {
        if (!HoraTrackV2.ENABLED) return
        val panel = activity.findViewById<LinearLayout>(R.id.pointageButtons) ?: return
        val existing = panel.findViewWithTag<V2EmployerSelectorView>(V2EmployerSelectorView.TAG)
        if (existing == null) {
            panel.addView(
                V2EmployerSelectorView(activity),
                0,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        } else existing.refresh()
    }

    private fun installOwnerShortcut(activity: MainActivity) {
        val settingsTab = activity.findViewById<TextView>(R.id.tabSettings) ?: return
        settingsTab.setOnLongClickListener {
            if (!AdminDiagnosticsGate.isEnabled(activity)) activity.startActivity(Intent(activity, OwnerEnrollmentActivity::class.java))
            else authenticateOwner(activity)
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
                super.onAuthenticationSucceeded(result); DeveloperToolsDialog.show(activity)
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed(); Toast.makeText(activity, "Empreinte non reconnue", Toast.LENGTH_SHORT).show()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                    Toast.makeText(activity, errString ?: "Authentification impossible", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun installGpsZoneTypeSelector(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        if (panel.findViewWithTag<View>(GpsZoneTypeView.TAG) == null) {
            panel.addView(GpsZoneTypeView(activity), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun installBackupRestore(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        if (panel.findViewWithTag<View>(V2BackupRestoreView.TAG) == null) {
            panel.addView(V2BackupRestoreView(activity), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun installSecuritySettings(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        val existing = panel.findViewWithTag<V2SecuritySettingsView>(V2SecuritySettingsView.TAG)
        if (existing == null) {
            panel.addView(V2SecuritySettingsView(activity), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else existing.refresh()
    }

    private fun installV2PdfExport(activity: MainActivity) {
        if (!HoraTrackV2.ENABLED) return
        activity.findViewById<Button>(R.id.generateMonthlyPdfButton)?.setOnClickListener {
            activity.startActivity(Intent(activity, V2MonthlyPdfActivity::class.java))
        }
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

    private fun removeLegacyGpsTestButton(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        panel.findViewWithTag<View>("gps_workplace_test")?.let { panel.removeView(it) }
    }

    private fun removeVisibleDeveloperButton(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        panel.findViewWithTag<View>("developer_tools")?.let { panel.removeView(it) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity) LightDirectionController.detach(activity)
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
