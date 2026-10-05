package com.amaury.pointage

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.*
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.engine.AnalyticsEngineV2
import com.amaury.pointage.v2.engine.TimeEngineV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.util.Locale
import java.util.UUID

/** Valide l'ensemble de l'employeur avant de réduire le total aux zones affichées. */
internal fun gpsPlacePaidTimeV2(
    sessions: List<WorkSessionV2>, companyId: String, zoneIds: Set<String>,
    timeEngine: TimeEngineV2, nowMs: Long
): Long? {
    val employerSessions = sessions.filter { it.employerId == companyId }
    if (employerSessions.any { it.placeId.isNullOrBlank() } ||
        sessions.any { it.placeId in zoneIds && it.employerId != companyId }) return null
    // Un chevauchement entre deux lieux du même employeur invalide aussi leurs sous-totaux.
    if (!AnalyticsEngineV2.summarize(employerSessions, timeEngine, nowMs).timeTotalsReliable) return null
    return AnalyticsEngineV2.summarize(
        employerSessions.filter { it.placeId in zoneIds }, timeEngine, nowMs
    ).takeIf { it.timeTotalsReliable }?.totalPaidMs
}

/** Les actions de zone utilisent exclusivement le store GPS V2 et un instantané vérifié. */
class LocationManagementView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
    init { orientation = VERTICAL; refresh() }
    private fun theme() = AppThemeCatalog.current(context)
    private fun darkMode() = AppThemeCatalog.useDarkPalette(context)
    private fun panelColor() = if (darkMode()) theme().darkPanel else theme().lightPanel
    private fun primaryText() = if (darkMode()) theme().darkText else theme().lightText
    private fun accentText() = if (darkMode()) theme().accentLight else theme().accent
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(this)
        refresh()
    }
    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetachedFromWindow()
    }
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key in setOf("zones", "address", "arrival_contacts", "address_names")) post { refresh() }
    }

    private fun text(value: String) = TextView(context).apply {
        text = value; textSize = 14f; setTextColor(primaryText()); setPadding(0, dp(6), 0, dp(6))
    }
    private fun action(value: String, run: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 14f; minHeight = dp(48)
        setTextColor(accentText()); setBackgroundResource(R.drawable.hp_panel)
        setOnClickListener { run() }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun box() = LinearLayout(context).apply {
        orientation = VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(10))
    }
    private fun scroll(content: LinearLayout) = ScrollView(context).apply { addView(content) }
    private fun style(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(panelColor()))
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { dialog.getButton(it)?.setTextColor(accentText()) }
    }
    private fun notice(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    private fun companyLabel(group: GpsPlaceGroup): String {
        val id = group.companyId
        if (id == null) return if (group.companySlot == null) "Sans association automatique" else "Ancienne entreprise ${group.companySlot} — à confirmer"
        val stored = SalaryCompanyStore.readConfirmed(context)
        return if (stored.reliable) stored.companies.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank)
            ?: "Entreprise à confirmer ($id)" else "Entreprise à vérifier ($id)"
    }
    private fun groupTitle(group: GpsPlaceGroup) = uniqueGpsPlaceLabel(group) ?: group.address

    fun refresh() {
        removeAllViews()
        addView(text("MES LIEUX ET ZONES GPS").apply { textSize = 16f; setTextColor(accentText()) })
        val snapshot = GpsZoneEditorStoreV2.read(prefs)
        if (snapshot == null) { addView(text("Configuration GPS à vérifier — aucune donnée modifiée")); return }
        val groups = snapshot.groups()
        if (groups.isEmpty()) { addView(text("Aucun lieu enregistré. Utilise Ajouter un lieu.")); return }
        for (group in groups) {
            val roles = summarizeGpsPlaceTypes(group)
            val description = if (group.legacyOnly) "Zone GPS à configurer" else
                "${group.zones.size} zones : ${roles.workZones} travail, ${roles.parkingZones} parking, ${roles.pauseZones} pause, ${roles.otherZones} à confirmer"
            addView(action("${companyLabel(group)}\n${groupTitle(group)}\n${group.address}\n$description") { showPlace(group.scope()) })
        }
    }

    private fun showPlace(scope: GpsPlaceScopeV2) {
        val snapshot = GpsZoneEditorStoreV2.read(prefs) ?: return notice("Configuration GPS à vérifier")
        val group = snapshot.groups().singleOrNull { it.scope() == scope } ?: return notice("Ce lieu a changé. Rouvre sa fiche.")
        val content = box()
        content.addView(text(companyLabel(group)))
        content.addView(text(group.address))
        content.addView(text("Chaque zone conserve son propre nom, centre, rayon et contact. La présence GPS ne décide pas du temps payé."))
        val totalText = text("Temps attribué aux zones actuelles : ${totalWorkedAtText(group)}")
        content.addView(totalText)
        val dialog = AlertDialog.Builder(context).setTitle(groupTitle(group)).setView(scroll(content))
            .setPositiveButton("Fermer", null).create()
        group.zones.forEach { zone ->
            content.addView(action("${zone.label ?: "Zone sans nom"}\n${zone.roleForContextV2().title} • ${zone.radius} m") {
                dialog.dismiss(); editZone(snapshot, group, zone.id)
            })
            content.addView(action("Ajuster sur la carte — ${zone.label ?: "cette zone"}") {
                val picker = rootView.findViewById<GpsPointPickerView>(R.id.gpsPointPickerView)
                if (picker == null) notice("Carte indisponible ici. Les coordonnées restent modifiables dans la fiche de zone.")
                else { dialog.dismiss(); picker.adjustZone(zone.id) }
            })
        }
        content.addView(action("Ajouter une zone à ce lieu") { dialog.dismiss(); editZone(snapshot, group, null) })
        content.addView(action("Modifier l'adresse du lieu") { dialog.dismiss(); editAddress(snapshot, group) })
        content.addView(action("Supprimer ce lieu et ses zones") { dialog.dismiss(); confirmDelete(snapshot, group, null) })
        val handler = Handler(Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                totalText.text = "Temps attribué aux zones actuelles : ${totalWorkedAtText(group)}"
                handler.postDelayed(this, 10_000L)
            }
        }
        dialog.setOnShowListener { style(dialog); handler.post(updater) }
        dialog.setOnDismissListener { handler.removeCallbacks(updater) }
        dialog.show()
    }

    private fun input(label: String, value: String, numeric: Boolean = false): EditText = EditText(context).apply {
        hint = label; setText(value); textSize = 16f; minHeight = dp(48); setTextColor(primaryText())
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            else InputType.TYPE_CLASS_TEXT
    }
    private fun field(content: LinearLayout, label: String, value: String, numeric: Boolean = false): EditText {
        content.addView(text(label))
        return input(label, value, numeric).also(content::addView)
    }
    private fun editZone(snapshot: GpsZoneEditorStoreV2.Snapshot, group: GpsPlaceGroup, zoneId: String?) {
        val zone = zoneId?.let { id -> group.zones.singleOrNull { it.id == id } ?: return }
        if (zone == null && group.companyId == null && group.companySlot != null) {
            notice("Confirme d'abord l'association V2 de cette ancienne entreprise."); return
        }
        val content = box()
        content.addView(text("${companyLabel(group)}\n${group.address}"))
        val name = field(content, "Nom de la zone", zone?.label.orEmpty())
        val lat = field(content, "Latitude", zone?.latitude?.toString().orEmpty(), true)
        val lon = field(content, "Longitude", zone?.longitude?.toString().orEmpty(), true)
        val radius = field(content, "Rayon de cette zone, en mètres (50–1 000)", zone?.radius?.toString().orEmpty(), true)
        content.addView(action("Utiliser ma position actuelle") {
            val location = recentLocation()
            if (location == null) notice("Position précise récente indisponible. Les coordonnées restent inchangées.")
            else { lat.setText(location.latitude.toString()); lon.setText(location.longitude.toString()) }
        })
        content.addView(action("Choisir le centre sur la carte") {
            val picker = rootView.findViewById<GpsPointPickerView>(R.id.gpsPointPickerView)
            if (picker == null) notice("Carte indisponible ici. Saisis les coordonnées ou utilise Ma position.")
            else picker.selectDraftPoint("${name.text.toString().ifBlank { "Nouvelle zone" }} — ${group.address}",
                lat.text.toString().replace(',', '.').toDoubleOrNull(),
                lon.text.toString().replace(',', '.').toDoubleOrNull()) { latitude, longitude ->
                    lat.setText(latitude.toString()); lon.setText(longitude.toString())
                }
        })
        content.addView(text("Rôle de la zone"))
        val roles = GpsZoneRoleV2.values()
        val role = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, roles.map { it.title })
            setSelection(roles.indexOf(zone?.roleForContextV2() ?: GpsZoneRoleV2.OTHER))
        }
        content.addView(role)
        content.addView(text("Une zone Pause reste une observation à confirmer : sa présence ne crée pas automatiquement une pause rémunérée."))
        val contact = zone?.let { snapshot.contact(it) }
        val contactName = field(content, "Nom du contact", contact?.optString("contactName").orEmpty())
        val phone = field(content, "Téléphone", contact?.optString("phone").orEmpty()).apply { inputType = InputType.TYPE_CLASS_PHONE }
        val notify = Switch(context).apply { text = "Proposer de prévenir à l'arrivée"; isChecked = contact?.optBoolean("enabled", false) == true }
        content.addView(notify)
        val builder = AlertDialog.Builder(context).setTitle(if (zone == null) "Ajouter une zone" else "Modifier cette zone")
            .setView(scroll(content)).setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null)
        if (zone != null) builder.setNeutralButton("Supprimer cette zone") { _, _ -> confirmDelete(snapshot, group, zone.id) }
        val dialog = builder.create()
        val generatedId = UUID.randomUUID().toString()
        dialog.setOnShowListener {
            style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun number(field: EditText) = field.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN
                val draft = GpsZoneDraftV2(name.text.toString(), number(lat), number(lon), number(radius),
                    roles[role.selectedItemPosition], contactName.text.toString(), phone.text.toString(), notify.isChecked)
                val error = draft.error()
                if (error != null) { notice(error); return@setOnClickListener }
                if (group.companyId != null) {
                    val companies = SalaryCompanyStore.readConfirmed(context)
                    if (!companies.reliable || companies.companies.none { it.id == group.companyId }) {
                        notice("Entreprise V2 non confirmée : aucune nouvelle configuration enregistrée."); return@setOnClickListener
                    }
                }
                val change = GpsZoneEditorStoreV2.saveZone(snapshot, group, zoneId, generatedId, draft, 10)
                if (change == null) { notice("Zone invalide, lieu modifié ou limite des 10 zones atteinte. Aucune zone écrasée."); return@setOnClickListener }
                if (save(change, "Zone enregistrée")) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun recentLocation(): android.location.Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time in 0L..60_000L && it.hasAccuracy() && it.accuracy > 0f && it.accuracy <= 50f &&
                it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0 }
            .maxByOrNull { it.time }
    }
    private fun editAddress(snapshot: GpsZoneEditorStoreV2.Snapshot, group: GpsPlaceGroup) {
        val content = box()
        val address = field(content, "Adresse du lieu", group.address)
        content.addView(text("Seules les zones de ce lieu et de cette entreprise seront concernées. Leurs centres GPS et l'historique ne changent pas."))
        val dialog = AlertDialog.Builder(context).setTitle("Modifier l'adresse")
            .setView(scroll(content)).setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
        dialog.setOnShowListener {
            style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val change = GpsZoneEditorStoreV2.renameAddress(snapshot, group, address.text.toString())
                if (change == null) notice("Adresse vide, lieu modifié ou déjà existant pour cette entreprise.")
                else if (save(change, "Adresse modifiée, centres GPS conservés")) dialog.dismiss()
            }
        }
        dialog.show()
    }
    private fun confirmDelete(snapshot: GpsZoneEditorStoreV2.Snapshot, group: GpsPlaceGroup, zoneId: String?) {
        val dialog = AlertDialog.Builder(context).setTitle(if (zoneId == null) "Supprimer ce lieu ?" else "Supprimer cette zone ?")
            .setMessage(if (zoneId == null) "Les ${group.zones.size} zones de ce lieu seront retirées. Les autres entreprises et l'historique seront conservés."
                else "Seule cette zone et ses réglages seront retirés. Les autres zones et l'historique seront conservés.")
            .setPositiveButton("Supprimer", null).setNegativeButton("Annuler", null).create()
        dialog.setOnShowListener {
            style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val change = GpsZoneEditorStoreV2.remove(snapshot, group, zoneId)
                if (change == null) notice("Ce lieu ou cette zone a changé. Aucune suppression effectuée.")
                else if (save(change, "Suppression enregistrée. Historique conservé")) dialog.dismiss()
            }
        }
        dialog.show()
    }
    private fun save(change: GpsZoneEditorStoreV2.Change, message: String): Boolean {
        if (!GpsZoneEditorStoreV2.commit(prefs, change)) {
            notice("Configuration modifiée depuis l'ouverture ou écriture non confirmée. Rouvre la fiche avant de réessayer.")
            return false
        }
        GpsZoneEditorStoreV2.read(prefs)?.let { saved ->
            rootView.findViewById<EditText>(R.id.workplaceAddress)?.let { field ->
                val addresses = saved.addresses().joinToString("\n")
                if (field.text.toString() != addresses) field.setText(addresses)
            }
        }
        refresh()
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        GeofenceManager.reconfigureStoredZones(context) { success, detail ->
            post { notice(if (success) message else "$message. $detail") }
        }
        return true
    }
    private fun totalWorkedAtText(group: GpsPlaceGroup): String {
        if (!HoraTrackV2.ENABLED || group.companyId == null || group.zones.isEmpty()) return "À confirmer"
        val now = System.currentTimeMillis()
        val runtime = V2RuntimeReader.allSessions(context, now)
        if (!runtime.reliable) return "À vérifier"
        val ids = group.zones.map { it.id }.toSet()
        // Une session non rattachée ne permet pas de certifier un total complet pour ce lieu.
        if (runtime.sessions.any { it.employerId == group.companyId && it.placeId.isNullOrBlank() }) return "À confirmer"
        if (runtime.sessions.any { it.placeId in ids && it.employerId != group.companyId }) return "À vérifier"
        val paidMs = gpsPlacePaidTimeV2(runtime.sessions, group.companyId, ids, HoraTrackV2.time, now)
            ?: return "À confirmer"
        val minutes = paidMs / 60_000L
        return String.format(Locale.FRANCE, "%dh %02d", minutes / 60L, minutes % 60L)
    }
}
