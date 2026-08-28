package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.Locale

/** Permet de qualifier un lieu GPS existant sans toucher à ses coordonnées. */
class GpsZoneTypeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object { const val TAG = "gps_zone_type_v2" }
    private val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(10), 0, dp(6))
        rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(this)
        rebuild()
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "zones" || key == "address") post { rebuild() }
    }

    private fun rebuild() {
        removeAllViews()
        addView(TextView(context).apply {
            text = "TYPE DES LIEUX GPS"
            textSize = 14f
        })
        addView(TextView(context).apply {
            text = "Poste = lieu de travail. Parking = zone intermédiaire utilisée pour confirmer pause ou départ."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })
        val zones = readZones()
        if (zones.length() == 0) {
            addView(TextView(context).apply { text = "Ajoute d'abord un lieu GPS."; textSize = 13f })
            return
        }
        for (i in 0 until zones.length()) {
            val zone = zones.optJSONObject(i) ?: continue
            val address = zone.optString("address").trim()
            if (address.isBlank()) continue
            val type = normalizedType(zone.optString("pointType").ifBlank { zone.optString("zoneType") })
            val display = PlaceNames.get(context, address)?.takeIf { it.isNotBlank() } ?: address
            addView(Button(context).apply {
                text = "$display  •  ${typeLabel(type)}"
                isAllCaps = false
                textSize = 14f
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener { chooseType(address) }
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(5) })
        }
    }

    private fun chooseType(address: String) {
        val labels = arrayOf("🏭 Poste de travail", "🅿️ Parking", "📍 Autre / à confirmer")
        val values = arrayOf("POSTE", "PARKING", "OTHER")
        AlertDialog.Builder(context)
            .setTitle("Type de lieu")
            .setMessage(address)
            .setItems(labels) { _, which ->
                val zones = readZones()
                var changed = false
                for (i in 0 until zones.length()) {
                    val zone = zones.optJSONObject(i) ?: continue
                    if (zone.optString("address").trim().equals(address, ignoreCase = true)) {
                        zone.put("pointType", values[which])
                        changed = true
                    }
                }
                if (changed) {
                    prefs.edit().putString("zones", zones.toString()).remove("active_zones").apply()
                    Toast.makeText(context, "Type GPS enregistré", Toast.LENGTH_SHORT).show()
                    rebuild()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun readZones(): JSONArray = runCatching {
        JSONArray(prefs.getString("zones", "[]") ?: "[]")
    }.getOrElse { JSONArray() }

    private fun normalizedType(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
        "PARKING" -> "PARKING"
        "OTHER", "AUTRE" -> "OTHER"
        else -> "POSTE"
    }

    private fun typeLabel(type: String) = when (type) {
        "PARKING" -> "Parking"
        "OTHER" -> "Autre"
        else -> "Poste"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
