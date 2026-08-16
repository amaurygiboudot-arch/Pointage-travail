package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class LocationManagementView(context: Context) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)

    init {
        orientation = VERTICAL
        refresh()
    }

    fun refresh() {
        removeAllViews()
        addView(TextView(context).apply {
            text = "MES LIEUX DE TRAVAIL"
            textSize = 16f
            setTextColor(Color.parseColor("#D6A84B"))
            setPadding(0, dp(18), 0, dp(8))
        })

        val add = AddAddressButton(context).apply {
            text = "+ NOUVEAU LIEU"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            setTextColor(Color.parseColor("#F3D58A"))
        }
        addView(add)

        val addresses = savedAddresses()
        if (addresses.isEmpty()) {
            addView(TextView(context).apply {
                text = "Aucun lieu enregistré"
                setTextColor(Color.parseColor("#A99F8C"))
                setPadding(0, dp(10), 0, 0)
            })
            return
        }

        addresses.forEach { address ->
            val name = PlaceNames.get(context, address) ?: "Lieu sans nom"
            val button = Button(context).apply {
                text = "$name\n$address"
                isAllCaps = false
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundResource(R.drawable.hp_panel)
                setTextColor(Color.parseColor("#F4EFE3"))
                setOnClickListener { showDetails(address) }
            }
            addView(button, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
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
        val nameInput = EditText(context).apply { hint = "Nom du lieu"; setText(PlaceNames.get(context, oldAddress).orEmpty()) }
        val addressInput = EditText(context).apply { hint = "Adresse"; setText(oldAddress) }
        val contactInput = EditText(context).apply { hint = "Nom du contact"; setText(contact?.optString("contactName").orEmpty()) }
        val phoneInput = EditText(context).apply { hint = "Téléphone"; inputType = android.text.InputType.TYPE_CLASS_PHONE; setText(contact?.optString("phone").orEmpty()) }
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(6), dp(20), 0); addView(nameInput); addView(addressInput); addView(contactInput); addView(phoneInput) }

        AlertDialog.Builder(context).setTitle("Modifier le lieu").setView(box).setPositiveButton("Enregistrer") { _, _ ->
            val newAddress = addressInput.text.toString().trim()
            val newName = nameInput.text.toString().trim()
            if (newAddress.isBlank()) return@setPositiveButton
            val addresses = savedAddresses().map { if (it.equals(oldAddress, true)) newAddress else it }.distinctBy { it.lowercase() }.take(10)
            prefs.edit().putString("address", addresses.joinToString("\n")).apply()
            rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(addresses.joinToString("\n"))

            val names = runCatching { JSONObject(prefs.getString("address_names", "{}") ?: "{}") }.getOrElse { JSONObject() }
            names.remove(oldAddress); names.put(newAddress, newName)
            prefs.edit().putString("address_names", names.toString()).apply()

            val enabled = contact?.optBoolean("enabled", false) ?: false
            contacts.remove(oldAddress)
            contacts.put(newAddress, JSONObject().put("contactName", contactInput.text.toString().trim()).put("phone", phoneInput.text.toString().trim()).put("enabled", enabled))
            prefs.edit().putString("arrival_contacts", contacts.toString()).apply()
            refresh()
            Toast.makeText(context, "Lieu modifié", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Annuler", null).show()
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

    private fun savedAddresses(): List<String> = prefs.getString("address", "").orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }

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
