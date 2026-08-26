package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.content.res.AppCompatResources
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class StandardButtonLiveConfig(
    val backgroundR: Int = 35,
    val backgroundG: Int = 35,
    val backgroundB: Int = 35,
    val backgroundAlpha: Int = 255,
    val frameR: Int = 214,
    val frameG: Int = 168,
    val frameB: Int = 75,
    val frameAlpha: Int = 255,
    val frameWidthDp: Float = 2f,
    val cornerRadiusDp: Float = 24f,
    val textR: Int = 255,
    val textG: Int = 255,
    val textB: Int = 255,
    val textAlpha: Int = 255,
    val textSizeSp: Float = 14f,
    val horizontalPaddingDp: Float = 14f,
    val verticalPaddingDp: Float = 4f,
    val backgroundImageAlpha: Int = 255,
    val frameImageAlpha: Int = 255,
    val backgroundImageUri: String = "",
    val frameImageUri: String = "",
    val backgroundImageResName: String = "",
    val frameImageResName: String = ""
)

object StandardButtonLiveStyle {
    private const val PREFS = "developer_standard_button_live_v1"
    private const val DEV_TAG = "horatrack_dev_live_control"

    fun current(c: Context): StandardButtonLiveConfig {
        val p = c.getSharedPreferences(PREFS, 0)
        return StandardButtonLiveConfig(
            p.getInt("backgroundR", 35),
            p.getInt("backgroundG", 35),
            p.getInt("backgroundB", 35),
            p.getInt("backgroundAlpha", 255),
            p.getInt("frameR", 214),
            p.getInt("frameG", 168),
            p.getInt("frameB", 75),
            p.getInt("frameAlpha", 255),
            p.getFloat("frameWidthDp", 2f),
            p.getFloat("cornerRadiusDp", 24f),
            p.getInt("textR", 255),
            p.getInt("textG", 255),
            p.getInt("textB", 255),
            p.getInt("textAlpha", 255),
            p.getFloat("textSizeSp", 14f),
            p.getFloat("horizontalPaddingDp", 14f),
            p.getFloat("verticalPaddingDp", 4f),
            p.getInt("backgroundImageAlpha", 255),
            p.getInt("frameImageAlpha", 255),
            p.getString("backgroundImageUri", "").orEmpty(),
            p.getString("frameImageUri", "").orEmpty(),
            p.getString("backgroundImageResName", "").orEmpty(),
            p.getString("frameImageResName", "").orEmpty()
        )
    }

    fun setInt(c: Context, k: String, v: Int) = c.getSharedPreferences(PREFS, 0).edit().putInt(k, v).commit()
    fun setFloat(c: Context, k: String, v: Float) = c.getSharedPreferences(PREFS, 0).edit().putFloat(k, v).commit()

    fun setImage(c: Context, frame: Boolean, uri: Uri?): Boolean = c.getSharedPreferences(PREFS, 0).edit()
        .putString(if (frame) "frameImageUri" else "backgroundImageUri", uri?.toString().orEmpty())
        .putString(if (frame) "frameImageResName" else "backgroundImageResName", "")
        .commit()

    fun setBundledImage(c: Context, frame: Boolean, resName: String?): Boolean = c.getSharedPreferences(PREFS, 0).edit()
        .putString(if (frame) "frameImageResName" else "backgroundImageResName", resName.orEmpty())
        .putString(if (frame) "frameImageUri" else "backgroundImageUri", "")
        .commit()

