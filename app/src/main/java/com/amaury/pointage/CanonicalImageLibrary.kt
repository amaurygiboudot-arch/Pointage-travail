package com.amaury.pointage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.MediaStore
import androidx.appcompat.content.res.AppCompatResources
import java.io.OutputStream

/**
 * Bibliothèque canonique des visuels HoraTrack.
 *
 * Règle : tout visuel destiné aux boutons est exporté en PNG RGBA, sans filtre
 * jour/nuit destructif. Les effets d'éclairage restent appliqués au runtime.
 */
object CanonicalImageLibrary {
    const val RELATIVE_DIR = "Pictures/HoraTrack/Bibliotheque"

    data class Item(
        val name: String,
        val resId: Int,
        val sourceType: String,
        val width: Int,
        val height: Int
    )

    fun items(context: Context): List<Item> = StandardButtonLiveStyle.bundledDrawableNames()
        .mapNotNull { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id == 0) return@mapNotNull null
            val drawable = AppCompatResources.getDrawable(context, id) ?: return@mapNotNull null
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1024
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
            Item(name, id, drawable.javaClass.simpleName.ifBlank { "Drawable" }, w, h)
        }
        .sortedBy { it.name }

    fun renderPngBitmap(context: Context, item: Item): Bitmap? {
        val d = AppCompatResources.getDrawable(context, item.resId)?.mutate() ?: return null
        val (w, h) = canonicalSize(d)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bitmap
    }

    private fun canonicalSize(drawable: Drawable): Pair<Int, Int> {
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1024
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
        val maxSide = 2048f
        val scale = minOf(1f, maxSide / maxOf(iw, ih).toFloat())
        val w = (iw * scale).toInt().coerceAtLeast(1)
        val h = (ih * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    fun exportOne(context: Context, item: Item): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val bitmap = renderPngBitmap(context, item) ?: return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${item.name}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            } ?: error("Flux PNG indisponible")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }.also { bitmap.recycle() }
    }

    fun exportAll(context: Context): Pair<Int, Int> {
        val all = items(context)
        var ok = 0
        all.forEach { if (exportOne(context, it)) ok++ }
        return ok to all.size
    }
}
