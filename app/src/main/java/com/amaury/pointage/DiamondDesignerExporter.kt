package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream

object DiamondDesignerExporter {
    fun exportCanvasPng(context: Context, canvasView: DiamondDesignerCanvas, name: String = "diamond_button"): File {
        val w = canvasView.width.coerceAtLeast(1)
        val h = canvasView.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        canvasView.draw(c)
        val dir = File(context.getExternalFilesDir(null), "DiamondDesigner").apply { mkdirs() }
        val file = File(dir, safe(name) + ".png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    fun exportProjectJson(context: Context, project: DiamondDesignerProjectStore.Project): File {
        val dir = File(context.getExternalFilesDir(null), "DiamondDesigner").apply { mkdirs() }
        val file = File(dir, safe(project.name) + ".diamond.json")
        file.writeText(DiamondDesignerProjectStore.projectToJson(project), Charsets.UTF_8)
        return file
    }

    private fun safe(name: String): String = name.trim().ifBlank { "diamond_design" }.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
}