    fun saveCurrent(c: Context): Boolean {
        val x = current(c)
        return c.getSharedPreferences(PREFS, 0).edit()
            .putInt("backgroundR", x.backgroundR)
            .putInt("backgroundG", x.backgroundG)
            .putInt("backgroundB", x.backgroundB)
            .putInt("backgroundAlpha", x.backgroundAlpha)
            .putInt("frameR", x.frameR)
            .putInt("frameG", x.frameG)
            .putInt("frameB", x.frameB)
            .putInt("frameAlpha", x.frameAlpha)
            .putFloat("frameWidthDp", x.frameWidthDp)
            .putFloat("cornerRadiusDp", x.cornerRadiusDp)
            .putInt("textR", x.textR)
            .putInt("textG", x.textG)
            .putInt("textB", x.textB)
            .putInt("textAlpha", x.textAlpha)
            .putFloat("textSizeSp", x.textSizeSp)
            .putFloat("horizontalPaddingDp", x.horizontalPaddingDp)
            .putFloat("verticalPaddingDp", x.verticalPaddingDp)
            .putInt("backgroundImageAlpha", x.backgroundImageAlpha)
            .putInt("frameImageAlpha", x.frameImageAlpha)
            .putString("backgroundImageUri", x.backgroundImageUri)
            .putString("frameImageUri", x.frameImageUri)
            .putString("backgroundImageResName", x.backgroundImageResName)
            .putString("frameImageResName", x.frameImageResName)
            .putLong("lastExplicitSaveAt", System.currentTimeMillis())
            .commit()
    }

    fun reset(c: Context) = c.getSharedPreferences(PREFS, 0).edit().clear().commit()
    fun markDeveloperControl(v: View) { v.tag = DEV_TAG }

    fun isProtected(b: Button): Boolean {
        if (b.tag == DEV_TAG || b is RedDiamondFinalButton || b is LightReactiveJewelButton) return true
        val n = runCatching { b.resources.getResourceEntryName(b.id) }.getOrNull().orEmpty()
        return n == "entryButton" || n == "pauseButton" || n == "exitButton"
    }

    private fun drawableByName(c: Context, name: String): Drawable? {
        if (name.isBlank()) return null
        val id = c.resources.getIdentifier(name, "drawable", c.packageName)
        return if (id != 0) AppCompatResources.getDrawable(c, id)?.mutate() else null
    }

    private fun drawableByUri(c: Context, value: String): Drawable? {
        if (value.isBlank()) return null
        return runCatching {
            c.contentResolver.openInputStream(Uri.parse(value))?.use {
                Drawable.createFromStream(it, "button-live-image")
            }
        }.getOrNull()?.mutate()
    }

    fun applyToButton(c: Context, b: Button) {
        if (isProtected(b)) return
        val x = current(c)
        val d = c.resources.displayMetrics.density
        val base = GradientDrawable().apply {
            cornerRadius = x.cornerRadiusDp * d
            setColor(Color.argb(x.backgroundAlpha, x.backgroundR, x.backgroundG, x.backgroundB))
            setStroke((x.frameWidthDp * d).roundToInt(), Color.argb(x.frameAlpha, x.frameR, x.frameG, x.frameB))
        }
        val layers = mutableListOf<Drawable>(base)
        (drawableByName(c, x.backgroundImageResName) ?: drawableByUri(c, x.backgroundImageUri))?.also {
            it.alpha = x.backgroundImageAlpha.coerceIn(0, 255)
            layers.add(it)
        }
        (drawableByName(c, x.frameImageResName) ?: drawableByUri(c, x.frameImageUri))?.also {
            it.alpha = x.frameImageAlpha.coerceIn(0, 255)
            layers.add(it)
        }
        b.background = LayerDrawable(layers.toTypedArray()).apply {
            for (i in 0 until numberOfLayers) setLayerGravity(i, Gravity.FILL)
        }
        b.backgroundTintList = null
        b.setTextColor(Color.argb(x.textAlpha, x.textR, x.textG, x.textB))
        b.textSize = x.textSizeSp
        b.isAllCaps = false
        b.setPadding(
            (x.horizontalPaddingDp * d).roundToInt(),
            (x.verticalPaddingDp * d).roundToInt(),
            (x.horizontalPaddingDp * d).roundToInt(),
            (x.verticalPaddingDp * d).roundToInt()
        )
    }

    fun applyTree(c: Context, v: View) {
        if (v is Button) applyToButton(c, v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) applyTree(c, v.getChildAt(i))
        }
    }

    fun imageName(c: Context, u: String) = if (u.isBlank()) "Aucune" else Uri.parse(u).lastPathSegment ?: u

    fun bundledDrawableNames(): List<String> = R.drawable::class.java.fields
        .mapNotNull { field -> runCatching { field.name }.getOrNull() }
        .filterNot { it.startsWith("abc_") || it.startsWith("notification_") }
        .distinct()
        .sorted()
}

