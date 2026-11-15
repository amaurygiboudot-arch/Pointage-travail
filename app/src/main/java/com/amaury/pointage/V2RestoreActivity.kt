package com.amaury.pointage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.amaury.pointage.v2.V2BackupManager

class V2RestoreActivity : SalaryAccountActivity() {
    companion object { private const val REQUEST_FILE = 9501 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!accountReady()) return
        if (savedInstanceState != null) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, REQUEST_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!accountReady()) { finish(); return }
        if (requestCode != REQUEST_FILE) return
        if (resultCode != RESULT_OK) { finish(); return }
        val uri = data?.data ?: run { finish(); return }
        val result = V2BackupManager.restoreFromUri(this, uri)
        Toast.makeText(
            this,
            result.fold(
                onSuccess = { it.summary() },
                onFailure = { "Restauration impossible : ${it.message ?: "erreur inconnue"}" }
            ),
            Toast.LENGTH_LONG
        ).show()
        PointageWidgetProvider.updateAll(this)
        QuickActionsWidgetProvider.updateAll(this)
        finish()
    }
}
