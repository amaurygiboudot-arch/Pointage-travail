package com.amaury.pointage

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import java.util.WeakHashMap

/** Explicit reader for content-sized bitmap images; small navigation icons are left alone. */
object ContentImageReaderV2 {
    private val actions = WeakHashMap<ImageView, Int>()
    fun attach(view: ImageView) {
        if (view is ZoomablePdfPageView || actions.containsKey(view)) return
        if (view.drawable !is BitmapDrawable) return
        val density = view.resources.displayMetrics.density
        if (view.width < 160 * density || view.height < 100 * density) return
        actions[view] = ViewCompat.addAccessibilityAction(view, "Ouvrir l’image agrandie") { _, _ -> show(view); true }
        if (!view.isLongClickable && !view.hasOnClickListeners()) view.setOnLongClickListener { show(view); true }
    }
    fun forget(view: ImageView) { actions.remove(view)?.let { ViewCompat.removeAccessibilityAction(view, it) } }
    private fun show(source: ImageView) {
        val original = (source.drawable as? BitmapDrawable)?.bitmap ?: return
        if (original.isRecycled || original.width < 1 || original.height < 1) return
        // Bound memory, and own the copy: another screen may recycle its original image.
        val factor = minOf(1f, 1600f / maxOf(original.width, original.height))
        val snapshot = runCatching {
            val scaled = Bitmap.createScaledBitmap(original,
                (original.width * factor).toInt().coerceAtLeast(1),
                (original.height * factor).toInt().coerceAtLeast(1), true)
            if (scaled === original) original.copy(Bitmap.Config.ARGB_8888, false) else scaled
        }.getOrNull()
        if (snapshot == null) {
            Toast.makeText(source.context, "Impossible d’ouvrir cette image", Toast.LENGTH_SHORT).show(); return
        }
        val reader = ZoomablePdfPageView(source.context).apply {
            setImageBitmap(snapshot)
            contentDescription = source.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?.let { "$it. Image agrandie." } ?: "Image agrandie"
            setBackgroundColor(Color.BLACK)
        }
        val box = LinearLayout(source.context).apply {
            tag = "personalization_reader_v2"
            orientation = LinearLayout.VERTICAL
        }
        val controls = LinearLayout(source.context)
        fun button(label: String, action: () -> Unit) = Button(source.context).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        controls.addView(button("Réduire", reader::zoomOut))
        controls.addView(button("Agrandir", reader::zoomIn))
        controls.addView(button("Ajuster", reader::resetZoom))
        box.addView(controls)
        box.addView(reader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialog = AlertDialog.Builder(source.context).setTitle("Lecture de l’image")
            .setView(box).setPositiveButton("Fermer", null).show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
            (source.resources.displayMetrics.heightPixels * .85f).toInt())
        PersonalizationRuntimeV2.track(dialog)
    }
}
