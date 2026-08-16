package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class PointageApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.window.decorView.post {
            AppearanceManager.apply(activity)
            if (activity is MainActivity) SettingsUiInstaller.install(activity)
        }
    }
    override fun onActivityResumed(activity: Activity) { AppearanceManager.apply(activity) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

object AppearanceManager {
    private const val PREFS = "appearance_settings"

    fun apply(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "auto") ?: "auto"
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && systemDark)
        val defaultBg = if (dark) "#080808" else "#F3F0E8"
        val defaultPanel = if (dark) "#121212" else "#FFFFFF"
        val bg = parseColor(prefs.getString("app_bg", null), defaultBg)
        val panel = if (prefs.getBoolean("custom_bg", false)) shift(bg, if (dark) 1.20f else 0.93f) else Color.parseColor(defaultPanel)
        val text = if (dark) Color.parseColor("#F4EFE3") else Color.parseColor("#17130D")
        val secondary = if (dark) Color.parseColor("#A99F8C") else Color.parseColor("#625B50")

        activity.window.statusBarColor = bg
        activity.window.navigationBarColor = bg
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        root.setBackgroundColor(bg)
        recolor(root, bg, panel, text, secondary)
    }

    private fun recolor(view: View, bg: Int, panel: Int, text: Int, secondary: Int) {
        if (view is ViewGroup) {
            if (view.parent != null && view.background != null && view !is android.widget.ScrollView) {
                val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
                if (idName.contains("Panel", true) || idName.contains("Card", true) || idName == "contentPanel") {
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(panel)
                }
            }
            for (i in 0 until view.childCount) recolor(view.getChildAt(i), bg, panel, text, secondary)
        }
        if (view is TextView) {
            val current = view.currentTextColor
            if (current == Color.parseColor("#F4EFE3")) view.setTextColor(text)
            if (current == Color.parseColor("#A99F8C")) view.setTextColor(secondary)
        }
        if (view is android.widget.ScrollView) view.setBackgroundColor(bg)
    }

    private fun parseColor(value: String?, fallback: String): Int = runCatching { Color.parseColor(value ?: fallback) }.getOrElse { Color.parseColor(fallback) }
    private fun shift(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0,255),
        (Color.green(color) * factor).toInt().coerceIn(0,255),
        (Color.blue(color) * factor).toInt().coerceIn(0,255)
    )
}

object PlaceNames {
    fun get(context: Context, address: String): String? {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        return runCatching {
            val obj = JSONObject(prefs.getString("address_names", "{}") ?: "{}")
            obj.optString(address).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun put(context: Context, address: String, name: String) {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val obj = runCatching { JSONObject(prefs.getString("address_names", "{}") ?: "{}") }.getOrElse { JSONObject() }
        obj.put(address, name)
        prefs.edit().putString("address_names", obj.toString()).apply()
    }

    fun display(context: Context, address: String): String {
        val name = get(context, address)
        return if (name.isNullOrBlank()) address else "$name — $address"
    }
}

object SettingsUiInstaller {
    private const val TAG = "settings_personalization_installed"

    fun install(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        if (panel.findViewWithTag<View>(TAG) != null) return
        val address = activity.findViewById<EditText>(R.id.workplaceAddress)
        address.isFocusable = false
        address.isClickable = false

        val addAddress = AddAddressButton(activity).apply {
            text = "+ AJOUTER UNE ADRESSE"
            tag = TAG
        }
        panel.addView(addAddress, 2)

        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 28, 0, 0)
        }
        section.addView(title(activity, "APPARENCE DE L'APPLICATION"))

        val modeButton = Button(activity)
        fun updateModeLabel() {
            val mode = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).getString("mode", "auto") ?: "auto"
            modeButton.text = "MODE : " + when(mode){"light"->"CLAIR";"dark"->"SOMBRE";else->"AUTOMATIQUE"}
        }
        updateModeLabel()
        modeButton.setOnClickListener {
            val values = arrayOf("Automatique", "Clair", "Sombre")
            AlertDialog.Builder(activity).setTitle("Mode d'affichage").setItems(values) { _, which ->
                val mode = arrayOf("auto","light","dark")[which]
                activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit().putString("mode", mode).apply()
                updateModeLabel(); AppearanceManager.apply(activity)
            }.show()
        }
        section.addView(modeButton)

        val bgButton = Button(activity).apply { text = "COULEUR / FOND DE L'APPLICATION" }
        bgButton.setOnClickListener { chooseAppBackground(activity) }
        section.addView(bgButton)

        val resetBg = Button(activity).apply { text = "RÉINITIALISER LE FOND" }
        resetBg.setOnClickListener {
            activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit().remove("app_bg").putBoolean("custom_bg", false).apply()
            AppearanceManager.apply(activity)
        }
        section.addView(resetBg)

        section.addView(title(activity, "PERSONNALISER LE WIDGET"))
        val widgetBg = Button(activity).apply { text = "COULEUR DU FOND DU WIDGET" }
        widgetBg.setOnClickListener { chooseWidgetColor(activity, "widget_bg", "Fond du widget") }
        section.addView(widgetBg)

