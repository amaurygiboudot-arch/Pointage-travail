package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Réglages maître des boutons standards. Les 3 boutons diamant restent hors de ce moteur. */
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
    val frameImageUri: String = ""
)

object StandardButtonLiveStyle {
    private const val PREFS = "developer_standard_button_live_v1"
    const val REQUEST_BACKGROUND_IMAGE = 7311
    const val REQUEST_FRAME_IMAGE = 7312

    fun current(context: Context): StandardButtonLiveConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return StandardButtonLiveConfig(
            backgroundR = p.getInt("backgroundR", 35), backgroundG = p.getInt("backgroundG", 35), backgroundB = p.getInt("backgroundB", 35),
            backgroundAlpha = p.getInt("backgroundAlpha", 255), frameR = p.getInt("frameR", 214), frameG = p.getInt("frameG", 168),
            frameB = p.getInt("frameB", 75), frameAlpha = p.getInt("frameAlpha", 255), frameWidthDp = p.getFloat("frameWidthDp", 2f),
            cornerRadiusDp = p.getFloat("cornerRadiusDp", 24f), textR = p.getInt("textR", 255), textG = p.getInt("textG", 255),
            textB = p.getInt("textB", 255), textAlpha = p.getInt("textAlpha", 255), textSizeSp = p.getFloat("textSizeSp", 14f),
            horizontalPaddingDp = p.getFloat("horizontalPaddingDp", 14f), verticalPaddingDp = p.getFloat("verticalPaddingDp", 4f),
            backgroundImageAlpha = p.getInt("backgroundImageAlpha", 255), frameImageAlpha = p.getInt("frameImageAlpha", 255),
            backgroundImageUri = p.getString("backgroundImageUri", "").orEmpty(), frameImageUri = p.getString("frameImageUri", "").orEmpty()
        )
    }

    fun setFloat(context: Context, key: String, value: Float): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ok = p.edit().putFloat(key, value).commit()
        return ok && p.getFloat(key, Float.NaN) == value
    }

    fun setInt(context: Context, key: String, value: Int): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ok = p.edit().putInt(key, value).commit()
        return ok && p.getInt(key, Int.MIN_VALUE) == value
    }

    fun setImage(context: Context, frame: Boolean, uri: Uri?): Boolean {
        val key = if (frame) "frameImageUri" else "backgroundImageUri"
        val value = uri?.toString().orEmpty()
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ok = p.edit().putString(key, value).commit()
        return ok && p.getString(key, "").orEmpty() == value
    }

    fun reset(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()

    fun isProtected(button: Button): Boolean {
        if (button is RedDiamondFinalButton || button is LightReactiveJewelButton) return true
        val id = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        return id == "entryButton" || id == "pauseButton" || id == "exitButton"
    }

    fun applyToButton(context: Context, button: Button) {
        if (isProtected(button)) return
        val c = current(context)
        val density = context.resources.displayMetrics.density
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = c.cornerRadiusDp * density
            setColor(Color.argb(c.backgroundAlpha, c.backgroundR, c.backgroundG, c.backgroundB))
            setStroke((c.frameWidthDp * density).roundToInt().coerceAtLeast(0), Color.argb(c.frameAlpha, c.frameR, c.frameG, c.frameB))
        }
        val layers = mutableListOf<android.graphics.drawable.Drawable>(base)
        loadBitmapDrawable(context, c.backgroundImageUri, c.backgroundImageAlpha)?.let(layers::add)
        loadBitmapDrawable(context, c.frameImageUri, c.frameImageAlpha)?.let(layers::add)
        button.background = LayerDrawable(layers.toTypedArray())
        button.backgroundTintList = null
        button.setTextColor(Color.argb(c.textAlpha, c.textR, c.textG, c.textB))
        button.textSize = c.textSizeSp
        button.isAllCaps = false
        button.setPadding(
            (c.horizontalPaddingDp * density).roundToInt(),
            (c.verticalPaddingDp * density).roundToInt(),
            (c.horizontalPaddingDp * density).roundToInt(),
            (c.verticalPaddingDp * density).roundToInt()
        )
    }

    fun applyTree(context: Context, view: View) {
        if (view is Button) applyToButton(context, view)
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTree(context, view.getChildAt(i))
    }

    private fun loadBitmapDrawable(context: Context, uriText: String, alpha: Int): BitmapDrawable? {
        if (uriText.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(uriText)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input) ?: return@runCatching null
                BitmapDrawable(context.resources, bitmap).apply {
                    gravity = Gravity.FILL
                    this.alpha = alpha.coerceIn(0, 255)
                }
            }
        }.getOrNull()
    }

    fun handlePickerResult(activity: MainActivity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_BACKGROUND_IMAGE && requestCode != REQUEST_FRAME_IMAGE) return false
        if (resultCode != android.app.Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        setImage(activity, requestCode == REQUEST_FRAME_IMAGE, uri)
        applyTree(activity, activity.window.decorView)
        Toast.makeText(activity, "Image enregistrée et appliquée", Toast.LENGTH_SHORT).show()
        return true
    }

    fun pickImage(activity: MainActivity, frame: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        activity.startActivityForResult(intent, if (frame) REQUEST_FRAME_IMAGE else REQUEST_BACKGROUND_IMAGE)
    }

    fun imageName(context: Context, uriText: String): String {
        if (uriText.isBlank()) return "Aucune"
        return runCatching {
            context.contentResolver.query(Uri.parse(uriText), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uriText
            } ?: uriText
        }.getOrDefault(uriText)
    }
}

