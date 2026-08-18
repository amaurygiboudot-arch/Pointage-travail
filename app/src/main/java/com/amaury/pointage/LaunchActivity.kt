package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
 * Mode test actuel : l'écran de bienvenue s'affiche une fois par version
 * installée afin de vérifier son rendu après chaque mise à jour.
 */
class LaunchActivity : Activity() {

    companion object {
        private const val PREFS = "welcome_preview"
        private const val KEY_LAST_VERSION_SHOWN = "last_version_shown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CrashRecoveryManager.shouldOpenRecovery(this)) {
            openActivity(RecoveryActivity::class.java)
            return
        }

        if (shouldShowWelcomeForCurrentVersion()) showWelcome() else openMain()
    }

    private fun openActivity(target: Class<out Activity>) {
        startActivity(Intent(this, target))
        finish()
    }

    private fun shouldShowWelcomeForCurrentVersion(): Boolean {
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastShown = prefs.getLong(KEY_LAST_VERSION_SHOWN, -1L)
        return lastShown != versionCode
    }

    private fun markWelcomeShown() {
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_VERSION_SHOWN, versionCode)
            .apply()
    }

    private fun showWelcome() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val background = Color.parseColor(if (night) "#080808" else "#F4F0E7")
        val panel = Color.parseColor(if (night) "#151515" else "#FFFDF8")
        val textColor = Color.parseColor(if (night) "#F7F2E8" else "#17130E")
        val secondary = Color.parseColor(if (night) "#CFC6B4" else "#5D5549")
        val gold = Color.parseColor("#D6A84B")
        val goldLight = Color.parseColor("#F3D58A")

        window.statusBarColor = background
        window.navigationBarColor = background

        fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(background)
        }

        content.addView(Space(this), LinearLayout.LayoutParams(1, dp(12)))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = rounded(panel, 24, gold)
            elevation = dp(8).toFloat()
        }
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(this).apply {
            text = "♛"
            gravity = Gravity.CENTER
            textSize = 38f
            includeFontPadding = true
            setTextColor(gold)
        })

        card.addView(TextView(this).apply {
            text = "BIENVENUE SUR HP TRAVAIL"
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(goldLight)
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
            setTextColor(Color.parseColor("#17110A"))
            background = rounded(goldLight, 14)
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
