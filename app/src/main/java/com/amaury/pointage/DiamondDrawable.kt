package com.amaury.pointage

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.*

/** Realistic cut-crystal button: thick crown, calm transparent table and moving refraction. */
class DiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val tint: Int = Color.parseColor("#DDF6FF")
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(angle: Float) { lightAngle = ((angle % 360f) + 360f) % 360f; invalidateSelf() }
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val n = state.any { it == android.R.attr.state_pressed || it == android.R.attr.state_focused }
        if (n == pressed) return false
        pressed = n; invalidateSelf(); return true
    }

    override fun draw(c: Canvas) {
        if (bounds.isEmpty) return
        r.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        val h = r.height(); val cut = min(h * .30f, 18f * density)
        val outer = cutPath(r, cut)
        c.save(); c.clipPath(outer)

        // Transparent blue-grey crystal mass, intentionally not opaque navy.
        fill.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
            intArrayOf(Color.argb(185, 44, 65, 91), Color.argb(145, 18, 34, 55), Color.argb(185, 8, 22, 42)),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r, fill); fill.shader = null

        val ix = r.left + cut * 1.35f; val ir = r.right - cut * 1.35f
        val it = r.top + h * .25f; val ib = r.bottom - h * .25f
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
            val base = when(i % 4) { 0 -> intArrayOf(205,235,255); 1 -> intArrayOf(255,255,255); 2 -> intArrayOf(154,203,242); else -> intArrayOf(220,230,255) }
            val alpha = (30 + 175*f).toInt().coerceIn(25,210)
            fill.shader = LinearGradient(r.left,r.top,r.right,r.bottom,
                Color.argb(alpha,base[0],base[1],base[2]), Color.argb((alpha*.12f).toInt(),65,112,166), Shader.TileMode.CLAMP)
            c.drawPath(path,fill); fill.shader=null
        }

        // Large clean central table like a real emerald-cut diamond.
        val table = RectF(ix,it,ir,ib)
        fill.shader = LinearGradient(table.left,table.top,table.right,table.bottom,
            intArrayOf(Color.argb(48,225,244,255),Color.argb(18,96,143,190),Color.argb(42,214,239,255)),
            floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        c.drawRoundRect(table,h*.10f,h*.10f,fill); fill.shader=null

        // One broad moving refraction band. No cross, no fixed fake scratches.
        val rad=Math.toRadians(lightAngle.toDouble()); val cx=r.centerX(); val cy=r.centerY()
        val dx=(cos(rad)*w*.62).toFloat(); val dy=(sin(rad)*h*3.2).toFloat()
        fill.shader=LinearGradient(cx-dx,cy-dy,cx+dx,cy+dy,
            intArrayOf(Color.TRANSPARENT,Color.argb(if(pressed)35 else 78,160,218,255),Color.argb(if(pressed)50 else 120,255,255,255),Color.TRANSPARENT),
            floatArrayOf(0f,.43f,.51f,1f),Shader.TileMode.CLAMP)
        c.drawRect(r,fill); fill.shader=null
        c.restore()

        // Multi-edge bevel gives thickness and diamond-like dispersion.
        line.strokeWidth=1.25f*density
        line.shader=LinearGradient(r.left,r.top,r.right,r.bottom,
            intArrayOf(Color.rgb(151,211,255),Color.WHITE,Color.rgb(113,170,224),Color.WHITE,Color.rgb(166,220,255)),null,Shader.TileMode.CLAMP)
        line.alpha=if(pressed)180 else 245; c.drawPath(outer,line); line.shader=null
        val inset=2.4f*density; val inner=RectF(r.left+inset,r.top+inset,r.right-inset,r.bottom-inset)
        line.strokeWidth=.65f*density; line.color=Color.argb(190,225,246,255); line.alpha=255
        c.drawPath(cutPath(inner,(cut-inset).coerceAtLeast(3f*density)),line)

        // Independent small glints that travel with the real light direction.
        val glints=arrayOf(floatArrayOf(.08f,.20f,210f),floatArrayOf(.25f,.07f,255f),floatArrayOf(.55f,.06f,292f),floatArrayOf(.82f,.08f,325f),floatArrayOf(.96f,.34f,0f),floatArrayOf(.88f,.88f,42f),floatArrayOf(.54f,.94f,90f),floatArrayOf(.15f,.88f,145f))
        glints.forEach { g ->
            val f=facing(g[2]); if(f<.72f)return@forEach
            val x=r.left+w*g[0]; val y=r.top+h*g[1]; val rr=(1.8f+4.5f*f)*density; val a=(235*f).toInt()
            fill.shader=RadialGradient(x,y,rr,Color.argb(a,255,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            c.drawCircle(x,y,rr,fill); fill.shader=null
        }
    }

    private fun facing(normal:Float):Float {
        val d=((lightAngle-normal+540f)%360f)-180f
        return ((cos(Math.toRadians(d.toDouble()))+1.0)*.5).toFloat()
    }
    private fun cutPath(x:RectF,cut:Float)=Path().apply{moveTo(x.left+cut,x.top);lineTo(x.right-cut,x.top);lineTo(x.right,x.top+cut);lineTo(x.right,x.bottom-cut);lineTo(x.right-cut,x.bottom);lineTo(x.left+cut,x.bottom);lineTo(x.left,x.bottom-cut);lineTo(x.left,x.top+cut);close()}
    private fun facet(vararg v:Float)=Path().apply{moveTo(v[0],v[1]);var i=2;while(i<v.size){lineTo(v[i],v[i+1]);i+=2};close()}
    override fun setAlpha(alpha:Int){fill.alpha=alpha;line.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(cf:ColorFilter?){fill.colorFilter=cf;line.colorFilter=cf;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
