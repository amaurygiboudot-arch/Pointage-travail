package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Locale

/**
 * Point unique pour :
 * - garantir des pauses automatiques exactes ;
 * - réarmer les alarmes après autorisation Android ;
 * - appliquer aux boutons standards les mêmes couches que la saisie manuelle.
 */
object AutomaticPauseRuntime {
    private var exactPromptShown = false
    private var notificationsPromptShown = false
    private var lastExactState: Boolean? = null

    fun onActivityResumed(activity: Activity) {
        if (!hasAutomaticPauseConfigured(activity)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarm = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val exact = alarm.canScheduleExactAlarms()
            if (exact && lastExactState != true) rearmAll(activity)
            lastExactState = exact
            if (!exact) requestExactAlarmPermission(activity)
        } else if (lastExactState != true) {
            rearmAll(activity)
            lastExactState = true
        }

        PauseScheduleManager.applyCurrentWindow(activity)
        applyCompanyPauseWindow(activity)
    }

    fun ensurePermissions(activity: Activity?, notifications: Boolean) {
        if (activity == null) return
        if (notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationsPromptShown
        ) {
            notificationsPromptShown = true
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1301)
        }
        requestExactAlarmPermission(activity)
    }

    private fun requestExactAlarmPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            rearmAll(activity)
            return
        }
        val alarm = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (alarm.canScheduleExactAlarms()) {
            rearmAll(activity)
            lastExactState = true
            return
        }
        if (exactPromptShown) return
        exactPromptShown = true
        Toast.makeText(
            activity,
            "Autorise « Alarmes et rappels » pour déclencher les pauses exactement à l’heure.",
            Toast.LENGTH_LONG
        ).show()
        activity.window.decorView.postDelayed({
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = android.net.Uri.parse("package:${activity.packageName}")
                    }
                )
            }
        }, 500L)
    }

    private fun hasAutomaticPauseConfigured(context: Context): Boolean {
        if (PauseScheduleManager.load(context).enabled) return true
        for (company in 1..2) for (pause in 1..2) {
            if (CompanyBasePauseSettings.pause(context, company, pause) != null) return true
        }
        return false
    }

    private fun rearmAll(context: Context) {
        PauseScheduleManager.schedule(context)
        CompanyPauseAlarmManager.scheduleAll(context)
    }

    /** Filet de sécurité au retour dans l'application si Android a raté un déclenchement. */
    private fun applyCompanyPauseWindow(context: Context) {
        val company = CompanyPauseAlarmManager.activeCompanySlot(context) ?: return
        val now = Calendar.getInstance(Locale.FRANCE)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        var activeOrigin: String? = null

        for (pauseIndex in 1..2) {
            val pause = CompanyBasePauseSettings.pause(context, company, pauseIndex) ?: continue
            val inside = if (pause.endMinute > pause.startMinute) {
                minute in pause.startMinute until pause.endMinute
            } else {
                minute >= pause.startMinute || minute < pause.endMinute
            }
            val origin = CompanyPauseAlarmManager.pauseOrigin(company, pauseIndex)
            if (inside) {
                activeOrigin = origin
                if (PointageStore.hasOpen(context) && !PointageStore.isPaused(context)) {
                    PointageStore.startPause(context, automatic = true, origin = origin)
                }
                break
            }
        }

        if (activeOrigin == null) {
            for (pauseIndex in 1..2) {
                val origin = CompanyPauseAlarmManager.pauseOrigin(company, pauseIndex)
                if (PointageStore.isPausedByOrigin(context, origin)) {
                    PointageStore.resumePause(context, automaticOnly = true, expectedOrigin = origin)
                    break
                }
            }
        }
    }
}

object StandardButtonLayers {
    fun apply(activity: Activity) {
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val theme = AppThemeCatalog.current(activity)
        val dark = AppThemeCatalog.useDarkPalette(activity)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val text = if (dark) theme.darkText else theme.lightText
        applyRecursive(root, panel, text)
    }

    fun applyToRoot(context: Context, root: View) {
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val text = if (dark) theme.darkText else theme.lightText
        applyRecursive(root, panel, text)
    }

    private fun applyRecursive(view: View, panel: Int, text: Int) {
        if (view is Button && !isProtectedButton(view)) {
            view.setBackgroundResource(R.drawable.hp_panel)
            view.backgroundTintList = ColorStateList.valueOf(panel)
            view.setTextColor(text)
            view.isAllCaps = false
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyRecursive(view.getChildAt(i), panel, text)
        }
    }

    private fun isProtectedButton(button: Button): Boolean {
        if (button is RedDiamondFinalButton || button is LightReactiveJewelButton) return true
        val idName = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        return idName == "entryButton" || idName == "pauseButton" || idName == "exitButton"
    }
}

/** Initialisation légère, sans deuxième moteur de style ni deuxième planificateur. */
class AutomaticPauseUiInitProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        var current = WeakReference<Activity>(null)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                current = WeakReference(activity)
                StandardButtonLayers.apply(activity)
                AutomaticPauseRuntime.onActivityResumed(activity)
                activity.window.decorView.post { StandardButtonLayers.apply(activity) }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (current.get() === activity) current.clear()
            }
        })

        app.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener { _, key ->
                if (key == "mode" || key == AppThemeCatalog.KEY_THEME || key == "app_bg" || key == "custom_bg") {
                    current.get()?.let { activity ->
                        activity.window.decorView.post { StandardButtonLayers.apply(activity) }
                    }
                }
            }
        return true
    }

    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: android.net.Uri) = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
