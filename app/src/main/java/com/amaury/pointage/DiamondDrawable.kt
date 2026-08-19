package com.amaury.pointage

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.*

/** Bouton cristal taillé réglable depuis le laboratoire Diamant. */
class DiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private var tuning: DiamondTuning = DiamondTuning()
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(angle: Float) {
        lightAngle = ((angle % 360f) + 360f) % 360f
        invalidateSelf()
    }

    fun setTuning(value: DiamondTuning) {
        tuning = value
        invalidateSelf()
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val n = state.any { it == android.R.attr.state_pressed || it == android.R.attr.state_focused }
        if (n == pressed) return false
        pressed = n
        invalidateSelf()
        return true
    }

    override fun draw(c: Canvas) {
        if (bounds.isEmpty) return
        r.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())

        val h = r.height()
        val facetScale = 0.18f + tuning.facetDepth * 0.18f
        val cut = min(h * facetScale, (12f + tuning.facetDepth * 10f) * density)
        val outer = cutPath(r, cut)

        c.save()
        c.clipPath(outer)

        val alphaScale = (1f - tuning.transparency * 0.72f).coerceIn(.18f, 1f)
        val blue = tuning.iceBlue.coerceIn(0f, 1f)
        val baseR = lerp(42, 18, blue)
        val baseG = lerp(54, 43, blue)
        val baseB = lerp(69, 84, blue)

        fill.shader = LinearGradient(
            r.left, r.top, r.left, r.bottom,
            intArrayOf(
                Color.argb((190 * alphaScale).toInt(), lerp(baseR, 72, blue), lerp(baseG, 105, blue), lerp(baseB, 132, blue)),
                Color.argb((145 * alphaScale).toInt(), baseR, baseG, baseB),
                Color.argb((185 * alphaScale).toInt(), 8, lerp(20, 31, blue), lerp(36, 58, blue))
            ),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(r, fill)
        fill.shader = null

        val ix = r.left + cut * 1.35f
        val ir = r.right - cut * 1.35f
        val tableInset = h * (0.18f + tuning.facetDepth * .10f)
        val it = r.top + tableInset
        val ib = r.bottom - tableInset
        val w = r.width()

        val facets = arrayOf(
            facet(r.left+cut,r.top, r.left+w*.19f,r.top, ix,it, r.left,r.top+cut) to 215f,
            facet(r.left+w*.19f,r.top, r.left+w*.39f,r.top, r.left+w*.34f,it,ix,it) to 250f,
            facet(r.left+w*.39f,r.top, r.left+w*.61f,r.top, r.left+w*.66f,it,r.left+w*.34f,it) to 285f,
            facet(r.left+w*.61f,r.top, r.right-cut,r.top,ir,it,r.left+w*.66f,it) to 320f,
            facet(r.right-cut,r.top,r.right,r.top+cut,r.right,r.bottom-cut,ir,ib,ir,it) to 355f,
            facet(r.right,r.bottom-cut,r.right-cut,r.bottom,r.left+w*.81f,r.bottom,ir,ib) to 28f,
            facet(r.left+w*.81f,r.bottom,r.left+w*.61f,r.bottom,r.left+w*.66f,ib,ir,ib) to 63f,
            facet(r.left+w*.61f,r.bottom,r.left+w*.39f,r.bottom,r.left+w*.34f,ib,r.left+w*.66f,ib) to 95f,
            facet(r.left+w*.39f,r.bottom,r.left+w*.19f,r.bottom,ix,ib,r.left+w*.34f,ib) to 128f,
            facet(r.left+w*.19f,r.bottom,r.left+cut,r.bottom,r.left,r.bottom-cut,r.left,r.top+cut,ix,it,ix,ib) to 165f
        )

        facets.forEachIndexed { i, (path, normal) ->
            val f = facing(normal)
            val base = when (i % 4) {
                0 -> intArrayOf(205,235,255)
                1 -> intArrayOf(255,255,255)
                2 -> intArrayOf(154,203,242)
                else -> intArrayOf(220,230,255)
            }
            val energy = .35f + tuning.facetDepth * .95f
            val alpha = ((22 + 170 * f * energy) * alphaScale.coerceAtLeast(.48f)).toInt().coerceIn(18, 220)
            fill.shader = LinearGradient(
                r.left,r.top,r.right,r.bottom,
                Color.argb(alpha,base[0],base[1],base[2]),
                Color.argb((alpha*.12f).toInt(),65,112,166),
                Shader.TileMode.CLAMP
            )
            c.drawPath(path, fill)
            fill.shader = null
        }

        val table = RectF(ix,it,ir,ib)
        val tableAlpha = (50 * alphaScale).toInt().coerceAtLeast(8)
        fill.shader = LinearGradient(
            table.left,table.top,table.right,table.bottom,
            intArrayOf(
                Color.argb(tableAlpha,225,244,255),
                Color.argb((18*alphaScale).toInt(),96,143,190),
                Color.argb((42*alphaScale).toInt(),214,239,255)
            ),
            floatArrayOf(0f,.55f,1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(table,h*.10f,h*.10f,fill)
        fill.shader = null

        val rad = Math.toRadians(lightAngle.toDouble())
        val cx = r.centerX()
        val cy = r.centerY()
        val dx = (cos(rad)*w*.62).toFloat()
        val dy = (sin(rad)*h*3.2).toFloat()
        val ref = tuning.refraction.coerceIn(0f,1f)
        fill.shader = LinearGradient(
            cx-dx,cy-dy,cx+dx,cy+dy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(((if(pressed)35 else 78)*ref).toInt(),160,218,255),
                Color.argb(((if(pressed)50 else 120)*ref).toInt(),255,255,255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f,.43f,.51f,1f), Shader.TileMode.CLAMP
        )
        c.drawRect(r,fill)
        fill.shader = null
        c.restore()

        val bevel = tuning.bevel.coerceIn(0f,1f)
        line.strokeWidth = (.65f + bevel * 1.15f) * density
        line.shader = LinearGradient(
            r.left,r.top,r.right,r.bottom,
            intArrayOf(Color.rgb(151,211,255),Color.WHITE,Color.rgb(113,170,224),Color.WHITE,Color.rgb(166,220,255)),
            null, Shader.TileMode.CLAMP
        )
        line.alpha = ((if(pressed)180 else 245) * (.55f + bevel*.45f)).toInt().coerceIn(70,255)
        c.drawPath(outer,line)
        line.shader = null

        val inset = (1.6f + bevel * 1.7f) * density
        val inner = RectF(r.left+inset,r.top+inset,r.right-inset,r.bottom-inset)
        line.strokeWidth = (.35f + bevel*.65f) * density
        line.color = Color.argb((110 + 100*bevel).toInt().coerceIn(0,255),225,246,255)
        line.alpha = 255
        c.drawPath(cutPath(inner,(cut-inset).coerceAtLeast(3f*density)),line)

        val sparkle = tuning.sparkle.coerceIn(0f,1f)
        val glints = arrayOf(
            floatArrayOf(.08f,.20f,210f), floatArrayOf(.25f,.07f,255f),
            floatArrayOf(.55f,.06f,292f), floatArrayOf(.82f,.08f,325f),
            floatArrayOf(.96f,.34f,0f), floatArrayOf(.88f,.88f,42f),
            floatArrayOf(.54f,.94f,90f), floatArrayOf(.15f,.88f,145f)
        )
        glints.forEach { g ->
            val f = facing(g[2])
            val threshold = .88f - sparkle*.28f
            if (f < threshold) return@forEach
            val x = r.left+w*g[0]
            val y = r.top+h*g[1]
            val rr = (1.2f + (2.2f + 4.2f*sparkle)*f) * density
            val a = (245*f*sparkle).toInt().coerceIn(0,245)
            fill.shader = RadialGradient(x,y,rr,Color.argb(a,255,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            c.drawCircle(x,y,rr,fill)
            fill.shader = null
        }
    }

    private fun facing(normal: Float): Float {
        val d = ((lightAngle-normal+540f)%360f)-180f
        return ((cos(Math.toRadians(d.toDouble()))+1.0)*.5).toFloat()
    }

    private fun cutPath(x:RectF,cut:Float)=Path().apply{
        moveTo(x.left+cut,x.top);lineTo(x.right-cut,x.top);lineTo(x.right,x.top+cut)
        lineTo(x.right,x.bottom-cut);lineTo(x.right-cut,x.bottom);lineTo(x.left+cut,x.bottom)
        lineTo(x.left,x.bottom-cut);lineTo(x.left,x.top+cut);close()
    }

    private fun facet(vararg v:Float)=Path().apply{
        moveTo(v[0],v[1]);var i=2;while(i<v.size){lineTo(v[i],v[i+1]);i+=2};close()
    }

    private fun lerp(a:Int,b:Int,t:Float)=(a+(b-a)*t.coerceIn(0f,1f)).toInt()
    override fun setAlpha(alpha:Int){fill.alpha=alpha;line.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(cf:ColorFilter?){fill.colorFilter=cf;line.colorFilter=cf;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
