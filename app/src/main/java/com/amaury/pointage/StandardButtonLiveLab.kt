package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import java.util.WeakHashMap
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
    val buttonShape: String = "ROUNDED",
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
    val frameImageResName: String = "",
    val imageWidthPercent: Float = 100f,
    val imageHeightPercent: Float = 100f,
    val imageOffsetXPercent: Float = 0f,
    val imageOffsetYPercent: Float = 0f,
    val keepImageAspect: Boolean = true,
    val imageScaleMode: String = "FIT",
    val imageShape: String = "BUTTON",
    val imageCornerRadiusDp: Float = 18f,
    val nightImageDimPercent: Int = 35
)

object StandardButtonLiveStyle {
    private const val PREFS = "developer_standard_button_live_v1"
    private const val DEV_TAG = "horatrack_dev_live_control"
    private val managedButtons = WeakHashMap<Button, Boolean>()

    fun current(c: Context): StandardButtonLiveConfig {
        val p = c.getSharedPreferences(PREFS, 0)
        return StandardButtonLiveConfig(
            backgroundR = p.getInt("backgroundR", 35),
            backgroundG = p.getInt("backgroundG", 35),
            backgroundB = p.getInt("backgroundB", 35),
            backgroundAlpha = p.getInt("backgroundAlpha", 255),
            frameR = p.getInt("frameR", 214),
            frameG = p.getInt("frameG", 168),
            frameB = p.getInt("frameB", 75),
            frameAlpha = p.getInt("frameAlpha", 255),
            frameWidthDp = p.getFloat("frameWidthDp", 2f),
            cornerRadiusDp = p.getFloat("cornerRadiusDp", 24f),
            buttonShape = p.getString("buttonShape", "ROUNDED").orEmpty(),
            textR = p.getInt("textR", 255),
            textG = p.getInt("textG", 255),
            textB = p.getInt("textB", 255),
            textAlpha = p.getInt("textAlpha", 255),
            textSizeSp = p.getFloat("textSizeSp", 14f),
            horizontalPaddingDp = p.getFloat("horizontalPaddingDp", 14f),
            verticalPaddingDp = p.getFloat("verticalPaddingDp", 4f),
            backgroundImageAlpha = p.getInt("backgroundImageAlpha", 255),
            frameImageAlpha = p.getInt("frameImageAlpha", 255),
            backgroundImageUri = p.getString("backgroundImageUri", "").orEmpty(),
            frameImageUri = p.getString("frameImageUri", "").orEmpty(),
            backgroundImageResName = p.getString("backgroundImageResName", "").orEmpty(),
            frameImageResName = p.getString("frameImageResName", "").orEmpty(),
            imageWidthPercent = p.getFloat("imageWidthPercent", 100f),
            imageHeightPercent = p.getFloat("imageHeightPercent", 100f),
            imageOffsetXPercent = p.getFloat("imageOffsetXPercent", 0f),
            imageOffsetYPercent = p.getFloat("imageOffsetYPercent", 0f),
            keepImageAspect = p.getBoolean("keepImageAspect", true),
            imageScaleMode = p.getString("imageScaleMode", "FIT").orEmpty(),
            imageShape = p.getString("imageShape", "BUTTON").orEmpty(),
            imageCornerRadiusDp = p.getFloat("imageCornerRadiusDp", 18f),
            nightImageDimPercent = p.getInt("nightImageDimPercent", 35)
        )
    }

    fun setInt(c: Context, k: String, v: Int) = c.getSharedPreferences(PREFS, 0).edit().putInt(k, v).commit()
    fun setFloat(c: Context, k: String, v: Float) = c.getSharedPreferences(PREFS, 0).edit().putFloat(k, v).commit()
    fun setBoolean(c: Context, k: String, v: Boolean) = c.getSharedPreferences(PREFS, 0).edit().putBoolean(k, v).commit()
    fun setString(c: Context, k: String, v: String) = c.getSharedPreferences(PREFS, 0).edit().putString(k, v).commit()

    fun setImage(c: Context, frame: Boolean, uri: Uri?): Boolean = c.getSharedPreferences(PREFS, 0).edit()
        .putString(if (frame) "frameImageUri" else "backgroundImageUri", uri?.toString().orEmpty())
        .putString(if (frame) "frameImageResName" else "backgroundImageResName", "")
        .commit()

    fun setBundledImage(c: Context, frame: Boolean, resName: String?): Boolean = c.getSharedPreferences(PREFS, 0).edit()
        .putString(if (frame) "frameImageResName" else "backgroundImageResName", resName.orEmpty())
        .putString(if (frame) "frameImageUri" else "backgroundImageUri", "")
        .commit()

