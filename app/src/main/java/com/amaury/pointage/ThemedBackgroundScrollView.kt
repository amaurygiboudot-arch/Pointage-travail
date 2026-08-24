package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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

    private data class GlobalBackgroundStats(
        val averageLuma: Float,
        val brightRatio: Float,
        val darkRatio: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedImage: Bitmap? = null
    private var cachedImageToken: String? = null
    private var cachedTextColor: Int? = null
    private var cachedShadowColor: Int? = null

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, scrollY.toFloat())
        drawHpBackground(canvas)
        canvas.restore()
        applyGlobalAdaptiveTextColor()
        super.dispatchDraw(canvas)
    }

    private fun selectedBackgroundFile(): File? {
        val prefs = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("custom_image_bg", false)) return null

        val file = File(context.filesDir, AppearanceManager.BACKGROUND_FILE)
        if (!file.exists() || file.length() <= 0L) {
            prefs.edit().putBoolean("custom_image_bg", false).apply()
            return null
        }
        return file
    }

    private fun drawHpBackground(canvas: Canvas) {
        val file = selectedBackgroundFile()
        if (file != null) {
            drawSelectedImage(canvas, file)
            return
        }
        val theme = AppThemeCatalog.current(context)
        val dark = ThemeDayNight.isDark(context)
        canvas.drawColor(if (dark) theme.darkBackground else theme.lightBackground)
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
            val metrics = resources.displayMetrics
            val targetWidth = width.takeIf { it > 0 } ?: metrics.widthPixels
            val targetHeight = height.takeIf { it > 0 } ?: metrics.heightPixels
            val decoded = BackgroundImageSampling.decode(file, targetWidth, targetHeight)

            cachedImage?.recycle()
            cachedImage = decoded
            cachedImageToken = token
            cachedTextColor = null
            cachedShadowColor = null
        }
    }

    private fun applyGlobalAdaptiveTextColor() {
        val file = selectedBackgroundFile()
        val hasImage = file != null
        if (file != null) {
            clearPhotoPanels(this, false)
            ensureImage(file)
        }

        val textColor: Int
        val shadowColor: Int
        if (hasImage) {
            if (cachedTextColor == null || cachedShadowColor == null) {
                cachedImage?.let {
                    val useDark = chooseDarkText(globalBackgroundStats(it))
                    cachedTextColor = if (useDark) Color.rgb(8, 8, 8) else Color.WHITE
                    cachedShadowColor = if (useDark) Color.argb(225, 255, 255, 255) else Color.argb(235, 0, 0, 0)
                }
            }
            textColor = cachedTextColor ?: Color.WHITE
            shadowColor = cachedShadowColor ?: Color.BLACK
        } else {
            val theme = AppThemeCatalog.current(context)
            val background = if (ThemeDayNight.isDark(context)) theme.darkBackground else theme.lightBackground
            val useDark = !isDark(background)
            textColor = if (useDark) Color.rgb(8, 8, 8) else Color.WHITE
            // Not used without a photo. Passing a defined value keeps this routine simple,
            // while applyTextColorRecursively explicitly clears any previously applied shadow.
            shadowColor = Color.TRANSPARENT
        }
        applyTextColorRecursively(this, textColor, shadowColor, hasImage)
    }

    private fun clearPhotoPanels(view: View, insideEnterprise: Boolean) {
        val nowInside = insideEnterprise || view is EnterpriseLookupView
        if (nowInside && view is ViewGroup && view !is Button && view !is EditText && view !is Switch) {
            view.background = null
            view.setBackgroundColor(Color.TRANSPARENT)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) clearPhotoPanels(view.getChildAt(i), nowInside)
        }
    }

    private fun chooseDarkText(s: GlobalBackgroundStats): Boolean =
        (s.averageLuma * .70f + s.brightRatio * .22f + (1f - s.darkRatio) * .08f)
            .coerceIn(0f, 1f) >= .53f

    private fun globalBackgroundStats(bitmap: Bitmap): GlobalBackgroundStats {
        val sx = (bitmap.width / 48).coerceAtLeast(1)
        val sy = (bitmap.height / 72).coerceAtLeast(1)
        var sum = 0f
        var bright = 0
        var dark = 0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val l = (.2126f * Color.red(c) + .7152f * Color.green(c) + .0722f * Color.blue(c)) / 255f
                sum += l
                if (l >= .62f) bright++
                if (l <= .36f) dark++
                count++
                x += sx
            }
            y += sy
        }
        return if (count == 0) GlobalBackgroundStats(0f, 0f, 1f)
        else GlobalBackgroundStats(sum / count, bright.toFloat() / count, dark.toFloat() / count)
    }

    private fun applyTextColorRecursively(view: View, color: Int, shadow: Int, photoActive: Boolean) {
        // Les onglets, boutons et switches sont des composants sémantiques :
        // leur palette active/inactive/entrée/pause/sortie ne doit jamais être
        // remplacée par la couleur globale du fond.
        if (view.id == R.id.navigationTabs) return

        when (view) {
            is Button, is Switch -> Unit
            is EditText -> {
                view.setTextColor(color)
                if (photoActive) view.setShadowLayer(3.8f, 0f, 1.1f, shadow) else view.clearShadowLayer()
                view.setHintTextColor(if (color == Color.WHITE) Color.rgb(225, 225, 225) else Color.rgb(55, 55, 55))
            }
            is TextView -> {
                // Les couleurs chromatiques (rouge, orange, vert, or, accents,
                // erreurs) sont considérées sémantiques et préservées. Seuls les
                // textes neutres noir/blanc/gris sont réellement adaptatifs.
                if (isNeutralTextColor(view.currentTextColor)) {
                    view.setTextColor(color)
                    if (photoActive) view.setShadowLayer(3.8f, 0f, 1.1f, shadow) else view.clearShadowLayer()
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTextColorRecursively(view.getChildAt(i), color, shadow, photoActive)
        }
    }

    private fun isNeutralTextColor(color: Int): Boolean {
        val maxChannel = maxOf(Color.red(color), Color.green(color), Color.blue(color))
        val minChannel = minOf(Color.red(color), Color.green(color), Color.blue(color))
        return maxChannel - minChannel <= 36
    }

    private fun isDark(color: Int) =
        ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000) < 155
}
