package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.EnterpriseIdccParserV2
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Ajout d'une entreprise V2 par nom OU SIRET. */
class V2SalaryCompanyLookupView(
    context: Context,
    private val onSaved: () -> Unit
) : LinearLayout(context) {

    private val queryInput = EditText(context)
    private val status = TextView(context)
    private val searchButton = Button(context)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        addView(TextView(context).apply { text = "AJOUTER UNE ENTREPRISE"; textSize = 17f; setTypeface(typeface, Typeface.BOLD) })
        addView(TextView(context).apply { text = "Écris le nom de l'entreprise OU son SIRET à 14 chiffres."; textSize = 13f; setPadding(0, dp(6), 0, dp(10)) })
        queryInput.apply { hint = "Nom de l'entreprise ou SIRET"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; isSingleLine = true }
        addView(queryInput, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        searchButton.apply { text = "RECHERCHER"; isAllCaps = false; setBackgroundResource(R.drawable.hp_panel); setOnClickListener { search() } }
        addView(searchButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) })
        status.textSize = 14f; status.setPadding(0, dp(10), 0, 0); addView(status)
    }

    private fun search() {
        val query = queryInput.text.toString().trim()
        if (query.length < 2) { Toast.makeText(context, "Écris un nom d'entreprise ou un SIRET", Toast.LENGTH_LONG).show(); return }
        val digits = query.filter(Char::isDigit)
        if (query.all { it.isDigit() || it.isWhitespace() } && digits.length != 14) { Toast.makeText(context, "Un SIRET doit contenir exactement 14 chiffres", Toast.LENGTH_LONG).show(); return }
        setSearching(true)
        Thread {
            try {
                val encoded = URLEncoder.encode(if (digits.length == 14) digits else query, StandardCharsets.UTF_8.name())
                val perPage = if (digits.length == 14) 1 else 10
                val connection = URL("https://recherche-entreprises.api.gouv.fr/search?q=$encoded&per_page=$perPage").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"; connection.connectTimeout = 10000; connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "HoraTrack-Android")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(); connection.disconnect()
                if (code !in 200..299) throw IllegalStateException("service indisponible ($code)")
                val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
                if (results.length() == 0) throw IllegalStateException("aucune entreprise trouvée")
                val choices = (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { parseCompany(it, if (digits.length == 14) digits else "") } }
                if (choices.isEmpty()) throw IllegalStateException("résultat incomplet")
                post { setSearching(false); if (choices.size == 1) saveCompany(choices.first()) else showChoices(choices) }
            } catch (e: Exception) {
                post { setSearching(false); status.text = "Impossible de trouver l'entreprise : ${e.message ?: "erreur inconnue"}" }
            }
        }.start()
    }

    private fun showChoices(companies: List<SalaryCompanyStore.Company>) {
        val labels = companies.map { c -> buildString { append(c.name.ifBlank { "Entreprise" }); if (c.siret.isNotBlank()) append("\nSIRET : ").append(c.siret); if (c.address.isNotBlank()) append("\n").append(c.address) } }.toTypedArray()
        AlertDialog.Builder(context).setTitle("Choisis l'entreprise").setItems(labels) { _, which -> companies.getOrNull(which)?.let(::saveCompany) }.setNegativeButton("ANNULER", null).show()
    }

    private fun saveCompany(company: SalaryCompanyStore.Company) {
        val saved = SalaryCompanyStore.upsert(context, company)
        val reread = SalaryCompanyStore.list(context).firstOrNull {
            it.id == company.id || (company.siret.isNotBlank() && it.siret == company.siret)
        }
        if (!saved || reread == null) {
            status.text = "ERREUR : l'entreprise n'a pas été enregistrée."
            status.setTypeface(status.typeface, Typeface.BOLD)
            searchButton.text = "RÉESSAYER"
            Toast.makeText(context, "Échec de l'enregistrement", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "✓ ${reread.name.ifBlank { "Entreprise" }} ENREGISTRÉE\n${if (reread.siret.isBlank()) "" else "SIRET : ${reread.siret}"}"
        status.setTypeface(status.typeface, Typeface.BOLD)
        searchButton.text = "AJOUTÉE ✓"
        Toast.makeText(context, "${reread.name.ifBlank { "Entreprise" }} enregistrée", Toast.LENGTH_LONG).show()
        onSaved()
    }

    private fun parseCompany(result: JSONObject, requestedSiret: String): SalaryCompanyStore.Company {
        val name = firstNonBlank(result.optString("nom_complet"), result.optString("nom_raison_sociale"), result.optString("denomination"), result.optString("nom"))
        val establishment = if (requestedSiret.isNotBlank()) findMatchingEstablishment(result, requestedSiret) else result.optJSONObject("siege")
        val siret = firstNonBlank(establishment?.optString("siret"), result.optJSONObject("siege")?.optString("siret")).filter(Char::isDigit)
        val siren = result.optString("siren").filter(Char::isDigit)
        val address = firstNonBlank(establishment?.optString("adresse"), establishment?.optString("adresse_complete"), result.optString("adresse"))
        val idcc = EnterpriseIdccParserV2.find(result, siret).firstOrNull().orEmpty()
        val conventionName = ConventionCatalog.findByIdcc(idcc)?.fullName.orEmpty()
        val id = when { siret.isNotBlank() -> "siret_$siret"; siren.isNotBlank() -> "siren_$siren"; else -> "name_${name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}" }
        return SalaryCompanyStore.Company(id, name.ifBlank { "Entreprise" }, siret, address, conventionName, idcc)
    }

    private fun setSearching(searching: Boolean) { searchButton.isEnabled = !searching; searchButton.text = if (searching) "RECHERCHE…" else "RECHERCHER"; if (searching) status.text = "Recherche dans les données publiques…" }
    private fun findMatchingEstablishment(result: JSONObject, siret: String): JSONObject? {
        val matching = result.optJSONArray("matching_etablissements") ?: JSONArray()
        for (i in 0 until matching.length()) { val item = matching.optJSONObject(i) ?: continue; if (item.optString("siret").filter(Char::isDigit) == siret) return item }
        val siege = result.optJSONObject("siege"); if (siege?.optString("siret")?.filter(Char::isDigit) == siret) return siege
        return matching.optJSONObject(0) ?: siege
    }
    private fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
