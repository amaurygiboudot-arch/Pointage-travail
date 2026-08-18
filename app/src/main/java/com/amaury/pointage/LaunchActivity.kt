package com.amaury.pointage

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Lanceur volontairement minimal. Il ne dépend pas de l'interface principale.
 * Après un crash récent il ouvre le mode récupération au lieu de relancer
 * immédiatement une éventuelle boucle de crash.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (CrashRecoveryManager.shouldOpenRecovery(this)) {
            RecoveryActivity::class.java
        } else {
            MainActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
