package com.amaury.pointage

import android.app.AlertDialog
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class StandardButtonLiveConfig(
    val backgroundR: Int = 35, val backgroundG: Int = 35, val backgroundB: Int = 35, val backgroundAlpha: Int = 255,
    val frameR: Int = 214, val frameG: Int = 168, val frameB: Int = 75, val frameAlpha: Int = 255,
    val frameWidthDp: Float = 2f, val cornerRadiusDp: Float = 24f,
    val textR: Int = 255, val textG: Int = 255, val textB: Int = 255, val textAlpha: Int = 255,
    val textSizeSp: Float = 14f, val horizontalPaddingDp: Float = 14f, val verticalPaddingDp: Float = 4f,
    val backgroundImageAlpha: Int = 255, val frameImageAlpha: Int = 255,
    val backgroundImageUri: String = "", val frameImageUri: String = ""
)

object StandardButtonLiveStyle {
    private const val PREFS = "developer_standard_button_live_v1"

    fun current(context: Context): StandardButtonLiveConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return StandardButtonLiveConfig(
            p.getInt("backgroundR", 35), p.getInt("backgroundG", 35), p.getInt("backgroundB", 35), p.getInt("backgroundAlpha", 255),
            p.getInt("frameR", 214), p.getInt("frameG", 168), p.getInt("frameB", 75), p.getInt("frameAlpha", 255),
            p.getFloat("frameWidthDp", 2f), p.getFloat("cornerRadiusDp", 24f),
            p.getInt("textR", 255), p.getInt("textG", 255), p.getInt("textB", 255), p.getInt("textAlpha", 255),
            p.getFloat("textSizeSp", 14f), p.getFloat("horizontalPaddingDp", 14f), p.getFloat("verticalPaddingDp", 4f),
            p.getInt("backgroundImageAlpha", 255), p.getInt("frameImageAlpha", 255),
            p.getString("backgroundImageUri", "").orEmpty(), p.getString("frameImageUri", "").orEmpty()
        )
    }

    fun setInt(context: Context, key: String, value: Int): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.edit().putInt(key, value).commit() && p.getInt(key, Int.MIN_VALUE) == value
    }

    fun setFloat(context: Context, key: String, value: Float): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.edit().putFloat(key, value).commit() && p.getFloat(key, Float.NaN) == value
    }

    fun setImage(context: Context, frame: Boolean, uri: Uri?): Boolean {
        val key = if (frame) "frameImageUri" else "backgroundImageUri"
        val value = uri?.toString().orEmpty()
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.edit().putString(key, value).commit() && p.getString(key, "").orEmpty() == value
    }

    fun reset(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()

    fun isProtected(button: Button): Boolean {
        if (button is RedDiamondFinalButton || button is LightReactiveJewelButton) return true
        val name = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        return name == "entryButton" || name == "pauseButton" || name == "exitButton"
    }

    fun applyToButton(context: Context, button: Button) {
        if (isProtected(button)) return
        val c = current(context)
        val d = context.resources.displayMetrics.density
        val base = GradientDrawable().apply {
            cornerRadius = c.cornerRadiusDp * d
            setColor(Color.argb(c.backgroundAlpha, c.backgroundR, c.backgroundG, c.backgroundB))
            setStroke((c.frameWidthDp * d).roundToInt(), Color.argb(c.frameAlpha, c.frameR, c.frameG, c.frameB))
        }
        val layers = mutableListOf<android.graphics.drawable.Drawable>(base)
        loadImage(context, c.backgroundImageUri, c.backgroundImageAlpha)?.let { layers.add(it) }
        loadImage(context, c.frameImageUri, c.frameImageAlpha)?.let { layers.add(it) }
        button.background = LayerDrawable(layers.toTypedArray())
        button.backgroundTintList = null
        button.setTextColor(Color.argb(c.textAlpha, c.textR, c.textG, c.textB))
        button.textSize = c.textSizeSp
        button.isAllCaps = false
        button.setPadding((c.horizontalPaddingDp*d).roundToInt(), (c.verticalPaddingDp*d).roundToInt(), (c.horizontalPaddingDp*d).roundToInt(), (c.verticalPaddingDp*d).roundToInt())
    }

    fun applyTree(context: Context, root: View) {
        if (root is Button) applyToButton(context, root)
        if (root is ViewGroup) for (i in 0 until root.childCount) applyTree(context, root.getChildAt(i))
    }

    private fun loadImage(context: Context, uriText: String, alpha: Int): BitmapDrawable? {
        if (uriText.isBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriText))?.use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input) ?: return@runCatching null
                BitmapDrawable(context.resources, bitmap).apply { gravity = Gravity.FILL; this.alpha = alpha.coerceIn(0,255) }
            }
        }.getOrNull()
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

