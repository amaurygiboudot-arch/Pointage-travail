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
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.max

/** Fond visible de HP Travail. Le fond reste fixe pendant le défilement. */
class ThemedBackgroundScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private data class GlobalBackgroundStats(
        val averageLuma: Float,
        val brightRatio: Float,
        val darkRatio: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedImage: Bitmap? = null
    private var cachedPath: String? = null
    private var cachedImageToken: String? = null
    private var cachedTextColor: Int? = null
    private var cachedShadowColor: Int? = null

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, scrollY.toFloat())
        drawHpBackground(canvas)
        canvas.restore()

        // Une seule couleur est calculée à partir de la moyenne de TOUT le fond d'écran.
        // Elle s'applique ensuite à tous les textes de l'application, sans changement pendant le scroll.
        applyGlobalAdaptiveTextColor()
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
        ensureImage(file)
        val bmp = cachedImage ?: return
        val scale = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = (bmp.width * scale).toInt()
        val dh = (bmp.height * scale).toInt()
        val left = (width - dw) / 2
        val top = (height - dh) / 2
        canvas.drawBitmap(bmp, null, Rect(left, top, left + dw, top + dh), paint)
    }

    private fun ensureImage(file: File) {
        val token = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        if (cachedImageToken != token || cachedImage == null) {
            cachedImage?.recycle()
            cachedImage = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            cachedPath = file.absolutePath
            cachedImageToken = token
            cachedTextColor = null
            cachedShadowColor = null
        }
    }

    private fun applyGlobalAdaptiveTextColor() {
        val prefs = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val file = File(context.filesDir, AppearanceManager.BACKGROUND_FILE)
        val hasImage = prefs.getBoolean("custom_image_bg", false) && file.exists()

        val textColor: Int
        val shadowColor: Int

        if (hasImage) {
            ensureImage(file)
            if (cachedTextColor == null || cachedShadowColor == null) {
                val bitmap = cachedImage
                if (bitmap != null) {
                    val stats = globalBackgroundStats(bitmap)
                    val useDarkText = chooseDarkText(stats)
                    cachedTextColor = if (useDarkText) Color.rgb(8, 8, 8) else Color.WHITE
                    cachedShadowColor = if (useDarkText) Color.argb(225, 255, 255, 255) else Color.argb(235, 0, 0, 0)
                }
            }
            textColor = cachedTextColor ?: Color.WHITE
            shadowColor = cachedShadowColor ?: Color.BLACK
        } else {
            val theme = AppThemeCatalog.current(context)
            val background = if (ThemeDayNight.isDark(context)) theme.darkBackground else theme.lightBackground
            val useDarkText = !isDark(background)
            textColor = if (useDarkText) Color.rgb(8, 8, 8) else Color.WHITE
            shadowColor = if (useDarkText) Color.argb(210, 255, 255, 255) else Color.argb(220, 0, 0, 0)
        }

        applyTextColorRecursively(this, textColor, shadowColor)
    }

    private fun chooseDarkText(stats: GlobalBackgroundStats): Boolean {
        // On combine la luminosité moyenne réelle avec la proportion de zones claires/sombres.
        // Le résultat représente la clarté générale du fond complet, pas une zone locale.
        val clarity = (
            stats.averageLuma * 0.70f +
            stats.brightRatio * 0.22f +
            (1f - stats.darkRatio) * 0.08f
        ).coerceIn(0f, 1f)

        return clarity >= 0.53f
    }

    private fun globalBackgroundStats(bitmap: Bitmap): GlobalBackgroundStats {
        // Échantillonnage régulier de toute l'image : rapide et représentatif.
        val stepX = (bitmap.width / 48).coerceAtLeast(1)
        val stepY = (bitmap.height / 72).coerceAtLeast(1)

        var sumLuma = 0f
        var bright = 0
        var dark = 0
        var count = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val luma = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
                sumLuma += luma
                if (luma >= 0.62f) bright++
                if (luma <= 0.36f) dark++
                count++
                x += stepX
            }
            y += stepY
        }

        if (count == 0) return GlobalBackgroundStats(0f, 0f, 1f)
        return GlobalBackgroundStats(
            averageLuma = sumLuma / count,
            brightRatio = bright.toFloat() / count,
            darkRatio = dark.toFloat() / count
        )
    }

    private fun applyTextColorRecursively(view: View, color: Int, shadow: Int) {
        if (view is TextView) {
            // Les boutons bijoux Entrée/Pause/Sortie n'ont pas de texte système à recolorer.
            view.setTextColor(color)
            view.setShadowLayer(3.8f, 0f, 1.1f, shadow)
            if (view is EditText) {
                view.setHintTextColor(if (color == Color.WHITE) Color.rgb(225, 225, 225) else Color.rgb(55, 55, 55))
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTextColorRecursively(view.getChildAt(i), color, shadow)
        }
    }

    private fun isDark(color: Int): Boolean =
        ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000) < 155

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
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.argb(if (dark) 28 else 20, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
