package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/** Device-wide settings share the restore barrier, without acquiring a salary account identity. */
object RestoreGuardedPreferences {
    private val names = setOf("gps_settings", "appearance_settings", "navigation_state",
        "widget_style", "place_names", "smart_setup", "welcome_preview", "horatrack_v2_legal_sources")

    fun manages(name: String): Boolean = name in names

    fun open(context: Context, name: String, mode: Int = Context.MODE_PRIVATE): SharedPreferences {
        require(manages(name)) { "Unregistered shared preference domain" }
        val app = SalaryAccountScope.rawContext(context)
        return Guarded(app, app.getSharedPreferences(name, mode))
    }

    private class Guarded(val context: Context, val delegate: SharedPreferences) : SharedPreferences {
        private val listeners = mutableMapOf<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
        private fun <T> access(unavailable: () -> T, block: () -> T): T =
            V2RestoreTransaction.withSharedAccess(context, unavailable, block)
        override fun getAll(): MutableMap<String, *> = access({ mutableMapOf<String, Any>() }) {
            delegate.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }.toMutableMap()
        }
        override fun contains(key: String?) = access({ false }) { delegate.contains(key) }
        override fun getString(key: String?, defValue: String?) = access({ defValue }) { delegate.getString(key, defValue) }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = access({ defValues?.toMutableSet() }) { delegate.getStringSet(key, defValues)?.toMutableSet() }
        override fun getInt(key: String?, defValue: Int) = access({ defValue }) { delegate.getInt(key, defValue) }
        override fun getLong(key: String?, defValue: Long) = access({ defValue }) { delegate.getLong(key, defValue) }
        override fun getFloat(key: String?, defValue: Float) = access({ defValue }) { delegate.getFloat(key, defValue) }
        override fun getBoolean(key: String?, defValue: Boolean) = access({ defValue }) { delegate.getBoolean(key, defValue) }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            synchronized(listeners) {
                if (listeners.containsKey(listener)) return
                val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    // SharedPreferences may notify synchronously from the publishing commit.
                    // Deliver after publication, never expose an intermediate restore to observers.
                    Handler(Looper.getMainLooper()).post {
                        val stillRegistered = synchronized(listeners) { listeners.containsKey(listener) }
                        if (stillRegistered && V2RestoreTransaction.sharedAvailable(context))
                            listener.onSharedPreferenceChanged(this, key)
                    }
                }
                listeners[listener] = wrapper
                delegate.registerOnSharedPreferenceChangeListener(wrapper)
            }
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            synchronized(listeners) { listeners.remove(listener)?.let(delegate::unregisterOnSharedPreferenceChangeListener) }
        }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val editor = delegate.edit()
            override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { editor.putStringSet(key, values?.toMutableSet()) }
            override fun putInt(key: String?, value: Int) = apply { editor.putInt(key, value) }
            override fun putLong(key: String?, value: Long) = apply { editor.putLong(key, value) }
            override fun putFloat(key: String?, value: Float) = apply { editor.putFloat(key, value) }
            override fun putBoolean(key: String?, value: Boolean) = apply { editor.putBoolean(key, value) }
            override fun remove(key: String?) = apply { editor.remove(key) }
            override fun clear() = apply { editor.clear() }
            override fun commit(): Boolean = access({ false }) { editor.commit() }
            override fun apply() { commit() }
        }
    }
}
