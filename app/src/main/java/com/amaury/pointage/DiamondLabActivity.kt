package com.amaury.pointage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class DiamondLabActivity : Activity() {

    companion object {
        private const val PREFS = "diamond_convex_lab"
        private const val PREF_LENS = "lens_strength"
        private const val DEFAULT_LENS = 0.50f
    }

    private lateinit var previewButton: GreenDiamondFinalButton
    private lateinit var valueLabel: TextView
    private var lensStrength = DEFAULT_LENS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lensStrength = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(PREF_LENS, DEFAULT_LENS)
            .coerceIn(0f, 1f)

        window.statusBarColor = Color.parseColor("#030810")
        window.navigationBarColor = Color.parseColor("#030810")
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#030810"))
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(24), dp(18), dp(30))
        }
        root.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(TextView(this).apply {
            text = "RÉGLAGE DU BOMBÉ"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, lp(bottom = 8))

        content.addView(TextView(this).apply {
            text = "Ce bouton de simulation est une vraie instance du bouton ENTRÉE. Le curseur ci-dessous ne modifie que son bombé radial."
            textSize = 14f
            setTextColor(Color.parseColor("#B8CBD9"))
            gravity = Gravity.CENTER
        }, lp(bottom = 22))

        previewButton = GreenDiamondFinalButton(this).apply {
            contentDescription = "Simulation identique au bouton Entrée"
            isClickable = false
            isFocusable = false
            setLensStrength(lensStrength)
        }
        content.addView(
            previewButton,
            LinearLayout.LayoutParams(dp(108), dp(108)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(22)
            }
        )

        content.addView(TextView(this).apply {
            text = "ENTRÉE — SIMULATION"
            textSize = 14f
            setTextColor(Color.parseColor("#D8E5EC"))
            gravity = Gravity.CENTER
        }, lp(bottom = 18))

        valueLabel = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        content.addView(valueLabel, lp(bottom = 6))

        val slider = SeekBar(this).apply {
            max = 1000
            progress = (lensStrength * max).toInt()
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                lensStrength = (progress / 1000f).coerceIn(0f, 1f)
                previewButton.setLensStrength(lensStrength)
                saveLabValue()
                updateValueLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        content.addView(slider, lp(bottom = 18))

        val copyButton = Button(this).apply {
            text = "COPIER LE RAPPORT"
            isAllCaps = false
            setOnClickListener {
                val report = buildReport()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Réglage bombé HP Travail", report))
                Toast.makeText(this@DiamondLabActivity, "Rapport copié", Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(copyButton, lp(height = 52, bottom = 10))

        val resetButton = Button(this).apply {
            text = "REMETTRE À 50 %"
            isAllCaps = false
            setOnClickListener {
                lensStrength = DEFAULT_LENS
                slider.progress = (DEFAULT_LENS * slider.max).toInt()
                previewButton.setLensStrength(lensStrength)
                saveLabValue()
                updateValueLabel()
            }
        }
        content.addView(resetButton, lp(height = 52, bottom = 10))

        val closeButton = Button(this).apply {
            text = "TERMINÉ"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        content.addView(closeButton, lp(height = 52))

        updateValueLabel()
        return root
    }

    private fun updateValueLabel() {
        valueLabel.text = "Bombé : %.3f  —  %.1f %%".format(lensStrength, lensStrength * 100f)
    }

    private fun buildReport(): String = buildString {
        appendLine("RAPPORT BOMBÉ DIAMANT HP TRAVAIL")
        appendLine("Simulation : bouton ENTRÉE vert")
        appendLine("Moteur : GreenDiamondFinalButton / 80 facettes")
        appendLine("Bombé lensStrength : %.3f".format(lensStrength))
        append("Bombé pourcentage : %.1f %%".format(lensStrength * 100f))
    }

    private fun saveLabValue() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PREF_LENS, lensStrength)
            .apply()
    }

    private fun lp(height: Int? = null, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height?.let { dp(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
