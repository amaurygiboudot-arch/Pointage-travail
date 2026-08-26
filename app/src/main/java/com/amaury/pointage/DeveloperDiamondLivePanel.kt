package com.amaury.pointage

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/** Banc de réglage live, réservé au mode développeur caché. */
object DeveloperDiamondLivePanel {
    private data class Spec(
        val tab: String,
        val label: String,
        val key: String,
        val min: Float,
        val max: Float,
        val decimals: Int = 2,
        val value: (PrimaryDiamondLiveTuningConfig) -> Float
    )

    private enum class PreviewTarget(val label: String, val id: Int?) {
        ENTRY("ENTRÉE", R.id.entryButton),
        PAUSE("PAUSE", R.id.pauseButton),
        EXIT("SORTIE", R.id.exitButton),
        ALL("LES 3", null)
    }

    private val tabs = listOf("MATIÈRE", "FACETTES", "SOLEIL / LUNE", "EFFETS", "CADRE")

    fun show(activity: MainActivity) {
        if (!AdminDiagnosticsGate.isEnabled(activity)) return
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        var previewTarget = PreviewTarget.ALL
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(Color.argb(244, 8, 10, 14))
        }

        root.addView(TextView(activity).apply {
            text = "💎 RÉGLAGES LIVE — BOUTONS RÉELS"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        })

        val connected = primaryButtons(activity).size
        root.addView(TextView(activity).apply {
            text = "Connexion boutons : $connected/3"
            setTextColor(if (connected == 3) Color.rgb(88, 235, 125) else Color.rgb(255, 105, 105))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        })

