package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.min

/**
 * Plateau 2D volontairement simple pour le premier chapitre.
 * Il représente le parcours de la commande sans moteur 3D ni animation temps réel.
 */
class ObjectiveDeliveryBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state: ObjectiveDeliveryState? = null

    private val labels = listOf("PROSPECT", "BESOIN", "DEVIS", "COMMANDE")

    init {
        minimumHeight = dp(238)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(value: ObjectiveDeliveryState) {
        state = value
        contentDescription = accessibilityDescription(value)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = dp(250)
        val height = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = state ?: return
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val text = if (dark) theme.darkText else theme.lightText
        val hint = if (dark) theme.darkHint else theme.lightHint
        val accent = if (dark) theme.accentLight else theme.accent

        val outer = RectF(
            dp(2).toFloat(),
            dp(2).toFloat(),
            width - dp(2).toFloat(),
            height - dp(2).toFloat()
        )
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(outer, dp(18).toFloat(), dp(18).toFloat(), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = withAlpha(accent, 150)
        canvas.drawRoundRect(outer, dp(18).toFloat(), dp(18).toFloat(), paint)

        textPaint.color = text
        textPaint.textSize = sp(15f)
        textPaint.isFakeBoldText = true
        canvas.drawText("PARCOURS DE LA COMMANDE", dp(16).toFloat(), dp(28).toFloat(), textPaint)

        val points = listOf(
            Point(width * 0.21f, height * 0.36f),
            Point(width * 0.73f, height * 0.36f),
            Point(width * 0.73f, height * 0.73f),
            Point(width * 0.21f, height * 0.73f)
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4f)
        paint.strokeCap = Paint.Cap.ROUND
        for (index in 0 until points.lastIndex) {
            val from = points[index]
            val to = points[index + 1]
            paint.color = if (isStageCompleted(current, index)) {
                withAlpha(accent, 210)
            } else {
                withAlpha(hint, 90)
            }
            canvas.drawLine(from.x, from.y, to.x, to.y, paint)
        }

        points.forEachIndexed { index, point ->
            drawNode(canvas, current, index, point, accent, text, hint)
        }

        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12.5f)
        textPaint.color = hint
        val footer = when (current.outcome) {
            ObjectiveOutcome.WON -> "Commande acceptée — chapitre suivant débloqué"
            ObjectiveOutcome.REWORK -> "Le dossier peut encore être retravaillé en entraînement"
            ObjectiveOutcome.LOST -> "Vente perdue — le bilan explique les causes"
            ObjectiveOutcome.IN_PROGRESS -> "Chaque décision fait évoluer le dossier"
        }
        canvas.drawText(footer, dp(16).toFloat(), height - dp(15).toFloat(), textPaint)
    }

    private fun drawNode(
        canvas: Canvas,
        current: ObjectiveDeliveryState,
        index: Int,
        point: Point,
        accent: Int,
        text: Int,
        hint: Int
    ) {
        val radius = min(dp(26).toFloat(), width * 0.075f)
        val completed = isStageCompleted(current, index)
        val active = activeStage(current) == index

        paint.style = Paint.Style.FILL
        paint.color = when {
            completed -> withAlpha(accent, 235)
            active -> withAlpha(accent, 175)
            else -> withAlpha(hint, 45)
        }
        canvas.drawCircle(point.x, point.y, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (active) dp(3f) else dp(1.5f)
        paint.color = if (active || completed) accent else withAlpha(hint, 120)
        canvas.drawCircle(point.x, point.y, radius, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = sp(14f)
        textPaint.color = if (completed) panelTextFor(accent) else text
        canvas.drawText((index + 1).toString(), point.x, point.y + sp(5f), textPaint)

        textPaint.textSize = sp(11.5f)
        textPaint.color = if (active || completed) text else hint
        canvas.drawText(labels[index], point.x, point.y + radius + dp(18), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun activeStage(current: ObjectiveDeliveryState): Int = when {
        current.outcome == ObjectiveOutcome.WON -> 3
        current.outcome != ObjectiveOutcome.IN_PROGRESS -> current.step.coerceIn(0, 2)
        else -> current.step.coerceIn(0, 2)
    }

    private fun isStageCompleted(current: ObjectiveDeliveryState, index: Int): Boolean {
        if (current.outcome == ObjectiveOutcome.WON) return true
        return index < current.step
    }

    private fun accessibilityDescription(current: ObjectiveDeliveryState): String {
        val active = labels[activeStage(current)]
        val result = when (current.outcome) {
            ObjectiveOutcome.WON -> "commande gagnée"
            ObjectiveOutcome.REWORK -> "offre à retravailler"
            ObjectiveOutcome.LOST -> "vente perdue"
            ObjectiveOutcome.IN_PROGRESS -> "partie en cours"
        }
        return "Parcours de la commande. Étape actuelle : $active. Résultat : $result."
    }

    private fun panelTextFor(background: Int): Int =
        if (AppearanceManager.bestTextColor(background) == Color.WHITE) Color.WHITE else Color.BLACK

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun dp(value: Int): Int =
        ceil(value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        value * resources.displayMetrics.scaledDensity

    private data class Point(val x: Float, val y: Float)
}
