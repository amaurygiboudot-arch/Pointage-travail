package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class DiamondLabActivity : Activity() {
    private lateinit var canvas: DiamondDesignerCanvas
    private lateinit var selectionLabel: TextView
    private lateinit var presets: MutableList<DiamondDesignerLibrary.Preset>
    private lateinit var assistantInput: EditText
    private lateinit var assistantStatus: TextView
    private lateinit var assistantSend: Button
    private lateinit var panelHost: FrameLayout

    private data class Control(val label: TextView, val bar: SeekBar)
    private lateinit var lens: Control; private lateinit var ring1: Control; private lateinit var ring2: Control; private lateinit var ring3: Control
    private lateinit var transparency: Control; private lateinit var translucency: Control; private lateinit var rotationControl: Control; private lateinit var light: Control
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

        val workspace = FrameLayout(this)
        canvas = DiamondDesignerCanvas(this)
        workspace.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        root.addView(buildControlArea(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        return root
    }

    private fun buildControlArea(): LinearLayout {
        val area = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1118"))
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }
        selectionLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; text = "Aucun élément sélectionné"; setPadding(dp(4),0,dp(4),dp(4)) }
        area.addView(selectionLabel)

        val formPanel = makePanel { p ->
            lens = addControl(p,"Bombé global",0,1000)
            ring1 = addControl(p,"Anneau 16 facettes",200,1800)
            ring2 = addControl(p,"Anneau 32 intérieur",200,1800)
            ring3 = addControl(p,"Anneau 32 extérieur",200,1800)
            rotationControl = addControl(p,"Rotation",0,3600)
        }
        val materialPanel = makePanel { p ->
            transparency = addControl(p,"Transparence",0,950)
            translucency = addControl(p,"Translucidité",0,1000)
            p.addView(helpText("Transparence = tout le bouton s'efface. Translucidité = la matière laisse davantage voir le fond tout en gardant les facettes et les arêtes."))
        }
        val edgePanel = makePanel { p ->
            edgeWidth = addControl(p,"Épaisseur arêtes",1,1200)
            edgeAlpha = addControl(p,"Visibilité arêtes",0,1000)
            edgeContrast = addControl(p,"Contraste arêtes",0,1000)
            edgeSoftness = addControl(p,"Douceur arêtes",0,1000)
            radialEdges = addControl(p,"Arêtes radiales",0,2000)
            circularEdges = addControl(p,"Arêtes circulaires",0,2000)
        }
        val lightPanel = makePanel { p ->
            light = addControl(p,"Direction lumière",0,3600)
            p.addView(helpText("La lumière modifie les reflets du diamant sans changer sa couleur de base."))
        }
        val assistantPanel = makePanel { p ->
            assistantStatus = TextView(this).apply {
                setTextColor(Color.parseColor("#B8CADB")); textSize = 13f
                text = if (BuildConfig.DESIGNER_AI_ENDPOINT.isBlank()) "Assistant prêt côté appli • serveur à configurer" else "Assistant prêt"
            }
            p.addView(assistantStatus)
            assistantInput = EditText(this).apply {
                hint = "Ex. Épaissis les arêtes et bombe davantage l’anneau 2"
                setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#73879A")); minLines = 2; maxLines = 4
            }
            p.addView(assistantInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            assistantSend = Button(this).apply { text = "ENVOYER À L’ASSISTANT"; isAllCaps = false; setOnClickListener { sendToAssistant() } }
            p.addView(assistantSend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        }

        val tabs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@DiamondLabActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tabButton("Forme") { showPanel(formPanel) })
                addView(tabButton("Matière") { showPanel(materialPanel) })
                addView(tabButton("Arêtes") { showPanel(edgePanel) })
                addView(tabButton("Lumière") { showPanel(lightPanel) })
                addView(tabButton("Assistant") { showPanel(assistantPanel) })
            })
        }
        area.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        panelHost = FrameLayout(this)
        listOf(formPanel, materialPanel, edgePanel, lightPanel, assistantPanel).forEach { panelHost.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        area.addView(panelHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))

        area.addView(Button(this).apply {
            text="COPIER LE RAPPORT"; isAllCaps=false
            setOnClickListener {
                val text=canvas.report()
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Diamond Designer",text))
                Toast.makeText(this@DiamondLabActivity,"Rapport copié",Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(44)))

        showPanel(formPanel)
        return area
    }

    private fun makePanel(builder: (LinearLayout) -> Unit): ScrollView {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(8)) }
        builder(body)
        return ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B1118")); addView(body) }
    }

    private fun showPanel(target: View) {
        if (!::panelHost.isInitialized) return
        for (i in 0 until panelHost.childCount) panelHost.getChildAt(i).visibility = if (panelHost.getChildAt(i) === target) View.VISIBLE else View.GONE
    }

    private fun addControl(parent: LinearLayout, title: String, min: Int, max: Int): Control {
        val l=valueLabel().apply { text=title }
        val b=SeekBar(this).apply { this.min=min; this.max=max }
        parent.addView(l); parent.addView(b)
        return Control(l,b)
    }

    private fun bindCanvas() {
        canvas.onSelectionChanged={ updateControls(it) }
        canvas.onDesignChanged={ canvas.selectedElement()?.let(::updateLabels) }
        lens.bar.listen { canvas.setSelectedLens(it/1000f) }
        ring1.bar.listen { canvas.setSelectedRingGain(1,it/1000f) }
        ring2.bar.listen { canvas.setSelectedRingGain(2,it/1000f) }
        ring3.bar.listen { canvas.setSelectedRingGain(3,it/1000f) }
        transparency.bar.listen { canvas.setSelectedTransparency(it/1000f) }
        translucency.bar.listen { canvas.setSelectedTranslucency(it/1000f) }
        rotationControl.bar.listen { canvas.setSelectedRotation(it/10f) }
        light.bar.listen { canvas.setSelectedLightAngle(it/10f) }
        edgeWidth.bar.listen { canvas.setSelectedEdgeWidth(it/100f) }
        edgeAlpha.bar.listen { canvas.setSelectedEdgeAlpha(it/1000f) }
        edgeContrast.bar.listen { canvas.setSelectedEdgeContrast(it/1000f) }
        edgeSoftness.bar.listen { canvas.setSelectedEdgeSoftness(it/1000f) }
        radialEdges.bar.listen { canvas.setSelectedRadialEdgeGain(it/1000f) }
        circularEdges.bar.listen { canvas.setSelectedCircularEdgeGain(it/1000f) }
    }

    private fun sendToAssistant() {
        val message = assistantInput.text?.toString()?.trim().orEmpty()
        if (message.isBlank()) { Toast.makeText(this,"Écris ce que tu veux modifier",Toast.LENGTH_SHORT).show(); return }
        assistantSend.isEnabled = false
        assistantStatus.text = "Assistant en cours…"
        val state = canvas.report()
        Thread {
            runCatching { DiamondDesignerAssistant.ask(message, state) }
                .onSuccess { result -> runOnUiThread {
                    val count = DiamondDesignerAssistant.apply(canvas, result.actions)
                    canvas.selectedElement()?.let(::updateControls)
                    assistantStatus.text = "${result.reply} • $count action${if(count>1)"s" else ""} appliquée${if(count>1)"s" else ""}"
                    assistantInput.text?.clear(); assistantSend.isEnabled = true
                }}
                .onFailure { error -> runOnUiThread { assistantStatus.text = error.message ?: "Erreur assistant"; assistantSend.isEnabled = true } }
        }.start()
    }

    private fun updateControls(e: DiamondDesignerCanvas.DesignElement?) {
        if (e==null) { selectionLabel.text="Aucun élément sélectionné"; return }
        selectionLabel.text="${e.name} • ${e.type}${if(e.locked)" • verrouillé" else ""}"
        lens.bar.progress=(e.lensStrength*1000).toInt()
        ring1.bar.progress=(e.ring1Gain*1000).toInt(); ring2.bar.progress=(e.ring2Gain*1000).toInt(); ring3.bar.progress=(e.ring3Gain*1000).toInt()
        transparency.bar.progress=(e.transparency*1000).toInt(); translucency.bar.progress=(e.translucency*1000).toInt()
        rotationControl.bar.progress=((((e.rotation%360)+360)%360)*10).toInt(); light.bar.progress=(e.lightAngle*10).toInt()
        edgeWidth.bar.progress=(e.edgeWidth*100).toInt(); edgeAlpha.bar.progress=(e.edgeAlpha*1000).toInt(); edgeContrast.bar.progress=(e.edgeContrast*1000).toInt(); edgeSoftness.bar.progress=(e.edgeSoftness*1000).toInt(); radialEdges.bar.progress=(e.radialEdgeGain*1000).toInt(); circularEdges.bar.progress=(e.circularEdgeGain*1000).toInt()
        val d=e.type==DiamondDesignerCanvas.ElementType.ENTRY_BUTTON||e.type==DiamondDesignerCanvas.ElementType.PAUSE_BUTTON||e.type==DiamondDesignerCanvas.ElementType.EXIT_BUTTON
        listOf(lens,ring1,ring2,ring3,translucency,light,edgeWidth,edgeAlpha,edgeContrast,edgeSoftness,radialEdges,circularEdges).forEach { it.bar.isEnabled=d }
        transparency.bar.isEnabled = true; rotationControl.bar.isEnabled = true
        updateLabels(e)
    }

    private fun updateLabels(e: DiamondDesignerCanvas.DesignElement) {
        lens.label.text="Bombé global : ${(e.lensStrength*100).toInt()} %"
        ring1.label.text="Anneau 16 facettes : ${(e.ring1Gain*100).toInt()} %"
        ring2.label.text="Anneau 32 intérieur : ${(e.ring2Gain*100).toInt()} %"
        ring3.label.text="Anneau 32 extérieur : ${(e.ring3Gain*100).toInt()} %"
        transparency.label.text="Transparence : ${(e.transparency*100).toInt()} %"
        translucency.label.text="Translucidité : ${(e.translucency*100).toInt()} %"
        rotationControl.label.text="Rotation : ${e.rotation.toInt()}°"
        light.label.text="Direction lumière : ${e.lightAngle.toInt()}°"
        edgeWidth.label.text="Épaisseur arêtes : ${"%.1f".format(e.edgeWidth)} px"
        edgeAlpha.label.text="Visibilité arêtes : ${(e.edgeAlpha*100).toInt()} %"
        edgeContrast.label.text="Contraste arêtes : ${(e.edgeContrast*100).toInt()} %"
        edgeSoftness.label.text="Douceur arêtes : ${(e.edgeSoftness*100).toInt()} %"
        radialEdges.label.text="Arêtes radiales : ${(e.radialEdgeGain*100).toInt()} %"
        circularEdges.label.text="Arêtes circulaires : ${(e.circularEdgeGain*100).toInt()} %"
    }

    private fun showLibrary() { AlertDialog.Builder(this).setTitle("Bibliothèque").setItems(presets.map{it.name}.toTypedArray()) { _,i -> presets.getOrNull(i)?.let(canvas::addPreset) }.setNegativeButton("Fermer",null).show() }
    private fun saveSelectedPreset() {
        val e=canvas.selectedElement()?:run{Toast.makeText(this,"Sélectionne d'abord un élément",Toast.LENGTH_SHORT).show();return}
        val input=EditText(this).apply{setText(e.name);selectAll()}
        AlertDialog.Builder(this).setTitle("Enregistrer dans la bibliothèque").setView(input).setPositiveButton("Enregistrer") { _,_->
            val name=input.text?.toString()?.trim().orEmpty().ifBlank{e.name}
            presets.add(DiamondDesignerLibrary.presetFromElement(name,e)); DiamondDesignerLibrary.save(this,presets)
            Toast.makeText(this,"$name enregistré",Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Annuler",null).show()
    }

    private fun SeekBar.listen(action:(Int)->Unit) { setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{ override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){if(f)action(p)}; override fun onStartTrackingTouch(s:SeekBar?){}; override fun onStopTrackingTouch(s:SeekBar?){} }) }
    private fun toolButton(label:String, action:()->Unit)=Button(this).apply{text=label;isAllCaps=false;textSize=12f;minWidth=0;minimumWidth=0;setPadding(dp(10),0,dp(10),0);setOnClickListener{action()}}.also{it.layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(42)).apply{marginEnd=dp(6)}}
    private fun tabButton(label:String, action:()->Unit)=Button(this).apply{text=label;isAllCaps=false;textSize=12f;minWidth=0;minimumWidth=0;setPadding(dp(12),0,dp(12),0);setOnClickListener{action()}}.also{it.layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(40)).apply{marginEnd=dp(4)}}
    private fun valueLabel()=TextView(this).apply{setTextColor(Color.parseColor("#D7E5F2"));textSize=13f}
    private fun helpText(text:String)=TextView(this).apply{this.text=text;setTextColor(Color.parseColor("#8FA4B7"));textSize=12f;setPadding(0,dp(8),0,dp(8))}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}