object DeveloperStandardButtonPanel {
    private data class Spec(val tab: String, val label: String, val key: String, val min: Float, val max: Float, val intValue: Boolean = false, val value: (StandardButtonLiveConfig) -> Float)
    private val tabs = listOf("FOND", "CADRE", "TEXTE", "IMAGES")

    fun show(activity: MainActivity) {
        if (!AdminDiagnosticsGate.isEnabled(activity)) return
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).roundToInt()

        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.argb(246, 8, 10, 14)) }
        root.addView(TextView(activity).apply { text = "🎛 RÉGLAGES LIVE — BOUTONS STANDARDS"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER })
        root.addView(TextView(activity).apply { text = "Source maître : une modification s'applique à tous les boutons standards"; setTextColor(Color.LTGRAY); textSize = 11f; gravity = Gravity.CENTER })

        val tabRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)
        val host = android.widget.FrameLayout(activity)
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pages = linkedMapOf<String, ScrollView>()
        val tabButtons = linkedMapOf<String, Button>()
        fun showTab(name: String) {
            pages.forEach { (k, v) -> v.visibility = if (k == name) View.VISIBLE else View.GONE }
            tabButtons.forEach { (k, b) -> b.alpha = if (k == name) 1f else .55f }
        }

        val specs = listOf(
            Spec("FOND", "Rouge", "backgroundR", 0f, 255f, true) { it.backgroundR.toFloat() }, Spec("FOND", "Vert", "backgroundG", 0f, 255f, true) { it.backgroundG.toFloat() },
            Spec("FOND", "Bleu", "backgroundB", 0f, 255f, true) { it.backgroundB.toFloat() }, Spec("FOND", "Opacité", "backgroundAlpha", 0f, 255f, true) { it.backgroundAlpha.toFloat() },
            Spec("CADRE", "Rouge", "frameR", 0f, 255f, true) { it.frameR.toFloat() }, Spec("CADRE", "Vert", "frameG", 0f, 255f, true) { it.frameG.toFloat() },
            Spec("CADRE", "Bleu", "frameB", 0f, 255f, true) { it.frameB.toFloat() }, Spec("CADRE", "Opacité", "frameAlpha", 0f, 255f, true) { it.frameAlpha.toFloat() },
            Spec("CADRE", "Épaisseur (dp)", "frameWidthDp", 0f, 12f) { it.frameWidthDp }, Spec("CADRE", "Arrondi (dp)", "cornerRadiusDp", 0f, 48f) { it.cornerRadiusDp },
            Spec("TEXTE", "Rouge", "textR", 0f, 255f, true) { it.textR.toFloat() }, Spec("TEXTE", "Vert", "textG", 0f, 255f, true) { it.textG.toFloat() },
            Spec("TEXTE", "Bleu", "textB", 0f, 255f, true) { it.textB.toFloat() }, Spec("TEXTE", "Opacité", "textAlpha", 0f, 255f, true) { it.textAlpha.toFloat() },
            Spec("TEXTE", "Taille (sp)", "textSizeSp", 9f, 26f) { it.textSizeSp }, Spec("TEXTE", "Marge horizontale", "horizontalPaddingDp", 0f, 30f) { it.horizontalPaddingDp },
            Spec("TEXTE", "Marge verticale", "verticalPaddingDp", 0f, 20f) { it.verticalPaddingDp },
            Spec("IMAGES", "Opacité image de fond", "backgroundImageAlpha", 0f, 255f, true) { it.backgroundImageAlpha.toFloat() },
            Spec("IMAGES", "Opacité image de cadre", "frameImageAlpha", 0f, 255f, true) { it.frameImageAlpha.toFloat() }
        )

        tabs.forEach { tab ->
            val b = Button(activity).apply { text = tab; isAllCaps = false; setOnClickListener { showTab(tab) } }
            tabButtons[tab] = b
            tabRow.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f))
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            specs.filter { it.tab == tab }.forEach { spec -> addControl(activity, col, spec) }
            if (tab == "IMAGES") {
                val bg = Button(activity).apply { text = "IMPORTER IMAGE DE FOND"; isAllCaps = false; setOnClickListener { StandardButtonLiveStyle.pickImage(activity, false) } }
                val fr = Button(activity).apply { text = "IMPORTER IMAGE DE CADRE"; isAllCaps = false; setOnClickListener { StandardButtonLiveStyle.pickImage(activity, true) } }
                val clearBg = Button(activity).apply { text = "RETIRER IMAGE DE FOND"; isAllCaps = false; setOnClickListener { StandardButtonLiveStyle.setImage(activity, false, null); applyNow(activity) } }
                val clearFr = Button(activity).apply { text = "RETIRER IMAGE DE CADRE"; isAllCaps = false; setOnClickListener { StandardButtonLiveStyle.setImage(activity, true, null); applyNow(activity) } }
                col.addView(bg); col.addView(fr); col.addView(clearBg); col.addView(clearFr)
            }
            val page = ScrollView(activity).apply { addView(col); visibility = View.GONE }
            pages[tab] = page
            host.addView(page, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val report = Button(activity).apply { text = "PARTAGER RAPPORT"; isAllCaps = false; setOnClickListener { StandardButtonDeveloperReport.share(activity) } }
        val reset = Button(activity).apply { text = "RÉINITIALISER"; isAllCaps = false }
        val close = Button(activity).apply { text = "FERMER"; isAllCaps = false }
        actions.addView(report, LinearLayout.LayoutParams(0, dp(44), 1f)); actions.addView(reset, LinearLayout.LayoutParams(0, dp(44), 1f)); actions.addView(close, LinearLayout.LayoutParams(0, dp(44), 1f)); root.addView(actions)

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        reset.setOnClickListener { StandardButtonLiveStyle.reset(activity); applyNow(activity); dialog.dismiss(); show(activity) }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setGravity(Gravity.TOP)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * .46f).roundToInt())
            }
            showTab(tabs.first())
        }
        dialog.show()
    }

    private fun addControl(activity: MainActivity, parent: LinearLayout, spec: Spec) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 12f }
        val bar = SeekBar(activity).apply { max = 1000 }
        fun valueOf(p: Int) = spec.min + (spec.max - spec.min) * p / 1000f
        fun progressOf(v: Float) = (((v - spec.min) / (spec.max - spec.min)) * 1000f).roundToInt().coerceIn(0, 1000)
        fun show(v: Float) { label.text = if (spec.intValue) "${spec.label} : ${v.roundToInt()}" else String.format(Locale.FRANCE, "%s : %.2f", spec.label, v) }
        val initial = spec.value(StandardButtonLiveStyle.current(activity)); bar.progress = progressOf(initial); show(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (!fromUser) return; val v = valueOf(progress); if (spec.intValue) StandardButtonLiveStyle.setInt(activity, spec.key, v.roundToInt()) else StandardButtonLiveStyle.setFloat(activity, spec.key, v); show(v); applyNow(activity) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        row.addView(label); row.addView(bar); parent.addView(row)
    }

    private fun applyNow(activity: MainActivity) { StandardButtonLiveStyle.applyTree(activity, activity.window.decorView); activity.window.decorView.invalidate() }
}

