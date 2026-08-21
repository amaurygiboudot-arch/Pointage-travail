package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.util.Base64
import java.util.Calendar
import kotlin.math.max

object WidgetVisualRenderer {
    enum class Jewel { ENTRY, PAUSE, EXIT }

    fun jewel(context: Context, type: Jewel, sizePx: Int): Bitmap {
        val out = jewelFrame(sizePx)
        val c = Canvas(out)
        c.drawBitmap(jewelInner(context, type, sizePx), 0f, 0f, null)
        return out
    }

    fun jewelFrame(sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(64)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        drawFrame(c, size / 2f, size / 2f, size / 2f)
        return bitmap
    }

    fun jewelInner(context: Context, type: Jewel, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(64)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f
        val jr = r * .885f

        val raw = when (type) {
            Jewel.ENTRY -> R.raw.hp_button_bg_green_b64
            Jewel.PAUSE -> R.raw.hp_button_bg_orange_b64
            Jewel.EXIT -> R.raw.hp_button_bg_red_b64
        }
        decodeRawBitmap(context, raw)?.let { src ->
            val dst = RectF(cx - jr, cy - jr, cx + jr, cy + jr)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isFilterBitmap = true
                val contrast = 1.18f
                val o = -128f * contrast + 128f
                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    contrast,0f,0f,0f,o,
                    0f,contrast,0f,0f,o,
                    0f,0f,contrast,0f,o,
                    0f,0f,0f,1f,0f
                )))
            }
            c.drawBitmap(src, null, dst, paint)
        }

        when (type) {
            Jewel.ENTRY -> drawArrow(c, cx, cy, jr, false)
            Jewel.EXIT -> drawArrow(c, cx, cy, jr, true)
            Jewel.PAUSE -> drawPause(c, cx, cy, jr)
        }
        return bitmap
    }

    fun clock(sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(120)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cx = size * .50f
        val cy = size * .55f
        val faceRadius = size * .40f
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isFilterBitmap = true }

        val faceRect = RectF(cx-faceRadius, cy-faceRadius, cx+faceRadius, cy+faceRadius)
        val contrast = 1.20f
        val translate = (-128f * contrast + 128f) + 4f
        bitmapPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            contrast,0f,0f,0f,translate,
            0f,contrast,0f,0f,translate,
            0f,0f,contrast,0f,translate,
            0f,0f,0f,1f,0f
        )))
        c.drawBitmap(HpDesignAssets.clockFace, null, faceRect, bitmapPaint)
        bitmapPaint.colorFilter = null

        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = (now.get(Calendar.HOUR) % 12) + minutes / 60f
        drawHand(c, HpDesignAssets.hand, cx, cy, hours * 30f, faceRadius * .48f, .90f, bitmapPaint)
        drawHand(c, HpDesignAssets.hand, cx, cy, minutes * 6f, faceRadius * .70f, .90f, bitmapPaint)
        drawHand(c, HpDesignAssets.secondHand, cx, cy, seconds * 6f, faceRadius * .78f, .88f, bitmapPaint)

        val earth = EarthDesignAsset.bitmap
        if (earth.width > 0 && earth.height > 0) {
            val er = max(faceRadius * .16f, 13f)
            val diameter = er * 2f
            val aspect = earth.width.toFloat() / earth.height.toFloat()
            val w: Float
            val h: Float
            if (aspect >= 1f) { w = diameter; h = diameter / aspect } else { h = diameter; w = diameter * aspect }
            c.drawBitmap(earth, null, RectF(cx-w/2f, cy-h/2f, cx+w/2f, cy+h/2f), bitmapPaint)
        }
        return out
    }

    private fun decodeRawBitmap(context: Context, raw: Int): Bitmap? = runCatching {
        val s = context.resources.openRawResource(raw).bufferedReader().use { it.readText() }
        val bytes = Base64.decode(s.trim(), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun drawFrame(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        p.strokeWidth = r*.09f
        p.shader = SweepGradient(cx,cy,intArrayOf(Color.rgb(82,42,2),Color.rgb(255,226,116),Color.rgb(160,88,6),Color.rgb(255,247,184),Color.rgb(91,47,3),Color.rgb(255,218,85),Color.rgb(82,42,2)),null)
        c.drawCircle(cx,cy,r*.95f,p)
        p.shader = null
        p.strokeWidth = r*.082f
        p.shader = SweepGradient(cx,cy,intArrayOf(Color.rgb(0,8,35),Color.rgb(5,91,220),Color.rgb(0,15,62),Color.rgb(24,126,255),Color.rgb(0,13,51),Color.rgb(4,80,202),Color.rgb(0,8,35)),null)
        c.drawCircle(cx,cy,r*.915f,p)
        p.shader = null
        p.strokeWidth = r*.027f; p.color = Color.rgb(255,202,62); c.drawCircle(cx,cy,r*.878f,p)
        p.strokeWidth = r*.009f; p.color = Color.rgb(255,248,190); c.drawCircle(cx,cy,r*.893f,p)
    }

    private fun drawArrow(c: Canvas, cx: Float, cy: Float, r: Float, mirror: Boolean) {
        val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        val ir = r*.285f
        icon.shader = RadialGradient(cx-ir*.22f,cy-ir*.25f,ir*1.3f,intArrayOf(Color.rgb(24,112,214),Color.rgb(2,35,104),Color.rgb(0,7,28)),floatArrayOf(0f,.58f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,ir,icon); icon.shader=null
        detail.color=Color.rgb(255,205,70); detail.strokeWidth=r*.032f; c.drawCircle(cx,cy,ir*.96f,detail)
        detail.color=Color.rgb(255,242,168); detail.strokeWidth=r*.01f; c.drawCircle(cx,cy,ir*.78f,detail)
        c.save(); if (mirror) c.scale(-1f,1f,cx,cy)
        val p=Path().apply { val x0=cx-ir*.5f; val x1=cx+ir*.18f; val tip=cx+ir*.58f; val half=ir*.18f; val head=ir*.33f; moveTo(x0,cy-half); lineTo(x1,cy-half); lineTo(x1,cy-head); lineTo(tip,cy); lineTo(x1,cy+head); lineTo(x1,cy+half); lineTo(x0,cy+half); close() }
        icon.color=Color.rgb(244,180,43); c.drawPath(p,icon)
        detail.strokeJoin=Paint.Join.MITER; detail.strokeWidth=r*.018f; detail.color=Color.rgb(255,244,174); c.drawPath(p,detail)
        c.restore()
    }

    private fun drawPause(c: Canvas, cx: Float, cy: Float, r: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(248,183,48) }
        val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(88,45,4); strokeWidth = r*.024f }
        val bw=r*.145f; val bh=r*.54f; val gap=r*.105f; val top=cy-bh*.5f; val bottom=cy+bh*.5f; val corner=bw*.16f
        c.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,detail); c.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,detail)
        c.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,fill); c.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,fill)
        detail.strokeWidth=r*.009f; detail.color=Color.rgb(255,244,176)
        c.drawRoundRect(cx-gap-bw,top,cx-gap,bottom,corner,corner,detail); c.drawRoundRect(cx+gap,top,cx+gap+bw,bottom,corner,corner,detail)
    }

    private fun drawHand(c: Canvas, bitmap: Bitmap, cx: Float, cy: Float, angle: Float, tipLength: Float, pivotYRatio: Float, paint: Paint) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val px = bitmap.width*.5f; val py = bitmap.height*pivotYRatio; val scale = tipLength / py.coerceAtLeast(1f)
        c.save(); c.translate(cx,cy); c.rotate(angle)
        c.drawBitmap(bitmap,null,RectF(-px*scale,-py*scale,(bitmap.width-px)*scale,(bitmap.height-py)*scale),paint)
        c.restore()
    }
}
