package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Animated top-down diorama for the delivery story.
 *
 * Movement is decorative only: it never advances or changes campaign state.
 */
internal class ObjectiveDeliveryStorySceneView(
    context: Context,
    private val scene: ObjectiveDeliverySceneModel
) : LinearLayout(context) {
    private val characterChips = mutableListOf<TextView>()
    private lateinit var sceneCanvas: ObjectiveDeliverySceneCanvas
    private lateinit var selectedPersonDetails: TextView

    init {
        orientation = VERTICAL
        setPadding(sceneDp(14), sceneDp(12), sceneDp(14), sceneDp(12))
        background = roundedBackground(0xFF163841.toInt(), sceneDp(18).toFloat())
        contentDescription = "Scène animée du chapitre ${scene.chapter}"

        addView(sceneText(
            "LES COULISSES DU CHAPITRE ${scene.chapter}",
            11f,
            0xFF93D8CB.toInt(),
            bold = true
        ), sceneLayout(bottom = 4))
        addView(sceneText(scene.headline, 19f, Color.WHITE, bold = true), sceneLayout(bottom = 3))
        addView(sceneText(scene.story, 14f, 0xFFE1EFEC.toInt()), sceneLayout(bottom = 10))

        sceneCanvas = ObjectiveDeliverySceneCanvas(context, scene).apply {
            contentDescription = "Vue de dessus animée : ${scene.zoneLabel}. Touche un personnage pour voir son rôle."
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addView(FrameLayout(context).apply {
            background = roundedBackground(0xFFD8E9E6.toInt(), sceneDp(14).toFloat())
            clipToOutline = true
            addView(sceneCanvas, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                sceneDp(222)
            ))
        }, sceneLayout(bottom = 8))

        selectedPersonDetails = sceneText("", 13f, Color.WHITE)
        selectedPersonDetails.setPadding(sceneDp(3), sceneDp(2), sceneDp(3), sceneDp(8))
        addView(selectedPersonDetails, sceneLayout())

        val chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chipScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scene.people.forEachIndexed { index, person ->
            val chip = TextView(context).apply {
                gravity = Gravity.CENTER
                minHeight = sceneDp(48)
                setPadding(sceneDp(12), sceneDp(6), sceneDp(12), sceneDp(6))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                text = moodFace(person.mood) + " " + person.name
                isClickable = true
                isFocusable = true
                contentDescription = "Sélectionner ${person.name}, ${person.role}"
                setOnClickListener { selectPerson(index) }
            }
            characterChips += chip
            chipRow.addView(chip, LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                sceneDp(48)
            ).apply { marginEnd = sceneDp(7) })
        }
        addView(chipScroller, sceneLayout(top = 2))
        sceneCanvas.onPersonSelected = ::selectPerson
        selectPerson(0)
    }

    private fun selectPerson(index: Int) {
        if (scene.people.isEmpty()) return
        val safeIndex = index.coerceIn(scene.people.indices)
        sceneCanvas.selectedIndex = safeIndex
        val person = scene.people[safeIndex]
        selectedPersonDetails.text =
            "${moodFace(person.mood)} ${person.name} • ${person.role}\n${person.task}"
        selectedPersonDetails.contentDescription =
            "${person.name}, ${person.role}. ${person.task} Humeur : ${moodLabel(person.mood)}."
        characterChips.forEachIndexed { chipIndex, chip ->
            val selected = chipIndex == safeIndex
            chip.isSelected = selected
            chip.setTextColor(if (selected) 0xFF163841.toInt() else Color.WHITE)
            chip.background = roundedBackground(
                if (selected) 0xFFE8F7F2.toInt() else 0xFF28545C.toInt(),
                sceneDp(24).toFloat()
            )
            chip.contentDescription = (if (selected) "Sélectionné : " else "Sélectionner : ") +
                "${scene.people[chipIndex].name}, ${scene.people[chipIndex].role}"
        }
        sceneCanvas.contentDescription =
            "Vue de dessus animée : ${scene.zoneLabel}. " +
                "${person.name}, ${person.role}, sélectionné. ${person.task}"
    }

    private fun sceneText(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun sceneLayout(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = sceneDp(top)
            bottomMargin = sceneDp(bottom)
        }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun sceneDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun moodFace(mood: ObjectiveDeliverySceneMood) =
        if (mood == ObjectiveDeliverySceneMood.ANGRY) "😠" else "🙂"

    private fun moodLabel(mood: ObjectiveDeliverySceneMood) =
        if (mood == ObjectiveDeliverySceneMood.ANGRY) "en colère" else "de bonne humeur"
}

