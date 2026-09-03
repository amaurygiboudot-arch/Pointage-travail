package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

class PointageApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        ConventionCatalog.initialize(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.window.decorView.post {
            AppearanceManager.apply(activity)
            if (activity is MainActivity) {
                SettingsUiInstaller.install(activity)
                LuxuryUiInstaller.install(activity)
                UpdateChecker.checkAutomatically(activity)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        AppearanceManager.apply(activity)
        if (activity is MainActivity) {
            // Si le téléchargement s'est terminé pendant que HP Travail était en arrière-plan,
            // ouvre immédiatement l'installateur au retour dans l'application.
            UpdateChecker.checkAutomatically(activity)
            activity.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
            PointageWidgetProvider.updateAll(activity)
            QuickActionsWidgetProvider.updateAll(activity)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

object AppearanceManager {
    private const val PREFS = "appearance_settings"
    const val BACKGROUND_FILE = "custom_app_background.jpg"

    fun apply(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dark = AppThemeCatalog.useDarkPalette(activity)
        val theme = AppThemeCatalog.current(activity)
        val defaultBg = if (dark) theme.darkBackground else theme.lightBackground
        val defaultPanel = if (dark) theme.darkPanel else theme.lightPanel
        val customColor = prefs.getBoolean("custom_bg", false)
        val bg = if (customColor) parseColor(prefs.getString("app_bg", null), defaultBg) else defaultBg
        val panel = if (customColor) shift(bg, if (isDark(bg)) 1.24f else 0.91f) else defaultPanel
        val imageFile = File(activity.filesDir, BACKGROUND_FILE)
        val hasImage = prefs.getBoolean("custom_image_bg", false) && imageFile.exists()

        activity.window.statusBarColor = bg
        activity.window.navigationBarColor = bg
        var flags = activity.window.decorView.systemUiVisibility
        if (!isDark(bg)) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        activity.window.decorView.systemUiVisibility = flags

        val contentRoot = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val firstChild = contentRoot.getChildAt(0)
        if (hasImage) {
            val bitmap = runCatching { BitmapFactory.decodeFile(imageFile.absolutePath) }.getOrNull()
            if (bitmap != null) {
                val drawable = BitmapDrawable(activity.resources, bitmap).apply { gravity = Gravity.FILL }
                contentRoot.background = drawable
                firstChild?.setBackgroundColor(Color.TRANSPARENT)
            } else {
                prefs.edit().putBoolean("custom_image_bg", false).apply()
                contentRoot.background = null
                contentRoot.setBackgroundColor(bg)
                firstChild?.setBackgroundColor(bg)
            }
        } else {
            contentRoot.background = null
            contentRoot.setBackgroundColor(bg)
            firstChild?.setBackgroundColor(bg)
        }

        recolor(contentRoot, bg, panel, hasImage, false)
    }

    private fun recolor(view: View, bg: Int, panel: Int, imageBg: Boolean, inheritedPanel: Boolean) {
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
        val ownPanel = idName.contains("Panel", true) || idName.contains("Card", true) || idName == "contentPanel"
        val onPanel = inheritedPanel || ownPanel
        val surface = if (onPanel) panel else bg

        if (view is ViewGroup) {
            if (ownPanel && view.background != null) view.backgroundTintList = ColorStateList.valueOf(panel)
            for (i in 0 until view.childCount) recolor(view.getChildAt(i), bg, panel, imageBg, onPanel)
        }

        val text = bestTextColor(surface)
        val secondary = if (isDark(surface)) Color.parseColor("#F0ECE4") else Color.parseColor("#333333")
        when (view) {
            is EditText -> {
                view.setTextColor(text)
                view.setHintTextColor(secondary)
            }
            is Button -> {
                val protected = idName == "entryButton" || idName == "pauseButton" || idName == "exitButton" || idName == "settingsButton"
                if (!protected) view.setTextColor(text)
            }
            is TextView -> {
                val gold = Color.parseColor("#D6A84B")
                val lightGold = Color.parseColor("#F3D58A")
                if (view.currentTextColor == gold || view.currentTextColor == lightGold) {
                    view.setTextColor(if (contrastRatio(lightGold, surface) >= 4.5) lightGold else text)
                } else {
                    view.setTextColor(text)
                }
            }
        }
        if (view is Switch) view.setTextColor(text)
        if (view is ScrollView) view.setBackgroundColor(if (imageBg) Color.TRANSPARENT else bg)
    }

    fun bestTextColor(background: Int): Int = if (isDark(background)) Color.WHITE else Color.parseColor("#111111")

    fun contrastRatio(foreground: Int, background: Int): Double {
        fun lum(c: Int): Double {
            fun channel(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))
        }
        val l1 = lum(foreground)
        val l2 = lum(background)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun isDark(color: Int): Boolean = ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000) < 145
    private fun parseColor(value: String?, fallback: Int): Int = runCatching { Color.parseColor(value ?: "") }.getOrDefault(fallback)
    private fun shift(color: Int, factor: Float) = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )
}

object PlaceNames {
    fun get(context: Context, address: String): String? {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        return runCatching {
            JSONObject(prefs.getString("address_names", "{}") ?: "{}")
                .optString(address).trim().takeIf { it.isNotBlank() }
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

        activity.findViewById<EditText>(R.id.workplaceAddress)?.apply {
            isFocusable = false
            isClickable = false
        }

        val settingsButton = activity.findViewById<Button>(R.id.settingsButton)
        val header = settingsButton.parent as? LinearLayout
        if (header != null && header.findViewWithTag<View>("main_back_button") == null) {
            val back = styledButton(activity, "←").apply {
                tag = "main_back_button"
                textSize = 24f
                layoutParams = LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)).apply { marginEnd = dp(activity, 8) }
                setOnClickListener { activity.findViewById<TextView>(R.id.tabToday)?.performClick() }
            }
            header.addView(back, 0)
        }

        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 14), 0, 0)
            tag = TAG
        }

        section.addView(title(activity, "APPARENCE DE L'APPLICATION"))
        val modeButton = styledButton(activity, "")
        fun updateModeLabel() {
            val mode = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).getString("mode", "auto") ?: "auto"
            modeButton.text = "MODE : " + when (mode) { "light" -> "CLAIR"; "dark" -> "SOMBRE"; else -> "AUTOMATIQUE JOUR / NUIT" }
        }
        updateModeLabel()
        modeButton.setOnClickListener {
            val values = arrayOf("Automatique jour / nuit", "Clair", "Sombre")
            AlertDialog.Builder(activity).setTitle("Mode d'affichage").setItems(values) { _, which ->
                val mode = arrayOf("auto", "light", "dark")[which]
                activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit().putString("mode", mode).apply()
                updateModeLabel()
                AppearanceManager.apply(activity)
                PointageWidgetProvider.updateAll(activity)
                QuickActionsWidgetProvider.updateAll(activity)
            }.show()
        }
        section.addView(modeButton)

        val bgButton = styledButton(activity, "COULEUR DU FOND")
        bgButton.setOnClickListener { chooseAppBackground(activity) }
        section.addView(bgButton)

        val imageButton = styledButton(activity, "CHOISIR UNE IMAGE DE FOND")
        imageButton.setOnClickListener { activity.startActivity(Intent(activity, BackgroundPickerActivity::class.java)) }
        section.addView(imageButton)

        val resetBg = styledButton(activity, "RÉINITIALISER LE FOND")
        resetBg.setOnClickListener {
            File(activity.filesDir, AppearanceManager.BACKGROUND_FILE).delete()
            activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit()
                .remove("app_bg").putBoolean("custom_bg", false).putBoolean("custom_image_bg", false).apply()
            AppearanceManager.apply(activity)
        }
        section.addView(resetBg)

        section.addView(title(activity, "PERSONNALISER LE WIDGET"))
        val widgetBg = styledButton(activity, "COULEUR DU FOND DU WIDGET")
        widgetBg.setOnClickListener { chooseWidgetColor(activity, "widget_bg", "Fond du widget") }
        section.addView(widgetBg)
        val widgetAccent = styledButton(activity, "COULEUR D'ACCENT DU WIDGET")
        widgetAccent.setOnClickListener { chooseWidgetColor(activity, "widget_accent", "Accent du widget") }
        section.addView(widgetAccent)

        val showPosition = Switch(activity).apply {
            text = "Afficher la position dans le widget"
            textSize = 14f
            isChecked = activity.getSharedPreferences("widget_style", Context.MODE_PRIVATE).getBoolean("show_position", true)
            setOnCheckedChangeListener { _, checked ->
                activity.getSharedPreferences("widget_style", Context.MODE_PRIVATE).edit().putBoolean("show_position", checked).apply()
                PointageWidgetProvider.updateAll(activity)
                QuickActionsWidgetProvider.updateAll(activity)
            }
        }
        section.addView(showPosition)

        section.addView(title(activity, "NOTICE"))
        section.addView(styledButton(activity, "📖 NOTICE D'UTILISATION").apply {
            setOnClickListener { UserGuideDialog.show(activity) }
        })

        section.addView(title(activity, "SAUVEGARDE GOOGLE DRIVE"))
        val driveStatus = TextView(activity).apply {
            textSize = 14f
            text = if (DriveBackupManager.isConfigured(activity)) "● Sauvegarde Drive active — PDF classés par lieu / année / mois" else "Drive non configuré"
        }
        section.addView(driveStatus)
        section.addView(styledButton(activity, if (DriveBackupManager.isConfigured(activity)) "CHANGER LE DOSSIER GOOGLE DRIVE" else "CHOISIR LE DOSSIER GOOGLE DRIVE").apply {
            setOnClickListener { activity.startActivity(Intent(activity, DriveFolderPickerActivity::class.java)) }
        })
        section.addView(styledButton(activity, "SYNCHRONISER TOUT L'HISTORIQUE").apply {
            setOnClickListener {
                if (!DriveBackupManager.isConfigured(activity)) {
                    Toast.makeText(activity, "Choisis d'abord un dossier Google Drive", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, "Synchronisation Drive démarrée", Toast.LENGTH_SHORT).show()
                    DriveBackupManager.syncAllAsync(activity) { ok, message ->
                        activity.runOnUiThread { Toast.makeText(activity, if (ok) "Drive : $message" else "Erreur Drive : $message", Toast.LENGTH_LONG).show() }
                    }
                }
            }
        })
        section.addView(styledButton(activity, "DÉCONNECTER LE DOSSIER DRIVE").apply {
            setOnClickListener {
                DriveBackupManager.clear(activity)
                driveStatus.text = "Drive non configuré"
                Toast.makeText(activity, "Sauvegarde Drive désactivée", Toast.LENGTH_SHORT).show()
            }
        })

        section.addView(title(activity, "MISES À JOUR"))
        section.addView(styledButton(activity, "VÉRIFIER LES MISES À JOUR").apply {
            setOnClickListener { UpdateChecker.check(activity, silent = false) }
        })

        panel.addView(section)
        AppearanceManager.apply(activity)
    }

    private fun styledButton(context: Context, label: String) = Button(context).apply {
        text = label
        setBackgroundResource(R.drawable.hp_panel)
        isAllCaps = false
        textSize = 14f
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        gravity = Gravity.CENTER
        setPadding(dp(context, 12), 0, dp(context, 12), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46)).apply {
            topMargin = dp(context, 4)
            bottomMargin = dp(context, 4)
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun title(context: Context, text: String) = TextView(context).apply { this.text = text; textSize = 16f; setPadding(0, dp(context, 18), 0, dp(context, 10)) }

    private fun chooseAppBackground(activity: Activity) {
        val labels = arrayOf("Noir", "Anthracite", "Bleu nuit", "Vert profond", "Bordeaux", "Beige clair", "Couleur personnalisée")
        val colors = arrayOf("#080808", "#242424", "#0D1B2A", "#102A20", "#351015", "#F3F0E8")
        AlertDialog.Builder(activity).setTitle("Fond de l'application").setItems(labels) { _, which ->
            if (which < colors.size) saveAppBg(activity, colors[which])
            else customColorDialog(activity, "Couleur du fond") { saveAppBg(activity, it) }
        }.show()
    }

    private fun saveAppBg(activity: Activity, color: String) {
        File(activity.filesDir, AppearanceManager.BACKGROUND_FILE).delete()
        activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit()
            .putString("app_bg", color).putBoolean("custom_bg", true).putBoolean("custom_image_bg", false).apply()
        AppearanceManager.apply(activity)
    }

    private fun chooseWidgetColor(activity: Activity, key: String, title: String) {
        val labels = arrayOf("Noir", "Anthracite", "Bleu nuit", "Vert profond", "Doré", "Blanc", "Couleur personnalisée")
        val colors = arrayOf("#080808", "#242424", "#0D1B2A", "#102A20", "#D6A84B", "#FFFFFF")
        AlertDialog.Builder(activity).setTitle(title).setItems(labels) { _, which ->
            if (which < colors.size) saveWidgetColor(activity, key, colors[which])
            else customColorDialog(activity, title) { saveWidgetColor(activity, key, it) }
        }.show()
    }

    private fun saveWidgetColor(activity: Activity, key: String, color: String) {
        activity.getSharedPreferences("widget_style", Context.MODE_PRIVATE).edit().putString(key, color).apply()
        PointageWidgetProvider.updateAll(activity)
        QuickActionsWidgetProvider.updateAll(activity)
        Toast.makeText(activity, "Widget mis à jour", Toast.LENGTH_SHORT).show()
    }

    private fun customColorDialog(activity: Activity, title: String, onSave: (String) -> Unit) {
        val input = EditText(activity).apply { hint = "#1A1A1A"; setText("#1A1A1A") }
        AlertDialog.Builder(activity).setTitle(title).setView(input)
            .setPositiveButton("Appliquer") { _, _ ->
                val value = input.text.toString().trim()
                if (runCatching { Color.parseColor(value) }.isSuccess) onSave(value)
                else Toast.makeText(activity, "Couleur invalide", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null).show()
    }
}
