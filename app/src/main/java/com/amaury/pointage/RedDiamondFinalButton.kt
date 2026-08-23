package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.widget.Button
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Moteur permanent des trois diamants de pointage.
 * Chaque facette possède une orientation propre et réagit séparément à la
 * direction réelle Soleil/Lune + à l'inclinaison du téléphone.
 */
open class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "Diamant rouge final"
        private const val FACETS = 16
        private val live = Collections.newSetFromMap(WeakHashMap<RedDiamondFinalButton, Boolean>())

        /** Une seule source céleste pilote simultanément rouge, vert et orange. */
        fun updateGlobalNaturalLight(angle: Float, pitch: Float, roll: Float, intensity: Float, night: Boolean) {
            live.toList().forEach { it.setNaturalLight(angle, pitch, roll, intensity, night) }
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = Path()

    private var lightAngle = -55f
    private var devicePitch = 0f
    private var deviceRoll = 0f
    private var lightIntensity = .78f
    private var nightLight = false

    open fun diamondPalette() = intArrayOf(
        Color.rgb(255,50,76), Color.rgb(214,5,35), Color.rgb(132,0,24), Color.rgb(255,92,118),
        Color.rgb(92,0,20), Color.rgb(238,12,48), Color.rgb(178,0,31), Color.rgb(255,148,164),
        Color.rgb(110,0,25), Color.rgb(245,22,56), Color.rgb(156,0,29), Color.rgb(255,72,102),
        Color.rgb(74,0,18), Color.rgb(226,8,42), Color.rgb(194,0,34), Color.rgb(255,118,140)
    )
    open fun diamondTint() = Color.rgb(255,28,62)
    open fun diamondDark() = Color.rgb(96,0,22)
    open fun diamondHighlight() = Color.rgb(255,238,243)

    init {
        background = null
        stateListAnimator = null
        setPadding(0,0,0,0)
        isAllCaps = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        live.add(this)
    }

    override fun onDetachedFromWindow() {
        live.remove(this)
        super.onDetachedFromWindow()
    }

    fun setDiamondLightAngle(angle: Float) = setNaturalLight(angle, devicePitch, deviceRoll, lightIntensity, nightLight)

    private fun setNaturalLight(angle: Float, pitch: Float, roll: Float, intensity: Float, night: Boolean) {
        lightAngle = normalize(angle)
        devicePitch = pitch.coerceIn(-90f, 90f)
        deviceRoll = roll.coerceIn(-90f, 90f)
        lightIntensity = intensity.coerceIn(.12f, 1f)
        nightLight = night
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w * .5f; val cy = h * .5f; val r = min(w,h) * .465f
        val press = if (isPressed) .93f else 1f
        c.save(); c.scale(press, press, cx, cy)
        outer.reset(); outer.addCircle(cx,cy,r,Path.Direction.CW); c.clipPath(outer)
        drawGlassBase(c,cx,cy,r)
        drawFacets(c,cx,cy,r)
        drawRefraction(c,cx,cy,r)
        drawEdges(c,cx,cy,r)
        drawSunGlints(c,cx,cy,r)
        c.restore()
        drawRim(c,cx,cy,r*press)
    }

    private fun drawGlassBase(c: Canvas, cx: Float, cy: Float, r: Float) {
        val t=diamondTint(); val d=diamondDark()
        val rad=Math.toRadians(lightAngle.toDouble())
        val lx=cx+cos(rad).toFloat()*r*.24f; val ly=cy+sin(rad).toFloat()*r*.24f
        val ambient = if (nightLight) .62f else .82f
        fill.shader=RadialGradient(lx,ly,r*1.28f,intArrayOf(
            alphaColor(lighten(t,.30f), (178+55*lightIntensity).toInt()),
            alphaColor(t,(190+42*ambient).toInt()),
            alphaColor(d,218),
            Color.argb(230,Color.red(d)/5,Color.green(d)/5,Color.blue(d)/5)
        ),floatArrayOf(0f,.34f,.73f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,r,fill); fill.shader=null
    }

    private fun drawFacets(c:Canvas,cx:Float,cy:Float,r:Float){
        val ir=r*.30f; val mr=r*.64f; val or=r*.96f
        for(i in 0 until FACETS){val a0=angle(i);val a1=angle(i+1);drawFacet(c,center(cx,cy,point(cx,cy,ir,a0),point(cx,cy,ir,a1)),i,normal(a0,a1),1.12f,0)}
        for(i in 0 until FACETS){val a0=angle(i);val a1=angle(i+1);val am=(a0+a1)*.5f;val i0=point(cx,cy,ir,a0);val i1=point(cx,cy,ir,a1);val m0=point(cx,cy,mr,a0);val m1=point(cx,cy,mr,a1);val mm=point(cx,cy,mr,am);drawFacet(c,poly(i0,m0,mm,i1),i+3,normal(a0,am),.92f,1);drawFacet(c,poly(i1,mm,m1),i+9,normal(am,a1),.82f,1)}
        for(i in 0 until FACETS){val a0=angle(i);val a1=angle(i+1);val am=(a0+a1)*.5f;val m0=point(cx,cy,mr,a0);val m1=point(cx,cy,mr,a1);val o0=point(cx,cy,or,a0);val o1=point(cx,cy,or,a1);val om=point(cx,cy,or,am);drawFacet(c,poly(m0,o0,om,m1),i+5,normal(a0,am),.72f,2);drawFacet(c,poly(m1,om,o1),i+12,normal(am,a1),.64f,2)}
    }

    private fun drawFacet(c:Canvas,p:Path,index:Int,normal:Float,energy:Float,ring:Int){
        // Chaque facette reçoit une légère inclinaison propre : deux voisines ne brillent jamais pareil.
        val localTilt = ((index*37 + ring*53) % 31 - 15) * .72f + deviceRoll*.22f - devicePitch*.14f
        val incidence = facing(normal + localTilt)
        val diffuse = (.14f + incidence*.78f*energy*lightIntensity).coerceIn(.10f,1.22f)
        val specular = incidence.toDouble().pow(if(nightLight) 14.0 else 22.0).toFloat() * lightIntensity
        val palette=diamondPalette(); val base=palette[index%palette.size]; val hi=diamondHighlight()
        val rr=(Color.red(base)*diffuse).toInt().coerceIn(0,255)
        val gg=(Color.green(base)*diffuse).toInt().coerceIn(0,255)
        val bb=(Color.blue(base)*diffuse).toInt().coerceIn(0,255)
        val flash=(specular*(if(nightLight) .55f else 1.35f)).coerceIn(0f,1f)
        val top=mix(Color.rgb(rr,gg,bb),hi,flash)
        val deep=Color.rgb((rr*.38f).toInt(),(gg*.38f).toInt(),(bb*.38f).toInt())
        val alpha=(150+incidence*86f).toInt().coerceIn(145,242)
        fill.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),alphaColor(top,alpha),alphaColor(deep,(alpha*.72f).toInt()),Shader.TileMode.CLAMP)
        c.drawPath(p,fill); fill.shader=null
        if(specular>.72f){
            fill.color=alphaColor(hi,((specular-.72f)/.28f*145f).toInt().coerceIn(0,145))
            c.drawPath(p,fill)
        }
    }

    private fun drawRefraction(c:Canvas,cx:Float,cy:Float,r:Float){
        val rad=Math.toRadians(lightAngle.toDouble());val ux=cos(rad).toFloat();val uy=sin(rad).toFloat();val t=diamondTint();val hi=diamondHighlight()
        val beam=(if(nightLight)62 else 142)*lightIntensity
        fill.shader=LinearGradient(cx-ux*r*.96f,cy-uy*r*.96f,cx+ux*r*.96f,cy+uy*r*.96f,intArrayOf(Color.TRANSPARENT,alphaColor(t,(20*lightIntensity).toInt()),alphaColor(hi,beam.toInt().coerceIn(20,160)),alphaColor(t,(58*lightIntensity).toInt()),Color.TRANSPARENT),floatArrayOf(0f,.30f,.49f,.64f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,r,fill);fill.shader=null
    }

    private fun drawEdges(c:Canvas,cx:Float,cy:Float,r:Float){
        val hi=diamondHighlight();edge.strokeWidth=maxOf(1f,r*.0105f);edge.color=alphaColor(hi,if(nightLight)60 else 92)
        for(i in 0 until FACETS){val a=angle(i);val p1=point(cx,cy,r*.30f,a);val p2=point(cx,cy,r*.64f,a);val p3=point(cx,cy,r*.96f,a);c.drawLine(cx,cy,p1[0],p1[1],edge);c.drawLine(p1[0],p1[1],p2[0],p2[1],edge);c.drawLine(p2[0],p2[1],p3[0],p3[1],edge)}
        edge.alpha=64;c.drawCircle(cx,cy,r*.30f,edge);c.drawCircle(cx,cy,r*.64f,edge);edge.alpha=255
    }

    private fun drawSunGlints(c:Canvas,cx:Float,cy:Float,r:Float){
        val rad=Math.toRadians(lightAngle.toDouble());val ux=cos(rad).toFloat();val uy=sin(rad).toFloat();val hi=diamondHighlight()
        val tiltShift=(deviceRoll-devicePitch)*.0035f
        val x=cx+ux*r*(.47f+tiltShift).coerceIn(.25f,.68f);val y=cy+uy*r*(.47f-tiltShift).coerceIn(.25f,.68f)
        val power=lightIntensity*(if(nightLight).48f else 1f)
        glow.shader=RadialGradient(x,y,r*.30f,intArrayOf(alphaColor(hi,(230*power).toInt().coerceIn(25,230)),alphaColor(hi,(112*power).toInt().coerceIn(12,112)),alphaColor(diamondTint(),(28*power).toInt()),Color.TRANSPARENT),floatArrayOf(0f,.11f,.48f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(x,y,r*.30f,glow);glow.shader=null
        if(!nightLight && power>.48f){
            edge.strokeWidth=maxOf(1f,r*.014f);edge.color=alphaColor(Color.WHITE,(210*power).toInt().coerceIn(50,220))
            val len=r*(.07f+.13f*power);c.drawLine(x-len,y,x+len,y,edge);c.drawLine(x,y-len,x,y+len,edge)
        }
    }

    private fun drawRim(c:Canvas,cx:Float,cy:Float,r:Float){val d=diamondDark();val t=diamondTint();val hi=diamondHighlight();edge.style=Paint.Style.STROKE;edge.strokeWidth=maxOf(1.2f,r*.028f);edge.shader=SweepGradient(cx,cy,intArrayOf(d,hi,t,d,hi,t,d),null);c.drawCircle(cx,cy,r,edge);edge.shader=null}

    private fun facing(n:Float):Float{val delta=((lightAngle-n+540f)%360f)-180f;return ((cos(Math.toRadians(delta.toDouble()))+1.0)*.5).toFloat()}
    private fun angle(i:Int)=-90f+i*(360f/FACETS)
    private fun normal(a:Float,b:Float)=(a+b)*.5f
    private fun normalize(v:Float)=((v%360f)+360f)%360f
    private fun point(cx:Float,cy:Float,r:Float,d:Float):FloatArray{val q=Math.toRadians(d.toDouble());return floatArrayOf(cx+cos(q).toFloat()*r,cy+sin(q).toFloat()*r)}
    private fun poly(vararg p:FloatArray)=Path().apply{moveTo(p[0][0],p[0][1]);for(i in 1 until p.size)lineTo(p[i][0],p[i][1]);close()}
    private fun center(cx:Float,cy:Float,vararg p:FloatArray)=Path().apply{moveTo(cx,cy);p.forEach{lineTo(it[0],it[1])};close()}
    private fun alphaColor(c:Int,a:Int)=Color.argb(a.coerceIn(0,255),Color.red(c),Color.green(c),Color.blue(c))
    private fun lighten(c:Int,t:Float)=Color.rgb((Color.red(c)+(255-Color.red(c))*t).toInt(),(Color.green(c)+(255-Color.green(c))*t).toInt(),(Color.blue(c)+(255-Color.blue(c))*t).toInt())
    private fun mix(a:Int,b:Int,t:Float):Int{val q=t.coerceIn(0f,1f);return Color.rgb((Color.red(a)+(Color.red(b)-Color.red(a))*q).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*q).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*q).toInt())}
}
