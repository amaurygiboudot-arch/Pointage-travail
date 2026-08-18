package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.os.Bundle

class PointageApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        ConventionCatalog.initialize(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.window.decorView.post {
            AppearanceManager.apply(activity)
            if (activity is MainActivity) {
                SettingsUiInstaller.install(activity)
                LuxuryUiInstaller.install(activity)
                UpdateChecker.check(activity, silent = true)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) AppearanceManager.apply(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}