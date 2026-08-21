package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import org.json.JSONObject

class AddAddressButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Button(context, attrs) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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

        val salaryPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val company1Name = salaryPrefs.getString("company_name", "").orEmpty().ifBlank { "Entreprise 1" }
        val company2Name = salaryPrefs.getString("company2_name", "").orEmpty().ifBlank { "Entreprise 2" }

        val companyLabel = TextView(context).apply {
            text = "Entreprise associée"
            textSize = 14f
            setPadding(0, dp(6), 0, dp(4))
        }
        val companyGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val company1Button = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Entreprise 1 — $company1Name"
            textSize = 15f
        }
        val company2Button = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Entreprise 2 — $company2Name"
            textSize = 15f
        }
        companyGroup.addView(company1Button)
        companyGroup.addView(company2Button)

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
                val companySlot = when (companyGroup.checkedRadioButtonId) {
                    company1Button.id -> 1
                    company2Button.id -> 2
                    else -> 0
                }

                if (companySlot == 0) {
                    Toast.makeText(context, "Choisis Entreprise 1 ou Entreprise 2", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
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
                val updated = (existing + formatted).distinctBy { it.lowercase() }.take(10)
                addressList.setText(updated.joinToString("\n"))

                val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
                gpsPrefs.edit().putString("address", updated.joinToString("\n")).apply()
                PlaceNames.put(context, formatted, nameValue)

                val contacts = runCatching {
                    JSONObject(gpsPrefs.getString("arrival_contacts", "{}") ?: "{}")
                }.getOrElse { JSONObject() }
                contacts.put(
                    formatted,
                    JSONObject()
                        .put("contactName", contactValue)
                        .put("phone", phoneValue)
                        .put("enabled", notifyOnArrival.isChecked)
                )

                val companyMap = runCatching {
                    JSONObject(gpsPrefs.getString("address_company_slots", "{}") ?: "{}")
                }.getOrElse { JSONObject() }
                companyMap.put(formatted, companySlot)

                gpsPrefs.edit()
                    .putString("arrival_contacts", contacts.toString())
                    .putString("address_company_slots", companyMap.toString())
                    .apply()

                if (notifyOnArrival.isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1102)
                }

                rootView.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
                val companyName = if (companySlot == 1) company1Name else company2Name
                Toast.makeText(context, "$nameValue ajouté à $companyName", Toast.LENGTH_SHORT).show()

                // Le bouton manuel « Enregistrer les lieux » n'est plus nécessaire :
                // dès que le formulaire est validé, on géocode et on enregistre la zone.
                // GpsPointPickerView détecte ensuite la nouvelle zone et ouvre le choix du point précis.
                dialog.dismiss()
                rootView.post {
                    rootView.findViewById<Button>(R.id.saveGpsSettingsButton)?.performClick()
                }
            }
        }
        dialog.show()
    }
}

class SafeGpsSaveButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Button(context, attrs) {
    override fun setOnClickListener(listener: View.OnClickListener?) {
        super.setOnClickListener {
            val enabledSwitch = rootView.findViewById<Switch>(R.id.autoGpsSwitch)
            val addressList = rootView.findViewById<EditText>(R.id.workplaceAddress)
            val radiusInput = rootView.findViewById<EditText>(R.id.geofenceRadius)
            if (enabledSwitch?.isChecked == true && !GeofenceManager.hasRequiredPermissions(context)) {
                val addresses = addressList?.text?.toString().orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }.distinct().take(10)
                if (addresses.isEmpty()) {
                    Toast.makeText(context, "Ajoute au moins une adresse avec le bouton +", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val radius = radiusInput?.text?.toString()?.toIntOrNull()?.coerceIn(50, 1000) ?: 150
                context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE).edit()
                    .putString("address", addresses.joinToString("\n"))
                    .putInt("radius", radius)
                    .putBoolean("enabled", true)
                    .apply()
                Toast.makeText(context, "Adresse enregistrée. Autorise maintenant la localisation pour activer le pointage automatique.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            listener?.onClick(this)
        }
    }
}
