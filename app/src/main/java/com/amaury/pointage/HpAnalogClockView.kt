package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.engine.CelestialGlobeModeV2
import com.amaury.pointage.v2.engine.CelestialScreenGeometryV2
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import java.util.Calendar
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Horloge HP modulaire : cadran, aiguilles et Terre indépendants.
 *
 * La Terre centrale V2 est désormais un globe orthographique orienté par la
 * position GPS : le point de l'utilisateur est placé au centre de la sphère.
 */
class HpAnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val handBitmap: Bitmap by lazy { HpDesignAssets.hand }
    private val secondBitmap: Bitmap by lazy { HpDesignAssets.secondHand }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val assetGeneration = AtomicLong(0L)
    private var sharpHandBitmap: Bitmap? = null
    private var sharpSecondBitmap: Bitmap? = null
    private var sharpAssetsRequested = false
    private val earthGlobeRenderer = EarthGlobeRendererV2 {
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
    private val celestialPreferences = context.applicationContext.getSharedPreferences(
        CelestialGlobeModeV2.PREFS,
        Context.MODE_PRIVATE
    )
    private var globeMode = CelestialGlobeModeV2.fromStored(
        celestialPreferences.getString(CelestialGlobeModeV2.PREF_KEY_GLOBE_MODE, null)
    )
    private val celestialPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == CelestialGlobeModeV2.PREF_KEY_GLOBE_MODE) {
                globeMode = CelestialGlobeModeV2.fromStored(preferences.getString(key, null))
                earthGlobeRenderer.clearCache()
                postInvalidateOnAnimation()
            }
        }

    private val starDome = CelestialStarLayerRendererV2(context) {
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
    private var celestialState: CelestialTrackerV2.State? = null
    private var celestialSnapshot: CelestialSnapshotV2? = null
    private var hostActivityVisible = false
    private var trackerSubscribed = false

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        celestialPreferences.registerOnSharedPreferenceChangeListener(celestialPreferenceListener)
        globeMode = CelestialGlobeModeV2.fromStored(
            celestialPreferences.getString(CelestialGlobeModeV2.PREF_KEY_GLOBE_MODE, null)
        )
        maybeRequestSharpAssets()
        updateTrackerSubscription()
    }

    /** Suspend aussi l'horloge quand la fenetre reste techniquement visible apres onPause. */
    fun setHostActivityVisible(visible: Boolean) {
        if (hostActivityVisible == visible) return
        hostActivityVisible = visible
        maybeRequestSharpAssets()
        updateTrackerSubscription()
        if (visible) {
            invalidate()
        }
        // En pause, on coupe uniquement l'acquisition. Le dernier snapshot et le
        // dernier globe V2 restent en mémoire pour éviter tout flash du PNG legacy
        // pendant la transition vers l'arrière-plan ou le retour à l'application.
    }

    override fun onDetachedFromWindow() {
        celestialPreferences.unregisterOnSharedPreferenceChangeListener(celestialPreferenceListener)
        if (trackerSubscribed) {
            CelestialTrackerV2.unsubscribe(this)
            trackerSubscribed = false
        }
        earthGlobeRenderer.clearCache()
        celestialSnapshot = null
        celestialState = null
        starDome.clear()
        assetGeneration.incrementAndGet()
        sharpHandBitmap?.takeIf { it !== handBitmap && !it.isRecycled }?.recycle()
        sharpSecondBitmap?.takeIf { it !== secondBitmap && !it.isRecycled }?.recycle()
        sharpHandBitmap = null
        sharpSecondBitmap = null
        sharpAssetsRequested = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) post {
            maybeRequestSharpAssets()
            updateTrackerSubscription()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) post {
            maybeRequestSharpAssets()
            updateTrackerSubscription()
        }
    }

    private fun updateTrackerSubscription() {
        val shouldSubscribe = hostActivityVisible && isAttachedToWindow &&
            alpha > 0f && isShown && windowVisibility == VISIBLE
        if (shouldSubscribe && !trackerSubscribed) {
            trackerSubscribed = true
            CelestialTrackerV2.subscribe(context, this) { state ->
                celestialSnapshot = state.snapshot
                celestialState = state
                starDome.update(state)
                invalidate()
            }
        } else if (!shouldSubscribe && trackerSubscribed) {
            CelestialTrackerV2.unsubscribe(this)
            trackerSubscribed = false
            celestialState = null
            starDome.clear()
            // Conserver le dernier snapshot qualifié : il s'agit uniquement d'un
            // état visuel figé, pas d'une acquisition GPS/capteurs en arrière-plan.
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (alpha <= 0f || width <= 0 || height <= 0) return

        val cx = width * 0.50f
        val cy = screenAnchoredCenterY()
        val safeSpan = CelestialScreenGeometryV2.safeRenderSpan(
            width.toDouble(),
            height.toDouble()
        ).toFloat()
        val faceRadius = safeSpan * 0.40f

        drawFace(canvas, cx, cy, faceRadius)
        starDome.draw(canvas, cx, cy, faceRadius, celestialState)

        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = (now.get(Calendar.HOUR) % 12) + minutes / 60f

        // Même géométrie et mêmes PNG : seule leur résolution de travail est augmentée
        // avant rotation afin d'éviter les marches d'escalier sur grand écran.
        val renderedHand = sharpHandBitmap ?: handBitmap
        val renderedSecond = sharpSecondBitmap ?: secondBitmap
        drawHandPng(canvas, renderedHand, cx, cy, hours * 30f, faceRadius * 0.48f, 0.90f)
        drawHandPng(canvas, renderedHand, cx, cy, minutes * 6f, faceRadius * 0.70f, 0.90f)
        drawHandPng(canvas, renderedSecond, cx, cy, seconds * 6f, faceRadius * 0.78f, 0.88f)

        val earthRadius = max(faceRadius * 0.16f, 13f)
        drawEarthGlobe(canvas, cx, cy, earthRadius)

        // Les vues de compatibilité transparentes (alpha = 0) ne doivent pas
        // entretenir une boucle de rendu à 20 FPS en arrière-plan.
        if (hostActivityVisible && alpha > 0f && isShown && windowVisibility == VISIBLE) {
            postInvalidateDelayed(50L)
        }
    }

    /**
     * Centre l'horloge dans la fenêtre visible et non dans le panneau situé sous
     * les onglets. Ainsi l'apparition/disparition des onglets Accueil ne déplace
     * plus le cadran verticalement.
     */
    private fun screenAnchoredCenterY(): Float {
        if (!isAttachedToWindow || height <= 0) return height * 0.50f

        val visibleFrame = Rect()
        getWindowVisibleDisplayFrame(visibleFrame)
        val location = IntArray(2)
        getLocationInWindow(location)

        val targetWindowY = visibleFrame.exactCenterY()
        val localY = targetWindowY - location[1]
        return localY.coerceIn(height * 0.32f, height * 0.68f)
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        ClockDialRendererV2.draw(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            // The dial owns its spherical sky: do not mix the flat panorama
            // behind it into the same star map.
            backgroundAlpha = 255
        )
    }

    private fun requestSharpAssets() {
        if (sharpAssetsRequested) return
        sharpAssetsRequested = true
        val generation = assetGeneration.incrementAndGet()
        val handSource = handBitmap
        val secondSource = secondBitmap
        bitmapExecutor.execute {
            val handResult = runCatching {
                HighQualityBitmapScalerV2.upscale(handSource, factor = 4)
            }.getOrNull()
            val secondResult = runCatching {
                HighQualityBitmapScalerV2.upscale(secondSource, factor = 4)
            }.getOrNull()
            uiHandler.post {
                if (generation != assetGeneration.get() || !isAttachedToWindow) {
                    handResult?.takeIf { it !== handSource && !it.isRecycled }?.recycle()
                    secondResult?.takeIf { it !== secondSource && !it.isRecycled }?.recycle()
                    return@post
                }
                sharpHandBitmap = handResult
                sharpSecondBitmap = secondResult
                invalidate()
            }
        }
    }

    private fun maybeRequestSharpAssets() {
        if (!hostActivityVisible || !isAttachedToWindow || alpha <= 0f ||
            !isShown || windowVisibility != VISIBLE
        ) return
        requestSharpAssets()
    }


    /**
     * Globe GPS V2.
     *
     * Quand une localisation qualifiée existe, sa latitude/longitude est la face
     * avant du globe et le marqueur rouge est exactement au centre. Si aucune
     * localisation fiable n'est disponible, on garde temporairement l'ancien
     * symbole Terre plutôt que d'afficher un pays arbitraire comme position réelle.
     */
    private fun drawEarthGlobe(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val rendered = earthGlobeRenderer.draw(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            snapshot = celestialSnapshot,
            mode = globeMode
        )
        if (!rendered) {
            drawFallbackEarthPng(canvas, EarthDesignAsset.bitmap, cx, cy, radius)
        }
    }

    private fun drawFallbackEarthPng(
        canvas: Canvas,
        bitmap: Bitmap,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val diameter = radius * 2f
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstWidth: Float
        val dstHeight: Float
        if (aspect >= 1f) {
            dstWidth = diameter
            dstHeight = diameter / aspect
        } else {
            dstHeight = diameter
            dstWidth = diameter * aspect
        }

        val rect = RectF(
            cx - dstWidth / 2f,
            cy - dstHeight / 2f,
            cx + dstWidth / 2f,
            cy + dstHeight / 2f
        )
        bitmapPaint.alpha = 190
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    private fun drawHandPng(
        canvas: Canvas,
        bitmap: Bitmap,
        cx: Float,
        cy: Float,
        angleDeg: Float,
        tipLength: Float,
        pivotYRatio: Float
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return

        val srcPivotX = bitmap.width * 0.50f
        val srcPivotY = bitmap.height * pivotYRatio
        val scale = tipLength / srcPivotY.coerceAtLeast(1f)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angleDeg)

        val dst = RectF(
            -srcPivotX * scale,
            -srcPivotY * scale,
            (bitmap.width - srcPivotX) * scale,
            (bitmap.height - srcPivotY) * scale
        )
        bitmapPaint.alpha = 255
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        canvas.restore()
    }

    companion object {
        private val bitmapExecutor: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-ClockBitmaps").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}