private class ObjectiveDeliverySceneCanvas(
    context: Context,
    private val scene: ObjectiveDeliverySceneModel
) : View(context) {
    var selectedIndex: Int = 0
    var onPersonSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var animationStart = 0L
    private var downX = 0f
    private var downY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val animateFrame = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE || windowVisibility != VISIBLE) return
            invalidate()
            postDelayed(this, 55L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationStart = SystemClock.uptimeMillis()
        removeCallbacks(animateFrame)
        post(animateFrame)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animateFrame)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            removeCallbacks(animateFrame)
            post(animateFrame)
        } else {
            removeCallbacks(animateFrame)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFD8E9E6.toInt())
        if (width <= 0 || height <= 0) return

        val bounds = floorBounds()
        drawRoom(canvas, bounds)
        scene.fixtures.forEach { drawFixture(canvas, bounds, it) }

        val elapsed = (SystemClock.uptimeMillis() - animationStart) / 1000f
        scene.people.forEachIndexed { index, person ->
            val point = characterPosition(index, elapsed, bounds)
            val bob = sin((elapsed * 4.2f + index).toDouble()).toFloat() * dp(1.5f)
            drawPerson(canvas, point.first, point.second + bob, person, index == selectedIndex, elapsed, index)
        }
        drawZonePill(canvas, bounds)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = sqrt(
                    ((event.x - downX) * (event.x - downX) +
                        (event.y - downY) * (event.y - downY)).toDouble()
                )
                if (moved <= dp(12f)) {
                    val bounds = floorBounds()
                    val elapsed = (SystemClock.uptimeMillis() - animationStart) / 1000f
                    val hitRadius = dp(31f)
                    var bestIndex = -1
                    var bestDistance = Float.MAX_VALUE
                    scene.people.indices.forEach { index ->
                        val point = characterPosition(index, elapsed, bounds)
                        val distance = sqrt(
                            ((event.x - point.first) * (event.x - point.first) +
                                (event.y - point.second) * (event.y - point.second)).toDouble()
                        ).toFloat()
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestIndex = index
                        }
                    }
                    if (bestIndex >= 0 && bestDistance <= hitRadius) {
                        onPersonSelected?.invoke(bestIndex)
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun floorBounds(): RectF = RectF(
        width * 0.035f,
        height * 0.08f,
        width * 0.965f,
        height * 0.92f
    )

    private fun drawRoom(canvas: Canvas, bounds: RectF) {
        fill.color = 0xFFBED0CD.toInt()
        canvas.drawRoundRect(
            RectF(bounds.left + dp(2f), bounds.top + dp(6f), bounds.right + dp(2f), bounds.bottom + dp(6f)),
            dp(13f), dp(13f), fill
        )

        fill.color = 0xFFF4F7F2.toInt()
        canvas.drawRoundRect(bounds, dp(13f), dp(13f), fill)

        val wall = dp(8f)
        fill.color = 0xFF4A7776.toInt()
        canvas.drawRoundRect(
            RectF(bounds.left, bounds.top, bounds.right, bounds.top + wall),
            dp(12f), dp(12f), fill
        )
        fill.color = 0xFF739492.toInt()
        canvas.drawRect(bounds.left, bounds.top + wall, bounds.left + dp(5f), bounds.bottom - dp(12f), fill)
        canvas.drawRect(bounds.right - dp(5f), bounds.top + wall, bounds.right, bounds.bottom - dp(12f), fill)

        stroke.color = 0xFFDDE7E3.toInt()
        stroke.strokeWidth = dp(1f)
        for (i in 1..5) {
            val x = bounds.left + bounds.width() * i / 6f
            canvas.drawLine(x, bounds.top + wall, x, bounds.bottom - dp(12f), stroke)
        }
        for (i in 1..3) {
            val y = bounds.top + wall + (bounds.height() - wall - dp(12f)) * i / 4f
            canvas.drawLine(bounds.left + dp(5f), y, bounds.right - dp(5f), y, stroke)
        }

        fill.color = 0xFFE5EEEA.toInt()
        canvas.drawRect(bounds.left + dp(5f), bounds.bottom - dp(18f), bounds.right - dp(5f), bounds.bottom - dp(5f), fill)
        fill.color = 0xFF9AAEAA.toInt()
        canvas.drawRect(bounds.left + dp(5f), bounds.bottom - dp(18f), bounds.right - dp(5f), bounds.bottom - dp(15f), fill)
    }

    private fun drawFixture(canvas: Canvas, bounds: RectF, fixture: ObjectiveDeliverySceneFixture) {
        val left = bounds.left + bounds.width() * fixture.x
        val top = bounds.top + dp(24f) + (bounds.height() - dp(38f)) * fixture.y
        val objectWidth = bounds.width() * fixture.width
        val objectDepth = bounds.height() * fixture.depth
        val lift = dp(fixture.heightDp)
        val body = RectF(left, top - lift, left + objectWidth, top + objectDepth)

        when (fixture.kind) {
            ObjectiveDeliverySceneFixture.Kind.VAN -> drawVan(canvas, body, fixture.color)
            ObjectiveDeliverySceneFixture.Kind.BARRIER -> drawBarrier(canvas, body, fixture.color)
            ObjectiveDeliverySceneFixture.Kind.RACK -> drawRack(canvas, body, fixture.color)
            ObjectiveDeliverySceneFixture.Kind.MACHINE,
            ObjectiveDeliverySceneFixture.Kind.DESK,
            ObjectiveDeliverySceneFixture.Kind.BLOCK -> drawBlock(canvas, body, fixture)
        }
    }

    private fun drawBlock(canvas: Canvas, body: RectF, fixture: ObjectiveDeliverySceneFixture) {
        fill.color = 0x330F3940
        canvas.drawRoundRect(
            RectF(body.left + dp(3f), body.bottom - dp(2f), body.right + dp(3f), body.bottom + dp(5f)),
            dp(5f), dp(5f), fill
        )
        fill.color = darker(fixture.color, 0.72f)
        canvas.drawRect(body.left, body.top + dp(7f), body.right, body.bottom, fill)
        fill.color = darker(fixture.color, 0.84f)
        canvas.drawRect(body.right - dp(7f), body.top + dp(2f), body.right, body.bottom - dp(2f), fill)
        fill.color = lighter(fixture.color, 1.12f)
        canvas.drawRoundRect(
            RectF(body.left, body.top, body.right - dp(5f), body.bottom - dp(5f)),
            dp(5f), dp(5f), fill
        )
        text.color = 0xFF183943.toInt()
        text.textSize = dp(7f)
        canvas.drawText(fixture.label, body.centerX(), body.top + dp(12f), text)
        if (fixture.kind == ObjectiveDeliverySceneFixture.Kind.MACHINE) {
            fill.color = 0xFFB8E5D9.toInt()
            canvas.drawRoundRect(
                RectF(body.left + dp(7f), body.top + dp(14f), body.right - dp(11f), body.bottom - dp(7f)),
                dp(3f), dp(3f), fill
            )
        } else if (fixture.kind == ObjectiveDeliverySceneFixture.Kind.DESK) {
            fill.color = 0xFFFFF5D8.toInt()
            canvas.drawRoundRect(
                RectF(body.left + dp(7f), body.top + dp(14f), body.right - dp(12f), body.bottom - dp(8f)),
                dp(2f), dp(2f), fill
            )
        }
    }

    private fun drawRack(canvas: Canvas, body: RectF, color: Int) {
        fill.color = darker(color, 0.75f)
        canvas.drawRoundRect(body, dp(3f), dp(3f), fill)
        fill.color = lighter(color, 1.15f)
        for (i in 0..2) {
            val y = body.top + dp(7f) + i * dp(9f)
            canvas.drawRect(body.left + dp(3f), y, body.right - dp(3f), y + dp(3f), fill)
        }
        fill.color = 0xFFE8C46E.toInt()
        for (i in 0..2) {
            val x = body.left + dp(8f) + i * dp(10f)
            canvas.drawRoundRect(
                RectF(x, body.top + dp(4f), x + dp(6f), body.bottom - dp(2f)),
                dp(2f), dp(2f), fill
            )
        }
    }

    private fun drawVan(canvas: Canvas, body: RectF, color: Int) {
        fill.color = 0x440D303A
        canvas.drawRoundRect(
            RectF(body.left + dp(4f), body.bottom - dp(2f), body.right + dp(3f), body.bottom + dp(5f)),
            dp(7f), dp(7f), fill
        )
        fill.color = 0xFF263F4B.toInt()
        canvas.drawRoundRect(
            RectF(body.left + dp(5f), body.bottom - dp(2f), body.left + dp(16f), body.bottom + dp(5f)),
            dp(4f), dp(4f), fill
        )
        canvas.drawRoundRect(
            RectF(body.right - dp(17f), body.bottom - dp(2f), body.right - dp(6f), body.bottom + dp(5f)),
            dp(4f), dp(4f), fill
        )
        fill.color = color
        canvas.drawRoundRect(body, dp(8f), dp(8f), fill)
        fill.color = 0xFFDDF3F0.toInt()
        canvas.drawRoundRect(
            RectF(body.left + body.width() * 0.55f, body.top + dp(3f), body.right - dp(4f), body.centerY()),
            dp(4f), dp(4f), fill
        )
        text.color = 0xFF173842.toInt()
        text.textSize = dp(7f)
        canvas.drawText("LIVRAISON", body.centerX(), body.bottom - dp(5f), text)
    }

    private fun drawBarrier(canvas: Canvas, body: RectF, color: Int) {
        fill.color = 0x55304A4B
        canvas.drawRoundRect(
            RectF(body.left + dp(2f), body.bottom - dp(1f), body.right + dp(3f), body.bottom + dp(4f)),
            dp(3f), dp(3f), fill
        )
        fill.color = 0xFF405B60.toInt()
        canvas.drawRect(body.left + dp(4f), body.top + dp(5f), body.left + dp(8f), body.bottom + dp(5f), fill)
        canvas.drawRect(body.right - dp(8f), body.top + dp(5f), body.right - dp(4f), body.bottom + dp(5f), fill)
        fill.color = 0xFFF7F1E5.toInt()
        canvas.drawRoundRect(body, dp(4f), dp(4f), fill)
        fill.color = color
        canvas.drawRect(body.left + dp(2f), body.centerY() - dp(3f), body.right - dp(2f), body.centerY() + dp(3f), fill)
        text.color = Color.WHITE
        text.textSize = dp(6f)
        canvas.drawText("SÉCURITÉ", body.centerX(), body.centerY() + dp(2f), text)
    }

    private fun drawZonePill(canvas: Canvas, bounds: RectF) {
        val label = scene.zoneLabel
        fill.color = 0xEEFFFFFF.toInt()
        val pill = RectF(bounds.left + dp(8f), bounds.top + dp(13f), bounds.left + dp(8f) + dp(112f), bounds.top + dp(34f))
        canvas.drawRoundRect(pill, dp(10f), dp(10f), fill)
        text.color = 0xFF315C5D.toInt()
        text.textSize = dp(7.5f)
        canvas.drawText(label, pill.centerX(), pill.centerY() + dp(2.5f), text)
    }

    private fun drawPerson(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        person: ObjectiveDeliveryScenePerson,
        selected: Boolean,
        elapsed: Float,
        index: Int
    ) {
        val headY = cy - dp(19f)
        val headRadius = dp(9f)

        if (selected) {
            stroke.color = 0xFF247A72.toInt()
            stroke.strokeWidth = dp(2f)
            canvas.drawCircle(cx, cy - dp(7f), dp(24f), stroke)
            fill.color = 0x33247A72
            canvas.drawCircle(cx, cy - dp(7f), dp(21f), fill)
        }

        fill.color = 0x3D15383D
        canvas.drawOval(RectF(cx - dp(13f), cy + dp(7f), cx + dp(13f), cy + dp(13f)), fill)

        stroke.color = darker(person.shirtColor, 0.55f)
        stroke.strokeWidth = dp(4f)
        canvas.drawLine(cx - dp(4f), cy - dp(3f), cx - dp(7f), cy + dp(9f), stroke)
        canvas.drawLine(cx + dp(4f), cy - dp(3f), cx + dp(7f), cy + dp(9f), stroke)

        fill.color = person.shirtColor
        canvas.drawRoundRect(
            RectF(cx - dp(10f), cy - dp(17f), cx + dp(10f), cy + dp(3f)),
            dp(6f), dp(6f), fill
        )
        fill.color = lighter(person.shirtColor, 1.2f)
        canvas.drawRoundRect(
            RectF(cx - dp(7f), cy - dp(15f), cx - dp(2f), cy - dp(1f)),
            dp(3f), dp(3f), fill
        )

        fill.color = person.hairColor
        canvas.drawCircle(cx, headY, headRadius + dp(1f), fill)
        fill.color = person.skinColor
        canvas.drawCircle(cx, headY + dp(1f), headRadius - dp(1f), fill)

        val fringe = Path().apply {
            moveTo(cx - headRadius + dp(1f), headY - dp(1f))
            quadTo(cx, headY - headRadius - dp(1f), cx + headRadius - dp(1f), headY - dp(1f))
            quadTo(cx + dp(3f), headY - dp(4f), cx, headY - dp(5f))
            quadTo(cx - dp(4f), headY - dp(3f), cx - headRadius + dp(1f), headY - dp(1f))
            close()
        }
        fill.color = person.hairColor
        canvas.drawPath(fringe, fill)

        fill.color = 0xFF3A3533.toInt()
        canvas.drawCircle(cx - dp(3f), headY + dp(1f), dp(0.9f), fill)
        canvas.drawCircle(cx + dp(3f), headY + dp(1f), dp(0.9f), fill)

        stroke.strokeWidth = dp(1.4f)
        stroke.color = 0xFF673E35.toInt()
        val mouth = Path().apply {
            moveTo(cx - dp(3f), headY + dp(4f))
            quadTo(
                cx,
                headY + dp(if (person.mood == ObjectiveDeliverySceneMood.ANGRY) 2f else 7f),
                cx + dp(3f),
                headY + dp(4f)
            )
        }
        canvas.drawPath(mouth, stroke)
        if (person.mood == ObjectiveDeliverySceneMood.ANGRY) {
            stroke.color = 0xFF51362E.toInt()
            stroke.strokeWidth = dp(1.4f)
            canvas.drawLine(cx - dp(5f), headY - dp(2f), cx - dp(2f), headY - dp(1f), stroke)
            canvas.drawLine(cx + dp(2f), headY - dp(1f), cx + dp(5f), headY - dp(2f), stroke)
            fill.color = 0xFFE17B62.toInt()
            canvas.drawCircle(cx + dp(10f), headY - dp(8f), dp(5f), fill)
            text.color = Color.WHITE
            text.textSize = dp(7f)
            canvas.drawText("!", cx + dp(10f), headY - dp(5.5f), text)
        } else {
            val bounce = sin((elapsed * 2.3f + index).toDouble()).toFloat()
            fill.color = 0xFFFFE6A1.toInt()
            canvas.drawCircle(cx + dp(10f), headY - dp(8f + bounce), dp(2.6f), fill)
        }
    }

    private fun characterPosition(index: Int, elapsed: Float, bounds: RectF): Pair<Float, Float> {
        val route = scene.people[index].route
        if (route.isEmpty()) return bounds.centerX() to bounds.centerY()
        if (route.size == 1) {
            return (bounds.left + bounds.width() * route[0].x) to
                (bounds.top + dp(24f) + (bounds.height() - dp(38f)) * route[0].y)
        }

        val phase = (elapsed / 7.5f + index * 0.37f) % route.size
        val segment = floor(phase.toDouble()).toInt().coerceIn(0, route.lastIndex)
        val next = (segment + 1) % route.size
        val raw = phase - segment
        val eased = raw * raw * (3f - 2f * raw)
        val x = route[segment].x + (route[next].x - route[segment].x) * eased
        val y = route[segment].y + (route[next].y - route[segment].y) * eased
        return (bounds.left + bounds.width() * x) to
            (bounds.top + dp(24f) + (bounds.height() - dp(38f)) * y)
    }

    private fun darker(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun lighter(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun dp(value: Float): Float = value * density
}
