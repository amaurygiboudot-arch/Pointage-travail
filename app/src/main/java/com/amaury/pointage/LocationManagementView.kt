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
        val groups = groupGpsZonesByPlace(readPersistedGpsZones(prefs), savedAddresses())
        when {
            groups == null -> addView(TextView(context).apply {
                text = "Configuration GPS à vérifier"
                textSize = 14f
                setTextColor(secondaryText())
                setPadding(0, dp(10), 0, dp(12))
            })
            groups.isEmpty() -> addView(TextView(context).apply {
                text = "Aucun lieu enregistré"
                textSize = 14f
                setTextColor(secondaryText())
                setPadding(0, dp(10), 0, dp(12))
            })
            else -> groups.forEach { group ->
                val entry = GpsLocationEntry(representativeGpsZoneId(group), group.address)
                addView(createPlaceCard(entry, group))
            }
        }
    }

    private fun arrivalContact(zoneId: String?, address: String): JSONObject? =
        resolveGpsZoneScopedObject(
            jsonObjectPreference("arrival_contacts"),
            readPersistedGpsZones(prefs),
            zoneId,
            address
        )

    private fun createPlaceCard(entry: GpsLocationEntry, group: GpsPlaceGroup): LinearLayout {
        val zoneId = entry.zoneId
        val address = entry.address
        val name = uniqueGpsPlaceLabel(group)
            ?: PlaceNames.get(context, zoneId, address)?.takeIf { it.isNotBlank() }
            ?: address
        val contact = if (group.zones.size <= 1) arrivalContact(zoneId, address) else null
        val contactName = contact?.optString("contactName")?.takeIf { it.isNotBlank() }
        val radius = zoneRadiusText(entry)
        val total = totalWorkedAtText(address)
        val sharedAddress = zonesAtAddressCount(address) > 1
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { showDetails(entry, group) }
            addView(TextView(context).apply { text = "📍 $name"; textSize = 16f; setTextColor(accentText()) })
            addView(TextView(context).apply { text = address; textSize = 14f; setTextColor(primaryText()); setPadding(0, dp(5), 0, 0) })
            if (contactName != null) addView(TextView(context).apply { text = "Contact : $contactName"; textSize = 14f; setTextColor(secondaryText()); setPadding(0, dp(7), 0, 0) })
            addView(TextView(context).apply {
                val roles = summarizeGpsPlaceTypes(group)
                val zoneSummary = if (group.legacyOnly) {
                    "Zone GPS à configurer"
                } else {
                    val roleParts = buildList {
                        if (roles.workZones > 0) add("${roles.workZones} travail")
                        if (roles.parkingZones > 0) add("${roles.parkingZones} parking")
                        if (roles.pauseZones > 0) add("${roles.pauseZones} pause")
                        if (roles.otherZones > 0) add("${roles.otherZones} autre")
                    }
                    "${group.zones.size} zone${if (group.zones.size > 1) "s" else ""} GPS • ${roleParts.joinToString(" / ")}"
                }
                text = "$zoneSummary   •   ${if (sharedAddress) "Rayons multiples" else "Rayon GPS : $radius"}   •   ${if (sharedAddress) "Temps à cette adresse" else "Temps travaillé"} : $total"
                textSize = 14f
                setTextColor(secondaryText())
                setPadding(0, dp(5), 0, 0)
            })
        }.also {
            it.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(panelColor()))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentText()); dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accentText()); dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accentText())
    }

    private fun showDetails(entry: GpsLocationEntry, group: GpsPlaceGroup) {
        val zoneId = entry.zoneId
        val address = entry.address
        val contact = if (group.zones.size <= 1) arrivalContact(zoneId, address) else null
        val name = uniqueGpsPlaceLabel(group)
            ?: PlaceNames.get(context, zoneId, address)?.takeIf { it.isNotBlank() }
            ?: address
        val contactName = contact?.optString("contactName")?.takeIf { it.isNotBlank() } ?: "Non renseigné"
        val phone = contact?.optString("phone")?.takeIf { it.isNotBlank() } ?: "Non renseigné"
        val notify = if (contact?.optBoolean("enabled", false) == true) "Oui" else "Non"
        val radius = zoneRadiusText(entry)
        val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(6), dp(20), 0); setBackgroundColor(panelColor()) }
        fun line(label: String, value: String): TextView = TextView(context).apply {
            text = "$label\n$value"; textSize = 14f; setTextColor(primaryText()); setPadding(0, dp(7), 0, dp(7)); content.addView(this)
        }
        line("Nom", name)
        line("Adresse", address)
        line("Contact", contactName)
        line("Téléphone", phone)
        line("Prévenir à l’arrivée", notify)
        line("Rayon GPS", radius)
        if (group.zones.isEmpty()) {
            line("Zones GPS", "Aucune zone canonique — configuration à terminer")
        } else {
            line("Zones GPS", group.zones.size.toString())
            group.zones.forEachIndexed { index, zone ->
                val type = zone.pointTypeToken?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "POSTE"
                val zoneName = zone.label?.takeIf { it.isNotBlank() } ?: "Zone ${index + 1}"
                line(zoneName, "$type • ${formatRadius(zone.radius)} • ID ${zone.id}")
            }
        }
        val totalLabel = if (zonesAtAddressCount(address) > 1) "Temps total à cette adresse" else "Temps total travaillé"
        val totalText = line(totalLabel, totalWorkedAtText(address))
        val dialog = AlertDialog.Builder(context)
            .setTitle(name)
            .setView(content)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Modifier") { _, _ -> showEdit(entry, group) }
            .setNegativeButton("Supprimer") { _, _ -> confirmDelete(entry, group, name) }
            .create()
        val handler = Handler(Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                totalText.text = "$totalLabel\n${totalWorkedAtText(address)}"
                handler.postDelayed(this, 10_000L)
            }
        }
        dialog.setOnShowListener { styleDialog(dialog); handler.post(updater) }
        dialog.setOnDismissListener { handler.removeCallbacks(updater); refresh() }
        dialog.show()
    }

    private fun showEdit(entry: GpsLocationEntry, group: GpsPlaceGroup) {
        val read = readPersistedGpsZones(prefs)
        if (read is GpsZonesReadResult.Corrupt) {
            GeofenceManager.reconfigureStoredZones(context)
            Toast.makeText(context, "Configuration GPS illisible : le lieu n’a pas été modifié", Toast.LENGTH_LONG).show()
            return
        }
        val oldAddress = entry.address
        val targetZoneId = entry.zoneId ?: resolveUniqueGpsZoneIdForAddress(read, oldAddress)
        val zones = read.toMutableJsonArrayOrNull() ?: return
        val targetZone = targetZoneId?.let { findZoneById(zones, it) }
        if (entry.zoneId != null && targetZone == null) {
            Toast.makeText(context, "Zone GPS introuvable : aucune modification effectuée", Toast.LENGTH_LONG).show()
            return
        }
        val contact = arrivalContact(targetZoneId, oldAddress)
        val nameInput = dialogInput("Nom du lieu", PlaceNames.get(context, targetZoneId, oldAddress).orEmpty())
        val addressInput = dialogInput("Adresse", oldAddress)
        val contactInput = dialogInput("Nom du contact", contact?.optString("contactName").orEmpty())
        val phoneInput = dialogInput("Téléphone", contact?.optString("phone").orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
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

                if (addressChanged) {
                    if (!moveGpsPlaceAddress(
                            zones,
                            oldAddress,
                            newAddress,
                            companyId = group.companyId,
                            companySlot = group.companySlot
                        ) && targetZoneId != null) {
                        Toast.makeText(context, "Lieu GPS introuvable : aucune modification effectuée", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                }

                val contacts = jsonObjectPreference("arrival_contacts")
                val contactValue = JSONObject()
                    .put("contactName", contactInput.text.toString().trim())
                    .put("phone", phoneInput.text.toString().trim())
                    .put("enabled", contact?.optBoolean("enabled", false) ?: false)
                if (targetZoneId != null) {
                    contacts.remove(oldAddress)
                    contacts.remove(newAddress)
                    putGpsZoneScopedObject(contacts, targetZoneId, newAddress, contactValue)
                } else {
                    contacts.remove(oldAddress)
                    contacts.put(newAddress, contactValue)
                }

                val names = jsonObjectPreference("address_names").apply {
                    remove(oldAddress)
                    if (addressChanged) remove(newAddress)
                }
                val overrides = jsonObjectPreference("zone_point_overrides")
                val confirmed = jsonObjectPreference("zone_point_confirmed")
                if (targetZoneId != null) {
                    overrides.remove(oldAddress); overrides.remove(newAddress)
                    confirmed.remove(oldAddress); confirmed.remove(newAddress)
                }

                val compatibilityAddresses = rebuiltAddressList(zones, savedAddresses(), oldAddress, newAddress)
                rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(compatibilityAddresses.joinToString("\n"))

                val companyMap = jsonObjectPreference("address_company_slots")
                if (targetZoneId == null && addressChanged) {
                    val legacySlot = companyMap.optInt(oldAddress, 0)
                    companyMap.remove(oldAddress)
                    if (legacySlot in 1..2) companyMap.put(newAddress, legacySlot)
                }

                val editor = prefs.edit()
                    .putString("address", compatibilityAddresses.joinToString("\n"))
                    .putString("address_names", names.toString())
                    .putString("arrival_contacts", contacts.toString())
                    .putString("address_company_slots", companyMap.toString())
                    .putString("zone_point_overrides", overrides.toString())
                    .putString("zone_point_confirmed", confirmed.toString())
                    .putString("zones", zones.toString())
                    .remove("active_zones")
                    .remove("entry_resolution_pending")
                    .remove("entry_resolution_token")
                    .remove("pending_exit_zones")
                if (addressChanged) editor.putString("pending_point_address", newAddress)
                editor.apply()

                PlaceNames.put(context, targetZoneId, newAddress, newName)
                GeofenceManager.reconfigureStoredZones(context)
                refresh()
                PointageWidgetProvider.updateAll(context)
                QuickActionsWidgetProvider.updateAll(context)
                Toast.makeText(
                    context,
                    if (addressChanged) "Adresse modifiée — vérifie maintenant le point GPS précis" else "Lieu mis à jour",
                    if (addressChanged) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun dialogInput(hintText: String, value: String): EditText = EditText(context).apply { hint = hintText; setText(value); setTextColor(primaryText()); setHintTextColor(secondaryText()) }
    private fun confirmDelete(entry: GpsLocationEntry, group: GpsPlaceGroup, name: String) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("Supprimer $name ?")
            .setMessage(
                if (group.zones.size > 1)
                    "Toutes les zones GPS de ce lieu seront retirées. Les autres lieux et l’historique déjà enregistré seront conservés."
                else
                    "Cette zone sera retirée du pointage GPS et de ses contacts. L’historique déjà enregistré sera conservé."
            )
            .setPositiveButton("Supprimer") { _, _ -> delete(entry, group) }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun delete(entry: GpsLocationEntry, group: GpsPlaceGroup) {
        val oldAddress = entry.address
        val read = readPersistedGpsZones(prefs)
        val zones = read.toMutableJsonArrayOrNull()
        if (zones == null) {
            GeofenceManager.reconfigureStoredZones(context)
            Toast.makeText(context, "Configuration GPS illisible : le lieu n’a pas été supprimé", Toast.LENGTH_LONG).show()
            return
        }

        val targetZoneId = entry.zoneId
        val remainingZones = when {
            group.zones.size > 1 -> removeGpsPlaceZones(
                zones,
                oldAddress,
                companyId = group.companyId,
                companySlot = group.companySlot
            )
            targetZoneId != null -> removeGpsZoneById(zones, targetZoneId)
            else -> zones
        }
        if (remainingZones == null) {
            Toast.makeText(context, "Zone GPS introuvable : aucune suppression effectuée", Toast.LENGTH_LONG).show()
            return
        }

        val addressStillUsed = jsonZonesUseAddress(remainingZones, oldAddress)
        val contacts = jsonObjectPreference("arrival_contacts").apply {
            targetZoneId?.let(::remove)
            if (!addressStillUsed) remove(oldAddress)
        }
        val overrides = jsonObjectPreference("zone_point_overrides").apply {
            targetZoneId?.let(::remove)
            if (!addressStillUsed) remove(oldAddress)
        }
        val confirmed = jsonObjectPreference("zone_point_confirmed").apply {
            targetZoneId?.let(::remove)
            if (!addressStillUsed) remove(oldAddress)
        }
        val names = jsonObjectPreference("address_names").apply { if (!addressStillUsed) remove(oldAddress) }
        val companyMap = jsonObjectPreference("address_company_slots").apply { if (!addressStillUsed) remove(oldAddress) }
        val compatibilityAddresses = rebuiltAddressListAfterDelete(remainingZones, savedAddresses(), oldAddress, addressStillUsed)
        rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(compatibilityAddresses.joinToString("\n"))

        val pending = prefs.getString("pending_point_address", "").orEmpty()
        val editor = prefs.edit()
            .putString("address", compatibilityAddresses.joinToString("\n"))
            .putString("address_names", names.toString())
            .putString("arrival_contacts", contacts.toString())
            .putString("address_company_slots", companyMap.toString())
            .putString("zone_point_overrides", overrides.toString())
            .putString("zone_point_confirmed", confirmed.toString())
            .putString("zones", remainingZones.toString())
            .remove("active_zones")
            .remove("entry_resolution_pending")
            .remove("entry_resolution_token")
            .remove("pending_exit_zones")
        if (!addressStillUsed && pending.equals(oldAddress, ignoreCase = true)) editor.remove("pending_point_address")
        editor.apply()

        registerZones()
        refresh()
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        Toast.makeText(
            context,
            if (group.zones.size > 1) "Lieu et zones supprimés. Historique conservé." else "Zone supprimée. Historique conservé.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun findZoneById(zones: JSONArray, zoneId: String): JSONObject? {
        val target = zoneId.trim()
        if (target.isBlank()) return null
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            if (zone.optString("id").trim() == target) return zone
        }
        return null
    }

    private fun zonesAtAddressCount(address: String): Int {
        val read = readPersistedGpsZones(prefs) as? GpsZonesReadResult.Valid ?: return 0
        return read.zones.count {
            it.address?.trim()?.equals(address.trim(), ignoreCase = true) == true
        }
    }

    private fun zoneRadiusText(entry: GpsLocationEntry): String {
        val read = readPersistedGpsZones(prefs)
        val zoneId = entry.zoneId?.trim().orEmpty()
        if (zoneId.isNotBlank()) {
            val valid = read as? GpsZonesReadResult.Valid ?: return "À vérifier"
            val zone = valid.zones.firstOrNull { it.id == zoneId } ?: return "À vérifier"
            return formatRadius(zone.radius)
        }
        return zoneRadiusText(entry.address)
    }

    private fun formatRadius(meters: Float): String =
        if (meters % 1f == 0f) "${meters.toInt()} m"
        else String.format(Locale.FRANCE, "%.1f m", meters)

    private fun jsonZonesUseAddress(zones: JSONArray, address: String): Boolean {
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            if (zone.optString("address").trim().equals(address.trim(), ignoreCase = true)) return true
        }
        return false
    }

    private fun rebuiltAddressList(
        zones: JSONArray,
        previous: List<String>,
        oldAddress: String,
        newAddress: String
    ): List<String> {
        val canonicalAddresses = (0 until zones.length()).mapNotNull { index ->
            zones.optJSONObject(index)?.optString("address")?.trim()?.takeIf { it.isNotBlank() }
        }
        val oldStillUsed = canonicalAddresses.any { it.equals(oldAddress, ignoreCase = true) }
        val legacy = previous
            .filterNot { !oldStillUsed && it.equals(oldAddress, ignoreCase = true) }
            .toMutableList()
        if (legacy.none { it.equals(newAddress, ignoreCase = true) }) legacy += newAddress
        canonicalAddresses.forEach { address ->
            if (legacy.none { it.equals(address, ignoreCase = true) }) legacy += address
        }
        return legacy.distinctBy { it.lowercase(Locale.FRANCE) }.take(10)
    }

    private fun rebuiltAddressListAfterDelete(
        zones: JSONArray,
        previous: List<String>,
        deletedAddress: String,
        addressStillUsed: Boolean
    ): List<String> {
        val result = previous
            .filterNot { !addressStillUsed && it.equals(deletedAddress, ignoreCase = true) }
            .toMutableList()
        for (index in 0 until zones.length()) {
            val address = zones.optJSONObject(index)?.optString("address")?.trim().orEmpty()
            if (address.isNotBlank() && result.none { it.equals(address, ignoreCase = true) }) result += address
        }
        return result.distinctBy { it.lowercase(Locale.FRANCE) }.take(10)
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
