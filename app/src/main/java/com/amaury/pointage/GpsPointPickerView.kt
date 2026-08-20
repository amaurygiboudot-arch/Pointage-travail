package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
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

/**
 * Permet de dissocier l'adresse postale du point GPS réellement utilisé par le geofencing.
 * Le choix est conservé dans zone_point_overrides et réappliqué si l'adresse est ré-enregistrée.
 */
class GpsPointPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
    private var promptScheduled = false
    private var applyingOverride = false

    init {
        orientation = VERTICAL
        setPadding(0, dp(6), 0, dp(6))

        addView(TextView(context).apply {
            text = "POINT GPS PRÉCIS"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(5))
        })

        addView(Button(context).apply {
            text = "📍 AJUSTER LE POINT GPS D'UN LIEU"
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.hp_panel)
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { choosePlaceManually() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        addView(TextView(context).apply {
            text = "L'adresse reste affichée normalement, mais le point choisi ici devient le centre réel du rayon GPS. Utile pour les grands sites où l'adresse tombe sur la route ou au portail."
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(this)
        post { reapplyStoredOverrides(); maybePromptForUnconfirmedPoint() }
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != "zones" || applyingOverride) return
        post {
            reapplyStoredOverrides()
            maybePromptForUnconfirmedPoint()
        }
    }

    private fun zones(): JSONArray = runCatching {
        JSONArray(prefs.getString("zones", "[]") ?: "[]")
    }.getOrElse { JSONArray() }

    private fun overrides(): JSONObject = runCatching {
        JSONObject(prefs.getString("zone_point_overrides", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun confirmed(): JSONObject = runCatching {
        JSONObject(prefs.getString("zone_point_confirmed", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    /** Réinjecte le point choisi par l'utilisateur si MainActivity a re-géocodé l'adresse. */
    private fun reapplyStoredOverrides() {
        val source = zones()
        if (source.length() == 0) return
        val custom = overrides()
        var changed = false

        for (i in 0 until source.length()) {
            val zone = source.optJSONObject(i) ?: continue
            val address = zone.optString("address").trim()
            val point = custom.optJSONObject(address) ?: continue
            val lat = point.optDouble("latitude", Double.NaN)
            val lon = point.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            if (kotlin.math.abs(zone.optDouble("latitude") - lat) > 0.0000001 ||
                kotlin.math.abs(zone.optDouble("longitude") - lon) > 0.0000001) {
                zone.put("latitude", lat)
                zone.put("longitude", lon)
                zone.put("pointSource", point.optString("source", "manual"))
                changed = true
            }
        }

        if (changed) {
            applyingOverride = true
            prefs.edit().putString("zones", source.toString()).apply()
            applyingOverride = false
            registerCurrentZones()
        }
    }

    /** Après le premier enregistrement d'une adresse, propose une fois de vérifier son point. */
    private fun maybePromptForUnconfirmedPoint() {
        if (promptScheduled || !isShown) return
        val list = zones()
        val done = confirmed()
        var target: JSONObject? = null
        for (i in 0 until list.length()) {
            val zone = list.optJSONObject(i) ?: continue
            val address = zone.optString("address").trim()
            if (address.isNotBlank() && !done.optBoolean(address, false)) {
                target = zone
                break
            }
        }
        val zone = target ?: return
        promptScheduled = true
        postDelayed({
            promptScheduled = false
            if (isAttachedToWindow && isShown) showPointChoice(zone, automatic = true)
        }, 350L)
    }

    private fun choosePlaceManually() {
        val list = zones()
        if (list.length() == 0) {
            Toast.makeText(context, "Enregistre d'abord une adresse", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = ArrayList<String>()
        val items = ArrayList<JSONObject>()
        for (i in 0 until list.length()) {
            val zone = list.optJSONObject(i) ?: continue
            val address = zone.optString("address").trim()
            if (address.isBlank()) continue
            labels += (PlaceNames.get(context, address)?.takeIf { it.isNotBlank() }?.let { "$it — $address" } ?: address)
            items += zone
        }
        AlertDialog.Builder(context)
            .setTitle("Quel lieu veux-tu ajuster ?")
            .setItems(labels.toTypedArray()) { _, which -> showPointChoice(items[which], automatic = false) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showPointChoice(zone: JSONObject, automatic: Boolean) {
        val address = zone.optString("address").trim()
        if (address.isBlank()) return
        val candidates = geocodeCandidates(address)
        val current = currentLocation()

        val labels = ArrayList<String>()
        val points = ArrayList<Pair<Double, Double>>()
        val sources = ArrayList<String>()

        // Le point déjà enregistré reste toujours proposé en premier.
        val savedLat = zone.optDouble("latitude", Double.NaN)
        val savedLon = zone.optDouble("longitude", Double.NaN)
        if (savedLat.isFinite() && savedLon.isFinite()) {
            labels += "✓ Point actuellement enregistré\n${fmt(savedLat)}, ${fmt(savedLon)}"
            points += savedLat to savedLon
            sources += zone.optString("pointSource", "address")
        }

        candidates.forEachIndexed { index, item ->
            if (points.any { closeTo(it.first, it.second, item.latitude, item.longitude) }) return@forEachIndexed
            val label = item.getAddressLine(0)?.takeIf { it.isNotBlank() } ?: "Résultat ${index + 1}"
            labels += "📌 $label\n${fmt(item.latitude)}, ${fmt(item.longitude)}"
            points += item.latitude to item.longitude
            sources += "address_candidate"
        }

        if (current != null && points.none { closeTo(it.first, it.second, current.latitude, current.longitude) }) {
            labels += "📱 MA POSITION ACTUELLE\n${fmt(current.latitude)}, ${fmt(current.longitude)}  (±${current.accuracy.toInt()} m)"
            points += current.latitude to current.longitude
            sources += "current_location"
        }

        labels += "✏️ ENTRER DES COORDONNÉES PRÉCISES"

        val message = if (automatic) {
            "L'adresse a été trouvée. Vérifie maintenant le centre réel de la zone de travail. Pour une grande entreprise, choisis plutôt un point au milieu du site qu'un point sur la route."
        } else {
            "Choisis le centre réel de la zone GPS. L'adresse postale ne sera pas modifiée."
        }

        AlertDialog.Builder(context)
            .setTitle("Point GPS — ${PlaceNames.get(context, address) ?: address}")
            .setMessage(message)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.lastIndex) {
                    showCoordinateEntry(zone)
                } else {
                    savePoint(zone, points[which].first, points[which].second, sources[which])
                }
            }
            .setNegativeButton(if (automatic) "Garder le point proposé" else "Annuler") { _, _ ->
                if (automatic) markConfirmed(address)
            }
            .show()
    }

    private fun showCoordinateEntry(zone: JSONObject) {
        val address = zone.optString("address").trim()
        val latInput = EditText(context).apply {
            hint = "Latitude"
            setText(zone.optDouble("latitude", 0.0).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val lonInput = EditText(context).apply {
            hint = "Longitude"
            setText(zone.optDouble("longitude", 0.0).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(latInput)
            addView(lonInput)
        }
        AlertDialog.Builder(context)
            .setTitle("Coordonnées précises")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val lat = latInput.text.toString().replace(',', '.').toDoubleOrNull()
                val lon = lonInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    Toast.makeText(context, "Coordonnées invalides", Toast.LENGTH_LONG).show()
                } else {
                    savePoint(zone, lat, lon, "manual_coordinates")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun savePoint(zone: JSONObject, latitude: Double, longitude: Double, source: String) {
        val address = zone.optString("address").trim()
        if (address.isBlank()) return

        val custom = overrides().apply {
            put(address, JSONObject()
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("source", source))
        }
        val list = zones()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("address").trim().equals(address, ignoreCase = true)) {
                item.put("latitude", latitude)
                item.put("longitude", longitude)
                item.put("pointSource", source)
            }
        }
        applyingOverride = true
        prefs.edit()
            .putString("zone_point_overrides", custom.toString())
            .putString("zones", list.toString())
            .apply()
        applyingOverride = false
        markConfirmed(address)
        registerCurrentZones()
        Toast.makeText(context, "Point GPS précis enregistré pour ${PlaceNames.get(context, address) ?: address}", Toast.LENGTH_LONG).show()
    }

    private fun markConfirmed(address: String) {
        val done = confirmed().apply { put(address, true) }
        prefs.edit().putString("zone_point_confirmed", done.toString()).apply()
        post { maybePromptForUnconfirmedPoint() }
    }

    private fun registerCurrentZones() {
        if (!prefs.getBoolean("enabled", false)) return
        if (!GeofenceManager.hasRequiredPermissions(context)) return
        val list = zones()
        val workZones = mutableListOf<WorkZone>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val lat = item.optDouble("latitude", Double.NaN)
            val lon = item.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            val radius = item.optDouble("radius", 150.0).toFloat().coerceIn(50f, 1000f)
            workZones += WorkZone(id, lat, lon, radius)
        }
        if (workZones.isNotEmpty()) GeofenceManager.registerAll(context, workZones) { _, _ -> }
    }

    @Suppress("DEPRECATION")
    private fun geocodeCandidates(address: String): List<Address> = runCatching {
        Geocoder(context, Locale.FRANCE).getFromLocationName(address, 5)?.filter {
            it.latitude.isFinite() && it.longitude.isFinite()
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun currentLocation(): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.minByOrNull { it.accuracy }
    }

    private fun closeTo(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean =
        kotlin.math.abs(aLat - bLat) < 0.00001 && kotlin.math.abs(aLon - bLon) < 0.00001

    private fun fmt(value: Double) = String.format(Locale.FRANCE, "%.6f", value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
