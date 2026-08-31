package com.amaury.pointage

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast

class SalaryPayslipPhotoActivity : Activity() {
    companion object { private const val REQUEST_CAMERA = 9701 }
    private var photoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "HoraTrack_bulletin_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        photoUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val uri = photoUri
        if (uri == null) {
            Toast.makeText(this, "Impossible de préparer la photo", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (camera.resolveActivity(packageManager) == null) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "Aucune application appareil photo disponible", Toast.LENGTH_LONG).show()
            finish(); return
        }
        startActivityForResult(camera, REQUEST_CAMERA)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAMERA) return
        val uri = photoUri
        if (resultCode != RESULT_OK || uri == null) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            finish(); return
        }
        startActivity(Intent(this, V2PayslipImportActivity::class.java).apply {
            putExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID, intent.getStringExtra(V2PayslipImportActivity.EXTRA_COMPANY_ID).orEmpty())
            putExtra(V2PayslipImportActivity.EXTRA_COMPANY_NAME, intent.getStringExtra(V2PayslipImportActivity.EXTRA_COMPANY_NAME).orEmpty())
            putExtra(V2PayslipImportActivity.EXTRA_SOURCE_URI, uri.toString())
            putExtra(V2PayslipImportActivity.EXTRA_SOURCE_MIME, "image/jpeg")
        })
        finish()
    }
}
