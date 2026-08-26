package com.amaury.pointage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.appcompat.content.res.AppCompatResources
import java.io.OutputStream

/** Bibliothèque canonique PNG RGBA des visuels HoraTrack. */
object CanonicalImageLibrary {
    const val RELATIVE_DIR = "Pictures/HoraTrack/Bibliotheque"

    data class Item(
        val name: String,
        val resId: Int = 0,
        val sourceType: String,
        val width: Int,
        val height: Int,
        val rawBase64ResId: Int = 0,
        val selectableInLive: Boolean = true
    )

    /** Ressources Android directement utilisables dans les boutons Live. */
    fun items(context: Context): List<Item> = StandardButtonLiveStyle.bundledDrawableNames()
        .mapNotNull { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id == 0) return@mapNotNull null
            val drawable = AppCompatResources.getDrawable(context, id) ?: return@mapNotNull null
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1024
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
            Item(name, id, drawable.javaClass.simpleName.ifBlank { "Drawable" }, w, h)
        }
        .distinctBy { it.name }
        .sortedBy { it.name }

    /** Inventaire complet : drawables + anciens PNG stockés en Base64 dans res/raw. */
    fun allItems(context: Context): List<Item> {
        val direct = items(context)
        val raw = R.raw::class.java.fields.mapNotNull { field ->
            val rawName = runCatching { field.name }.getOrNull() ?: return@mapNotNull null
            if (!rawName.endsWith("_b64")) return@mapNotNull null
            val id = runCatching { field.getInt(null) }.getOrNull() ?: return@mapNotNull null
            val bitmap = decodeRawBase64(context, id) ?: return@mapNotNull null
            val clean = rawName.removeSuffix("_b64")
            val item = Item(
                name = clean,
                sourceType = "Base64 raw → PNG RGBA",
                width = bitmap.width,
                height = bitmap.height,
                rawBase64ResId = id,
                selectableInLive = false
            )
            bitmap.recycle()
            item
        }
        return (direct + raw).distinctBy { it.name to it.sourceType }.sortedBy { it.name }
    }

    fun renderPngBitmap(context: Context, item: Item): Bitmap? {
        if (item.rawBase64ResId != 0) return decodeRawBase64(context, item.rawBase64ResId)
        val d = AppCompatResources.getDrawable(context, item.resId)?.mutate() ?: return null
        val (w, h) = canonicalSize(d)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bitmap
    }

    private fun decodeRawBase64(context: Context, rawId: Int): Bitmap? = runCatching {
        val text = context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
        val clean = text.substringAfter("base64,", text).replace(Regex("\\s+"), "")
        val bytes = Base64.decode(clean, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.copy(Bitmap.Config.ARGB_8888, false)
    }.getOrNull()

    private fun canonicalSize(drawable: Drawable): Pair<Int, Int> {
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1024
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
        val minLongSide = 1024f
        val maxLongSide = 2048f
        val longSide = maxOf(iw, ih).toFloat()
        val scale = when {
            longSide < minLongSide -> minLongSide / longSide
            longSide > maxLongSide -> maxLongSide / longSide
            else -> 1f
        }
        return (iw * scale).toInt().coerceAtLeast(1) to (ih * scale).toInt().coerceAtLeast(1)
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
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run { bitmap.recycle(); return false }
        return runCatching {
            resolver.openOutputStream(uri)?.use { out: OutputStream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) }
                ?: error("Flux PNG indisponible")
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
        val all = allItems(context)
        var ok = 0
        all.forEach { if (exportOne(context, it)) ok++ }
        return ok to all.size
    }
}
