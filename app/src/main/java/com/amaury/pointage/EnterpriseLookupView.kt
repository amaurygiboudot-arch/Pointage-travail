package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.EnterpriseIdccParserV2
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
        val summary: TextView,
        val details: Button,
        val delete: Button
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
            text = "Entreprise 1 = principale. Appuie sur DÉTAILS pour afficher toutes les informations."
            textSize = 12f
            setPadding(0, dp(5), 0, dp(8))
        }
        addView(help)

        slots[1] = createSlot(1)
        slots[2] = createSlot(2)
        restoreSlot(1)
        restoreSlot(2)
        applyTheme()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTheme()
        restoreSlot(1)
        restoreSlot(2)
    }

    private fun createSlot(slot: Int): SlotViews {
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.hp_panel)
        }
        val title = TextView(context).apply {
            text = if (slot == 1) "ENTREPRISE 1 • PRINCIPALE" else "ENTREPRISE 2"
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
        val summary = TextView(context).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }
        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val details = Button(context).apply {
            text = "DÉTAILS"
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { showDetails(slot) }
        }
        val delete = Button(context).apply {
            text = "SUPPRIMER"
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { confirmDelete(slot) }
        }

        root.addView(title)
        root.addView(siret, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        root.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        root.addView(summary)
        actions.addView(details, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        actions.addView(delete, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        root.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (slot == 1) 4 else 10) })
        return SlotViews(root, title, siret, search, summary, details, delete)
    }

    private fun prefix(slot: Int) = if (slot == 1) "company_" else "company2_"
    private fun key(slot: Int, field: String) = prefix(slot) + field

    private fun restoreSlot(slot: Int) {
        val v = slots[slot] ?: return
        val siret = prefs.getString(key(slot, "siret"), "").orEmpty()
        val name = prefs.getString(key(slot, "name"), "").orEmpty()
        val idcc = prefs.getString(key(slot, "idcc"), "").orEmpty()
        v.siret.setText(siret)
        v.summary.text = when {
            name.isBlank() && siret.isBlank() -> "Aucune entreprise enregistrée"
            idcc.isBlank() -> name.ifBlank { "Entreprise enregistrée" }
            else -> "${name.ifBlank { "Entreprise enregistrée" }}\nConvention : IDCC $idcc"
        }
        val hasData = name.isNotBlank() || siret.isNotBlank()
        v.details.isEnabled = hasData
        v.delete.isEnabled = hasData
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
        v.summary.text = "Recherche dans les données publiques…"

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

                val name = firstNonBlank(result.optString("nom_complet"), result.optString("nom_raison_sociale"), result.optString("denomination"), result.optString("nom"))
                val siren = result.optString("siren").ifBlank { siret.take(9) }
                val establishment = findMatchingEstablishment(result, siret)
                val address = firstNonBlank(establishment?.optString("adresse"), establishment?.optString("adresse_complete"), result.optString("adresse"))
                val ape = firstNonBlank(establishment?.optString("activite_principale"), result.optString("activite_principale"))
                val idcc = EnterpriseIdccParserV2.find(result, siret).firstOrNull().orEmpty()
                val convention = ConventionCatalog.findByIdcc(idcc)
                val conventionName = convention?.fullName.orEmpty()
                val agreements = fetchCompanyAgreementSummary(siren, name)

                prefs.edit()
                    .putString(key(slot, "siret"), siret)
                    .putString(key(slot, "siren"), siren)
                    .putString(key(slot, "name"), name)
                    .putString(key(slot, "address"), address)
                    .putString(key(slot, "ape"), ape)
                    .putString(key(slot, "idcc"), idcc)
                    .putString(key(slot, "convention_name"), conventionName)
                    .putString(key(slot, "agreement_summary"), agreements)
                    .apply()

                if (slot == 1 && convention != null) {
                    prefs.edit().putString("convention_idcc", convention.idcc).apply()
                }

                post {
                    v.search.isEnabled = true
                    v.search.text = "RECHERCHER"
                    restoreSlot(slot)
                    Toast.makeText(context, if (slot == 1) "Entreprise principale enregistrée" else "Entreprise 2 enregistrée", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                post {
                    v.search.isEnabled = true
                    v.search.text = "RECHERCHER"
                    v.summary.text = "Impossible de récupérer l'entreprise : ${e.message ?: "erreur inconnue"}"
                }
            }
        }.start()
    }

    private fun showDetails(slot: Int) {
        val name = prefs.getString(key(slot, "name"), "").orEmpty().ifBlank { "Entreprise $slot" }
        val siret = prefs.getString(key(slot, "siret"), "").orEmpty()
        val siren = prefs.getString(key(slot, "siren"), "").orEmpty()
        val address = prefs.getString(key(slot, "address"), "").orEmpty()
        val ape = prefs.getString(key(slot, "ape"), "").orEmpty()
        val idcc = prefs.getString(key(slot, "idcc"), "").orEmpty()
        val conventionName = prefs.getString(key(slot, "convention_name"), "").orEmpty()
        val agreements = prefs.getString(key(slot, "agreement_summary"), "").orEmpty()
        val convention = ConventionCatalog.findByIdcc(idcc)

        val text = buildString {
            append(if (slot == 1) "ENTREPRISE PRINCIPALE\n\n" else "ENTREPRISE 2\n\n")
            append(name)
            if (siret.isNotBlank()) append("\nSIRET : ").append(siret)
            if (siren.isNotBlank()) append("\nSIREN : ").append(siren)
            if (address.isNotBlank()) append("\nAdresse : ").append(address)
            if (ape.isNotBlank()) append("\nAPE/NAF : ").append(ape)
            if (idcc.isNotBlank()) {
                append("\n\nCONVENTION COLLECTIVE\n")
                append(conventionName.ifBlank { "IDCC $idcc" }).append("\nIDCC : ").append(idcc)
            }
            convention?.advantages?.takeIf { it.isNotEmpty() }?.let {
                append("\n\nAVANTAGES / GARANTIES\n")
                it.forEach { item -> append("• ").append(item).append('\n') }
            }
            convention?.cautions?.takeIf { it.isNotEmpty() }?.let {
                append("\nPOINTS DE VIGILANCE\n")
                it.forEach { item -> append("• ").append(item).append('\n') }
            }
            if (agreements.isNotBlank()) append("\nACCORDS D'ENTREPRISE\n").append(agreements)
        }
        val tv = TextView(context).apply {
            this.text = text.trim()
            textSize = 15f
            setPadding(dp(20), dp(12), dp(20), dp(20))
            setTextColor(themeColors().second)
        }
        val scroll = ScrollView(context).apply { addView(tv) }
        AlertDialog.Builder(context)
            .setTitle(name)
            .setView(scroll)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Légifrance") { _, _ -> openLegifrance(slot) }
            .show()
    }

    private fun confirmDelete(slot: Int) {
        val name = prefs.getString(key(slot, "name"), "").orEmpty().ifBlank { "Entreprise $slot" }
        AlertDialog.Builder(context)
            .setTitle("Supprimer $name ?")
            .setMessage("Les informations de cette entreprise seront supprimées. Les pointages déjà enregistrés restent conservés.")
            .setPositiveButton("Supprimer") { _, _ -> deleteSlot(slot) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun deleteSlot(slot: Int) {
        val editor = prefs.edit()
        listOf("siret", "siren", "name", "address", "ape", "idcc", "convention_name", "agreement_summary").forEach { editor.remove(key(slot, it)) }
        if (slot == 1) editor.remove("convention_idcc")
        editor.apply()
        restoreSlot(slot)
        Toast.makeText(context, "Entreprise $slot supprimée", Toast.LENGTH_SHORT).show()
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
                .distinct().take(10).toList()
            if (relevant.isEmpty()) "" else relevant.joinToString("\n") { "• $it" }
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
        val panel = if (dark) Color.parseColor("#1B1B1B") else Color.WHITE
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
            v.summary.setTextColor(text)
            listOf(v.search, v.details, v.delete).forEach { b ->
                b.backgroundTintList = ColorStateList.valueOf(panel)
                b.setTextColor(text)
            }
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
