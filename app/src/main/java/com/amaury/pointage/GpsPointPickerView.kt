package com.amaury.pointage

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Sélection du centre GPS réel d'un lieu.
 * L'adresse postale reste inchangée ; le point choisi sur la carte devient le centre du geofence.
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
        rebuildHeader()
    }

    private fun rebuildHeader() {
        removeAllViews()
        val dark = AppThemeCatalog.useDarkPalette(context)
        val theme = AppThemeCatalog.current(context)
        val text = if (dark) theme.darkText else theme.lightText
        val hint = if (dark) theme.darkHint else theme.lightHint
        val accent = if (dark) theme.accentLight else theme.accent

        addView(TextView(context).apply {
            this.text = "POINT GPS PRÉCIS"
            textSize = 14f
            setTextColor(accent)
            setPadding(0, dp(12), 0, dp(5))
        })

        addView(Button(context).apply {
            this.text = "📍 AJUSTER LE POINT GPS D'UN LIEU"
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(text)
            setBackgroundResource(R.drawable.hp_panel)
            backgroundTintList = ColorStateList.valueOf(if (dark) theme.darkPanel else theme.lightPanel)
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { choosePlaceManually() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        addView(TextView(context).apply {
            this.text = "L'adresse reste affichée normalement. Le point choisi sur la carte devient le centre réel du rayon GPS."
            textSize = 12f
            setTextColor(hint)
            setPadding(0, dp(6), 0, dp(6))
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(this)
        post { reapplyStoredOverrides(); maybePromptForPendingPoint() }
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (applyingOverride) return
        if (key != "zones" && key != "pending_point_address" && key != "address") return
        post {
            reapplyStoredOverrides()
            maybePromptForPendingPoint()
        }
    }

    private fun zones(): JSONArray = runCatching {
        JSONArray(prefs.getString("zones", "[]") ?: "[]")
    }.getOrElse { JSONArray() }

    private fun savedAddresses(): List<String> = prefs.getString("address", "")
        .orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.FRANCE) }.take(10)

    private fun overrides(): JSONObject = runCatching {
        JSONObject(prefs.getString("zone_point_overrides", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun confirmed(): JSONObject = runCatching {
        JSONObject(prefs.getString("zone_point_confirmed", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun findZone(address: String, list: JSONArray = zones()): JSONObject? {
        for (i in 0 until list.length()) {
            val zone = list.optJSONObject(i) ?: continue
            if (zone.optString("address").trim().equals(address.trim(), ignoreCase = true)) return zone
        }
        return null
    }

    private fun provisionalZone(address: String): JSONObject {
        val custom = overrides().optJSONObject(address)
        val current = currentLocation()
        val lat = custom?.optDouble("latitude", Double.NaN)?.takeIf { it.isFinite() }
            ?: current?.latitude
            ?: 46.603354
        val lon = custom?.optDouble("longitude", Double.NaN)?.takeIf { it.isFinite() }
            ?: current?.longitude
            ?: 1.888334
        return JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("address", address)
            .put("latitude", lat)
            .put("longitude", lon)
            .put("radius", prefs.getInt("radius", 150).coerceIn(50, 1000))
            .put("pointSource", custom?.optString("source", "provisional") ?: "provisional")
    }

    /**
     * Réapplique les points choisis par l'utilisateur après tout regéocodage.
     * Si Android n'arrive plus à géocoder une adresse, on recrée quand même la zone
     * à partir du point manuel enregistré au lieu de la perdre silencieusement.
     */
    private fun reapplyStoredOverrides() {
        val source = zones()
        val custom = overrides()
        val addresses = savedAddresses()
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

        addresses.forEach { address ->
            if (findZone(address, source) != null) return@forEach
            val point = custom.optJSONObject(address) ?: return@forEach
            val lat = point.optDouble("latitude", Double.NaN)
            val lon = point.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) return@forEach
            source.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("address", address)
                    .put("latitude", lat)
                    .put("longitude", lon)
                    .put("radius", prefs.getInt("radius", 150).coerceIn(50, 1000))
                    .put("pointSource", point.optString("source", "manual"))
            )
            changed = true
        }

        if (changed) {
            applyingOverride = true
            prefs.edit().putString("zones", source.toString()).remove("active_zones").apply()
            applyingOverride = false
            registerCurrentZones()
        }
    }

    /**
     * N'ouvre automatiquement que le lieu qui vient réellement d'être ajouté ou modifié.
     * Même si le géocodeur n'a rien trouvé, la carte s'ouvre avec une position provisoire
     * afin que l'utilisateur puisse poser lui-même le point exact.
     */
    private fun maybePromptForPendingPoint() {
        if (promptScheduled || !isShown) return
        val pending = prefs.getString("pending_point_address", "").orEmpty().trim()
        if (pending.isBlank()) return
        if (savedAddresses().none { it.equals(pending, ignoreCase = true) }) {
            prefs.edit().remove("pending_point_address").apply()
            return
        }
        val zone = findZone(pending) ?: provisionalZone(pending)
        promptScheduled = true
        postDelayed({
            promptScheduled = false
            if (isAttachedToWindow && isShown) showMapPicker(zone, automatic = true)
        }, 300L)
    }

    private fun choosePlaceManually() {
        val addresses = savedAddresses()
        if (addresses.isEmpty()) {
            Toast.makeText(context, "Ajoute d'abord un lieu", Toast.LENGTH_SHORT).show()
            return
        }
        val list = zones()
        val labels = ArrayList<String>()
        val items = ArrayList<JSONObject>()
        addresses.forEach { address ->
            labels += (PlaceNames.get(context, address)?.takeIf { it.isNotBlank() }?.let { "$it — $address" } ?: address)
            items += (findZone(address, list) ?: provisionalZone(address))
        }

        val dark = AppThemeCatalog.useDarkPalette(context)
        val theme = AppThemeCatalog.current(context)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val text = if (dark) theme.darkText else theme.lightText
        val accent = if (dark) theme.accentLight else theme.accent

        val dialog = AlertDialog.Builder(context)
            .setTitle("Quel lieu veux-tu ajuster ?")
            .setItems(labels.toTypedArray()) { _, which -> showMapPicker(items[which], automatic = false) }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(panel, 20, accent))
            dialog.listView?.setBackgroundColor(panel)
            for (i in 0 until dialog.listView.childCount) {
                (dialog.listView.getChildAt(i) as? TextView)?.setTextColor(text)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        }
        dialog.show()
    }

    private fun showMapPicker(zone: JSONObject, automatic: Boolean) {
        val address = zone.optString("address").trim()
        if (address.isBlank()) return

        val customPoint = overrides().optJSONObject(address)
        val savedLat = customPoint?.optDouble("latitude", Double.NaN)?.takeIf { it.isFinite() }
            ?: zone.optDouble("latitude", Double.NaN)
        val savedLon = customPoint?.optDouble("longitude", Double.NaN)?.takeIf { it.isFinite() }
            ?: zone.optDouble("longitude", Double.NaN)
        val current = currentLocation()
        var selectedLat = when {
            savedLat.isFinite() -> savedLat
            current != null -> current.latitude
            else -> 46.603354
        }
        var selectedLon = when {
            savedLon.isFinite() -> savedLon
            current != null -> current.longitude
            else -> 1.888334
        }

        val dark = AppThemeCatalog.useDarkPalette(context)
        val theme = AppThemeCatalog.current(context)
        val background = if (dark) theme.darkBackground else theme.lightBackground
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val text = if (dark) theme.darkText else theme.lightText
        val hint = if (dark) theme.darkHint else theme.lightHint
        val accent = if (dark) theme.accentLight else theme.accent

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(background)
        }

        root.addView(TextView(context).apply {
            this.text = "📍 ${PlaceNames.get(context, address) ?: address}"
            textSize = 19f
            setTextColor(accent)
            setPadding(0, 0, 0, dp(5))
        })
        root.addView(TextView(context).apply {
            this.text = if (automatic)
                "Place le repère au centre réel de ta zone de travail. Tu peux déplacer la carte, zoomer et déplacer le repère."
            else "Déplace le repère sur le centre réel de la zone GPS. L'adresse postale ne changera pas."
            textSize = 14f
            setTextColor(text)
            setPadding(0, 0, 0, dp(10))
        })

        val coordinateLabel = TextView(context).apply {
            textSize = 13f
            setTextColor(hint)
            gravity = Gravity.CENTER
            this.text = "${fmt(selectedLat)}, ${fmt(selectedLon)}"
            setPadding(0, dp(6), 0, dp(6))
        }

        val webView = WebView(context).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = false
            webViewClient = WebViewClient()
        }

        val bridge = object {
            @JavascriptInterface
            fun onPoint(latitude: Double, longitude: Double) {
                selectedLat = latitude
                selectedLon = longitude
                post { coordinateLabel.text = "${fmt(latitude)}, ${fmt(longitude)}" }
            }
        }
        webView.addJavascriptInterface(bridge, "Android")
        webView.loadDataWithBaseURL(
            "https://www.openstreetmap.org/",
            leafletHtml(selectedLat, selectedLon),
            "text/html",
            "UTF-8",
            null
        )
        root.addView(webView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)))
        root.addView(coordinateLabel)

        val quick = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun actionButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
            this.text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(text)
            this.background = rounded(panel, 12, accent)
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
        }
        val addressButton = actionButton("POINT ADRESSE") {
            if (savedLat.isFinite() && savedLon.isFinite()) {
                selectedLat = savedLat
                selectedLon = savedLon
                coordinateLabel.text = "${fmt(selectedLat)}, ${fmt(selectedLon)}"
                webView.evaluateJavascript("setPoint($selectedLat,$selectedLon,true);", null)
            }
        }
        val positionButton = actionButton("MA POSITION") {
            val now = currentLocation()
            if (now == null) {
                Toast.makeText(context, "Position actuelle indisponible pour le moment", Toast.LENGTH_SHORT).show()
            } else {
                selectedLat = now.latitude
                selectedLon = now.longitude
                coordinateLabel.text = "${fmt(selectedLat)}, ${fmt(selectedLon)}  ±${now.accuracy.toInt()} m"
                webView.evaluateJavascript("setPoint($selectedLat,$selectedLon,true);", null)
            }
        }
        quick.addView(addressButton, LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        quick.addView(positionButton, LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        root.addView(quick)

        val save = actionButton("✓ VALIDER CE POINT") { }
        save.textSize = 14f
        save.setTextColor(accent)
        root.addView(save, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) })

        val later = TextView(context).apply {
            this.text = if (automatic) "Plus tard" else "Annuler"
            textSize = 14f
            setTextColor(hint)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
            isClickable = true
        }
        root.addView(later)

        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(background, 22, accent))
            val width = (resources.displayMetrics.widthPixels * .95f).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { webView.destroy() }

        save.setOnClickListener {
            savePoint(zone, selectedLat, selectedLon, "map")
            prefs.edit().remove("pending_point_address").apply()
            dialog.dismiss()
        }
        later.setOnClickListener {
            if (automatic) prefs.edit().remove("pending_point_address").apply()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showCoordinateEntry(zone: JSONObject) {
        val dark = AppThemeCatalog.useDarkPalette(context)
        val theme = AppThemeCatalog.current(context)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val text = if (dark) theme.darkText else theme.lightText
        val hint = if (dark) theme.darkHint else theme.lightHint
        val accent = if (dark) theme.accentLight else theme.accent

        val latInput = EditText(context).apply {
            this.hint = "Latitude"
            setText(zone.optDouble("latitude", 0.0).toString())
            setTextColor(text)
            setHintTextColor(hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val lonInput = EditText(context).apply {
            this.hint = "Longitude"
            setText(zone.optDouble("longitude", 0.0).toString())
            setTextColor(text)
            setHintTextColor(hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            setBackgroundColor(panel)
            addView(latInput)
            addView(lonInput)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Coordonnées précises")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val lat = latInput.text.toString().replace(',', '.').toDoubleOrNull()
                val lon = lonInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    Toast.makeText(context, "Coordonnées invalides", Toast.LENGTH_LONG).show()
                } else savePoint(zone, lat, lon, "manual_coordinates")
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(panel, 18, accent))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        }
        dialog.show()
    }

    private fun savePoint(zone: JSONObject, latitude: Double, longitude: Double, source: String) {
        val address = zone.optString("address").trim()
        if (address.isBlank()) return

        val custom = overrides().apply {
            put(address, JSONObject().put("latitude", latitude).put("longitude", longitude).put("source", source))
        }
        val list = zones()
        var found = false
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("address").trim().equals(address, ignoreCase = true)) {
                item.put("latitude", latitude)
                item.put("longitude", longitude)
                item.put("radius", prefs.getInt("radius", item.optInt("radius", 150)).coerceIn(50, 1000))
                item.put("pointSource", source)
                if (item.optString("id").isBlank()) item.put("id", UUID.randomUUID().toString())
                found = true
                break
            }
        }
        if (!found) {
            list.put(
                JSONObject()
                    .put("id", zone.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
                    .put("address", address)
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("radius", prefs.getInt("radius", 150).coerceIn(50, 1000))
                    .put("pointSource", source)
            )
        }

        applyingOverride = true
        prefs.edit()
            .putString("zone_point_overrides", custom.toString())
            .putString("zones", list.toString())
            .remove("active_zones")
            .apply()
        applyingOverride = false
        markConfirmed(address)
        registerCurrentZones()
        Toast.makeText(context, "Point GPS enregistré pour ${PlaceNames.get(context, address) ?: address}", Toast.LENGTH_LONG).show()
    }

    private fun markConfirmed(address: String) {
        val done = confirmed().apply { put(address, true) }
        prefs.edit().putString("zone_point_confirmed", done.toString()).apply()
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

    /**
     * Pour le bouton « Ma position », on évite désormais de choisir un ancien point GPS
     * uniquement parce qu'il avait une meilleure précision. On privilégie d'abord les
     * positions récentes, puis la meilleure précision parmi elles.
     */
    private fun currentLocation(): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        val fixes = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        if (fixes.isEmpty()) return null

        val recent = fixes.filter { fix ->
            val age = now - fix.time
            age in 0L..300_000L
        }
        val candidates = if (recent.isNotEmpty()) recent else fixes
        return candidates.minWithOrNull(
            compareBy<Location> { if (it.hasAccuracy() && it.accuracy > 0f) it.accuracy else Float.MAX_VALUE }
                .thenByDescending { it.time }
        )
    }

    private fun leafletHtml(latitude: Double, longitude: Double): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
        <style>html,body,#map{height:100%;margin:0;padding:0;background:#e8e8e8}.leaflet-control-attribution{font-size:9px}</style>
        </head><body><div id="map"></div>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <script>
        var map=L.map('map',{zoomControl:true}).setView([$latitude,$longitude],17);
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:20,attribution:'© OpenStreetMap'}).addTo(map);
        var marker=L.marker([$latitude,$longitude],{draggable:true}).addTo(map);
        function report(p){ if(window.Android){Android.onPoint(p.lat,p.lng);} }
        marker.on('dragend',function(e){report(e.target.getLatLng());});
        map.on('click',function(e){marker.setLatLng(e.latlng);report(e.latlng);});
        function setPoint(lat,lon,recenter){var p=L.latLng(lat,lon);marker.setLatLng(p);if(recenter){map.setView(p,18);}report(p);}
        setTimeout(function(){map.invalidateSize();},350);
        </script></body></html>
    """.trimIndent()

    private fun rounded(color: Int, radiusDp: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun fmt(value: Double) = String.format(Locale.FRANCE, "%.6f", value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
