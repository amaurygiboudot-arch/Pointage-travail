package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.widget.Button
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.*

/** Rendu 3D procédural des trois diamants permanents, sans image bitmap. */
open class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "Diamant rouge final"
        private const val FACETS = 16
        private val live = Collections.newSetFromMap(WeakHashMap<RedDiamondFinalButton, Boolean>())
        fun updateGlobalNaturalLight(angle:Float,pitch:Float,roll:Float,intensity:Float,night:Boolean,elevation:Float) {
            live.toList().forEach { it.setNaturalLight(angle,pitch,roll,intensity,night,elevation) }
        }
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var lightAngle = -55f
    private var pitch = 0f
    private var roll = 0f
    private var intensity = .78f
    private var night = false
    private var elevation = 45f

    open fun diamondPalette() = intArrayOf(
        Color.rgb(255,50,76),Color.rgb(214,5,35),Color.rgb(132,0,24),Color.rgb(255,92,118),
        Color.rgb(92,0,20),Color.rgb(238,12,48),Color.rgb(178,0,31),Color.rgb(255,148,164),
        Color.rgb(110,0,25),Color.rgb(245,22,56),Color.rgb(156,0,29),Color.rgb(255,72,102),
        Color.rgb(74,0,18),Color.rgb(226,8,42),Color.rgb(194,0,34),Color.rgb(255,118,140)
    )
    open fun diamondTint() = Color.rgb(255,28,62)
    open fun diamondDark() = Color.rgb(96,0,22)
    open fun diamondHighlight() = Color.rgb(255,238,243)

    init { background=null; stateListAnimator=null; setPadding(0,0,0,0); isAllCaps=false }
    override fun onAttachedToWindow(){ super.onAttachedToWindow(); live.add(this) }
    override fun onDetachedFromWindow(){ live.remove(this); super.onDetachedFromWindow() }
    fun setDiamondLightAngle(a:Float){ setNaturalLight(a,pitch,roll,intensity,night,elevation) }
    private fun setNaturalLight(a:Float,pi:Float,ro:Float,i:Float,n:Boolean,e:Float){
        lightAngle=((a%360f)+360f)%360f; pitch=pi.coerceIn(-90f,90f); roll=ro.coerceIn(-90f,90f)
        intensity=i.coerceIn(.12f,1f); night=n; elevation=e.coerceIn(-10f,90f); invalidate()
    }

    override fun onDraw(c:Canvas){
        val s=min(width,height).toFloat(); if(s<=0f)return
        val cx=width*.5f; val cy=height*.5f; val r=s*.405f
        val press=if(isPressed).94f else 1f
        c.save(); c.scale(press,press,cx,cy)

        // La face supérieure se déplace réellement par rapport au pavillon : ce n'est plus un disque plat.
        val tx=(roll/90f)*r*.13f
        val ty=(pitch/90f)*r*.13f
        val topX=cx+tx; val topY=cy+ty-r*.035f
        val bottomX=cx-tx*.45f; val bottomY=cy-ty*.45f+r*.115f

        drawGroundShadow(c,bottomX,bottomY,r)
        drawPavilion(c,topX,topY,bottomX,bottomY,r)
        drawGirdleSide(c,topX,topY,bottomX,bottomY,r)
        drawCrown(c,topX,topY,r)
        drawTable(c,topX,topY,r)
        drawFacetLines(c,topX,topY,r)
        drawSpark(c,topX,topY,r)
        c.restore()
    }

    private fun drawGroundShadow(c:Canvas,cx:Float,cy:Float,r:Float){
        p.shader=RadialGradient(cx,cy+r*.18f,r*1.18f,intArrayOf(Color.argb(105,0,0,0),Color.argb(45,0,0,0),Color.TRANSPARENT),floatArrayOf(0f,.62f,1f),Shader.TileMode.CLAMP)
        c.save(); c.scale(1f,.42f,cx,cy); c.drawCircle(cx,cy,r*1.08f,p); c.restore(); p.shader=null
    }

    private fun drawPavilion(c:Canvas,tx:Float,ty:Float,bx:Float,by:Float,r:Float){
        val dark=diamondDark(); val tint=diamondTint()
        // Pavillon visible sous la couronne : plusieurs triangles convergent vers un culot décalé.
        val culetX=bx; val culetY=by+r*.34f
        for(i in 0 until FACETS){
            val a0=ang(i); val a1=ang(i+1)
            val q0=pt(tx,ty,r*.91f,a0); val q1=pt(tx,ty,r*.91f,a1)
            val path=poly(q0,q1,floatArrayOf(culetX,culetY))
            val f=(.20f+.55f*lightFacing((a0+a1)*.5f+180f))*(if(night).62f else 1f)
            val base=diamondPalette()[(i+7)%diamondPalette().size]
            p.shader=LinearGradient(q0[0],q0[1],culetX,culetY,alpha(scale(base,.46f+f*.34f),205),alpha(scale(dark,.28f),238),Shader.TileMode.CLAMP)
            c.drawPath(path,p); p.shader=null
        }
        p.color=alpha(tint,38); c.drawCircle(culetX,culetY,r*.055f,p)
    }

    private fun drawGirdleSide(c:Canvas,tx:Float,ty:Float,bx:Float,by:Float,r:Float){
        val topRy=r*.91f; val lowerRy=r*.91f
        // Bande latérale en trapèzes : elle crée une épaisseur géométrique visible.
        for(i in 0 until FACETS){
            val a0=ang(i); val a1=ang(i+1)
            val t0=pt(tx,ty,topRy,a0); val t1=pt(tx,ty,topRy,a1)
            val b0=pt(bx,by,lowerRy,a0); val b1=pt(bx,by,lowerRy,a1)
            val path=poly(t0,t1,b1,b0)
            val face=lightFacing((a0+a1)*.5f)
            val base=diamondPalette()[(i+4)%diamondPalette().size]
            p.shader=LinearGradient(t0[0],t0[1],b0[0],b0[1],alpha(scale(base,.48f+.52f*face),238),alpha(scale(diamondDark(),.22f+.22f*face),248),Shader.TileMode.CLAMP)
            c.drawPath(path,p); p.shader=null
        }
        stroke.strokeWidth=max(1.4f,r*.035f); stroke.color=alpha(diamondHighlight(),130)
        c.drawCircle(tx,ty,r*.91f,stroke)
    }

    private fun drawCrown(c:Canvas,cx:Float,cy:Float,r:Float){
        val table=r*.34f; val crown=r*.90f
        for(i in 0 until FACETS){
            val a0=ang(i); val a1=ang(i+1); val am=(a0+a1)*.5f
            val i0=pt(cx,cy,table,a0); val i1=pt(cx,cy,table,a1)
            val o0=pt(cx,cy,crown,a0); val o1=pt(cx,cy,crown,a1); val om=pt(cx,cy,crown,am)
            drawFace(c,poly(i0,o0,om,i1),i,am-5f,.98f)
            drawFace(c,poly(i1,om,o1),i+9,am+7f,.72f)
        }
    }

    private fun drawTable(c:Canvas,cx:Float,cy:Float,r:Float){
        val tr=r*.34f
        val points=Array(FACETS){pt(cx,cy,tr,ang(it))}
        val path=poly(*points)
        val rad=Math.toRadians(lightAngle.toDouble()); val dx=cos(rad).toFloat()*tr*.55f; val dy=sin(rad).toFloat()*tr*.55f
        p.shader=LinearGradient(cx-dx,cy-dy,cx+dx,cy+dy,alpha(lighten(diamondTint(),.58f),220),alpha(scale(diamondTint(),.42f),225),Shader.TileMode.CLAMP)
        c.drawPath(path,p); p.shader=null
        // Petit plan interne décalé : donne une vraie sensation de table au-dessus du cœur.
        p.shader=RadialGradient(cx-dx*.22f,cy-dy*.22f,tr*.78f,intArrayOf(alpha(Color.WHITE,90),alpha(diamondTint(),40),alpha(diamondDark(),105)),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,tr*.72f,p); p.shader=null
    }

    private fun drawFace(c:Canvas,path:Path,index:Int,normalAz:Float,depth:Float){
        val facing=lightFacing(normalAz)
        val base=diamondPalette()[index%diamondPalette().size]
        val dark=diamondDark(); val hi=diamondHighlight()
        val shade=(.20f+facing*.92f*intensity)*depth
        val bright=scale(base,shade.coerceIn(.12f,1.28f))
        val spec=specular(normalAz)
        val lit=mix(bright,hi,(spec*.78f).coerceIn(0f,.82f))
        p.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),alpha(lit,230),alpha(scale(dark,.30f+.22f*facing),235),Shader.TileMode.CLAMP)
        c.drawPath(path,p); p.shader=null
        if(spec>.72f){p.color=alpha(Color.WHITE,((spec-.72f)*420f).toInt().coerceIn(0,115));c.drawPath(path,p)}
        drawFire(c,path,index,spec)
    }

    private fun drawFacetLines(c:Canvas,cx:Float,cy:Float,r:Float){
        stroke.strokeWidth=max(1f,r*.010f); stroke.color=alpha(diamondHighlight(),72)
        for(i in 0 until FACETS){val a=ang(i);val aMid=ang(i)+360f/FACETS/2f;val t=pt(cx,cy,r*.34f,a);val o=pt(cx,cy,r*.90f,a);val m=pt(cx,cy,r*.90f,aMid);c.drawLine(t[0],t[1],o[0],o[1],stroke);c.drawLine(t[0],t[1],m[0],m[1],stroke)}
        stroke.strokeWidth=max(1f,r*.018f); stroke.color=alpha(Color.BLACK,105); c.drawCircle(cx,cy,r*.905f,stroke)
        stroke.strokeWidth=max(1f,r*.010f); stroke.color=alpha(diamondHighlight(),120); c.drawCircle(cx,cy,r*.885f,stroke)
    }

    private fun drawSpark(c:Canvas,cx:Float,cy:Float,r:Float){
        val rad=Math.toRadians(lightAngle.toDouble()); val power=intensity*(if(night).30f else 1f)
        val x=cx+cos(rad).toFloat()*r*.53f; val y=cy+sin(rad).toFloat()*r*.53f
        p.shader=RadialGradient(x,y,r*.16f,intArrayOf(alpha(Color.WHITE,(210*power).toInt()),alpha(diamondHighlight(),(95*power).toInt()),Color.TRANSPARENT),floatArrayOf(0f,.16f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(x,y,r*.16f,p); p.shader=null
    }

    private fun drawFire(c:Canvas,path:Path,index:Int,spec:Float){
        if(night||spec<.84f||index%5!=0)return
        val power=((spec-.84f)/.16f).coerceIn(0f,1f)*intensity
        val q=Math.toRadians((lightAngle+90f).toDouble());val dx=cos(q).toFloat()*width*.010f;val dy=sin(q).toFloat()*height*.010f
        p.shader=LinearGradient(width*.5f-dx,height*.5f-dy,width*.5f+dx,height*.5f+dy,intArrayOf(alpha(Color.rgb(255,120,45),(38*power).toInt()),Color.TRANSPARENT,alpha(Color.rgb(90,145,255),(42*power).toInt())),null,Shader.TileMode.CLAMP)
        c.drawPath(path,p);p.shader=null
    }

    private fun lightFacing(az:Float):Float{val d=Math.toRadians((((lightAngle-az+540f)%360f)-180f).toDouble());val horizontal=((cos(d)+1.0)*.5).toFloat();val elev=((elevation+10f)/100f).coerceIn(.08f,1f);return(horizontal*.72f+elev*.28f).coerceIn(0f,1f)}
    private fun specular(az:Float):Float{val delta=abs(((lightAngle-az+540f)%360f)-180f);val angular=cos(Math.toRadians(delta.toDouble())).toFloat().coerceAtLeast(0f);val tiltInfluence=(1f-(abs(pitch)*.004f+abs(roll)*.004f)).coerceIn(.45f,1f);return angular.pow(if(night)18f else 38f)*tiltInfluence*intensity}
    private fun ang(i:Int)=-90f+i*(360f/FACETS)
    private fun pt(cx:Float,cy:Float,r:Float,d:Float):FloatArray{val q=Math.toRadians(d.toDouble());return floatArrayOf(cx+cos(q).toFloat()*r,cy+sin(q).toFloat()*r)}
    private fun poly(vararg a:FloatArray)=Path().apply{moveTo(a[0][0],a[0][1]);for(i in 1 until a.size)lineTo(a[i][0],a[i][1]);close()}
    private fun alpha(c:Int,a:Int)=Color.argb(a.coerceIn(0,255),Color.red(c),Color.green(c),Color.blue(c))
    private fun scale(c:Int,s:Float)=Color.rgb((Color.red(c)*s).toInt().coerceIn(0,255),(Color.green(c)*s).toInt().coerceIn(0,255),(Color.blue(c)*s).toInt().coerceIn(0,255))
    private fun lighten(c:Int,t:Float)=Color.rgb((Color.red(c)+(255-Color.red(c))*t).toInt().coerceIn(0,255),(Color.green(c)+(255-Color.green(c))*t).toInt().coerceIn(0,255),(Color.blue(c)+(255-Color.blue(c))*t).toInt().coerceIn(0,255))
    private fun mix(a:Int,b:Int,t:Float):Int{val q=t.coerceIn(0f,1f);return Color.rgb((Color.red(a)+(Color.red(b)-Color.red(a))*q).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*q).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*q).toInt())}
}