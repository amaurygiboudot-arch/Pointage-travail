package com.amaury.pointage

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
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

/**
 * Ajout d'une entreprise pour l'espace Salaire V2.
 *
 * Contrairement à EnterpriseLookupView, cette vue n'expose aucun emplacement
 * "Entreprise 1 / Entreprise 2" : chaque recherche valide crée/met à jour une
 * vraie entrée de SalaryCompanyStore, qui est illimité et déduplique par SIRET.
 */
class V2SalaryCompanyLookupView(
    context: Context,
    private val onSaved: () -> Unit
) : LinearLayout(context) {

    private val siretInput = EditText(context)
    private val status = TextView(context)
    private val searchButton = Button(context)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))

        addView(TextView(context).apply {
            text = "AJOUTER UNE ENTREPRISE"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "Entre le SIRET à 14 chiffres. L'entreprise sera ajoutée directement à MES ENTREPRISES."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(10))
        })

        siretInput.apply {
            hint = "SIRET — 14 chiffres"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        addView(siretInput, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        searchButton.apply {
            text = "RECHERCHER ET AJOUTER"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { lookupAndSave() }
        }
        addView(searchButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) })

        status.textSize = 13f
        status.setPadding(0, dp(8), 0, 0)
        addView(status)
    }

    private fun lookupAndSave() {
        val siret = siretInput.text.toString().filter(Char::isDigit)
        if (siret.length != 14) {
            Toast.makeText(context, "Le SIRET doit contenir exactement 14 chiffres", Toast.LENGTH_LONG).show()
            return
        }

        searchButton.isEnabled = false
        searchButton.text = "RECHERCHE…"
        status.text = "Recherche dans les données publiques…"

        Thread {
            try {
                val encoded = URLEncoder.encode(siret, StandardCharsets.UTF_8.name())
                val connection = URL("https://recherche-entreprises.api.gouv.fr/search?q=$encoded&per_page=1").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "HoraTrack-Android")
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
                val establishment = findMatchingEstablishment(result, siret)
                val address = firstNonBlank(
                    establishment?.optString("adresse"),
                    establishment?.optString("adresse_complete"),
                    result.optString("adresse")
                )
                val idcc = findIdccs(result).firstOrNull().orEmpty()
                val conventionName = ConventionCatalog.findByIdcc(idcc)?.fullName.orEmpty()

                SalaryCompanyStore.upsert(
                    context,
                    SalaryCompanyStore.Company(
                        id = "siret_$siret",
                        name = name.ifBlank { "Entreprise" },
                        siret = siret,
                        address = address,
                        conventionName = conventionName,
                        idcc = idcc
                    )
                )

                post {
                    searchButton.isEnabled = true
                    searchButton.text = "RECHERCHER ET AJOUTER"
                    status.text = "${name.ifBlank { "Entreprise" }} ajoutée à MES ENTREPRISES."
                    Toast.makeText(context, "Entreprise ajoutée", Toast.LENGTH_LONG).show()
                    onSaved()
                }
            } catch (e: Exception) {
                post {
                    searchButton.isEnabled = true
                    searchButton.text = "RECHERCHER ET AJOUTER"
                    status.text = "Impossible d'ajouter l'entreprise : ${e.message ?: "erreur inconnue"}"
                }
            }
        }.start()
    }

    private fun findMatchingEstablishment(result: JSONObject, siret: String): JSONObject? {
        val matching = result.optJSONArray("matching_etablissements") ?: JSONArray()
        for (i in 0 until matching.length()) {
            val item = matching.optJSONObject(i) ?: continue
            if (item.optString("siret").filter(Char::isDigit) == siret) return item
        }
        val siege = result.optJSONObject("siege")
        if (siege?.optString("siret")?.filter(Char::isDigit) == siret) return siege
        return matching.optJSONObject(0) ?: siege
    }

    private fun findIdccs(result: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        val complements = result.optJSONArray("complements")
        if (complements != null) {
            for (i in 0 until complements.length()) {
                val item = complements.optJSONObject(i) ?: continue
                collectIdcc(item, out)
            }
        }
        collectIdcc(result, out)
        return out.toList()
    }

    private fun collectIdcc(obj: JSONObject, out: MutableSet<String>) {
        listOf("idcc", "idcc_principal").forEach { key ->
            obj.optString(key).filter(Char::isDigit).takeIf { it.isNotBlank() }?.let(out::add)
        }
        val ids = obj.optJSONArray("idccs") ?: return
        for (i in 0 until ids.length()) {
            val value = ids.optString(i).filter(Char::isDigit)
            if (value.isNotBlank()) out.add(value)
        }
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
