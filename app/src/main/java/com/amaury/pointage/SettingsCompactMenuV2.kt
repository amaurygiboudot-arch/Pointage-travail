package com.amaury.pointage

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Navigation compacte de Paramètres V2.
 *
 * Les sections existantes restent les propriétaires de leurs fonctions.
 * Ce composant ne fait que choisir quelle rubrique est visible afin d'éviter
 * d'afficher toutes les actions sur un seul écran.
 */
object SettingsCompactMenuV2 {
    private enum class Page {
        ACCOUNT,
        POINTAGE,
        PERSONALIZATION,
        BACKUP,
        HELP
    }

    private val activePages = WeakHashMap<MainActivity, Page?>()

    fun installOrRefresh(activity: MainActivity) {
        val panel = SettingsV2Host.panel(activity) ?: return
        ensureMenu(activity, panel)
        ensureBackControl(activity, panel)

        when (activePages[activity]) {
            Page.ACCOUNT -> showPage(activity, Page.ACCOUNT)
            Page.POINTAGE -> showPage(activity, Page.POINTAGE)
            Page.PERSONALIZATION -> showPage(activity, Page.PERSONALIZATION)
            Page.BACKUP -> showPage(activity, Page.BACKUP)
            Page.HELP -> showPage(activity, Page.HELP)
            null -> showMenu(activity)
        }
    }

    fun showMenu(activity: MainActivity) {
        activePages[activity] = null
        SettingsV2Host.panel(activity)?.let { panel ->
            panel.findViewWithTag<View>(SettingsV2Host.TAG_COMPACT_MENU)?.visibility = View.VISIBLE
            panel.findViewWithTag<View>(SettingsV2Host.TAG_COMPACT_BACK)?.visibility = View.GONE
        }
        managedSections(activity).forEach { it.visibility = View.GONE }
    }

    private fun showPage(activity: MainActivity, page: Page) {
        activePages[activity] = page
        val panel = SettingsV2Host.panel(activity) ?: return
        panel.findViewWithTag<View>(SettingsV2Host.TAG_COMPACT_MENU)?.visibility = View.GONE
        panel.findViewWithTag<View>(SettingsV2Host.TAG_COMPACT_BACK)?.visibility = View.VISIBLE

        val visibleTags = when (page) {
            Page.ACCOUNT -> setOf(SettingsV2Host.TAG_ACCOUNT_SECURITY)
            Page.POINTAGE -> setOf(SettingsV2Host.TAG_POINTAGE)
            Page.PERSONALIZATION -> setOf(
                SettingsV2Host.TAG_CELESTIAL,
                SettingsV2Host.TAG_PERSONALIZATION,
                SettingsV2Host.TAG_WIDGET
            )
            Page.BACKUP -> setOf(SettingsV2Host.TAG_DRIVE)
            Page.HELP -> setOf(SettingsV2Host.TAG_HELP, SettingsV2Host.TAG_EXTRAS)
        }

        managedSections(activity).forEach { section ->
            section.visibility = if (section.tag?.toString() in visibleTags) View.VISIBLE else View.GONE
        }
        AppearanceManager.apply(activity)
        ThemeFrameStyler.apply(panel)
    }

    private fun ensureMenu(activity: MainActivity, panel: LinearLayout) {
        if (panel.findViewWithTag<View>(SettingsV2Host.TAG_COMPACT_MENU) != null) return

        val menu = LinearLayout(activity).apply {
            tag = SettingsV2Host.TAG_COMPACT_MENU
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))

            addView(categoryButton(activity, "COMPTE & SÉCURITÉ") {
                showPage(activity, Page.ACCOUNT)
            })
            addView(categoryButton(activity, "POINTAGE & LIEUX") {
                showPage(activity, Page.POINTAGE)
            })
            addView(categoryButton(activity, "PERSONNALISATION") {
                showPage(activity, Page.PERSONALIZATION)
            })
            addView(categoryButton(activity, "SAUVEGARDE & DONNÉES") {
                showPage(activity, Page.BACKUP)
            })
            addView(categoryButton(activity, "AIDE") {
                showPage(activity, Page.HELP)
            })
            addView(TextView(activity).apply {
                text = "AGKGMG — version ${BuildConfig.VERSION_NAME}"
                textSize = 12f
                gravity = Gravity.CENTER
                alpha = 0.72f
                setPadding(0, dp(activity, 20), 0, dp(activity, 8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
        panel.addView(menu, 0)
    }

    private fun ensureBackControl(activity: MainActivity, panel: LinearLayout) {
        if (panel.findViewWithTag<View>(SettingsV2Host.TAG_COMPACT_BACK) != null) return
        val back = Button(activity).apply {
            tag = SettingsV2Host.TAG_COMPACT_BACK
            text = "RETOUR AUX PARAMÈTRES"
            contentDescription = "Retour au menu Paramètres"
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = 0
            minimumHeight = 0
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { showMenu(activity) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 48)
            ).apply {
                topMargin = dp(activity, 4)
                bottomMargin = dp(activity, 8)
            }
        }
        panel.addView(back, 1.coerceAtMost(panel.childCount))
    }

    private fun categoryButton(
        activity: MainActivity,
        label: String,
        onClick: () -> Unit
    ) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER
        minHeight = 0
        minimumHeight = 0
        setBackgroundResource(R.drawable.hp_panel)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 56)
        ).apply {
            topMargin = dp(activity, 5)
            bottomMargin = dp(activity, 5)
        }
    }

    private fun managedSections(activity: MainActivity): List<LinearLayout> =
        listOf(
            SettingsV2Host.TAG_UPDATES,
            SettingsV2Host.TAG_ACCOUNT_SECURITY,
            SettingsV2Host.TAG_POINTAGE,
            SettingsV2Host.TAG_CELESTIAL,
            SettingsV2Host.TAG_PERSONALIZATION,
            SettingsV2Host.TAG_WIDGET,
            SettingsV2Host.TAG_DRIVE,
            SettingsV2Host.TAG_HELP,
            SettingsV2Host.TAG_EXTRAS
        ).mapNotNull { SettingsV2Host.section(activity, it) }.distinct()

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
