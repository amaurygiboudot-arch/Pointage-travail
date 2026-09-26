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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class AddAddressButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Button(context, attrs) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootView.findViewById<Button>(R.id.saveGpsSettingsButton)?.visibility = View.GONE
        super.setOnClickListener { showAddressDialog() }
    }

    private fun showAddressDialog() {
        val addressList = rootView.findViewById<EditText>(R.id.workplaceAddress)
        val existing = addressList.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (existing.size >= 10) {
            Toast.makeText(context, "10 adresses maximum", Toast.LENGTH_LONG).show()
            return
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val useV2EmployerBinding = HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)
        val companyLabel = TextView(context).apply {
            text = "Entreprise associée"
            textSize = 14f
            setPadding(0, dp(6), 0, dp(4))
        }
        val companyGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val companyByButtonId = linkedMapOf<Int, String?>()
        val legacySlotByButtonId = linkedMapOf<Int, Int>()
        val companyDisplayByButtonId = linkedMapOf<Int, String>()

        if (useV2EmployerBinding) {
            val none = RadioButton(context).apply {
                id = View.generateViewId()
                text = "Aucune association automatique — garder l'entreprise choisie au pointage"
                textSize = 15f
            }
            companyGroup.addView(none)
            companyByButtonId[none.id] = null
            companyDisplayByButtonId[none.id] = "sans association automatique"

            val stored = SalaryCompanyStore.readConfirmed(context)
            if (!stored.reliable) {
                container.addView(TextView(context).apply {
                    text = "Entreprises V2 à vérifier : ce lieu peut être enregistré sans association automatique."
                    textSize = 13f
                    setPadding(0, 0, 0, dp(4))
                })
            } else {
                stored.companies.forEach { company ->
                    val button = RadioButton(context).apply {
                        id = View.generateViewId()
                        text = buildString {
                            append(company.name.ifBlank { "Entreprise" })
                            if (company.siret.isNotBlank()) append(" — SIRET ${company.siret}")
                        }
                        textSize = 15f
                    }
                    companyGroup.addView(button)
                    companyByButtonId[button.id] = company.id
                    companyDisplayByButtonId[button.id] = company.name.ifBlank { "Entreprise" }
                }
            }
        } else {
            val salaryPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
            val company1Name = salaryPrefs.getString("company_name", "").orEmpty().ifBlank { "Entreprise 1" }
            val company2Name = salaryPrefs.getString("company2_name", "").orEmpty().ifBlank { "Entreprise 2" }
            listOf(1 to company1Name, 2 to company2Name).forEach { (slot, name) ->
                val button = RadioButton(context).apply {
                    id = View.generateViewId()
                    text = "Entreprise $slot — $name"
                    textSize = 15f
                }
                companyGroup.addView(button)
                legacySlotByButtonId[button.id] = slot
                companyDisplayByButtonId[button.id] = name
            }
        }

        val placeName = EditText(context).apply {
            hint = "Nom du lieu / client"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
        val contactName = EditText(context).apply {
            hint = "Nom du contact"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
        val phone = EditText(context).apply {
            hint = "Téléphone du contact"
            inputType = InputType.TYPE_CLASS_PHONE
            isSingleLine = true
        }
        val notifyOnArrival = Switch(context).apply { text = "Proposer de prévenir ce contact à l'arrivée" }
        val street = EditText(context).apply {
            hint = "N° et rue — ex. 12 rue des Lilas"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            isSingleLine = true
        }
        val postalCode = EditText(context).apply {
            hint = "Code postal — ex. 50400"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        val city = EditText(context).apply {
            hint = "Ville — ex. Granville"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }

        listOf(companyLabel, companyGroup, placeName, contactName, phone, notifyOnArrival, street, postalCode, city)
            .forEach { container.addView(it) }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Ajouter un lieu de travail")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Ajouter", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nameValue = placeName.text.toString().trim()
                val contactValue = contactName.text.toString().trim()
                val phoneValue = phone.text.toString().trim()
                val streetValue = street.text.toString().trim()
                val postalValue = postalCode.text.toString().trim()
                val cityValue = city.text.toString().trim()
                val checkedCompanyButtonId = companyGroup.checkedRadioButtonId

                if (checkedCompanyButtonId == -1 ||
                    (useV2EmployerBinding && !companyByButtonId.containsKey(checkedCompanyButtonId)) ||
                    (!useV2EmployerBinding && !legacySlotByButtonId.containsKey(checkedCompanyButtonId))
                ) {
                    Toast.makeText(
                        context,
                        if (useV2EmployerBinding) "Choisis une entreprise ou Aucune association automatique" else "Choisis Entreprise 1 ou Entreprise 2",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val selectedCompanyId = companyByButtonId[checkedCompanyButtonId]
                val legacyCompanySlot = legacySlotByButtonId[checkedCompanyButtonId]
                val selectedCompanyLabel = companyDisplayByButtonId[checkedCompanyButtonId] ?: "sans association automatique"

                if (nameValue.isBlank()) {
                    placeName.error = "Donne un nom à ce lieu"
                    return@setOnClickListener
                }
                if (streetValue.isBlank()) {
                    street.error = "Indique le numéro et la rue"
                    return@setOnClickListener
                }
                if (postalValue.isBlank() && cityValue.isBlank()) {
                    city.error = "Indique la ville ou le code postal"
                    return@setOnClickListener
                }
                if (notifyOnArrival.isChecked && phoneValue.isBlank()) {
                    phone.error = "Ajoute un numéro pour prévenir à l'arrivée"
                    return@setOnClickListener
                }

                val locality = listOf(postalValue, cityValue).filter { it.isNotBlank() }.joinToString(" ")
                val formatted = listOf(streetValue, locality).filter { it.isNotBlank() }.joinToString(", ")
                val duplicate = existing.any { it.equals(formatted, ignoreCase = true) }
                if (duplicate) {
                    Toast.makeText(context, "Ce lieu est déjà enregistré", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positiveButton.isEnabled = false
                positiveButton.text = "LOCALISATION…"
                val notifyOnArrivalValue = notifyOnArrival.isChecked
                val appContext = context.applicationContext

                // Le géocodage peut interroger un service distant et ne doit jamais bloquer l'écran.
                Thread {
                    val geocoded = runCatching {
                        Geocoder(appContext, Locale.FRANCE).getFromLocationName(formatted, 1)?.firstOrNull()
                    }.getOrNull()

                    Handler(Looper.getMainLooper()).post finishGeocoding@{
                        if (!dialog.isShowing || !isAttachedToWindow) return@finishGeocoding

                        val latestAddresses = addressList.text.toString().lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        if (latestAddresses.any { it.equals(formatted, ignoreCase = true) }) {
                            positiveButton.isEnabled = true
                            positiveButton.text = "Ajouter"
                            Toast.makeText(context, "Ce lieu est déjà enregistré", Toast.LENGTH_LONG).show()
                            return@finishGeocoding
                        }
                        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
                        val zones = readPersistedGpsZones(gpsPrefs).toMutableJsonArrayOrNull()
                        if (zones == null) {
                            GeofenceManager.reconfigureStoredZones(context)
                            positiveButton.isEnabled = true
                            positiveButton.text = "Ajouter"
                            Toast.makeText(context, "Configuration GPS illisible : le lieu n'a pas été ajouté", Toast.LENGTH_LONG).show()
                            return@finishGeocoding
                        }
                        val updated = (latestAddresses + formatted).distinctBy { it.lowercase() }.take(10)
                        addressList.setText(updated.joinToString("\n"))


                        val companyMap = runCatching {
                            JSONObject(gpsPrefs.getString("address_company_slots", "{}") ?: "{}")
                        }.getOrElse { JSONObject() }
                        if (!useV2EmployerBinding && legacyCompanySlot != null) {
                            companyMap.put(formatted, legacyCompanySlot)
                        }

                        var createdZoneId: String? = null
                        if (geocoded != null) {
                            createdZoneId = UUID.randomUUID().toString()
                            val zone = JSONObject()
                                .put("id", createdZoneId)
                                .put("address", formatted)
                                .put("label", nameValue)
                                .put("latitude", geocoded.latitude)
                                .put("longitude", geocoded.longitude)
                                .put("radius", gpsPrefs.getInt("radius", 150).coerceIn(50, 1000))
                                .put("pointType", "POSTE")
                                .put("pointSource", "geocoder")
                            selectedCompanyId?.let { zone.put("companyId", it) }
                            if (!useV2EmployerBinding && legacyCompanySlot != null) zone.put("companySlot", legacyCompanySlot)
                            zones.put(zone)
                        }

                        val editor = gpsPrefs.edit()
                            .putString("address", updated.joinToString("\n"))
                            .putString("zones", zones.toString())
                            .remove("active_zones")
                            .remove("entry_resolution_pending")
                            .remove("entry_resolution_token")
                            .remove("pending_exit_zones")
                            .putString("pending_point_address", formatted)
                        if (!useV2EmployerBinding) editor.putString("address_company_slots", companyMap.toString())
                        editor.apply()
                        if (geocoded == null) {
                            PlaceNames.put(context, formatted, nameValue)
                        }
                        GpsZoneArrivalContacts.put(
                            context = context,
                            zoneId = createdZoneId,
                            address = formatted,
                            contactName = contactValue,
                            phone = phoneValue,
                            enabled = notifyOnArrivalValue
                        )
                        GeofenceManager.reconfigureStoredZones(context)

                        if (notifyOnArrivalValue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1102)
                        }

                        rootView.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
                        val message = if (geocoded != null)
                            "$nameValue ajouté — $selectedCompanyLabel — la carte va s'ouvrir sur l'adresse"
                        else
                            "$nameValue ajouté — $selectedCompanyLabel — adresse introuvable automatiquement, place le point manuellement"
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                }.start()
            }
        }
        dialog.show()
    }
}

class SafeGpsSaveButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Button(context, attrs)