    fun saveCurrent(c: Context): Boolean = c.getSharedPreferences(PREFS, 0).edit()
        .putLong("lastExplicitSaveAt", System.currentTimeMillis())
        .commit()

    fun reset(c: Context) = c.getSharedPreferences(PREFS, 0).edit().clear().commit()
    fun markDeveloperControl(v: View) { v.tag = DEV_TAG }

    fun isProtected(b: Button): Boolean {
        if (b.tag == DEV_TAG || b is RedDiamondFinalButton || b is LightReactiveJewelButton) return true
        val n = runCatching { b.resources.getResourceEntryName(b.id) }.getOrNull().orEmpty()
        return n == "entryButton" || n == "pauseButton" || n == "exitButton"
    }

    fun isLiveManaged(v: View): Boolean = v is Button && v.background is LiveButtonCompositeDrawable

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
        val bgImage = drawableByName(c, x.backgroundImageResName) ?: drawableByUri(c, x.backgroundImageUri)
        val frameImage = drawableByName(c, x.frameImageResName) ?: drawableByUri(c, x.frameImageUri)
        b.backgroundTintList = null
        b.setLayerType(View.LAYER_TYPE_NONE, null)
        b.background = LiveButtonCompositeDrawable(
            config = x,
            density = d,
            night = AppThemeCatalog.useDarkPalette(c),
            backgroundImage = bgImage,
            frameImage = frameImage
        )
        b.setTextColor(Color.argb(x.textAlpha, x.textR, x.textG, x.textB))
        b.textSize = x.textSizeSp
        b.isAllCaps = false
        b.setPadding(
            (x.horizontalPaddingDp * d).roundToInt(),
            (x.verticalPaddingDp * d).roundToInt(),
            (x.horizontalPaddingDp * d).roundToInt(),
            (x.verticalPaddingDp * d).roundToInt()
        )

