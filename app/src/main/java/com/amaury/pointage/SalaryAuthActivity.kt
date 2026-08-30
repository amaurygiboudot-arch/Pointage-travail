package com.amaury.pointage

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Activity transparente dédiée à l'authentification de l'espace Salaire. */
class SalaryAuthActivity : Activity() {
    companion object {
        const val EXTRA_COMPANY_ID = "company_id"
        private const val REQUEST_DEVICE_AUTH = 4717
    }

    private var companyId: String = ""
    private var authStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        companyId = intent.getStringExtra(EXTRA_COMPANY_ID).orEmpty()
        if (companyId.isBlank()) { finish(); return }
        if (savedInstanceState != null) authStarted = savedInstanceState.getBoolean("auth_started", false)
        if (!authStarted) startAuthentication()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("auth_started", authStarted)
        super.onSaveInstanceState(outState)
    }

    private fun startAuthentication() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            Toast.makeText(this, "Configure d’abord la sécurité Android pour accéder aux données Salaire", Toast.LENGTH_LONG).show()
            finish(); return
        }
        @Suppress("DEPRECATION")
        val authIntent = keyguard.createConfirmDeviceCredentialIntent(
            "HoraTrack — accès sécurisé",
            "Authentifie-toi pour ouvrir les informations détaillées de l’entreprise."
        )
        if (authIntent == null) {
            Toast.makeText(this, "Authentification Android indisponible", Toast.LENGTH_LONG).show()
            finish(); return
        }
        authStarted = true
        startActivityForResult(authIntent, REQUEST_DEVICE_AUTH)
    }

    @Deprecated("Résultat requis pour l'API d'authentification système utilisée avec Activity classique")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_AUTH) {
            if (resultCode == RESULT_OK) PendingSalaryCompanyAccess.authorizedCompanyId = companyId
            finish()
        }
    }
}
