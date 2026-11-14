package com.amaury.pointage.v2

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** Captured account/session ownership for salary reads, edits, forms and asynchronous operations. */
object SalaryAccountScope {
    const val AUTH_WARNING = "Compte Salaire V2 indisponible ou modifié : rouvrir cette page après vérification du compte."
    const val LEGACY_WARNING = "Des anciennes données salariales restent conservées sur cet appareil sans propriétaire confirmé. Leur reprise nécessite une confirmation explicite."
    data class Lease(val scope: String?, val generation: Long, val processId: String)

    interface LeaseOwner { val salaryAccountLease: Lease? }

    private val operationOwner = ThreadLocal<Lease?>()

    private val identityLock = Any()
    private val processId = UUID.randomUUID().toString()
    private var generation = 0L
    private var observedScope: String? = null
    private var observed = false
    private var observedAuth: FirebaseAuth? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val scope = runCatching { V2RuntimeAccountScope.authenticationScope(true, auth.currentUser?.uid) }.getOrNull()
        observe(scope)
    }

    private fun observe(scope: String?): Lease {
        var changed = false
        val result = synchronized(identityLock) {
            if (observed && scope != observedScope) { generation++; changed = true }
            observed = true
            observedScope = scope
            Lease(scope, generation, processId)
        }
        if (changed) notifyListeners()
        return result
    }

    private fun identity(): String? = runCatching {
        val auth = FirebaseAuth.getInstance()
        val scope = V2RuntimeAccountScope.authenticationScope(true, auth.currentUser?.uid)
        var previous: FirebaseAuth? = null
        val attach = synchronized(identityLock) {
            if (observedAuth === auth) false else {
                previous = observedAuth
                observedAuth = auth
                if (previous != null) generation++
                true
            }
        }
        if (attach) {
            if (previous != null) notifyListeners()
            observe(scope)
            previous?.let { runCatching { it.removeAuthStateListener(authListener) } }
            // Firebase may deliver its initial callback synchronously. Never hold identityLock here.
            auth.addAuthStateListener(authListener)
        }
        scope
    }.getOrNull()

    fun capture(): Lease = observe(identity())

    fun invalidateSession() {
        synchronized(identityLock) { generation++ }
        notifyListeners()
    }

    private fun notifyListeners() {
        // Advance ownership synchronously, deliver UI callbacks outside all persistence locks.
        Handler(Looper.getMainLooper()).post { listeners.forEach { runCatching(it) } }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun isCurrent(lease: Lease): Boolean = lease.scope != null && lease == capture()
    fun lease(context: Context): Lease {
        var current = context
        while (true) {
            if (current is LeaseOwner) return current.salaryAccountLease ?: Lease(null, -1, "missing")
            if (current is BoundContext) return current.owner
            if (current !is ContextWrapper) break
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return operationOwner.get() ?: capture()
    }

    fun <T> withLease(lease: Lease, block: () -> T): T {
        val previous = operationOwner.get()
        operationOwner.set(lease)
        try { return block() } finally {
            if (previous == null) operationOwner.remove() else operationOwner.set(previous)
        }
    }

    fun bindContext(context: Context, lease: Lease = lease(context)): Context =
        if (context is BoundContext && context.owner == lease) context else BoundContext(context, lease)

    internal fun rawContext(context: Context): Context {
        if (context is BoundContext) return rawContext(context.baseContext)
        val app = context.applicationContext
        return if (app is BoundContext) rawContext(app.baseContext) else app
    }

    fun available(context: Context): Boolean {
        val owner = lease(context)
        val scope = owner.scope ?: return false
        return isCurrent(owner) && V2RestoreTransaction.available(rawContext(context), scope) && isCurrent(owner)
    }

    private class BoundContext(base: Context, val owner: Lease) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    fun preferences(context: Context, logicalName: String): SharedPreferences {
        require(SalaryPreferenceRegistry.isPersonal(logicalName)) { "Unknown salary preference domain" }
        val owner = lease(context)
        val physicalName = SalaryPreferenceRegistry.fileName(logicalName, owner.scope)
        val app = rawContext(context)
        // The unavailable delegate is never readable/writable through the guard.
        val delegate = app.getSharedPreferences(physicalName ?: "agkgmg_salary_auth_unavailable", Context.MODE_PRIVATE)
        return GuardedPreferences(app, owner, delegate)
    }

    private class GuardedPreferences(
        val context: Context, val owner: Lease, val delegate: SharedPreferences
    ) : SharedPreferences {
        private val registered = mutableMapOf<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
        private fun <T> access(unavailable: () -> T, block: () -> T): T {
            val scope = owner.scope ?: return unavailable()
            if (!isCurrent(owner)) return unavailable()
            return V2RestoreTransaction.withAccess(context, scope, unavailable) {
                if (!isCurrent(owner)) unavailable() else {
                    val result = block()
                    if (isCurrent(owner)) result else unavailable()
                }
            }
        }
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
            synchronized(registered) {
                if (registered.containsKey(listener)) return
                val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (isCurrent(owner)) listener.onSharedPreferenceChanged(this, key)
                }
                registered[listener] = wrapper
                delegate.registerOnSharedPreferenceChangeListener(wrapper)
            }
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            synchronized(registered) { registered.remove(listener)?.let(delegate::unregisterOnSharedPreferenceChangeListener) }
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
            override fun commit(): Boolean = access({ false }) { editor.commit() && isCurrent(owner) }
            override fun apply() { commit() }
        }
    }
}
