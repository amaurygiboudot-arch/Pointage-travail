package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.util.Base64
import android.view.MotionEvent
import android.widget.Button
import kotlin.math.min

open class LightReactiveJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isFilterBitmap = true; isDither = false }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false }
    private val pauseGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false }
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false; style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER; strokeCap = Paint.Cap.SQUARE }
    private var backgroundLayer: Bitmap? = null
    private var loadedForId: Int = ViewIdNone
    private var innerPressScale = 1f

    protected var jewelLightAngle = -55f
    protected var jewelAccent = Color.parseColor("#D6A84B")
    protected var jewelAccentLight = Color.parseColor("#F3D58A")
    private var nightLight = false

    init {
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        isAllCaps = false
    }

    override fun drawableStateChanged() {
        stateListAnimator = null
        super.drawableStateChanged()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                innerPressScale = 0.93f
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                innerPressScale = 1f
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * MainActivity applique encore une animation générique de scale au clic.
     * On l'annule ici après l'exécution du listener afin que le cadre extérieur
     * reste parfaitement fixe : seul le cristal intérieur réagit au toucher.
     */
    override fun performClick(): Boolean {
        val result = super.performClick()
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        innerPressScale = 1f
        invalidate()
        return result
    }

    open fun setLightAngle(angle: Float) { val n=((angle%360f)+360f)%360f; if(kotlin.math.abs(shortestDelta(jewelLightAngle,n))<0.6f)return; jewelLightAngle=n; invalidate() }
    open fun setJewelAccent(color:Int, lightColor:Int){ jewelAccent=color; jewelAccentLight=lightColor; invalidate() }
    open fun setNightLight(enabled:Boolean){ if(nightLight==enabled)return; nightLight=enabled; invalidate() }

    private fun decodeRawBitmap(resId:Int):Bitmap?=runCatching{ val e=resources.openRawResource(resId).bufferedReader().use{it.readText()}; val b=Base64.decode(e.trim(),Base64.DEFAULT); BitmapFactory.decodeByteArray(b,0,b.size)}.getOrNull()
    private fun ensureLayers(){ if(loadedForId==id&&backgroundLayer!=null)return; loadedForId=id; backgroundLayer=when(id){R.id.entryButton->decodeRawBitmap(R.raw.hp_button_bg_green_b64);R.id.pauseButton->decodeRawBitmap(R.raw.hp_button_bg_orange_b64);R.id.exitButton->decodeRawBitmap(R.raw.hp_button_bg_red_b64);else->null} }

    override fun onDraw(canvas:Canvas){
        ensureLayers(); val w=width.toFloat(); val h=height.toFloat(); if(w<=0f||h<=0f)return
        val save=canvas.save(); canvas.scale(0.5f,0.5f); val rw=w*2f; val rh=h*2f; val radius=min(rw,rh)*0.50f; val cx=rw*.5f; val cy=rh*.5f

        // Le cadre reste toujours fixe.
        drawCelestialFrame(canvas,cx,cy,radius)

        // Seul le contenu intérieur (gemme + facettes + pictogramme) s'enfonce.
        val innerSave = canvas.save()
        canvas.scale(innerPressScale, innerPressScale, cx, cy)
        val jewelRadius=radius*.885f; val dst=RectF(cx-jewelRadius,cy-jewelRadius,cx+jewelRadius,cy+jewelRadius)
        backgroundLayer?.let{bitmap->
            val contrast=1.38f; val offset=-128f*contrast+128f+3f
            bitmapPaint.colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(contrast,0f,0f,0f,offset, 0f,contrast,0f,0f,offset, 0f,0f,contrast,0f,offset, 0f,0f,0f,1f,0f)))
            bitmapPaint.alpha=255; canvas.drawBitmap(bitmap,null,dst,bitmapPaint); bitmapPaint.colorFilter=null
        }
        drawCrispFacetEdges(canvas,cx,cy,jewelRadius)
        when(id){R.id.entryButton->drawCelestialIcon(canvas,cx,cy,jewelRadius,false);R.id.exitButton->drawCelestialIcon(canvas,cx,cy,jewelRadius,true);R.id.pauseButton->drawPauseGlyph(canvas,cx,cy,jewelRadius)}
        canvas.restoreToCount(innerSave)
        canvas.restoreToCount(save)
        super.onDraw(canvas)
    }

    private fun drawCrispFacetEdges(canvas:Canvas,cx:Float,cy:Float,r:Float){
        val clip=canvas.save(); val p=Path().apply{addCircle(cx,cy,r*.965f,Path.Direction.CW)}; canvas.clipPath(p)
        facetPaint.strokeWidth=r*.008f; facetPaint.color=Color.argb(if(nightLight)105 else 145,255,255,255)
        val rings=floatArrayOf(.34f,.56f,.76f,.91f); val pts=12
        for(ri in rings.indices){ val rr=r*rings[ri]; val off=if(ri%2==0)0.0 else Math.PI/12.0; val path=Path(); for(i in 0..pts){ val a=(Math.PI*2.0*(i%pts)/pts)+off; val x=cx+(kotlin.math.cos(a)*rr).toFloat(); val y=cy+(kotlin.math.sin(a)*rr).toFloat(); if(i==0)path.moveTo(x,y) else path.lineTo(x,y)}; path.close(); canvas.drawPath(path,facetPaint) }
        facetPaint.strokeWidth=r*.006f; facetPaint.color=Color.argb(if(nightLight)75 else 110,255,244,205)
        for(i in 0 until pts){ val a=Math.PI*2.0*i/pts; val a2=a+Math.PI/12.0; canvas.drawLine(cx+(kotlin.math.cos(a)*r*.34).toFloat(),cy+(kotlin.math.sin(a)*r*.34).toFloat(),cx+(kotlin.math.cos(a2)*r*.91).toFloat(),cy+(kotlin.math.sin(a2)*r*.91).toFloat(),facetPaint) }
        facetPaint.strokeWidth=r*.018f; facetPaint.color=Color.argb(210,255,238,178); canvas.drawCircle(cx,cy,r*.955f,facetPaint)
        facetPaint.strokeWidth=r*.006f; facetPaint.color=Color.argb(235,255,255,230); canvas.drawCircle(cx,cy,r*.935f,facetPaint)
        canvas.restoreToCount(clip)
    }

    private fun drawCelestialFrame(canvas:Canvas,cx:Float,cy:Float,radius:Float){
        val alpha=255; framePaint.style=Paint.Style.STROKE; framePaint.strokeCap=Paint.Cap.SQUARE
        framePaint.strokeWidth=radius*.09f; framePaint.shader=SweepGradient(cx,cy,intArrayOf(Color.argb(alpha,82,42,2),Color.argb(alpha,255,226,116),Color.argb(alpha,160,88,6),Color.argb(alpha,255,247,184),Color.argb(alpha,91,47,3),Color.argb(alpha,255,218,85),Color.argb(alpha,82,42,2)),null); canvas.drawCircle(cx,cy,radius*.95f,framePaint); framePaint.shader=null
        framePaint.strokeWidth=radius*.082f; framePaint.shader=SweepGradient(cx,cy,intArrayOf(Color.argb(alpha,0,8,35),Color.argb(alpha,5,91,220),Color.argb(alpha,0,15,62),Color.argb(alpha,24,126,255),Color.argb(alpha,0,13,51),Color.argb(alpha,4,80,202),Color.argb(alpha,0,8,35)),null); canvas.drawCircle(cx,cy,radius*.915f,framePaint); framePaint.shader=null
        framePaint.strokeWidth=radius*.027f; framePaint.color=Color.argb(alpha,255,202,62); canvas.drawCircle(cx,cy,radius*.878f,framePaint); framePaint.strokeWidth=radius*.009f; framePaint.color=Color.argb(alpha,255,248,190); canvas.drawCircle(cx,cy,radius*.893f,framePaint)
    }

    private fun drawCelestialIcon(canvas:Canvas,cx:Float,cy:Float,radius:Float,mirror:Boolean){
        val ir=radius*.285f; val alpha=255; iconPaint.style=Paint.Style.FILL; iconPaint.shader=RadialGradient(cx-ir*.22f,cy-ir*.25f,ir*1.3f,intArrayOf(Color.argb(alpha,24,112,214),Color.argb(alpha,2,35,104),Color.argb(alpha,0,7,28)),floatArrayOf(0f,.58f,1f),Shader.TileMode.CLAMP); canvas.drawCircle(cx,cy,ir,iconPaint); iconPaint.shader=null
        detailPaint.style=Paint.Style.STROKE; detailPaint.strokeCap=Paint.Cap.SQUARE; detailPaint.color=Color.argb(alpha,255,205,70); detailPaint.strokeWidth=radius*.032f; canvas.drawCircle(cx,cy,ir*.96f,detailPaint); detailPaint.color=Color.argb(alpha,255,242,168); detailPaint.strokeWidth=radius*.01f; canvas.drawCircle(cx,cy,ir*.78f,detailPaint)
        canvas.save(); if(mirror)canvas.scale(-1f,1f,cx,cy); val arrow=Path().apply{val x0=cx-ir*.5f;val x1=cx+ir*.18f;val tip=cx+ir*.58f;val half=ir*.18f;val head=ir*.33f;moveTo(x0,cy-half);lineTo(x1,cy-half);lineTo(x1,cy-head);lineTo(tip,cy);lineTo(x1,cy+head);lineTo(x1,cy+half);lineTo(x0,cy+half);close()}; iconPaint.style=Paint.Style.FILL;iconPaint.color=Color.argb(alpha,244,180,43);canvas.drawPath(arrow,iconPaint);detailPaint.style=Paint.Style.STROKE;detailPaint.strokeJoin=Paint.Join.MITER;detailPaint.strokeWidth=radius*.018f;detailPaint.color=Color.argb(alpha,255,244,174);canvas.drawPath(arrow,detailPaint);canvas.restore()
    }

    private fun drawPauseGlyph(canvas:Canvas,cx:Float,cy:Float,radius:Float){
        val alpha=255;val bw=radius*.145f;val bh=radius*.54f;val gap=radius*.105f;val top=cy-bh*.5f;val bottom=cy+bh*.5f;val corner=bw*.16f
        detailPaint.style=Paint.Style.STROKE;detailPaint.strokeWidth=radius*.024f;detailPaint.color=Color.argb(alpha,88,45,4);canvas.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,detailPaint);canvas.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,detailPaint)
        pauseGlyphPaint.style=Paint.Style.FILL;pauseGlyphPaint.color=Color.argb(alpha,248,183,48);canvas.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,pauseGlyphPaint);canvas.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,pauseGlyphPaint)
        detailPaint.style=Paint.Style.STROKE;detailPaint.strokeWidth=radius*.009f;detailPaint.color=Color.argb(alpha,255,244,176);canvas.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,detailPaint);canvas.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,detailPaint)
    }

    protected fun shortestDelta(a:Float,b:Float):Float=((b-a+540f)%360f)-180f
    private companion object{const val ViewIdNone=-1}
}
