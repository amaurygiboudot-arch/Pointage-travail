package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max
import kotlin.math.min

class DiamondDesignerReferenceActivity : Activity() {
    private lateinit var canvas: DiamondDesignerCanvas
    private lateinit var layersHost: LinearLayout
    private lateinit var presetsHost: LinearLayout
    private lateinit var panelHost: FrameLayout
    private lateinit var selectionLabel: TextView
    private lateinit var presets: MutableList<DiamondDesignerLibrary.Preset>
    private var currentProjectName = "Mon Diamond"

    private data class Control(val label: TextView, val bar: SeekBar)
    private lateinit var lens: Control; private lateinit var ring1: Control; private lateinit var ring2: Control; private lateinit var ring3: Control
    private lateinit var ringCount: Control; private lateinit var facetDensity: Control; private lateinit var facetDepth: Control
    private lateinit var sizeControl: Control; private lateinit var posX: Control; private lateinit var posY: Control; private lateinit var ellipseX: Control; private lateinit var ellipseY: Control
    private lateinit var transparency: Control; private lateinit var translucency: Control
    private lateinit var edgeWidth: Control; private lateinit var edgeAlpha: Control; private lateinit var edgeContrast: Control; private lateinit var edgeSoftness: Control
    private lateinit var radialEdges: Control; private lateinit var circularEdges: Control; private lateinit var light: Control; private lateinit var rotation: Control
    private lateinit var assistantStatus: TextView; private lateinit var assistantInput: EditText; private lateinit var assistantSend: Button
    private var beforeState: DiamondDesignerCanvas.DesignElement? = null
    private var showingBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presets = DiamondDesignerLibrary.load(this)
        window.statusBarColor = Color.parseColor("#07090C"); window.navigationBarColor = Color.parseColor("#07090C")
        setContentView(buildUi()); bindCanvas()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#07090C")) }
        root.addView(buildHeader(), lpMatch(dp(58))); root.addView(buildToolbar(), lpMatch(dp(58)))
        val scroller=ScrollView(this); val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(12))};scroller.addView(body)
        body.addView(sectionTitle("CALQUES"));layersHost=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};body.addView(card(layersHost))
        val workspace=FrameLayout(this).apply{setBackgroundColor(Color.parseColor("#101419"))};canvas=DiamondDesignerCanvas(this);workspace.addView(canvas,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));body.addView(workspace,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(390)).apply{topMargin=dp(8)})
        val previewRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;addView(smallButton("AVANT / APRÈS"){toggleBeforeAfter()},weightLp());addView(smallButton("RÉINITIALISER VUE"){resetSelectedView()},weightLp())};body.addView(card(previewRow))
        body.addView(sectionTitle("PRÉRÉGLAGES"));presetsHost=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};body.addView(card(presetsHost))
        body.addView(buildTabsArea(),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(560)).apply{topMargin=dp(8)})
        body.addView(sectionTitle("EXPORTATION"));body.addView(buildExportCard())
        root.addView(scroller,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),0,dp(10),0)
        addView(TextView(this@DiamondDesignerReferenceActivity).apply{text="DIAMOND DESIGNER";setTextColor(Color.WHITE);textSize=22f;gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f))
        addView(smallButton("OUVRIR"){openProject()});addView(smallButton("ENREGISTRER"){saveProject()})
    }

    private fun buildToolbar(): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled=false;addView(LinearLayout(this@DiamondDesignerReferenceActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(4));addView(toolButton("▶ + ENTRÉE"){canvas.addEntryButton();refreshAll()});addView(toolButton("Ⅱ + PAUSE"){canvas.addPauseButton();refreshAll()});addView(toolButton("■ + SORTIE"){canvas.addExitButton();refreshAll()});addView(toolButton("＋ CADRE"){canvas.addFrame();refreshAll()});addView(toolButton("＋ FOND"){canvas.addBackground();refreshAll()});addView(toolButton("◇ BIBLIOTHÈQUE"){showLibrary()})})
    }

    private fun buildTabsArea(): View {
        val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.parseColor("#0D1116"))}
        selectionLabel=TextView(this).apply{text="Aucun élément sélectionné";setTextColor(Color.parseColor("#D7E5F2"));textSize=13f;setPadding(dp(12),dp(8),dp(12),dp(6))};shell.addView(selectionLabel)
        val form=panel{p->
            p.addView(subTitle("GÉNÉRAL"));rotation=addControl(p,"Rotation",0,3600)
            p.addView(subTitle("ANNEAUX & FACETTES"));ringCount=addControl(p,"Nombre d’anneaux",1,3);facetDensity=addControl(p,"Facettes par anneau",8,64);facetDepth=addControl(p,"Profondeur des facettes",0,1000)
            ring1=addControl(p,"Anneau 1 (16) — déformation",200,1800);ring2=addControl(p,"Anneau 2 (32) — déformation",200,1800);ring3=addControl(p,"Anneau 3 (32) — déformation",200,1800)
            p.addView(subTitle("DÉFORMATION GLOBALE"));lens=addControl(p,"Bombage (convexe)",0,1000);ellipseX=addControl(p,"Ellipticité X",500,1500);ellipseY=addControl(p,"Ellipticité Y",500,1500)
            p.addView(subTitle("TAILLE & POSITION"));sizeControl=addControl(p,"Taille du bouton",100,1000);posX=addControl(p,"Position X",0,1000);posY=addControl(p,"Position Y",0,1000);p.addView(wideButton("RÉINITIALISER CET ONGLET"){resetForm()})
        }
        val material=panel{p->p.addView(subTitle("MATIÈRE — TRANSPARENCE & TRANSLUCIDITÉ"));transparency=addControl(p,"Transparence (efface le bouton)",0,950);translucency=addControl(p,"Translucidité (laisse voir le fond)",0,1000);p.addView(help("0 % = opaque. La transparence agit sur l’objet entier ; la translucidité conserve les facettes et les arêtes."))}
        val edges=panel{p->p.addView(subTitle("ARÊTES DES FACETTES"));edgeWidth=addControl(p,"Épaisseur",1,1200);edgeAlpha=addControl(p,"Visibilité",0,1000);edgeContrast=addControl(p,"Contraste",0,1000);edgeSoftness=addControl(p,"Douceur",0,1000);radialEdges=addControl(p,"Arêtes radiales",0,2000);circularEdges=addControl(p,"Arêtes circulaires",0,2000)}
        val lighting=panel{p->p.addView(subTitle("LUMIÈRE"));light=addControl(p,"Direction",0,3600);p.addView(help("La lumière agit sur les reflets sans remplacer les couleurs permanentes des facettes."))}
        val assistant=panel{p->p.addView(subTitle("ASSISTANT"));assistantStatus=TextView(this).apply{setTextColor(Color.parseColor("#AFC0CF"));text=if(BuildConfig.DESIGNER_AI_ENDPOINT.isBlank())"Assistant prêt côté appli • serveur à configurer" else "Assistant prêt"};p.addView(assistantStatus);assistantInput=EditText(this).apply{hint="Ex. Épaissis les arêtes extérieures et bombe davantage l’anneau 2";setTextColor(Color.WHITE);setHintTextColor(Color.parseColor("#718394"));minLines=2;maxLines=4};p.addView(assistantInput);assistantSend=wideButton("ENVOYER À L’ASSISTANT"){sendToAssistant()};p.addView(assistantSend)}
        val tabs=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(LinearLayout(this@DiamondDesignerReferenceActivity).apply{orientation=LinearLayout.HORIZONTAL;addView(tabButton("◇ FORME"){showPanel(form)});addView(tabButton("◇ MATIÈRE"){showPanel(material)});addView(tabButton("◐ ARÊTES"){showPanel(edges)});addView(tabButton("☼ LUMIÈRE"){showPanel(lighting)});addView(tabButton("♙ ASSISTANT"){showPanel(assistant)})})};shell.addView(tabs,lpMatch(dp(48)))
        panelHost=FrameLayout(this);listOf(form,material,edges,lighting,assistant).forEach{panelHost.addView(it)};shell.addView(panelHost,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));showPanel(form);return shell
    }

    private fun buildExportCard(): View = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        addView(wideButton("▧ EXPORTER BOUTON PNG"){exportPng()})
        addView(wideButton("▤ EXPORTER THÈME (.JSON)"){exportJson()})
        addView(wideButton("▯ APERÇU DANS L’APP"){showAppPreview()})
    }.let(::card)

    private fun bindCanvas(){
        canvas.onSelectionChanged={e->if(!showingBefore)beforeState=e?.copy();updateControls(e);refreshLayers()}
        canvas.onDesignChanged={canvas.selectedElement()?.let(::updateLabels);refreshLayers()}
        lens.bar.listen{canvas.setSelectedLens(it/1000f)};ring1.bar.listen{canvas.setSelectedRingGain(1,it/1000f)};ring2.bar.listen{canvas.setSelectedRingGain(2,it/1000f)};ring3.bar.listen{canvas.setSelectedRingGain(3,it/1000f)}
        ringCount.bar.listen{canvas.setSelectedRingCount(it)};facetDensity.bar.listen{canvas.setSelectedFacetDensity(it)};facetDepth.bar.listen{canvas.setSelectedFacetDepth(it/1000f)}
        transparency.bar.listen{canvas.setSelectedTransparency(it/1000f)};translucency.bar.listen{canvas.setSelectedTranslucency(it/1000f)};rotation.bar.listen{canvas.setSelectedRotation(it/10f)};light.bar.listen{canvas.setSelectedLightAngle(it/10f)}
        edgeWidth.bar.listen{canvas.setSelectedEdgeWidth(it/100f)};edgeAlpha.bar.listen{canvas.setSelectedEdgeAlpha(it/1000f)};edgeContrast.bar.listen{canvas.setSelectedEdgeContrast(it/1000f)};edgeSoftness.bar.listen{canvas.setSelectedEdgeSoftness(it/1000f)};radialEdges.bar.listen{canvas.setSelectedRadialEdgeGain(it/1000f)};circularEdges.bar.listen{canvas.setSelectedCircularEdgeGain(it/1000f)}
        sizeControl.bar.listen{p->mutateSelected{e->val side=max(72f,min(canvas.width,canvas.height)*(p/1000f));val cx=e.x+e.width/2f;val cy=e.y+e.height/2f;e.width=side;e.height=side;e.x=cx-side/2f;e.y=cy-side/2f}}
        posX.bar.listen{p->mutateSelected{e->e.x=(canvas.width-e.width).coerceAtLeast(0f)*p/1000f}};posY.bar.listen{p->mutateSelected{e->e.y=(canvas.height-e.height).coerceAtLeast(0f)*p/1000f}}
        ellipseX.bar.listen{p->mutateSelected{e->val center=e.x+e.width/2f;val base=min(e.width,e.height);e.width=base*(p/1000f);e.x=center-e.width/2f}};ellipseY.bar.listen{p->mutateSelected{e->val center=e.y+e.height/2f;val base=min(e.width,e.height);e.height=base*(p/1000f);e.y=center-e.height/2f}}
        refreshAll()
    }

    private fun isDiamond(e:DiamondDesignerCanvas.DesignElement?)=e?.type==DiamondDesignerCanvas.ElementType.ENTRY_BUTTON||e?.type==DiamondDesignerCanvas.ElementType.PAUSE_BUTTON||e?.type==DiamondDesignerCanvas.ElementType.EXIT_BUTTON
    private fun mutateSelected(block:(DiamondDesignerCanvas.DesignElement)->Unit){val e=canvas.selectedElement()?:return;block(e);canvas.invalidate();updateControls(e);refreshLayers()}
    private fun refreshAll(){refreshLayers();refreshPresets();updateControls(canvas.selectedElement())}

    private fun refreshLayers(){
        if(!::layersHost.isInitialized)return;layersHost.removeAllViews();val list=canvas.allElements();if(list.isEmpty()){layersHost.addView(help("Aucun calque"));return}
        list.asReversed().forEach{e->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};row.addView(smallButton(if(e.visible)"◉" else "○"){e.visible=!e.visible;canvas.invalidate();refreshLayers()},LinearLayout.LayoutParams(dp(52),dp(42)))
            val name=TextView(this).apply{text=e.name;setTextColor(if(canvas.selectedElement()===e)Color.parseColor("#E0B24E") else Color.WHITE);textSize=14f;gravity=Gravity.CENTER_VERTICAL;setOnClickListener{canvas.selectElement(e);refreshAll()}};row.addView(name,LinearLayout.LayoutParams(0,dp(42),1f));row.addView(smallButton(if(e.locked)"🔒" else "⚙"){e.locked=!e.locked;canvas.invalidate();refreshLayers()},LinearLayout.LayoutParams(dp(58),dp(42)));layersHost.addView(row)
        }
        val s=canvas.selectedElement();if(isDiamond(s)&&s!=null){layersHost.addView(subTitle("SOUS-CALQUES DU DIAMANT"));subLayerRow("Anneau 3 (${s.facetDensity})",s.ring3Visible){canvas.setSelectedSubLayerVisible("ring3",it)};subLayerRow("Anneau 2 (${s.facetDensity})",s.ring2Visible){canvas.setSelectedSubLayerVisible("ring2",it)};subLayerRow("Anneau 1 (${max(8,s.facetDensity/2)})",s.ring1Visible){canvas.setSelectedSubLayerVisible("ring1",it)};subLayerRow("Noyau",s.coreVisible){canvas.setSelectedSubLayerVisible("core",it)};subLayerRow("Facettes",s.facetsVisible){canvas.setSelectedSubLayerVisible("facets",it)};subLayerRow("Arêtes",s.edgesVisible){canvas.setSelectedSubLayerVisible("edges",it)}}
    }

    private fun subLayerRow(label:String,visible:Boolean,onToggle:(Boolean)->Unit){val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(26),0,0,0)};row.addView(smallButton(if(visible)"◉" else "○"){onToggle(!visible);refreshLayers()},LinearLayout.LayoutParams(dp(52),dp(40)));row.addView(TextView(this).apply{text=label;setTextColor(Color.parseColor("#C9D5DF"));textSize=13f;gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(40),1f));layersHost.addView(row)}

    private fun refreshPresets(){if(!::presetsHost.isInitialized)return;presetsHost.removeAllViews();presets.take(8).forEach{p->presetsHost.addView(wideButton(p.name){canvas.addPreset(p);refreshAll()})};presetsHost.addView(wideButton("＋ ENREGISTRER LE RÉGLAGE ACTUEL"){saveSelectedPreset()})}

    private fun toggleBeforeAfter(){val current=canvas.selectedElement()?:return;val before=beforeState?:return;val now=current.copy();copyVisual(before,current);beforeState=now;showingBefore=!showingBefore;Toast.makeText(this,if(showingBefore)"AVANT" else "APRÈS",Toast.LENGTH_SHORT).show();canvas.invalidate();updateControls(current)}
    private fun copyVisual(f:DiamondDesignerCanvas.DesignElement,t:DiamondDesignerCanvas.DesignElement){t.x=f.x;t.y=f.y;t.width=f.width;t.height=f.height;t.rotation=f.rotation;t.alpha=f.alpha;t.transparency=f.transparency;t.translucency=f.translucency;t.lensStrength=f.lensStrength;t.lightAngle=f.lightAngle;t.ring1Gain=f.ring1Gain;t.ring2Gain=f.ring2Gain;t.ring3Gain=f.ring3Gain;t.ringCount=f.ringCount;t.facetDensity=f.facetDensity;t.facetDepth=f.facetDepth;t.ring1Visible=f.ring1Visible;t.ring2Visible=f.ring2Visible;t.ring3Visible=f.ring3Visible;t.coreVisible=f.coreVisible;t.facetsVisible=f.facetsVisible;t.edgesVisible=f.edgesVisible;t.edgeWidth=f.edgeWidth;t.edgeAlpha=f.edgeAlpha;t.edgeContrast=f.edgeContrast;t.edgeSoftness=f.edgeSoftness;t.radialEdgeGain=f.radialEdgeGain;t.circularEdgeGain=f.circularEdgeGain}
    private fun resetSelectedView(){mutateSelected{it.rotation=0f}}
    private fun resetForm(){mutateSelected{e->e.rotation=0f;e.lensStrength=.5f;e.ring1Gain=1f;e.ring2Gain=1f;e.ring3Gain=1f;e.ringCount=3;e.facetDensity=32;e.facetDepth=.68f;val side=min(canvas.width,canvas.height)*.42f;e.width=side;e.height=side;e.x=(canvas.width-side)/2f;e.y=(canvas.height-side)/2f}}

    private fun updateControls(e:DiamondDesignerCanvas.DesignElement?){if(e==null){selectionLabel.text="Aucun élément sélectionné";return};selectionLabel.text="${e.name} • ${e.type}${if(e.locked)" • verrouillé" else ""}";lens.bar.progress=(e.lensStrength*1000).toInt();ring1.bar.progress=(e.ring1Gain*1000).toInt();ring2.bar.progress=(e.ring2Gain*1000).toInt();ring3.bar.progress=(e.ring3Gain*1000).toInt();ringCount.bar.progress=e.ringCount;facetDensity.bar.progress=e.facetDensity;facetDepth.bar.progress=(e.facetDepth*1000).toInt();transparency.bar.progress=(e.transparency*1000).toInt();translucency.bar.progress=(e.translucency*1000).toInt();rotation.bar.progress=((((e.rotation%360)+360)%360)*10).toInt();light.bar.progress=(e.lightAngle*10).toInt();edgeWidth.bar.progress=(e.edgeWidth*100).toInt();edgeAlpha.bar.progress=(e.edgeAlpha*1000).toInt();edgeContrast.bar.progress=(e.edgeContrast*1000).toInt();edgeSoftness.bar.progress=(e.edgeSoftness*1000).toInt();radialEdges.bar.progress=(e.radialEdgeGain*1000).toInt();circularEdges.bar.progress=(e.circularEdgeGain*1000).toInt();val cw=max(1,canvas.width);val ch=max(1,canvas.height);sizeControl.bar.progress=((max(e.width,e.height)/min(cw,ch))*1000).toInt().coerceIn(100,1000);posX.bar.progress=if(cw>e.width)(e.x/(cw-e.width)*1000).toInt().coerceIn(0,1000) else 500;posY.bar.progress=if(ch>e.height)(e.y/(ch-e.height)*1000).toInt().coerceIn(0,1000) else 500;ellipseX.bar.progress=((e.width/max(1f,min(e.width,e.height)))*1000).toInt().coerceIn(500,1500);ellipseY.bar.progress=((e.height/max(1f,min(e.width,e.height)))*1000).toInt().coerceIn(500,1500);updateLabels(e)}
    private fun updateLabels(e:DiamondDesignerCanvas.DesignElement){lens.label.text="Bombage (convexe) : ${(e.lensStrength*100).toInt()} %";ring1.label.text="Anneau 1 : ${(e.ring1Gain*100).toInt()} %";ring2.label.text="Anneau 2 : ${(e.ring2Gain*100).toInt()} %";ring3.label.text="Anneau 3 : ${(e.ring3Gain*100).toInt()} %";ringCount.label.text="Nombre d’anneaux : ${e.ringCount}";facetDensity.label.text="Facettes par anneau : ${e.facetDensity}";facetDepth.label.text="Profondeur des facettes : ${(e.facetDepth*100).toInt()} %";transparency.label.text="Transparence : ${(e.transparency*100).toInt()} %";translucency.label.text="Translucidité : ${(e.translucency*100).toInt()} %";rotation.label.text="Rotation : ${e.rotation.toInt()}°";light.label.text="Lumière : ${e.lightAngle.toInt()}°";edgeWidth.label.text="Épaisseur : ${"%.1f".format(e.edgeWidth)} px";edgeAlpha.label.text="Visibilité : ${(e.edgeAlpha*100).toInt()} %";edgeContrast.label.text="Contraste : ${(e.edgeContrast*100).toInt()} %";edgeSoftness.label.text="Douceur : ${(e.edgeSoftness*100).toInt()} %";radialEdges.label.text="Arêtes radiales : ${(e.radialEdgeGain*100).toInt()} %";circularEdges.label.text="Arêtes circulaires : ${(e.circularEdgeGain*100).toInt()} %";sizeControl.label.text="Taille du bouton";posX.label.text="Position X";posY.label.text="Position Y";ellipseX.label.text="Ellipticité X";ellipseY.label.text="Ellipticité Y"}

    private fun openProject(){val projects=DiamondDesignerProjectStore.list(this);if(projects.isEmpty()){Toast.makeText(this,"Aucun projet enregistré",Toast.LENGTH_SHORT).show();return};AlertDialog.Builder(this).setTitle("Ouvrir un projet").setItems(projects.map{it.name}.toTypedArray()){_,i->projects.getOrNull(i)?.let{currentProjectName=it.name;canvas.replaceElements(it.elements);refreshAll()}}.setNegativeButton("Fermer",null).show()}
    private fun saveProject(){val input=EditText(this).apply{setText(currentProjectName);selectAll()};AlertDialog.Builder(this).setTitle("Enregistrer le projet").setView(input).setPositiveButton("Enregistrer"){_,_->currentProjectName=input.text?.toString()?.trim().orEmpty().ifBlank{"Mon Diamond"};DiamondDesignerProjectStore.save(this,DiamondDesignerProjectStore.Project(currentProjectName,canvas.snapshot()));Toast.makeText(this,"$currentProjectName enregistré",Toast.LENGTH_SHORT).show()}.setNegativeButton("Annuler",null).show()}
    private fun showLibrary(){AlertDialog.Builder(this).setTitle("Bibliothèque").setItems(presets.map{it.name}.toTypedArray()){_,i->presets.getOrNull(i)?.let{canvas.addPreset(it);refreshAll()}}.setNegativeButton("Fermer",null).show()}
    private fun saveSelectedPreset(){val e=canvas.selectedElement()?:run{Toast.makeText(this,"Sélectionne un élément",Toast.LENGTH_SHORT).show();return};val input=EditText(this).apply{setText(e.name);selectAll()};AlertDialog.Builder(this).setTitle("Enregistrer le préréglage").setView(input).setPositiveButton("Enregistrer"){_,_->val name=input.text?.toString()?.trim().orEmpty().ifBlank{e.name};presets.add(DiamondDesignerLibrary.presetFromElement(name,e));DiamondDesignerLibrary.save(this,presets);refreshPresets()}.setNegativeButton("Annuler",null).show()}

    private fun exportPng(){runCatching{DiamondDesignerExporter.exportCanvasPng(this,canvas,currentProjectName)}.onSuccess{shareFile(it,"image/png")}.onFailure{Toast.makeText(this,it.message?:"Erreur export PNG",Toast.LENGTH_LONG).show()}}
    private fun exportJson(){val project=DiamondDesignerProjectStore.Project(currentProjectName,canvas.snapshot());runCatching{DiamondDesignerExporter.exportProjectJson(this,project)}.onSuccess{shareFile(it,"application/json")}.onFailure{Toast.makeText(this,it.message?:"Erreur export JSON",Toast.LENGTH_LONG).show()}}
    private fun shareFile(file:File,mime:String){val uri=FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.exports",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Exporter avec…"))}
    private fun showAppPreview(){val bmp=DiamondDesignerExporter.renderBitmap(canvas);val image=ImageView(this).apply{setImageBitmap(bmp);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(Color.parseColor("#07090C"))};AlertDialog.Builder(this).setTitle("Aperçu dans l’app").setView(image).setPositiveButton("Fermer"){_,_->bmp.recycle()}.setOnCancelListener{bmp.recycle()}.show()}

    private fun sendToAssistant(){val message=assistantInput.text?.toString()?.trim().orEmpty();if(message.isBlank())return;assistantSend.isEnabled=false;assistantStatus.text="Assistant en cours…";Thread{runCatching{DiamondDesignerAssistant.ask(message,canvas.report())}.onSuccess{result->runOnUiThread{val count=DiamondDesignerAssistant.apply(canvas,result.actions);assistantStatus.text="${result.reply} • $count action(s)";assistantInput.text?.clear();assistantSend.isEnabled=true;refreshAll()}}.onFailure{err->runOnUiThread{assistantStatus.text=err.message?:"Erreur assistant";assistantSend.isEnabled=true}}}.start()}

    private fun panel(build:(LinearLayout)->Unit):ScrollView{val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(12))};build(body);return ScrollView(this).apply{visibility=View.GONE;addView(body);setBackgroundColor(Color.parseColor("#0D1116"))}}
    private fun showPanel(target:View){for(i in 0 until panelHost.childCount)panelHost.getChildAt(i).visibility=if(panelHost.getChildAt(i)===target)View.VISIBLE else View.GONE}
    private fun addControl(parent:LinearLayout,name:String,min:Int,max:Int):Control{val label=TextView(this).apply{text=name;setTextColor(Color.parseColor("#DCE6EF"));textSize=13f};val bar=SeekBar(this).apply{this.min=min;this.max=max};parent.addView(label);parent.addView(bar);return Control(label,bar)}
    private fun SeekBar.listen(action:(Int)->Unit){setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,from:Boolean){if(from)action(p)};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})}
    private fun sectionTitle(text:String)=TextView(this).apply{this.text=text;setTextColor(Color.WHITE);textSize=15f;setPadding(dp(4),dp(10),dp(4),dp(4))}
    private fun subTitle(text:String)=TextView(this).apply{this.text=text;setTextColor(Color.WHITE);textSize=14f;setPadding(0,dp(10),0,dp(5))}
    private fun help(text:String)=TextView(this).apply{this.text=text;setTextColor(Color.parseColor("#91A4B5"));textSize=12f;setPadding(0,dp(6),0,dp(6))}
    private fun card(content:View)=FrameLayout(this).apply{setPadding(dp(8),dp(6),dp(8),dp(6));setBackgroundColor(Color.parseColor("#11161C"));addView(content)}
    private fun smallButton(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;textSize=11f;setOnClickListener{action()}}
    private fun wideButton(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;setOnClickListener{action()}}
    private fun toolButton(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;textSize=12f;setOnClickListener{action()}}.also{it.layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(46)).apply{marginEnd=dp(6)}}
    private fun tabButton(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;textSize=12f;setOnClickListener{action()}}.also{it.layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(44)).apply{marginEnd=dp(4)}}
    private fun lpMatch(h:Int)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,h)
    private fun weightLp()=LinearLayout.LayoutParams(0,dp(46),1f).apply{marginEnd=dp(4)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
