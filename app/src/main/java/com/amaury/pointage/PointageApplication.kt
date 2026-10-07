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
import com.amaury.pointage.v2.HoraTrackV2
import org.json.JSONObject
import java.io.File

class PointageApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        ConventionCatalog.initialize(this)
        PersonalizationRuntimeV2.install(this)
        NightContextRuntimeV2.install(this)
        UniversalWritingInstaller.install(this)
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
            SettingsUiInstaller.refreshDriveSection(activity)
            SettingsCompactMenuV2.installOrRefresh(activity)
            activity.findViewById<LocationManagementView>(R.id.locationManagementView)?.refresh()
            activity.findViewById<ShiftControlView>(R.id.shiftControlView)?.refresh()
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

    /** Shared by window chrome, the scrolling canvas and text contrast. */
    fun backgroundColor(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = AppThemeCatalog.current(context)
        val fallback = if (AppThemeCatalog.useDarkPalette(context)) theme.darkBackground else theme.lightBackground
        return if (prefs.getBoolean("custom_bg", false)) parseColor(prefs.getString("app_bg", null), fallback) else fallback
    }

    fun applyDialog(dialog: AlertDialog) {
        val root = dialog.window?.decorView ?: return
        // The reader deliberately owns its high-contrast canvas and speech controls.
        if (root.findViewWithTag<View>("personalization_reader_v2") != null) return
        val bg = backgroundColor(root.context)
        val panel = if (PersonalizationStoreV2.read(root.context).highContrast) Color.BLACK
            else shift(bg, if (isDark(bg)) 1.24f else .91f)
        val foreground = bestTextColor(panel)
        val disabledColor = if (foreground == Color.WHITE) Color.LTGRAY else Color.DKGRAY
        val colors = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(if (contrastRatio(disabledColor, panel) >= 4.5) disabledColor else foreground, foreground))
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(panel)
            cornerRadius = 20f * root.resources.displayMetrics.density
        })
        fun style(view: View) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
            if (view is ViewGroup) {
                if (name in setOf("parentPanel", "topPanel", "contentPanel", "customPanel", "buttonPanel")) {
                    view.backgroundTintList = null
                    view.setBackgroundColor(Color.TRANSPARENT)
                }
                for (i in 0 until view.childCount) style(view.getChildAt(i))
            }
            if (view is TextView) {
                view.setTextColor(colors)
                if (view is EditText) {
                    view.setHintTextColor(foreground)
                    view.backgroundTintList = ColorStateList.valueOf(foreground)
                } else if (view is Button) {
                    view.backgroundTintList = null
                    val left = view.paddingLeft; val top = view.paddingTop
                    val right = view.paddingRight; val bottom = view.paddingBottom
                    val shape = view.resources.getDrawable(R.drawable.hp_panel, view.context.theme).mutate()
                    (shape as? android.graphics.drawable.GradientDrawable)?.setColor(panel)
                    view.background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(if (foreground == Color.WHITE) 0x33FFFFFF else 0x33000000), shape, null)
                    view.setPadding(left, top, right, bottom)
                }
            }
        }
        style(root)
    }

    fun apply(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dark = AppThemeCatalog.useDarkPalette(activity)
        val theme = AppThemeCatalog.current(activity)
        val defaultPanel = if (dark) theme.darkPanel else theme.lightPanel
        val customColor = prefs.getBoolean("custom_bg", false)
        val bg = backgroundColor(activity)
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
        contentRoot.findViewWithTag<TextView>("settings_display_mode_v2")?.text = DisplayModeSettingsV2.label(activity)
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
        PersonalizationRuntimeV2.apply(contentRoot)
        if (activity is MainActivity) {
            activity.findViewById<LinearLayout>(R.id.navigationTabs)?.let(NavigationTabContrastV2::apply)
        }
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
                val protected = idName == "entryButton" || idName == "pauseButton" || idName == "exitButton"
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

    fun bestTextColor(background: Int): Int = VisualContrastV2.bestText(background)

    fun contrastRatio(foreground: Int, background: Int): Double = VisualContrastV2.ratio(foreground, background)

    private fun isDark(color: Int): Boolean = ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000) < 145
    private fun parseColor(value: String?, fallback: Int): Int = runCatching { Color.parseColor(value ?: "") }.getOrDefault(fallback)
    private fun shift(color: Int, factor: Float) = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )
}

object PlaceNames {
    private const val LEGACY_KEY = "address_names"

    fun get(context: Context, address: String): String? =
        get(context, zoneId = null, address = address)

