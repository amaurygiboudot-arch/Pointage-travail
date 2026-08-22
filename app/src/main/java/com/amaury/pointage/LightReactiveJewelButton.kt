package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Base64
import android.view.MotionEvent
import android.widget.Button
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

open class LightReactiveJewelButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.buttonStyle) : Button(context, attrs, defStyleAttr) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isFilterBitmap=true; isDither=false }
    private val framePaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val pauseGlyphPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val celestialLightPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val celestialRimPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var backgroundLayer:Bitmap?=null
    private var sharpenedLayer:Bitmap?=null
    private var loadedForId=-1
    private var innerPressScale=1f
    protected var jewelLightAngle=-55f
    protected var jewelAccent=Color.parseColor("#D6A84B")
    protected var jewelAccentLight=Color.parseColor("#F3D58A")
    private var nightLight=false

    init { background=null; stateListAnimator=null; setPadding(0,0,0,0); isAllCaps=false }
    override fun drawableStateChanged(){ stateListAnimator=null; super.drawableStateChanged(); invalidate() }
    override fun onTouchEvent(e:MotionEvent):Boolean { when(e.actionMasked){MotionEvent.ACTION_DOWN->{innerPressScale=.93f;invalidate()};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{innerPressScale=1f;invalidate()}};return super.onTouchEvent(e) }
    override fun performClick():Boolean { val r=super.performClick();animate().cancel();scaleX=1f;scaleY=1f;innerPressScale=1f;invalidate();return r }
    open fun setLightAngle(a:Float){val n=((a%360)+360)%360;if(kotlin.math.abs(shortestDelta(jewelLightAngle,n))<.35f)return;jewelLightAngle=n;invalidate()}
    open fun setJewelAccent(c:Int,l:Int){jewelAccent=c;jewelAccentLight=l;invalidate()}
    open fun setNightLight(e:Boolean){if(nightLight==e)return;nightLight=e;invalidate()}

    private fun decodeRawBitmap(r:Int):Bitmap?=runCatching{val s=resources.openRawResource(r).bufferedReader().use{it.readText()};val b=Base64.decode(s.trim(),Base64.DEFAULT);BitmapFactory.decodeByteArray(b,0,b.size)}.getOrNull()
    private fun ensureLayers(){if(loadedForId==id&&backgroundLayer!=null)return;loadedForId=id;backgroundLayer=when(id){R.id.entryButton->decodeRawBitmap(R.raw.hp_button_bg_green_b64);R.id.pauseButton->decodeRawBitmap(R.raw.hp_button_bg_orange_b64);R.id.exitButton->decodeRawBitmap(R.raw.hp_button_bg_red_b64);else->null};sharpenedLayer=backgroundLayer?.let{sharpenOriginal(it)}}

    /** Accentue uniquement les transitions déjà présentes dans le PNG (unsharp mask 3x3). */
    private fun sharpenOriginal(src:Bitmap):Bitmap{
        val w=src.width;val h=src.height;val input=IntArray(w*h);val out=IntArray(w*h);src.getPixels(input,0,w,0,0,w,h)
        for(y in 0 until h) for(x in 0 until w){val i=y*w+x;val c=input[i];val a=Color.alpha(c);if(a==0){out[i]=c;continue};var sr=0;var sg=0;var sb=0;var n=0
            for(dy in -1..1)for(dx in -1..1){val xx=(x+dx).coerceIn(0,w-1);val yy=(y+dy).coerceIn(0,h-1);val q=input[yy*w+xx];sr+=Color.red(q);sg+=Color.green(q);sb+=Color.blue(q);n++}
            val br=sr/n;val bg=sg/n;val bb=sb/n;val amount=1.35f
            val r=(Color.red(c)+(Color.red(c)-br)*amount).toInt().coerceIn(0,255);val g=(Color.green(c)+(Color.green(c)-bg)*amount).toInt().coerceIn(0,255);val b=(Color.blue(c)+(Color.blue(c)-bb)*amount).toInt().coerceIn(0,255);out[i]=Color.argb(a,r,g,b)}
        return Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas:Canvas){
        ensureLayers()
        val w=width.toFloat();val h=height.toFloat();if(w<=0||h<=0)return
        val save=canvas.save();canvas.scale(.5f,.5f)
        val rw=w*2;val rh=h*2;val radius=min(rw,rh)*.5f;val cx=rw*.5f;val cy=rh*.5f
        drawFrame(canvas,cx,cy,radius)
        val ins=canvas.save();canvas.scale(innerPressScale,innerPressScale,cx,cy)
        val jr=radius*.885f;val dst=RectF(cx-jr,cy-jr,cx+jr,cy+jr)
        sharpenedLayer?.let{
            val contrast=1.18f;val o=-128*contrast+128
            bitmapPaint.colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(contrast,0f,0f,0f,o,0f,contrast,0f,0f,o,0f,0f,contrast,0f,o,0f,0f,0f,1f,0f)))
            canvas.drawBitmap(it,null,dst,bitmapPaint);bitmapPaint.colorFilter=null
        }
        drawCelestialLighting(canvas,cx,cy,jr)
        when(id){R.id.entryButton->drawIcon(canvas,cx,cy,jr,false);R.id.exitButton->drawIcon(canvas,cx,cy,jr,true);R.id.pauseButton->drawPause(canvas,cx,cy,jr)}
        canvas.restoreToCount(ins);canvas.restoreToCount(save)
        super.onDraw(canvas)
    }

    /**
     * Éclairage réellement piloté par LightDirectionController.
     * Le point lumineux se déplace sur la matière suivant la position apparente
     * du Soleil le jour et de la Lune la nuit, avec une teinte plus froide la nuit.
     */
    private fun drawCelestialLighting(c:Canvas,cx:Float,cy:Float,r:Float){
        val rad=Math.toRadians(jewelLightAngle.toDouble())
        val ux=cos(rad).toFloat();val uy=sin(rad).toFloat()
        val hx=cx+ux*r*.48f;val hy=cy+uy*r*.48f
        val lightRgb=if(nightLight) intArrayOf(190,220,255) else intArrayOf(255,239,184)
        val coreAlpha=if(nightLight) 118 else 158
        val midAlpha=if(nightLight) 62 else 82

        celestialLightPaint.shader=RadialGradient(
            hx,hy,r*1.05f,
            intArrayOf(
                Color.argb(coreAlpha,lightRgb[0],lightRgb[1],lightRgb[2]),
                Color.argb(midAlpha,lightRgb[0],lightRgb[1],lightRgb[2]),
                Color.argb(8,lightRgb[0],lightRgb[1],lightRgb[2]),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f,.30f,.68f,1f),Shader.TileMode.CLAMP
        )
        c.save()
        c.clipPath(Path().apply{addCircle(cx,cy,r,Path.Direction.CW)})
        c.drawCircle(cx,cy,r,celestialLightPaint)

        // Ombre opposée : donne le relief et rend le déplacement de la lumière évident.
        val sx=cx-ux*r*.62f;val sy=cy-uy*r*.62f
        celestialLightPaint.shader=RadialGradient(
            sx,sy,r*.92f,
            intArrayOf(Color.argb(if(nightLight)72 else 92,0,0,0),Color.argb(28,0,0,0),Color.TRANSPARENT),
            floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP
        )
        c.drawCircle(cx,cy,r,celestialLightPaint)
        c.restore()
        celestialLightPaint.shader=null

        // Petit reflet de bord orienté vers l'astre, comme une arête polie.
        celestialRimPaint.style=Paint.Style.STROKE
        celestialRimPaint.strokeCap=Paint.Cap.ROUND
        celestialRimPaint.strokeWidth=r*.035f
        celestialRimPaint.color=Color.argb(if(nightLight)115 else 150,lightRgb[0],lightRgb[1],lightRgb[2])
        val start=jewelLightAngle-27f
        c.drawArc(RectF(cx-r*.86f,cy-r*.86f,cx+r*.86f,cy+r*.86f),start,54f,false,celestialRimPaint)
    }

    private fun drawFrame(c:Canvas,cx:Float,cy:Float,r:Float){framePaint.style=Paint.Style.STROKE;framePaint.strokeCap=Paint.Cap.SQUARE;framePaint.strokeWidth=r*.09f;framePaint.shader=SweepGradient(cx,cy,intArrayOf(Color.rgb(82,42,2),Color.rgb(255,226,116),Color.rgb(160,88,6),Color.rgb(255,247,184),Color.rgb(91,47,3),Color.rgb(255,218,85),Color.rgb(82,42,2)),null);c.drawCircle(cx,cy,r*.95f,framePaint);framePaint.shader=null;framePaint.strokeWidth=r*.082f;framePaint.shader=SweepGradient(cx,cy,intArrayOf(Color.rgb(0,8,35),Color.rgb(5,91,220),Color.rgb(0,15,62),Color.rgb(24,126,255),Color.rgb(0,13,51),Color.rgb(4,80,202),Color.rgb(0,8,35)),null);c.drawCircle(cx,cy,r*.915f,framePaint);framePaint.shader=null;framePaint.strokeWidth=r*.027f;framePaint.color=Color.rgb(255,202,62);c.drawCircle(cx,cy,r*.878f,framePaint);framePaint.strokeWidth=r*.009f;framePaint.color=Color.rgb(255,248,190);c.drawCircle(cx,cy,r*.893f,framePaint)}
    private fun drawIcon(c:Canvas,cx:Float,cy:Float,r:Float,mirror:Boolean){val ir=r*.285f;iconPaint.style=Paint.Style.FILL;iconPaint.shader=RadialGradient(cx-ir*.22f,cy-ir*.25f,ir*1.3f,intArrayOf(Color.rgb(24,112,214),Color.rgb(2,35,104),Color.rgb(0,7,28)),floatArrayOf(0f,.58f,1f),Shader.TileMode.CLAMP);c.drawCircle(cx,cy,ir,iconPaint);iconPaint.shader=null;detailPaint.style=Paint.Style.STROKE;detailPaint.strokeCap=Paint.Cap.SQUARE;detailPaint.color=Color.rgb(255,205,70);detailPaint.strokeWidth=r*.032f;c.drawCircle(cx,cy,ir*.96f,detailPaint);detailPaint.color=Color.rgb(255,242,168);detailPaint.strokeWidth=r*.01f;c.drawCircle(cx,cy,ir*.78f,detailPaint);c.save();if(mirror)c.scale(-1f,1f,cx,cy);val p=Path().apply{val x0=cx-ir*.5f;val x1=cx+ir*.18f;val tip=cx+ir*.58f;val half=ir*.18f;val head=ir*.33f;moveTo(x0,cy-half);lineTo(x1,cy-half);lineTo(x1,cy-head);lineTo(tip,cy);lineTo(x1,cy+head);lineTo(x1,cy+half);lineTo(x0,cy+half);close()};iconPaint.color=Color.rgb(244,180,43);c.drawPath(p,iconPaint);detailPaint.strokeJoin=Paint.Join.MITER;detailPaint.strokeWidth=r*.018f;detailPaint.color=Color.rgb(255,244,174);c.drawPath(p,detailPaint);c.restore()}
    private fun drawPause(c:Canvas,cx:Float,cy:Float,r:Float){val bw=r*.145f;val bh=r*.54f;val gap=r*.105f;val top=cy-bh*.5f;val bottom=cy+bh*.5f;val corner=bw*.16f;detailPaint.style=Paint.Style.STROKE;detailPaint.strokeWidth=r*.024f;detailPaint.color=Color.rgb(88,45,4);c.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,detailPaint);c.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,detailPaint);pauseGlyphPaint.style=Paint.Style.FILL;pauseGlyphPaint.color=Color.rgb(248,183,48);c.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,pauseGlyphPaint);c.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,pauseGlyphPaint);detailPaint.strokeWidth=r*.009f;detailPaint.color=Color.rgb(255,244,176);c.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,detailPaint);c.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,detailPaint)}
    protected fun shortestDelta(a:Float,b:Float):Float=((b-a+540)%360)-180
}
