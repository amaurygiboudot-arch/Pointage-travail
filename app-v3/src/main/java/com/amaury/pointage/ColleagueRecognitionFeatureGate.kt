package com.amaury.pointage

import android.content.Context

/**
 * Private feature gate for the colleague-recognition experiment.
 *
 * The feature is intentionally unavailable to normal users. It is exposed only
 * on devices where the existing owner/developer diagnostics mode has already
 * been enabled. This keeps the experimental recognition system out of the
 * public product until its legal/privacy design is validated.
 */
object ColleagueRecognitionFeatureGate {
    fun isAvailable(context: Context): Boolean = AdminDiagnosticsGate.isEnabled(context)

    fun requireAvailable(context: Context) {
        check(isAvailable(context)) {
            "Colleague recognition is restricted to the owner test mode."
        }
    }
}
