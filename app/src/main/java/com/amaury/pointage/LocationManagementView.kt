package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.engine.AnalyticsEngineV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class LocationManagementView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)

    init { orientation = VERTICAL; refresh() }

    private fun darkMode(): Boolean = AppThemeCatalog.useDarkPalette(context)
    private fun theme() = AppThemeCatalog.current(context)
    private fun panelColor() = if (darkMode()) theme().darkPanel else theme().lightPanel
    private fun primaryText() = if (darkMode()) theme().darkText else theme().lightText
    private fun secondaryText() = if (darkMode()) theme().darkHint else theme().lightHint
    private fun accentText() = if (darkMode()) theme().accentLight else theme().accent

    fun refresh() {
        removeAllViews()
        addView(TextView(context).apply { text = "MES LIEUX DE TRAVAIL"; textSize = 16f; setTextColor(accentText()); setPadding(0, dp(18), 0, dp(8)) })
        val addresses = savedAddresses()
        if (addresses.isEmpty()) addView(TextView(context).apply { text = "Aucun lieu enregistré"; textSize = 14f; setTextColor(secondaryText()); setPadding(0, dp(10), 0, dp(12)) })
        else addresses.forEach { addView(createPlaceCard(it)) }
    }

    private fun createPlaceCard(address: String): LinearLayout {
        val zoneId = resolveUniqueGpsZoneIdForAddress(readPersistedGpsZones(prefs), address)
        val name = PlaceNames.get(context, zoneId, address)?.takeIf { it.isNotBlank() } ?: "Lieu sans nom"
        val contacts = jsonObjectPreference("arrival_contacts")
        val contact = contacts.optJSONObject(address)
        val contactName = contact?.optString("contactName")?.takeIf { it.isNotBlank() }
        val radius = zoneRadiusText(address)
        val total = totalWorkedAtText(address)
        return LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            // Pas de panneau blanc : la photo/le thème reste visible derrière les informations.
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true; isFocusable = true; setOnClickListener { showDetails(address) }
            addView(TextView(context).apply { text = "📍 $name"; textSize = 16f; setTextColor(accentText()) })
            addView(TextView(context).apply { text = address; textSize = 14f; setTextColor(primaryText()); setPadding(0, dp(5), 0, 0) })
            if (contactName != null) addView(TextView(context).apply { text = "Contact : $contactName"; textSize = 14f; setTextColor(secondaryText()); setPadding(0, dp(7), 0, 0) })
            addView(TextView(context).apply { text = "Rayon GPS : $radius   •   Temps travaillé : $total"; textSize = 14f; setTextColor(secondaryText()); setPadding(0, dp(5), 0, 0) })
        }.also { it.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(panelColor()))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentText()); dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accentText()); dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accentText())
    }

    private fun showDetails(address: String) {
        val zoneId = resolveUniqueGpsZoneIdForAddress(readPersistedGpsZones(prefs), address)
        val contacts = jsonObjectPreference("arrival_contacts"); val contact = contacts.optJSONObject(address); val name = PlaceNames.get(context, zoneId, address) ?: "Lieu sans nom"
        val contactName = contact?.optString("contactName")?.takeIf { it.isNotBlank() } ?: "Non renseigné"; val phone = contact?.optString("phone")?.takeIf { it.isNotBlank() } ?: "Non renseigné"; val notify = if (contact?.optBoolean("enabled", false) == true) "Oui" else "Non"; val radius = zoneRadiusText(address)
        val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(6), dp(20), 0); setBackgroundColor(panelColor()) }
        fun line(label: String, value: String): TextView = TextView(context).apply { text = "$label\n$value"; textSize = 14f; setTextColor(primaryText()); setPadding(0, dp(7), 0, dp(7)); content.addView(this) }
        line("Nom", name); line("Adresse", address); line("Contact", contactName); line("Téléphone", phone); line("Prévenir à l'arrivée", notify); line("Rayon GPS", radius); val totalText = line("Temps total travaillé", totalWorkedAtText(address))
        val dialog = AlertDialog.Builder(context).setTitle(name).setView(content).setPositiveButton("Fermer", null).setNeutralButton("Modifier") { _, _ -> showEdit(address) }.setNegativeButton("Supprimer") { _, _ -> confirmDelete(address, name) }.create()
        val handler = Handler(Looper.getMainLooper()); val updater = object : Runnable { override fun run() { if (!dialog.isShowing) return; totalText.text = "Temps total travaillé\n${totalWorkedAtText(address)}"; handler.postDelayed(this, 10_000L) } }
        dialog.setOnShowListener { styleDialog(dialog); handler.post(updater) }; dialog.setOnDismissListener { handler.removeCallbacks(updater); refresh() }; dialog.show()
    }

    private fun showEdit(oldAddress: String) {
        if (readPersistedGpsZones(prefs) is GpsZonesReadResult.Corrupt) {
            GeofenceManager.reconfigureStoredZones(context)
            Toast.makeText(context, "Configuration GPS illisible : le lieu n'a pas été modifié", Toast.LENGTH_LONG).show()
            return
        }
        val zoneId = resolveUniqueGpsZoneIdForAddress(readPersistedGpsZones(prefs), oldAddress)
        val contacts = jsonObjectPreference("arrival_contacts"); val contact = contacts.optJSONObject(oldAddress); val nameInput = dialogInput("Nom du lieu", PlaceNames.get(context, zoneId, oldAddress).orEmpty()); val addressInput = dialogInput("Adresse", oldAddress); val contactInput = dialogInput("Nom du contact", contact?.optString("contactName").orEmpty()); val phoneInput = dialogInput("Téléphone", contact?.optString("phone").orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(6), dp(20), 0); setBackgroundColor(panelColor()); addView(nameInput); addView(addressInput); addView(contactInput); addView(phoneInput) }
        val dialog = AlertDialog.Builder(context).setTitle("Modifier le lieu").setView(box).setPositiveButton("Enregistrer") { _, _ ->
            val newAddress = addressInput.text.toString().trim(); val newName = nameInput.text.toString().trim(); if (newAddress.isBlank()) return@setPositiveButton; val addressChanged = !newAddress.equals(oldAddress, ignoreCase = true)
            val oldZones = readPersistedGpsZones(prefs).toMutableJsonArrayOrNull()
            if (oldZones == null) {
                GeofenceManager.reconfigureStoredZones(context)
                Toast.makeText(context, "Configuration GPS illisible : le lieu n'a pas été modifié", Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            val addresses = savedAddresses().map { if (it.equals(oldAddress, true)) newAddress else it }.distinctBy { it.lowercase(Locale.FRANCE) }.take(10); rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(addresses.joinToString("\n"))
            val names = jsonObjectPreference("address_names").apply {
                remove(oldAddress)
                if (!newAddress.equals(oldAddress, ignoreCase = true)) remove(newAddress)
            }
            val targetZoneIds = (0 until oldZones.length()).mapNotNull { index ->
                oldZones.optJSONObject(index)
                    ?.takeIf { it.optString("address").trim().equals(oldAddress, ignoreCase = true) }
                    ?.optString("id")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            val targetZoneId = targetZoneIds.singleOrNull()
            val enabled = contact?.optBoolean("enabled", false) ?: false; contacts.remove(oldAddress); contacts.put(newAddress, JSONObject().put("contactName", contactInput.text.toString().trim()).put("phone", phoneInput.text.toString().trim()).put("enabled", enabled))
            val companyMap = jsonObjectPreference("address_company_slots"); val oldCompanySlot = companyMap.optInt(oldAddress, 0); companyMap.remove(oldAddress); if (oldCompanySlot > 0) companyMap.put(newAddress, oldCompanySlot)
            val overrides = jsonObjectPreference("zone_point_overrides"); val confirmed = jsonObjectPreference("zone_point_confirmed"); if (addressChanged) { val oldPoint = overrides.optJSONObject(oldAddress); overrides.remove(oldAddress); if (oldPoint != null) overrides.put(newAddress, JSONObject(oldPoint.toString())); confirmed.remove(oldAddress); confirmed.remove(newAddress) }
            if (addressChanged) for (i in 0 until oldZones.length()) { val zone = oldZones.optJSONObject(i) ?: continue; if (zone.optString("address").trim().equals(oldAddress, ignoreCase = true)) { zone.put("address", newAddress); break } }
            val editor = prefs.edit().putString("address", addresses.joinToString("\n")).putString("address_names", names.toString()).putString("arrival_contacts", contacts.toString()).putString("address_company_slots", companyMap.toString()).putString("zone_point_overrides", overrides.toString()).putString("zone_point_confirmed", confirmed.toString()).putString("zones", oldZones.toString()).remove("active_zones").remove("entry_resolution_pending").remove("entry_resolution_token").remove("pending_exit_zones"); if (addressChanged) editor.putString("pending_point_address", newAddress); editor.apply()
            PlaceNames.put(context, targetZoneId, newAddress, newName)
            GeofenceManager.reconfigureStoredZones(context); refresh(); PointageWidgetProvider.updateAll(context); QuickActionsWidgetProvider.updateAll(context); Toast.makeText(context, if (addressChanged) "Adresse modifiée — vérifie maintenant le point GPS précis" else "Lieu mis à jour", if (addressChanged) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Annuler", null).create(); dialog.setOnShowListener { styleDialog(dialog) }; dialog.show()
    }

    private fun dialogInput(hintText: String, value: String): EditText = EditText(context).apply { hint = hintText; setText(value); setTextColor(primaryText()); setHintTextColor(secondaryText()) }
    private fun confirmDelete(address: String, name: String) { val dialog = AlertDialog.Builder(context).setTitle("Supprimer $name ?").setMessage("Le lieu sera retiré des zones GPS et des contacts. L'historique déjà enregistré sera conservé.").setPositiveButton("Supprimer") { _, _ -> delete(address) }.setNegativeButton("Annuler", null).create(); dialog.setOnShowListener { styleDialog(dialog) }; dialog.show() }
    private fun delete(address: String) {
        val oldZones = readPersistedGpsZones(prefs).toMutableJsonArrayOrNull()
        if (oldZones == null) {
            GeofenceManager.reconfigureStoredZones(context)
            Toast.makeText(context, "Configuration GPS illisible : le lieu n'a pas été supprimé", Toast.LENGTH_LONG).show()
            return
        }
        val deletedZoneIds = (0 until oldZones.length()).mapNotNull { index ->
            oldZones.optJSONObject(index)
                ?.takeIf { it.optString("address").trim().equals(address.trim(), ignoreCase = true) }
                ?.optString("id")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        val addresses = savedAddresses().filterNot { it.equals(address, true) }; rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(addresses.joinToString("\n")); val names = jsonObjectPreference("address_names").apply { remove(address) }; val contacts = jsonObjectPreference("arrival_contacts").apply { remove(address) }; val companyMap = jsonObjectPreference("address_company_slots").apply { remove(address) }; val overrides = jsonObjectPreference("zone_point_overrides").apply { remove(address); deletedZoneIds.forEach(::remove) }; val confirmed = jsonObjectPreference("zone_point_confirmed").apply { remove(address); deletedZoneIds.forEach(::remove) }
        val newZones = JSONArray(); for (i in 0 until oldZones.length()) { val zone = oldZones.optJSONObject(i) ?: continue; if (!zone.optString("address").equals(address, true)) newZones.put(zone) }
        val pending = prefs.getString("pending_point_address", "").orEmpty(); val editor = prefs.edit().putString("address", addresses.joinToString("\n")).putString("address_names", names.toString()).putString("arrival_contacts", contacts.toString()).putString("address_company_slots", companyMap.toString()).putString("zone_point_overrides", overrides.toString()).putString("zone_point_confirmed", confirmed.toString()).putString("zones", newZones.toString()).remove("active_zones").remove("entry_resolution_pending").remove("entry_resolution_token").remove("pending_exit_zones"); if (pending.equals(address, ignoreCase = true)) editor.remove("pending_point_address"); editor.apply()
        registerZones(); refresh(); PointageWidgetProvider.updateAll(context); QuickActionsWidgetProvider.updateAll(context); Toast.makeText(context, "Lieu supprimé. Historique conservé.", Toast.LENGTH_LONG).show()
    }
    private fun registerZones() {
        GeofenceManager.reconfigureStoredZones(context)
    }
    private fun jsonObjectPreference(key: String): JSONObject = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrElse { JSONObject() }
    private fun savedAddresses(): List<String> = prefs.getString("address", "").orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.FRANCE) }
    private fun zoneRadiusText(address: String): String =
        when (val resolution = resolveGpsZoneRadiusForAddress(readPersistedGpsZones(prefs), address)) {
            is GpsZoneRadiusResolution.Known -> {
                val meters = resolution.radiusMeters
                if (meters % 1f == 0f) "${meters.toInt()} m" else String.format(Locale.FRANCE, "%.1f m", meters)
            }
            GpsZoneRadiusResolution.Missing -> "À confirmer"
            GpsZoneRadiusResolution.Ambiguous,
            GpsZoneRadiusResolution.Corrupt -> "À vérifier"
        }
    private fun totalWorkedAtText(address: String): String {
        if (!HoraTrackV2.ENABLED) return formatDuration(legacyTotalWorkedAt(address))
        val now = System.currentTimeMillis()
        val runtime = V2RuntimeReader.allSessions(context, now)
        if (!runtime.reliable) return "À vérifier"
        val analytics = AnalyticsEngineV2.summarize(runtime.sessions, HoraTrackV2.time, now)
        val place = AnalyticsEngineV2.placeTotalForAddress(analytics, address)
        return if (place.reliable) formatDuration(place.paidMs) else "À confirmer"
    }
    private fun legacyTotalWorkedAt(address: String): Long { val data = PointageStore.load(context); val now = System.currentTimeMillis(); var total = 0L; for (i in 0 until data.length()) { val item = data.optJSONObject(i) ?: continue; val storedPlace = item.optString("zoneAddress").trim(); if (!AnalyticsEngineV2.matchesAddress(storedPlace, address)) continue; val entry = item.optLong("entry", 0L); if (entry <= 0L) continue; val end = if (item.isNull("exit")) now else item.optLong("exit", entry); total += PointageStore.workedDuration(item, end) }; return total }
    private fun formatDuration(ms: Long): String { val minutes = ms.coerceAtLeast(0L) / 60000L; return String.format(Locale.FRANCE, "%dh %02d", minutes / 60L, minutes % 60L) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
