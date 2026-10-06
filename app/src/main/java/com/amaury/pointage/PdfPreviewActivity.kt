package com.amaury.pointage

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import com.amaury.pointage.billing.BillingPdfGate
import com.google.firebase.auth.FirebaseAuth

class PdfPreviewActivity : Activity() {
    companion object { private const val REQUEST_SAVE = 4102 }
    private lateinit var pdfFile: File
    private var fileName: String = "Pointage.pdf"
    private var documentAccountUid: String? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_preview)
        pdfFile = File(intent.getStringExtra("pdf_path") ?: "")
        fileName = intent.getStringExtra("pdf_name") ?: "Pointage.pdf"
        documentAccountUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        findViewById<Button>(R.id.pdfPreviewBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.pdfPreviewSave).setOnClickListener { savePdf() }
        findViewById<Button>(R.id.pdfPreviewSave).isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val currentUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (currentUid != documentAccountUid) { finish(); return }
        BillingPdfGate.require(this, pdfFile, displayName = fileName) { authorizedFile ->
            if (!resumed) return@require
            pdfFile = authorizedFile
            findViewById<LinearLayout>(R.id.pdfPagesContainer).removeAllViews()
            runCatching { renderPdf() }.onFailure {
                Toast.makeText(this, "Impossible d'afficher ce PDF", Toast.LENGTH_LONG).show()
            }.onSuccess { findViewById<Button>(R.id.pdfPreviewSave).isEnabled = true }
        }
    }

    override fun onPause() {
        resumed = false
        findViewById<LinearLayout>(R.id.pdfPagesContainer).removeAllViews()
        findViewById<Button>(R.id.pdfPreviewSave).isEnabled = false
        super.onPause()
    }

    private fun renderPdf() {
        val container = findViewById<LinearLayout>(R.id.pdfPagesContainer)
        if (!pdfFile.exists()) { Toast.makeText(this, "PDF introuvable", Toast.LENGTH_LONG).show(); finish(); return }
        val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        try {
            val targetWidth = resources.displayMetrics.widthPixels - dp(24)
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val scale = targetWidth.toFloat() / page.width.toFloat()
                val bmp = Bitmap.createBitmap(targetWidth, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                container.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    setPadding(0, dp(6), 0, dp(6))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        } finally { renderer.close(); fd.close() }
    }

    private fun savePdf() {
        BillingPdfGate.require(this, pdfFile, displayName = fileName) { authorizedFile ->
            pdfFile = authorizedFile
            requestSaveDestination()
        }
    }

    private fun requestSaveDestination() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        startActivityForResult(intent, REQUEST_SAVE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        BillingPdfGate.require(this, pdfFile, displayName = fileName) { authorizedFile -> runCatching {
            contentResolver.openOutputStream(uri)?.use { out -> authorizedFile.inputStream().use { it.copyTo(out) } }
        }.onSuccess { Toast.makeText(this, "PDF enregistré", Toast.LENGTH_LONG).show() }
         .onFailure { Toast.makeText(this, "Impossible d'enregistrer le PDF", Toast.LENGTH_LONG).show() } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
