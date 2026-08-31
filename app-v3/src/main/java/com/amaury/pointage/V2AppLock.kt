package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.LinearLayout
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Verrouillage local : aucun PIN brut n'est stocké. */
object V2AppLock {
    private const val PREFS = "v2_app_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BIOMETRIC = "biometric"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_SCHEME = "pin_scheme"
    private const val KEY_TIMEOUT_MIN = "timeout_min"
    private const val KEY_LAST_UNLOCK = "last_unlock_ms"

    private const val SCHEME_PBKDF2_SHA256 = "pbkdf2-sha256-v1"
    private const val SCHEME_PBKDF2_SHA1 = "pbkdf2-sha1-v1"
    private const val PBKDF2_ITERATIONS = 210_000
    private const val PBKDF2_BITS = 256
    private const val SALT_BYTES = 16

    private val promptVisible = AtomicBoolean(false)
    private val secureRandom = SecureRandom()

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!shouldProtect(activity)) return
                if (!needsUnlock(activity) || !promptVisible.compareAndSet(false, true)) return
                activity.window.decorView.post {
                    authenticate(
                        activity,
                        onSuccess = { markUnlocked(activity); promptVisible.set(false) },
                        onCancel = { promptVisible.set(false); activity.moveTaskToBack(true) }
                    )
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun shouldProtect(activity: Activity): Boolean = when (activity) {
        is LaunchActivity -> false
        is OwnerEnrollmentActivity -> false
        else -> activity.packageName == "com.amaury.pointage"
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun biometricEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BIOMETRIC, false)
    fun timeoutMinutes(context: Context): Int = prefs(context).getInt(KEY_TIMEOUT_MIN, 5).coerceIn(1, 120)
    fun hasPin(context: Context): Boolean = !prefs(context).getString(KEY_PIN_HASH, null).isNullOrBlank()

    fun setTimeoutMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_TIMEOUT_MIN, minutes.coerceIn(1, 120)).apply()
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !hasPin(context) && !biometricEnabled(context)) return false
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) markUnlocked(context)
        return true
    }

    fun setPin(context: Context, pin: String): Boolean {
        val clean = pin.filter(Char::isDigit)
        if (clean.length !in 4..8) return false
        return storePbkdf2Pin(context, clean)
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val clean = pin.filter(Char::isDigit)
        if (clean.length !in 4..8) return false

        val p = prefs(context)
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val expected = p.getString(KEY_PIN_HASH, null) ?: return false
        val scheme = p.getString(KEY_PIN_SCHEME, null)

        if (scheme.isNullOrBlank()) {
            // Migration transparente de l'ancien SHA-256 salé : le PIN n'est jamais remis à zéro.
            val legacyOk = constantTimeEquals(expected, legacyHash(salt, clean))
            if (legacyOk) storePbkdf2Pin(context, clean)
            return legacyOk
        }

        val actual = runCatching { pbkdf2Hash(scheme, salt, clean) }.getOrNull() ?: return false
        return constantTimeEquals(expected, actual)
    }

    fun needsUnlock(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isEnabled(context)) return false
        val last = safeLong(prefs(context).all[KEY_LAST_UNLOCK])
        return last <= 0L || nowMs - last >= timeoutMinutes(context) * 60_000L
    }

    fun markUnlocked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_UNLOCK, System.currentTimeMillis()).apply()
    }

    fun authenticate(activity: Activity, onSuccess: () -> Unit, onCancel: () -> Unit) {
        if (biometricEnabled(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            showBiometric(activity, onSuccess, onCancel)
        } else if (hasPin(activity)) {
            showPinDialog(activity, onSuccess, onCancel)
        } else {
            setEnabled(activity, false)
            onSuccess()
        }
    }

    private fun showBiometric(activity: Activity, onSuccess: () -> Unit, onCancel: () -> Unit) {
        val cancellation = CancellationSignal()
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Déverrouiller HoraTrack")
            .setSubtitle("Confirme ton identité")
        builder.setNegativeButton(
            if (hasPin(activity)) "Utiliser le PIN" else "Annuler",
            activity.mainExecutor
        ) { _, _ ->
            cancellation.cancel()
            if (hasPin(activity)) showPinDialog(activity, onSuccess, onCancel) else onCancel()
        }
        builder.build().authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                    errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && hasPin(activity)
                ) showPinDialog(activity, onSuccess, onCancel)
                else if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) onCancel()
            }
        })
    }

    private fun showPinDialog(activity: Activity, onSuccess: () -> Unit, onCancel: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val d = resources.displayMetrics.density
            setPadding((20*d).toInt(), (8*d).toInt(), (20*d).toInt(), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("HoraTrack verrouillé")
            .setView(box)
            .setPositiveButton("Déverrouiller", null)
            .setNegativeButton("Fermer") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (verifyPin(activity, input.text.toString())) {
                    dialog.setOnCancelListener(null)
                    dialog.dismiss()
                    onSuccess()
                } else input.error = "PIN incorrect"
            }
        }
        dialog.show()
    }

    private fun storePbkdf2Pin(context: Context, cleanPin: String): Boolean {
        val saltBytes = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val preferredScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) SCHEME_PBKDF2_SHA256 else SCHEME_PBKDF2_SHA1
        val derived = runCatching { preferredScheme to pbkdf2Hash(preferredScheme, salt, cleanPin) }.getOrElse {
            if (preferredScheme == SCHEME_PBKDF2_SHA1) return false
            runCatching { SCHEME_PBKDF2_SHA1 to pbkdf2Hash(SCHEME_PBKDF2_SHA1, salt, cleanPin) }.getOrNull() ?: return false
        }
        prefs(context).edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, derived.second)
            .putString(KEY_PIN_SCHEME, derived.first)
            .apply()
        return true
    }

    private fun pbkdf2Hash(scheme: String, saltBase64: String, pin: String): String {
        val algorithm = when (scheme) {
            SCHEME_PBKDF2_SHA256 -> "PBKDF2WithHmacSHA256"
            SCHEME_PBKDF2_SHA1 -> "PBKDF2WithHmacSHA1"
            else -> throw IllegalArgumentException("Schéma PIN inconnu")
        }
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_BITS)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun safeLong(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun legacyHash(salt: String, pin: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$salt:$pin".toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