/** Fragment invisible : reçoit le résultat du sélecteur d'image sans modifier MainActivity. */
class StandardButtonImagePickerFragment : Fragment() {
    private var frameMode = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        frameMode = arguments?.getBoolean("frame") == true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val a = activity as? MainActivity
        if (a != null && requestCode == 1 && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                runCatching { a.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                StandardButtonLiveStyle.setImage(a, frameMode, uri)
                StandardButtonLiveStyle.applyTree(a, a.window.decorView)
                Toast.makeText(a, if (frameMode) "Cadre importé et appliqué" else "Fond importé et appliqué", Toast.LENGTH_SHORT).show()
            }
        }
        fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
    }

    companion object {
        fun open(activity: MainActivity, frame: Boolean) {
            val f = StandardButtonImagePickerFragment().apply { arguments = Bundle().apply { putBoolean("frame", frame) } }
            activity.fragmentManager.beginTransaction().add(f, "standard-button-image-picker-${System.nanoTime()}").commitAllowingStateLoss()
        }
    }
}

object DeveloperStandardButtonPanel {
    private data class Spec(val tab: String, val label: String, val key: String, val min: Float, val max: Float, val integer: Boolean = false, val value: (StandardButtonLiveConfig)->Float)
    private val tabs = listOf("FOND", "CADRE", "TEXTE", "IMAGES")

