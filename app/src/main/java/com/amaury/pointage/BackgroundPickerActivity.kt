package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                runCatching {
                    val target = File(filesDir, AppearanceManager.BACKGROUND_FILE)
                    contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    getSharedPreferences("appearance_settings", Context.MODE_PRIVATE).edit()
                        .putBoolean("custom_image_bg", true)
                        .apply()
                    Toast.makeText(this, "Image de fond enregistrée", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Impossible d'enregistrer cette image", Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }
}
