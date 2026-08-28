package com.amaury.pointage

import android.content.Context
import android.os.Build
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Centralise les rapports techniques et les idées utilisateurs.
 * Les diagnostics automatiques sont assainis avant envoi et restent soumis
 * au consentement explicite de l'utilisateur.
 */
object TelemetryManager {
    private const val PREFS = "telemetry_settings"
    private const val KEY_CRASH_REPORTS = "crash_reports_enabled"
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized || BuildConfig.SENTRY_DSN.isBlank()) return

        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.isSendDefaultPii = false
            options.tracesSampleRate = 0.0
            options.isEnableAutoSessionTracking = false
            options.setBeforeSend(SentryOptions.BeforeSendCallback { event, _ ->
                event.user = null
                event.request = null

                val voluntaryFeedback = event.getTag("hp_type") == "feedback"
                if (!voluntaryFeedback && !crashReportsEnabled(context)) return@BeforeSendCallback null

                // Les crashs automatiques ne quittent jamais le téléphone avec le
                // Throwable brut : on conserve uniquement type + pile de code limitée.
                if (!voluntaryFeedback) {
                    event.throwable?.let { event.throwable = DiagnosticSanitizer.safeThrowable(it) }
                    event.breadcrumbs?.clear()
                    event.contexts.clear()
                }
                event
            })
        }
        initialized = true
    }

    fun isConfigured(): Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    fun crashReportsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASH_REPORTS, false)

    fun setCrashReportsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CRASH_REPORTS, enabled)
            .apply()
        initialize(context)
    }

    /** Une idée est envoyée uniquement après action explicite de l'utilisateur. */
    fun sendIdea(context: Context, idea: String): Boolean {
        if (BuildConfig.SENTRY_DSN.isBlank()) return false
        initialize(context)

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

        Sentry.withScope { scope ->
            scope.setTag("hp_type", "feedback")
            scope.setTag("hp_action", "review_only")
            scope.setTag("owner_approval_required", "true")
            scope.setTag("auto_code_change_allowed", "false")
            scope.setTag("app_version", version.take(80))
            scope.setTag("android_version", Build.VERSION.RELEASE.orEmpty().take(80))
            scope.setTag("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120))
            scope.setExtra("idea", idea.take(4000))
            scope.setExtra("governance", "Suggestion uniquement. Toute modification nécessite l'approbation explicite du propriétaire de HoraTrack.")
            Sentry.captureMessage("Suggestion utilisateur HoraTrack", SentryLevel.INFO)
        }
        return true
    }
}
