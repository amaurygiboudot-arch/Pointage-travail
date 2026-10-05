package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.amaury.pointage.v2.HoraTrackV2
import java.util.Locale
import java.util.UUID

/** Création du premier point d'un lieu ; l'association ne dépend jamais de l'adresse seule. */
class AddAddressButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Button(context, attrs) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        super.setOnClickListener { showAddressDialog() }
    }

    private fun showAddressDialog() {
        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        fun notice(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        val initial = GpsZoneEditorStoreV2.read(gpsPrefs)
        if (initial == null) { notice("Configuration GPS à vérifier : aucun lieu ne sera ajouté"); return }
        if (initial.zones.size >= 10) { notice("10 zones GPS maximum. Modifie ou retire une zone existante avant d'en ajouter."); return }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        fun label(value: String) = TextView(context).apply { text = value; textSize = 14f; setPadding(0, dp(6), 0, dp(4)) }
        fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(context).apply {
            this.hint = hint; inputType = type; isSingleLine = true; minHeight = dp(48)
        }
        val useV2EmployerBinding = HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)
        val companyGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val companyByButtonId = linkedMapOf<Int, String?>()
        val legacySlotByButtonId = linkedMapOf<Int, Int>()
        val companyDisplayByButtonId = linkedMapOf<Int, String>()
        if (useV2EmployerBinding) {
            val none = RadioButton(context).apply {
                id = View.generateViewId(); minHeight = dp(48); textSize = 15f
                text = "Aucune association automatique — garder l'entreprise choisie au pointage"
            }
            companyGroup.addView(none)
            companyByButtonId[none.id] = null
            companyDisplayByButtonId[none.id] = "sans association automatique"
            val stored = SalaryCompanyStore.readConfirmed(context)
            if (!stored.reliable) {
                container.addView(label("Entreprises V2 à vérifier : ce lieu peut être enregistré sans association automatique."))
            } else stored.companies.forEach { company ->
                val button = RadioButton(context).apply {
                    id = View.generateViewId(); minHeight = dp(48); textSize = 15f
                    text = buildString {
                        append(company.name.ifBlank { "Entreprise" })
                        if (company.siret.isNotBlank()) append(" — SIRET ${company.siret}")
                    }
                }
                companyGroup.addView(button)
                companyByButtonId[button.id] = company.id
                companyDisplayByButtonId[button.id] = company.name.ifBlank { "Entreprise" }
            }
        } else {
            val salaryPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
            val company1Name = salaryPrefs.getString("company_name", "").orEmpty().ifBlank { "Entreprise 1" }
            val company2Name = salaryPrefs.getString("company2_name", "").orEmpty().ifBlank { "Entreprise 2" }
            listOf(1 to company1Name, 2 to company2Name).forEach { (slot, name) ->
                val button = RadioButton(context).apply {
                    id = View.generateViewId(); text = "Entreprise $slot — $name"; minHeight = dp(48); textSize = 15f
                }
                companyGroup.addView(button)
                legacySlotByButtonId[button.id] = slot
                companyDisplayByButtonId[button.id] = name
            }
        }
        val placeName = field("Nom du lieu / client", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val contactName = field("Nom du contact", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val phone = field("Téléphone du contact", InputType.TYPE_CLASS_PHONE)
        val notifyOnArrival = Switch(context).apply { text = "Proposer de prévenir ce contact à l'arrivée"; minHeight = dp(48) }
        val street = field("N° et rue — ex. 12 rue des Lilas", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
        val postalCode = field("Code postal — ex. 50400", InputType.TYPE_CLASS_NUMBER)
        val city = field("Ville — ex. Granville", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val decimalType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val latitude = field("Latitude (facultative avant recherche de l'adresse)", decimalType)
        val longitude = field("Longitude (facultative avant recherche de l'adresse)", decimalType)
        val radius = field("Rayon GPS, en mètres (50–1 000)", decimalType).apply {
            val prior = (gpsPrefs.all["radius"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 50.0..1000.0 }
            setText((prior ?: 150.0).toString())
        }
        val roles = GpsZoneRoleV2.values()
        val role = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, roles.map { it.title })
            setSelection(roles.indexOf(GpsZoneRoleV2.OTHER))
        }
        var selectedMapPoint: Pair<Double, Double>? = null
        val mapButton = Button(context).apply {
            text = "Choisir le centre sur la carte"; isAllCaps = false; minHeight = dp(48)
            setOnClickListener {
                val picker = rootView.findViewById<GpsPointPickerView>(R.id.gpsPointPickerView)
                if (picker == null) notice("Carte indisponible ici. Saisis les coordonnées ou utilise la recherche d'adresse.")
                else picker.selectDraftPoint(placeName.text.toString().trim().ifBlank { "Nouveau lieu" },
                    latitude.text.toString().replace(',', '.').toDoubleOrNull(),
                    longitude.text.toString().replace(',', '.').toDoubleOrNull()) { lat, lon ->
                        selectedMapPoint = lat to lon
                        latitude.setText(lat.toString()); longitude.setText(lon.toString())
                    }
            }
        }
        listOf(label("Entreprise associée"), companyGroup, placeName, contactName, phone, notifyOnArrival,
            street, postalCode, city, label("Rôle de la première zone"), role,
            label("Chaque lieu peut ensuite contenir plusieurs zones. Une pause GPS ne décide pas de sa rémunération."),
            radius, label("Centre GPS : carte, coordonnées ou recherche de l'adresse"), latitude, longitude, mapButton)
            .forEach(container::addView)
        val dialog = AlertDialog.Builder(context).setTitle("Ajouter un lieu et sa première zone")
            .setView(ScrollView(context).apply { addView(container) })
            .setNegativeButton("Annuler", null).setPositiveButton("Ajouter", null).create()
        val generatedId = UUID.randomUUID().toString()
        fun setControls(view: View, enabled: Boolean) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (i in 0 until view.childCount) setControls(view.getChildAt(i), enabled)
        }
        fun busy(value: Boolean) {
            setControls(container, !value)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = !value; text = if (value) "LOCALISATION…" else "Ajouter"
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = companyGroup.checkedRadioButtonId
                if (selected == -1 || (useV2EmployerBinding && !companyByButtonId.containsKey(selected)) ||
                    (!useV2EmployerBinding && !legacySlotByButtonId.containsKey(selected))) {
                    notice(if (useV2EmployerBinding) "Choisis une entreprise ou Aucune association automatique" else "Choisis Entreprise 1 ou Entreprise 2")
                    return@setOnClickListener
                }
                val companyId = companyByButtonId[selected]
                val companySlot = legacySlotByButtonId[selected]
                val companyName = companyDisplayByButtonId[selected] ?: "sans association automatique"
                val name = placeName.text.toString().trim()
                val road = street.text.toString().trim()
                val postal = postalCode.text.toString().trim()
                val town = city.text.toString().trim()
                if (name.isBlank()) { placeName.error = "Donne un nom à ce lieu"; return@setOnClickListener }
                if (road.isBlank()) { street.error = "Indique le numéro et la rue"; return@setOnClickListener }
                if (postal.isBlank() && town.isBlank()) { city.error = "Indique la ville ou le code postal"; return@setOnClickListener }
                val locality = listOf(postal, town).filter(String::isNotBlank).joinToString(" ")
                val address = listOf(road, locality).joinToString(", ")
                val draft = GpsZoneDraftV2(name, Double.NaN, Double.NaN,
                    radius.text.toString().replace(',', '.').toDoubleOrNull() ?: Double.NaN,
                    roles[role.selectedItemPosition], contactName.text.toString().trim(), phone.text.toString().trim(), notifyOnArrival.isChecked)
                if (!draft.radius.isFinite() || draft.radius !in 50.0..1000.0) {
                    notice("Rayon attendu : de 50 à 1 000 mètres"); return@setOnClickListener
                }
                if (draft.notifyOnArrival && draft.phone.isBlank()) {
                    phone.error = "Ajoute un numéro pour prévenir à l'arrivée"; return@setOnClickListener
                }
                val snapshot = GpsZoneEditorStoreV2.read(gpsPrefs)
                if (snapshot == null) { notice("Configuration GPS à vérifier : aucun lieu ajouté"); return@setOnClickListener }
                val latText = latitude.text.toString().trim()
                val lonText = longitude.text.toString().trim()
                val typedLat = latText.replace(',', '.').toDoubleOrNull()
                val typedLon = lonText.replace(',', '.').toDoubleOrNull()
                val hasCoordinates = latText.isNotBlank() || lonText.isNotBlank()
                if (hasCoordinates && (typedLat == null || typedLon == null ||
                    !typedLat.isFinite() || typedLat !in -90.0..90.0 || !typedLon.isFinite() || typedLon !in -180.0..180.0)) {
                    notice("Renseigne deux coordonnées valides, ou laisse les deux champs vides pour rechercher l'adresse.")
                    return@setOnClickListener
                }
                fun finish(lat: Double?, lon: Double?, source: String) {
                    if (!dialog.isShowing || !isAttachedToWindow) return
                    busy(false)
                    if (lat == null || lon == null || !lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) {
                        notice("Adresse introuvable automatiquement. Choisis un point sur la carte ou saisis ses coordonnées ; ton formulaire et l'entreprise choisie sont conservés.")
                        return
                    }
                    if (companyId != null) {
                        val currentCompanies = SalaryCompanyStore.readConfirmed(context)
                        if (!currentCompanies.reliable || currentCompanies.companies.none { it.id == companyId }) {
                            notice("L'entreprise choisie n'est plus confirmée. Aucune nouvelle zone enregistrée."); return
                        }
                    }
                    val change = GpsZoneEditorStoreV2.addPlace(snapshot, address, companyId, companySlot,
                        generatedId, draft.copy(latitude = lat, longitude = lon), 10, source)
                    if (change == null) {
                        notice("Lieu déjà présent pour cette entreprise, ancien lien ambigu, données invalides ou limite de 10 zones atteinte."); return
                    }
                    if (!GpsZoneEditorStoreV2.commit(gpsPrefs, change)) {
                        notice("Configuration modifiée pendant la saisie ou écriture non confirmée. Vérifie les lieux avant de réessayer."); return
                    }
                    GpsZoneEditorStoreV2.read(gpsPrefs)?.let { saved ->
                        rootView.findViewById<EditText>(R.id.workplaceAddress)?.setText(saved.addresses().joinToString("\n"))
                    }
                    rootView.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
                    PointageWidgetProvider.updateAll(context)
                    QuickActionsWidgetProvider.updateAll(context)
                    GeofenceManager.reconfigureStoredZones(context) { success, detail ->
                        post { notice(if (success) "$name ajouté — $companyName" else "$name enregistré — $companyName. $detail") }
                    }
                    if (draft.notifyOnArrival && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1102)
                    }
                    dialog.dismiss()
                    if (source == "geocoder") post {
                        rootView.findViewById<GpsPointPickerView>(R.id.gpsPointPickerView)?.adjustZone(generatedId)
                    }
                }
                if (hasCoordinates) {
                    val source = if (selectedMapPoint == (typedLat to typedLon)) "map" else "manual_coordinates"
                    finish(typedLat, typedLon, source)
                } else {
                    busy(true)
                    val app = context.applicationContext
                    // Une adresse non résolue n'est jamais transformée en zone sans propriétaire.
                    Thread {
                        val found = runCatching {
                            Geocoder(app, Locale.FRANCE).getFromLocationName(address, 1)
                                ?.firstOrNull { it.hasLatitude() && it.hasLongitude() }
                        }.getOrNull()
                        Handler(Looper.getMainLooper()).post { finish(found?.latitude, found?.longitude, "geocoder") }
                    }.start()
                }
            }
        }
        dialog.show()
    }
}

class SafeGpsSaveButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Button(context, attrs)
