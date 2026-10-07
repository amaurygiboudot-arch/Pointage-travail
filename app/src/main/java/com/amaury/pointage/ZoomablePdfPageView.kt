package com.amaury.pointage

import android.content.Context
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/** Zoom stays inside this page; ordinary scrolling remains owned by the document. */
class ZoomablePdfPageView(context: Context) : ImageView(context) {
    private val transform = Matrix()
    private var zoom = 1f
    private var baseScale = 1f
    private var translationXInPage = 0f
    private var translationYInPage = 0f
    private var ready = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return ready
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })
    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                performClick()
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                zoomAt(if (zoom > 1.01f) 1f else 2.5f, event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
                return true
            }
            override fun onScroll(first: MotionEvent?, current: MotionEvent,
                distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress || current.pointerCount > 1) return true
                if (zoom <= 1.01f) return false
                val oldY = translationYInPage
                translationXInPage -= distanceX
                translationYInPage -= distanceY
                applyTransform()
                // At the upper/lower edge, let the document move to another page.
                val verticalEdge = abs(translationYInPage - oldY) < 0.01f && abs(distanceY) > abs(distanceX)
                parent?.requestDisallowInterceptTouchEvent(!verticalEdge)
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        cropToPadding = true
        androidx.core.view.ViewCompat.addAccessibilityAction(this, "Agrandir la page") { _, _ ->
            zoomAt(zoom + .5f, width / 2f, height / 2f); true
        }
        androidx.core.view.ViewCompat.addAccessibilityAction(this, "Réduire la page") { _, _ ->
            zoomAt(zoom - .5f, width / 2f, height / 2f); true
        }
        androidx.core.view.ViewCompat.addAccessibilityAction(this, "Ajuster la page à l’écran") { _, _ ->
            zoomAt(1f, width / 2f, height / 2f); true
        }
        contentDescription = "Page PDF. Pincez pour agrandir, double appui pour agrandir ou réinitialiser."
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val image = drawable ?: return
        val availableWidth = w - paddingLeft - paddingRight
        val availableHeight = h - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        baseScale = min(availableWidth.toFloat() / image.intrinsicWidth,
            availableHeight.toFloat() / image.intrinsicHeight)
        ready = true
        zoom = 1f
        translationXInPage = 0f
        translationYInPage = 0f
        applyTransform()
    }

    fun zoomIn() = zoomAt(zoom + .5f, width / 2f, height / 2f)
    fun zoomOut() = zoomAt(zoom - .5f, width / 2f, height / 2f)
    fun resetZoom() = zoomAt(1f, width / 2f, height / 2f)

    private fun zoomAt(requested: Float, focusX: Float, focusY: Float) {
        if (!ready || !requested.isFinite()) return
        val next = requested.coerceIn(1f, 4f)
        val ratio = next / zoom
        translationXInPage = focusX - paddingLeft - (focusX - paddingLeft - translationXInPage) * ratio
        translationYInPage = focusY - paddingTop - (focusY - paddingTop - translationYInPage) * ratio
        zoom = next
        applyTransform()
    }

    private fun applyTransform() {
        val image = drawable ?: return
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        val imageWidth = image.intrinsicWidth * baseScale * zoom
        val imageHeight = image.intrinsicHeight * baseScale * zoom
        translationXInPage = constrainOffset(translationXInPage, availableWidth, imageWidth)
        translationYInPage = constrainOffset(translationYInPage, availableHeight, imageHeight)
        transform.setScale(baseScale * zoom, baseScale * zoom)
        transform.postTranslate(translationXInPage, translationYInPage)
        imageMatrix = transform
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        internal fun constrainOffset(offset: Float, viewport: Float, image: Float): Float =
            if (image <= viewport) (viewport - image) / 2f
            else offset.coerceIn(viewport - image, 0f)
    }
}
