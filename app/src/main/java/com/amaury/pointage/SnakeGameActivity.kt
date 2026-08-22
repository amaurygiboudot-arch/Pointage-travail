package com.amaury.pointage

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.random.Random

class SnakeGameActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val score = TextView(this).apply {
            text = "Score : 0"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        val game = SnakeView(this) { value -> score.text = "Score : $value" }

        val restart = Button(this).apply {
            text = "RECOMMENCER"
            isAllCaps = false
            setOnClickListener { game.restart() }
        }
        val close = Button(this).apply {
            text = "FERMER"
            isAllCaps = false
            setOnClickListener { finish() }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(restart, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
            addView(close, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
            addView(TextView(this@SnakeGameActivity).apply {
                text = "🐍 SERPENT"
                textSize = 24f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@SnakeGameActivity).apply {
                text = "Glisse ton doigt dans la direction voulue"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(6))
            })
            addView(score)
            addView(game, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        setContentView(root)
        AppearanceManager.apply(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

private class SnakeView(
    context: android.content.Context,
    private val onScore: (Int) -> Unit
) : View(context) {

    private data class Cell(val x: Int, val y: Int)
    private enum class Direction { UP, DOWN, LEFT, RIGHT }

    private val cols = 18
    private val rows = 26
    private val snake = mutableListOf<Cell>()
    private var food = Cell(4, 4)
    private var direction = Direction.RIGHT
    private var nextDirection = Direction.RIGHT
    private var score = 0
    private var running = true
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > abs(dy)) {
                if (dx > 0) change(Direction.RIGHT) else change(Direction.LEFT)
            } else {
                if (dy > 0) change(Direction.DOWN) else change(Direction.UP)
            }
            return true
        }
    })

    private val tick = object : Runnable {
        override fun run() {
            if (running) step()
            handler.postDelayed(this, 135L)
        }
    }

    init {
        isFocusable = true
        restart()
        handler.post(tick)
    }

    fun restart() {
        snake.clear()
        snake += Cell(7, 12)
        snake += Cell(6, 12)
        snake += Cell(5, 12)
        direction = Direction.RIGHT
        nextDirection = Direction.RIGHT
        score = 0
        running = true
        placeFood()
        onScore(score)
        invalidate()
    }

    private fun change(newDirection: Direction) {
        val opposite = when (direction) {
            Direction.UP -> Direction.DOWN
            Direction.DOWN -> Direction.UP
            Direction.LEFT -> Direction.RIGHT
            Direction.RIGHT -> Direction.LEFT
        }
        if (newDirection != opposite) nextDirection = newDirection
    }

    private fun step() {
        direction = nextDirection
        val head = snake.first()
        val newHead = when (direction) {
            Direction.UP -> Cell(head.x, head.y - 1)
            Direction.DOWN -> Cell(head.x, head.y + 1)
            Direction.LEFT -> Cell(head.x - 1, head.y)
            Direction.RIGHT -> Cell(head.x + 1, head.y)
        }

        if (newHead.x !in 0 until cols || newHead.y !in 0 until rows || snake.contains(newHead)) {
            running = false
            invalidate()
            return
        }

        snake.add(0, newHead)
        if (newHead == food) {
            score++
            onScore(score)
            placeFood()
        } else {
            snake.removeAt(snake.lastIndex)
        }
        invalidate()
    }

    private fun placeFood() {
        do {
            food = Cell(Random.nextInt(cols), Random.nextInt(rows))
        } while (snake.contains(food))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cw = width.toFloat() / cols
        val ch = height.toFloat() / rows
        val cell = minOf(cw, ch)
        val boardW = cell * cols
        val boardH = cell * rows
        val ox = (width - boardW) / 2f
        val oy = (height - boardH) / 2f

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(20, 20, 20)
        canvas.drawRoundRect(RectF(ox, oy, ox + boardW, oy + boardH), 18f, 18f, paint)

        paint.color = Color.rgb(214, 168, 75)
        snake.forEachIndexed { index, c ->
            val margin = if (index == 0) cell * 0.10f else cell * 0.16f
            canvas.drawRoundRect(
                RectF(ox + c.x * cell + margin, oy + c.y * cell + margin, ox + (c.x + 1) * cell - margin, oy + (c.y + 1) * cell - margin),
                cell * 0.22f, cell * 0.22f, paint
            )
        }

        paint.color = Color.rgb(220, 65, 65)
        val fm = cell * 0.18f
        canvas.drawOval(RectF(ox + food.x * cell + fm, oy + food.y * cell + fm, ox + (food.x + 1) * cell - fm, oy + (food.y + 1) * cell - fm), paint)

        if (!running) {
            paint.color = Color.argb(190, 0, 0, 0)
            canvas.drawRect(ox, oy, ox + boardW, oy + boardH, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = cell * 1.15f
            canvas.drawText("PERDU 😄", width / 2f, height / 2f, paint)
            paint.textSize = cell * 0.62f
            canvas.drawText("Score : $score", width / 2f, height / 2f + cell, paint)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
