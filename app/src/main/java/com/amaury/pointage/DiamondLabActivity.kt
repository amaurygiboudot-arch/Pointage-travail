package com.amaury.pointage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class DiamondLabActivity : Activity() {
    private lateinit var canvas: DiamondDesignerCanvas
    private lateinit var selectionLabel: TextView
    private lateinit var lensLabel: TextView
    private lateinit var alphaLabel: TextView
    private lateinit var rotationLabel: TextView
    private lateinit var lightLabel: TextView
    private lateinit var lensBar: SeekBar
    private lateinit var alphaBar: SeekBar
    private lateinit var rotationBar: SeekBar
    private lateinit var lightBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#05070B")
        window.navigationBarColor = Color.parseColor("#05070B")
        setContentView(buildUi())
        bindCanvas()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05070B"))
        }

        val title = TextView(this).apply {
            text = "DIAMOND DESIGNER"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val library = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@DiamondLabActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
                addView(toolButton("+ Entrée") { canvas.addEntryButton() })
                addView(toolButton("+ Cadre") { canvas.addFrame() })
                addView(toolButton("+ Fond") { canvas.addBackground() })
                addView(toolButton("Dupliquer") { canvas.duplicateSelected() })
                addView(toolButton("Avant") { canvas.bringForward() })
                addView(toolButton("Arrière") { canvas.sendBackward() })
                addView(toolButton("Verrou") { canvas.toggleLock() })
                addView(toolButton("Supprimer") { canvas.deleteSelected() })
            })
        }
        root.addView(library, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val workspace = FrameLayout(this)
        canvas = DiamondDesignerCanvas(this)
        workspace.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val controls = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1118"))
            addView(buildControls())
        }
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(285)))
        return root
    }

    private fun buildControls(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))

            selectionLabel = TextView(this@DiamondLabActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                text = "Aucun élément sélectionné"
            }
            addView(selectionLabel)

            lensLabel = valueLabel()
            lensBar = slider(0, 1000)
            addView(lensLabel); addView(lensBar)

            alphaLabel = valueLabel()
            alphaBar = slider(50, 1000)
            addView(alphaLabel); addView(alphaBar)

            rotationLabel = valueLabel()
            rotationBar = slider(0, 3600)
            addView(rotationLabel); addView(rotationBar)

            lightLabel = valueLabel()
            lightBar = slider(0, 3600)
            addView(lightLabel); addView(lightBar)

            val report = Button(this@DiamondLabActivity).apply {
                text = "COPIER LE RAPPORT"
                isAllCaps = false
                setOnClickListener {
                    val text = canvas.report()
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Diamond Designer", text))
                    Toast.makeText(this@DiamondLabActivity, "Rapport copié", Toast.LENGTH_SHORT).show()
                }
            }
            addView(report, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        }
    }

    private fun bindCanvas() {
        canvas.onSelectionChanged = { updateControls(it) }
        canvas.onDesignChanged = { canvas.selectedElement()?.let { updateLabels(it) } }

        lensBar.setOnSeekBarChangeListener(listener { p -> canvas.setSelectedLens(p / 1000f) })
        alphaBar.setOnSeekBarChangeListener(listener { p -> canvas.setSelectedAlpha(p / 1000f) })
        rotationBar.setOnSeekBarChangeListener(listener { p -> canvas.setSelectedRotation(p / 10f) })
        lightBar.setOnSeekBarChangeListener(listener { p -> canvas.setSelectedLightAngle(p / 10f) })
    }

    private fun updateControls(e: DiamondDesignerCanvas.DesignElement?) {
        if (e == null) {
            selectionLabel.text = "Aucun élément sélectionné"
            return
        }
        selectionLabel.text = "${e.name} • ${e.type}${if (e.locked) " • verrouillé" else ""}"
        lensBar.progress = (e.lensStrength * 1000f).toInt()
        alphaBar.progress = (e.alpha * 1000f).toInt()
        rotationBar.progress = (((e.rotation % 360f) + 360f) % 360f * 10f).toInt()
        lightBar.progress = (e.lightAngle * 10f).toInt()
        lensBar.isEnabled = e.type == DiamondDesignerCanvas.ElementType.ENTRY_BUTTON
        lightBar.isEnabled = e.type == DiamondDesignerCanvas.ElementType.ENTRY_BUTTON
        updateLabels(e)
    }

    private fun updateLabels(e: DiamondDesignerCanvas.DesignElement) {
        lensLabel.text = "Bombé : ${(e.lensStrength * 100f).toInt()} %"
        alphaLabel.text = "Transparence : ${(e.alpha * 100f).toInt()} %"
        rotationLabel.text = "Rotation : ${e.rotation.toInt()}°"
        lightLabel.text = "Lumière : ${e.lightAngle.toInt()}°"
    }

    private fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun toolButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(6) } }

    private fun valueLabel() = TextView(this).apply { setTextColor(Color.parseColor("#D7E5F2")); textSize = 13f }
    private fun slider(minValue: Int, maxValue: Int) = SeekBar(this).apply { min = minValue; max = maxValue }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