        val widgetAccent = Button(activity).apply { text = "COULEUR D'ACCENT DU WIDGET" }
        widgetAccent.setOnClickListener { chooseWidgetColor(activity, "widget_accent", "Accent du widget") }
        section.addView(widgetAccent)

        val showPosition = Switch(activity).apply {
            text = "Afficher la position dans le widget"
            isChecked = activity.getSharedPreferences("widget_style", Context.MODE_PRIVATE).getBoolean("show_position", true)
            setOnCheckedChangeListener { _, checked ->
                activity.getSharedPreferences("widget_style", Context.MODE_PRIVATE).edit().putBoolean("show_position", checked).apply()
                PointageWidgetProvider.updateAll(activity)
            }
        }
        section.addView(showPosition)

        section.addView(title(activity, "SAUVEGARDE GOOGLE DRIVE"))

        val driveStatus = TextView(activity).apply {
            textSize = 14f
            text = if (DriveBackupManager.isConfigured(activity)) {
                "● Sauvegarde Drive active — PDF classés par lieu / année / mois"
            } else {
                "Drive non configuré"
            }
        }
        section.addView(driveStatus)

        val chooseDrive = Button(activity).apply {
            text = if (DriveBackupManager.isConfigured(activity)) "CHANGER LE DOSSIER GOOGLE DRIVE" else "CHOISIR LE DOSSIER GOOGLE DRIVE"
            setOnClickListener {
                activity.startActivity(Intent(activity, DriveFolderPickerActivity::class.java))
            }
        }
        section.addView(chooseDrive)

        val syncDrive = Button(activity).apply {
            text = "SYNCHRONISER TOUT L'HISTORIQUE"
            setOnClickListener {
                if (!DriveBackupManager.isConfigured(activity)) {
                    Toast.makeText(activity, "Choisis d'abord un dossier Google Drive", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, "Synchronisation Drive démarrée", Toast.LENGTH_SHORT).show()
                    DriveBackupManager.syncAllAsync(activity) { ok, message ->
                        activity.runOnUiThread {
                            Toast.makeText(activity, if (ok) "Drive : $message" else "Erreur Drive : $message", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        section.addView(syncDrive)

        val forgetDrive = Button(activity).apply {
            text = "DÉCONNECTER LE DOSSIER DRIVE"
            setOnClickListener {
                DriveBackupManager.clear(activity)
                driveStatus.text = "Drive non configuré"
                Toast.makeText(activity, "Sauvegarde Drive désactivée", Toast.LENGTH_SHORT).show()
            }
        }
        section.addView(forgetDrive)

        panel.addView(section)
    }

    private fun title(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.parseColor("#D6A84B"))
        setPadding(0, 18, 0, 10)
    }

    private fun chooseAppBackground(activity: Activity) {
        val labels = arrayOf("Noir", "Anthracite", "Bleu nuit", "Vert profond", "Bordeaux", "Beige clair", "Couleur personnalisée")
        val colors = arrayOf("#080808", "#242424", "#0D1B2A", "#102A20", "#351015", "#F3F0E8")
        AlertDialog.Builder(activity).setTitle("Fond de l'application").setItems(labels) { _, which ->
            if (which < colors.size) saveAppBg(activity, colors[which]) else customColorDialog(activity, "Couleur du fond") { saveAppBg(activity, it) }
        }.show()
    }

    private fun saveAppBg(activity: Activity, color: String) {
        activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit().putString("app_bg", color).putBoolean("custom_bg", true).apply()
        AppearanceManager.apply(activity)
    }

    private fun chooseWidgetColor(activity: Activity, key: String, title: String) {
        val labels = arrayOf("Noir", "Anthracite", "Bleu nuit", "Vert profond", "Doré", "Blanc", "Couleur personnalisée")
        val colors = arrayOf("#080808", "#242424", "#0D1B2A", "#102A20", "#D6A84B", "#FFFFFF")
        AlertDialog.Builder(activity).setTitle(title).setItems(labels) { _, which ->
            if (which < colors.size) saveWidgetColor(activity,key,colors[which]) else customColorDialog(activity,title){ saveWidgetColor(activity,key,it) }
        }.show()
    }

    private fun saveWidgetColor(activity: Activity, key: String, color: String) {
        activity.getSharedPreferences("widget_style", Context.MODE_PRIVATE).edit().putString(key,color).apply()
        PointageWidgetProvider.updateAll(activity)
    }

    private fun customColorDialog(activity: Activity, title: String, onSave:(String)->Unit) {
        val input = EditText(activity).apply { hint = "#1A1A1A"; setText("#1A1A1A") }
        AlertDialog.Builder(activity).setTitle(title).setView(input).setPositiveButton("Appliquer") { _, _ ->
            val value = input.text.toString().trim()
            if (runCatching { Color.parseColor(value) }.isSuccess) onSave(value) else Toast.makeText(activity,"Couleur invalide",Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Annuler", null).show()
    }
}
