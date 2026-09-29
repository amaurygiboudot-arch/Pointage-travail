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
import android.view.ScaleGestureDetector
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
    private val designColors = mutableMapOf<TextView, Int>()
    private lateinit var sceneCanvas: ObjectiveDeliverySceneCanvas
    private lateinit var selectedPersonDetails: TextView

    init {
        orientation = VERTICAL
        setPadding(sceneDp(14), sceneDp(12), sceneDp(14), sceneDp(12))
        background = roundedBackground(0xFF163841.toInt(), sceneDp(18).toFloat())
        contentDescription = "Scène animée du chapitre ${scene.chapter}"

        addView(sceneText(
            "MONDE OUVERT • CHAPITRE ${scene.chapter}",
            11f,
            0xFF93D8CB.toInt(),
            bold = true
        ), sceneLayout(bottom = 4))
        addView(sceneText(scene.headline, 19f, Color.WHITE, bold = true), sceneLayout(bottom = 3))
        addView(sceneText(scene.story, 14f, 0xFFE1EFEC.toInt()), sceneLayout(bottom = 4))
        addView(sceneText(
            "Touche le sol pour marcher • glisse pour regarder • pince pour zoomer",
            12f,
            0xFFAAD8CE.toInt(),
            bold = true
        ), sceneLayout(bottom = 10))

        sceneCanvas = ObjectiveDeliverySceneCanvas(context, scene).apply {
            contentDescription = "Monde ouvert d’entreprise en vue de dessus : ${scene.zoneLabel}. Touche le sol pour marcher, glisse pour explorer, pince pour zoomer. Sélectionne un personnage pour voir son rôle."
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addView(FrameLayout(context).apply {
            background = roundedBackground(0xFFD8E9E6.toInt(), sceneDp(14).toFloat())
            clipToOutline = true
            addView(sceneCanvas, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                sceneDp(294)
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
        selectPerson(sceneCanvas.playerIndex)
    }

    internal fun reapplySceneStyles() {
        designColors.forEach { (view, color) -> view.setTextColor(color) }
        selectPerson(sceneCanvas.selectedIndex)
    }

    private fun selectPerson(index: Int) {
        if (scene.people.isEmpty()) return
        val safeIndex = index.coerceIn(scene.people.indices)
        sceneCanvas.selectedIndex = safeIndex
        sceneCanvas.focusPerson(safeIndex)
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
            "Monde ouvert de l’entreprise. " +
                "${person.name}, ${person.role}, sélectionné. ${person.task} " +
                "Touche un endroit pour déplacer la direction, glisse pour explorer et pince pour zoomer."
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
    }.also { designColors[it] = color }

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
    private data class WorldZone(
        val label: String,
        val column: Int,
        val row: Int,
        val floorColor: Int
    )

    var selectedIndex: Int = 0
    val playerIndex: Int = scene.people.indexOfFirst { person ->
        person.name.equals("Direction", ignoreCase = true) ||
            person.role.contains("direction", ignoreCase = true)
    }.let { if (it >= 0) it else 0 }
    var onPersonSelected: ((Int) -> Unit)? = null

    private val zones = listOf(
        WorldZone("ACCUEIL CLIENT", 0, 0, 0xFFF7F2E8.toInt()),
        WorldZone("BUREAUX & DEVIS", 1, 0, 0xFFEAF2EE.toInt()),
        WorldZone("STOCK & MATIÈRES", 2, 0, 0xFFF2EEDC.toInt()),
        WorldZone("ATELIER & QUALITÉ", 0, 1, 0xFFE7F0ED.toInt()),
        WorldZone("ESPACE ÉQUIPE", 1, 1, 0xFFEEF1E8.toInt()),
        WorldZone("QUAI & SÉCURITÉ", 2, 1, 0xFFE7EFED.toInt())
    )
    private val density = resources.displayMetrics.density
    private var animationStart = 0L
    private var previousFrameAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var downCameraX = 0f
    private var downCameraY = 0f
    private var managerX = 0f
    private var managerY = 0f
    private var cameraX = 0f
    private var cameraY = 0f
    private var zoomFactor = 1f
    private var walkingTarget: ObjectiveDeliveryScenePoint? = null
    private var trackingIndex = playerIndex
    private var cameraReady = false
    private var manualCamera = false
    private var isDragging = false
    private var isPinching = false

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
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomFactor = (zoomFactor * detector.scaleFactor).coerceIn(0.72f, 1.45f)
                clampCamera()
                invalidate()
                return true
            }
        }
    )

    private val animateFrame = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE || windowVisibility != VISIBLE) return
            invalidate()
            postDelayed(this, 55L)
        }
    }

    init {
        isClickable = true
        contentDescription =
            "Monde ouvert d’entreprise. Touche un lieu pour marcher, glisse pour explorer et pince pour zoomer."
    }

    fun focusPerson(index: Int) {
        if (scene.people.isEmpty()) return
        trackingIndex = index.coerceIn(scene.people.indices)
        manualCamera = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val start = zoneBounds(activeZoneIndex()).centerPoint()
            managerX = start.x
            managerY = start.y
            cameraX = managerX
            cameraY = managerY
            cameraReady = true
            clampCamera()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationStart = SystemClock.uptimeMillis()
        previousFrameAt = 0L
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
        canvas.drawColor(0xFFB8CEC3.toInt())
        if (width <= 0 || height <= 0) return

        val now = SystemClock.uptimeMillis()
        val elapsed = (now - animationStart) / 1000f
        advanceWorld(now, elapsed)

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(width / 2f - cameraX * zoomFactor, height / 2f - cameraY * zoomFactor)
        canvas.scale(zoomFactor, zoomFactor)

        val world = worldBounds()
        drawRoom(canvas, world)
        zones.indices.forEach { index ->
            val bounds = zoneBounds(index)
            drawWorldZone(canvas, bounds, zones[index])
            if (index == activeZoneIndex()) {
                scene.fixtures.forEach { drawFixture(canvas, bounds, it) }
            } else {
                drawGenericFixtures(canvas, bounds, index)
            }
        }

        walkingTarget?.let { target ->
            stroke.color = 0xFF2C8277.toInt()
            stroke.strokeWidth = dp(2f)
            canvas.drawCircle(target.x, target.y, dp(11f), stroke)
            fill.color = 0xFF2C8277.toInt()
            canvas.drawCircle(target.x, target.y, dp(2.5f), fill)
        }

        scene.people.forEachIndexed { index, person ->
            val point = characterPosition(index, elapsed)
            val bob = sin((elapsed * 4.2f + index).toDouble()).toFloat() * dp(1.5f)
            drawPerson(canvas, point.first, point.second + bob, person, index == selectedIndex, elapsed, index)
        }
        canvas.restore()
        drawOpenWorldHud(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downCameraX = cameraX
                downCameraY = cameraY
                isDragging = false
                isPinching = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isPinching = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || event.pointerCount > 1) {
                    isPinching = true
                    return true
                }
                val dx = event.x - downX
                val dy = event.y - downY
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (distance > dp(12f)) {
                    isDragging = true
                    manualCamera = true
                    cameraX = downCameraX - dx / zoomFactor
                    cameraY = downCameraY - dy / zoomFactor
                    clampCamera()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                isPinching = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (!isPinching && !isDragging && distance <= dp(12f)) {
                    handleWorldTap(event.x, event.y)
                }
                isDragging = false
                isPinching = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isPinching = false
                return false
            }
        }
        return true
    }

    private fun handleWorldTap(screenX: Float, screenY: Float) {
        val target = screenToWorld(screenX, screenY)
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        scene.people.indices.forEach { index ->
            val person = characterPosition(index, (SystemClock.uptimeMillis() - animationStart) / 1000f)
            val personScreenX = width / 2f + (person.first - cameraX) * zoomFactor
            val personScreenY = height / 2f + (person.second - cameraY) * zoomFactor
            val distance = sqrt(
                ((screenX - personScreenX) * (screenX - personScreenX) +
                    (screenY - personScreenY) * (screenY - personScreenY)).toDouble()
            ).toFloat()
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        if (bestIndex >= 0 && bestDistance <= dp(30f)) {
            onPersonSelected?.invoke(bestIndex)
            performClick()
            return
        }

        val world = worldBounds()
        walkingTarget = ObjectiveDeliveryScenePoint(
            target.x.coerceIn(world.left + dp(12f), world.right - dp(12f)),
            target.y.coerceIn(world.top + dp(12f), world.bottom - dp(12f))
        )
        trackingIndex = playerIndex
        manualCamera = false
        performClick()
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun advanceWorld(now: Long, elapsed: Float) {
        if (!cameraReady) {
            val start = zoneBounds(activeZoneIndex()).centerPoint()
            managerX = start.x
            managerY = start.y
            cameraX = managerX
            cameraY = managerY
            cameraReady = true
        }
        val delta = if (previousFrameAt == 0L) 0f else
            ((now - previousFrameAt) / 1000f).coerceIn(0f, 0.12f)
        previousFrameAt = now
        walkingTarget?.let { target ->
            val dx = target.x - managerX
            val dy = target.y - managerY
            val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val step = dp(82f) * delta
            if (distance <= step || distance < dp(2f)) {
                managerX = target.x
                managerY = target.y
                walkingTarget = null
            } else if (step > 0f) {
                managerX += dx / distance * step
                managerY += dy / distance * step
            }
        }

        if (!manualCamera) {
            val focused = characterPosition(trackingIndex, elapsed)
            cameraX += (focused.first - cameraX) * 0.16f
            cameraY += (focused.second - cameraY) * 0.16f
        }
        clampCamera()
    }

    private fun activeZoneIndex(): Int = when (scene.chapter.coerceIn(1, 10)) {
        1, 2 -> 0
        3 -> 1
        4, 8 -> 4
        5 -> 2
        6 -> 3
        7, 9 -> 5
        else -> 4
    }

    private fun characterZoneIndex(index: Int): Int {
        if (index == playerIndex) return activeZoneIndex()
        val role = scene.people[index].role.lowercase()
        return when {
            role.contains("client") && scene.chapter >= 7 -> 5
            role.contains("client") || role.contains("commerce") || role.contains("vente") -> 0
            role.contains("approvisionnement") || role.contains("fournisseur") -> 2
            role.contains("logistique") -> 5
            role.contains("production") || role.contains("qualité") || role.contains("atelier") -> 3
            role.contains("équipe") || role.contains("manager") || role.contains("ressources humaines") -> 4
            else -> activeZoneIndex()
        }
    }

    private fun worldBounds(): RectF = RectF(0f, 0f, width * 3f, height * 2f)

    private fun zoneBounds(index: Int): RectF {
        val zone = zones[index]
        return RectF(
            zone.column * width + dp(16f),
            zone.row * height + dp(16f),
            (zone.column + 1) * width - dp(16f),
            (zone.row + 1) * height - dp(16f)
        )
    }

    private fun RectF.centerPoint() = ObjectiveDeliveryScenePoint(centerX(), centerY())

    private fun clampCamera() {
        if (width <= 0 || height <= 0) return
        val world = worldBounds()
        val halfWidth = width / (2f * zoomFactor)
        val halfHeight = height / (2f * zoomFactor)
        cameraX = cameraX.coerceIn(halfWidth, world.right - halfWidth)
        cameraY = cameraY.coerceIn(halfHeight, world.bottom - halfHeight)
    }

    private fun screenToWorld(x: Float, y: Float) = ObjectiveDeliveryScenePoint(
        cameraX + (x - width / 2f) / zoomFactor,
        cameraY + (y - height / 2f) / zoomFactor
    )

    private fun drawRoom(canvas: Canvas, bounds: RectF) {
        fill.color = 0xFFB9CEC3.toInt()
        canvas.drawRect(bounds, fill)

        fill.color = 0xFFDDE5DE.toInt()
        val horizontalRoad = bounds.centerY()
        val verticalRoadOne = bounds.width() / 3f
        val verticalRoadTwo = verticalRoadOne * 2f
        val road = dp(18f)
        canvas.drawRect(bounds.left, horizontalRoad - road, bounds.right, horizontalRoad + road, fill)
        canvas.drawRect(verticalRoadOne - road, bounds.top, verticalRoadOne + road, bounds.bottom, fill)
        canvas.drawRect(verticalRoadTwo - road, bounds.top, verticalRoadTwo + road, bounds.bottom, fill)

        stroke.color = 0xFFB9C9C1.toInt()
        stroke.strokeWidth = dp(1f)
        canvas.drawLine(bounds.left, horizontalRoad, bounds.right, horizontalRoad, stroke)
        canvas.drawLine(verticalRoadOne, bounds.top, verticalRoadOne, bounds.bottom, stroke)
        canvas.drawLine(verticalRoadTwo, bounds.top, verticalRoadTwo, bounds.bottom, stroke)
    }

    private fun drawWorldZone(canvas: Canvas, bounds: RectF, zone: WorldZone) {
        fill.color = 0x33153639
        canvas.drawRoundRect(
            RectF(bounds.left + dp(3f), bounds.top + dp(6f), bounds.right + dp(3f), bounds.bottom + dp(6f)),
            dp(15f), dp(15f), fill
        )
        fill.color = zone.floorColor
        canvas.drawRoundRect(bounds, dp(14f), dp(14f), fill)
        stroke.color = 0xFF9FB5AD.toInt()
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(bounds, dp(14f), dp(14f), stroke)

        val inset = dp(12f)
        stroke.color = 0xFFD8E3DD.toInt()
        stroke.strokeWidth = dp(1f)
        for (line in 1..4) {
            val x = bounds.left + inset + (bounds.width() - inset * 2) * line / 5f
            canvas.drawLine(x, bounds.top + dp(44f), x, bounds.bottom - dp(10f), stroke)
        }
        for (line in 1..2) {
            val y = bounds.top + dp(44f) + (bounds.height() - dp(56f)) * line / 3f
            canvas.drawLine(bounds.left + dp(7f), y, bounds.right - dp(7f), y, stroke)
        }
        drawZonePill(canvas, bounds, zone.label)
    }

    private fun drawGenericFixtures(canvas: Canvas, bounds: RectF, index: Int) {
        fun fixture(
            x: Float,
            y: Float,
            width: Float,
            depth: Float,
            height: Float,
            label: String,
            color: Int,
            kind: ObjectiveDeliverySceneFixture.Kind = ObjectiveDeliverySceneFixture.Kind.BLOCK
        ) = ObjectiveDeliverySceneFixture(x, y, width, depth, height, label, color, kind)

        val decorations = when (index) {
            0 -> listOf(fixture(0.14f, 0.30f, 0.28f, 0.16f, 18f, "ACCUEIL", 0xFF2D8B83.toInt(), ObjectiveDeliverySceneFixture.Kind.DESK))
            1 -> listOf(
                fixture(0.14f, 0.30f, 0.25f, 0.14f, 14f, "DEVIS", 0xFFE6AF48.toInt(), ObjectiveDeliverySceneFixture.Kind.DESK),
                fixture(0.60f, 0.32f, 0.20f, 0.12f, 12f, "DOSSIERS", 0xFF8B72B4.toInt())
            )
            2 -> listOf(fixture(0.14f, 0.28f, 0.27f, 0.22f, 22f, "MATIÈRES", 0xFFE6AF48.toInt(), ObjectiveDeliverySceneFixture.Kind.RACK))
            3 -> listOf(fixture(0.13f, 0.29f, 0.29f, 0.20f, 25f, "MACHINE", 0xFF4387B5.toInt(), ObjectiveDeliverySceneFixture.Kind.MACHINE))
            4 -> listOf(fixture(0.16f, 0.32f, 0.30f, 0.16f, 10f, "ÉQUIPE", 0xFF8B72B4.toInt(), ObjectiveDeliverySceneFixture.Kind.DESK))
            else -> listOf(
                fixture(0.13f, 0.29f, 0.32f, 0.18f, 15f, "DÉPART", 0xFF4387B5.toInt(), ObjectiveDeliverySceneFixture.Kind.VAN),
                fixture(0.59f, 0.28f, 0.20f, 0.14f, 13f, "CONTRÔLE", 0xFFE17B62.toInt(), ObjectiveDeliverySceneFixture.Kind.BARRIER)
            )
        }
        decorations.forEach { drawFixture(canvas, bounds, it) }
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

    private fun drawZonePill(canvas: Canvas, bounds: RectF, label: String) {
        fill.color = 0xEFFFFFFF.toInt()
        val width = dp(132f)
        val pill = RectF(bounds.left + dp(7f), bounds.top + dp(7f), bounds.left + dp(7f) + width, bounds.top + dp(29f))
        canvas.drawRoundRect(pill, dp(10f), dp(10f), fill)
        text.color = 0xFF315C5D.toInt()
        text.textSize = dp(7.2f)
        canvas.drawText(label, pill.centerX(), pill.centerY() + dp(2.5f), text)
    }

    private fun drawOpenWorldHud(canvas: Canvas) {
        val zone = zones[zoneIndexAt(managerX, managerY)]
        fill.color = 0xEFFFFFFF.toInt()
        val labelPill = RectF(dp(8f), dp(8f), dp(185f), dp(34f))
        canvas.drawRoundRect(labelPill, dp(12f), dp(12f), fill)
        text.color = 0xFF28545C.toInt()
        text.textSize = dp(8f)
        canvas.drawText("TU ES ICI • " + zone.label, labelPill.centerX(), labelPill.centerY() + dp(3f), text)
        drawMiniMap(canvas)
    }

    private fun drawMiniMap(canvas: Canvas) {
        val miniWidth = dp(72f)
        val miniHeight = dp(48f)
        val left = width - miniWidth - dp(8f)
        val top = dp(8f)
        fill.color = 0xEFFFFFFF.toInt()
        canvas.drawRoundRect(RectF(left, top, left + miniWidth, top + miniHeight), dp(7f), dp(7f), fill)
        val cellWidth = dp(19f)
        val cellHeight = dp(12f)
        val gap = dp(2f)
        val startX = left + dp(5f)
        val startY = top + dp(5f)
        val current = zoneIndexAt(managerX, managerY)
        zones.forEachIndexed { index, zone ->
            val x = startX + zone.column * (cellWidth + gap)
            val y = startY + zone.row * (cellHeight + gap)
            fill.color = zone.floorColor
            canvas.drawRoundRect(RectF(x, y, x + cellWidth, y + cellHeight), dp(2f), dp(2f), fill)
            stroke.color = if (index == current) 0xFFE6AF48.toInt() else 0xFF93AAA2.toInt()
            stroke.strokeWidth = if (index == current) dp(2f) else dp(0.7f)
            canvas.drawRoundRect(RectF(x, y, x + cellWidth, y + cellHeight), dp(2f), dp(2f), stroke)
            if (index == current) {
                val area = zoneBounds(index)
                val dotX = x + (managerX - area.left) / area.width() * cellWidth
                val dotY = y + (managerY - area.top) / area.height() * cellHeight
                fill.color = 0xFFE17B62.toInt()
                canvas.drawCircle(dotX.coerceIn(x + dp(2f), x + cellWidth - dp(2f)), dotY.coerceIn(y + dp(2f), y + cellHeight - dp(2f)), dp(2f), fill)
            }
        }
    }

    private fun zoneIndexAt(x: Float, y: Float): Int {
        val column = (x / width.coerceAtLeast(1)).toInt().coerceIn(0, 2)
        val row = (y / height.coerceAtLeast(1)).toInt().coerceIn(0, 1)
        return row * 3 + column
    }

    private fun characterPosition(index: Int, elapsed: Float): Pair<Float, Float> {
        if (index == playerIndex) return managerX to managerY
        val route = scene.people[index].route
        val bounds = zoneBounds(characterZoneIndex(index))
        if (route.isEmpty()) return bounds.centerX() to bounds.centerY()
        if (route.size == 1) {
            return (bounds.left + bounds.width() * route[0].x) to
                (bounds.top + dp(18f) + (bounds.height() - dp(36f)) * route[0].y)
        }

        val phase = (elapsed / 7.5f + index * 0.37f) % route.size
        val segment = floor(phase.toDouble()).toInt().coerceIn(0, route.lastIndex)
        val next = (segment + 1) % route.size
        val raw = phase - segment
        val eased = raw * raw * (3f - 2f * raw)
        val x = route[segment].x + (route[next].x - route[segment].x) * eased
        val y = route[segment].y + (route[next].y - route[segment].y) * eased
        return (bounds.left + bounds.width() * x) to
            (bounds.top + dp(18f) + (bounds.height() - dp(36f)) * y)
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

