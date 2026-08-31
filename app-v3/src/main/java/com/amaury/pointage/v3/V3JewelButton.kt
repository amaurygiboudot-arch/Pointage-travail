package com.amaury.pointage.v3

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class V3JewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var isEntry: Boolean = true
        set(value) { field = value; invalidate() }
    var lightAngle: Float = -55f
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gold = Color.rgb(206,150,48)
    private val goldLight = Color.rgb(255,222,133)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width,height).toFloat(); if(size<=0f) return
        val cx=width/2f; val cy=height/2f; val r=size*.46f
        drawShadow(canvas,cx,cy,r)
        drawGoldRing(canvas,cx,cy,r)
        drawDiamonds(canvas,cx,cy,r)
        drawStone(canvas,cx,cy,r*.72f)
        drawSymbol(canvas,cx,cy,r)
    }

    private fun drawShadow(c:Canvas,cx:Float,cy:Float,r:Float){
        paint.color=Color.argb(90,0,0,0); c.drawCircle(cx+r*.03f,cy+r*.05f,r*1.02f,paint)
    }

    private fun drawGoldRing(c:Canvas,cx:Float,cy:Float,r:Float){
        val a=Math.toRadians(lightAngle.toDouble()); val lx=(cos(a)*r).toFloat(); val ly=(sin(a)*r).toFloat()
        paint.shader=LinearGradient(cx-lx,cy-ly,cx+lx,cy+ly,
            intArrayOf(Color.rgb(78,45,7),gold,goldLight,Color.rgb(121,74,11),Color.rgb(55,30,5)),
            floatArrayOf(0f,.26f,.45f,.72f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,r,paint); paint.shader=null
        paint.color=Color.rgb(15,10,5); c.drawCircle(cx,cy,r*.84f,paint)
        stroke.color=goldLight; stroke.strokeWidth=r*.035f; c.drawCircle(cx,cy,r*.97f,stroke)
        stroke.color=Color.rgb(95,55,8); stroke.strokeWidth=r*.025f; c.drawCircle(cx,cy,r*.86f,stroke)
    }

    private fun drawDiamonds(c:Canvas,cx:Float,cy:Float,r:Float){
        for(i in 0 until 18){
            val deg=i*20f-90f; val a=Math.toRadians(deg.toDouble()); val rr=r*.905f
            val x=cx+cos(a).toFloat()*rr; val y=cy+sin(a).toFloat()*rr
            val facing=(cos(Math.toRadians((deg-lightAngle).toDouble()))*.5+.5).toFloat()
            val dr=r*.055f
            paint.color=Color.rgb((180+75*facing).toInt(),(180+75*facing).toInt(),(190+65*facing).toInt())
            val p=Path().apply{moveTo(x,y-dr);lineTo(x+dr,y);lineTo(x,y+dr);lineTo(x-dr,y);close()}
            c.drawPath(p,paint)
            if(facing>.72f){
                stroke.color=Color.argb((120+120*facing).toInt(),255,255,255); stroke.strokeWidth=r*.018f
                c.drawLine(x-dr*.9f,y,x+dr*.9f,y,stroke); c.drawLine(x,y-dr*.9f,x,y+dr*.9f,stroke)
            }
        }
    }

    private fun drawStone(c:Canvas,cx:Float,cy:Float,r:Float){
        val base=if(isEntry) Color.rgb(0,125,54) else Color.rgb(170,0,18)
        val dark=if(isEntry) Color.rgb(0,35,15) else Color.rgb(52,0,8)
        val a=Math.toRadians(lightAngle.toDouble()); val lx=(cos(a)*r).toFloat(); val ly=(sin(a)*r).toFloat()
        paint.shader=RadialGradient(cx-lx*.35f,cy-ly*.35f,r*1.1f,
            intArrayOf(lighten(base,1.65f),base,dark),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,r,paint); paint.shader=null

        // facettes : la pierre diffuse davantage que les diamants du contour
        for(i in 0 until 12){
            val a1=Math.toRadians((i*30f).toDouble()); val a2=Math.toRadians((i*30f+30f).toDouble())
            val p=Path().apply{moveTo(cx,cy);lineTo(cx+cos(a1).toFloat()*r,cy+sin(a1).toFloat()*r);lineTo(cx+cos(a2).toFloat()*r,cy+sin(a2).toFloat()*r);close()}
            val mid=i*30f+15f
            val f=(cos(Math.toRadians((mid-lightAngle).toDouble()))*.5+.5).toFloat()
            paint.color=if(f>.5f) Color.argb((30+70*f).toInt(),255,255,255) else Color.argb((30+50*(1f-f)).toInt(),0,0,0)
            c.drawPath(p,paint)
        }
        stroke.color=Color.argb(150,255,255,255); stroke.strokeWidth=r*.02f; c.drawCircle(cx,cy,r,stroke)
    }

    private fun drawSymbol(c:Canvas,cx:Float,cy:Float,r:Float){
        paint.color=goldLight; paint.textAlign=Paint.Align.CENTER; paint.typeface=Typeface.DEFAULT_BOLD; paint.textSize=r*.72f
        c.drawText(if(isEntry) "↪" else "↩",cx,cy+r*.22f,paint)
    }

    private fun lighten(color:Int,f:Float)=Color.rgb(
        (Color.red(color)*f).toInt().coerceIn(0,255),
        (Color.green(color)*f).toInt().coerceIn(0,255),
        (Color.blue(color)*f).toInt().coerceIn(0,255)
    )
}
