package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private var lastAppliedStyleToken: String? = null
    private var textStyleDirty = true

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Des descendants peuvent être ajoutés/recomposés sans changer les bornes
        // du ScrollView. Chaque passe de layout doit donc rendre le style à revoir.
        textStyleDirty = true
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, scrollY.toFloat())
        drawHpBackground(canvas)
        canvas.restore()

        val styleToken = currentStyleToken()
        if (textStyleDirty || styleToken != lastAppliedStyleToken) {
            applyGlobalAdaptiveTextColor()
            lastAppliedStyleToken = currentStyleToken()
            textStyleDirty = false
        }
        super.dispatchDraw(canvas)
    }

    /**
     * Seule une image explicitement choisie comme fond D'APPLICATION peut être lue ici.
     * Les images/textures de boutons et de cadres n'entrent jamais dans ce circuit.
     */
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

    private fun currentStyleToken(): String {
        val theme = AppThemeCatalog.current(context)
        val dark = ThemeDayNight.isDark(context)
        val file = selectedBackgroundFile()
        if (file != null) {
            // Même avec la même photo, un changement clair/sombre ou de thème peut
            // recolorer d'autres composants : l'adaptation photo doit être réappliquée.
            return "image:${file.absolutePath}:${file.lastModified()}:${file.length()}:$dark:${theme.id}:${theme.accent}:${theme.accentLight}"
        }
        val background = if (dark) theme.darkBackground else theme.lightBackground
        return "palette:$dark:$background:${theme.id}:${theme.accent}:${theme.accentLight}"
    }

    private fun drawHpBackground(canvas: Canvas) {
        val file = selectedBackgroundFile()
        if (file != null && drawSelectedImage(canvas, file)) return

        val theme = AppThemeCatalog.current(context)
        val dark = ThemeDayNight.isDark(context)
        canvas.drawColor(if (dark) theme.darkBackground else theme.lightBackground)
    }

    private fun drawSelectedImage(canvas: Canvas, file: File): Boolean {
        ensureImage(file)
        val bmp = cachedImage ?: return false
        val scale = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = (bmp.width * scale).toInt()
        val dh = (bmp.height * scale).toInt()
        val left = (width - dw) / 2
        val top = (height - dh) / 2
        canvas.drawBitmap(bmp, null, Rect(left, top, left + dw, top + dh), paint)
        return true
    }

    private fun ensureImage(file: File) {
        val token = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        // Le token est mémorisé même si le décodage échoue : une image corrompue
        // ou une allocation refusée ne doit pas être redécodée à chaque frame.
        if (cachedImageToken == token) return

        cachedImage?.recycle()
        cachedImage = decodeSampled(file)
        cachedImageToken = token
        cachedTextColor = null
        cachedShadowColor = null
        textStyleDirty = true
    }

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val metrics = resources.displayMetrics
        val targetWidth = max(width, metrics.widthPixels).coerceAtLeast(1)
        val targetHeight = max(height, metrics.heightPixels).coerceAtLeast(1)
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inJustDecodeBounds = false
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    private fun applyGlobalAdaptiveTextColor() {
        val file = selectedBackgroundFile()
        if (file != null) {
            ensureImage(file)
            if (cachedImage != null) clearPhotoPanels(this, false)
        }
        val hasImage = file != null && cachedImage != null

        val textColor: Int
        val shadowColor: Int
        if (hasImage) {
            if (cachedTextColor == null || cachedShadowColor == null) {
                cachedImage?.let {
                    val useDark = chooseDarkText(globalBackgroundStats(it))
                    cachedTextColor = if (useDark) Color.rgb(8, 8, 8) else Color.WHITE
                    cachedShadowColor = if (useDark) {
                        Color.argb(225, 255, 255, 255)
                    } else {
                        Color.argb(235, 0, 0, 0)
                    }
                }
            }
            textColor = cachedTextColor ?: Color.WHITE
            shadowColor = cachedShadowColor ?: Color.BLACK
        } else {
            val theme = AppThemeCatalog.current(context)
            val background = if (ThemeDayNight.isDark(context)) theme.darkBackground else theme.lightBackground
            val useDark = !isDark(background)
            textColor = if (useDark) Color.rgb(8, 8, 8) else Color.WHITE
            shadowColor = if (useDark) {
                Color.argb(210, 255, 255, 255)
            } else {
                Color.argb(220, 0, 0, 0)
            }
        }
        applyTextColorRecursively(this, textColor, shadowColor, hasImage)
    }

    private fun clearPhotoPanels(view: View, insideEnterprise: Boolean) {
        val nowInside = insideEnterprise || view is EnterpriseLookupView
        if (
            nowInside &&
            view is ViewGroup &&
            view !is Button &&
            view !is EditText &&
            view !is Switch &&
            view.background != null
        ) {
            // Une fois le panneau rendu transparent, ne réinstalle pas un ColorDrawable
            // transparent à chaque passe : cela peut redemander un layout et recréer une
            // boucle layout -> style -> layout pendant le défilement.
            view.background = null
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
        return if (count == 0) {
            GlobalBackgroundStats(0f, 0f, 1f)
        } else {
            GlobalBackgroundStats(sum / count, bright.toFloat() / count, dark.toFloat() / count)
        }
    }

    private fun applyTextColorRecursively(view: View, color: Int, shadow: Int, photoBackground: Boolean) {
        if (view.id == R.id.navigationTabs) return

        if (view is TextView) {
            view.setTextColor(color)
            if (photoBackground) view.setShadowLayer(3.8f, 0f, 1.1f, shadow)
            else view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            if (view is EditText) {
                view.setHintTextColor(
                    if (color == Color.WHITE) Color.rgb(225, 225, 225) else Color.rgb(55, 55, 55)
                )
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTextColorRecursively(view.getChildAt(i), color, shadow, photoBackground)
            }
        }
    }

    private fun isDark(color: Int) =
        ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000) < 155
}
