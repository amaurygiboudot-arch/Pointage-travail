package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.widget.Button
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.*

/**
 * Moteur optique temps réel des trois diamants permanents.
 * Modèle physiquement inspiré : normale 3D par facette, Fresnel-Schlick,
 * indice de réfraction du diamant, angle critique, dispersion RGB et éclats
 * spéculaires très étroits. Optimisé pour un bouton Android de petite taille.
 */
open class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "Diamant rouge final"
        private const val FACETS = 16
        private const val N_AIR = 1.000293f
        private const val N_DIAMOND = 2.417f
        private val live = Collections.newSetFromMap(WeakHashMap<RedDiamondFinalButton, Boolean>())

        fun updateGlobalNaturalLight(angle:Float,pitch:Float,roll:Float,intensity:Float,night:Boolean,elevation:Float){
            live.toList().forEach{it.setNaturalLight(angle,pitch,roll,intensity,night,elevation)}
        }
    }

    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
    private val glow=Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer=Path()
    private var lightAngle=-55f
    private var pitch=0f
    private var roll=0f
    private var intensity=.78f
    private var night=false
    private var elevation=45f

    open fun diamondPalette()=intArrayOf(Color.rgb(255,50,76),Color.rgb(214,5,35),Color.rgb(132,0,24),Color.rgb(255,92,118),Color.rgb(92,0,20),Color.rgb(238,12,48),Color.rgb(178,0,31),Color.rgb(255,148,164),Color.rgb(110,0,25),Color.rgb(245,22,56),Color.rgb(156,0,29),Color.rgb(255,72,102),Color.rgb(74,0,18),Color.rgb(226,8,42),Color.rgb(194,0,34),Color.rgb(255,118,140))
    open fun diamondTint()=Color.rgb(255,28,62)
    open fun diamondDark()=Color.rgb(96,0,22)
    open fun diamondHighlight()=Color.rgb(255,238,243)

    init{background=null;stateListAnimator=null;setPadding(0,0,0,0);isAllCaps=false}
    override fun onAttachedToWindow(){super.onAttachedToWindow();live.add(this)}
    override fun onDetachedFromWindow(){live.remove(this);super.onDetachedFromWindow()}
    fun setDiamondLightAngle(angle:Float)=setNaturalLight(angle,pitch,roll,intensity,night,elevation)
    private fun setNaturalLight(a:Float,p:Float,r:Float,i:Float,n:Boolean,e:Float){lightAngle=norm(a);pitch=p.coerceIn(-90f,90f);roll=r.coerceIn(-90f,90f);intensity=i.coerceIn(.12f,1f);night=n;elevation=e.coerceIn(-10f,90f);invalidate()}

    override fun onDraw(c:Canvas){
        val w=width.toFloat();val h=height.toFloat();if(w<=0f||h<=0f)return
        val cx=w*.5f;val cy=h*.5f;val radius=min(w,h)*.465f;val press=if(isPressed).93f else 1f
        c.save();c.scale(press,press,cx,cy);outer.reset();outer.addCircle(cx,cy,radius,Path.Direction.CW);c.clipPath(outer)
        drawGlass(c,cx,cy,radius);drawFacets(c,cx,cy,radius);drawDispersion(c,cx,cy,radius);drawEdges(c,cx,cy,radius);drawCausticGlints(c,cx,cy,radius)
        c.restore();drawRim(c,cx,cy,radius*press)
    }

    private fun drawGlass(c:Canvas,cx:Float,cy:Float,r:Float){
        val rad=Math.toRadians(lightAngle.toDouble());val lx=cx+cos(rad).toFloat()*r*.22f;val ly=cy+sin(rad).toFloat()*r*.22f
        val t=diamondTint();val d=diamondDark();val amb=if(night).55f else .76f
        fill.shader=RadialGradient(lx,ly,r*1.30f,intArrayOf(alpha(lighten(t,.31f),(166+62*intensity).toInt()),alpha(t,(186+48*amb).toInt()),alpha(d,216),Color.argb(232,Color.red(d)/6,Color.green(d)/6,Color.blue(d)/6)),floatArrayOf(0f,.34f,.72f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,r,fill);fill.shader=null
    }

    private fun drawFacets(c:Canvas,cx:Float,cy:Float,r:Float){
        val ir=r*.30f;val mr=r*.64f;val or=r*.96f
        for(i in 0 until FACETS){val a0=angle(i);val a1=angle(i+1);facet(c,center(cx,cy,pt(cx,cy,ir,a0),pt(cx,cy,ir,a1)),i,(a0+a1)*.5f,0,1.10f)}
        for(i in 0 until FACETS){val a0=angle(i);val a1=angle(i+1);val am=(a0+a1)*.5f;val i0=pt(cx,cy,ir,a0);val i1=pt(cx,cy,ir,a1);val m0=pt(cx,cy,mr,a0);val m1=pt(cx,cy,mr,a1);val mm=pt(cx,cy,mr,am);facet(c,poly(i0,m0,mm,i1),i+3,(a0+am)*.5f,1,.92f);facet(c,poly(i1,mm,m1),i+9,(am+a1)*.5f,1,.82f)}
        for(i in 0 until FACETS){val a0=angle(i);val a1=angle(i+1);val am=(a0+a1)*.5f;val m0=pt(cx,cy,mr,a0);val m1=pt(cx,cy,mr,a1);val o0=pt(cx,cy,or,a0);val o1=pt(cx,cy,or,a1);val om=pt(cx,cy,or,am);facet(c,poly(m0,o0,om,m1),i+5,(a0+am)*.5f,2,.74f);facet(c,poly(m1,om,o1),i+12,(am+a1)*.5f,2,.64f)}
    }

    private fun facet(c:Canvas,path:Path,index:Int,azimuth:Float,ring:Int,energy:Float){
        val normalTilt=when(ring){0->18f;1->31f;else->43f}+(((index*37+ring*53)%17)-8)*.42f
        val normalAz=azimuth+(((index*29+ring*11)%13)-6)*.38f
        val N=normal3(normalAz,normalTilt,pitch,roll)
        val L=light3(lightAngle,elevation)
        val V=floatArrayOf(0f,0f,1f)
        val ndl=max(0f,dot(N,L));val ndv=max(.001f,dot(N,V))
        val H=normalize3(floatArrayOf(L[0]+V[0],L[1]+V[1],L[2]+V[2]))
        val ndh=max(0f,dot(N,H))
        val r0=((N_AIR-N_DIAMOND)/(N_AIR+N_DIAMOND)).pow(2)
        val fresnel=r0+(1f-r0)*(1f-ndv).pow(5)
        val shininess=if(night)72f else 145f
        val spec=ndh.pow(shininess)*intensity
        val incidenceDeg=Math.toDegrees(acos(ndl.coerceIn(0f,1f)).toDouble()).toFloat()
        val critical=Math.toDegrees(asin((N_AIR/N_DIAMOND).toDouble())).toFloat()
        val tir=if(incidenceDeg>critical) ((incidenceDeg-critical)/(90f-critical)).coerceIn(0f,1f) else 0f
        val transmission=(1f-fresnel)*(1f-tir)*ndl
        val brightness=(.11f+energy*(ndl*.62f+transmission*.34f)+fresnel*.24f).coerceIn(.08f,1.35f)*(.62f+.48f*intensity)
        val base=diamondPalette()[index%diamondPalette().size];val hi=diamondHighlight()
        val rr=(Color.red(base)*brightness).toInt().coerceIn(0,255);val gg=(Color.green(base)*brightness).toInt().coerceIn(0,255);val bb=(Color.blue(base)*brightness).toInt().coerceIn(0,255)
        val flash=(spec*1.9f+fresnel*.18f+tir*.22f).coerceIn(0f,1f)
        val top=mix(Color.rgb(rr,gg,bb),hi,flash)
        val deep=Color.rgb((rr*.32f).toInt(),(gg*.32f).toInt(),(bb*.32f).toInt())
        val a=(150+ndl*58f+fresnel*30f).toInt().coerceIn(145,244)
        fill.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),alpha(top,a),alpha(deep,(a*.70f).toInt()),Shader.TileMode.CLAMP);c.drawPath(path,fill);fill.shader=null
        if(spec>.38f){fill.color=alpha(Color.WHITE,((spec-.38f)/.62f*185f).toInt().coerceIn(0,185));c.drawPath(path,fill)}
    }

    private fun drawDispersion(c:Canvas,cx:Float,cy:Float,r:Float){
        val rad=Math.toRadians(lightAngle.toDouble());val ux=cos(rad).toFloat();val uy=sin(rad).toFloat();val px=-uy;val py=ux
        val strength=(if(night).22f else .65f)*intensity
        val spread=r*(.010f+.012f*strength)
        val colors=intArrayOf(Color.argb((52*strength).toInt(),255,70,55),Color.argb((45*strength).toInt(),70,255,120),Color.argb((58*strength).toInt(),80,135,255))
        for(j in -1..1){val off=spread*j;fill.shader=LinearGradient(cx-ux*r*.82f+px*off,cy-uy*r*.82f+py*off,cx+ux*r*.82f+px*off,cy+uy*r*.82f+py*off,intArrayOf(Color.TRANSPARENT,colors[j+1],Color.TRANSPARENT),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP);c.drawCircle(cx,cy,r,fill);fill.shader=null}
    }

    private fun drawEdges(c:Canvas,cx:Float,cy:Float,r:Float){val hi=diamondHighlight();edge.strokeWidth=maxOf(1f,r*.009f);edge.color=alpha(hi,if(night)48 else 78);for(i in 0 until FACETS){val a=angle(i);val p1=pt(cx,cy,r*.30f,a);val p2=pt(cx,cy,r*.64f,a);val p3=pt(cx,cy,r*.96f,a);c.drawLine(cx,cy,p1[0],p1[1],edge);c.drawLine(p1[0],p1[1],p2[0],p2[1],edge);c.drawLine(p2[0],p2[1],p3[0],p3[1],edge)};edge.alpha=54;c.drawCircle(cx,cy,r*.30f,edge);c.drawCircle(cx,cy,r*.64f,edge);edge.alpha=255}

    private fun drawCausticGlints(c:Canvas,cx:Float,cy:Float,r:Float){
        val rad=Math.toRadians(lightAngle.toDouble());val ux=cos(rad).toFloat();val uy=sin(rad).toFloat();val hi=diamondHighlight();val elevFactor=((elevation+5f)/70f).coerceIn(.15f,1f);val power=intensity*elevFactor*(if(night).38f else 1f)
        val x=cx+ux*r*(.45f+roll*.0015f).coerceIn(.28f,.66f);val y=cy+uy*r*(.45f-pitch*.0015f).coerceIn(.28f,.66f)
        glow.shader=RadialGradient(x,y,r*.26f,intArrayOf(alpha(Color.WHITE,(238*power).toInt().coerceIn(18,238)),alpha(hi,(130*power).toInt().coerceIn(10,130)),alpha(diamondTint(),(30*power).toInt()),Color.TRANSPARENT),floatArrayOf(0f,.08f,.34f,1f),Shader.TileMode.CLAMP);c.drawCircle(x,y,r*.26f,glow);glow.shader=null
        if(!night&&power>.42f){edge.strokeWidth=maxOf(1f,r*.012f);edge.color=alpha(Color.WHITE,(225*power).toInt().coerceIn(45,225));val len=r*(.055f+.15f*power);c.drawLine(x-len,y,x+len,y,edge);c.drawLine(x,y-len,x,y+len,edge)}
    }

    private fun drawRim(c:Canvas,cx:Float,cy:Float,r:Float){val d=diamondDark();val t=diamondTint();val hi=diamondHighlight();edge.style=Paint.Style.STROKE;edge.strokeWidth=maxOf(1.2f,r*.026f);edge.shader=SweepGradient(cx,cy,intArrayOf(d,hi,t,d,hi,t,d),null);c.drawCircle(cx,cy,r,edge);edge.shader=null}

    private fun normal3(az:Float,tilt:Float,p:Float,r:Float):FloatArray{val azr=Math.toRadians((az+r*.18f).toDouble());val tr=Math.toRadians((tilt+p*.08f).coerceIn(2f,82f).toDouble());val s=sin(tr).toFloat();return normalize3(floatArrayOf(cos(azr).toFloat()*s,sin(azr).toFloat()*s,cos(tr).toFloat()))}
    private fun light3(az:Float,elev:Float):FloatArray{val ar=Math.toRadians(az.toDouble());val er=Math.toRadians(elev.coerceIn(-5f,90f).toDouble());val ce=cos(er).toFloat();return normalize3(floatArrayOf(cos(ar).toFloat()*ce,sin(ar).toFloat()*ce,sin(er).toFloat()))}
    private fun dot(a:FloatArray,b:FloatArray)=a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
    private fun normalize3(v:FloatArray):FloatArray{val l=sqrt((v[0]*v[0]+v[1]*v[1]+v[2]*v[2]).coerceAtLeast(1e-8f));return floatArrayOf(v[0]/l,v[1]/l,v[2]/l)}
    private fun angle(i:Int)=-90f+i*(360f/FACETS);private fun norm(v:Float)=((v%360f)+360f)%360f
    private fun pt(cx:Float,cy:Float,r:Float,d:Float):FloatArray{val q=Math.toRadians(d.toDouble());return floatArrayOf(cx+cos(q).toFloat()*r,cy+sin(q).toFloat()*r)}
    private fun poly(vararg p:FloatArray)=Path().apply{moveTo(p[0][0],p[0][1]);for(i in 1 until p.size)lineTo(p[i][0],p[i][1]);close()}
    private fun center(cx:Float,cy:Float,vararg p:FloatArray)=Path().apply{moveTo(cx,cy);p.forEach{lineTo(it[0],it[1])};close()}
    private fun alpha(c:Int,a:Int)=Color.argb(a.coerceIn(0,255),Color.red(c),Color.green(c),Color.blue(c))
    private fun lighten(c:Int,t:Float)=Color.rgb((Color.red(c)+(255-Color.red(c))*t).toInt(),(Color.green(c)+(255-Color.green(c))*t).toInt(),(Color.blue(c)+(255-Color.blue(c))*t).toInt())
    private fun mix(a:Int,b:Int,t:Float):Int{val q=t.coerceIn(0f,1f);return Color.rgb((Color.red(a)+(Color.red(b)-Color.red(a))*q).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*q).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*q).toInt())}
}
