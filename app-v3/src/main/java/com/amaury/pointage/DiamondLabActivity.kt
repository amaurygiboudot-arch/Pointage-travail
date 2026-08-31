package com.amaury.pointage

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class DiamondLabActivity : Activity() {
    private lateinit var previewSurface: True3DButtonTextureView
    private lateinit var previewLabel: Button
    private var tuning = DiamondTuning()
    private var lightAngle = 305f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tuning = DiamondTuningStore.load(this)
        window.statusBarColor = Color.parseColor("#030810")
        window.navigationBarColor = Color.parseColor("#030810")
        setContentView(buildUi())
        refreshPreview()
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#030810")) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(TextView(this).apply {
            text = "LABORATOIRE DIAMANT"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, lp(top = 0, bottom = 8))

        content.addView(TextView(this).apply {
            text = "Ajuste le cristal en direct. Cet aperçu utilise exactement le nouveau moteur 3D du thème Diamant."
            textSize = 14f
            setTextColor(Color.parseColor("#B8CBD9"))
            gravity = Gravity.CENTER
        }, lp(bottom = 18))

        // L'ancien DiamondDrawable 2D n'est plus utilisé ici : l'aperçu est
        // rendu par le même moteur OpenGL à facettes que les boutons du thème.
        val previewFrame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        previewSurface = True3DButtonTextureView(this).apply {
            setLightAngle(lightAngle)
            setCrystalTuning(tuning)
        }
        previewLabel = Button(this).apply {
            text = "APERÇU DIAMANT 3D"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            background = ColorDrawable(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        previewFrame.addView(previewSurface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewFrame.addView(previewLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(previewFrame, lp(height = 96, bottom = 20))

        addSlider(content, "Transparence", tuning.transparency) { tuning = tuning.copy(transparency = it) }
        addSlider(content, "Profondeur des facettes", tuning.facetDepth) { tuning = tuning.copy(facetDepth = it) }
        addSlider(content, "Réfraction", tuning.refraction) { tuning = tuning.copy(refraction = it) }
        addSlider(content, "Éclats lumineux", tuning.sparkle) { tuning = tuning.copy(sparkle = it) }
        addSlider(content, "Bleu glace", tuning.iceBlue) { tuning = tuning.copy(iceBlue = it) }
        addSlider(content, "Épaisseur du biseau", tuning.bevel) { tuning = tuning.copy(bevel = it) }
        addSlider(content, "Direction de la lumière", lightAngle / 360f, valueText = { "${(it * 360f).toInt()}°" }) {
            lightAngle = it * 360f
            refreshPreview(save = false)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val reset = Button(this).apply {
            text = "Réinitialiser"
            isAllCaps = false
            setOnClickListener {
                DiamondTuningStore.reset(this@DiamondLabActivity)
                tuning = DiamondTuningStore.defaults
                recreate()
            }
        }
        val done = Button(this).apply {
            text = "Garder ces réglages"
            isAllCaps = false
            setOnClickListener {
                DiamondTuningStore.save(this@DiamondLabActivity, tuning)
                finish()
            }
        }
        row.addView(reset, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        row.addView(done, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        content.addView(row, lp(top = 18))
        return root
    }

    private fun addSlider(
        parent: LinearLayout,
        title: String,
        initial: Float,
        valueText: (Float) -> String = { "${(it * 100).toInt()} %" },
        onChange: (Float) -> Unit
    ) {
        val label = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        parent.addView(label, lp(top = 8))
        val bar = SeekBar(this).apply {
            max = 1000
            progress = (initial.coerceIn(0f, 1f) * max).toInt()
        }
        fun update(value: Float) {
            label.text = "$title : ${valueText(value)}"
            onChange(value)
            if (title != "Direction de la lumière") refreshPreview()
        }
        update(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) update(progress / 1000f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        parent.addView(bar, lp(bottom = 2))
    }

    private fun refreshPreview(save: Boolean = true) {
        if (!::previewSurface.isInitialized) return
        if (save) DiamondTuningStore.save(this, tuning)
        previewSurface.setCrystalTuning(tuning)
        previewSurface.setLightAngle(lightAngle)
        previewSurface.invalidate()
    }

    private fun lp(height: Int? = null, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height?.let { dp(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
