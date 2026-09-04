package com.amaury.pointage

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.v2.LegifranceFunctionClientV2
import com.amaury.pointage.v2.OfficialConventionCatalogParserV2
import org.json.JSONArray
import org.json.JSONObject

object ConventionCatalog {
    data class OvertimeTier(val fromHour: Double, val toHour: Double?, val multiplier: Double)
    data class Convention(
        val idcc: String,
        val shortName: String,
        val fullName: String,
        /** Indique que les paliers d'heures supplémentaires utilisés par le calcul sont connus. */
        val rulesIntegrated: Boolean = false,
        val overtimeTiers: List<OvertimeTier> = legalOvertimeTiers(),
        /** Informations de référence : ces majorations ne sont pas encore calculées automatiquement. */
        val nightMultiplier: Double? = null,
        val sundayHolidayMultiplier: Double? = null,
        val advantages: List<String> = emptyList(),
        val cautions: List<String> = emptyList()
    ) {
        val displayName: String get() = if (idcc.isBlank()) shortName else "$shortName — IDCC $idcc"
        fun matches(query: String): Boolean {
            val q = query.trim().lowercase()
            if (q.isBlank()) return true
            return idcc.lowercase().contains(q) ||
                shortName.lowercase().contains(q) ||
                fullName.lowercase().contains(q)
        }
    }

    private fun legalOvertimeTiers() = listOf(
        OvertimeTier(35.0, 43.0, 1.25),
        OvertimeTier(43.0, null, 1.50)
    )

    private val builtIns = listOf(
        Convention(
            "0292",
            "Plasturgie",
            "Transformation des matières plastiques",
            true,
            legalOvertimeTiers(),
            1.12,
            2.00,
            listOf(
                "Heures supplémentaires majorées à 25 % puis 50 %.",
                "Majoration conventionnelle de nuit de 12 % lorsque les conditions sont réunies.",
                "Travail exceptionnel le dimanche ou un jour férié : majoration conventionnelle de 100 %.",
                "Repos compensateur prévu pour les travailleurs de nuit."
            ),
            listOf(
                "Le calcul automatique applique actuellement les paliers d'heures supplémentaires. Les majorations nuit, dimanche et jour férié restent affichées comme informations de référence tant qu'elles ne sont pas reliées à des horaires qualifiés.",
                "Un accord d'entreprise peut prévoir des règles différentes ou plus favorables.",
                "Certaines majorations ne se cumulent pas entre elles."
            )
        ),
        Convention(
            "1979",
            "Hôtels, cafés, restaurants (HCR)",
            "Convention collective nationale des hôtels, cafés restaurants",
            true,
            listOf(
                OvertimeTier(35.0, 39.0, 1.10),
                OvertimeTier(39.0, 42.0, 1.20),
                OvertimeTier(42.0, 43.0, 1.25),
                OvertimeTier(43.0, null, 1.50)
            ),
            advantages = listOf("Barème conventionnel spécifique des heures supplémentaires dans les dispositifs HCR concernés."),
            cautions = listOf("La modulation ou l'annualisation peut modifier le déclenchement des heures supplémentaires.")
        ),
        Convention("3248", "Métallurgie", "Convention collective nationale de la métallurgie", cautions = listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")),
        Convention("0016", "Transports routiers", "Transports routiers et activités auxiliaires du transport", cautions = listOf("Règles spécifiques selon l'emploi : régime légal provisoire.")),
        Convention("1486", "Syntec", "Bureaux d'études techniques, cabinets d'ingénieurs-conseils et sociétés de conseils", cautions = listOf("Règles détaillées non encore intégrées : régime légal provisoire.")),
        Convention("2216", "Commerce alimentaire", "Commerce de détail et de gros à prédominance alimentaire", cautions = listOf("Règles détaillées non encore intégrées : régime légal provisoire.")),
        Convention("1596", "Bâtiment — jusqu'à 10 salariés", "Ouvriers des entreprises du bâtiment occupant jusqu'à 10 salariés", cautions = listOf("Règles détaillées non encore intégrées : régime légal provisoire.")),
        Convention("1597", "Bâtiment — plus de 10 salariés", "Ouvriers des entreprises du bâtiment occupant plus de 10 salariés", cautions = listOf("Règles détaillées non encore intégrées : régime légal provisoire.")),
        Convention("3127", "Services à la personne", "Convention collective nationale des entreprises de services à la personne du 20 septembre 2012", cautions = listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")),
        Convention("2941", "Aide et services à domicile", "Convention collective nationale de la branche de l'aide, de l'accompagnement, des soins et des services à domicile du 21 mai 2010", cautions = listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")),
        Convention("3239", "Particuliers employeurs / emploi à domicile", "Convention collective de la branche du secteur des particuliers employeurs et de l'emploi à domicile du 15 mars 2021", cautions = listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")),
        Convention(
            "",
            "Régime légal / autre convention",
            "Régime légal sans règle conventionnelle intégrée",
            true,
            legalOvertimeTiers(),
            advantages = listOf("Calcul basé sur les majorations légales de référence des heures supplémentaires."),
            cautions = listOf("Ne tient pas compte d'une convention collective ou d'un accord d'entreprise plus favorable.")
        )
    )

    private const val PREFS = "convention_catalog"
    private const val CACHE = "kali_cache"
    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 20

    @Volatile private var current: List<Convention> = builtIns
    @Volatile private var refreshRunning = false
    val conventions: List<Convention> get() = current

    fun initialize(context: Context) {
        val app = context.applicationContext
        val cached = decodeCache(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CACHE, null))
        if (cached.isNotEmpty()) current = mergeWithBuiltIns(cached)
        refreshAsync(app)
    }

    private fun mergeWithBuiltIns(dynamic: List<Convention>): List<Convention> {
        val byId = linkedMapOf<String, Convention>()
        dynamic.forEach { if (it.idcc.isNotBlank()) byId[it.idcc.padStart(4, '0')] = it }
        // Les règles intégrées localement priment sur le simple catalogue distant.
        builtIns.forEach { if (it.idcc.isNotBlank()) byId[it.idcc.padStart(4, '0')] = it }
        return byId.values.sortedBy { it.shortName.lowercase() } + builtIns.first { it.idcc.isBlank() }
    }

    fun all(context: Context): List<Convention> {
        val dynamic = decodeCache(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CACHE, null))
        if (dynamic.isNotEmpty()) current = mergeWithBuiltIns(dynamic)
        return current
    }

    fun findByIdcc(context: Context, idcc: String?): Convention? {
        if (idcc.isNullOrBlank()) return current.firstOrNull { it.idcc.isBlank() }
        val normalized = idcc.padStart(4, '0')
        return all(context).firstOrNull { it.idcc.padStart(4, '0') == normalized }
    }

    fun findByIdcc(idcc: String?): Convention? {
        if (idcc.isNullOrBlank()) return current.firstOrNull { it.idcc.isBlank() }
        val normalized = idcc.padStart(4, '0')
        return current.firstOrNull { it.idcc.padStart(4, '0') == normalized }
    }

    /** Ajoute immédiatement une convention dont l'IDCC a été vérifié par KALI. */
    fun rememberOfficialConvention(context: Context, idcc: String, title: String): Boolean {
        val normalized = OfficialConventionCatalogParserV2.normalizeIdcc(idcc) ?: return false
        if (title.isBlank()) return false
        val app = context.applicationContext
        val cached = decodeCache(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CACHE, null))
            .filterNot { it.idcc.padStart(4, '0') == normalized }
            .toMutableList()
        cached += Convention(
            normalized,
            shortTitle(title),
            title.trim(),
            false,
            cautions = listOf("Convention vérifiée via l’API officielle KALI. Règles salariales spécifiques non encore validées : régime légal provisoire.")
        )
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CACHE, encodeCache(cached))
            .commit()
        if (saved) current = mergeWithBuiltIns(cached)
        return saved
    }

