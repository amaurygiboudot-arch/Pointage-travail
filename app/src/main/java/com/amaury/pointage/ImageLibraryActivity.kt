package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ImageLibraryActivity : Activity() {
    private val requestUnlock = 7412

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminDiagnosticsGate.isEnabled(this)) { finish(); return }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            Toast.makeText(this, "Configure un verrouillage Android pour protéger la bibliothèque.", Toast.LENGTH_LONG).show()
            finish(); return
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
            text = "Liste privée : drawables Android + anciens Base64. Aperçu à la demande. Format maître : PNG RGBA."
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(10))
        })

        val exportAll = Button(this).apply {
            text = "EXPORTER TOUT EN PNG"
            isAllCaps = false
            setOnClickListener {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Toast.makeText(this@ImageLibraryActivity, "Export externe automatique disponible à partir d’Android 10.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                isEnabled = false
                val (ok, total) = CanonicalImageLibrary.exportAll(this@ImageLibraryActivity)
                Toast.makeText(this@ImageLibraryActivity, "$ok/$total visuels exportés dans ${CanonicalImageLibrary.RELATIVE_DIR}", Toast.LENGTH_LONG).show()
                isEnabled = true
            }
        }
        root.addView(exportAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        val search = EditText(this).apply {
            hint = "Rechercher un nom ou un type…"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8); bottomMargin = dp(8) })

        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listHost)
        val all = CanonicalImageLibrary.allItems(this)

        fun renderList(query: String) {
            listHost.removeAllViews()
            val q = query.trim().lowercase()
            val filtered = all.filter { q.isBlank() || it.name.lowercase().contains(q) || it.sourceType.lowercase().contains(q) }
            if (filtered.isEmpty()) {
                listHost.addView(TextView(this).apply { text = "Aucun visuel trouvé"; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20)) })
                return
            }
            filtered.forEach { item ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(8), dp(8))
                    setBackgroundColor(Color.WHITE)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showItem(item) }
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this).apply {
                    text = item.name
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                })
                info.addView(TextView(this).apply {
                    text = "${item.sourceType} • ${item.width}×${item.height} • PNG RGBA"
                    textSize = 11f
                    setTextColor(Color.DKGRAY)
                })
                line.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                line.addView(TextView(this).apply { text = "›"; textSize = 26f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(32), dp(44)))
                listHost.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderList(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderList("")

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
    }

    private fun showItem(item: CanonicalImageLibrary.Item) {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val bitmap = CanonicalImageLibrary.renderPngBitmap(this, item)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        box.addView(ImageView(this).apply {
            bitmap?.let { setImageBitmap(it) }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            setBackgroundColor(Color.rgb(238, 238, 238))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
        box.addView(TextView(this).apply {
            text = "Nom : ${item.name}\nOrigine : ${item.sourceType}\nDimensions : ${item.width}×${item.height}\nSortie maître : PNG RGBA transparent\nLive direct : ${if (item.selectableInLive) "oui" else "après conversion PNG"}\nDossier : ${CanonicalImageLibrary.RELATIVE_DIR}"
            setTextColor(Color.BLACK)
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("Détail du visuel")
            .setView(box)
            .setPositiveButton("EXPORTER PNG") { _, _ ->
                val ok = CanonicalImageLibrary.exportOne(this, item)
                Toast.makeText(this, if (ok) "${item.name}.png exporté" else "Échec export", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("FERMER", null)
            .create()
        dialog.setOnDismissListener { bitmap?.recycle() }
        dialog.show()
    }
}
