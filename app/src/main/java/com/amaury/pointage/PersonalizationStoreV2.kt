package com.amaury.pointage

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import java.security.MessageDigest

/** One atomic document per account. Local comfort never goes through generic cloud backup. */
object PersonalizationStoreV2 {
    fun accountScope(): String = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: "guest"
    private var cachedOwner: String? = null
    private var cachedPreferences: android.content.SharedPreferences? = null
    private var cachedRaw: String? = null
    private var cachedProfile = PersonalizationProfileV2()
    @Synchronized
    private fun preferences(context: Context): android.content.SharedPreferences {
        val uid = accountScope()
        if (cachedOwner == uid) cachedPreferences?.let { return it }
        val scope = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return context.applicationContext.getSharedPreferences("personalization_private_v2_$scope", Context.MODE_PRIVATE).also {
            cachedOwner = uid; cachedPreferences = it; cachedRaw = null; cachedProfile = PersonalizationProfileV2()
        }
    }
    @Synchronized
    fun read(context: Context): PersonalizationProfileV2 {
        val raw = preferences(context).getString("profile", null) ?: return PersonalizationProfileV2()
        // A corrupt profile is not overwritten. An explicit reset/import can repair it.
        if (cachedRaw != raw) {
            cachedProfile = runCatching { PersonalizationProfileV2.decode(raw) }.getOrDefault(PersonalizationProfileV2())
            cachedRaw = raw
        }
        return cachedProfile
    }
    fun save(context: Context, profile: PersonalizationProfileV2): Boolean =
        preferences(context).edit().putString("profile", profile.validated().encode()).commit()

    fun reset(context: Context): Boolean {
        val prefs = preferences(context)
        val old = prefs.getString("profile", null) ?: PersonalizationProfileV2().encode()
        return prefs.edit().putString("before_reset", old)
            .putString("profile", PersonalizationProfileV2(writingAssistance = read(context).writingAssistance).encode()).commit()
    }
    fun undoReset(context: Context): Boolean {
        val prefs = preferences(context)
        val previous = prefs.getString("before_reset", null) ?: return false
        val restored = runCatching { PersonalizationProfileV2.decode(previous) }.getOrNull() ?: return false
        return prefs.edit().putString("profile", restored.copy(writingAssistance = read(context).writingAssistance).encode()).remove("before_reset").commit()
    }
}
