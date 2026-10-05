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

    private fun zones(): JSONArray? = readPersistedGpsZones(prefs).toMutableJsonArrayOrNull()

    private fun savedAddresses(): List<String> = prefs.getString("address", "")
        .orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.FRANCE) }.take(10)

    private fun overrides(): JSONObject = runCatching {
        JSONObject(prefs.getString("zone_point_overrides", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun pointOverrideFor(zoneId: String?, address: String, source: JSONObject = overrides()): JSONObject? {
        val canonicalId = zoneId?.trim().orEmpty()
        if (canonicalId.isNotBlank()) {
            source.optJSONObject(canonicalId)?.let { return it }
        }
        val uniqueOwner = resolveUniqueGpsZoneIdForAddress(readPersistedGpsZones(prefs), address)
        if (uniqueOwner == null || canonicalId.isNotBlank() && uniqueOwner != canonicalId) return null
        return source.optJSONObject(address)
    }

    private fun zonesForAddress(address: String, list: JSONArray?): List<JSONObject> {
        if (list == null) return emptyList()
        val normalized = address.trim()
        return (0 until list.length()).mapNotNull { index ->
            list.optJSONObject(index)
                ?.takeIf { it.optString("address").trim().equals(normalized, ignoreCase = true) }
        }
    }

    private fun provisionalZone(address: String): JSONObject {
        val provisionalId = UUID.randomUUID().toString()
        val custom = pointOverrideFor(provisionalId, address)
        val current = currentLocation()
        val lat = custom?.optDouble("latitude", Double.NaN)?.takeIf { it.isFinite() }
            ?: current?.latitude
            ?: 46.603354
        val lon = custom?.optDouble("longitude", Double.NaN)?.takeIf { it.isFinite() }
            ?: current?.longitude
            ?: 1.888334
        return JSONObject()
            .put("id", provisionalId)
            .put("address", address)
            .put("latitude", lat)
            .put("longitude", lon)
            .put("radius", prefs.getInt("radius", 150).coerceIn(50, 1000))
            .put("pointType", "POSTE")
            .put("pointSource", custom?.optString("source", "provisional") ?: "provisional")
            .apply {
                PlaceNames.get(context, address)?.takeIf { it.isNotBlank() }?.let { put("label", it) }
            }
    }

    /**
     * Réapplique les points choisis par l'utilisateur après tout regéocodage.
     * Si Android n'arrive plus à géocoder une adresse, on recrée quand même la zone
     * à partir du point manuel enregistré au lieu de la perdre silencieusement.
     */
    private fun reapplyStoredOverrides() {
        val snapshot = GpsZoneEditorStoreV2.read(prefs)
        val source = snapshot?.let { GpsZonesReadResult.Valid(it.zones).toMutableJsonArrayOrNull() }
        if (snapshot == null || source == null) {
            GeofenceManager.reconfigureStoredZones(context)
            return
        }
        val custom = snapshot.json("zone_point_overrides")
        val addresses = snapshot.addresses()
        var changed = false

        for (i in 0 until source.length()) {
            val zone = source.optJSONObject(i) ?: continue
            val address = zone.optString("address").trim()
            val zoneId = zone.optString("id").trim()
            val point = pointOverrideFor(zoneId, address, custom) ?: continue
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
            if (zonesForAddress(address, source).isNotEmpty() || source.length() >= 10) return@forEach
            if (snapshot.zones.any { it.id == address }) return@forEach
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
                    .put("pointType", "OTHER")
                    .put("pointSource", point.optString("source", "manual"))
                    .apply {
                        PlaceNames.get(context, address)?.takeIf { it.isNotBlank() }?.let { put("label", it) }
                    }
            )
            changed = true
        }

        if (changed) {
            applyingOverride = true
            val values = snapshot.values.toMutableMap().apply { put("zones", source.toString()) }
            val saved = try {
                GpsZoneEditorStoreV2.commit(prefs, GpsZoneEditorStoreV2.Change(snapshot, values))
            } finally { applyingOverride = false }
            if (saved) registerCurrentZones()
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
        val storedZones = zones()
        if (storedZones == null) {
            GeofenceManager.reconfigureStoredZones(context)
            return
        }
        val matching = zonesForAddress(pending, storedZones)
        if (matching.size > 1) {
            prefs.edit().remove("pending_point_address").apply()
            Toast.makeText(
                context,
                "Plusieurs zones utilisent cette adresse : choisis explicitement la zone à ajuster.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val zone = matching.singleOrNull() ?: provisionalZone(pending)
        promptScheduled = true
        postDelayed({
            promptScheduled = false
            if (isAttachedToWindow && isShown) showMapPicker(zone, automatic = true)
        }, 300L)
    }

    private fun choosePlaceManually() {
        val addresses = savedAddresses()
        val snapshot = GpsZoneEditorStoreV2.read(prefs)
        if (snapshot == null) {
            Toast.makeText(context, "Configuration GPS à vérifier", Toast.LENGTH_LONG).show()
            return
        }
        if (addresses.isEmpty() && snapshot.zones.none { !it.isGpsCandidate() }) {
            Toast.makeText(context, "Ajoute d'abord un lieu", Toast.LENGTH_SHORT).show()
            return
        }
        val list = GpsZonesReadResult.Valid(snapshot.zones).toMutableJsonArrayOrNull()
        if (list == null) {
            GeofenceManager.reconfigureStoredZones(context)
            Toast.makeText(context, "Configuration GPS illisible : aucun point n'a été modifié", Toast.LENGTH_LONG).show()
            return
        }
        val labels = ArrayList<String>()
        val items = ArrayList<JSONObject>()
        val coveredAddresses = mutableSetOf<String>()

        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val address = item.optString("address").trim()
            if (address.isBlank()) continue
            if (item.optBoolean("smartCandidate", false)) continue
            val zoneId = item.optString("id").trim().takeIf { it.isNotBlank() }
            val placeName = PlaceNames.get(context, zoneId, address)?.takeIf { it.isNotBlank() }
            val type = item.optString("pointType").trim().takeIf { it.isNotBlank() }
            labels += buildString {
                append(placeName ?: address)
                if (placeName != null) append(" — ").append(address)
                if (type != null) append(" • ").append(type)
            }
            items += JSONObject(item.toString())
            coveredAddresses += address.lowercase(Locale.FRANCE)
        }

        addresses
            .filterNot { it.lowercase(Locale.FRANCE) in coveredAddresses }
            .forEach { address ->
                val item = provisionalZone(address)
                labels += address
                items += item
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

    internal fun adjustZone(zoneId: String) {
        val snapshot = GpsZoneEditorStoreV2.read(prefs) ?: return
        val zone = snapshot.zones.singleOrNull { it.id == zoneId && !it.isGpsCandidate() } ?: return
        showMapPicker(JSONObject(zone.sourceJson), automatic = false)
    }

    private fun showMapPicker(requestedZone: JSONObject, automatic: Boolean) {
        val snapshot = GpsZoneEditorStoreV2.read(prefs)
        if (snapshot == null) {
            Toast.makeText(context, "Configuration GPS à vérifier : aucun point ne peut être enregistré", Toast.LENGTH_LONG).show()
            return
        }
        val known = snapshot.zones.singleOrNull { it.id == requestedZone.optString("id").trim() && !it.isGpsCandidate() }
        val zone = known?.let { JSONObject(it.sourceJson) } ?: requestedZone
        val address = zone.optString("address").trim()
        if (address.isBlank()) return
        val creating = known == null && zone.optString("pointSource") == "provisional" &&
            snapshot.groups().any { it.legacyOnly && it.address.equals(address, ignoreCase = true) }
        if (known == null && !creating) {
            Toast.makeText(context, "Cette zone a changé ou a été supprimée. Rouvre sa fiche.", Toast.LENGTH_LONG).show()
            return
        }
        var pointChosen = !creating
        val zoneId = zone.optString("id").trim()
        val customPoint = pointOverrideFor(zoneId, address)
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
            this.text = "📍 ${PlaceNames.get(context, zone.optString("id"), address) ?: address}"
            textSize = 19f
            setTextColor(accent)
            setPadding(0, 0, 0, dp(5))
        })
        root.addView(TextView(context).apply {
            this.text = if (automatic)
                "Place le repère au centre réel de ta zone de travail. Tu peux déplacer la carte, zoomer et déplacer le repère."
            else "Déplace le repère sur le centre réel de la zone GPS. L'adresse postale ne changera pas."
            if (creating) this.text = "Choisis explicitement le point sur la carte. Nouvelle zone sans association automatique, rôle à confirmer. Rayon : ${zone.optInt("radius", 150)} m."
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
                if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                    !longitude.isFinite() || longitude !in -180.0..180.0) return
                post {
                    selectedLat = latitude
                    selectedLon = longitude
                    pointChosen = true
                    coordinateLabel.text = "${fmt(latitude)}, ${fmt(longitude)}"
                }
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
        addressButton.isEnabled = !creating
        val positionButton = actionButton("MA POSITION") {
            val now = currentLocation()
            if (now == null) {
                Toast.makeText(context, "Position actuelle indisponible pour le moment", Toast.LENGTH_SHORT).show()
            } else {
                selectedLat = now.latitude
                selectedLon = now.longitude
                pointChosen = true
                coordinateLabel.text = "${fmt(selectedLat)}, ${fmt(selectedLon)}  ±${now.accuracy.toInt()} m"
                webView.evaluateJavascript("setPoint($selectedLat,$selectedLon,true);", null)
            }
        }
        quick.addView(addressButton, LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        quick.addView(positionButton, LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
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
            if (!pointChosen) {
                Toast.makeText(context, "Choisis le centre réel en touchant la carte ou avec Ma position", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (savePoint(snapshot, zone, selectedLat, selectedLon, "map", creating)) dialog.dismiss()
        }
        later.setOnClickListener {
            if (automatic && GpsZoneEditorStoreV2.read(prefs)?.values == snapshot.values &&
                snapshot.values["pending_point_address"]?.equals(address, ignoreCase = true) == true) {
                prefs.edit().remove("pending_point_address").commit()
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showCoordinateEntry(zone: JSONObject) {
        val snapshot = GpsZoneEditorStoreV2.read(prefs) ?: return
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
                } else savePoint(snapshot, zone, lat, lon, "manual_coordinates", false)
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

    private fun savePoint(snapshot: GpsZoneEditorStoreV2.Snapshot, zone: JSONObject,
                          latitude: Double, longitude: Double, source: String, creating: Boolean): Boolean {
        val address = zone.optString("address").trim()
        val zoneId = zone.optString("id").trim()
        val change = if (!creating) {
            GpsZoneEditorStoreV2.setPoint(snapshot, zoneId, latitude, longitude, source)
        } else {
            val group = snapshot.groups().singleOrNull {
                it.legacyOnly && it.address.equals(address, ignoreCase = true)
            } ?: return false
            val contact = snapshot.json("arrival_contacts").optJSONObject(address)
            val name = snapshot.json("address_names").optString(address).trim().ifBlank { address }
            val draft = GpsZoneDraftV2(name, latitude, longitude,
                zone.optDouble("radius", Double.NaN), GpsZoneRoleV2.OTHER,
                contact?.optString("contactName").orEmpty(), contact?.optString("phone").orEmpty(),
                contact?.optBoolean("enabled", false) == true)
            val prepared = GpsZoneEditorStoreV2.saveZone(snapshot, group, null, zoneId, draft, 10, source)
            prepared?.let {
                val values = it.values.toMutableMap()
                if (values["pending_point_address"]?.equals(address, ignoreCase = true) == true) {
                    values["pending_point_address"] = null
                }
                it.copy(values = values)
            }
        }
        if (change == null) {
            Toast.makeText(context, "Zone ou coordonnées invalides : aucun point enregistré", Toast.LENGTH_LONG).show()
            return false
        }
        applyingOverride = true
        val saved = try { GpsZoneEditorStoreV2.commit(prefs, change) } finally { applyingOverride = false }
        if (!saved) {
            Toast.makeText(context, "Configuration modifiée depuis l'ouverture ou écriture non confirmée. Rouvre la carte.", Toast.LENGTH_LONG).show()
            return false
        }
        GeofenceManager.reconfigureStoredZones(context) { success, message ->
            post { Toast.makeText(context, if (success) "Point GPS enregistré — rayon et autres zones conservés"
                else "Point GPS enregistré. $message", Toast.LENGTH_LONG).show() }
        }
        return true
    }

    private fun registerCurrentZones() {
        GeofenceManager.reconfigureStoredZones(context)
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
        return recent.filter { it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy > 0f }.minWithOrNull(
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
    private fun dp(value: Int) = kotlin.math.ceil(value * resources.displayMetrics.density.toDouble()).toInt()
}
