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

/** Même qualification que l'éditeur de zone ; aucune pause inconnue ne devient Poste. */
class GpsZoneTypeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object { const val TAG = "gps_zone_type_v2" }
    private val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
    init { tag = TAG; orientation = VERTICAL; setPadding(0, dp(10), 0, dp(6)); rebuild() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(this); rebuild()
    }
    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this); super.onDetachedFromWindow()
    }
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "zones" || key == "address") post { rebuild() }
    }
    private fun rebuild() {
        removeAllViews()
        addView(TextView(context).apply { text = "RÔLE DES ZONES GPS"; textSize = 14f })
        addView(TextView(context).apply {
            text = "Travail, parking, pause ou autre contexte. Le GPS ne décide pas à lui seul du temps payé."
            textSize = 12f; setPadding(0, dp(4), 0, dp(6))
        })
        val snapshot = GpsZoneEditorStoreV2.read(prefs)
        if (snapshot == null) {
            addView(TextView(context).apply { text = "Configuration GPS à vérifier. Aucun rôle modifié." }); return
        }
        val zones = snapshot.zones.filter { !it.isGpsCandidate() && !it.address.isNullOrBlank() }
        if (zones.isEmpty()) addView(TextView(context).apply { text = "Ajoute d'abord une zone GPS." })
        zones.forEach { zone ->
            addView(Button(context).apply {
                text = "${zone.label ?: zone.address} • ${GpsZoneRoleV2.fromToken(zone.pointTypeToken).title}"
                isAllCaps = false; textSize = 14f; minHeight = dp(48)
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener { chooseType(zone.id) }
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        }
    }
    private fun chooseType(zoneId: String) {
        val snapshot = GpsZoneEditorStoreV2.read(prefs) ?: return
        val zone = snapshot.zones.singleOrNull { it.id == zoneId && !it.isGpsCandidate() } ?: return
        val roles = GpsZoneRoleV2.values()
        AlertDialog.Builder(context).setTitle("Rôle — ${zone.label ?: zone.address}")
            .setItems(roles.map { it.title }.toTypedArray()) { _, which ->
                val change = GpsZoneEditorStoreV2.setRole(snapshot, zoneId, roles[which])
                if (change == null || !GpsZoneEditorStoreV2.commit(prefs, change)) {
                    Toast.makeText(context, "Zone modifiée depuis l'ouverture ou écriture non confirmée. Rouvre sa fiche.", Toast.LENGTH_LONG).show()
                } else {
                    GeofenceManager.reconfigureStoredZones(context) { success, message ->
                        post { Toast.makeText(context, if (success) "Rôle enregistré" else "Rôle enregistré. $message", Toast.LENGTH_LONG).show() }
                    }
                    rebuild()
                }
            }.setNegativeButton("Annuler", null).show()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
