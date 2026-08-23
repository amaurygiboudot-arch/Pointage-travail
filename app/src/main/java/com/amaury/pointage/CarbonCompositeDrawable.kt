package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.Base64
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Carbon source image is never altered: texture -> transparent convex lens -> metal frame. */
class CarbonCompositeDrawable(context: Context) : Drawable() {
    companion object {
        private val instances = WeakHashMap<CarbonCompositeDrawable, Unit>()
        private var sharedLightAngle = -55f
        private var sharedNight = false
        @Synchronized fun updateGlobalLight(angle: Float, night: Boolean) {
            sharedLightAngle = ((angle % 360f) + 360f) % 360f
            sharedNight = night
            instances.keys.toList().forEach { it.applyCelestialLight(sharedLightAngle, sharedNight) }
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val clipPath = Path()
    private val frameBand = Path()
    private val innerPath = Path()
    private val fillBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_fill_b64)
    private val frameBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_frame_b64)?.let(::makeBlackTransparent)
    private var globalAlpha = 255
    private var lightAngle = sharedLightAngle
    private var nightLight = sharedNight

    init { synchronized(CarbonCompositeDrawable::class.java) { instances[this] = Unit } }
    private fun applyCelestialLight(angle: Float, night: Boolean) { lightAngle = angle; nightLight = night; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        dst.set(bounds)
        val radius = bounds.height() * .48f
        clipPath.reset(); clipPath.addRoundRect(dst, radius, radius, Path.Direction.CW)

        // Layer 1: original carbon bitmap, drawn untouched.
        canvas.save(); canvas.clipPath(clipPath)
        fillBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackCarbon(canvas, dst)
        // Layer 2: transparent optical lens only. It never rewrites the bitmap.
        drawConvexLensOverlay(canvas, dst)
        canvas.restore()

        // Layer 3: independent metal frame and its own celestial reflection.
        frameBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackFrame(canvas, dst, radius)
        drawMetalSpecularReflection(canvas, dst, radius)
    }

