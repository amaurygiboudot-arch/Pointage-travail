package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

class PointageApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() { super.onCreate(); registerActivityLifecycleCallbacks(this); ConventionCatalog.initialize(this) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.window.decorView.post {
            AppearanceManager.apply(activity)
            if (activity is MainActivity) {
                SettingsUiInstaller.install(activity)
                LuxuryUiInstaller.install(activity)
                UpdateChecker.check(activity, silent = true)
            }
        }
    }
    override fun onActivityResumed(activity: Activity) { AppearanceManager.apply(activity) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

object AppearanceManager {
    private const val PREFS = "appearance_settings"
    const val BACKGROUND_FILE = "custom_app_background.jpg"

    fun apply(activity: Activity) {
        // L'écran principal HP Travail a un design noir/doré volontairement fixe.
        // On ne le recolore pas avec le mode clair, sinon les cartes deviennent blanches
        // et les textes dorés/noirs perdent leur contraste.
        if (activity is MainActivity) {
            applyLuxuryMain(activity)
            return
        }

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "dark") ?: "dark"
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && systemDark)
        val defaultBg = if (dark) "#080808" else "#F3F0E8"
        val defaultPanel = if (dark) "#181818" else "#FFFFFF"
        val bg = parseColor(prefs.getString("app_bg", null), defaultBg)
        val panel = if (prefs.getBoolean("custom_bg", false)) shift(bg, if (isDark(bg)) 1.28f else 0.90f) else Color.parseColor(defaultPanel)
        val text = bestTextColor(bg)
        val panelText = bestTextColor(panel)
        val secondary = if (isDark(panel)) Color.parseColor("#E0E0E0") else Color.parseColor("#333333")
        val imageFile = File(activity.filesDir, BACKGROUND_FILE)
        val hasImage = prefs.getBoolean("custom_image_bg", false) && imageFile.exists()

        activity.window.statusBarColor = bg
        activity.window.navigationBarColor = bg
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        if (hasImage) {
            val bitmap = runCatching { BitmapFactory.decodeFile(imageFile.absolutePath) }.getOrNull()
            if (bitmap != null) root.background = BitmapDrawable(activity.resources, bitmap).apply { gravity = Gravity.FILL }
            else root.setBackgroundColor(bg)
        } else root.setBackgroundColor(bg)
        recolor(root, bg, panel, text, panelText, secondary, hasImage, false)
    }

    private fun applyLuxuryMain(activity: MainActivity) {
        val black = Color.parseColor("#050505")
        activity.window.statusBarColor = black
        activity.window.navigationBarColor = black
        activity.window.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else -1)

        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        root.setBackgroundColor(black)
        clearRuntimeTints(root)
    }

    private fun clearRuntimeTints(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) clearRuntimeTints(view.getChildAt(i))
        }
        val id = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
        if (id == "contentPanel" || id == "statusCard" || id == "pointageButtons" || id == "gpsSettingsPanel" || id == "analyticsPdfPanel") {
            view.backgroundTintList = null
        }
    }

    private fun recolor(view: View, bg: Int, panel: Int, text: Int, panelText: Int, secondary: Int, imageBg: Boolean, inheritedPanel: Boolean) {
        val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
        val ownPanel = idName.contains("Panel", true) || idName.contains("Card", true) || idName == "contentPanel"
        val onPanel = inheritedPanel || ownPanel
        if (view is ViewGroup) {
            if (view.parent != null && view.background != null && view !is android.widget.ScrollView && ownPanel) view.backgroundTintList = ColorStateList.valueOf(panel)
            for (i in 0 until view.childCount) recolor(view.getChildAt(i), bg, panel, text, panelText, secondary, imageBg, onPanel)
        }
        val surface = if (onPanel) panel else bg
        val strongText = bestTextColor(surface)
        val softText = if (isDark(surface)) Color.parseColor("#E0E0E0") else Color.parseColor("#333333")
        when (view) {
            is EditText -> { view.setTextColor(strongText); view.setHintTextColor(softText) }
            is Button -> {
                val id = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
                val isEntryExit = id == "entryButton" || id == "exitButton"
                if (!isEntryExit && view.background != null) view.setTextColor(Color.parseColor("#F3D58A"))
                else { val buttonSurface = if (view.background != null) panel else surface; view.setTextColor(bestTextColor(buttonSurface)) }
            }
            is TextView -> {
                val current = view.currentTextColor
                val gold = Color.parseColor("#D6A84B"); val lightGold = Color.parseColor("#F3D58A")
                if ((current == gold || current == lightGold) && contrastRatio(lightGold, surface) >= 4.5) view.setTextColor(lightGold) else view.setTextColor(strongText)
            }
        }
        if (view is Switch) { view.setTextColor(strongText); view.buttonTintList = null }
        if (view is android.widget.ScrollView) { if (imageBg) view.setBackgroundColor(Color.TRANSPARENT) else view.setBackgroundColor(bg) }
    }

    fun bestTextColor(background: Int): Int = if (isDark(background)) Color.WHITE else Color.parseColor("#111111")
    fun contrastRatio(foreground: Int, background: Int): Double { fun lum(c: Int): Double { fun channel(v:Int):Double { val s=v/255.0; return if(s<=0.03928)s/12.92 else Math.pow((s+0.055)/1.055,2.4) }; return 0.2126*channel(Color.red(c))+0.7152*channel(Color.green(c))+0.0722*channel(Color.blue(c)) }; val l1=lum(foreground); val l2=lum(background); return (maxOf(l1,l2)+0.05)/(minOf(l1,l2)+0.05) }
    private fun isDark(color:Int):Boolean=((Color.red(color)*299+Color.green(color)*587+Color.blue(color)*114)/1000)<145
    private fun parseColor(value:String?,fallback:String):Int=runCatching{Color.parseColor(value?:fallback)}.getOrElse{Color.parseColor(fallback)}
    private fun shift(color:Int,factor:Float)=Color.rgb((Color.red(color)*factor).toInt().coerceIn(0,255),(Color.green(color)*factor).toInt().coerceIn(0,255),(Color.blue(color)*factor).toInt().coerceIn(0,255))
}