    fun get(context: Context, zoneId: String?, address: String): String? {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val canonical = resolveGpsZoneLabel(readPersistedGpsZones(prefs), zoneId, address)
        if (!canonical.isNullOrBlank()) return canonical

        // Compatibilité de migration uniquement : les nouveaux noms appartiennent à la zone GPS.
        return runCatching {
            JSONObject(prefs.getString(LEGACY_KEY, "{}") ?: "{}")
                .optString(address).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun put(context: Context, address: String, name: String) {
        put(context, zoneId = null, address = address, name = name)
    }

    fun put(context: Context, zoneId: String?, address: String, name: String) {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val stored = readPersistedGpsZones(prefs)
        if (stored is GpsZonesReadResult.Corrupt) return

        val resolvedId = zoneId?.trim()?.takeIf { it.isNotBlank() }
            ?: (stored as? GpsZonesReadResult.Valid)
                ?.zones
                ?.filter { it.address?.trim()?.equals(address.trim(), ignoreCase = true) == true }
                ?.singleOrNull()
                ?.id

        if (!resolvedId.isNullOrBlank()) {
            val zones = stored.toMutableJsonArrayOrNull() ?: return
            if (updateGpsZoneLabelById(zones, resolvedId, name)) {
                val legacy = runCatching {
                    JSONObject(prefs.getString(LEGACY_KEY, "{}") ?: "{}")
                }.getOrElse { JSONObject() }
                legacy.remove(address)
                prefs.edit()
                    .putString("zones", zones.toString())
                    .putString(LEGACY_KEY, legacy.toString())
                    .apply()
                return
            }
        }

        // Compatibilité transitoire uniquement si aucun propriétaire de zone unique
        // ne peut encore être résolu. La configuration corrompue reste bloquée plus haut.
        val legacy = runCatching {
            JSONObject(prefs.getString(LEGACY_KEY, "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        if (name.isBlank()) legacy.remove(address) else legacy.put(address, name.trim())
        prefs.edit().putString(LEGACY_KEY, legacy.toString()).apply()
    }

    fun display(context: Context, address: String): String {
        val name = get(context, address)
        return if (name.isNullOrBlank()) address else "$name — $address"
    }

    fun display(context: Context, zoneId: String?, address: String): String {
        val name = get(context, zoneId, address)
        return if (name.isNullOrBlank()) address else "$name — $address"
    }
}

object SettingsUiInstaller {
    private const val TAG = SettingsV2Host.TAG_PERSONALIZATION

    fun install(activity: MainActivity) {
        val panel = SettingsV2Host.panel(activity) ?: return
        if (panel.findViewWithTag<View>(TAG) != null) return

        activity.findViewById<EditText>(R.id.workplaceAddress)?.apply {
            isFocusable = false
            isClickable = false
        }

        val updates = settingsSection(activity, SettingsV2Host.TAG_UPDATES)
        updates.addView(title(activity, "MISES À JOUR"))
        updates.addView(styledButton(activity, "VÉRIFIER LES MISES À JOUR").apply {
            tag = "settings_check_updates"
            setOnClickListener {
                UpdateChecker.check(
                    activity = activity,
                    silent = false,
                    askBeforeDownload = true
                )
            }
        })

        val appearance = settingsSection(activity, SettingsV2Host.TAG_PERSONALIZATION)
        appearance.addView(title(activity, "APPARENCE DE L'APPLICATION"))
        appearance.addView(styledButton(activity, "CONFORT VISUEL ET ÉCRITURE").apply {
            setOnClickListener { PersonalizationSettingsV2.open(activity) }
        })
        val modeButton = styledButton(activity, "").apply { tag = "settings_display_mode_v2" }
        fun updateModeLabel() { modeButton.text = DisplayModeSettingsV2.label(activity) }
        updateModeLabel()
        modeButton.setOnClickListener {
            DisplayModeSettingsV2.open(activity, ::updateModeLabel)
        }
        appearance.addView(modeButton)

        val bgButton = styledButton(activity, "COULEUR DU FOND")
        bgButton.setOnClickListener { chooseAppBackground(activity) }
        appearance.addView(bgButton)

        val imageButton = styledButton(activity, "CHOISIR UNE IMAGE DE FOND")
        imageButton.setOnClickListener { activity.startActivity(Intent(activity, BackgroundPickerActivity::class.java)) }
        appearance.addView(imageButton)

        val resetBg = styledButton(activity, "RÉINITIALISER LE FOND")
        resetBg.setOnClickListener {
            File(activity.filesDir, AppearanceManager.BACKGROUND_FILE).delete()
            activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit()
                .remove("app_bg").putBoolean("custom_bg", false).putBoolean("custom_image_bg", false).apply()
            AppearanceManager.apply(activity)
        }
        appearance.addView(resetBg)

        val widget = settingsSection(activity, SettingsV2Host.TAG_WIDGET)
        widget.addView(title(activity, "WIDGET"))
        val widgetBg = styledButton(activity, "COULEUR DU FOND DU WIDGET")
        widgetBg.setOnClickListener { chooseWidgetColor(activity, WidgetStyleSettings.KEY_BACKGROUND, "Fond du widget") }
        widget.addView(widgetBg)
        val widgetAccent = styledButton(activity, "COULEUR D'ACCENT DU WIDGET")
        widgetAccent.setOnClickListener { chooseWidgetColor(activity, WidgetStyleSettings.KEY_ACCENT, "Accent du widget") }
        widget.addView(widgetAccent)

        val showPosition = Switch(activity).apply {
            text = "Afficher la position dans le widget"
            textSize = 14f
            isChecked = activity.getSharedPreferences(WidgetStyleSettings.PREFS, Context.MODE_PRIVATE).getBoolean(WidgetStyleSettings.KEY_SHOW_POSITION, true)
            setOnCheckedChangeListener { _, checked ->
                activity.getSharedPreferences(WidgetStyleSettings.PREFS, Context.MODE_PRIVATE).edit().putBoolean(WidgetStyleSettings.KEY_SHOW_POSITION, checked).apply()
                PointageWidgetProvider.updateAll(activity)
                QuickActionsWidgetProvider.updateAll(activity)
            }
        }
        widget.addView(showPosition)

        val drive = settingsSection(activity, SettingsV2Host.TAG_DRIVE)
        drive.addView(title(activity, "SAUVEGARDE & DONNÉES"))
        drive.addView(TextView(activity).apply {
            tag = "settings_drive_status"
            textSize = 14f
        })
        drive.addView(styledButton(activity, "").apply {
            tag = "settings_drive_folder"
            setOnClickListener { activity.startActivity(Intent(activity, DriveFolderPickerActivity::class.java)) }
        })
        drive.addView(styledButton(activity, "SYNCHRONISER TOUT L'HISTORIQUE").apply {
            tag = "settings_drive_sync_all"
            setOnClickListener {
                Toast.makeText(activity, "Synchronisation Drive démarrée", Toast.LENGTH_SHORT).show()
                DriveBackupManager.syncAllAsync(activity) { ok, message ->
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            if (ok) "Drive : $message" else "Erreur Drive : $message",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
        drive.addView(styledButton(activity, "DÉCONNECTER LE DOSSIER DRIVE").apply {
            tag = "settings_drive_disconnect"
            setOnClickListener {
                DriveBackupManager.clear(activity)
                refreshDriveSection(activity)
                Toast.makeText(activity, "Sauvegarde Drive désactivée", Toast.LENGTH_SHORT).show()
            }
        })

        val help = settingsSection(activity, SettingsV2Host.TAG_HELP)
        help.addView(title(activity, "AIDE"))
        help.addView(styledButton(activity, "NOTICE D'UTILISATION").apply {
            setOnClickListener { UserGuideDialog.show(activity) }
        })

        listOf(updates, appearance, widget, drive, help).forEach(panel::addView)
        SettingsV2SectionOrganizer.organize(activity)
        installPointageAddressButton(activity)
        refreshDriveSection(activity)
        SettingsCompactMenuV2.installOrRefresh(activity)
        AppearanceManager.apply(activity)
    }

    fun refreshDriveSection(activity: MainActivity) {
        val drive = SettingsV2Host.section(activity, SettingsV2Host.TAG_DRIVE) ?: return
        val configured = DriveBackupManager.isConfigured(activity)
        drive.findViewWithTag<TextView>("settings_drive_status")?.text = when {
            !configured -> "Drive non configuré"
            HoraTrackV2.ENABLED -> "Drive configuré — pointages et réglages fonctionnels"
            else -> "Drive configuré — PDF classés par lieu / année / mois"
        }
        drive.findViewWithTag<Button>("settings_drive_folder")?.text =
            if (configured) "CHANGER LE DOSSIER GOOGLE DRIVE" else "CHOISIR LE DOSSIER GOOGLE DRIVE"
        drive.findViewWithTag<View>("settings_drive_sync_all")?.visibility =
            if (configured && !HoraTrackV2.ENABLED) View.VISIBLE else View.GONE
        drive.findViewWithTag<View>("settings_drive_disconnect")?.visibility =
            if (configured) View.VISIBLE else View.GONE
    }

    private fun installPointageAddressButton(activity: MainActivity) {
        val section = SettingsV2Host.section(activity, SettingsV2Host.TAG_POINTAGE) ?: return
        if (section.findViewWithTag<View>("add_address_button") != null) return
        val addressList = activity.findViewById<EditText>(R.id.workplaceAddress) ?: return
        val parent = addressList.parent as? ViewGroup ?: return
        if (parent !== section) return

        addressList.isFocusable = false
        addressList.isFocusableInTouchMode = false
        addressList.isCursorVisible = false
        addressList.isLongClickable = false
        addressList.hint = "Aucune adresse — utilise le bouton +"
        addressList.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))

        val addButton = AddAddressButton(activity).apply {
            tag = "add_address_button"
            text = "+  AJOUTER UNE ADRESSE"
            textSize = 14f
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 48)
            ).apply { topMargin = dp(activity, 8) }
        }
        val index = section.indexOfChild(addressList)
        section.addView(addButton, (index + 1).coerceAtMost(section.childCount))
    }

    private fun settingsSection(context: Context, sectionTag: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 14), 0, 0)
        tag = sectionTag
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
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)).apply {
            topMargin = dp(context, 4)
            bottomMargin = dp(context, 4)
        }
    }

    private fun dp(context: Context, value: Int) =
        kotlin.math.ceil(value * context.resources.displayMetrics.density.toDouble()).toInt()
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
        activity.getSharedPreferences(WidgetStyleSettings.PREFS, Context.MODE_PRIVATE).edit().putString(key, color).apply()
        PointageWidgetProvider.refreshAppearance(activity)
        QuickActionsWidgetProvider.refreshAppearance(activity)
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
