package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ScrollView
import java.io.File
import kotlin.math.max

/** Fond visible de HP Travail. Le fond reste fixe pendant le défilement. */
class ThemedBackgroundScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedImage: Bitmap? = null
    private var cachedPath: String? = null

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, scrollY.toFloat())
        drawHpBackground(canvas)
        canvas.restore()
        super.dispatchDraw(canvas)
    }

    private fun drawHpBackground(canvas: Canvas) {
        val appearance = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val file = File(context.filesDir, AppearanceManager.BACKGROUND_FILE)
        if (appearance.getBoolean("custom_image_bg", false) && file.exists()) {
            drawSelectedImage(canvas, file)
            return
        }

        val theme = AppThemeCatalog.current(context)
        val dark = ThemeDayNight.isDark(context)
        when (theme.id) {
            "brushed_aluminum" -> drawBrushedAluminum(canvas, dark)
            "carbon" -> drawCarbon(canvas, dark)
            else -> canvas.drawColor(if (dark) theme.darkBackground else theme.lightBackground)
        }
    }

    private fun drawSelectedImage(canvas: Canvas, file: File) {
        if (cachedPath != file.absolutePath || cachedImage == null) {
            cachedImage?.recycle()
            cachedImage = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            cachedPath = file.absolutePath
        }
        val bmp = cachedImage ?: return
        val scale = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = (bmp.width * scale).toInt()
        val dh = (bmp.height * scale).toInt()
        val left = (width - dw) / 2
        val top = (height - dh) / 2
        canvas.drawBitmap(bmp, null, Rect(left, top, left + dw, top + dh), paint)
    }

    private fun drawBrushedAluminum(canvas: Canvas, dark: Boolean) {
        val top = if (dark) Color.rgb(48, 52, 55) else Color.rgb(238, 241, 242)
        val mid = if (dark) Color.rgb(82, 87, 90) else Color.rgb(190, 197, 201)
        val bottom = if (dark) Color.rgb(35, 39, 42) else Color.rgb(224, 228, 230)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(top, mid, top, bottom), floatArrayOf(0f, .38f, .7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        var y = 0
        while (y < height) {
            val phase = (y * 37) % 11
            val alpha = if (phase < 4) 26 else 12
            paint.color = if (dark) Color.argb(alpha, 225, 232, 235) else Color.argb(alpha, 35, 42, 46)
            paint.strokeWidth = if (phase == 0) 1.4f else .7f
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
            y += 2 + (phase % 3)
        }
    }

    private fun drawCarbon(canvas: Canvas, dark: Boolean) {
        val base = if (dark) Color.rgb(13, 14, 15) else Color.rgb(183, 187, 190)
        canvas.drawColor(base)
        val cell = (10f * resources.displayMetrics.density).coerceAtLeast(12f)
        val half = cell / 2f
        var y = -cell
        var row = 0
        while (y < height + cell) {
            var x = -cell
            while (x < width + cell) {
                val shift = if (row % 2 == 0) 0f else half
                paint.color = if (dark) Color.rgb(34, 36, 38) else Color.rgb(219, 222, 224)
                canvas.save()
                canvas.rotate(35f, x + shift + half, y + half)
                canvas.drawRoundRect(x + shift, y + cell * .12f, x + shift + cell * .72f, y + cell * .42f, cell * .08f, cell * .08f, paint)
                paint.color = if (dark) Color.rgb(6, 7, 8) else Color.rgb(143, 148, 151)
                canvas.drawRoundRect(x + shift + cell * .24f, y + cell * .50f, x + shift + cell * .96f, y + cell * .80f, cell * .08f, cell * .08f, paint)
                canvas.restore()
                x += cell
            }
            y += half
            row++
        }
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.argb(if (dark) 28 else 20, 255,255,255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
