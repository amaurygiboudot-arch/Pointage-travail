package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class DiamondLabActivity : Activity() {
    private lateinit var canvas: DiamondDesignerCanvas
    private lateinit var selectionLabel: TextView
    private lateinit var presets: MutableList<DiamondDesignerLibrary.Preset>
    private lateinit var assistantInput: EditText
    private lateinit var assistantStatus: TextView
    private lateinit var assistantSend: Button

    private data class Control(val label: TextView, val bar: SeekBar)
    private lateinit var lens: Control; private lateinit var ring1: Control; private lateinit var ring2: Control; private lateinit var ring3: Control
    private lateinit var alpha: Control; private lateinit var rotation: Control; private lateinit var light: Control
    private lateinit var edgeWidth: Control; private lateinit var edgeAlpha: Control; private lateinit var edgeContrast: Control; private lateinit var edgeSoftness: Control
    private lateinit var radialEdges: Control; private lateinit var circularEdges: Control

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presets = DiamondDesignerLibrary.load(this)
        window.statusBarColor = Color.parseColor("#05070B")
        window.navigationBarColor = Color.parseColor("#05070B")
        setContentView(buildUi())
        bindCanvas()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#05070B")) }
        root.addView(TextView(this).apply { text = "DIAMOND DESIGNER"; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(dp(12),dp(12),dp(12),dp(8)) })

        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@DiamondLabActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(8))
                addView(toolButton("+ Entrée") { canvas.addEntryButton() }); addView(toolButton("+ Pause") { canvas.addPauseButton() }); addView(toolButton("+ Sortie") { canvas.addExitButton() })
                addView(toolButton("+ Cadre") { canvas.addFrame() }); addView(toolButton("+ Fond") { canvas.addBackground() }); addView(toolButton("Bibliothèque") { showLibrary() })
                addView(toolButton("Enregistrer") { saveSelectedPreset() }); addView(toolButton("Dupliquer") { canvas.duplicateSelected() }); addView(toolButton("Avant") { canvas.bringForward() })
                addView(toolButton("Arrière") { canvas.sendBackward() }); addView(toolButton("Verrou") { canvas.toggleLock() }); addView(toolButton("Supprimer") { canvas.deleteSelected() })
            })
        })

        val workspace = FrameLayout(this); canvas = DiamondDesignerCanvas(this); workspace.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        root.addView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B1118")); addView(buildControls()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(620)))
        return root
    }

    private fun buildControls() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(8),dp(14),dp(14))
        selectionLabel = TextView(this@DiamondLabActivity).apply { setTextColor(Color.WHITE); textSize = 15f; text = "Aucun élément sélectionné" }; addView(selectionLabel)
        lens = addControl(this,"Bombé global",0,1000); ring1 = addControl(this,"Anneau 16 facettes",200,1800); ring2 = addControl(this,"Anneau 32 intérieur",200,1800); ring3 = addControl(this,"Anneau 32 extérieur",200,1800)
        this@DiamondLabActivity.alpha = addControl(this,"Opacité",50,1000); this@DiamondLabActivity.rotation = addControl(this,"Rotation",0,3600); light = addControl(this,"Lumière",0,3600)
        addView(TextView(this@DiamondLabActivity).apply { text="ARÊTES DES FACETTES"; setTextColor(Color.WHITE); textSize=15f; setPadding(0,dp(10),0,dp(4)) })
        edgeWidth = addControl(this,"Épaisseur arêtes",1,1200); edgeAlpha = addControl(this,"Opacité arêtes",0,1000); edgeContrast = addControl(this,"Contraste arêtes",0,1000); edgeSoftness = addControl(this,"Douceur arêtes",0,1000)
        radialEdges = addControl(this,"Arêtes radiales",0,2000); circularEdges = addControl(this,"Arêtes circulaires",0,2000)

        addView(TextView(this@DiamondLabActivity).apply {
            text = "ASSISTANT"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(14), 0, dp(4))
        })
        assistantStatus = TextView(this@DiamondLabActivity).apply {
            setTextColor(Color.parseColor("#B8CADB"))
            textSize = 13f
            text = if (BuildConfig.DESIGNER_AI_ENDPOINT.isBlank()) "Assistant prêt côté appli • serveur à configurer" else "Assistant prêt"
        }
        addView(assistantStatus)
        assistantInput = EditText(this@DiamondLabActivity).apply {
            hint = "Ex. Épaissis les arêtes extérieures et bombe un peu plus l’anneau 2"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#73879A"))
            minLines = 2
            maxLines = 4
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        addView(assistantInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        assistantSend = Button(this@DiamondLabActivity).apply {
            text = "ENVOYER À L’ASSISTANT"
            isAllCaps = false
            setOnClickListener { sendToAssistant() }
        }
        addView(assistantSend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        addView(Button(this@DiamondLabActivity).apply { text="COPIER LE RAPPORT"; isAllCaps=false; setOnClickListener { val text=canvas.report(); (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Diamond Designer",text)); Toast.makeText(this@DiamondLabActivity,"Rapport copié",Toast.LENGTH_SHORT).show() } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)).apply { topMargin=dp(8) })
    }

    private fun addControl(parent: LinearLayout, title: String, min: Int, max: Int): Control {
        val l=valueLabel().apply { text=title }; val b=SeekBar(this).apply { this.min=min; this.max=max }; parent.addView(l); parent.addView(b); return Control(l,b)
    }

    private fun bindCanvas() {
        canvas.onSelectionChanged={ updateControls(it) }; canvas.onDesignChanged={ canvas.selectedElement()?.let(::updateLabels) }
        lens.bar.listen { canvas.setSelectedLens(it/1000f) }; ring1.bar.listen { canvas.setSelectedRingGain(1,it/1000f) }; ring2.bar.listen { canvas.setSelectedRingGain(2,it/1000f) }; ring3.bar.listen { canvas.setSelectedRingGain(3,it/1000f) }
        alpha.bar.listen { canvas.setSelectedAlpha(it/1000f) }; rotation.bar.listen { canvas.setSelectedRotation(it/10f) }; light.bar.listen { canvas.setSelectedLightAngle(it/10f) }
        edgeWidth.bar.listen { canvas.setSelectedEdgeWidth(it/100f) }; edgeAlpha.bar.listen { canvas.setSelectedEdgeAlpha(it/1000f) }; edgeContrast.bar.listen { canvas.setSelectedEdgeContrast(it/1000f) }; edgeSoftness.bar.listen { canvas.setSelectedEdgeSoftness(it/1000f) }
        radialEdges.bar.listen { canvas.setSelectedRadialEdgeGain(it/1000f) }; circularEdges.bar.listen { canvas.setSelectedCircularEdgeGain(it/1000f) }
    }

    private fun sendToAssistant() {
        val message = assistantInput.text?.toString()?.trim().orEmpty()
        if (message.isBlank()) { Toast.makeText(this,"Écris ce que tu veux modifier",Toast.LENGTH_SHORT).show(); return }
        assistantSend.isEnabled = false
        assistantStatus.text = "Assistant en cours…"
        val state = canvas.report()
        Thread {
            runCatching { DiamondDesignerAssistant.ask(message, state) }
                .onSuccess { result ->
                    runOnUiThread {
                        val count = DiamondDesignerAssistant.apply(canvas, result.actions)
                        canvas.selectedElement()?.let(::updateControls)
                        assistantStatus.text = "${result.reply} • $count action${if(count>1)"s" else ""} appliquée${if(count>1)"s" else ""}"
                        assistantInput.text?.clear()
                        assistantSend.isEnabled = true
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        assistantStatus.text = error.message ?: "Erreur assistant"
                        assistantSend.isEnabled = true
                    }
                }
        }.start()
    }

    private fun updateControls(e: DiamondDesignerCanvas.DesignElement?) {
        if (e==null) { selectionLabel.text="Aucun élément sélectionné"; return }
        selectionLabel.text="${e.name} • ${e.type}${if(e.locked)" • verrouillé" else ""}"
        lens.bar.progress=(e.lensStrength*1000).toInt(); ring1.bar.progress=(e.ring1Gain*1000).toInt(); ring2.bar.progress=(e.ring2Gain*1000).toInt(); ring3.bar.progress=(e.ring3Gain*1000).toInt()
        alpha.bar.progress=(e.alpha*1000).toInt(); rotation.bar.progress=((((e.rotation%360)+360)%360)*10).toInt(); light.bar.progress=(e.lightAngle*10).toInt()
        edgeWidth.bar.progress=(e.edgeWidth*100).toInt(); edgeAlpha.bar.progress=(e.edgeAlpha*1000).toInt(); edgeContrast.bar.progress=(e.edgeContrast*1000).toInt(); edgeSoftness.bar.progress=(e.edgeSoftness*1000).toInt(); radialEdges.bar.progress=(e.radialEdgeGain*1000).toInt(); circularEdges.bar.progress=(e.circularEdgeGain*1000).toInt()
        val d=e.type==DiamondDesignerCanvas.ElementType.ENTRY_BUTTON||e.type==DiamondDesignerCanvas.ElementType.PAUSE_BUTTON||e.type==DiamondDesignerCanvas.ElementType.EXIT_BUTTON
        listOf(lens,ring1,ring2,ring3,light,edgeWidth,edgeAlpha,edgeContrast,edgeSoftness,radialEdges,circularEdges).forEach { it.bar.isEnabled=d }
        updateLabels(e)
    }

    private fun updateLabels(e: DiamondDesignerCanvas.DesignElement) {
        lens.label.text="Bombé global : ${(e.lensStrength*100).toInt()} %"; ring1.label.text="Anneau 16 facettes : ${(e.ring1Gain*100).toInt()} %"; ring2.label.text="Anneau 32 intérieur : ${(e.ring2Gain*100).toInt()} %"; ring3.label.text="Anneau 32 extérieur : ${(e.ring3Gain*100).toInt()} %"
        alpha.label.text="Opacité : ${(e.alpha*100).toInt()} %"; rotation.label.text="Rotation : ${e.rotation.toInt()}°"; light.label.text="Lumière : ${e.lightAngle.toInt()}°"
        edgeWidth.label.text="Épaisseur arêtes : ${"%.1f".format(e.edgeWidth)} px"; edgeAlpha.label.text="Opacité arêtes : ${(e.edgeAlpha*100).toInt()} %"; edgeContrast.label.text="Contraste arêtes : ${(e.edgeContrast*100).toInt()} %"; edgeSoftness.label.text="Douceur arêtes : ${(e.edgeSoftness*100).toInt()} %"
        radialEdges.label.text="Arêtes radiales : ${(e.radialEdgeGain*100).toInt()} %"; circularEdges.label.text="Arêtes circulaires : ${(e.circularEdgeGain*100).toInt()} %"
    }

    private fun showLibrary() { AlertDialog.Builder(this).setTitle("Bibliothèque").setItems(presets.map{it.name}.toTypedArray()) { _,i -> presets.getOrNull(i)?.let(canvas::addPreset) }.setNegativeButton("Fermer",null).show() }
    private fun saveSelectedPreset() { val e=canvas.selectedElement()?:run{Toast.makeText(this,"Sélectionne d'abord un élément",Toast.LENGTH_SHORT).show();return}; val input=EditText(this).apply{setText(e.name);selectAll()}; AlertDialog.Builder(this).setTitle("Enregistrer dans la bibliothèque").setView(input).setPositiveButton("Enregistrer") { _,_-> val name=input.text?.toString()?.trim().orEmpty().ifBlank{e.name}; presets.add(DiamondDesignerLibrary.presetFromElement(name,e)); DiamondDesignerLibrary.save(this,presets); Toast.makeText(this,"$name enregistré",Toast.LENGTH_SHORT).show() }.setNegativeButton("Annuler",null).show() }

    private fun SeekBar.listen(action:(Int)->Unit) { setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{ override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){if(f)action(p)}; override fun onStartTrackingTouch(s:SeekBar?){}; override fun onStopTrackingTouch(s:SeekBar?){} }) }
    private fun toolButton(label:String, action:()->Unit)=Button(this).apply{text=label;isAllCaps=false;textSize=12f;minWidth=0;minimumWidth=0;setPadding(dp(10),0,dp(10),0);setOnClickListener{action()}}.also{it.layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(42)).apply{marginEnd=dp(6)}}
    private fun valueLabel()=TextView(this).apply{setTextColor(Color.parseColor("#D7E5F2"));textSize=13f}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
