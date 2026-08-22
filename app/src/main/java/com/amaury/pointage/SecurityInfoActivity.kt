package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest

class SecurityInfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "🛡 SÉCURITÉ DE L'APPLICATION"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        })

        val pkg = packageName
        val packageInfo = packageManager.getPackageInfo(pkg, 0)
        val versionName = packageInfo.versionName.orEmpty()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()

        addCard(root, "Version installée", "$versionName  (code $versionCode)")
        addCard(root, "Identifiant de l'application", pkg)
        addCard(root, "Certificat SHA-256", signingCertificateSha256())
        addCard(root, "Firebase App Check", appCheckStatus())
        addCard(root, "Installation d'autres applications", if (declaresPermission(Manifest.permission.REQUEST_INSTALL_PACKAGES)) "⚠ Permission présente" else "✓ Permission absente")
        addCard(root, "Trafic réseau non chiffré", "✓ Désactivé par la configuration de HP Travail")
        addCard(root, "Localisation en arrière-plan", if (declaresPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) "Utilisée pour le pointage GPS automatique" else "Non demandée")
        addCard(root, "Notifications", if (declaresPermission(Manifest.permission.POST_NOTIFICATIONS)) "Permission déclarée" else "Non demandées")

        root.addView(TextView(this).apply {
            text = "Cet écran affiche des informations techniques vérifiables. Il ne prétend pas qu'une version est approuvée définitivement par Play Protect ou MI Protect."
            textSize = 13f
            setPadding(dp(6), dp(16), dp(6), dp(12))
        })

        val snakeButton = Button(this).apply {
            text = "🐍 JEU DU SERPENT"
            isAllCaps = false
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { SnakeGameDialog.show(this@SecurityInfoActivity) }
        }
        root.addView(snakeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        revealOwnerGame(snakeButton)

        root.addView(Button(this).apply {
            text = "OUVRIR LA VERSION OFFICIELLE GITHUB"
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/amaurygiboudot-arch/Pointage-travail/releases/latest")))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        root.addView(Button(this).apply {
            text = "FERMER"
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        AppearanceManager.apply(this)
    }

    private fun revealOwnerGame(button: Button) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
            .addOnSuccessListener { profile ->
                button.visibility = if (profile.getBoolean("owner") == true) View.VISIBLE else View.GONE
            }
    }

    private fun addCard(parent: LinearLayout, title: String, value: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.hp_panel)
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.parseColor("#D6A84B"))
        })
        box.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setPadding(0, dp(5), 0, 0)
            setTextIsSelectable(true)
        })
        parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    private fun appCheckStatus(): String {
        val prefs = getSharedPreferences("app_check_status", MODE_PRIVATE)
        return when (prefs.getString("state", null)) {
            "valid" -> "✓ Jeton Play Integrity obtenu"
            "initializing" -> "Attestation Play Integrity en cours…"
            "error" -> "✗ Échec d'attestation\n${prefs.getString("error", "Erreur inconnue").orEmpty()}"
            else -> "Pas encore testé sur cette installation"
        }
    }

    private fun declaresPermission(permission: String): Boolean {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions?.contains(permission) == true
    }

    private fun signingCertificateSha256(): String {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
            } ?: return@runCatching "Indisponible"
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString(":") { "%02X".format(it) }
        }.getOrDefault("Indisponible")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