    fun refreshAsync(context: Context, onDone: (Int) -> Unit = {}) {
        if (refreshRunning) {
            Handler(Looper.getMainLooper()).post { onDone(current.count { it.idcc.isNotBlank() }) }
            return
        }
        refreshRunning = true
        val app = context.applicationContext
        downloadOfficialCatalogPage(1, linkedMapOf()) { parsed ->
            if (!parsed.isNullOrEmpty()) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(CACHE, encodeCache(parsed))
                    .apply()
                current = mergeWithBuiltIns(parsed)
            }
            refreshRunning = false
            val count = if (parsed.isNullOrEmpty()) current.count { it.idcc.isNotBlank() } else parsed.size
            Handler(Looper.getMainLooper()).post { onDone(count) }
        }
    }

    private fun downloadOfficialCatalogPage(
        pageNumber: Int,
        byId: LinkedHashMap<String, Convention>,
        onDone: (List<Convention>?) -> Unit
    ) {
        val body = mapOf(
            "pageNumber" to pageNumber,
            "pageSize" to PAGE_SIZE,
            "sort" to "DATE_UPDATE",
            "legalStatus" to listOf("VIGUEUR", "VIGUEUR_ETEN", "VIGUEUR_NON_ETEN", "VIGUEUR_DIFF")
        )
        LegifranceFunctionClientV2.request("/list/conventions", body)
            .addOnSuccessListener { result ->
                val parsed = OfficialConventionCatalogParserV2.parse(result.data)
                parsed.items.forEach { item ->
                    byId[item.idcc] = Convention(
                        item.idcc,
                        shortTitle(item.title),
                        item.title,
                        false,
                        cautions = listOf("Convention issue de l’API officielle KALI (DILA). Règles salariales spécifiques non encore validées : régime légal provisoire.")
                    )
                }
                val reachedTotal = parsed.totalResultNumber?.let { pageNumber * PAGE_SIZE >= it } ?: false
                val hasAnotherPage = pageNumber < MAX_PAGES &&
                    (parsed.totalResultNumber?.let { pageNumber * PAGE_SIZE < it }
                        ?: (parsed.rawResultCount == PAGE_SIZE)) &&
                    !reachedTotal
                when {
                    hasAnotherPage -> downloadOfficialCatalogPage(pageNumber + 1, byId, onDone)
                    byId.isNotEmpty() -> onDone(byId.values.toList())
                    else -> onDone(null)
                }
            }
            .addOnFailureListener { onDone(null) }
    }

    private fun shortTitle(title: String): String {
        var t = title
            .replace(Regex("(?i)^Convention collective nationale\\s+(de|des|du|pour|relative à|concernant)?\\s*"), "")
            .replace(Regex("(?i)^Convention collective\\s+(de|des|du|pour)?\\s*"), "")
            .trim()
        if (t.length > 110) t = t.take(107).trimEnd() + "…"
        return t.ifBlank { title }
    }

    private fun encodeCache(items: List<Convention>): String {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("idcc", it.idcc).put("title", it.fullName).put("short", it.shortName))
        }
        return a.toString()
    }

    private fun decodeCache(raw: String?): List<Convention> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optString("idcc")
                    val t = o.optString("title")
                    val s = o.optString("short").ifBlank { shortTitle(t) }
                    if (id.isNotBlank() && t.isNotBlank()) {
                        add(
                            Convention(
                                id,
                                s,
                                t,
                                false,
                                cautions = listOf("Convention issue du catalogue officiel Légifrance (DILA). Règles salariales spécifiques non encore intégrées : régime légal provisoire.")
                            )
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }
}