    /** Transparent convex-lens model: broad highlight + soft vignette + inner glass rim. */
    private fun drawConvexLensOverlay(canvas: Canvas, target: RectF) {
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
        val cx = target.centerX(); val cy = target.centerY()
        val diag = sqrt(target.width()*target.width() + target.height()*target.height())
        paint.style = Paint.Style.FILL; paint.alpha = globalAlpha; paint.colorFilter = null

        // Soft optical highlight, deliberately translucent so the carbon stays identical underneath.
        val tint = if (nightLight) Color.rgb(190,215,240) else Color.rgb(255,247,226)
        val lx = cx + dx * target.width() * .20f; val ly = cy + dy * target.height() * .25f
        paint.shader = RadialGradient(lx, ly, diag*.62f,
            intArrayOf(Color.argb(if(nightLight)30 else 48,Color.red(tint),Color.green(tint),Color.blue(tint)), Color.argb(if(nightLight)13 else 22,Color.red(tint),Color.green(tint),Color.blue(tint)), Color.TRANSPARENT),
            floatArrayOf(0f,.48f,1f), Shader.TileMode.CLAMP)
        canvas.drawRect(target, paint)

        // Lens curvature: centre stays clear; edges progressively darken like a convex transparent cover.
        paint.shader = RadialGradient(cx,cy,target.width()*.66f,
            intArrayOf(Color.TRANSPARENT,Color.argb(7,0,0,0),Color.argb(if(nightLight)48 else 62,0,0,0)),
            floatArrayOf(0f,.58f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(target,paint)

        // Very soft opposite shading. No hard line, no change to carbon pixels.
        val sx=cx-dx*target.width()*.34f; val sy=cy-dy*target.height()*.40f
        paint.shader=RadialGradient(sx,sy,diag*.58f,
            intArrayOf(Color.argb(if(nightLight)34 else 42,0,0,0),Color.argb(12,0,0,0),Color.TRANSPARENT),
            floatArrayOf(0f,.52f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(target,paint)

        // Glass-like inner rim, fully blended rather than a sharp highlight.
        val edge=(target.height()*.035f).coerceAtLeast(1.5f)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=edge
        paint.shader=LinearGradient(cx-dx*target.width()*.62f,cy-dy*target.height()*.62f,cx+dx*target.width()*.62f,cy+dy*target.height()*.62f,
            intArrayOf(Color.argb(38,0,0,0),Color.TRANSPARENT,Color.argb(if(nightLight)34 else 48,235,238,240)),
            floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        val inset=edge*1.2f
        canvas.drawRoundRect(RectF(target.left+inset,target.top+inset,target.right-inset,target.bottom-inset),target.height()*.43f,target.height()*.43f,paint)
        paint.shader=null; paint.style=Paint.Style.FILL
    }

    private fun drawMetalSpecularReflection(canvas: Canvas, target: RectF, radius: Float) {
        val band=(target.height()*.145f).coerceAtLeast(4f)
        val inner=RectF(target.left+band,target.top+band,target.right-band,target.bottom-band)
        if(inner.width()<=0f||inner.height()<=0f)return
        frameBand.reset(); frameBand.addRoundRect(target,radius,radius,Path.Direction.CW)
        innerPath.reset(); innerPath.addRoundRect(inner,(radius-band).coerceAtLeast(1f),(radius-band).coerceAtLeast(1f),Path.Direction.CW)
        frameBand.op(innerPath,Path.Op.DIFFERENCE)
        val rad=Math.toRadians(lightAngle.toDouble()); val dx=cos(rad).toFloat(); val dy=sin(rad).toFloat()
        val cx=target.centerX(); val cy=target.centerY(); val half=sqrt(target.width()*target.width()+target.height()*target.height())*.66f
        val cool=if(nightLight)Color.rgb(188,220,255) else Color.rgb(255,250,232)
        // Wider stops than before: metal reflection is strongly visible but smoothly blended.
        val peak=if(nightLight)145 else 205; val shoulder=if(nightLight)55 else 105
        paint.style=Paint.Style.FILL; paint.alpha=globalAlpha; paint.colorFilter=null
        paint.shader=LinearGradient(cx-dx*half,cy-dy*half,cx+dx*half,cy+dy*half,
            intArrayOf(Color.argb(48,5,7,9),Color.argb(12,20,24,28),Color.argb(shoulder,Color.red(cool),Color.green(cool),Color.blue(cool)),Color.argb(peak,255,255,255),Color.argb(shoulder,Color.red(cool),Color.green(cool),Color.blue(cool)),Color.argb(18,18,22,26),Color.argb(58,0,0,0)),
            floatArrayOf(0f,.25f,.39f,.50f,.61f,.75f,1f),Shader.TileMode.CLAMP)
        canvas.save(); canvas.clipPath(frameBand); canvas.drawRect(target,paint)
        val hotX=cx+dx*target.width()*.42f; val hotY=cy+dy*target.height()*.42f
        paint.shader=RadialGradient(hotX,hotY,(target.height()*if(nightLight).38f else .48f).coerceAtLeast(9f),
            intArrayOf(Color.argb(if(nightLight)100 else 145,255,255,255),Color.argb(if(nightLight)35 else 65,Color.red(cool),Color.green(cool),Color.blue(cool)),Color.TRANSPARENT),
            floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(target,paint); canvas.restore(); paint.shader=null
    }

    private fun drawCenterCrop(canvas:Canvas,bitmap:Bitmap,target:RectF){
        val bw=bitmap.width.toFloat();val bh=bitmap.height.toFloat();val tw=target.width();val th=target.height();if(bw<=0f||bh<=0f||tw<=0f||th<=0f)return
        val srcAspect=bw/bh;val dstAspect=tw/th
        if(srcAspect>dstAspect){val wanted=bh*dstAspect;val left=((bw-wanted)/2f).toInt().coerceAtLeast(0);src.set(left,0,(left+wanted.toInt()).coerceAtMost(bitmap.width),bitmap.height)}else{val wanted=bw/dstAspect;val top=((bh-wanted)/2f).toInt().coerceAtLeast(0);src.set(0,top,bitmap.width,(top+wanted.toInt()).coerceAtMost(bitmap.height))}
        paint.alpha=globalAlpha;paint.colorFilter=null;paint.shader=null;canvas.drawBitmap(bitmap,src,target,paint)
    }
    private fun decodeRawBase64(context:Context,resId:Int):Bitmap?=runCatching{val encoded=context.resources.openRawResource(resId).bufferedReader().use{it.readText()}.trim();val bytes=Base64.decode(encoded,Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)}.getOrNull()
    private fun makeBlackTransparent(source:Bitmap):Bitmap{val w=source.width;val h=source.height;val pixels=IntArray(w*h);source.getPixels(pixels,0,w,0,0,w,h);for(i in pixels.indices){val c=pixels[i];val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);val lum=max(r,max(g,b));val a=when{lum<=3->0;lum<=12->((lum-3)*14).coerceIn(0,126);else->(105+(lum-12)*3).coerceIn(105,255)};pixels[i]=Color.argb((a*globalAlpha)/255,r,g,b)};return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)}
    private fun drawFallbackCarbon(canvas:Canvas,target:RectF){paint.alpha=globalAlpha;paint.style=Paint.Style.FILL;paint.shader=null;paint.color=Color.rgb(13,15,17);canvas.drawRoundRect(target,target.height()*.48f,target.height()*.48f,paint)}
    private fun drawFallbackFrame(canvas:Canvas,target:RectF,radius:Float){val inset=(target.height()*.055f).coerceAtLeast(2f);paint.style=Paint.Style.STROKE;paint.strokeWidth=inset;paint.alpha=globalAlpha;paint.shader=LinearGradient(target.left,target.top,target.right,target.bottom,intArrayOf(Color.WHITE,Color.rgb(75,80,86),Color.rgb(235,238,241),Color.rgb(55,59,64)),null,Shader.TileMode.CLAMP);canvas.drawRoundRect(RectF(target.left+inset/2f,target.top+inset/2f,target.right-inset/2f,target.bottom-inset/2f),radius-inset/2f,radius-inset/2f,paint);paint.shader=null;paint.style=Paint.Style.FILL}
    override fun setAlpha(alpha:Int){globalAlpha=alpha.coerceIn(0,255);invalidateSelf()}
    override fun setColorFilter(colorFilter:ColorFilter?){invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity():Int=PixelFormat.TRANSLUCENT
}