    fun show(activity: MainActivity) {
        if (!AdminDiagnosticsGate.isEnabled(activity)) return
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).roundToInt()

        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); setBackgroundColor(Color.argb(246,8,10,14)) }
        root.addView(TextView(activity).apply { text = "🎛 RÉGLAGES LIVE — BOUTONS STANDARDS"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER })
        root.addView(TextView(activity).apply { text = "Une seule source maître pour cadre, fond et texte"; setTextColor(Color.LTGRAY); textSize = 11f; gravity = Gravity.CENTER })

        val tabRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)
        val host = FrameLayout(activity)
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        val pages = linkedMapOf<String,ScrollView>()
        val buttons = linkedMapOf<String,Button>()
        fun showTab(name:String) { pages.forEach { (k,v)->v.visibility=if(k==name) View.VISIBLE else View.GONE }; buttons.forEach { (k,b)->b.alpha=if(k==name)1f else .55f } }

        val specs = listOf(
            Spec("FOND","Rouge","backgroundR",0f,255f,true){it.backgroundR.toFloat()}, Spec("FOND","Vert","backgroundG",0f,255f,true){it.backgroundG.toFloat()}, Spec("FOND","Bleu","backgroundB",0f,255f,true){it.backgroundB.toFloat()}, Spec("FOND","Opacité","backgroundAlpha",0f,255f,true){it.backgroundAlpha.toFloat()},
            Spec("CADRE","Rouge","frameR",0f,255f,true){it.frameR.toFloat()}, Spec("CADRE","Vert","frameG",0f,255f,true){it.frameG.toFloat()}, Spec("CADRE","Bleu","frameB",0f,255f,true){it.frameB.toFloat()}, Spec("CADRE","Opacité","frameAlpha",0f,255f,true){it.frameAlpha.toFloat()}, Spec("CADRE","Épaisseur dp","frameWidthDp",0f,12f){it.frameWidthDp}, Spec("CADRE","Arrondi dp","cornerRadiusDp",0f,48f){it.cornerRadiusDp},
            Spec("TEXTE","Rouge","textR",0f,255f,true){it.textR.toFloat()}, Spec("TEXTE","Vert","textG",0f,255f,true){it.textG.toFloat()}, Spec("TEXTE","Bleu","textB",0f,255f,true){it.textB.toFloat()}, Spec("TEXTE","Opacité","textAlpha",0f,255f,true){it.textAlpha.toFloat()}, Spec("TEXTE","Taille sp","textSizeSp",9f,26f){it.textSizeSp}, Spec("TEXTE","Padding horizontal","horizontalPaddingDp",0f,30f){it.horizontalPaddingDp}, Spec("TEXTE","Padding vertical","verticalPaddingDp",0f,20f){it.verticalPaddingDp},
            Spec("IMAGES","Opacité image fond","backgroundImageAlpha",0f,255f,true){it.backgroundImageAlpha.toFloat()}, Spec("IMAGES","Opacité image cadre","frameImageAlpha",0f,255f,true){it.frameImageAlpha.toFloat()}
        )

        tabs.forEach { tab ->
            val tb = Button(activity).apply { text=tab; isAllCaps=false; setOnClickListener { showTab(tab) } }
            buttons[tab]=tb; tabRow.addView(tb,LinearLayout.LayoutParams(0,dp(40),1f))
            val col = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            specs.filter { it.tab==tab }.forEach { addControl(activity,col,it) }
            if (tab=="IMAGES") {
                col.addView(Button(activity).apply { text="IMPORTER IMAGE DE FOND"; isAllCaps=false; setOnClickListener { StandardButtonImagePickerFragment.open(activity,false) } })
                col.addView(Button(activity).apply { text="IMPORTER IMAGE DE CADRE"; isAllCaps=false; setOnClickListener { StandardButtonImagePickerFragment.open(activity,true) } })
                col.addView(Button(activity).apply { text="RETIRER IMAGE DE FOND"; isAllCaps=false; setOnClickListener { StandardButtonLiveStyle.setImage(activity,false,null); applyNow(activity) } })
                col.addView(Button(activity).apply { text="RETIRER IMAGE DE CADRE"; isAllCaps=false; setOnClickListener { StandardButtonLiveStyle.setImage(activity,true,null); applyNow(activity) } })
            }
            val page=ScrollView(activity).apply { addView(col); visibility=View.GONE }; pages[tab]=page; host.addView(page)
        }

        val actions=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL }
        val report=Button(activity).apply { text="PARTAGER RAPPORT"; isAllCaps=false; setOnClickListener { StandardButtonDeveloperReport.share(activity) } }
        val reset=Button(activity).apply { text="RÉINITIALISER"; isAllCaps=false }
        val close=Button(activity).apply { text="FERMER"; isAllCaps=false }
        actions.addView(report,LinearLayout.LayoutParams(0,dp(44),1f)); actions.addView(reset,LinearLayout.LayoutParams(0,dp(44),1f)); actions.addView(close,LinearLayout.LayoutParams(0,dp(44),1f)); root.addView(actions)

        val dialog=AlertDialog.Builder(activity).setView(root).create()
        reset.setOnClickListener { StandardButtonLiveStyle.reset(activity); applyNow(activity); dialog.dismiss(); show(activity) }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener { dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setGravity(Gravity.TOP); setLayout(ViewGroup.LayoutParams.MATCH_PARENT,(activity.resources.displayMetrics.heightPixels*.46f).roundToInt()) }; showTab(tabs.first()); applyNow(activity) }
        dialog.show()
    }

    private fun addControl(activity:MainActivity,parent:LinearLayout,s:Spec) {
        val row=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val label=TextView(activity).apply { setTextColor(Color.WHITE); textSize=12f }
        val bar=SeekBar(activity).apply { max=1000 }
        fun value(p:Int)=s.min+(s.max-s.min)*p/1000f
        fun progress(v:Float)=(((v-s.min)/(s.max-s.min))*1000f).roundToInt().coerceIn(0,1000)
        fun show(v:Float){ label.text=if(s.integer) "${s.label} : ${v.roundToInt()}" else String.format(Locale.FRANCE,"%s : %.2f",s.label,v) }
        val init=s.value(StandardButtonLiveStyle.current(activity)); bar.progress=progress(init); show(init)
        bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar:SeekBar?,p:Int,fromUser:Boolean){ if(!fromUser)return; val v=value(p); if(s.integer)StandardButtonLiveStyle.setInt(activity,s.key,v.roundToInt()) else StandardButtonLiveStyle.setFloat(activity,s.key,v); show(v); applyNow(activity) }
            override fun onStartTrackingTouch(seekBar:SeekBar?)=Unit; override fun onStopTrackingTouch(seekBar:SeekBar?)=Unit
        })
        row.addView(label); row.addView(bar); parent.addView(row)
    }

    private fun applyNow(activity:MainActivity){ StandardButtonLiveStyle.applyTree(activity,activity.window.decorView); activity.window.decorView.invalidate() }
}

