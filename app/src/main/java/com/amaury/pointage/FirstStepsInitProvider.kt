package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/** Branche le tutoriel et initialise l'assistant intelligent dès la première installation. */
class FirstStepsInitProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        SmartSetupManager.init(app)
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        activity.window.decorView.post {
            installReplayButton(activity)
            FirstStepsTutorial.showIfNeeded(activity)
            WorkplaceProposalLimiter.showIfAllowed(activity)
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
