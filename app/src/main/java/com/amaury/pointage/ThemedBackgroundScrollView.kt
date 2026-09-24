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
    private val celestialHomeSky = CelestialHomeSkyBackgroundRendererV2(context) {
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
    private var celestialHomeState: com.amaury.pointage.v2.CelestialTrackerV2.State? = null
    private var celestialAmbientState = com.amaury.pointage.v2.CelestialAmbientLightV2.currentState()
    private var celestialRenderState: com.amaury.pointage.v2.engine.CelestialRenderStateV2? = null
    private var celestialHomeActive = false
    private var celestialTrackerSubscribed = false
    private var celestialAmbientSubscribed = false
    private var cachedImage: Bitmap? = null
    private var cachedImageToken: String? = null
    private var cachedTextColor: Int? = null
    private var cachedShadowColor: Int? = null

    fun setCelestialHomeActive(active: Boolean) {
        if (celestialHomeActive == active) return
        celestialHomeActive = active
        updateCelestialSubscription()
        if (!active) {
            celestialHomeState = null
            celestialRenderState = null
            celestialHomeSky.clear()
        }
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateCelestialSubscription()
    }

    override fun onDetachedFromWindow() {
        if (celestialTrackerSubscribed) {
            com.amaury.pointage.v2.CelestialTrackerV2.unsubscribe(this)
            celestialTrackerSubscribed = false
        }
        if (celestialAmbientSubscribed) {
            com.amaury.pointage.v2.CelestialAmbientLightV2.unsubscribe(this)
            celestialAmbientSubscribed = false
        }
        celestialHomeState = null
        celestialRenderState = null
        celestialHomeSky.clear()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) updateCelestialSubscription()
    }

    private fun updateCelestialSubscription() {
        val shouldSubscribe = celestialHomeActive && isAttachedToWindow &&
            windowVisibility == VISIBLE && isShown
        if (shouldSubscribe && !celestialTrackerSubscribed) {
            celestialTrackerSubscribed = true
            com.amaury.pointage.v2.CelestialTrackerV2.subscribe(context, this) { state ->
                celestialHomeState = state
                celestialHomeSky.update(state)
                postInvalidateOnAnimation()
            }
        } else if (!shouldSubscribe && celestialTrackerSubscribed) {
            com.amaury.pointage.v2.CelestialTrackerV2.unsubscribe(this)
            celestialTrackerSubscribed = false
        }

        if (shouldSubscribe && !celestialAmbientSubscribed) {
            celestialAmbientSubscribed = true
            com.amaury.pointage.v2.CelestialAmbientLightV2.subscribe(context, this) { state ->
                celestialAmbientState = state
                postInvalidateOnAnimation()
            }
        } else if (!shouldSubscribe && celestialAmbientSubscribed) {
            com.amaury.pointage.v2.CelestialAmbientLightV2.unsubscribe(this)
            celestialAmbientSubscribed = false
            celestialAmbientState = com.amaury.pointage.v2.CelestialAmbientLightV2.currentState()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, scrollY.toFloat())
        val celestialState = celestialHomeState
        val hasQualifiedSky = celestialHomeActive &&
            celestialState?.snapshot != null &&
            celestialState.locationQuality == com.amaury.pointage.v2.engine.CelestialLocationQualityV2.VALID

        if (hasQualifiedSky) {
            val snapshot = celestialState.snapshot!!
            val weather = com.amaury.pointage.v2.CelestialWeatherContextV2
                .currentStateFor(snapshot)
            val renderState = com.amaury.pointage.v2.engine.CelestialRenderStateFactoryV2.build(
                snapshot = snapshot,
                weather = weather,
                ambient = com.amaury.pointage.v2.CelestialAmbientLightV2.currentState(),
                orientationQuality = celestialState.headingQuality,
                nowElapsedMs = android.os.SystemClock.elapsedRealtime()
            )
            celestialRenderState = renderState
            celestialHomeSky.draw(
                canvas = canvas,
                width = width.toFloat(),
                height = height.toFloat(),
                state = celestialState,
                renderState = renderState
            )
        } else {
            celestialRenderState = null
            // Fail-closed : sans position/éphéméride qualifiée, ne jamais inventer
            // un ciel de jour ou de nuit. On conserve simplement le fond normal.
            drawHpBackground(canvas)
        }
        canvas.restore()
        applyGlobalAdaptiveTextColor()
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

    private fun drawHpBackground(canvas: Canvas) {
        val file = selectedBackgroundFile()
        if (file != null) {
            drawSelectedImage(canvas, file)
            return
        }

        // Règle d'architecture : la matière d'un bouton appartient au bouton uniquement.
        // Un thème peut changer la palette du fond de l'application, mais il ne réutilise
        // jamais automatiquement une texture/image destinée à un bouton ou à son cadre.
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
            cachedImage?.recycle()
            cachedImage = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
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
        val celestial = celestialRenderState
        if (celestialHomeActive && celestial != null) {
            val useDark = celestial.solarLightLevel >= 0.58 && celestial.nightLevel < 0.20
            textColor = if (useDark) Color.rgb(12, 18, 24) else Color.WHITE
            shadowColor = if (useDark) {
                Color.argb(200, 255, 255, 255)
            } else {
                Color.argb(225, 0, 0, 0)
            }
        } else if (hasImage) {
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
        applyTextColorRecursively(this, textColor, shadowColor)
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
        return if (count == 0) {
            GlobalBackgroundStats(0f, 0f, 1f)
        } else {
            GlobalBackgroundStats(sum / count, bright.toFloat() / count, dark.toFloat() / count)
        }
    }

    private fun applyTextColorRecursively(view: View, color: Int, shadow: Int) {
        // Les onglets possèdent leur propre palette active/inactive gérée par MainActivity.
        if (view.id == R.id.navigationTabs) return

        if (view is TextView) {
            view.setTextColor(color)
            view.setShadowLayer(3.8f, 0f, 1.1f, shadow)
            if (view is EditText) {
                view.setHintTextColor(
                    if (color == Color.WHITE) Color.rgb(225, 225, 225) else Color.rgb(55, 55, 55)
                )
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTextColorRecursively(view.getChildAt(i), color, shadow)
            }
        }
    }

    private fun isDark(color: Int) =
        ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000) < 155
}
