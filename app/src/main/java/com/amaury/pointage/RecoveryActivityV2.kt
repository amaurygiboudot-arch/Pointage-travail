package com.amaury.pointage

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class RecoveryActivityV2 : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private lateinit var adminButton: Button
    private var ownerTapCount = 0
    private val requestEnroll = 7401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.alpha = 1f
        window.setBackgroundDrawableResource(android.R.color.white)
        buildUi()
        RecoveryUpdater.checkAndRepair(this, status, progress, retry)
    }

    private fun buildUi() {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        fun buttonBg(enabled: Boolean = true) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(if (enabled) Color.rgb(24, 24, 24) else Color.rgb(105, 105, 105))
        }
        fun styleButton(button: Button) {
            button.isAllCaps = false
            button.textSize = 15f
            button.setTextColor(Color.WHITE)
            button.backgroundTintList = null
            button.background = buttonBg(button.isEnabled)
            button.alpha = 1f
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(38), dp(24), dp(28))
            setBackgroundColor(Color.rgb(250, 250, 250))
        }
        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(250, 250, 250))
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val brand = TextView(this).apply {
            text = "♛  HP TRAVAIL"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 20, 20))
            setOnClickListener {
                ownerTapCount++
                if (ownerTapCount >= 7) {
                    ownerTapCount = 0
                    requestOwnerEnrollment()
                }
            }
        }
        content.addView(brand)

        content.addView(TextView(this).apply {
            text = "MODE RÉCUPÉRATION"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(170, 25, 25))
            setPadding(0, dp(12), 0, dp(22))
        })

        status = TextView(this).apply {
            text = "Vérification d'une mise à jour de réparation…"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(dp(4), 0, dp(4), dp(18))
        }
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))

        val errorDetails = TextView(this).apply {
            text = CrashRecoveryManager.getLastCrashReport(this@RecoveryActivityV2)
            textSize = 13f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(235, 235, 235))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        content.addView(errorDetails, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        content.addView(Button(this).apply {
            text = "VOIR L’ERREUR"
            styleButton(this)
            setOnClickListener {
                val showing = errorDetails.visibility == View.VISIBLE
                errorDetails.visibility = if (showing) View.GONE else View.VISIBLE
                text = if (showing) "VOIR L’ERREUR" else "MASQUER L’ERREUR"
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(18) })

        content.addView(Button(this).apply {
            text = "PARTAGER DANS LA BOÎTE À IDÉES"
            styleButton(this)
            setOnClickListener {
                val report = CrashRecoveryManager.getLastCrashReport(this@RecoveryActivityV2)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "HP Travail — rapport d’erreur")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(share, "Partager le rapport d’erreur"))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        adminButton = Button(this).apply {
            text = "🔐 DIAGNOSTIC DÉVELOPPEUR"
            styleButton(this)
            visibility = if (AdminDiagnosticsGate.isEnabled(this@RecoveryActivityV2)) View.VISIBLE else View.GONE
            setOnClickListener { startActivity(Intent(this@RecoveryActivityV2, AdminDiagnosticsActivity::class.java)) }
        }
        content.addView(adminButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        retry = Button(this).apply {
            text = "RÉESSAYER LA RÉPARATION"
            isEnabled = false
            styleButton(this)
            setOnClickListener { RecoveryUpdater.checkAndRepair(this@RecoveryActivityV2, status, progress, this) }
        }
        content.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        content.addView(Button(this).apply {
            text = "OUVRIR HP TRAVAIL QUAND MÊME"
            styleButton(this)
            setOnClickListener {
                CrashRecoveryManager.clear(this@RecoveryActivityV2)
                startActivity(Intent(this@RecoveryActivityV2, MainActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        setContentView(root)
    }

    private fun requestOwnerEnrollment() {
        if (AdminDiagnosticsGate.isEnabled(this)) {
            Toast.makeText(this, "Diagnostic développeur déjà activé sur ce téléphone.", Toast.LENGTH_SHORT).show()
            adminButton.visibility = View.VISIBLE
            return
        }
        val intent = AdminDiagnosticsGate.deviceCredentialIntent(this, "Activer le diagnostic développeur")
        if (intent == null) {
            Toast.makeText(this, "Configure un code, schéma ou empreinte Android avant d’activer cette zone privée.", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(intent, requestEnroll)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestEnroll && resultCode == RESULT_OK) {
            AdminDiagnosticsGate.enable(this)
            adminButton.visibility = View.VISIBLE
            Toast.makeText(this, "Zone développeur privée activée sur ce téléphone.", Toast.LENGTH_LONG).show()
        }
    }
}
