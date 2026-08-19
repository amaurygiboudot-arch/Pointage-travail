package com.amaury.pointage

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.*

/**
 * Bouton cristal taillé : centre optiquement calme, couronne facettée épaisse,
 * réfraction et éclats ponctuels pilotés par la direction lumineuse.
 * Aucun symbole/croix de lumière n'est dessiné.
 */
class DiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val tint: Int = Color.parseColor("#DDF6FF")
) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(angle: Float) {
        val n=((angle%360f)+360f)%360f
        if(abs(delta(lightAngle,n))<.45f)return
        lightAngle=n; invalidateSelf()
    }
    override fun isStateful()=true
    override fun onStateChange(state:IntArray):Boolean{
        val n=state.any{it==android.R.attr.state_pressed||it==android.R.attr.state_focused}
        if(n==pressed)return false; pressed=n; invalidateSelf(); return true
    }

    override fun draw(c:Canvas){
        if(bounds.isEmpty)return
        r.set(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat())
        val cut=min(r.height()*.24f,16f*density)
        val outer=cutPath(r,cut)
        val save=c.save(); c.clipPath(outer)
        drawGlassBody(c)
        drawCutRim(c,cut)
        drawRefraction(c)
        drawHotSpots(c,cut)
        c.restoreToCount(save)
        drawEdges(c,outer,cut)
    }

    private fun drawGlassBody(c:Canvas){
        // Le centre reste sombre/translucide comme le bouton de référence.
        val top=if(dark) Color.argb(126,20,38,60) else Color.argb(105,185,218,238)
        val mid=if(dark) Color.argb(155,8,20,37) else Color.argb(94,220,240,250)
        val bottom=if(dark) Color.argb(180,4,14,29) else Color.argb(118,151,193,220)
        p.shader=LinearGradient(r.left,r.top,r.left,r.bottom,intArrayOf(top,mid,bottom),floatArrayOf(0f,.47f,1f),Shader.TileMode.CLAMP)
        c.drawRect(r,p);p.shader=null
        // reflet doux de surface, pas de réseau de lignes au centre
        p.shader=LinearGradient(r.left,r.top,r.right,r.bottom,intArrayOf(Color.argb(35,255,255,255),Color.TRANSPARENT,Color.argb(18,120,196,242)),floatArrayOf(0f,.43f,1f),Shader.TileMode.CLAMP)
        c.drawRect(r,p);p.shader=null
    }

    private fun drawCutRim(c:Canvas,cut:Float){
        val l=r.left;val t=r.top;val rr=r.right;val b=r.bottom;val w=r.width();val h=r.height()
        val ix=l+cut*1.15f; val ir=rr-cut*1.15f; val it=t+cut*.72f; val ib=b-cut*.72f
        val facets=arrayOf(
            poly(l+cut,t, l+w*.18f,t, ix,it, l,t+cut) to 208f,
            poly(l+w*.18f,t,l+w*.38f,t, l+w*.34f,it,ix,it) to 250f,
            poly(l+w*.38f,t,l+w*.62f,t,l+w*.66f,it,l+w*.34f,it) to 285f,
            poly(l+w*.62f,t,rr-cut,t,ir,it,l+w*.66f,it) to 320f,
            poly(rr-cut,t,rr,t+cut,rr,b-cut,ir,ib,ir,it) to 350f,
            poly(rr,b-cut,rr-cut,b,l+w*.82f,b,ir,ib) to 25f,
            poly(l+w*.82f,b,l+w*.61f,b,l+w*.66f,ib,ir,ib) to 62f,
            poly(l+w*.61f,b,l+w*.39f,b,l+w*.34f,ib,l+w*.66f,ib) to 92f,
            poly(l+w*.39f,b,l+w*.18f,b,ix,ib,l+w*.34f,ib) to 126f,
            poly(l+w*.18f,b,l+cut,b,l,b-cut,l,t+cut,ix,it,ix,ib) to 160f
        )
        facets.forEachIndexed{idx,(path,norm)->
            val facing=((cos(Math.toRadians(delta(norm,lightAngle).toDouble()))+1.0)*.5).toFloat()
            val cool=if(idx%3==0)Color.rgb(174,220,255) else if(idx%4==0)Color.rgb(221,231,255) else Color.WHITE
            val a=((if(dark)38 else 28)+facing*(if(pressed)75 else 165)).toInt().coerceIn(25,205)
            p.shader=LinearGradient(l,t,rr,b,Color.argb(a,cool.red(),cool.green(),cool.blue()),Color.argb((a*.18f).toInt(),75,142,200),Shader.TileMode.CLAMP)
            c.drawPath(path,p);p.shader=null
        }
        // centre intérieur : aucune facette traversante
        p.color=if(dark)Color.argb(28,0,9,20) else Color.argb(18,235,250,255)
        c.drawRoundRect(RectF(ix,it,ir,ib),h*.14f,h*.14f,p)
    }

    private fun drawRefraction(c:Canvas){
        val rad=Math.toRadians(lightAngle.toDouble()); val cx=r.centerX();val cy=r.centerY()
        val dx=(cos(rad)*r.width()*.55).toFloat();val dy=(sin(rad)*r.height()*2.2).toFloat()
        p.shader=LinearGradient(cx-dx,cy-dy,cx+dx,cy+dy,intArrayOf(Color.TRANSPARENT,Color.argb(if(pressed)18 else 48,174,221,255),Color.argb(if(pressed)25 else 72,255,255,255),Color.TRANSPARENT),floatArrayOf(0f,.43f,.52f,1f),Shader.TileMode.CLAMP)
        c.drawRect(r,p);p.shader=null
    }

    private fun drawHotSpots(c:Canvas,cut:Float){
        // Reflets indépendants placés surtout sur la couronne taillée.
        val pts=arrayOf(floatArrayOf(.08f,.22f,18f),floatArrayOf(.25f,.08f,92f),floatArrayOf(.52f,.06f,151f),floatArrayOf(.78f,.10f,214f),floatArrayOf(.94f,.30f,278f),floatArrayOf(.87f,.86f,326f),floatArrayOf(.58f,.93f,43f),floatArrayOf(.18f,.88f,128f),floatArrayOf(.04f,.63f,184f))
        pts.forEach{q->
            val facing=((cos(Math.toRadians(delta(q[2],lightAngle).toDouble()))+1.0)*.5).toFloat()
            if(facing<.43f)return@forEach
            val x=r.left+r.width()*q[0];val y=r.top+r.height()*q[1]
            val radius=(2.0f+5.2f*facing)*density
            val a=((facing-.43f)/.57f*(if(pressed)125 else 235)).toInt().coerceIn(0,235)
            p.shader=RadialGradient(x,y,radius,intArrayOf(Color.argb(a,255,255,255),Color.argb((a*.28f).toInt(),151,215,255),Color.TRANSPARENT),floatArrayOf(0f,.22f,1f),Shader.TileMode.CLAMP)
            c.drawCircle(x,y,radius,p);p.shader=null
            // petite diffraction, sans forme de +
            if(facing>.78f){
                stroke.strokeWidth=.55f*density;stroke.color=Color.argb((a*.65f).toInt(),225,245,255)
                val len=(3f+4f*facing)*density
                val aRad=Math.toRadians((lightAngle+q[2]*.17f).toDouble())
                val vx=(cos(aRad)*len).toFloat();val vy=(sin(aRad)*len).toFloat()
                c.drawLine(x-vx,y-vy,x+vx,y+vy,stroke)
            }
        }
    }

    private fun drawEdges(c:Canvas,outer:Path,cut:Float){
        stroke.strokeWidth=1.15f*density
        stroke.shader=LinearGradient(r.left,r.top,r.right,r.bottom,intArrayOf(Color.rgb(174,222,255),Color.WHITE,Color.rgb(108,172,225),Color.WHITE),null,Shader.TileMode.CLAMP)
        stroke.alpha=if(pressed)165 else 230;c.drawPath(outer,stroke);stroke.shader=null
        val inset=2.2f*density;val inner=RectF(r.left+inset,r.top+inset,r.right-inset,r.bottom-inset)
        stroke.strokeWidth=.55f*density;stroke.color=Color.argb(if(pressed)90 else 155,225,247,255);stroke.alpha=255
        c.drawPath(cutPath(inner,(cut-inset).coerceAtLeast(3f*density)),stroke)
    }

    private fun cutPath(x:RectF,cut:Float)=Path().apply{moveTo(x.left+cut,x.top);lineTo(x.right-cut,x.top);lineTo(x.right,x.top+cut);lineTo(x.right,x.bottom-cut);lineTo(x.right-cut,x.bottom);lineTo(x.left+cut,x.bottom);lineTo(x.left,x.bottom-cut);lineTo(x.left,x.top+cut);close()}
    private fun poly(vararg v:Float)=Path().apply{moveTo(v[0],v[1]);var i=2;while(i<v.size){lineTo(v[i],v[i+1]);i+=2};close()}
    private fun Int.red()=Color.red(this);private fun Int.green()=Color.green(this);private fun Int.blue()=Color.blue(this)
    private fun delta(a:Float,b:Float)=((b-a+540f)%360f)-180f
    override fun setAlpha(alpha:Int){p.alpha=alpha;stroke.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(cf:ColorFilter?){p.colorFilter=cf;stroke.colorFilter=cf;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
