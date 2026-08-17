package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class LocationManagementView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)

    init {
        orientation = VERTICAL
        refresh()
    }

    private fun darkMode(): Boolean {
        val appearance = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        return when (appearance.getString("mode", "auto") ?: "auto") {
            "light" -> false
            "dark" -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun panelColor() = Color.parseColor(if (darkMode()) "#181818" else "#FFFFFF")
    private fun primaryText() = Color.parseColor(if (darkMode()) "#FFFFFF" else "#111111")
    private fun secondaryText() = Color.parseColor(if (darkMode()) "#F0ECE4" else "#3A3A3A")
    private fun accentText() = Color.parseColor(if (darkMode()) "#F3D58A" else "#795600")

    fun refresh() {
        removeAllViews()

        addView(TextView(context).apply {
            text = "MES LIEUX DE TRAVAIL"
            textSize = 16f
            setTextColor(accentText())
            setPadding(0, dp(18), 0, dp(8))
        })

        val addresses = savedAddresses()
        if (addresses.isEmpty()) {
            addView(TextView(context).apply {
                text = "Aucun lieu enregistré"
                textSize = 15f
                setTextColor(secondaryText())
                setPadding(0, dp(10), 0, dp(12))
            })
        } else {
            addresses.forEach { address ->
                addView(createPlaceCard(address))
            }
        }

        val add = AddAddressButton(context).apply {
            text = "+ NOUVEAU LIEU"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            backgroundTintList = ColorStateList.valueOf(panelColor())
            setTextColor(accentText())
        }
        addView(add, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
    }

    private fun createPlaceCard(address: String): LinearLayout {
        val name = PlaceNames.get(context, address)?.takeIf { it.isNotBlank() } ?: "Lieu sans nom"
        val contacts = runCatching {
            JSONObject(prefs.getString("arrival_contacts", "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        val contact = contacts.optJSONObject(address)
        val contactName = contact?.optString("contactName")?.takeIf { it.isNotBlank() }
        val radius = prefs.getInt("radius", 150)
        val total = totalWorkedAt(address)

        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.hp_panel)
            backgroundTintList = ColorStateList.valueOf(panelColor())
            isClickable = true
            isFocusable = true
            setOnClickListener { showDetails(address) }

            addView(TextView(context).apply {
                text = "📍 $name"
                textSize = 17f
                setTextColor(accentText())
            })

            addView(TextView(context).apply {
                text = address
                textSize = 14f
                setTextColor(primaryText())
                setPadding(0, dp(5), 0, 0)
            })

            if (contactName != null) {
                addView(TextView(context).apply {
                    text = "Contact : $contactName"
                    textSize = 13f
                    setTextColor(secondaryText())
                    setPadding(0, dp(7), 0, 0)
                })
            }

            addView(TextView(context).apply {
                text = "Rayon GPS : $radius m   •   Temps : ${formatDuration(total)}"
                textSize = 13f
                setTextColor(secondaryText())
                setPadding(0, dp(5), 0, 0)
            })
        }.also { card ->
            card.layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun showDetails(address: String) {
        val contacts = runCatching { JSONObject(prefs.getString("arrival_contacts", "{}") ?: "{}") }.getOrElse { JSONObject() }
        val contact = contacts.optJSONObject(address)
        val name = PlaceNames.get(context, address) ?: "Lieu sans nom"
        val contactName = contact?.optString("contactName")?.takeIf { it.isNotBlank() } ?: "Non renseigné"
        val phone = contact?.optString("phone")?.takeIf { it.isNotBlank() } ?: "Non renseigné"
        val notify = if (contact?.optBoolean("enabled", false) == true) "Oui" else "Non"
        val radius = prefs.getInt("radius", 150)
        val total = totalWorkedAt(address)

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        fun line(label: String, value: String) {
            content.addView(TextView(context).apply {
                text = "$label\n$value"
                textSize = 15f
                setTextColor(primaryText())
                setPadding(0, dp(7), 0, dp(7))
            })
        }
        line("Nom", name)
        line("Adresse", address)
        line("Contact", contactName)
        line("Téléphone", phone)
        line("Prévenir à l'arrivée", notify)
        line("Rayon GPS", "$radius m")
        line("Temps total enregistré", formatDuration(total))

        AlertDialog.Builder(context)
            .setTitle(name)
            .setView(content)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Modifier") { _, _ -> showEdit(address) }
            .setNegativeButton("Supprimer") { _, _ -> confirmDelete(address, name) }
            .show()
    }

    private fun showEdit(oldAddress: String) {
        val contacts = runCatching { JSONObject(prefs.getString("arrival_contacts", "{}") ?: "{}") }.getOrElse { JSONObject() }
        val contact = contacts.optJSONObject(oldAddress)
        val nameInput = dialogInput("Nom du lieu", PlaceNames.get(context, oldAddress).orEmpty())
        val addressInput = dialogInput("Adresse", oldAddress)
        val contactInput = dialogInput("Nom du contact", contact?.optString("contactName").orEmpty())
        val phoneInput = dialogInput("Téléphone", contact?.optString("phone").orEmpty()).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(nameInput)
            addView(addressInput)
            addView(contactInput)
            addView(phoneInput)
        }

        AlertDialog.Builder(context)
            .setTitle("Modifier le lieu")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val newAddress = addressInput.text.toString().trim()
                val newName = nameInput.text.toString().trim()
                if (newAddress.isBlank()) return@setPositiveButton

                val addresses = savedAddresses()
                    .map { if (it.equals(oldAddress, true)) newAddress else it }
                    .distinctBy { it.lowercase() }
                    .take(10)

                prefs.edit().putString("address", addresses.joinToString("\n")).apply()
                rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(addresses.joinToString("\n"))

                val names = runCatching { JSONObject(prefs.getString("address_names", "{}") ?: "{}") }.getOrElse { JSONObject() }
                names.remove(oldAddress)
                names.put(newAddress, newName)
                prefs.edit().putString("address_names", names.toString()).apply()

                val enabled = contact?.optBoolean("enabled", false) ?: false
                contacts.remove(oldAddress)
                contacts.put(
                    newAddress,
                    JSONObject()
                        .put("contactName", contactInput.text.toString().trim())
                        .put("phone", phoneInput.text.toString().trim())
                        .put("enabled", enabled)
                )
                prefs.edit().putString("arrival_contacts", contacts.toString()).apply()

                refresh()
                Toast.makeText(context, "Lieu modifié", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun dialogInput(hintText: String, value: String): EditText = EditText(context).apply {
        hint = hintText
        setText(value)
        setTextColor(primaryText())
        setHintTextColor(secondaryText())
    }

    private fun confirmDelete(address: String, name: String) {
        AlertDialog.Builder(context)
            .setTitle("Supprimer $name ?")
            .setMessage("Le lieu sera retiré des zones GPS et des contacts. L'historique de pointage déjà enregistré sera conservé.")
            .setPositiveButton("Supprimer") { _, _ -> delete(address) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun delete(address: String) {
        val addresses = savedAddresses().filterNot { it.equals(address, true) }
        prefs.edit().putString("address", addresses.joinToString("\n")).apply()
        rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(addresses.joinToString("\n"))

        val names = runCatching { JSONObject(prefs.getString("address_names", "{}") ?: "{}") }.getOrElse { JSONObject() }
        names.remove(address)
        val contacts = runCatching { JSONObject(prefs.getString("arrival_contacts", "{}") ?: "{}") }.getOrElse { JSONObject() }
        contacts.remove(address)

        val oldZones = runCatching { JSONArray(prefs.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val newZones = JSONArray()
        for (i in 0 until oldZones.length()) {
            val zone = oldZones.optJSONObject(i) ?: continue
            if (!zone.optString("address").equals(address, true)) newZones.put(zone)
        }

        prefs.edit()
            .putString("address_names", names.toString())
            .putString("arrival_contacts", contacts.toString())
            .putString("zones", newZones.toString())
            .remove("active_zones")
            .apply()

        GeofenceManager.remove(context)
        refresh()
        PointageWidgetProvider.updateAll(context)
        Toast.makeText(context, "Lieu supprimé. Historique conservé.", Toast.LENGTH_LONG).show()
    }

    private fun savedAddresses(): List<String> = prefs
        .getString("address", "")
        .orEmpty()
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

    private fun totalWorkedAt(address: String): Long {
        val data = PointageStore.load(context)
        var total = 0L
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (!item.optString("zoneAddress").trim().equals(address.trim(), true)) continue
            if (item.isNull("exit")) continue
            total += (item.optLong("exit") - item.optLong("entry")).coerceAtLeast(0L)
        }
        return total
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60000L
        return String.format(Locale.FRANCE, "%dh %02d", minutes / 60L, minutes % 60L)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