object StandardButtonDeveloperReport {
    fun build(activity:MainActivity):String {
        val c=StandardButtonLiveStyle.current(activity); val theme=AppThemeCatalog.current(activity); val date=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.FRANCE).format(Date())
        val buttons=mutableListOf<Button>(); fun scan(v:View){ if(v is Button && !StandardButtonLiveStyle.isProtected(v))buttons.add(v); if(v is ViewGroup)for(i in 0 until v.childCount)scan(v.getChildAt(i)) }; scan(activity.window.decorView)
        return buildString {
            appendLine("HORATRACK — RAPPORT STYLE BOUTONS STANDARDS v1"); appendLine("Généré : $date"); appendLine("Thème actif : ${theme.id}"); appendLine("Boutons standards visibles : ${buttons.size}"); appendLine("Protection : Entrée/Pause/Sortie uniquement"); appendLine()
            appendLine("FOND"); appendLine("RGBA=${c.backgroundR},${c.backgroundG},${c.backgroundB},${c.backgroundAlpha}"); appendLine("Image=${StandardButtonLiveStyle.imageName(activity,c.backgroundImageUri)}"); appendLine("URI=${c.backgroundImageUri.ifBlank{"AUCUNE"}}"); appendLine("Alpha image=${c.backgroundImageAlpha}"); appendLine()
            appendLine("CADRE"); appendLine("RGBA=${c.frameR},${c.frameG},${c.frameB},${c.frameAlpha}"); appendLine("Épaisseur=${c.frameWidthDp}dp | arrondi=${c.cornerRadiusDp}dp"); appendLine("Image=${StandardButtonLiveStyle.imageName(activity,c.frameImageUri)}"); appendLine("URI=${c.frameImageUri.ifBlank{"AUCUNE"}}"); appendLine("Alpha image=${c.frameImageAlpha}"); appendLine()
            appendLine("TEXTE"); appendLine("RGBA=${c.textR},${c.textG},${c.textB},${c.textAlpha}"); appendLine("Taille=${c.textSizeSp}sp | padding H=${c.horizontalPaddingDp}dp | V=${c.verticalPaddingDp}dp"); appendLine()
            appendLine("INTÉGRATION CODE"); appendLine("Source maître=StandardButtonLiveStyle"); appendLine("Rendu=base couleur + image fond optionnelle + cadre couleur + image cadre optionnelle + texte"); appendLine("Ce rapport est prévu pour figer ensuite ces choix dans le code final de l'application.")
        }
    }
    fun share(activity:MainActivity){ activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"HoraTrack — rapport style boutons");putExtra(Intent.EXTRA_TEXT,build(activity))},"Partager le rapport")) }
}
