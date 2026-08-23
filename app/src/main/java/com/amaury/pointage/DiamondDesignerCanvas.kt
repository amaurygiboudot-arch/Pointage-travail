package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class DiamondDesignerCanvas(context: Context) : View(context) {
    enum class ElementType { ENTRY_BUTTON, FRAME, BACKGROUND }

    data class DesignElement(
        var type: ElementType,
        var x: Float,
        var y: Float,
        var width: Float,
        var height: Float,
        var rotation: Float = 0f,
        var alpha: Float = 1f,
        var lensStrength: Float = .50f,
        var lightAngle: Float = 305f,
        var locked: Boolean = false,
        var visible: Boolean = true,
        var name: String = type.name
    )

    var onSelectionChanged: ((DesignElement?) -> Unit)? = null
    var onDesignChanged: (() -> Unit)? = null

    private val elements = mutableListOf<DesignElement>()
    private var selected: DesignElement? = null
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var entryPreview: GreenDiamondFinalButton? = null

    private enum class DragMode { NONE, MOVE, RESIZE, ROTATE }
    private var dragMode = DragMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f
    private var startRotation = 0f
    private var startPointerAngle = 0f

    init {
        setBackgroundColor(Color.rgb(18, 20, 24))
        post {
            if (elements.isEmpty()) {
                addEntryButton()
            }
        }
    }

    fun allElements(): List<DesignElement> = elements.toList()
    fun selectedElement(): DesignElement? = selected

    fun addEntryButton() {
        val size = min(width.takeIf { it > 0 } ?: 700, height.takeIf { it > 0 } ?: 900) * .42f
        val e = DesignElement(
            ElementType.ENTRY_BUTTON,
            max(20f, (width - size) / 2f),
            max(80f, (height - size) / 2f),
            size,
            size,
            name = "Bouton Entrée"
        )
        elements.add(e)
        select(e)
        invalidate()
    }

    fun addFrame() {
        val e = DesignElement(ElementType.FRAME, 48f, 90f, max(280f, width * .70f), max(180f, height * .30f), alpha = .9f, name = "Cadre")
        elements.add(e)
        select(e)
        invalidate()
    }

    fun addBackground() {
        val e = DesignElement(ElementType.BACKGROUND, 0f, 0f, width.toFloat(), height.toFloat(), alpha = 1f, locked = true, name = "Fond")
        elements.add(0, e)
        select(e)
        invalidate()
    }

    fun duplicateSelected() {
        val s = selected ?: return
        val e = s.copy(x = s.x + 24f, y = s.y + 24f, locked = false, name = s.name + " copie")
        elements.add(e)
        select(e)
        invalidate()
    }

    fun deleteSelected() {
        val s = selected ?: return
        elements.remove(s)
        select(elements.lastOrNull())
        invalidate()
    }

    fun bringForward() {
        val s = selected ?: return
        val i = elements.indexOf(s)
        if (i >= 0 && i < elements.lastIndex) {
            elements.removeAt(i)
            elements.add(i + 1, s)
            invalidate()
            onDesignChanged?.invoke()
        }
    }

    fun sendBackward() {
        val s = selected ?: return
        val i = elements.indexOf(s)
        if (i > 0) {
            elements.removeAt(i)
            elements.add(i - 1, s)
            invalidate()
            onDesignChanged?.invoke()
        }
    }

    fun setSelectedLens(v: Float) { selected?.let { if (it.type == ElementType.ENTRY_BUTTON) { it.lensStrength = v.coerceIn(0f,1f); invalidate(); onDesignChanged?.invoke() } } }
    fun setSelectedAlpha(v: Float) { selected?.let { it.alpha = v.coerceIn(.05f,1f); invalidate(); onDesignChanged?.invoke() } }
    fun setSelectedRotation(v: Float) { selected?.let { it.rotation = v; invalidate(); onDesignChanged?.invoke() } }
    fun setSelectedLightAngle(v: Float) { selected?.let { if (it.type == ElementType.ENTRY_BUTTON) { it.lightAngle = ((v % 360f) + 360f) % 360f; invalidate(); onDesignChanged?.invoke() } } }
    fun toggleLock() { selected?.let { it.locked = !it.locked; onSelectionChanged?.invoke(it); invalidate() } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (e in elements) if (e.visible) drawElement(canvas, e)
        selected?.takeIf { it.visible }?.let { drawSelection(canvas, it) }
    }

    private fun drawElement(canvas: Canvas, e: DesignElement) {
        canvas.save()
        canvas.rotate(e.rotation, e.x + e.width / 2f, e.y + e.height / 2f)
        when (e.type) {
            ElementType.BACKGROUND -> {
                bgPaint.color = Color.argb((255 * e.alpha).toInt(), 12, 18, 28)
                canvas.drawRect(e.x, e.y, e.x + e.width, e.y + e.height, bgPaint)
            }
            ElementType.FRAME -> {
                framePaint.strokeWidth = max(3f, min(e.width, e.height) * .035f)
                framePaint.color = Color.argb((255 * e.alpha).toInt(), 205, 216, 230)
                canvas.drawRoundRect(e.x, e.y, e.x + e.width, e.y + e.height, 24f, 24f, framePaint)
            }
            ElementType.ENTRY_BUTTON -> drawEntryButton(canvas, e)
        }
        canvas.restore()
    }

    private fun drawEntryButton(canvas: Canvas, e: DesignElement) {
        val v = entryPreview ?: GreenDiamondFinalButton(context).also { entryPreview = it }
        v.alpha = e.alpha
        v.setLensStrength(e.lensStrength)
        v.setDiamondLightAngle(e.lightAngle)
        val w = max(1, e.width.toInt())
        val h = max(1, e.height.toInt())
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        canvas.save()
        canvas.translate(e.x, e.y)
        v.draw(canvas)
        canvas.restore()
    }

    private fun drawSelection(canvas: Canvas, e: DesignElement) {
        val r = RectF(e.x, e.y, e.x + e.width, e.y + e.height)
        canvas.save()
        canvas.rotate(e.rotation, r.centerX(), r.centerY())
        selectPaint.pathEffect = if (e.locked) DashPathEffect(floatArrayOf(12f, 8f), 0f) else null
        canvas.drawRect(r, selectPaint)
        val hs = 12f
        canvas.drawCircle(r.right, r.bottom, hs, handlePaint)
        canvas.drawCircle(r.centerX(), r.top - 34f, hs, handlePaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                val hit = findHit(event.x, event.y)
                if (hit != null) select(hit)
                val s = selected ?: return true
                if (s.locked) return true
                startX = s.x; startY = s.y; startW = s.width; startH = s.height; startRotation = s.rotation
                val resizeDistance = hypot(event.x - (s.x + s.width), event.y - (s.y + s.height))
                val rotateDistance = hypot(event.x - (s.x + s.width/2f), event.y - (s.y - 34f))
                dragMode = when {
                    resizeDistance < 52f -> DragMode.RESIZE
                    rotateDistance < 52f -> {
                        startPointerAngle = pointerAngle(event.x, event.y, s)
                        DragMode.ROTATE
                    }
                    else -> DragMode.MOVE
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = selected ?: return true
                if (s.locked) return true
                when (dragMode) {
                    DragMode.MOVE -> { s.x = startX + event.x - downX; s.y = startY + event.y - downY }
                    DragMode.RESIZE -> {
                        s.width = max(72f, startW + event.x - downX)
                        s.height = max(72f, startH + event.y - downY)
                    }
                    DragMode.ROTATE -> s.rotation = startRotation + pointerAngle(event.x, event.y, s) - startPointerAngle
                    else -> Unit
                }
                invalidate(); onDesignChanged?.invoke(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragMode = DragMode.NONE; return true }
        }
        return super.onTouchEvent(event)
    }

    private fun pointerAngle(x: Float, y: Float, e: DesignElement): Float = Math.toDegrees(atan2((y - (e.y + e.height/2f)).toDouble(), (x - (e.x + e.width/2f)).toDouble())).toFloat()

    private fun findHit(x: Float, y: Float): DesignElement? {
        for (i in elements.indices.reversed()) {
            val e = elements[i]
            if (!e.visible) continue
            if (x >= e.x && x <= e.x + e.width && y >= e.y && y <= e.y + e.height) return e
        }
        return null
    }

    private fun select(e: DesignElement?) {
        selected = e
        onSelectionChanged?.invoke(e)
        invalidate()
    }

    fun report(): String = buildString {
        appendLine("DIAMOND DESIGNER REPORT")
        appendLine("elements=${elements.size}")
        elements.forEachIndexed { i, e ->
            appendLine("[$i] name=${e.name}; type=${e.type}; x=${"%.1f".format(e.x)}; y=${"%.1f".format(e.y)}; w=${"%.1f".format(e.width)}; h=${"%.1f".format(e.height)}; rotation=${"%.1f".format(e.rotation)}; alpha=${"%.3f".format(e.alpha)}; lens=${"%.3f".format(e.lensStrength)}; light=${"%.1f".format(e.lightAngle)}; locked=${e.locked}")
        }
    }
}