object StandardButtonDeveloperReport {
    fun build(activity: MainActivity): String {
        val c = StandardButtonLiveStyle.current(activity)
        val theme = AppThemeCatalog.current(activity)
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())
        val allButtons = mutableListOf<Button>()
        fun scan(v: View) { if (v is Button && !StandardButtonLiveStyle.isProtected(v)) allButtons.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i)) }
        scan(activity.window.decorView)
        return buildString {
            appendLine("HORATRACK — RAPPORT STYLE BOUTONS STANDARDS v1")
            appendLine("Généré : $date")
            appendLine("Thème actif : ${theme.id}")
            appendLine("Boutons standards visibles détectés : ${allButtons.size}")
            appendLine("Protection : Entrée/Pause/Sortie uniquement")
            appendLine()
            appendLine("FOND")
            appendLine("RGBA=${c.backgroundR},${c.backgroundG},${c.backgroundB},${c.backgroundAlpha}")
            appendLine("Image=${StandardButtonLiveStyle.imageName(activity, c.backgroundImageUri)}")
            appendLine("URI image fond=${c.backgroundImageUri.ifBlank { "AUCUNE" }}")
            appendLine("Alpha image fond=${c.backgroundImageAlpha}")
            appendLine()
            appendLine("CADRE")
            appendLine("RGBA=${c.frameR},${c.frameG},${c.frameB},${c.frameAlpha}")
            appendLine("Épaisseur=${c.frameWidthDp}dp | arrondi=${c.cornerRadiusDp}dp")
            appendLine("Image=${StandardButtonLiveStyle.imageName(activity, c.frameImageUri)}")
            appendLine("URI image cadre=${c.frameImageUri.ifBlank { "AUCUNE" }}")
            appendLine("Alpha image cadre=${c.frameImageAlpha}")
            appendLine()
            appendLine("TEXTE")
            appendLine("RGBA=${c.textR},${c.textG},${c.textB},${c.textAlpha}")
            appendLine("Taille=${c.textSizeSp}sp | padding H=${c.horizontalPaddingDp}dp | V=${c.verticalPaddingDp}dp")
            appendLine()
            appendLine("INTÉGRATION CODE")
            appendLine("Source maître=StandardButtonLiveStyle")
            appendLine("Rendu=GradientDrawable + image fond optionnelle + image cadre optionnelle")
            appendLine("Ce rapport est destiné à être partagé pour figer ensuite ces valeurs/images dans le code de l'application.")
        }
    }

    fun share(activity: MainActivity) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "HoraTrack — rapport style boutons"); putExtra(Intent.EXTRA_TEXT, build(activity)) }
        activity.startActivity(Intent.createChooser(intent, "Partager le rapport"))
    }
}
