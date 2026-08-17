package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class EnterpriseLookupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private data class SlotViews(
        val root: LinearLayout,
        val title: TextView,
        val siret: EditText,
        val search: Button,
        val company: TextView,
        val convention: TextView,
        val agreements: TextView,
        val legifrance: Button
    )

    private val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
    private val header = TextView(context)
    private val help = TextView(context)
    private val slots = mutableMapOf<Int, SlotViews>()

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundResource(R.drawable.hp_panel)

        header.apply {
            text = "MES ENTREPRISES"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(header)

        help.apply {
            text = "Ajoute jusqu'à deux employeurs. Chaque SIRET garde sa convention et ses accords séparément."
            textSize = 12f
            setPadding(0, dp(5), 0, dp(8))
        }
        addView(help)

        slots[1] = createSlot(1)
        slots[2] = createSlot(2)

        migrateLegacyCompanyToSlot1()
        restoreSlot(1)
        restoreSlot(2)
        applyTheme()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTheme()
    }

    private fun createSlot(slot: Int): SlotViews {
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.hp_panel)
        }
        val title = TextView(context).apply {
            text = "ENTREPRISE $slot"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        val siret = EditText(context).apply {
            hint = "SIRET — 14 chiffres"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        val search = Button(context).apply {
            text = "RECHERCHER"
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { lookup(slot) }
        }
        val company = TextView(context).apply { textSize = 14f; setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        val convention = TextView(context).apply { textSize = 13f; setPadding(0, dp(7), 0, 0); visibility = View.GONE }
        val agreements = TextView(context).apply { textSize = 13f; setPadding(0, dp(7), 0, 0); visibility = View.GONE }
        val legifrance = Button(context).apply {
            text = "VOIR LES ACCORDS SUR LÉGIFRANCE"
            setBackgroundResource(R.drawable.hp_panel)
            visibility = View.GONE
            setOnClickListener { openLegifrance(slot) }
        }

        root.addView(title)
        root.addView(siret, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        root.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        root.addView(company)
        root.addView(convention)
        root.addView(agreements)
        root.addView(legifrance, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (slot == 1) 4 else 10) })
        return SlotViews(root, title, siret, search, company, convention, agreements, legifrance)
    }

    private fun prefix(slot: Int): String = if (slot == 1) "company_" else "company2_"
    private fun key(slot: Int, field: String): String = prefix(slot) + field

    private fun migrateLegacyCompanyToSlot1() {
        // Slot 1 deliberately keeps the old company_* keys for compatibility.
        // This method exists to make the two-slot model explicit and future-proof.
    }

    private fun restoreSlot(slot: Int) {
        val v = slots[slot] ?: return
        val siret = prefs.getString(key(slot, "siret"), "").orEmpty()
        val name = prefs.getString(key(slot, "name"), "").orEmpty()
        val siren = prefs.getString(key(slot, "siren"), "").orEmpty()
        val address = prefs.getString(key(slot, "address"), "").orEmpty()
        val ape = prefs.getString(key(slot, "ape"), "").orEmpty()
        val idcc = prefs.getString(key(slot, "idcc"), "").orEmpty()
        val conventionName = prefs.getString(key(slot, "convention_name"), "").orEmpty()
        val agreementSummary = prefs.getString(key(slot, "agreement_summary"), "").orEmpty()

        v.siret.setText(siret)
        show(v.company, buildCompanySummary(name, siret, address, ape))
        show(v.convention, buildConventionSummary(idcc, conventionName))
        show(v.agreements, agreementSummary)
        v.legifrance.visibility = if (siren.isBlank() && name.isBlank()) View.GONE else View.VISIBLE
    }

    private fun lookup(slot: Int) {
        val v = slots[slot] ?: return
        val siret = v.siret.text.toString().filter(Char::isDigit)
        if (siret.length != 14) {
            Toast.makeText(context, "Le SIRET doit contenir exactement 14 chiffres", Toast.LENGTH_LONG).show()
            return
        }

        v.search.isEnabled = false
        v.search.text = "RECHERCHE…"
        show(v.company, "Recherche dans les données publiques…")
        show(v.convention, "")
        show(v.agreements, "")
        v.legifrance.visibility = View.GONE

        Thread {
            try {
                val encoded = URLEncoder.encode(siret, StandardCharsets.UTF_8.name())
                val connection = URL("https://recherche-entreprises.api.gouv.fr/search?q=$encoded&per_page=1").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "HP-Travail-Android")

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (code !in 200..299) throw IllegalStateException("service indisponible ($code)")

                val root = JSONObject(body)
                val results = root.optJSONArray("results") ?: JSONArray()
                if (results.length() == 0) throw IllegalStateException("aucune entreprise trouvée pour ce SIRET")
                val result = results.getJSONObject(0)

                val name = firstNonBlank(
                    result.optString("nom_complet"),
                    result.optString("nom_raison_sociale"),
                    result.optString("denomination"),
                    result.optString("nom")
                )
                val siren = result.optString("siren").ifBlank { siret.take(9) }
                val establishment = findMatchingEstablishment(result, siret)
                val address = firstNonBlank(
                    establishment?.optString("adresse"),
                    establishment?.optString("adresse_complete"),
                    result.optString("adresse")
                )
                val ape = firstNonBlank(
                    establishment?.optString("activite_principale"),
                    result.optString("activite_principale")
                )
                val idcc = findIdccs(result).firstOrNull().orEmpty()
                val localConvention = ConventionCatalog.findByIdcc(idcc)
                val conventionName = localConvention?.fullName.orEmpty()
                val agreementSummary = fetchCompanyAgreementSummary(siren, name)

                prefs.edit()
                    .putString(key(slot, "siret"), siret)
                    .putString(key(slot, "siren"), siren)
                    .putString(key(slot, "name"), name)
                    .putString(key(slot, "address"), address)
                    .putString(key(slot, "ape"), ape)
                    .putString(key(slot, "idcc"), idcc)
                    .putString(key(slot, "convention_name"), conventionName)
                    .putString(key(slot, "agreement_summary"), agreementSummary)
                    .apply()

                // Keep the historical salary convention tied to employer 1 only.
                if (slot == 1 && localConvention != null) {
                    prefs.edit().putString("convention_idcc", localConvention.idcc).apply()
                }

                post {
                    applyTheme()
                    show(v.company, buildCompanySummary(name, siret, address, ape))
                    show(v.convention, buildConventionSummary(idcc, conventionName))
                    show(v.agreements, agreementSummary)
                    v.legifrance.visibility = if (siren.isBlank() && name.isBlank()) View.GONE else View.VISIBLE
                    v.search.isEnabled = true
                    v.search.text = "RECHERCHER"
                    Toast.makeText(
                        context,
                        if (idcc.isNotBlank()) "Entreprise $slot trouvée — IDCC $idcc" else "Entreprise $slot trouvée",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                post {
                    show(v.company, "Impossible de récupérer l'entreprise : ${e.message ?: "erreur inconnue"}")
                    show(v.convention, "")
                    show(v.agreements, "")
                    v.legifrance.visibility = View.GONE
                    v.search.isEnabled = true
                    v.search.text = "RECHERCHER"
                    applyTheme()
                }
            }
        }.start()
    }

    private fun buildCompanySummary(name: String, siret: String, address: String, ape: String): String {
        val lines = mutableListOf<String>()
        if (name.isNotBlank()) lines += "🏢 $name"
        if (siret.isNotBlank()) lines += "SIRET : $siret"
        if (address.isNotBlank()) lines += "Adresse : $address"
        if (ape.isNotBlank()) lines += "APE/NAF : $ape"
        return lines.joinToString("\n")
    }

    private fun buildConventionSummary(idcc: String, conventionName: String): String {
        if (idcc.isBlank()) return ""
        val lines = mutableListOf<String>()
        lines += "Convention : ${conventionName.ifBlank { "IDCC $idcc" }}${if (conventionName.isNotBlank()) " — IDCC $idcc" else ""}"
        ConventionCatalog.findByIdcc(idcc)?.advantages?.takeIf { it.isNotEmpty() }?.let { advantages ->
            lines += ""
            lines += "Rémunération / avantages de branche :"
            advantages.forEach { lines += "• $it" }
        }
        return lines.joinToString("\n")
    }

    private fun show(view: TextView, value: String) {
        view.text = value
        view.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private fun openLegifrance(slot: Int) {
        val name = prefs.getString(key(slot, "name"), "").orEmpty()
        val siren = prefs.getString(key(slot, "siren"), "").orEmpty()
        val query = listOf(name, siren).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return
        val url = "https://www.legifrance.gouv.fr/liste/acco?query=${Uri.encode(query)}&searchField=ALL&tab_selection=acco"
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "Impossible d'ouvrir Légifrance", Toast.LENGTH_LONG).show() }
    }

    private fun fetchCompanyAgreementSummary(siren: String, companyName: String): String {
        if (siren.isBlank() && companyName.isBlank()) return ""
        return runCatching {
            val query = listOf(siren, companyName).filter { it.isNotBlank() }.joinToString(" ")
            val url = "https://www.legifrance.gouv.fr/liste/acco?query=${URLEncoder.encode(query, StandardCharsets.UTF_8.name())}&searchField=ALL&tab_selection=acco"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 HP-Travail-Android")
            val code = connection.responseCode
            val html = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else ""
            connection.disconnect()
            if (html.isBlank()) return@runCatching ""
            val text = decodeHtml(html)
            val relevant = text.lineSequence()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.length in 6..240 }
                .filter { line -> remunerationKeywords.any { line.contains(it, ignoreCase = true) } }
                .filterNot { it.contains("Rechercher", true) || it.contains("Filtrer", true) }
                .distinct()
                .take(10)
                .toList()
            if (relevant.isEmpty()) "" else buildString {
                append("AVANTAGES / ACCORDS DE L'ENTREPRISE\n")
                relevant.forEach { append("• ").append(it).append('\n') }
            }.trim()
        }.getOrDefault("")
    }

    private val remunerationKeywords = listOf(
        "prime", "salaire", "rémunération", "remuneration", "indemnité", "indemnités",
        "intéressement", "interessement", "participation", "heures supplémentaires",
        "heures supplementaires", "majoration", "travail de nuit", "dimanche", "panier",
        "repas", "13e mois", "treizième mois", "RTT", "congés", "partage de la valeur", "PPV"
    )

    private fun decodeHtml(html: String): String = html
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|li|h1|h2|h3|div|article)>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&eacute;", "é").replace("&Eacute;", "É")
        .replace("&agrave;", "à").replace("&ccedil;", "ç")

    private fun findIdccs(result: JSONObject): List<String> {
        val values = linkedSetOf<String>()
        result.optJSONObject("complements")?.optJSONArray("liste_idcc")?.let { list ->
            for (i in 0 until list.length()) normalizeIdcc(list.optString(i)).takeIf { it.isNotBlank() }?.let(values::add)
        }
        fun collect(obj: JSONObject) {
            listOf(obj.optString("idcc"), obj.optString("numero_idcc"), obj.optString("id_convention_collective"))
                .forEach { normalizeIdcc(it).takeIf(String::isNotBlank)?.let(values::add) }
            obj.optJSONArray("conventions_collectives")?.let { conventions ->
                for (i in 0 until conventions.length()) {
                    val item = conventions.optJSONObject(i) ?: continue
                    normalizeIdcc(firstNonBlank(item.optString("idcc"), item.optString("numero_idcc"), item.optString("id_convention_collective")))
                        .takeIf(String::isNotBlank)?.let(values::add)
                }
            }
        }
        collect(result)
        result.optJSONObject("siege")?.let(::collect)
        result.optJSONArray("matching_etablissements")?.let { matching ->
            for (i in 0 until matching.length()) matching.optJSONObject(i)?.let(::collect)
        }
        return values.toList()
    }

    private fun normalizeIdcc(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return if (digits.isBlank()) "" else digits.padStart(4, '0').takeLast(4)
    }

    private fun findMatchingEstablishment(result: JSONObject, siret: String): JSONObject? {
        val siege = result.optJSONObject("siege")
        if (siege != null && siege.optString("siret") == siret) return siege
        val matching = result.optJSONArray("matching_etablissements")
        if (matching != null) {
            for (i in 0 until matching.length()) {
                val item = matching.optJSONObject(i) ?: continue
                if (item.optString("siret") == siret) return item
            }
            if (matching.length() > 0) return matching.optJSONObject(0)
        }
        return siege
    }

    private fun themeColors(): Triple<Int, Int, Int> {
        val appearance = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = appearance.getString("mode", "auto") ?: "auto"
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && systemDark)
        val defaultBg = if (dark) "#080808" else "#F3F0E8"
        val bg = runCatching { Color.parseColor(appearance.getString("app_bg", null) ?: defaultBg) }.getOrElse { Color.parseColor(defaultBg) }
        val custom = appearance.getBoolean("custom_bg", false)
        val panel = if (custom) {
            mix(bg, if (AppearanceManager.bestTextColor(bg) == Color.WHITE) Color.WHITE else Color.BLACK,
                if (AppearanceManager.bestTextColor(bg) == Color.WHITE) 0.16f else 0.07f)
        } else if (dark) Color.parseColor("#1B1B1B") else Color.WHITE
        val text = AppearanceManager.bestTextColor(panel)
        val secondary = mix(text, panel, 0.68f)
        return Triple(panel, text, secondary)
    }

    private fun applyTheme() {
        val (panel, text, secondary) = themeColors()
        val gold = Color.parseColor("#D6A84B")
        val accent = if (AppearanceManager.contrastRatio(gold, panel) >= 4.5) gold else text
        backgroundTintList = ColorStateList.valueOf(panel)
        header.setTextColor(accent)
        help.setTextColor(secondary)
        slots.values.forEach { v ->
            v.root.backgroundTintList = ColorStateList.valueOf(panel)
            v.title.setTextColor(accent)
            v.siret.setTextColor(text)
            v.siret.setHintTextColor(secondary)
            v.company.setTextColor(text)
            v.convention.setTextColor(secondary)
            v.agreements.setTextColor(text)
            v.search.backgroundTintList = ColorStateList.valueOf(panel)
            v.search.setTextColor(text)
            v.legifrance.backgroundTintList = ColorStateList.valueOf(panel)
            v.legifrance.setTextColor(text)
        }
    }

    private fun mix(a: Int, b: Int, bAmount: Float) = Color.rgb(
        (Color.red(a) * (1f - bAmount) + Color.red(b) * bAmount).toInt().coerceIn(0, 255),
        (Color.green(a) * (1f - bAmount) + Color.green(b) * bAmount).toInt().coerceIn(0, 255),
        (Color.blue(a) * (1f - bAmount) + Color.blue(b) * bAmount).toInt().coerceIn(0, 255)
    )

    private fun firstNonBlank(vararg values: String?) = values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
