package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class DiamondDesignerCanvas(context: Context) : View(context) {
    enum class ElementType { ENTRY_BUTTON, PAUSE_BUTTON, EXIT_BUTTON, FRAME, BACKGROUND }

    data class DesignElement(
        var type: ElementType,
        var x: Float,
        var y: Float,
        var width: Float,
        var height: Float,
        var rotation: Float = 0f,
        var alpha: Float = 1f,
        var transparency: Float = 0f,
        var translucency: Float = 0f,
        var lensStrength: Float = .50f,
        var lightAngle: Float = 305f,
        var ring1Gain: Float = 1f,
        var ring2Gain: Float = 1f,
        var ring3Gain: Float = 1f,
        var ringCount: Int = 3,
        var facetDensity: Int = 32,
        var facetDepth: Float = .68f,
        var ring1Visible: Boolean = true,
        var ring2Visible: Boolean = true,
        var ring3Visible: Boolean = true,
        var coreVisible: Boolean = true,
        var facetsVisible: Boolean = true,
        var edgesVisible: Boolean = true,
        var edgeWidth: Float = 1.4f,
        var edgeAlpha: Float = .55f,
        var edgeContrast: Float = .62f,
        var edgeSoftness: Float = .08f,
        var radialEdgeGain: Float = 1f,
        var circularEdgeGain: Float = 1f,
        var frameWidth: Float = 12f,
        var cornerRadius: Float = 24f,
        var locked: Boolean = false,
        var visible: Boolean = true,
        var name: String = type.name
    )

    var onSelectionChanged: ((DesignElement?) -> Unit)? = null
    var onDesignChanged: (() -> Unit)? = null

    private val elements = mutableListOf<DesignElement>()
    private var selected: DesignElement? = null
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var entryPreview: GreenDiamondFinalButton? = null
    private var pausePreview: OrangeDiamondFinalButton? = null
    private var exitPreview: RedDiamondFinalButton? = null

    private enum class DragMode { NONE, MOVE, RESIZE, ROTATE }
    private var dragMode = DragMode.NONE
    private var downX = 0f; private var downY = 0f
    private var startX = 0f; private var startY = 0f; private var startW = 0f; private var startH = 0f
    private var startRotation = 0f; private var startPointerAngle = 0f

    init {
        setBackgroundColor(Color.rgb(18,20,24))
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        post { if (elements.isEmpty()) addEntryButton() }
    }

    fun allElements(): List<DesignElement> = elements.toList()
    fun snapshot(): List<DesignElement> = elements.map { it.copy() }
    fun selectedElement(): DesignElement? = selected
    fun selectElement(e: DesignElement?) { if (e == null || elements.contains(e)) select(e) }
    fun replaceElements(items: List<DesignElement>) {
        elements.clear(); elements.addAll(items.map { it.copy() }); select(elements.lastOrNull()); changed()
    }

    fun addEntryButton() = addButton(ElementType.ENTRY_BUTTON,"Bouton Entrée")
    fun addPauseButton() = addButton(ElementType.PAUSE_BUTTON,"Bouton Pause")
    fun addExitButton() = addButton(ElementType.EXIT_BUTTON,"Bouton Sortie")

    private fun addButton(type: ElementType, name: String) {
        val size = min(width.takeIf { it > 0 } ?: 700, height.takeIf { it > 0 } ?: 900) * .42f
        val e = DesignElement(type,max(20f,(width-size)/2f),max(80f,(height-size)/2f),size,size,name=name)
        elements.add(e); select(e); changed()
    }

    fun addFrame() { val e=DesignElement(ElementType.FRAME,48f,90f,max(280f,width*.70f),max(180f,height*.30f),alpha=.9f,name="Cadre"); elements.add(e); select(e); changed() }
    fun addBackground() { val e=DesignElement(ElementType.BACKGROUND,0f,0f,width.toFloat(),height.toFloat(),locked=true,name="Fond"); elements.add(0,e); select(e); changed() }

    fun addPreset(p: DiamondDesignerLibrary.Preset) {
        val x=max(20f,(width-p.width)/2f); val y=max(40f,(height-p.height)/2f)
        val e=DesignElement(type=p.type,x=x,y=y,width=p.width,height=p.height,rotation=p.rotation,alpha=p.alpha,transparency=p.transparency,translucency=p.translucency,lensStrength=p.lensStrength,lightAngle=p.lightAngle,ring1Gain=p.ring1Gain,ring2Gain=p.ring2Gain,ring3Gain=p.ring3Gain,edgeWidth=p.edgeWidth,edgeAlpha=p.edgeAlpha,edgeContrast=p.edgeContrast,edgeSoftness=p.edgeSoftness,radialEdgeGain=p.radialEdgeGain,circularEdgeGain=p.circularEdgeGain,frameWidth=p.frameWidth,cornerRadius=p.cornerRadius,name=p.name)
        if(p.type==ElementType.BACKGROUND) elements.add(0,e) else elements.add(e); select(e); changed()
    }

    fun duplicateSelected(){ val s=selected?:return; val e=s.copy(x=s.x+24f,y=s.y+24f,locked=false,name=s.name+" copie"); elements.add(e); select(e); changed() }
    fun deleteSelected(){ val s=selected?:return; elements.remove(s); select(elements.lastOrNull()); changed() }
    fun bringForward(){ val s=selected?:return; val i=elements.indexOf(s); if(i>=0&&i<elements.lastIndex){elements.removeAt(i);elements.add(i+1,s);changed()} }
    fun sendBackward(){ val s=selected?:return; val i=elements.indexOf(s); if(i>0){elements.removeAt(i);elements.add(i-1,s);changed()} }

    private fun isDiamond(e: DesignElement?) = e?.type==ElementType.ENTRY_BUTTON || e?.type==ElementType.PAUSE_BUTTON || e?.type==ElementType.EXIT_BUTTON
    fun setSelectedLens(v:Float){selected?.let{if(isDiamond(it)){it.lensStrength=v.coerceIn(0f,1f);changed()}}}
    fun setSelectedAlpha(v:Float){selected?.let{it.alpha=v.coerceIn(.05f,1f);changed()}}
    fun setSelectedTransparency(v:Float){selected?.let{it.transparency=v.coerceIn(0f,.95f);changed()}}
    fun setSelectedTranslucency(v:Float){selected?.let{if(isDiamond(it)){it.translucency=v.coerceIn(0f,1f);changed()}}}
    fun setSelectedRotation(v:Float){selected?.let{it.rotation=v;changed()}}
    fun setSelectedLightAngle(v:Float){selected?.let{if(isDiamond(it)){it.lightAngle=((v%360f)+360f)%360f;changed()}}}
    fun setSelectedRingGain(ring:Int,v:Float){selected?.let{if(isDiamond(it)){val g=v.coerceIn(.2f,1.8f);when(ring){1->it.ring1Gain=g;2->it.ring2Gain=g;3->it.ring3Gain=g};changed()}}}
    fun setSelectedRingCount(v:Int){selected?.let{if(isDiamond(it)){it.ringCount=v.coerceIn(1,3);changed()}}}
    fun setSelectedFacetDensity(v:Int){selected?.let{if(isDiamond(it)){it.facetDensity=v.coerceIn(8,64);changed()}}}
    fun setSelectedFacetDepth(v:Float){selected?.let{if(isDiamond(it)){it.facetDepth=v.coerceIn(0f,1f);changed()}}}
    fun setSelectedSubLayerVisible(layer:String,visible:Boolean){selected?.let{if(isDiamond(it)){when(layer){"ring1"->it.ring1Visible=visible;"ring2"->it.ring2Visible=visible;"ring3"->it.ring3Visible=visible;"core"->it.coreVisible=visible;"facets"->it.facetsVisible=visible;"edges"->it.edgesVisible=visible};changed()}}}
    fun setSelectedEdgeWidth(v:Float){selected?.let{if(isDiamond(it)){it.edgeWidth=v.coerceIn(.1f,12f);changed()}}}
    fun setSelectedEdgeAlpha(v:Float){selected?.let{if(isDiamond(it)){it.edgeAlpha=v.coerceIn(0f,1f);changed()}}}
    fun setSelectedEdgeContrast(v:Float){selected?.let{if(isDiamond(it)){it.edgeContrast=v.coerceIn(0f,1f);changed()}}}
    fun setSelectedEdgeSoftness(v:Float){selected?.let{if(isDiamond(it)){it.edgeSoftness=v.coerceIn(0f,1f);changed()}}}
    fun setSelectedRadialEdgeGain(v:Float){selected?.let{if(isDiamond(it)){it.radialEdgeGain=v.coerceIn(0f,2f);changed()}}}
    fun setSelectedCircularEdgeGain(v:Float){selected?.let{if(isDiamond(it)){it.circularEdgeGain=v.coerceIn(0f,2f);changed()}}}
    fun setSelectedFrameWidth(v:Float){selected?.let{if(it.type==ElementType.FRAME){it.frameWidth=v.coerceIn(1f,80f);changed()}}}
    fun setSelectedCornerRadius(v:Float){selected?.let{if(it.type==ElementType.FRAME){it.cornerRadius=v.coerceIn(0f,120f);changed()}}}
    fun toggleLock(){selected?.let{it.locked=!it.locked;onSelectionChanged?.invoke(it);invalidate()}}
    private fun changed(){invalidate();onDesignChanged?.invoke()}

    override fun onDraw(canvas:Canvas){super.onDraw(canvas);for(e in elements)if(e.visible)drawElement(canvas,e);selected?.takeIf{it.visible}?.let{drawSelection(canvas,it)}}

    private fun drawElement(canvas:Canvas,e:DesignElement){
        canvas.save();canvas.rotate(e.rotation,e.x+e.width/2f,e.y+e.height/2f)
        val effectiveAlpha=(e.alpha*(1f-e.transparency)).coerceIn(0f,1f)
        when(e.type){
            ElementType.BACKGROUND->{bgPaint.color=Color.argb((255*effectiveAlpha).toInt(),12,18,28);canvas.drawRect(e.x,e.y,e.x+e.width,e.y+e.height,bgPaint)}
            ElementType.FRAME->{framePaint.strokeWidth=e.frameWidth;framePaint.color=Color.argb((255*effectiveAlpha).toInt(),205,216,230);canvas.drawRoundRect(e.x,e.y,e.x+e.width,e.y+e.height,e.cornerRadius,e.cornerRadius,framePaint)}
            ElementType.ENTRY_BUTTON->drawDiamondButton(canvas,e,entryPreview?:GreenDiamondFinalButton(context).also{entryPreview=it})
            ElementType.PAUSE_BUTTON->drawDiamondButton(canvas,e,pausePreview?:OrangeDiamondFinalButton(context).also{pausePreview=it})
            ElementType.EXIT_BUTTON->drawDiamondButton(canvas,e,exitPreview?:RedDiamondFinalButton(context).also{exitPreview=it})
        };canvas.restore()
    }

    private fun drawDiamondButton(canvas:Canvas,e:DesignElement,v:RedDiamondFinalButton){
        val w=max(1,e.width.toInt());val h=max(1,e.height.toInt());v.setDiamondLightAngle(e.lightAngle);v.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        canvas.save();canvas.translate(e.x,e.y)
        val overallAlpha=(255f*e.alpha*(1f-e.transparency)).toInt().coerceIn(8,255);val wholeLayer=canvas.saveLayerAlpha(0f,0f,w.toFloat(),h.toFloat(),overallAlpha)
        val materialAlpha=(255f*(1f-.70f*e.translucency)).toInt().coerceIn(76,255)
        val depth=.45f+.75f*e.facetDepth
        if(e.facetsVisible){
            if(e.ring1Visible&&e.ringCount>=1)drawDiamondRing(canvas,v,w,h,0f,.28f,e.lensStrength,e.ring1Gain*depth,materialAlpha)
            if(e.ring2Visible&&e.ringCount>=2)drawDiamondRing(canvas,v,w,h,.28f,.63f,e.lensStrength,e.ring2Gain*depth,materialAlpha)
            if(e.ring3Visible&&e.ringCount>=3)drawDiamondRing(canvas,v,w,h,.63f,1f,e.lensStrength,e.ring3Gain*depth,materialAlpha)
        }
        if(e.coreVisible)drawCore(canvas,e,w,h,materialAlpha)
        if(e.edgesVisible)drawFacetEdges(canvas,e,w,h)
        canvas.restoreToCount(wholeLayer);canvas.restore()
    }

    private fun drawDiamondRing(canvas:Canvas,v:RedDiamondFinalButton,w:Int,h:Int,inner:Float,outer:Float,lens:Float,gain:Float,materialAlpha:Int){
        val cx=w*.5f;val cy=h*.5f;val r=min(w,h)*.455f;val ring=Path().apply{fillType=Path.FillType.EVEN_ODD;addCircle(cx,cy,r*outer,Path.Direction.CW);if(inner>0f)addCircle(cx,cy,r*inner,Path.Direction.CW)}
        canvas.save();canvas.clipPath(ring);val layer=canvas.saveLayerAlpha(0f,0f,w.toFloat(),h.toFloat(),materialAlpha);val ringScale=(1f+(gain-1f)*.28f).coerceIn(.76f,1.24f);canvas.scale(ringScale,ringScale,cx,cy);v.setLensStrength((lens*(.65f+.35f*gain)).coerceIn(0f,1f));v.draw(canvas);canvas.restoreToCount(layer);canvas.restore()
    }

    private fun drawCore(canvas:Canvas,e:DesignElement,w:Int,h:Int,alpha:Int){
        val cx=w*.5f;val cy=h*.5f;val r=min(w,h)*.455f*.16f;val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=Color.argb((alpha*.34f).toInt().coerceIn(12,150),255,255,255);p.style=Paint.Style.FILL;canvas.drawCircle(cx,cy,r,p)
    }

    private fun drawFacetEdges(canvas:Canvas,e:DesignElement,w:Int,h:Int){
        if(e.edgeAlpha<=0f||e.edgeWidth<=0f)return
        val cx=w*.5f;val cy=h*.5f;val r=min(w,h)*.455f;val baseAlpha=(255f*e.edgeAlpha).toInt().coerceIn(0,255);val bright=(120+135*e.edgeContrast).toInt().coerceIn(0,255)
        edgePaint.strokeWidth=e.edgeWidth;edgePaint.color=Color.argb(baseAlpha,bright,bright,bright);edgePaint.maskFilter=if(e.edgeSoftness>.01f)BlurMaskFilter(1f+e.edgeSoftness*9f,BlurMaskFilter.Blur.NORMAL)else null
        edgePaint.alpha=(baseAlpha*e.radialEdgeGain.coerceIn(0f,2f)).toInt().coerceIn(0,255)
        val innerCount=max(8,e.facetDensity/2);val outerCount=e.facetDensity
        if(e.ring1Visible&&e.ringCount>=1)for(i in 0 until innerCount){val a=Math.toRadians((-90f+i*(360f/innerCount)).toDouble());canvas.drawLine(cx,cy,cx+cos(a).toFloat()*r*.28f,cy+sin(a).toFloat()*r*.28f,edgePaint)}
        if((e.ring2Visible&&e.ringCount>=2)||(e.ring3Visible&&e.ringCount>=3))for(i in 0 until outerCount){val a=Math.toRadians((-90f+i*(360f/outerCount)).toDouble());val start=if(e.ring2Visible&&e.ringCount>=2).28f else .63f;val end=if(e.ring3Visible&&e.ringCount>=3).96f else .63f;canvas.drawLine(cx+cos(a).toFloat()*r*start,cy+sin(a).toFloat()*r*start,cx+cos(a).toFloat()*r*end,cy+sin(a).toFloat()*r*end,edgePaint)}
        edgePaint.alpha=(baseAlpha*e.circularEdgeGain.coerceIn(0f,2f)).toInt().coerceIn(0,255);if(e.ring1Visible&&e.ringCount>=1)canvas.drawCircle(cx,cy,r*.28f,edgePaint);if(e.ring2Visible&&e.ringCount>=2)canvas.drawCircle(cx,cy,r*.63f,edgePaint);if(e.ring3Visible&&e.ringCount>=3)canvas.drawCircle(cx,cy,r*.96f,edgePaint);edgePaint.maskFilter=null;edgePaint.alpha=255
    }

    private fun drawSelection(canvas:Canvas,e:DesignElement){val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height);canvas.save();canvas.rotate(e.rotation,r.centerX(),r.centerY());selectPaint.pathEffect=if(e.locked)DashPathEffect(floatArrayOf(12f,8f),0f)else null;canvas.drawRect(r,selectPaint);canvas.drawCircle(r.right,r.bottom,12f,handlePaint);canvas.drawCircle(r.centerX(),r.top-34f,12f,handlePaint);canvas.restore()}

    override fun onTouchEvent(event:MotionEvent):Boolean{when(event.actionMasked){MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;val hit=findHit(event.x,event.y);if(hit!=null)select(hit);val s=selected?:return true;if(s.locked)return true;startX=s.x;startY=s.y;startW=s.width;startH=s.height;startRotation=s.rotation;val rd=hypot(event.x-(s.x+s.width),event.y-(s.y+s.height));val ad=hypot(event.x-(s.x+s.width/2f),event.y-(s.y-34f));dragMode=when{rd<52f->DragMode.RESIZE;ad<52f->{startPointerAngle=pointerAngle(event.x,event.y,s);DragMode.ROTATE};else->DragMode.MOVE};return true};MotionEvent.ACTION_MOVE->{val s=selected?:return true;if(s.locked)return true;when(dragMode){DragMode.MOVE->{s.x=startX+event.x-downX;s.y=startY+event.y-downY};DragMode.RESIZE->{s.width=max(72f,startW+event.x-downX);s.height=max(72f,startH+event.y-downY)};DragMode.ROTATE->s.rotation=startRotation+pointerAngle(event.x,event.y,s)-startPointerAngle;else->Unit};changed();return true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragMode=DragMode.NONE;return true}};return super.onTouchEvent(event)}
    private fun pointerAngle(x:Float,y:Float,e:DesignElement)=Math.toDegrees(atan2((y-(e.y+e.height/2f)).toDouble(),(x-(e.x+e.width/2f)).toDouble())).toFloat()
    private fun findHit(x:Float,y:Float):DesignElement?{for(i in elements.indices.reversed()){val e=elements[i];if(!e.visible)continue;if(x>=e.x&&x<=e.x+e.width&&y>=e.y&&y<=e.y+e.height)return e};return null}
    private fun select(e:DesignElement?){selected=e;onSelectionChanged?.invoke(e);invalidate()}

    fun report():String=buildString{appendLine("DIAMOND DESIGNER REPORT");appendLine("elements=${elements.size}");elements.forEachIndexed{i,e->appendLine("[$i] name=${e.name}; type=${e.type}; x=${"%.1f".format(e.x)}; y=${"%.1f".format(e.y)}; w=${"%.1f".format(e.width)}; h=${"%.1f".format(e.height)}; rotation=${"%.1f".format(e.rotation)}; transparency=${"%.3f".format(e.transparency)}; translucency=${"%.3f".format(e.translucency)}; lens=${"%.3f".format(e.lensStrength)}; light=${"%.1f".format(e.lightAngle)}; ring1=${"%.3f".format(e.ring1Gain)}; ring2=${"%.3f".format(e.ring2Gain)}; ring3=${"%.3f".format(e.ring3Gain)}; ringCount=${e.ringCount}; facets=${e.facetDensity}; facetDepth=${"%.3f".format(e.facetDepth)}; sublayers=${e.ring1Visible},${e.ring2Visible},${e.ring3Visible},${e.coreVisible},${e.facetsVisible},${e.edgesVisible}; edgeWidth=${"%.2f".format(e.edgeWidth)}; edgeAlpha=${"%.3f".format(e.edgeAlpha)}; edgeContrast=${"%.3f".format(e.edgeContrast)}; edgeSoftness=${"%.3f".format(e.edgeSoftness)}; radialEdges=${"%.3f".format(e.radialEdgeGain)}; circularEdges=${"%.3f".format(e.circularEdgeGain)}; frameWidth=${"%.1f".format(e.frameWidth)}; cornerRadius=${"%.1f".format(e.cornerRadius)}; locked=${e.locked}")}}
}
