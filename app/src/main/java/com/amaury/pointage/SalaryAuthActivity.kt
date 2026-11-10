package com.amaury.pointage

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Activity transparente dédiée à l'authentification de l'espace Salaire. */
class SalaryAuthActivity : SalaryAccountActivity() {
    companion object {
        const val EXTRA_COMPANY_ID = "company_id"
        const val EXTRA_LEGACY_RECOVERY = "salary_legacy_recovery"
        private const val REQUEST_DEVICE_AUTH = 4717
    }

    private var companyId: String = ""
    private var authStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!accountReady()) return
        companyId = intent.getStringExtra(EXTRA_COMPANY_ID).orEmpty()
        if (companyId.isBlank() && !intent.getBooleanExtra(EXTRA_LEGACY_RECOVERY, false)) { finish(); return }
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
            "AGKGMG — accès sécurisé",
            if (intent.getBooleanExtra(EXTRA_LEGACY_RECOVERY, false))
                "Authentifie-toi pour confirmer la reprise des anciennes données salariales."
            else "Authentifie-toi pour ouvrir les informations détaillées de l’entreprise."
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
        if (!accountReady()) return
        if (requestCode == REQUEST_DEVICE_AUTH) {
            if (resultCode == RESULT_OK) salaryAccountLease?.let {
                if (intent.getBooleanExtra(EXTRA_LEGACY_RECOVERY, false)) PendingSalaryCompanyAccess.authorizeRecovery(it)
                else PendingSalaryCompanyAccess.authorize(companyId, it)
            }
            finish()
        }
    }
}
