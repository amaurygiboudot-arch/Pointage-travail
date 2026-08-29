package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

/**
 * Lanceur minimal, indépendant de l'interface principale.
 *
 * L'écran de bienvenue s'affiche uniquement lors de la première installation.
 * Les mises à jour conservent l'état de navigation existant et ouvrent
 * directement l'application, sauf si le mode récupération doit être prioritaire.
 */
class LaunchActivity : Activity() {

    companion object {
        private const val PREFS = "welcome_preview"
        private const val KEY_LAST_VERSION_SHOWN = "last_version_shown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CrashRecoveryManager.shouldOpenRecovery(this)) {
            openActivity(RecoveryActivityV2::class.java)
            return
        }

        if (shouldShowWelcomeForCurrentVersion()) showWelcome() else openMain()
    }

    private fun openActivity(target: Class<out Activity>) {
        startActivity(Intent(this, target))
        finish()
    }

    /**
     * Compatibilité avec les anciennes versions qui ont pu enregistrer le
     * versionCode en Int. SharedPreferences#getLong lance un ClassCastException
     * dans ce cas ; on lit donc la valeur brute puis on la normalise en Long.
     */
    private fun readLastShownVersion(): Long {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.all[KEY_LAST_VERSION_SHOWN] ?: return -1L
        val value = when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Short -> raw.toLong()
            is Byte -> raw.toLong()
            is String -> raw.toLongOrNull() ?: -1L
            is Number -> raw.toLong()
            else -> -1L
        }
        if (value >= 0L && raw !is Long) {
            prefs.edit().putLong(KEY_LAST_VERSION_SHOWN, value).apply()
        }
        return value
    }

    private fun currentVersionCode(): Long = if (android.os.Build.VERSION.SDK_INT >= 28) {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
    }

    private fun shouldShowWelcomeForCurrentVersion(): Boolean {
        return readLastShownVersion() < 0L
    }

    private fun markWelcomeShown() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_VERSION_SHOWN, currentVersionCode())
            .apply()
    }

    private fun showWelcome() {
        val theme = AppThemeCatalog.current(this)
        val night = AppThemeCatalog.useDarkPalette(this)
        val screenBackground = if (night) theme.darkBackground else theme.lightBackground
        val panel = if (night) theme.darkPanel else theme.lightPanel
        val textColor = if (night) theme.darkText else theme.lightText
        val secondary = if (night) theme.darkHint else theme.lightHint
        val gold = theme.accent
        val goldLight = theme.accentLight
        val accentDisplay = if (night) goldLight else gold

        window.statusBarColor = screenBackground
        window.navigationBarColor = screenBackground

        fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(screenBackground)
        }

        content.addView(Space(this), LinearLayout.LayoutParams(1, dp(12)))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = rounded(panel, 24, accentDisplay)
            elevation = dp(8).toFloat()
        }
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(this).apply {
            text = "♛"
            gravity = Gravity.CENTER
            textSize = 38f
            includeFontPadding = true
            setTextColor(accentDisplay)
        })

        card.addView(TextView(this).apply {
            text = "BIENVENUE SUR HP TRAVAIL"
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentDisplay)
            maxLines = 3
            setPadding(0, dp(8), 0, dp(14))
        })

        card.addView(TextView(this).apply {
            text = "Merci d’avoir téléchargé HP Travail.\n\nL’application est conçue pour simplifier le suivi de ton temps de travail, de tes heures et de ton activité professionnelle.\n\nBonne utilisation !"
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 15f
            setTextColor(textColor)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(2), 0, dp(2), dp(18))
        })

        card.addView(TextView(this).apply {
            text = "Merci pour ta confiance ✨"
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, 0, 0, dp(18))
        })

        val startButton = Button(this).apply {
            text = "COMMENCER"
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (night) theme.lightText else Color.WHITE)
            background = rounded(if (night) gold else gold, 14)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener {
                isEnabled = false
                animate().alpha(0f).setDuration(140L).withEndAction {
                    markWelcomeShown()
                    openMain()
                }.start()
            }
        }
        card.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(2)
        })

        content.addView(Space(this), LinearLayout.LayoutParams(1, dp(18)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            alpha = 0f
            animate().alpha(1f).setDuration(260L).start()
        }

        setContentView(scroll)
    }

    private fun openMain() {
        openActivity(MainActivity::class.java)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
