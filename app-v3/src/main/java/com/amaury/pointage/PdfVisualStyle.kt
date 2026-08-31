package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Calendar

/** Identité visuelle commune à tous les PDF HoraTrack. */
object PdfVisualStyle {
    val gold = Color.rgb(190, 150, 72)
    val goldLight = Color.rgb(226, 199, 126)
    val ink = Color.rgb(31, 31, 31)
    val panel = Color.rgb(247, 244, 236)
    val line = Color.rgb(211, 197, 164)

    fun header(canvas: Canvas, width: Int, title: String, subtitle: String = "") {
        val dark = Paint().apply { color = Color.rgb(18,18,18) }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; style = Paint.Style.STROKE; strokeWidth = 1.4f }
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = goldLight; textSize = 17f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205,205,205); textSize = 7.5f }
        canvas.drawRect(0f, 0f, width.toFloat(), 62f, dark)
        canvas.drawLine(0f, 62f, width.toFloat(), 62f, border)
        canvas.drawText("HoraTrack", 24f, 25f, brand)
        canvas.drawText(title, 24f, 45f, heading)
        if (subtitle.isNotBlank()) canvas.drawText(subtitle, width - 24f - small.measureText(subtitle), 43f, small)
    }

    fun footer(canvas: Canvas, width: Int, height: Int, page: Int? = null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(105,105,105); textSize = 7f }
        val copyright = "© ${Calendar.getInstance().get(Calendar.YEAR)} HoraTrack — Tous droits réservés"
        canvas.drawLine(24f, height - 24f, width - 24f, height - 24f, Paint().apply { color = line; strokeWidth = .7f })
        canvas.drawText(copyright, 24f, height - 11f, p)
        page?.let {
            val s = "Page $it"
            canvas.drawText(s, width - 24f - p.measureText(s), height - 11f, p)
        }
    }

    fun tableHeaderPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 7.5f; typeface = Typeface.DEFAULT_BOLD }
    fun bodyPaint(size: Float = 7.2f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48,48,48); textSize = size }
    fun boldPaint(size: Float = 7.2f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = size; typeface = Typeface.DEFAULT_BOLD }
}
