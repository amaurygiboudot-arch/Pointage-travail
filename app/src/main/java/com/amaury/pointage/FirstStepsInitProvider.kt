package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
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
            installDeveloperButton(activity)
            FirstStepsTutorial.showIfNeeded(activity)
            WorkplaceProposalLimiter.showIfAllowed(activity)
            CompanyNameUiBinder.bind(activity)
            PrimaryButtonIsolation.install(activity)
        }
    }

    /** Activation cachée : appui long sur l'onglet Paramètres. */
    private fun installOwnerShortcut(activity: MainActivity) {
        val settingsTab = activity.findViewById<TextView>(R.id.tabSettings) ?: return
        settingsTab.setOnLongClickListener {
            if (AdminDiagnosticsGate.isEnabled(activity)) {
                Toast.makeText(activity, "Mode Développeur déjà activé", Toast.LENGTH_SHORT).show()
                installDeveloperButton(activity)
            } else {
                activity.startActivity(Intent(activity, OwnerEnrollmentActivity::class.java))
            }
            true
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
            setOnLongClickListener {
                if (!AdminDiagnosticsGate.isEnabled(activity)) {
                    activity.startActivity(Intent(activity, OwnerEnrollmentActivity::class.java))
                    true
                } else false
            }
        }
        panel.addView(button, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Le test GPS est désormais disponible uniquement dans le menu Développeur. */
    private fun removeLegacyGpsTestButton(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        panel.findViewWithTag<View>("gps_workplace_test")?.let { panel.removeView(it) }
    }

    private fun installDeveloperButton(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        if (!AdminDiagnosticsGate.isEnabled(activity)) {
            panel.findViewWithTag<View>("developer_tools")?.let { panel.removeView(it) }
            return
        }
        if (panel.findViewWithTag<View>("developer_tools") != null) return

        val button = Button(activity).apply {
            tag = "developer_tools"
            text = "🛠 DÉVELOPPEUR"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { DeveloperToolsDialog.show(activity) }
        }
        panel.addView(button, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
