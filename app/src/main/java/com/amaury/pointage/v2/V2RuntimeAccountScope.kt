package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import java.security.MessageDigest

/** One account boundary for journal, migration metadata and their backup consumers. */
object V2RuntimeAccountScope {
    const val UNASSIGNED_WARNING = "Un ancien journal est conservé sur cet appareil sans propriétaire confirmé : historique complet et calculs à confirmer ; les nouveaux pointages restent enregistrés séparément."
    const val AUTH_WARNING = "Compte de pointage indisponible ou modifié : réessayer après vérification de la connexion."
    @PublishedApi internal val pinnedScope = ThreadLocal<String?>()
    @PublishedApi internal val pinDepth = ThreadLocal<Int>()
    @PublishedApi internal val pinnedLease = ThreadLocal<SalaryAccountScope.Lease?>()
    @Volatile private var accessContext: Context? = null

    fun currentScope(): String? = runCatching {
        authenticationScope(true, FirebaseAuth.getInstance().currentUser?.uid)
    }.getOrNull()

    internal fun authenticationScope(available: Boolean, uid: String?): String? {
        if (!available) return null
        if (uid == null) return "guest"
        return uid.trim().takeIf { it.isNotEmpty() }?.let { "uid:$it" }
    }

    fun fileName(baseName: String, scope: String?): String? {
        if (scope == null || !(scope == "guest" || scope.startsWith("uid:") && scope.length > 4)) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "${baseName}_account_$digest"
    }

    internal inline fun <T> withScope(block: () -> T): T {
        val depth = pinDepth.get() ?: 0
        if (depth == 0) {
            val lease = SalaryAccountScope.capture()
            pinnedLease.set(lease)
            pinnedScope.set(lease.scope)
        }
        pinDepth.set(depth + 1)
        try { return block() } finally {
            if (depth == 0) { pinDepth.remove(); pinnedScope.remove(); pinnedLease.remove() } else pinDepth.set(depth)
        }
    }

    internal fun operationScope(): String? = if ((pinDepth.get() ?: 0) > 0) pinnedScope.get() else currentScope()
    internal fun available(context: Context? = null): Boolean {
        if (context != null) accessContext = SalaryAccountScope.rawContext(context)
        val lease = context?.let(SalaryAccountScope::lease) ?: pinnedLease.get() ?: SalaryAccountScope.capture()
        val scope = lease.scope ?: return false
        if (!SalaryAccountScope.isCurrent(lease) || operationScope() != scope) return false
        if (pinnedLease.get()?.let { !SalaryAccountScope.isCurrent(it) } == true) return false
        return accessContext?.let { V2RestoreTransaction.available(it, scope) } != false
    }

    /** Durable surveillance continuity, independent of per-account journals. */
    @Synchronized
    internal fun monitoringEpoch(context: Context): String? {
        val scope = currentScope()
        val owner = fileName("monitor", scope) ?: "unavailable"
        val prefs = context.applicationContext.getSharedPreferences("departure_monitoring_v2", Context.MODE_PRIVATE)
        val previousOwner = prefs.all["owner"] as? String
        val previousEpoch = prefs.all["epoch"] as? String
        val epoch = if (previousOwner == owner && !previousEpoch.isNullOrBlank()) previousEpoch
            else java.util.UUID.randomUUID().toString()
        // Recommit to reject a RAM-only epoch after a failed write.
        return if (prefs.edit().putString("owner", owner).putString("epoch", epoch).commit() && scope != null)
            epoch else null
    }

    fun hasUnassignedHistory(context: Context): Boolean {
        val app = context.applicationContext
        if (V2LegacyJournalRecovery.assigned(app)) return false
        return app.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE).all.isNotEmpty() ||
            app.getSharedPreferences("pointage", Context.MODE_PRIVATE).contains("data")
    }

    fun preferences(context: Context, baseName: String): SharedPreferences {
        val owner = SalaryAccountScope.lease(context)
        val scope = owner.scope
        val name = fileName(baseName, scope) ?: "${baseName}_auth_unavailable"
        val app = SalaryAccountScope.rawContext(context)
        accessContext = app
        val delegate = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        return GuardedPreferences(delegate,
            valid = { SalaryAccountScope.isCurrent(owner) && scope == operationScope() &&
                pinnedLease.get()?.let(SalaryAccountScope::isCurrent) != false },
            writeAllowed = { V2LegacyJournalRecovery.canWrite(app, scope) }, context = app, scope = scope)
    }

    internal fun guardedPreferences(delegate: SharedPreferences, valid: () -> Boolean): SharedPreferences =
        GuardedPreferences(delegate, valid)

    /** An editor captured for A can never commit after the caller switches to B. */
    private class GuardedPreferences(val delegate: SharedPreferences, val valid: () -> Boolean,
        val writeAllowed: () -> Boolean = { true }, val context: Context? = null, val scope: String? = null) : SharedPreferences by delegate {
        private val listeners = mutableMapOf<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
        private fun <T> access(unavailable: () -> T, block: () -> T): T {
            if (!valid()) return unavailable()
            return if (context == null) block() else {
                val owner = scope ?: return unavailable()
                V2RestoreTransaction.withAccess(context, owner, unavailable) { if (valid()) block() else unavailable() }
            }
        }
        override fun getAll(): MutableMap<String, *> = access({ mutableMapOf<String, Any>() }) { delegate.all }
        override fun contains(key: String?) = access({ false }) { delegate.contains(key) }
        override fun getString(key: String?, defValue: String?) = access({ defValue }) { delegate.getString(key, defValue) }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = access({ defValues }) { delegate.getStringSet(key, defValues)?.toMutableSet() }
        override fun getInt(key: String?, defValue: Int) = access({ defValue }) { delegate.getInt(key, defValue) }
        override fun getLong(key: String?, defValue: Long) = access({ defValue }) { delegate.getLong(key, defValue) }
        override fun getFloat(key: String?, defValue: Float) = access({ defValue }) { delegate.getFloat(key, defValue) }
        override fun getBoolean(key: String?, defValue: Boolean) = access({ defValue }) { delegate.getBoolean(key, defValue) }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            synchronized(listeners) {
                if (listeners.containsKey(listener)) return
                val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    Handler(Looper.getMainLooper()).post {
                        val registered = synchronized(listeners) { listeners.containsKey(listener) }
                        if (registered && access({ false }) { true }) listener.onSharedPreferenceChanged(this, key)
                    }
                }
                listeners[listener] = wrapper
                delegate.registerOnSharedPreferenceChangeListener(wrapper)
            }
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            synchronized(listeners) { listeners.remove(listener)?.let(delegate::unregisterOnSharedPreferenceChangeListener) }
        }
        override fun edit(): SharedPreferences.Editor = GuardedEditor(delegate.edit(), { valid() && writeAllowed() }) { block -> access({ false }, block) }
    }

    private class GuardedEditor(val delegate: SharedPreferences.Editor, val valid: () -> Boolean,
        val access: (() -> Boolean) -> Boolean) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { delegate.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { delegate.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { delegate.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { delegate.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { delegate.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { delegate.putBoolean(key, value) }
        override fun remove(key: String?) = apply { delegate.remove(key) }
        override fun clear() = apply { delegate.clear() }
        override fun commit(): Boolean = access { valid() && delegate.commit() && valid() }
        override fun apply() { commit() }
    }
}
