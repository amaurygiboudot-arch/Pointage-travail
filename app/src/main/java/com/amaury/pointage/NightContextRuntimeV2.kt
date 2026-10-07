package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.Collections
import java.util.WeakHashMap

/** One foreground-only minute check; no wake lock, worker, receiver or alarm. */
object NightContextRuntimeV2 : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val resumed = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var app: Application? = null
    private var last: String? = null
    fun effectiveContext(context: Context): String {
        val clock = Calendar.getInstance()
        return PersonalizationStoreV2.read(context).effectiveContextAt(clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE))
    }
    private val update = object : Runnable {
        override fun run() {
            if (resumed.isEmpty()) return
            val application = app ?: return
            val current = "${PersonalizationStoreV2.accountScope()}:${effectiveContext(application)}"
            if (current != last) { last = current; PersonalizationRuntimeV2.refresh() }
            main.postDelayed(this, 60_000L - ((System.currentTimeMillis() % 60_000L + 60_000L) % 60_000L))
        }
    }
    fun install(application: Application) {
        if (app != null) return
        app = application
        application.registerActivityLifecycleCallbacks(this)
    }
    override fun onActivityResumed(activity: Activity) {
        resumed.add(activity); main.removeCallbacks(update); main.post(update)
    }
    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
        if (resumed.isEmpty()) { main.removeCallbacks(update); last = null }
    }
    override fun onActivityDestroyed(activity: Activity) { onActivityPaused(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
