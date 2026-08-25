package com.amaury.pointage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class OwnerEnrollmentActivity : Activity() {
    private val requestCode = 7501

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AdminDiagnosticsGate.isEnabled(this)) {
            finish()
            return
        }
        val intent = AdminDiagnosticsGate.deviceCredentialIntent(this, "Activer le mode Développeur")
        if (intent == null) {
            Toast.makeText(this, "Configure d’abord un verrouillage Android sur ce téléphone.", Toast.LENGTH_LONG).show()
            finish()
        } else {
            startActivityForResult(intent, requestCode)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            AdminDiagnosticsGate.enable(this)
            Toast.makeText(this, "Mode Développeur activé sur ce téléphone.", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
