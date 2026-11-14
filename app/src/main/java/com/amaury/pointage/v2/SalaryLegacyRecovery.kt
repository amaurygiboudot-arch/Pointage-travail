package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest

/** Explicit local attribution. No business value is shown before the ownership declaration. */
object SalaryLegacyRecovery {
    private const val RECEIPTS = "salary_legacy_recovery_owner"
    private const val OWNER = "local:owner"
    private const val DIGEST = "local:digest"
    data class Preview(val digest: String, val fileCount: Int, val entryCount: Int, val alreadyClaimed: Boolean, val declined: Boolean = false)

    fun preview(context: Context): Preview? {
        val context = SalaryAccountScope.bindContext(context)
        val owner = SalaryAccountScope.lease(context)
        val scope = owner.scope ?: return null
        if (!SalaryAccountScope.available(context)) return null
        val receipts = SalaryAccountScope.rawContext(context).getSharedPreferences(RECEIPTS, Context.MODE_PRIVATE).all
        val previous = receipts[OWNER]
        if (previous != null && previous != scope) return null
        val sources = runCatching { sources(context) }.getOrNull() ?: return null
        if (sources.isEmpty()) return null
        val digest = fingerprint(sources)
        if (!SalaryAccountScope.isCurrent(owner)) return null
        val decision = SalaryAccountScope.preferences(context, "salary_account_recovery_receipt").getString("ignoredDigest", null)
        return Preview(digest, sources.size, sources.values.sumOf { it.size }, previous == scope && receipts[DIGEST] == digest, decision == digest)
    }

    fun claim(context: Context, expectedDigest: String, ownershipConfirmed: Boolean): Boolean {
        if (!ownershipConfirmed || expectedDigest.isBlank()) return false
        val context = SalaryAccountScope.bindContext(context)
        val lease = SalaryAccountScope.lease(context)
        val scope = lease.scope?.takeIf { it.startsWith("uid:") } ?: return false
        if (!SalaryAccountScope.available(context)) return false
        return runCatching {
            val committedLease = V2RestoreTransaction.execute(context, scope, {
                SalaryAccountScope.isCurrent(lease) && runCatching { fingerprint(sources(context)) == expectedDigest }.getOrDefault(false)
            }) {
                val app = SalaryAccountScope.rawContext(context)
                val receipts = app.getSharedPreferences(RECEIPTS, Context.MODE_PRIVATE).all.toMutableMap()
                val previousOwner = receipts[OWNER]
                check(previousOwner == null || previousOwner == scope) { "Les anciennes données ont déjà un propriétaire." }
                val sources = sources(context)
                check(sources.isNotEmpty() && fingerprint(sources) == expectedDigest) { "Les anciennes données ont changé : vérifier de nouveau la reprise." }
                if (previousOwner == scope) {
                    check(receipts[DIGEST] == expectedDigest) { "La reprise précédente ne correspond pas à cette source." }
                    return@execute emptyMap()
                }
                val destinationNames = SalaryPreferenceRegistry.personalNames(context, scope) + sources.keys
                check(destinationNames.all { name ->
                    val physical = SalaryPreferenceRegistry.fileName(name, scope) ?: return@all false
                    app.getSharedPreferences(physical, Context.MODE_PRIVATE).all.isEmpty()
                }) { "Ce compte contient déjà des données salariales : aucune fusion automatique n'est autorisée." }
                receipts[OWNER] = scope
                receipts[DIGEST] = expectedDigest
                receipts["local:version"] = 1
                sources + mapOf(
                    RECEIPTS to receipts,
                    "salary_account_recovery_receipt" to mapOf("sourceDigest" to expectedDigest, "ownerScope" to scope, "version" to 1)
                )
            }
            committedLease.scope == scope && SalaryAccountScope.isCurrent(committedLease) &&
                preview(SalaryAccountScope.bindContext(SalaryAccountScope.rawContext(context), committedLease))?.alreadyClaimed == true
        }.getOrDefault(false)
    }

    fun hasUnresolved(context: Context): Boolean {
        val context = SalaryAccountScope.bindContext(context)
        if (!SalaryAccountScope.available(context)) return true
        val app = SalaryAccountScope.rawContext(context)
        val receipt = app.getSharedPreferences(RECEIPTS, Context.MODE_PRIVATE).all
        // A confirmed attribution means these originals belong to that owner, never another account.
        if (receipt[OWNER] is String) return false
        val sources = runCatching { sources(context) }.getOrNull() ?: return true
        if (sources.isEmpty()) return false
        val decision = SalaryAccountScope.preferences(context, "salary_account_recovery_receipt").getString("ignoredDigest", null)
        return decision != fingerprint(sources)
    }

    /** Legacy binary references are usable only after this installation's explicit local attribution. */
    fun ownsOriginalHistory(context: Context): Boolean {
        val context = SalaryAccountScope.bindContext(context)
        val lease = SalaryAccountScope.lease(context)
        val scope = lease.scope?.takeIf { it.startsWith("uid:") } ?: return false
        if (!SalaryAccountScope.available(context)) return false
        val confirmed = V2RestoreTransaction.withAccess(context, scope, { false }) {
            val receipt = SalaryAccountScope.rawContext(context).getSharedPreferences(RECEIPTS, Context.MODE_PRIVATE).all
            val digest = receipt[DIGEST] as? String
            val local = SalaryAccountScope.preferences(context, "salary_account_recovery_receipt")
            receipt[OWNER] == scope && receipt["local:version"] == 1 && digest != null &&
                local.getString("ownerScope", null) == scope && local.getString("sourceDigest", null) == digest &&
                runCatching { fingerprint(sources(context)) == digest }.getOrDefault(false)
        }
        return confirmed && SalaryAccountScope.available(context)
    }

    fun decline(context: Context, expectedDigest: String, confirmed: Boolean): Boolean {
        if (!confirmed) return false
        val context = SalaryAccountScope.bindContext(context)
        if (!SalaryAccountScope.available(context)) return false
        val preview = preview(context) ?: return false
        if (preview.alreadyClaimed || preview.digest != expectedDigest) return false
        return SalaryAccountScope.preferences(context, "salary_account_recovery_receipt").edit()
            .putString("ignoredDigest", expectedDigest).commit()
    }

    /** Only this recovery path reads unassigned personal preference files. Sources remain untouched. */
    private fun sources(context: Context): Map<String, Map<String, Any?>> {
        val app = SalaryAccountScope.rawContext(context)
        return SalaryPreferenceRegistry.legacyNames(context).sorted().mapNotNull { name ->
            val values = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
            if (values.isEmpty()) null else name to values
        }.toMap()
    }

    private fun fingerprint(sources: Map<String, Map<String, Any?>>): String {
        val files = JSONArray()
        sources.toSortedMap().forEach { (name, values) ->
            val entries = JSONArray()
            values.toSortedMap().forEach { (key, value) ->
                val type = when (value) {
                    is String -> "string"
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is Set<*> -> "string_set"
                    else -> error("Unsupported preference value")
                }
                val encoded = if (value is Set<*>) {
                    check(value.all { it is String })
                    JSONArray(value.filterIsInstance<String>().sorted())
                } else value.toString()
                entries.put(JSONArray().put(key).put(type).put(encoded))
            }
            files.put(JSONArray().put(name).put(entries))
        }
        return MessageDigest.getInstance("SHA-256").digest(files.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
