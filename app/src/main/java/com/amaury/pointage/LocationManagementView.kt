package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
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

    private fun darkMode(): Boolean = AppThemeCatalog.useDarkPalette(context)
    private fun theme() = AppThemeCatalog.current(context)
    private fun panelColor() = if (darkMode()) theme().darkPanel else theme().lightPanel
    private fun primaryText() = if (darkMode()) theme().darkText else theme().lightText
    private fun secondaryText() = if (darkMode()) theme().darkHint else theme().lightHint
    private fun accentText() = if (darkMode()) theme().accentLight else theme().accent

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
                textSize = 14f
                setTextColor(secondaryText())
                setPadding(0, dp(10), 0, dp(12))
            })
        } else addresses.forEach { address -> addView(createPlaceCard(address)) }
    }

    private fun createPlaceCard(address: String): LinearLayout {
        val name = PlaceNames.get(context, address)?.takeIf { it.isNotBlank() } ?: "Lieu sans nom"
        val contacts = jsonObjectPreference("arrival_contacts")
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
                textSize = 16f
                setTextColor(accentText())
            })
            addView(TextView(context).apply {
                text = address
                textSize = 14f
                setTextColor(primaryText())
                setPadding(0, dp(5), 0, 0)
            })
            if (contactName != null) addView(TextView(context).apply {
                text = "Contact : $contactName"
                textSize = 14f
                setTextColor(secondaryText())
                setPadding(0, dp(7), 0, 0)
            })
            addView(TextView(context).apply {
                text = "Rayon GPS : $radius m   •   Temps travaillé : ${formatDuration(total)}"
                textSize = 14f
                setTextColor(secondaryText())
                setPadding(0, dp(5), 0, 0)
            })
        }.also { card ->
            card.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(panelColor()))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentText())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accentText())
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accentText())
    }

    private fun showDetails(address: String) {
        val contacts = jsonObjectPreference("arrival_contacts")
        val contact = contacts.optJSONObject(address)
        val name = PlaceNames.get(context, address) ?: "Lieu sans nom"
        val contactName = contact?.optString("contactName")?.takeIf { it.isNotBlank() } ?: "Non renseigné"
        val phone = contact?.optString("phone")?.takeIf { it.isNotBlank() } ?: "Non renseigné"
        val notify = if (contact?.optBoolean("enabled", false) == true) "Oui" else "Non"
        val radius = prefs.getInt("radius", 150)

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            setBackgroundColor(panelColor())
        }
        fun line(label: String, value: String): TextView = TextView(context).apply {
            text = "$label\n$value"
            textSize = 14f
            setTextColor(primaryText())
            setPadding(0, dp(7), 0, dp(7))
            content.addView(this)
        }
        line("Nom", name)
        line("Adresse", address)
        line("Contact", contactName)
        line("Téléphone", phone)
        line("Prévenir à l'arrivée", notify)
        line("Rayon GPS", "$radius m")
        val totalText = line("Temps total travaillé", formatDuration(totalWorkedAt(address)))

        val dialog = AlertDialog.Builder(context)
            .setTitle(name)
            .setView(content)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Modifier") { _, _ -> showEdit(address) }
            .setNegativeButton("Supprimer") { _, _ -> confirmDelete(address, name) }
            .create()

        val handler = Handler(Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                totalText.text = "Temps total travaillé\n${formatDuration(totalWorkedAt(address))}"
                handler.postDelayed(this, 10_000L)
            }
        }
        dialog.setOnShowListener {
            styleDialog(dialog)
            handler.post(updater)
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(updater)
            refresh()
        }
        dialog.show()
    }

    private fun showEdit(oldAddress: String) {
        val contacts = jsonObjectPreference("arrival_contacts")
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
            setBackgroundColor(panelColor())
            addView(nameInput); addView(addressInput); addView(contactInput); addView(phoneInput)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Modifier le lieu")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val newAddress = addressInput.text.toString().trim()
                val newName = nameInput.text.toString().trim()
                if (newAddress.isBlank()) return@setPositiveButton
                val addressChanged = !newAddress.equals(oldAddress, ignoreCase = true)

                val addresses = savedAddresses()
                    .map { if (it.equals(oldAddress, true)) newAddress else it }
                    .distinctBy { it.lowercase(Locale.FRANCE) }
                    .take(10)
                rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(addresses.joinToString("\n"))

                val names = jsonObjectPreference("address_names")
                names.remove(oldAddress)
                if (newName.isNotBlank()) names.put(newAddress, newName)

                val enabled = contact?.optBoolean("enabled", false) ?: false
                contacts.remove(oldAddress)
                contacts.put(newAddress, JSONObject()
                    .put("contactName", contactInput.text.toString().trim())
                    .put("phone", phoneInput.text.toString().trim())
                    .put("enabled", enabled))

                val companyMap = jsonObjectPreference("address_company_slots")
                val oldCompanySlot = companyMap.optInt(oldAddress, 0)
                companyMap.remove(oldAddress)
                if (oldCompanySlot > 0) companyMap.put(newAddress, oldCompanySlot)

                val overrides = jsonObjectPreference("zone_point_overrides")
                val confirmed = jsonObjectPreference("zone_point_confirmed")
                if (addressChanged) {
                    val oldPoint = overrides.optJSONObject(oldAddress)
                    overrides.remove(oldAddress)
                    if (oldPoint != null) overrides.put(newAddress, JSONObject(oldPoint.toString()))
                    confirmed.remove(oldAddress)
                    confirmed.remove(newAddress)
                }

                val oldZones = runCatching { JSONArray(prefs.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }
                if (addressChanged) {
                    for (i in 0 until oldZones.length()) {
                        val zone = oldZones.optJSONObject(i) ?: continue
                        if (zone.optString("address").trim().equals(oldAddress, ignoreCase = true)) {
                            zone.put("address", newAddress)
                            break
                        }
                    }
                }

                val editor = prefs.edit()
                    .putString("address", addresses.joinToString("\n"))
                    .putString("address_names", names.toString())
                    .putString("arrival_contacts", contacts.toString())
                    .putString("address_company_slots", companyMap.toString())
                    .putString("zone_point_overrides", overrides.toString())
                    .putString("zone_point_confirmed", confirmed.toString())
                    .putString("zones", oldZones.toString())
                    .remove("active_zones")

                if (addressChanged) editor.putString("pending_point_address", newAddress)
                editor.apply()

                refresh()
                PointageWidgetProvider.updateAll(context)
                QuickActionsWidgetProvider.updateAll(context)
                if (addressChanged) {
                    Toast.makeText(context, "Adresse modifiée — vérifie maintenant le point GPS précis", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Lieu mis à jour", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun dialogInput(hintText: String, value: String): EditText = EditText(context).apply {
        hint = hintText
        setText(value)
        setTextColor(primaryText())
        setHintTextColor(secondaryText())
    }

    private fun confirmDelete(address: String, name: String) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("Supprimer $name ?")
            .setMessage("Le lieu sera retiré des zones GPS et des contacts. L'historique déjà enregistré sera conservé.")
            .setPositiveButton("Supprimer") { _, _ -> delete(address) }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun delete(address: String) {
        val addresses = savedAddresses().filterNot { it.equals(address, true) }
        rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(addresses.joinToString("\n"))

        val names = jsonObjectPreference("address_names").apply { remove(address) }
        val contacts = jsonObjectPreference("arrival_contacts").apply { remove(address) }
        val companyMap = jsonObjectPreference("address_company_slots").apply { remove(address) }
        val overrides = jsonObjectPreference("zone_point_overrides").apply { remove(address) }
        val confirmed = jsonObjectPreference("zone_point_confirmed").apply { remove(address) }

        val oldZones = runCatching { JSONArray(prefs.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val newZones = JSONArray()
        for (i in 0 until oldZones.length()) {
            val zone = oldZones.optJSONObject(i) ?: continue
            if (!zone.optString("address").equals(address, true)) newZones.put(zone)
        }

        val pending = prefs.getString("pending_point_address", "").orEmpty()
        val editor = prefs.edit()
            .putString("address", addresses.joinToString("\n"))
            .putString("address_names", names.toString())
            .putString("arrival_contacts", contacts.toString())
            .putString("address_company_slots", companyMap.toString())
            .putString("zone_point_overrides", overrides.toString())
            .putString("zone_point_confirmed", confirmed.toString())
            .putString("zones", newZones.toString())
            .remove("active_zones")
        if (pending.equals(address, ignoreCase = true)) editor.remove("pending_point_address")
        editor.apply()

        if (addresses.isEmpty()) {
            GeofenceManager.remove(context)
        } else {
            registerZones(newZones)
        }

        refresh()
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        Toast.makeText(context, "Lieu supprimé. Historique conservé.", Toast.LENGTH_LONG).show()
    }

    private fun registerZones(list: JSONArray) {
        if (!prefs.getBoolean("enabled", false)) return
        if (!GeofenceManager.hasRequiredPermissions(context)) return
        val workZones = mutableListOf<WorkZone>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val lat = item.optDouble("latitude", Double.NaN)
            val lon = item.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            val radius = item.optDouble("radius", prefs.getInt("radius", 150).toDouble())
                .toFloat().coerceIn(50f, 1000f)
            workZones += WorkZone(id, lat, lon, radius)
        }
        if (workZones.isEmpty()) GeofenceManager.unregisterAll(context)
        else GeofenceManager.registerAll(context, workZones) { _, _ -> }
    }

    private fun jsonObjectPreference(key: String): JSONObject =
        runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrElse { JSONObject() }

    private fun savedAddresses(): List<String> = prefs.getString("address", "")
        .orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.FRANCE) }

    private fun totalWorkedAt(address: String): Long {
        val data = PointageStore.load(context)
        val now = System.currentTimeMillis()
        var total = 0L
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val storedPlace = item.optString("zoneAddress").trim()
            if (!matchesAddress(storedPlace, address)) continue
            val entry = item.optLong("entry", 0L)
            if (entry <= 0L) continue
            val end = if (item.isNull("exit")) now else item.optLong("exit", entry)
            total += PointageStore.workedDuration(item, end)
        }
        return total
    }

    private fun matchesAddress(storedPlace: String, address: String): Boolean {
        val wanted = address.trim()
        if (storedPlace.equals(wanted, true)) return true
        val marker = " — "
        return storedPlace.contains(marker) && storedPlace.substringAfterLast(marker).trim().equals(wanted, true)
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0L) / 60000L
        return String.format(Locale.FRANCE, "%dh %02d", minutes / 60L, minutes % 60L)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
