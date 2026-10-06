package com.amaury.pointage

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.Executors
import com.amaury.pointage.billing.BillingContract
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import com.amaury.pointage.billing.BillingPdfGate
import com.google.firebase.auth.FirebaseAuth

class PdfPreviewActivity : Activity() {
    companion object {
        private const val REQUEST_SAVE = 4102
        private val exportWorker = Executors.newSingleThreadExecutor()
    }
    private lateinit var pdfFile: File
    private var fileName: String = "Pointage.pdf"
    private var documentAccountUid: String? = null
    private var resumed = false
    private var pendingFile: File? = null
    private var pendingHash: String? = null
    private var pickerRequested = false
    private var destination: Uri? = null
    private var stateSaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_preview)
        pdfFile = File(intent.getStringExtra("pdf_path") ?: "")
        fileName = intent.getStringExtra("pdf_name") ?: "Pointage.pdf"
        documentAccountUid = if (savedInstanceState != null) savedInstanceState.getString("pdf_owner") else currentUid()
        if (documentAccountUid == null || documentAccountUid != currentUid()) {
            if (savedInstanceState?.getBoolean("save_picker", false) == true) {
                savedInstanceState.getString("save_uri")?.let(Uri::parse)?.takeIf { it.scheme == "content" }?.let { uri -> exportWorker.execute { cleanup(uri) } }
            }
            finish(); return
        }
        pendingFile = savedInstanceState?.getString("save_file")?.let(::File)
        pendingHash = savedInstanceState?.getString("save_hash")
        pickerRequested = savedInstanceState?.getBoolean("save_picker", false) ?: false
        destination = savedInstanceState?.getString("save_uri")?.let(Uri::parse)
        findViewById<Button>(R.id.pdfPreviewBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.pdfPreviewSave).setOnClickListener { savePdf() }
        findViewById<Button>(R.id.pdfPreviewSave).isEnabled = false
        destination?.let { copyToDestination(it) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        stateSaved = false
        val currentUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (currentUid != documentAccountUid) { finish(); return }
        // The existing picker/copy is finalized by read-only authorization only.
        if (pickerRequested || destination != null) return
        BillingPdfGate.require(this, pdfFile, displayName = fileName) { authorizedFile ->
            if (!resumed || documentAccountUid != currentUid()) return@require
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
        val uid = documentAccountUid ?: return
        if (!active(uid) || pickerRequested) return
        BillingPdfGate.require(this, pdfFile, displayName = fileName) { authorizedFile ->
            if (!active(uid) || pickerRequested || stateSaved) return@require
            runCatching {
                pdfFile = authorizedFile
                pendingFile = authorizedFile
                pendingHash = BillingContract.documentId(authorizedFile)
                pickerRequested = true
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_TITLE, fileName)
                }, REQUEST_SAVE)
            }.onFailure { clearSave(); toast("Impossible d'ouvrir le choix d'emplacement.") }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (uri?.scheme == "content") cleanup(uri)
            clearSave()
            return
        }
        if (uri.scheme != "content") { clearSave(); toast("Emplacement PDF invalide."); return }
        destination = uri
        runCatching { contentResolver.takePersistableUriPermission(uri,
            (data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) }
        copyToDestination(uri)
    }

    private fun copyToDestination(uri: Uri) {
        val uid = documentAccountUid
        val file = pendingFile
        val hash = pendingHash
        if (uid == null || file == null || hash == null || !pickerRequested || uri.scheme != "content") {
            cleanup(uri); clearSave(); toast("Export annulé : autorisation à vérifier."); return
        }
        exportWorker.execute {
            val result = runCatching {
                check(active(uid))
                check(BillingContract.documentId(file) == hash)
                // Read-only: never offer another purchase after the destination exists.
                check(BillingPdfGate.authorizeBackgroundBlocking(this, file))
                check(active(uid) && BillingContract.documentId(file) == hash)
                val output = contentResolver.openOutputStream(uri, "w") ?: error("Destination inaccessible")
                output.use { out -> file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        check(active(uid))
                        out.write(buffer, 0, size)
                    }
                } }
                check(active(uid) && BillingContract.documentId(file) == hash)
            }
            if (result.isFailure && !isChangingConfigurations) cleanup(uri)
            runOnUiThread {
                if (isDestroyed || isChangingConfigurations) return@runOnUiThread
                clearSave()
                if (result.isSuccess && resumed && active(uid)) {
                    pdfFile = file
                    findViewById<LinearLayout>(R.id.pdfPagesContainer).removeAllViews()
                    runCatching { renderPdf() }
                    findViewById<Button>(R.id.pdfPreviewSave).isEnabled = true
                } else if (currentUid() == uid) {
                    // Retry is an explicit click; never reopen offers from this callback.
                    findViewById<Button>(R.id.pdfPreviewSave).isEnabled = true
                } else finish()
                toast(if (result.isSuccess) "PDF enregistré" else "Impossible d'enregistrer le PDF. Le document incomplet a été supprimé si le fournisseur le permet.")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        stateSaved = true
        outState.putString("pdf_owner", documentAccountUid)
        outState.putString("save_file", pendingFile?.absolutePath)
        outState.putString("save_hash", pendingHash)
        outState.putBoolean("save_picker", pickerRequested)
        outState.putString("save_uri", destination?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun active(uid: String) = !isFinishing && !isDestroyed && !isChangingConfigurations && currentUid() == uid
    private fun currentUid() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
    private fun cleanup(uri: Uri) { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }
    private fun clearSave() { pendingFile = null; pendingHash = null; pickerRequested = false; destination = null }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
