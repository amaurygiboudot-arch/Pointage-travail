package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Calendar

/** Identité visuelle commune à tous les PDF AGKGMG. */
object PdfVisualStyle {
    val accent = Color.rgb(11, 119, 119)
    val gold = accent
    val goldLight = Color.rgb(221, 239, 239)
    val ink = Color.rgb(31, 31, 31)
    val panel = Color.rgb(235, 246, 246)
    val line = Color.rgb(193, 215, 215)

    fun header(canvas: Canvas, width: Int, title: String, subtitle: String = "") {
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 1.2f }
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 8f }
        canvas.drawRect(0f, 0f, width.toFloat(), 62f, Paint().apply { color = Color.WHITE })
        canvas.drawLine(24f, 62f, width - 24f, 62f, border)
        canvas.drawText("AGKGMG", 24f, 25f, brand)
        val titleWidth = width - 48f - brand.measureText("AGKGMG") - 24f
        heading.textSize = minOf(heading.textSize, titleWidth / heading.measureText(title) * heading.textSize)
        canvas.drawText(title, width - 24f - heading.measureText(title), 25f, heading)
        canvas.drawText("Suivi du temps de travail", 24f, 45f, small)
        if (subtitle.isNotBlank()) {
            val available = width - 48f - small.measureText("Suivi du temps de travail") - 16f
            if (available > 0f) {
                small.textSize = minOf(small.textSize, available / small.measureText(subtitle) * small.textSize)
                if (small.textSize >= 6f) canvas.drawText(subtitle, width - 24f - small.measureText(subtitle), 43f, small)
            }
        }
    }

    fun footer(canvas: Canvas, width: Int, height: Int, page: Int? = null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(105,105,105); textSize = 7f }
        val copyright = "© ${Calendar.getInstance().get(Calendar.YEAR)} AGKGMG — Tous droits réservés"
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