        if (managedButtons.put(b, true) != true) {
            b.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val button = view as? Button ?: return@addOnLayoutChangeListener
                if (!isProtected(button) && button.background !is LiveButtonCompositeDrawable) {
                    button.post { applyToButton(button.context, button) }
                }
            }
        }
    }

    fun applyTree(c: Context, v: View) {
        if (v is Button) applyToButton(c, v)
        if (v is ViewGroup) for (i in 0 until v.childCount) applyTree(c, v.getChildAt(i))
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
        fun devButton(label: String) = Button(a).apply {
            text = label
            isAllCaps = false
            setTextColor(primaryText(a))
            background = controlBackground(a)
            backgroundTintList = null
            StandardButtonLiveStyle.markDeveloperControl(this)
        }

        root.addView(txt("🎛 RÉGLAGES LIVE — BOUTONS STANDARDS", 15f).apply { gravity = Gravity.CENTER })
        root.addView(txt("Moteur maître : fond → image → cadre → texte", 11f).apply {
            setTextColor(secondaryText(a)); gravity = Gravity.CENTER
        })

        val row = LinearLayout(a)
        root.addView(row)
        val host = FrameLayout(a)
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        val pages = linkedMapOf<String, ScrollView>()
        val tabBtns = linkedMapOf<String, Button>()

        fun showTab(n: String) {
            pages.forEach { (k, v) -> v.visibility = if (k == n) View.VISIBLE else View.GONE }
            tabBtns.forEach { (k, b) -> b.alpha = if (k == n) 1f else .72f }
        }

        val specs = listOf(
            Spec("FOND","Rouge","backgroundR",0f,255f,true){it.backgroundR.toFloat()},
            Spec("FOND","Vert","backgroundG",0f,255f,true){it.backgroundG.toFloat()},
            Spec("FOND","Bleu","backgroundB",0f,255f,true){it.backgroundB.toFloat()},
            Spec("FOND","Opacité","backgroundAlpha",0f,255f,true){it.backgroundAlpha.toFloat()},
            Spec("CADRE","Rouge","frameR",0f,255f,true){it.frameR.toFloat()},
            Spec("CADRE","Vert","frameG",0f,255f,true){it.frameG.toFloat()},
            Spec("CADRE","Bleu","frameB",0f,255f,true){it.frameB.toFloat()},
            Spec("CADRE","Opacité","frameAlpha",0f,255f,true){it.frameAlpha.toFloat()},
            Spec("CADRE","Épaisseur dp","frameWidthDp",0f,12f){it.frameWidthDp},
            Spec("CADRE","Arrondi bouton dp","cornerRadiusDp",0f,60f){it.cornerRadiusDp},
            Spec("TEXTE","Rouge","textR",0f,255f,true){it.textR.toFloat()},
            Spec("TEXTE","Vert","textG",0f,255f,true){it.textG.toFloat()},
            Spec("TEXTE","Bleu","textB",0f,255f,true){it.textB.toFloat()},
            Spec("TEXTE","Opacité","textAlpha",0f,255f,true){it.textAlpha.toFloat()},
            Spec("TEXTE","Taille sp","textSizeSp",9f,26f){it.textSizeSp},
            Spec("TEXTE","Padding horizontal","horizontalPaddingDp",0f,30f){it.horizontalPaddingDp},
            Spec("TEXTE","Padding vertical","verticalPaddingDp",0f,20f){it.verticalPaddingDp},
            Spec("IMAGES","Opacité image fond","backgroundImageAlpha",0f,255f,true){it.backgroundImageAlpha.toFloat()},
            Spec("IMAGES","Opacité image cadre","frameImageAlpha",0f,255f,true){it.frameImageAlpha.toFloat()},
            Spec("IMAGES","Largeur image %","imageWidthPercent",5f,200f){it.imageWidthPercent},
            Spec("IMAGES","Hauteur image %","imageHeightPercent",5f,200f){it.imageHeightPercent},
            Spec("IMAGES","Position X %","imageOffsetXPercent",-100f,100f){it.imageOffsetXPercent},
            Spec("IMAGES","Position Y %","imageOffsetYPercent",-100f,100f){it.imageOffsetYPercent},
            Spec("IMAGES","Arrondi image dp","imageCornerRadiusDp",0f,80f){it.imageCornerRadiusDp},
            Spec("IMAGES","Assombrissement nuit %","nightImageDimPercent",0f,90f,true){it.nightImageDimPercent.toFloat()}
        )

        tabs.forEach { tab ->
            val tb = devButton(tab)
            tb.setOnClickListener { showTab(tab) }
            tabBtns[tab] = tb
            row.addView(tb, LinearLayout.LayoutParams(0, dp(40), 1f))

            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            specs.filter { it.tab == tab }.forEach { addControl(a, col, it) }

            if (tab == "CADRE") {
                col.addView(cycleButton(a, "Forme bouton", listOf("ROUNDED","RECT","CAPSULE","CIRCLE"), { StandardButtonLiveStyle.current(a).buttonShape }) {
                    StandardButtonLiveStyle.setString(a, "buttonShape", it); applyNow(a)
                })
            }

            if (tab == "IMAGES") {
                val ratio = CheckBox(a).apply {
                    text = "Conserver le ratio de l'image"
                    setTextColor(primaryText(a))
                    isChecked = StandardButtonLiveStyle.current(a).keepImageAspect
                    setOnCheckedChangeListener { _, checked ->
                        StandardButtonLiveStyle.setBoolean(a, "keepImageAspect", checked)
                        applyNow(a)
                    }
                }
                col.addView(ratio)
                col.addView(cycleButton(a, "Mode image", listOf("FIT","FILL","STRETCH"), { StandardButtonLiveStyle.current(a).imageScaleMode }) {
                    StandardButtonLiveStyle.setString(a, "imageScaleMode", it); applyNow(a)
                })
                col.addView(cycleButton(a, "Forme image", listOf("BUTTON","RECT","ROUNDED","CAPSULE","CIRCLE"), { StandardButtonLiveStyle.current(a).imageShape }) {
                    StandardButtonLiveStyle.setString(a, "imageShape", it); applyNow(a)
                })

                val selected = txt("", 11f).apply { setTextColor(secondaryText(a)); setPadding(0, dp(6), 0, dp(6)) }
                fun refreshSelected() {
                    val c = StandardButtonLiveStyle.current(a)
                    val bg = c.backgroundImageResName.ifBlank { StandardButtonLiveStyle.imageName(a, c.backgroundImageUri) }
                    val frame = c.frameImageResName.ifBlank { StandardButtonLiveStyle.imageName(a, c.frameImageUri) }
                    selected.text = "Fond : $bg\nCadre : $frame"
                }
                refreshSelected()
                col.addView(selected)
                col.addView(txt("BIBLIOTHÈQUE — liste compacte. Touche un nom pour l'aperçu et les actions.", 11f).apply { setTextColor(secondaryText(a)) })

                CanonicalImageLibrary.items(a).forEach { item ->
                    val line = devButton("${item.name}   •   ${item.sourceType}").apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        textSize = 11f
                        setOnClickListener { showImageChoice(a, item, refreshSelected = { refreshSelected(); applyNow(a) }) }
                    }
                    col.addView(line, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(3) })
                }

                val clearRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                clearRow.addView(devButton("RETIRER FOND").apply {
                    setOnClickListener {
                        StandardButtonLiveStyle.setBundledImage(a, false, null)
                        StandardButtonLiveStyle.setImage(a, false, null)
                        applyNow(a); refreshSelected()
                    }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                clearRow.addView(devButton("RETIRER CADRE").apply {
                    setOnClickListener {
                        StandardButtonLiveStyle.setBundledImage(a, true, null)
                        StandardButtonLiveStyle.setImage(a, true, null)
                        applyNow(a); refreshSelected()
                    }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                col.addView(clearRow)
            }

            val p = ScrollView(a).apply { addView(col); visibility = View.GONE }
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
                applyNow(a); Toast.makeText(a, "Réglages live enregistrés", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(a, "Échec de l'enregistrement", Toast.LENGTH_LONG).show()
        }
        report.setOnClickListener { StandardButtonDeveloperReport.share(a) }
        reset.setOnClickListener { StandardButtonLiveStyle.reset(a); applyNow(a); dialog.dismiss(); show(a) }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setGravity(Gravity.TOP)
                setLayout(-1, (a.resources.displayMetrics.heightPixels * .62f).roundToInt())
            }
            showTab("FOND"); applyNow(a)
        }
        dialog.show()
    }

    private fun cycleButton(a: MainActivity, label: String, values: List<String>, current: () -> String, onChange: (String) -> Unit): Button {
        fun redraw(b: Button) { b.text = "$label : ${current()}" }
        return Button(a).apply {
            isAllCaps = false
            setTextColor(primaryText(a))
            background = controlBackground(a)
            backgroundTintList = null
            StandardButtonLiveStyle.markDeveloperControl(this)
            redraw(this)
            setOnClickListener {
                val now = current()
                val next = values[(values.indexOf(now).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }]
                onChange(next); redraw(this)
            }
        }
    }

    private fun showImageChoice(a: MainActivity, item: CanonicalImageLibrary.Item, refreshSelected: () -> Unit) {
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).roundToInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        box.addView(ImageView(a).apply {
            setImageResource(item.resId)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(-1, dp(160)))
        box.addView(TextView(a).apply {
            text = "${item.name}\nOrigine : ${item.sourceType}\n${item.width}×${item.height} → PNG RGBA canonique à l'export"
            setTextColor(primaryText(a)); gravity = Gravity.CENTER
        })
        val d = AlertDialog.Builder(a)
            .setTitle("Bibliothèque")
            .setView(box)
            .setPositiveButton("FOND") { _, _ -> StandardButtonLiveStyle.setBundledImage(a, false, item.name); refreshSelected() }
            .setNeutralButton("CADRE") { _, _ -> StandardButtonLiveStyle.setBundledImage(a, true, item.name); refreshSelected() }
            .setNegativeButton("FERMER", null)
            .create()
        d.show()
    }

    private fun addControl(a: MainActivity, p: LinearLayout, s: Spec) {
        val label = TextView(a).apply { setTextColor(primaryText(a)); textSize = 12f }
        val bar = SeekBar(a).apply { max = 1000 }
        fun value(progress: Int): Float = s.min + (s.max - s.min) * progress / 1000f
        fun progress(v: Float): Int = (((v - s.min) / (s.max - s.min)) * 1000f).roundToInt().coerceIn(0, 1000)
        fun showValue(v: Float) { label.text = if (s.integer) "${s.label} : ${v.roundToInt()}" else String.format(Locale.FRANCE, "%s : %.2f", s.label, v) }
        val initial = s.get(StandardButtonLiveStyle.current(a))
        bar.progress = progress(initial); showValue(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = value(progress)
                if (s.integer) StandardButtonLiveStyle.setInt(a, s.key, v.roundToInt()) else StandardButtonLiveStyle.setFloat(a, s.key, v)
                showValue(v); applyNow(a)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        p.addView(label); p.addView(bar)
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
            appendLine("CADRE RGBA=${c.frameR},${c.frameG},${c.frameB},${c.frameAlpha} largeur=${c.frameWidthDp}dp arrondi=${c.cornerRadiusDp}dp forme=${c.buttonShape}")
            appendLine("TEXTE RGBA=${c.textR},${c.textG},${c.textB},${c.textAlpha} taille=${c.textSizeSp}sp padding=${c.horizontalPaddingDp}/${c.verticalPaddingDp}dp")
            appendLine("Image fond=$bg alpha=${c.backgroundImageAlpha}")
            appendLine("Image cadre=$frame alpha=${c.frameImageAlpha}")
            appendLine("Image taille=${c.imageWidthPercent}% x ${c.imageHeightPercent}% position=${c.imageOffsetXPercent}/${c.imageOffsetYPercent}%")
            appendLine("Image ratio=${c.keepImageAspect} mode=${c.imageScaleMode} forme=${c.imageShape} rayon=${c.imageCornerRadiusDp}dp")
            append("Image nuit assombrissement=${c.nightImageDimPercent}%")
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
