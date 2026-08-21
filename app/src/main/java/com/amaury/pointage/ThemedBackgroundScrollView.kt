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
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.util.WeakHashMap
import kotlin.math.max

/** Fond visible de HP Travail. Le fond reste fixe pendant le défilement. */
class ThemedBackgroundScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private data class BackgroundStats(
        val averageLuma: Float,
        val brightRatio: Float,
        val darkRatio: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedImage: Bitmap? = null
    private var cachedPath: String? = null
    private var lastThemeToken: String? = null
    private var contrastFramePending = false
    private val lastUseDarkText = WeakHashMap<TextView, Boolean>()

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, scrollY.toFloat())
        drawHpBackground(canvas)
        canvas.restore()
        scheduleAdaptiveContrast()
        super.dispatchDraw(canvas)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // Un seul recalcul par frame : le changement de contraste reste fluide même
        // pendant un défilement rapide, sans analyser la photo des dizaines de fois par frame.
        scheduleAdaptiveContrast()
    }

    private fun scheduleAdaptiveContrast() {
        if (contrastFramePending) return
        contrastFramePending = true
        postOnAnimation {
            contrastFramePending = false
            applyAdaptiveTextContrast(force = true)
        }
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
        if (cachedPath != file.absolutePath || cachedImage == null) {
            cachedImage?.recycle()
            cachedImage = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            cachedPath = file.absolutePath
            lastThemeToken = null
            lastUseDarkText.clear()
        }
    }

    private fun applyAdaptiveTextContrast(force: Boolean) {
        val prefs = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val file = File(context.filesDir, AppearanceManager.BACKGROUND_FILE)
        val hasImage = prefs.getBoolean("custom_image_bg", false) && file.exists()

        if (hasImage) {
            ensureImage(file)
            val bmp = cachedImage ?: return
            // Chaque texte analyse toute la zone de photo située derrière lui.
            // On calcule un taux de clarté / de sombre, puis on choisit noir ou blanc.
            applyLocalContrastRecursively(this, bmp)
            return
        }

        val theme = AppThemeCatalog.current(context)
        val token = "theme:${theme.id}:${ThemeDayNight.isDark(context)}"
        if (!force && token == lastThemeToken) return
        lastThemeToken = token
        val background = if (ThemeDayNight.isDark(context)) theme.darkBackground else theme.lightBackground
        val foreground = if (isDark(background)) Color.WHITE else Color.rgb(20, 20, 20)
        val shadow = if (foreground == Color.WHITE) Color.argb(220, 0, 0, 0) else Color.argb(210, 255, 255, 255)
        applyUniformTextStyleRecursively(this, foreground, shadow)
    }

    private fun applyLocalContrastRecursively(view: View, bitmap: Bitmap) {
        when (view) {
            is EditText -> styleTextForLocalBackground(view, bitmap, true)
            is TextView -> if (view !is Button && view !is Switch) {
                styleTextForLocalBackground(view, bitmap, false)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyLocalContrastRecursively(view.getChildAt(i), bitmap)
        }
    }

    private fun styleTextForLocalBackground(view: TextView, bitmap: Bitmap, isInput: Boolean) {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0 || width <= 0 || height <= 0) return

        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        getLocationOnScreen(rootLocation)
        view.getLocationOnScreen(viewLocation)

        val left = (viewLocation[0] - rootLocation[0]).toFloat().coerceIn(0f, width.toFloat())
        val top = (viewLocation[1] - rootLocation[1]).toFloat().coerceIn(0f, height.toFloat())
        val right = (left + view.width).coerceIn(left, width.toFloat())
        val bottom = (top + view.height).coerceIn(top, height.toFloat())

        val stats = backgroundStats(bitmap, left, top, right, bottom)
        val previousDarkText = lastUseDarkText[view]

        // Score 0 = très sombre, 1 = très clair. On mélange luminosité moyenne et
        // proportion de pixels réellement clairs pour mieux gérer visages, herbe et ciel.
        val clarityScore = (stats.averageLuma * 0.62f + stats.brightRatio * 0.38f).coerceIn(0f, 1f)

        // Hystérésis : dans la zone intermédiaire on conserve la couleur précédente.
        // Ça évite que le texte clignote noir/blanc à chaque petit mouvement du scroll.
        val useDarkText = when (previousDarkText) {
            true -> clarityScore >= 0.46f
            false -> clarityScore > 0.58f
            null -> clarityScore >= 0.52f
        }
        lastUseDarkText[view] = useDarkText

        val foreground = if (useDarkText) Color.rgb(8, 8, 8) else Color.WHITE
        val shadow = if (useDarkText) Color.argb(235, 255, 255, 255) else Color.argb(240, 0, 0, 0)

        view.setTextColor(foreground)
        view.setShadowLayer(4.6f, 0f, 1.2f, shadow)
        if (isInput && view is EditText) {
            view.setHintTextColor(if (useDarkText) Color.rgb(55, 55, 55) else Color.rgb(225, 225, 225))
        }
    }

    private fun backgroundStats(bitmap: Bitmap, viewportLeft: Float, viewportTop: Float, viewportRight: Float, viewportBottom: Float): BackgroundStats {
        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawnW = bitmap.width * scale
        val drawnH = bitmap.height * scale
        val imageLeft = (width - drawnW) / 2f
        val imageTop = (height - drawnH) / 2f

        fun toSrcX(x: Float) = ((x - imageLeft) / scale).toInt().coerceIn(0, bitmap.width - 1)
        fun toSrcY(y: Float) = ((y - imageTop) / scale).toInt().coerceIn(0, bitmap.height - 1)

        var x0 = toSrcX(viewportLeft)
        var x1 = toSrcX(viewportRight)
        var y0 = toSrcY(viewportTop)
        var y1 = toSrcY(viewportBottom)
        if (x1 < x0) x0 = x1.also { x1 = x0 }
        if (y1 < y0) y0 = y1.also { y1 = y0 }

        // Élargit légèrement la zone : on tient compte du décor autour des lettres,
        // pas seulement d'une ligne de pixels située exactement sous les glyphes.
        val padX = ((x1 - x0) * 0.12f).toInt().coerceAtLeast(2)
        val padY = ((y1 - y0) * 0.25f).toInt().coerceAtLeast(2)
        x0 = (x0 - padX).coerceAtLeast(0)
        x1 = (x1 + padX).coerceAtMost(bitmap.width - 1)
        y0 = (y0 - padY).coerceAtLeast(0)
        y1 = (y1 + padY).coerceAtMost(bitmap.height - 1)

        val areaW = (x1 - x0 + 1).coerceAtLeast(1)
        val areaH = (y1 - y0 + 1).coerceAtLeast(1)
        val stepX = (areaW / 18).coerceAtLeast(1)
        val stepY = (areaH / 8).coerceAtLeast(1)

        var sumLuma = 0f
        var bright = 0
        var dark = 0
        var count = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = bitmap.getPixel(x, y)
                // Luminance perçue (sRGB simplifiée), plus proche de ce que l'œil voit.
                val luma = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
                sumLuma += luma
                if (luma >= 0.60f) bright++
                if (luma <= 0.38f) dark++
                count++
                x += stepX
            }
            y += stepY
        }

        if (count == 0) return BackgroundStats(0f, 0f, 1f)
        return BackgroundStats(
            averageLuma = sumLuma / count,
            brightRatio = bright.toFloat() / count,
            darkRatio = dark.toFloat() / count
        )
    }

    private fun applyUniformTextStyleRecursively(view: View, color: Int, shadow: Int) {
        when (view) {
            is EditText -> {
                view.setTextColor(color)
                view.setHintTextColor(if (color == Color.WHITE) Color.LTGRAY else Color.DKGRAY)
                view.setShadowLayer(3f, 0f, 1f, shadow)
            }
            is TextView -> if (view !is Button && view !is Switch) {
                view.setTextColor(color)
                view.setShadowLayer(3f, 0f, 1f, shadow)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyUniformTextStyleRecursively(view.getChildAt(i), color, shadow)
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
