package com.amaury.pointage.v3

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class V3HeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gold = Color.rgb(214, 168, 75)
    private val goldLight = Color.rgb(246, 216, 142)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(Color.rgb(4,4,4), Color.rgb(18,14,8), Color.BLACK), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint); paint.shader=null

        // fines veines dorées modifiables en code
        stroke.color = Color.argb(75, 214,168,75); stroke.strokeWidth = 1.2f * resources.displayMetrics.density
        for (i in 0..6) {
            val y = h * (0.12f + i * 0.12f)
            val p = Path().apply { moveTo(0f,y); cubicTo(w*.25f,y-18f,w*.55f,y+20f,w,y-8f) }
            canvas.drawPath(p,stroke)
        }

        drawBrand(canvas, w, h)
        drawWatch(canvas, w, h)
        drawDecor(canvas, w, h)
        stroke.color = gold; stroke.strokeWidth = 1f * resources.displayMetrics.density
        canvas.drawLine(0f,h-1f,w,h-1f,stroke)
    }

    private fun drawBrand(canvas: Canvas, w: Float, h: Float) {
        val cx = w * .17f
        paint.shader = LinearGradient(cx-60f,0f,cx+60f,0f,intArrayOf(Color.rgb(112,72,12),goldLight,Color.rgb(132,87,16)),null,Shader.TileMode.CLAMP)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("serif", Typeface.BOLD)
        paint.textSize = h * .17f
        canvas.drawText("♛", cx, h*.31f, paint)
        paint.textSize = h*.13f; canvas.drawText("H P", cx, h*.55f, paint)
        paint.textSize = h*.062f; paint.letterSpacing = .18f; canvas.drawText("T R A V A I L", cx, h*.68f, paint)
        paint.letterSpacing = 0f; paint.shader = null
    }

    private fun drawWatch(canvas: Canvas, w: Float, h: Float) {
        val cx = w*.52f; val cy = h*.47f; val r = min(w,h)*.31f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx-r*.25f,cy-r*.25f,r*1.3f,intArrayOf(Color.rgb(40,31,17),Color.rgb(4,4,4),Color.BLACK),null,Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy,r,paint); paint.shader=null

        stroke.strokeWidth = r*.10f; stroke.color = Color.rgb(91,58,10); canvas.drawCircle(cx,cy,r*.96f,stroke)
        stroke.strokeWidth = r*.045f; stroke.color = goldLight; canvas.drawCircle(cx,cy,r*.90f,stroke)
        stroke.strokeWidth = r*.02f; stroke.color = Color.rgb(100,66,17); canvas.drawCircle(cx,cy,r*.82f,stroke)

        for(i in 0 until 12){
            val a=Math.toRadians((i*30-90).toDouble())
            val x1=cx+cos(a).toFloat()*r*.66f; val y1=cy+sin(a).toFloat()*r*.66f
            val x2=cx+cos(a).toFloat()*r*.79f; val y2=cy+sin(a).toFloat()*r*.79f
            stroke.strokeWidth=if(i%3==0) r*.045f else r*.028f; stroke.color=goldLight
            canvas.drawLine(x1,y1,x2,y2,stroke)
        }

        paint.color=goldLight; paint.textAlign=Paint.Align.CENTER; paint.typeface=Typeface.create("serif",Typeface.BOLD); paint.textSize=r*.20f
        canvas.drawText("HP",cx,cy-r*.10f,paint)

        val now=Calendar.getInstance(); val sec=now.get(Calendar.SECOND); val min=now.get(Calendar.MINUTE)+sec/60f; val hour=(now.get(Calendar.HOUR)%12)+min/60f
        hand(canvas,cx,cy,r*.48f,hour*30f-90f,r*.045f,goldLight)
        hand(canvas,cx,cy,r*.63f,min*6f-90f,r*.030f,goldLight)
        hand(canvas,cx,cy,r*.69f,sec*6f-90f,r*.012f,gold)
        paint.color=gold; canvas.drawCircle(cx,cy,r*.07f,paint)
        postInvalidateDelayed(1000L)
    }

    private fun hand(c:Canvas,cx:Float,cy:Float,len:Float,deg:Float,width:Float,color:Int){
        val a=Math.toRadians(deg.toDouble()); stroke.color=color; stroke.strokeWidth=width; stroke.strokeCap=Paint.Cap.ROUND
        c.drawLine(cx,cy,cx+cos(a).toFloat()*len,cy+sin(a).toFloat()*len,stroke)
    }

    private fun drawDecor(canvas:Canvas,w:Float,h:Float){
        val x=w*.84f; paint.color=Color.rgb(25,20,13)
        for(i in 0..2){ val yy=h*(.30f+i*.17f); stroke.color=Color.rgb(91,64,26); stroke.strokeWidth=h*.035f; canvas.drawLine(x+i*w*.05f,yy,x-i*w*.015f,h*.79f,stroke); canvas.drawCircle(x+i*w*.05f,yy,h*.035f,paint) }
        paint.color=gold; paint.textAlign=Paint.Align.CENTER; paint.textSize=h*.13f; canvas.drawText("★",x-w*.03f,h*.28f,paint)
        stroke.color=gold; stroke.strokeWidth=2f*resources.displayMetrics.density
        canvas.drawRoundRect(w*.91f,h*.06f,w*.985f,h*.25f,12f,12f,stroke)
        paint.textSize=h*.10f; canvas.drawText("⚙",w*.948f,h*.20f,paint)
    }
}
