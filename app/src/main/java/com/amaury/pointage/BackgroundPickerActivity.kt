package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class BackgroundPickerActivity : Activity() {
    companion object { private const val REQUEST_IMAGE = 9011 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) { finish(); return }
        val uri = data?.data ?: run { finish(); return }
        val bitmap = runCatching { contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(this, "Impossible d'ouvrir cette image", Toast.LENGTH_LONG).show(); finish(); return
        }
        showCropEditor(bitmap)
    }

    private fun showCropEditor(bitmap: Bitmap) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val title = TextView(this).apply {
            text = "CADRER L'IMAGE DE FOND"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        val help = TextView(this).apply {
            text = "Déplace l'image avec un doigt et pince avec deux doigts pour agrandir ou réduire. La zone visible sera conservée."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        val crop = CropImageView(this).apply { setBitmap(bitmap) }
        val preview = FrameLayout(this).apply {
            addView(crop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val previewParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = Button(this).apply { text = "ANNULER"; setOnClickListener { finish() } }
        val save = Button(this).apply {
            text = "UTILISER CETTE ZONE"
            setOnClickListener {
                runCatching {
                    val target = File(filesDir, AppearanceManager.BACKGROUND_FILE)
                    val result = crop.renderVisibleArea()
                    target.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit()
                        .putBoolean("custom_image_bg", true).apply()
                    Toast.makeText(this@BackgroundPickerActivity, "Image de fond enregistrée", Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure {
                    Toast.makeText(this@BackgroundPickerActivity, "Impossible d'enregistrer cette image", Toast.LENGTH_LONG).show()
                }
            }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(4) })
        buttons.addView(save, LinearLayout.LayoutParams(0, dp(52), 2f).apply { marginStart = dp(4) })
        root.addView(title)
        root.addView(help)
        root.addView(preview, previewParams)
        root.addView(buttons)
        setContentView(root)
        AppearanceManager.apply(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class CropImageView(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
        private var source: Bitmap? = null
        private val imageMatrixLocal = Matrix()
        private var minScale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = currentScale()
                val wanted = (old * detector.scaleFactor).coerceIn(minScale, minScale * 6f)
                val factor = wanted / old
                imageMatrixLocal.postScale(factor, factor, detector.focusX, detector.focusY)
                constrain()
                imageMatrix = imageMatrixLocal
                return true
            }
        })

        init { scaleType = ScaleType.MATRIX; setBackgroundColor(android.graphics.Color.BLACK) }

        fun setBitmap(bitmap: Bitmap) {
            source = bitmap
            setImageBitmap(bitmap)
            post { fitInitial() }
        }

        private fun fitInitial() {
            val b = source ?: return
            if (width == 0 || height == 0) return
            val sx = width.toFloat() / b.width
            val sy = height.toFloat() / b.height
            minScale = maxOf(sx, sy)
            imageMatrixLocal.reset()
            imageMatrixLocal.postScale(minScale, minScale)
            imageMatrixLocal.postTranslate((width - b.width * minScale) / 2f, (height - b.height * minScale) / 2f)
            imageMatrix = imageMatrixLocal
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; dragging = true }
                MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    imageMatrixLocal.postTranslate(dx, dy); constrain(); imageMatrix = imageMatrixLocal
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        private fun currentScale(): Float {
            val values = FloatArray(9); imageMatrixLocal.getValues(values); return values[Matrix.MSCALE_X]
        }

        private fun constrain() {
            val d = drawable ?: return
            val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            imageMatrixLocal.mapRect(rect)
            var dx = 0f; var dy = 0f
            if (rect.left > 0) dx = -rect.left
            if (rect.right < width) dx = width - rect.right
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < height) dy = height - rect.bottom
            imageMatrixLocal.postTranslate(dx, dy)
        }

        fun renderVisibleArea(): Bitmap {
            val outW = width.coerceAtLeast(1)
            val outH = height.coerceAtLeast(1)
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(output)
            canvas.drawColor(android.graphics.Color.BLACK)
            source?.let { canvas.drawBitmap(it, imageMatrixLocal, null) }
            return output
        }
    }
}
