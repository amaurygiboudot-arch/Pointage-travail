package com.amaury.pointage

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ImageLibraryActivity : Activity() {
    private val requestUnlock = 7412

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminDiagnosticsGate.isEnabled(this)) {
            finish()
            return
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            Toast.makeText(this, "Configure un verrouillage Android pour protéger la bibliothèque.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val unlock = AdminDiagnosticsGate.deviceCredentialIntent(this, "Bibliothèque HoraTrack")
        if (unlock == null) finish() else startActivityForResult(unlock, requestUnlock)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestUnlock && resultCode == RESULT_OK) buildUi() else finish()
    }

    private fun buildUi() {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(20))
            setBackgroundColor(Color.rgb(245, 245, 245))
        }
        root.addView(TextView(this).apply {
            text = "BIBLIOTHÈQUE HORATRACK"
            gravity = Gravity.CENTER
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Bibliothèque privée des ressources visuelles. Export canonique : PNG RGBA."
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(12))
        })

        val exportAll = Button(this).apply {
            text = "EXPORTER TOUT DANS PICTURES/HoraTrack/Bibliotheque"
            isAllCaps = false
            setOnClickListener {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Toast.makeText(this@ImageLibraryActivity, "Export externe automatique disponible à partir d’Android 10.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                isEnabled = false
                val (ok, total) = CanonicalImageLibrary.exportAll(this@ImageLibraryActivity)
                Toast.makeText(this@ImageLibraryActivity, "$ok/$total images exportées en PNG", Toast.LENGTH_LONG).show()
                isEnabled = true
            }
        }
        root.addView(exportAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(12) })

        CanonicalImageLibrary.items(this).forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.WHITE)
            }
            val preview = ImageView(this).apply {
                setImageResource(item.resId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                adjustViewBounds = true
            }
            card.addView(preview, LinearLayout.LayoutParams(dp(82), dp(70)))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = item.name
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
            })
            info.addView(TextView(this).apply {
                text = "${item.sourceType} • ${item.width}×${item.height} • export PNG RGBA"
                textSize = 11f
                setTextColor(Color.DKGRAY)
            })
            val export = Button(this).apply {
                text = "Exporter PNG"
                isAllCaps = false
                textSize = 11f
                setOnClickListener {
                    val ok = CanonicalImageLibrary.exportOne(this@ImageLibraryActivity, item)
                    Toast.makeText(this@ImageLibraryActivity, if (ok) "${item.name}.png exporté" else "Échec export", Toast.LENGTH_SHORT).show()
                }
            }
            info.addView(export, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }
}
