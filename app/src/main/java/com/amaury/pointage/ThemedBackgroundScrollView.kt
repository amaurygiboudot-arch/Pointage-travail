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
    private var lastThemeToken: String? = null
    private var lastContrastScrollY = Int.MIN_VALUE

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, scrollY.toFloat())
        drawHpBackground(canvas)
        canvas.restore()
        applyAdaptiveTextContrast(force = false)
        super.dispatchDraw(canvas)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // Le fond ne bouge pas, mais le texte se déplace dessus : on recalcule donc
        // localement la meilleure couleur au fur et à mesure du défilement.
        if (kotlin.math.abs(t - lastContrastScrollY) >= 12) {
            applyAdaptiveTextContrast(force = true)
            lastContrastScrollY = t
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
            lastContrastScrollY = Int.MIN_VALUE
        }
    }

    private fun applyAdaptiveTextContrast(force: Boolean) {
        val prefs = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val file = File(context.filesDir, AppearanceManager.BACKGROUND_FILE)
        val hasImage = prefs.getBoolean("custom_image_bg", false) && file.exists()

        if (hasImage) {
            ensureImage(file)
            val bmp = cachedImage ?: return
            // Une photo peut être claire à un endroit et sombre 50 px plus loin.
            // On choisit donc la couleur POUR CHAQUE TEXTE selon la zone située juste derrière lui.
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
            is TextView -> {
                // Les boutons ont déjà leur propre fond/contraste ; on ne les recolore pas.
                if (view !is Button && view !is Switch) styleTextForLocalBackground(view, bitmap, false)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyLocalContrastRecursively(view.getChildAt(i), bitmap)
        }
    }

    private fun styleTextForLocalBackground(view: TextView, bitmap: Bitmap, isInput: Boolean) {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0 || width <= 0 || height <= 0) return

        val scrollLocation = IntArray(2)
        val viewLocation = IntArray(2)
        getLocationOnScreen(scrollLocation)
        view.getLocationOnScreen(viewLocation)

        val centerX = (viewLocation[0] - scrollLocation[0] + view.width / 2f).coerceIn(0f, width.toFloat())
        val centerY = (viewLocation[1] - scrollLocation[1] + view.height / 2f).coerceIn(0f, height.toFloat())
        val local = localBackgroundColor(bitmap, centerX, centerY)

        val foreground = if (isDark(local)) Color.WHITE else Color.rgb(12, 12, 12)
        val shadow = if (foreground == Color.WHITE) Color.argb(235, 0, 0, 0) else Color.argb(225, 255, 255, 255)

        view.setTextColor(foreground)
        // Ombre assez franche pour rester lisible sur herbe, feuillage, visages, ciel, etc.
        view.setShadowLayer(4.2f, 0f, 1.2f, shadow)
        if (isInput && view is EditText) {
            view.setHintTextColor(if (foreground == Color.WHITE) Color.rgb(225, 225, 225) else Color.rgb(55, 55, 55))
        }
    }

    private fun localBackgroundColor(bitmap: Bitmap, viewportX: Float, viewportY: Float): Int {
        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawnW = bitmap.width * scale
        val drawnH = bitmap.height * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f

        val srcX = ((viewportX - left) / scale).toInt().coerceIn(0, bitmap.width - 1)
        val srcY = ((viewportY - top) / scale).toInt().coerceIn(0, bitmap.height - 1)
        val radiusX = (bitmap.width / 80).coerceIn(3, 24)
        val radiusY = (bitmap.height / 120).coerceIn(3, 24)

        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        var y = (srcY - radiusY).coerceAtLeast(0)
        val yEnd = (srcY + radiusY).coerceAtMost(bitmap.height - 1)
        while (y <= yEnd) {
            var x = (srcX - radiusX).coerceAtLeast(0)
            val xEnd = (srcX + radiusX).coerceAtMost(bitmap.width - 1)
            val step = 3
            while (x <= xEnd) {
                val c = bitmap.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
                x += step
            }
            y += step
        }
        if (count == 0L) return Color.BLACK
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
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
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.argb(if (dark) 28 else 20, 255,255,255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