        root.addView(TextView(activity).apply {
            text = "APERÇU CIBLÉ — choisir le bouton puis envoyer"
            setTextColor(Color.rgb(205, 216, 232))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(3))
        })

        val targetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val targetButtons = linkedMapOf<PreviewTarget, Button>()
        var sendPreview: Button? = null

        fun refreshTargets() {
            targetButtons.forEach { (target, button) ->
                val selected = target == previewTarget
                button.alpha = if (selected) 1f else .55f
                button.setTextColor(if (selected) Color.WHITE else Color.rgb(165, 175, 190))
            }
            sendPreview?.text = "➤ ENVOYER L’APERÇU SUR ${previewTarget.label}"
        }

        PreviewTarget.entries.forEach { target ->
            val button = Button(activity).apply {
                text = target.label
                isAllCaps = false
                textSize = 10f
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(4), 0, dp(4), 0)
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener {
                    previewTarget = target
                    refreshTargets()
                }
            }
            targetButtons[target] = button
            targetRow.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(2) })
        }
        root.addView(targetRow)

        val previewButton = Button(activity).apply {
            text = "➤ ENVOYER L’APERÇU SUR LES 3"
            isAllCaps = false
            textSize = 12f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener {
                val ok = applyPreview(activity, previewTarget)
                Toast.makeText(
                    activity,
                    if (ok) "Aperçu envoyé sur ${previewTarget.label}" else "Bouton ${previewTarget.label} introuvable",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        sendPreview = previewButton
        root.addView(previewButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        refreshTargets()

        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val stripScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        root.addView(stripScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        val host = FrameLayout(activity)
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val allSpecs = specs()
        val pages = linkedMapOf<String, ScrollView>()
        val tabButtons = linkedMapOf<String, Button>()

        fun showTab(name: String) {
            pages.forEach { (tab, page) -> page.visibility = if (tab == name) View.VISIBLE else View.GONE }
            tabButtons.forEach { (tab, button) ->
                val active = tab == name
                button.alpha = if (active) 1f else .62f
                button.setTextColor(if (active) Color.WHITE else Color.rgb(170, 180, 195))
            }
        }

        tabs.forEach { tabName ->
            val tabButton = Button(activity).apply {
                text = tabName
                isAllCaps = false
                textSize = 11f
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(12), 0, dp(12), 0)
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener { showTab(tabName) }
            }
            tabButtons[tabName] = tabButton
            strip.addView(tabButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(4) })

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            allSpecs.filter { it.tab == tabName }.forEach { addControl(activity, content, it) }
            val page = ScrollView(activity).apply {
                addView(content)
                visibility = View.GONE
            }
            pages[tabName] = page
            host.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        val reset = Button(activity).apply { text = "RÉINITIALISER"; isAllCaps = false }
        val close = Button(activity).apply { text = "FERMER"; isAllCaps = false }
        actions.addView(reset, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) })
        actions.addView(close, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })
        root.addView(actions)

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        reset.setOnClickListener {
            PrimaryDiamondLiveTuning.reset(activity)
            invalidateRealButtons(activity)
            dialog.dismiss()
            show(activity)
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setGravity(Gravity.TOP)
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * .42f).toInt()
                )
            }
            showTab(tabs.first())
        }
        dialog.show()
    }

    private fun addControl(activity: MainActivity, parent: LinearLayout, spec: Spec) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val label = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 12f }
        val bar = SeekBar(activity).apply { max = 1000 }

        fun formatted(v: Float): String = when (spec.decimals) {
            0 -> v.roundToInt().toString()
            3 -> String.format(Locale.FRANCE, "%.3f", v)
            4 -> String.format(Locale.FRANCE, "%.4f", v)
            else -> String.format(Locale.FRANCE, "%.2f", v)
        }
        fun setLabel(v: Float) { label.text = "${spec.label} : ${formatted(v)}" }
        fun progressOf(v: Float) = (((v - spec.min) / (spec.max - spec.min)) * 1000f).roundToInt().coerceIn(0, 1000)
        fun valueOf(progress: Int) = spec.min + (spec.max - spec.min) * (progress / 1000f)

        val initial = spec.value(PrimaryDiamondLiveTuning.current(activity))
        bar.progress = progressOf(initial)
        setLabel(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = valueOf(progress)
                PrimaryDiamondLiveTuning.set(activity, spec.key, value)
                setLabel(value)
                invalidateRealButtons(activity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        row.addView(label)
        row.addView(bar)
        parent.addView(row)
    }

    private fun primaryButtons(activity: MainActivity): List<RedDiamondFinalButton> {
        val result = ArrayList<RedDiamondFinalButton>(3)
        val ids = intArrayOf(R.id.entryButton, R.id.pauseButton, R.id.exitButton)
        for (id in ids) {
            val button = activity.findViewById<View>(id) as? RedDiamondFinalButton
            if (button != null) result.add(button)
        }
        return result
    }

    private fun applyPreview(activity: MainActivity, target: PreviewTarget): Boolean {
        val targets = if (target.id == null) {
            primaryButtons(activity)
        } else {
            val button = activity.findViewById<View>(target.id) as? RedDiamondFinalButton
            if (button == null) emptyList() else listOf(button)
        }
        targets.forEach { button ->
            button.applyLiveDeveloperTuning()
            button.requestLayout()
            button.postInvalidateOnAnimation()
        }
        activity.window.decorView.postInvalidateOnAnimation()
        return targets.isNotEmpty() && (target.id != null || targets.size == 3)
    }

    private fun invalidateRealButtons(activity: MainActivity) {
        primaryButtons(activity).forEach { it.applyLiveDeveloperTuning() }
        activity.window.decorView.invalidate()
    }

    private fun specs() = listOf(
        Spec("MATIÈRE", "Taille du diamant", "radiusScale", .35f, .50f, 3) { it.radiusScale },
        Spec("MATIÈRE", "Échelle à l'appui", "pressScale", .75f, 1f, 3) { it.pressScale },
        Spec("MATIÈRE", "Saturation couleur", "saturation", .4f, 1.6f) { it.saturation },
        Spec("MATIÈRE", "Luminosité Entrée", "entryGain", .3f, 1.7f) { it.entryGain },
        Spec("MATIÈRE", "Luminosité Pause", "pauseGain", .3f, 1.7f) { it.pauseGain },
        Spec("MATIÈRE", "Luminosité Sortie", "exitGain", .3f, 1.7f) { it.exitGain },
        Spec("MATIÈRE", "Translucidité", "translucencyScale", .35f, 1.55f) { it.translucencyScale },

        Spec("FACETTES", "Luminosité minimale", "baseLuminance", .05f, .60f) { it.baseLuminance },
        Spec("FACETTES", "Lumière directe", "directWeight", 0f, 1f) { it.directWeight },
        Spec("FACETTES", "Lumière interne", "internalWeight", 0f, 1f) { it.internalWeight },
        Spec("FACETTES", "Rétention interne", "internalRetention", 0f, 1f) { it.internalRetention },
        Spec("FACETTES", "Inertie / réponse", "responseTau", .01f, .25f, 3) { it.responseTau },
        Spec("FACETTES", "Surbrillance facette", "highlightMix", 0f, .30f, 3) { it.highlightMix },
        Spec("FACETTES", "Pente intérieure", "innerSlope", 0f, .45f, 3) { it.innerSlope },
        Spec("FACETTES", "Pente centrale", "middleSlope", 0f, .45f, 3) { it.middleSlope },
        Spec("FACETTES", "Pente extérieure", "outerSlope", 0f, .45f, 3) { it.outerSlope },

        Spec("SOLEIL / LUNE", "Force Soleil", "sunIntensityScale", 0f, 2f) { it.sunIntensityScale },
        Spec("SOLEIL / LUNE", "Force Lune", "moonIntensityScale", 0f, 2f) { it.moonIntensityScale },
        Spec("SOLEIL / LUNE", "Concentration reflet Soleil", "daySpecularPower", 8f, 220f, 0) { it.daySpecularPower },
        Spec("SOLEIL / LUNE", "Concentration reflet Lune", "nightSpecularPower", 8f, 220f, 0) { it.nightSpecularPower },
        Spec("SOLEIL / LUNE", "Intensité spéculaire", "specularAlpha", 0f, 255f, 0) { it.specularAlpha },
        Spec("SOLEIL / LUNE", "Rayon spéculaire", "specularRadius", .15f, 1.30f) { it.specularRadius },
        Spec("SOLEIL / LUNE", "Décalage spéculaire", "specularOffset", 0f, .60f) { it.specularOffset },

        Spec("EFFETS", "Halo Soleil", "sunHaloAlpha", 0f, 255f, 0) { it.sunHaloAlpha },
        Spec("EFFETS", "Halo Lune", "moonHaloAlpha", 0f, 255f, 0) { it.moonHaloAlpha },
        Spec("EFFETS", "Rayon halo", "haloRadius", .35f, 1.80f) { it.haloRadius },
        Spec("EFFETS", "Décalage halo", "haloOffset", 0f, .85f) { it.haloOffset },
        Spec("EFFETS", "Ombre Soleil", "sunShadowAlpha", 0f, 255f, 0) { it.sunShadowAlpha },
        Spec("EFFETS", "Ombre Lune", "moonShadowAlpha", 0f, 255f, 0) { it.moonShadowAlpha },
        Spec("EFFETS", "Rayon ombre", "shadowRadius", .30f, 1.50f) { it.shadowRadius },
        Spec("EFFETS", "Décalage ombre", "shadowOffset", 0f, 1f) { it.shadowOffset },
        Spec("EFFETS", "Arc Soleil", "sunArcAlpha", 0f, 255f, 0) { it.sunArcAlpha },
        Spec("EFFETS", "Arc Lune", "moonArcAlpha", 0f, 255f, 0) { it.moonArcAlpha },
        Spec("EFFETS", "Épaisseur arc", "arcWidth", .002f, .10f, 3) { it.arcWidth },
        Spec("EFFETS", "Ouverture arc (°)", "arcSpanDeg", 0f, 180f, 0) { it.arcSpanDeg },

        Spec("CADRE", "Épaisseur arêtes", "edgeWidth", .001f, .03f, 4) { it.edgeWidth },
        Spec("CADRE", "Alpha arêtes base", "edgeBaseAlpha", 0f, 100f, 0) { it.edgeBaseAlpha },
        Spec("CADRE", "Réaction lumière arêtes", "edgeLightAlpha", 0f, 120f, 0) { it.edgeLightAlpha },
        Spec("CADRE", "Épaisseur ceinture", "girdleWidth", .003f, .08f, 3) { it.girdleWidth },
        Spec("CADRE", "Alpha ceinture", "girdleAlpha", 0f, 255f, 0) { it.girdleAlpha },
        Spec("CADRE", "Épaisseur cercle interne", "girdleInnerWidth", .001f, .04f, 3) { it.girdleInnerWidth },
        Spec("CADRE", "Alpha cercle interne", "girdleInnerAlpha", 0f, 255f, 0) { it.girdleInnerAlpha },
        Spec("CADRE", "Rayon ceinture", "girdleRadius", .88f, 1.02f, 3) { it.girdleRadius },
        Spec("CADRE", "Rayon cercle interne", "girdleInnerRadius", .85f, 1.00f, 3) { it.girdleInnerRadius }
    )
}