object DeveloperStandardButtonPanel {
    private data class Spec(
        val tab: String,
        val label: String,
        val key: String,
        val min: Float,
        val max: Float,
        val integer: Boolean = false,
        val get: (StandardButtonLiveConfig) -> Float
    )

    private val tabs = listOf("FOND", "CADRE", "TEXTE", "IMAGES")

    private fun isNight(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun primaryText(c: Context) = if (isNight(c)) Color.WHITE else Color.rgb(28, 28, 28)
    private fun secondaryText(c: Context) = if (isNight(c)) Color.rgb(205, 216, 232) else Color.rgb(70, 70, 70)
    private fun panelColor(c: Context) = if (isNight(c)) Color.argb(246, 8, 10, 14) else Color.argb(252, 248, 244, 234)

    private fun controlBackground(c: Context) = GradientDrawable().apply {
        cornerRadius = 22f * c.resources.displayMetrics.density
        setColor(if (isNight(c)) Color.rgb(24, 26, 30) else Color.rgb(255, 252, 245))
        setStroke((2f * c.resources.displayMetrics.density).roundToInt(), Color.rgb(190, 145, 55))
    }

    fun show(a: MainActivity) {
        if (!AdminDiagnosticsGate.isEnabled(a)) return
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).roundToInt()

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panelColor(a))
        }

        fun txt(s: String, size: Float = 12f) = TextView(a).apply {
            text = s
            textSize = size
            setTextColor(primaryText(a))
        }

        root.addView(txt("🎛 RÉGLAGES LIVE — BOUTONS STANDARDS", 15f).apply { gravity = Gravity.CENTER })
        root.addView(txt("Une seule source maître pour cadre, fond, texte et images", 11f).apply {
            setTextColor(secondaryText(a))
            gravity = Gravity.CENTER
        })

        val row = LinearLayout(a)
        root.addView(row)
        val host = FrameLayout(a)
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        val pages = linkedMapOf<String, ScrollView>()
        val tabBtns = linkedMapOf<String, Button>()

        fun devButton(label: String) = Button(a).apply {
            text = label
            isAllCaps = false
            setTextColor(primaryText(a))
            background = controlBackground(a)
            backgroundTintList = null
            StandardButtonLiveStyle.markDeveloperControl(this)
        }

        fun showTab(n: String) {
            pages.forEach { (k, v) -> v.visibility = if (k == n) View.VISIBLE else View.GONE }
            tabBtns.forEach { (k, b) ->
                b.alpha = if (k == n) 1f else .72f
                b.setTextColor(primaryText(a))
            }
        }

        val specs = listOf(
            Spec("FOND","Rouge","backgroundR",0f,255f,true){it.backgroundR.toFloat()},Spec("FOND","Vert","backgroundG",0f,255f,true){it.backgroundG.toFloat()},Spec("FOND","Bleu","backgroundB",0f,255f,true){it.backgroundB.toFloat()},Spec("FOND","Opacité","backgroundAlpha",0f,255f,true){it.backgroundAlpha.toFloat()},
            Spec("CADRE","Rouge","frameR",0f,255f,true){it.frameR.toFloat()},Spec("CADRE","Vert","frameG",0f,255f,true){it.frameG.toFloat()},Spec("CADRE","Bleu","frameB",0f,255f,true){it.frameB.toFloat()},Spec("CADRE","Opacité","frameAlpha",0f,255f,true){it.frameAlpha.toFloat()},Spec("CADRE","Épaisseur dp","frameWidthDp",0f,12f){it.frameWidthDp},Spec("CADRE","Arrondi dp","cornerRadiusDp",0f,48f){it.cornerRadiusDp},
            Spec("TEXTE","Rouge","textR",0f,255f,true){it.textR.toFloat()},Spec("TEXTE","Vert","textG",0f,255f,true){it.textG.toFloat()},Spec("TEXTE","Bleu","textB",0f,255f,true){it.textB.toFloat()},Spec("TEXTE","Opacité","textAlpha",0f,255f,true){it.textAlpha.toFloat()},Spec("TEXTE","Taille sp","textSizeSp",9f,26f){it.textSizeSp},Spec("TEXTE","Padding horizontal","horizontalPaddingDp",0f,30f){it.horizontalPaddingDp},Spec("TEXTE","Padding vertical","verticalPaddingDp",0f,20f){it.verticalPaddingDp},
            Spec("IMAGES","Opacité image fond","backgroundImageAlpha",0f,255f,true){it.backgroundImageAlpha.toFloat()},Spec("IMAGES","Opacité image cadre","frameImageAlpha",0f,255f,true){it.frameImageAlpha.toFloat()}
        )

        tabs.forEach { tab ->
            val tb = devButton(tab)
            tb.setOnClickListener { showTab(tab) }
            tabBtns[tab] = tb
            row.addView(tb, LinearLayout.LayoutParams(0, dp(40), 1f))

            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            specs.filter { it.tab == tab }.forEach { addControl(a, col, it) }

            if (tab == "IMAGES") {
                val selected = txt("", 11f).apply {
                    setTextColor(secondaryText(a))
                    setPadding(0, dp(4), 0, dp(8))
                }

                fun refreshSelected() {
                    val c = StandardButtonLiveStyle.current(a)
                    val bg = c.backgroundImageResName.ifBlank { StandardButtonLiveStyle.imageName(a, c.backgroundImageUri) }
                    val frame = c.frameImageResName.ifBlank { StandardButtonLiveStyle.imageName(a, c.frameImageUri) }
                    selected.text = "Fond : $bg\nCadre : $frame"
                }

                refreshSelected()
                col.addView(selected)
                col.addView(txt("BIBLIOTHÈQUE DU DÉPÔT — touche FOND ou CADRE sous une vignette pour l'appliquer immédiatement.", 11f).apply {
                    setTextColor(secondaryText(a))
                    setPadding(0, 0, 0, dp(6))
                })

                val grid = GridLayout(a).apply {
                    columnCount = 3
                    alignmentMode = GridLayout.ALIGN_BOUNDS
                    useDefaultMargins = true
                }

                StandardButtonLiveStyle.bundledDrawableNames().forEach { name ->
                    val id = a.resources.getIdentifier(name, "drawable", a.packageName)
                    if (id == 0) return@forEach

                    val card = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(3), dp(3), dp(3), dp(5))
                        background = controlBackground(a)
                    }
                    val preview = ImageView(a).apply {
                        setImageResource(id)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        adjustViewBounds = true
                        contentDescription = name
                    }
                    card.addView(preview, LinearLayout.LayoutParams(-1, dp(64)))
                    card.addView(txt(name, 9f).apply {
                        gravity = Gravity.CENTER
                        maxLines = 2
                    }, LinearLayout.LayoutParams(-1, dp(34)))

                    val choose = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                    val asBg = devButton("FOND").apply {
                        textSize = 9f
                        setOnClickListener {
                            StandardButtonLiveStyle.setBundledImage(a, false, name)
                            applyNow(a)
                            refreshSelected()
                            Toast.makeText(a, "$name appliqué au fond", Toast.LENGTH_SHORT).show()
                        }
                    }
                    val asFrame = devButton("CADRE").apply {
                        textSize = 9f
                        setOnClickListener {
                            StandardButtonLiveStyle.setBundledImage(a, true, name)
                            applyNow(a)
                            refreshSelected()
                            Toast.makeText(a, "$name appliqué au cadre", Toast.LENGTH_SHORT).show()
                        }
                    }
                    choose.addView(asBg, LinearLayout.LayoutParams(0, dp(34), 1f))
                    choose.addView(asFrame, LinearLayout.LayoutParams(0, dp(34), 1f))
                    card.addView(choose)
                    grid.addView(card, GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    })
                }
                col.addView(grid)

                val clearRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                clearRow.addView(devButton("RETIRER FOND").apply {
                    setOnClickListener {
                        StandardButtonLiveStyle.setBundledImage(a, false, null)
                        StandardButtonLiveStyle.setImage(a, false, null)
                        applyNow(a)
                        refreshSelected()
                    }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                clearRow.addView(devButton("RETIRER CADRE").apply {
                    setOnClickListener {
                        StandardButtonLiveStyle.setBundledImage(a, true, null)
                        StandardButtonLiveStyle.setImage(a, true, null)
                        applyNow(a)
                        refreshSelected()
                    }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                col.addView(clearRow)
            }

            val p = ScrollView(a).apply {
                addView(col)
                visibility = View.GONE
            }
            pages[tab] = p
            host.addView(p)
        }

        val actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val save = devButton("ENREGISTRER")
        val report = devButton("RAPPORT")
        val reset = devButton("RÉINITIALISER")
        val close = devButton("FERMER")
        actions.addView(save, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(report, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(reset, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(close, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(actions)

        val dialog = AlertDialog.Builder(a).setView(root).create()
        save.setOnClickListener {
            if (StandardButtonLiveStyle.saveCurrent(a)) {
                applyNow(a)
                Toast.makeText(a, "Réglages live enregistrés", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(a, "Échec de l'enregistrement", Toast.LENGTH_LONG).show()
            }
        }
        report.setOnClickListener { StandardButtonDeveloperReport.share(a) }
        reset.setOnClickListener {
            StandardButtonLiveStyle.reset(a)
            applyNow(a)
            dialog.dismiss()
            show(a)
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setGravity(Gravity.TOP)
                setLayout(-1, (a.resources.displayMetrics.heightPixels * .56f).roundToInt())
            }
            showTab("FOND")
            applyNow(a)
        }
        dialog.show()
    }

    private fun addControl(a: MainActivity, p: LinearLayout, s: Spec) {
        val label = TextView(a).apply {
            setTextColor(primaryText(a))
            textSize = 12f
        }
        val bar = SeekBar(a).apply { max = 1000 }

        fun value(progress: Int): Float = s.min + (s.max - s.min) * progress / 1000f
        fun progress(v: Float): Int = (((v - s.min) / (s.max - s.min)) * 1000f).roundToInt().coerceIn(0, 1000)
        fun showValue(v: Float) {
            label.text = if (s.integer) {
                "${s.label} : ${v.roundToInt()}"
            } else {
                String.format(Locale.FRANCE, "%s : %.2f", s.label, v)
            }
        }

        val initial = s.get(StandardButtonLiveStyle.current(a))
        bar.progress = progress(initial)
        showValue(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = value(progress)
                if (s.integer) {
                    StandardButtonLiveStyle.setInt(a, s.key, v.roundToInt())
                } else {
                    StandardButtonLiveStyle.setFloat(a, s.key, v)
                }
                showValue(v)
                applyNow(a)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        p.addView(label)
        p.addView(bar)
    }

    private fun applyNow(a: MainActivity) {
        StandardButtonLiveStyle.applyTree(a, a.window.decorView)
        a.window.decorView.invalidate()
    }
}

object StandardButtonDeveloperReport {
    fun build(a: MainActivity): String {
        val c = StandardButtonLiveStyle.current(a)
        val bg = c.backgroundImageResName.ifBlank { c.backgroundImageUri.ifBlank { "AUCUNE" } }
        val frame = c.frameImageResName.ifBlank { c.frameImageUri.ifBlank { "AUCUNE" } }
        return buildString {
            appendLine("HORATRACK — RAPPORT STYLE BOUTONS STANDARDS")
            appendLine("Généré : ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())}")
            appendLine("FOND RGBA=${c.backgroundR},${c.backgroundG},${c.backgroundB},${c.backgroundAlpha}")
            appendLine("CADRE RGBA=${c.frameR},${c.frameG},${c.frameB},${c.frameAlpha} largeur=${c.frameWidthDp}dp arrondi=${c.cornerRadiusDp}dp")
            appendLine("TEXTE RGBA=${c.textR},${c.textG},${c.textB},${c.textAlpha} taille=${c.textSizeSp}sp padding=${c.horizontalPaddingDp}/${c.verticalPaddingDp}dp")
            appendLine("Image fond=$bg")
            append("Image cadre=$frame")
        }
    }

    fun share(a: MainActivity) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "HoraTrack — rapport style boutons")
            putExtra(Intent.EXTRA_TEXT, build(a))
        }
        a.startActivity(Intent.createChooser(intent, "Partager le rapport"))
    }
}
