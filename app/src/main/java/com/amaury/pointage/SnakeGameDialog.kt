package com.amaury.pointage

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.random.Random

object SnakeGameDialog {
    fun show(context: Context) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val score = TextView(context).apply {
            text = "Score : 0"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        val game = SnakeBoard(context) { score.text = "Score : $it" }

        val restart = Button(context).apply {
            text = "RECOMMENCER"
            isAllCaps = false
            setOnClickListener { game.restart() }
        }
        val close = Button(context).apply {
            text = "FERMER"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            addView(TextView(context).apply {
                text = "🐍 SERPENT"
                textSize = 24f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Glisse ton doigt pour diriger"
                textSize = 13f
                gravity = Gravity.CENTER
            })
            addView(score)
            addView(game, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(restart, LinearLayout.LayoutParams(0, dp(context, 50), 1f).apply { marginEnd = dp(context, 5) })
                addView(close, LinearLayout.LayoutParams(0, dp(context, 50), 1f).apply { marginStart = dp(context, 5) })
            })
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener { game.stop() }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
}

private class SnakeBoard(context: Context, private val onScore: (Int) -> Unit) : View(context) {
    private data class Cell(val x: Int, val y: Int)
    private enum class Dir { UP, DOWN, LEFT, RIGHT }

    private val cols = 18
    private val rows = 25
    private val snake = mutableListOf<Cell>()
    private var food = Cell(3, 3)
    private var dir = Dir.RIGHT
    private var next = Dir.RIGHT
    private var running = true
    private var score = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            e1 ?: return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > abs(dy)) change(if (dx > 0) Dir.RIGHT else Dir.LEFT)
            else change(if (dy > 0) Dir.DOWN else Dir.UP)
            return true
        }
    })

    private val tick = object : Runnable {
        override fun run() {
            if (running) step()
            handler.postDelayed(this, 140)
        }
    }

    init {
        restart()
        handler.post(tick)
    }

    fun restart() {
        snake.clear()
        snake += Cell(7, 12)
        snake += Cell(6, 12)
        snake += Cell(5, 12)
        dir = Dir.RIGHT
        next = Dir.RIGHT
        score = 0
        running = true
        placeFood()
        onScore(score)
        invalidate()
    }

    fun stop() = handler.removeCallbacks(tick)

    private fun change(d: Dir) {
        val opposite = when (dir) {
            Dir.UP -> Dir.DOWN
            Dir.DOWN -> Dir.UP
            Dir.LEFT -> Dir.RIGHT
            Dir.RIGHT -> Dir.LEFT
        }
        if (d != opposite) next = d
    }

    private fun step() {
        dir = next
        val h = snake.first()
        val n = when (dir) {
            Dir.UP -> Cell(h.x, h.y - 1)
            Dir.DOWN -> Cell(h.x, h.y + 1)
            Dir.LEFT -> Cell(h.x - 1, h.y)
            Dir.RIGHT -> Cell(h.x + 1, h.y)
        }
        if (n.x !in 0 until cols || n.y !in 0 until rows || snake.contains(n)) {
            running = false
            invalidate()
            return
        }
        snake.add(0, n)
        if (n == food) {
            score++
            onScore(score)
            placeFood()
        } else snake.removeAt(snake.lastIndex)
        invalidate()
    }

    private fun placeFood() {
        do food = Cell(Random.nextInt(cols), Random.nextInt(rows)) while (snake.contains(food))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event) || true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cell = minOf(width.toFloat() / cols, height.toFloat() / rows)
        val bw = cell * cols
        val bh = cell * rows
        val ox = (width - bw) / 2f
        val oy = (height - bh) / 2f

        paint.color = Color.rgb(18, 18, 18)
        canvas.drawRoundRect(RectF(ox, oy, ox + bw, oy + bh), 20f, 20f, paint)

        paint.color = Color.rgb(214, 168, 75)
        snake.forEachIndexed { i, c ->
            val m = if (i == 0) cell * .10f else cell * .16f
            canvas.drawRoundRect(RectF(ox + c.x * cell + m, oy + c.y * cell + m, ox + (c.x + 1) * cell - m, oy + (c.y + 1) * cell - m), cell * .2f, cell * .2f, paint)
        }

        paint.color = Color.rgb(220, 65, 65)
        val m = cell * .18f
        canvas.drawOval(RectF(ox + food.x * cell + m, oy + food.y * cell + m, ox + (food.x + 1) * cell - m, oy + (food.y + 1) * cell - m), paint)

        if (!running) {
            paint.color = Color.argb(190, 0, 0, 0)
            canvas.drawRect(ox, oy, ox + bw, oy + bh, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = cell
            canvas.drawText("PERDU 😄", width / 2f, height / 2f, paint)
        }
    }
}
