package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import java.security.MessageDigest

/** One account boundary for journal, migration metadata and their backup consumers. */
object V2RuntimeAccountScope {
    const val UNASSIGNED_WARNING = "Un ancien journal est conservé sur cet appareil sans propriétaire confirmé : historique complet et calculs à confirmer ; les nouveaux pointages restent enregistrés séparément."
    const val AUTH_WARNING = "Compte de pointage indisponible ou modifié : réessayer après vérification de la connexion."
    @PublishedApi internal val pinnedScope = ThreadLocal<String?>()
    @PublishedApi internal val pinDepth = ThreadLocal<Int>()

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
        if (depth == 0) pinnedScope.set(currentScope())
        pinDepth.set(depth + 1)
        try { return block() } finally {
            if (depth == 0) { pinDepth.remove(); pinnedScope.remove() } else pinDepth.set(depth)
        }
    }

    internal fun operationScope(): String? = if ((pinDepth.get() ?: 0) > 0) pinnedScope.get() else currentScope()
    internal fun available(): Boolean = operationScope()?.let { it == currentScope() } == true

    fun hasUnassignedHistory(context: Context): Boolean {
        val app = context.applicationContext
        if (V2LegacyJournalRecovery.assigned(app)) return false
        return app.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE).all.isNotEmpty() ||
            app.getSharedPreferences("pointage", Context.MODE_PRIVATE).contains("data")
    }

    fun preferences(context: Context, baseName: String): SharedPreferences {
        val scope = operationScope()
        val name = fileName(baseName, scope) ?: "${baseName}_auth_unavailable"
        val delegate = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        return GuardedPreferences(delegate,
            valid = { scope != null && scope == currentScope() },
            writeAllowed = { V2LegacyJournalRecovery.canWrite(context.applicationContext, scope) })
    }

    internal fun guardedPreferences(delegate: SharedPreferences, valid: () -> Boolean): SharedPreferences =
        GuardedPreferences(delegate, valid)

    /** An editor captured for A can never commit after the caller switches to B. */
    private class GuardedPreferences(val delegate: SharedPreferences, val valid: () -> Boolean, val writeAllowed: () -> Boolean = { true }) : SharedPreferences by delegate {
        override fun getAll(): MutableMap<String, *> = if (valid()) delegate.all else mutableMapOf<String, Any>()
        override fun contains(key: String?) = valid() && delegate.contains(key)
        override fun getString(key: String?, defValue: String?) = if (valid()) delegate.getString(key, defValue) else defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = if (valid()) delegate.getStringSet(key, defValues) else defValues
        override fun getInt(key: String?, defValue: Int) = if (valid()) delegate.getInt(key, defValue) else defValue
        override fun getLong(key: String?, defValue: Long) = if (valid()) delegate.getLong(key, defValue) else defValue
        override fun getFloat(key: String?, defValue: Float) = if (valid()) delegate.getFloat(key, defValue) else defValue
        override fun getBoolean(key: String?, defValue: Boolean) = if (valid()) delegate.getBoolean(key, defValue) else defValue
        override fun edit(): SharedPreferences.Editor = GuardedEditor(delegate.edit()) { valid() && writeAllowed() }
    }

    private class GuardedEditor(val delegate: SharedPreferences.Editor, val valid: () -> Boolean) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { delegate.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { delegate.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { delegate.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { delegate.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { delegate.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { delegate.putBoolean(key, value) }
        override fun remove(key: String?) = apply { delegate.remove(key) }
        override fun clear() = apply { delegate.clear() }
        override fun commit(): Boolean = valid() && delegate.commit()
        override fun apply() { if (valid()) delegate.commit() }
    }
}
