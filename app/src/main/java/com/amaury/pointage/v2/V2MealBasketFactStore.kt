package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.VerifiedMealBasketPayrollV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Persistance strictement locale du journal factuel repas.
 *
 * Aucun profil détaillé ni fait salarié n'est envoyé vers Firebase. Le store refuse d'écraser un
 * journal corrompu et ne tronque jamais silencieusement les faits lorsqu'il atteint sa capacité.
 */
object V2MealBasketFactStore {
    private const val PREFS = "horatrack_v2_meal_fact_journal"
    private const val KEY_ENTRIES = "entries_v1"
    private const val MAX_ENTRIES = 5_000

    data class LoadResult(
        val entries: List<MealBasketFactJournalV2.Entry>,
        val malformedCount: Int
    )

    fun resolve(
        context: Context,
        companyId: String,
        day: LocalDate,
        sessionId: String
    ): MealBasketFactJournalV2.Resolution {
        val loaded = load(context)
        val resolved = MealBasketFactJournalV2.resolve(
            entries = loaded.entries,
            companyId = companyId,
            day = day,
            sessionId = sessionId
        )
        if (loaded.malformedCount == 0) return resolved

        // Une entrée illisible pourrait être précisément le fait plus spécifique qui contredit un
        // ancien fallback. Comme sa clé/portée ne sont plus prouvables, toutes les clés factuelles
        // restent inconnues pour cette résolution plutôt que de traverser la corruption.
        return MealBasketFactJournalV2.Resolution(
            facts = VerifiedMealBasketPayrollV2.FactDefaults(),
            warnings = (resolved.warnings +
                "Panier : ${loaded.malformedCount} fait(s) local(aux) illisible(s) ; journal factuel non exploitable pour cette session.").distinct(),
            blockedKeys = MealBasketFactJournalV2.Key.entries.toSet()
        )
    }

    fun save(context: Context, entry: MealBasketFactJournalV2.Entry): Boolean =
        replaceAtomically(context, setOf(entry.id), listOf(entry))

    fun remove(context: Context, entryId: String): Boolean =
        replaceAtomically(context, setOf(entryId), emptyList())

    /**
     * Remplace plusieurs faits en une seule écriture SharedPreferences.
     *
     * Cette API est utilisée par les formulaires qui décrivent une même période : soit tout le lot
     * est validé et écrit, soit le journal reste strictement inchangé.
     */
    fun replaceAtomically(
        context: Context,
        removeEntryIds: Set<String>,
        replacements: List<MealBasketFactJournalV2.Entry>
    ): Boolean {
        if (removeEntryIds.any { it.isBlank() }) return false
        if (replacements.any { !it.structurallyValid() }) return false
        if (replacements.map { it.id }.toSet().size != replacements.size) return false

        val loaded = load(context)
        // Ne jamais réécrire une version partiellement décodée : cela pourrait effacer un fait
        // bloquant que cette version de l'app n'a pas réussi à relire.
        if (loaded.malformedCount > 0) return false

        val idsToReplace = removeEntryIds + replacements.map { it.id }
        val next = loaded.entries.filterNot { it.id in idsToReplace } + replacements
        if (next.size > MAX_ENTRIES) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, encode(next).toString())
            .commit()
    }

    fun load(context: Context): LoadResult {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return LoadResult(emptyList(), 0)
        return decode(raw)
    }

    internal fun encode(entries: List<MealBasketFactJournalV2.Entry>): JSONArray = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("id", entry.id)
                put("companyId", entry.companyId)
                put("scope", entry.scope.name)
                put("key", entry.key.name)
                put("source", entry.source.name)
                put("status", entry.status.name)
                put("recordedAtMs", entry.recordedAtMs)
                put("effectiveFromEpochDay", entry.effectiveFromEpochDay ?: JSONObject.NULL)
                put("effectiveToEpochDay", entry.effectiveToEpochDay ?: JSONObject.NULL)
                put("dayEpochDay", entry.dayEpochDay ?: JSONObject.NULL)
                put("sessionId", entry.sessionId ?: JSONObject.NULL)
                when (val value = entry.value) {
                    MealBasketFactJournalV2.Value.Unknown -> put("valueKind", "UNKNOWN")
                    is MealBasketFactJournalV2.Value.Flag -> {
                        put("valueKind", "FLAG")
                        put("flag", value.value)
                    }
                    is MealBasketFactJournalV2.Value.NightWindow -> {
                        put("valueKind", "NIGHT_WINDOW")
                        put("windowStart", value.window.startMinute)
                        put("windowEnd", value.window.endMinute)
                    }
                }
            })
        }
    }

    internal fun decode(raw: String): LoadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return LoadResult(emptyList(), 1)
        val entries = mutableListOf<MealBasketFactJournalV2.Entry>()
        var malformed = 0
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index)
            val entry = json?.let(::decodeEntry)
            if (entry == null) malformed++ else entries += entry
        }
        return LoadResult(entries, malformed)
    }

    private fun decodeEntry(json: JSONObject): MealBasketFactJournalV2.Entry? = runCatching {
        val scope = MealBasketFactJournalV2.Scope.valueOf(json.getString("scope"))
        val key = MealBasketFactJournalV2.Key.valueOf(json.getString("key"))
        val source = MealBasketFactJournalV2.Source.valueOf(json.getString("source"))
        val status = DecisionStatusV2.valueOf(json.getString("status"))
        val value = when (json.getString("valueKind")) {
            "UNKNOWN" -> MealBasketFactJournalV2.Value.Unknown
            "FLAG" -> MealBasketFactJournalV2.Value.Flag(json.getBoolean("flag"))
            "NIGHT_WINDOW" -> MealBasketFactJournalV2.Value.NightWindow(
                ConventionMealBasketV2.DailyWindow(
                    startMinute = json.getInt("windowStart"),
                    endMinute = json.getInt("windowEnd")
                )
            )
            else -> return null
        }
        MealBasketFactJournalV2.Entry(
            id = json.getString("id"),
            companyId = json.getString("companyId"),
            scope = scope,
            key = key,
            value = value,
            source = source,
            status = status,
            recordedAtMs = json.getLong("recordedAtMs"),
            effectiveFromEpochDay = nullableLong(json, "effectiveFromEpochDay"),
            effectiveToEpochDay = nullableLong(json, "effectiveToEpochDay"),
            dayEpochDay = nullableLong(json, "dayEpochDay"),
            sessionId = nullableString(json, "sessionId")
        ).takeIf { it.structurallyValid() }
    }.getOrNull()

    private fun nullableLong(json: JSONObject, key: String): Long? =
        if (!json.has(key) || json.isNull(key)) null else json.getLong(key)

    private fun nullableString(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) null
        else json.getString(key).trim().takeIf { it.isNotBlank() }
}