object PlaceNames {
    fun get(context: Context, address: String): String? { val prefs=context.getSharedPreferences("gps_settings",Context.MODE_PRIVATE); return runCatching{JSONObject(prefs.getString("address_names","{}")?:"{}").optString(address).trim().takeIf{it.isNotBlank()}}.getOrNull() }
    fun put(context:Context,address:String,name:String){val prefs=context.getSharedPreferences("gps_settings",Context.MODE_PRIVATE);val obj=runCatching{JSONObject(prefs.getString("address_names","{}")?:"{}")}.getOrElse{JSONObject()};obj.put(address,name);prefs.edit().putString("address_names",obj.toString()).apply()}
    fun display(context:Context,address:String):String{val name=get(context,address);return if(name.isNullOrBlank())address else "$name — $address"}
}

object SettingsUiInstaller {
    private const val TAG = "settings_personalization_installed"
    fun install(activity: MainActivity) {
        val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        if (panel.findViewWithTag<View>(TAG) != null) return
        val address = activity.findViewById<EditText>(R.id.workplaceAddress); address.isFocusable = false; address.isClickable = false
        val settingsButton = activity.findViewById<Button>(R.id.settingsButton)
        val header = settingsButton.parent as? LinearLayout
        if (header != null && header.findViewWithTag<View>("main_back_button") == null) {
            val back = styledButton(activity, "←").apply { tag = "main_back_button"; textSize = 24f; layoutParams = LinearLayout.LayoutParams(dp(activity,56), dp(activity,56)).apply { marginEnd = dp(activity,8) }; setOnClickListener { activity.findViewById<TextView>(R.id.tabToday)?.performClick() } }
            header.addView(back, 0)
        }
        val section = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(0,28,0,0); tag=TAG }
        section.addView(title(activity,"APPARENCE DE L'APPLICATION"))
        val modeButton=styledButton(activity,"")
        fun updateModeLabel(){val mode=activity.getSharedPreferences("appearance_settings",Context.MODE_PRIVATE).getString("mode","dark")?:"dark";modeButton.text="MODE : "+when(mode){"light"->"CLAIR";"dark"->"SOMBRE";else->"AUTOMATIQUE"}}
        updateModeLabel();modeButton.setOnClickListener{val values=arrayOf("Automatique","Clair","Sombre");AlertDialog.Builder(activity).setTitle("Mode d'affichage").setItems(values){_,which->val mode=arrayOf("auto","light","dark")[which];activity.getSharedPreferences("appearance_settings",Context.MODE_PRIVATE).edit().putString("mode",mode).apply();updateModeLabel();AppearanceManager.apply(activity)}.show()};section.addView(modeButton)
        val bgButton=styledButton(activity,"COULEUR DU FOND");bgButton.setOnClickListener{chooseAppBackground(activity)};section.addView(bgButton)
        val imageButton=styledButton(activity,"CHOISIR UNE IMAGE DE FOND");imageButton.setOnClickListener{activity.startActivity(Intent(activity,BackgroundPickerActivity::class.java))};section.addView(imageButton)
        val resetBg=styledButton(activity,"RÉINITIALISER LE FOND");resetBg.setOnClickListener{File(activity.filesDir,AppearanceManager.BACKGROUND_FILE).delete();activity.getSharedPreferences("appearance_settings",Context.MODE_PRIVATE).edit().remove("app_bg").putBoolean("custom_bg",false).putBoolean("custom_image_bg",false).apply();AppearanceManager.apply(activity)};section.addView(resetBg)
        section.addView(title(activity,"PERSONNALISER LE WIDGET"))
        val widgetBg=styledButton(activity,"COULEUR DU FOND DU WIDGET");widgetBg.setOnClickListener{chooseWidgetColor(activity,"widget_bg","Fond du widget")};section.addView(widgetBg)
        val widgetAccent=styledButton(activity,"COULEUR D'ACCENT DU WIDGET");widgetAccent.setOnClickListener{chooseWidgetColor(activity,"widget_accent","Accent du widget")};section.addView(widgetAccent)
        val showPosition=Switch(activity).apply{text="Afficher la position dans le widget";isChecked=activity.getSharedPreferences("widget_style",Context.MODE_PRIVATE).getBoolean("show_position",true);setOnCheckedChangeListener{_,checked->activity.getSharedPreferences("widget_style",Context.MODE_PRIVATE).edit().putBoolean("show_position",checked).apply();PointageWidgetProvider.updateAll(activity)}};section.addView(showPosition)
        section.addView(title(activity,"SAUVEGARDE GOOGLE DRIVE"))
        val driveStatus=TextView(activity).apply{textSize=14f;text=if(DriveBackupManager.isConfigured(activity))"● Sauvegarde Drive active — PDF classés par lieu / année / mois" else "Drive non configuré"};section.addView(driveStatus)
        val chooseDrive=styledButton(activity,if(DriveBackupManager.isConfigured(activity))"CHANGER LE DOSSIER GOOGLE DRIVE" else "CHOISIR LE DOSSIER GOOGLE DRIVE");chooseDrive.setOnClickListener{activity.startActivity(Intent(activity,DriveFolderPickerActivity::class.java))};section.addView(chooseDrive)
        val syncDrive=styledButton(activity,"SYNCHRONISER TOUT L'HISTORIQUE");syncDrive.setOnClickListener{if(!DriveBackupManager.isConfigured(activity))Toast.makeText(activity,"Choisis d'abord un dossier Google Drive",Toast.LENGTH_LONG).show()else{Toast.makeText(activity,"Synchronisation Drive démarrée",Toast.LENGTH_SHORT).show();DriveBackupManager.syncAllAsync(activity){ok,message->activity.runOnUiThread{Toast.makeText(activity,if(ok)"Drive : $message" else "Erreur Drive : $message",Toast.LENGTH_LONG).show()}}}};section.addView(syncDrive)
        val forgetDrive=styledButton(activity,"DÉCONNECTER LE DOSSIER DRIVE");forgetDrive.setOnClickListener{DriveBackupManager.clear(activity);driveStatus.text="Drive non configuré";Toast.makeText(activity,"Sauvegarde Drive désactivée",Toast.LENGTH_SHORT).show()};section.addView(forgetDrive)
        section.addView(title(activity,"MISES À JOUR"))
        val updateButton=styledButton(activity,"VÉRIFIER LES MISES À JOUR");updateButton.setOnClickListener{UpdateChecker.check(activity, silent=false)};section.addView(updateButton)
        panel.addView(section);AppearanceManager.apply(activity)
    }
    private fun styledButton(context: Context, label:String)=Button(context).apply{text=label;setBackgroundResource(R.drawable.hp_panel);isAllCaps=false}
    private fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density).toInt()
    private fun title(context:Context,text:String)=TextView(context).apply{this.text=text;textSize=16f;setPadding(0,18,0,10)}
    private fun chooseAppBackground(activity:Activity){val labels=arrayOf("Noir","Anthracite","Bleu nuit","Vert profond","Bordeaux","Beige clair","Couleur personnalisée");val colors=arrayOf("#080808","#242424","#0D1B2A","#102A20","#351015","#F3F0E8");AlertDialog.Builder(activity).setTitle("Fond de l'application").setItems(labels){_,which->if(which<colors.size)saveAppBg(activity,colors[which])else customColorDialog(activity,"Couleur du fond"){saveAppBg(activity,it)}}.show()}
    private fun saveAppBg(activity:Activity,color:String){activity.getSharedPreferences("appearance_settings",Context.MODE_PRIVATE).edit().putString("app_bg",color).putBoolean("custom_bg",true).putBoolean("custom_image_bg",false).apply();AppearanceManager.apply(activity)}
    private fun chooseWidgetColor(activity:Activity,key:String,title:String){val labels=arrayOf("Noir","Anthracite","Bleu nuit","Vert profond","Doré","Blanc","Couleur personnalisée");val colors=arrayOf("#080808","#242424","#0D1B2A","#102A20","#D6A84B","#FFFFFF");AlertDialog.Builder(activity).setTitle(title).setItems(labels){_,which->if(which<colors.size)saveWidgetColor(activity,key,colors[which])else customColorDialog(activity,title){saveWidgetColor(activity,key,it)}}.show()}
    private fun saveWidgetColor(activity:Activity,key:String,color:String){activity.getSharedPreferences("widget_style",Context.MODE_PRIVATE).edit().putString(key,color).apply();PointageWidgetProvider.updateAll(activity)}
    private fun customColorDialog(activity:Activity,title:String,onSave:(String)->Unit){val input=EditText(activity).apply{hint="#1A1A1A";setText("#1A1A1A")};AlertDialog.Builder(activity).setTitle(title).setView(input).setPositiveButton("Appliquer"){_,_->val value=input.text.toString().trim();if(runCatching{Color.parseColor(value)}.isSuccess)onSave(value)else Toast.makeText(activity,"Couleur invalide",Toast.LENGTH_SHORT).show()}.setNegativeButton("Annuler",null).show()}
}
