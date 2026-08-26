package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

data class StandardButtonLiveConfig(
 val backgroundR:Int=35,val backgroundG:Int=35,val backgroundB:Int=35,val backgroundAlpha:Int=255,
 val frameR:Int=214,val frameG:Int=168,val frameB:Int=75,val frameAlpha:Int=255,val frameWidthDp:Float=2f,val cornerRadiusDp:Float=24f,
 val textR:Int=255,val textG:Int=255,val textB:Int=255,val textAlpha:Int=255,val textSizeSp:Float=14f,val horizontalPaddingDp:Float=14f,val verticalPaddingDp:Float=4f,
 val backgroundImageAlpha:Int=255,val frameImageAlpha:Int=255,val backgroundImageUri:String="",val frameImageUri:String="")

object StandardButtonLiveStyle {
 private const val PREFS="developer_standard_button_live_v1"
 private const val DEV_TAG="horatrack_dev_live_control"
 fun current(c:Context):StandardButtonLiveConfig { val p=c.getSharedPreferences(PREFS,0); return StandardButtonLiveConfig(
  p.getInt("backgroundR",35),p.getInt("backgroundG",35),p.getInt("backgroundB",35),p.getInt("backgroundAlpha",255),
  p.getInt("frameR",214),p.getInt("frameG",168),p.getInt("frameB",75),p.getInt("frameAlpha",255),p.getFloat("frameWidthDp",2f),p.getFloat("cornerRadiusDp",24f),
  p.getInt("textR",255),p.getInt("textG",255),p.getInt("textB",255),p.getInt("textAlpha",255),p.getFloat("textSizeSp",14f),p.getFloat("horizontalPaddingDp",14f),p.getFloat("verticalPaddingDp",4f),
  p.getInt("backgroundImageAlpha",255),p.getInt("frameImageAlpha",255),p.getString("backgroundImageUri","").orEmpty(),p.getString("frameImageUri","").orEmpty()) }
 fun setInt(c:Context,k:String,v:Int)=c.getSharedPreferences(PREFS,0).edit().putInt(k,v).commit()
 fun setFloat(c:Context,k:String,v:Float)=c.getSharedPreferences(PREFS,0).edit().putFloat(k,v).commit()
 fun setImage(c:Context,frame:Boolean,uri:Uri?):Boolean=c.getSharedPreferences(PREFS,0).edit().putString(if(frame)"frameImageUri" else "backgroundImageUri",uri?.toString().orEmpty()).commit()
 fun reset(c:Context)=c.getSharedPreferences(PREFS,0).edit().clear().commit()
 fun markDeveloperControl(v:View){ v.tag=DEV_TAG }
 fun isProtected(b:Button):Boolean { if(b.tag==DEV_TAG || b is RedDiamondFinalButton || b is LightReactiveJewelButton)return true; val n=runCatching{b.resources.getResourceEntryName(b.id)}.getOrNull().orEmpty(); return n=="entryButton"||n=="pauseButton"||n=="exitButton" }
 fun applyToButton(c:Context,b:Button){ if(isProtected(b))return; val x=current(c); val d=c.resources.displayMetrics.density; val bg=GradientDrawable().apply{cornerRadius=x.cornerRadiusDp*d;setColor(Color.argb(x.backgroundAlpha,x.backgroundR,x.backgroundG,x.backgroundB));setStroke((x.frameWidthDp*d).roundToInt(),Color.argb(x.frameAlpha,x.frameR,x.frameG,x.frameB))}; b.background=LayerDrawable(arrayOf(bg));b.backgroundTintList=null;b.setTextColor(Color.argb(x.textAlpha,x.textR,x.textG,x.textB));b.textSize=x.textSizeSp;b.isAllCaps=false;b.setPadding((x.horizontalPaddingDp*d).roundToInt(),(x.verticalPaddingDp*d).roundToInt(),(x.horizontalPaddingDp*d).roundToInt(),(x.verticalPaddingDp*d).roundToInt()) }
 fun applyTree(c:Context,v:View){if(v is Button)applyToButton(c,v);if(v is ViewGroup)for(i in 0 until v.childCount)applyTree(c,v.getChildAt(i))}
 fun imageName(c:Context,u:String)=if(u.isBlank())"Aucune" else Uri.parse(u).lastPathSegment?:u
}

object DeveloperStandardButtonPanel {
 private data class Spec(val tab:String,val label:String,val key:String,val min:Float,val max:Float,val integer:Boolean=false,val get:(StandardButtonLiveConfig)->Float)
 private val tabs=listOf("FOND","CADRE","TEXTE","IMAGES")
 private fun isNight(c:Context)=((c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES)
 private fun primaryText(c:Context)=if(isNight(c)) Color.WHITE else Color.rgb(28,28,28)
 private fun secondaryText(c:Context)=if(isNight(c)) Color.rgb(205,216,232) else Color.rgb(70,70,70)
 private fun panelColor(c:Context)=if(isNight(c)) Color.argb(246,8,10,14) else Color.argb(252,248,244,234)
 private fun controlBackground(c:Context)=GradientDrawable().apply{cornerRadius=22f*c.resources.displayMetrics.density;setColor(if(isNight(c))Color.rgb(24,26,30) else Color.rgb(255,252,245));setStroke((2f*c.resources.displayMetrics.density).roundToInt(),Color.rgb(190,145,55))}
 fun show(a:MainActivity){
  if(!AdminDiagnosticsGate.isEnabled(a))return; fun dp(v:Int)=(v*a.resources.displayMetrics.density).roundToInt()
  val root=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(panelColor(a))}
  fun txt(s:String,size:Float=12f)=TextView(a).apply{text=s;textSize=size;setTextColor(primaryText(a))}
  root.addView(txt("🎛 RÉGLAGES LIVE — BOUTONS STANDARDS",15f).apply{gravity=Gravity.CENTER});root.addView(txt("Une seule source maître pour cadre, fond et texte",11f).apply{setTextColor(secondaryText(a));gravity=Gravity.CENTER})
  val row=LinearLayout(a);root.addView(row);val host=FrameLayout(a);root.addView(host,LinearLayout.LayoutParams(-1,0,1f));val pages=linkedMapOf<String,ScrollView>();val tabBtns=linkedMapOf<String,Button>()
  fun devButton(label:String)=Button(a).apply{text=label;isAllCaps=false;setTextColor(primaryText(a));background=controlBackground(a);backgroundTintList=null;StandardButtonLiveStyle.markDeveloperControl(this)}
  fun showTab(n:String){pages.forEach{(k,v)->v.visibility=if(k==n)View.VISIBLE else View.GONE};tabBtns.forEach{(k,b)->b.alpha=if(k==n)1f else .72f;b.setTextColor(primaryText(a))}}
  val specs=listOf(
   Spec("FOND","Rouge","backgroundR",0f,255f,true){it.backgroundR.toFloat()},Spec("FOND","Vert","backgroundG",0f,255f,true){it.backgroundG.toFloat()},Spec("FOND","Bleu","backgroundB",0f,255f,true){it.backgroundB.toFloat()},Spec("FOND","Opacité","backgroundAlpha",0f,255f,true){it.backgroundAlpha.toFloat()},
   Spec("CADRE","Rouge","frameR",0f,255f,true){it.frameR.toFloat()},Spec("CADRE","Vert","frameG",0f,255f,true){it.frameG.toFloat()},Spec("CADRE","Bleu","frameB",0f,255f,true){it.frameB.toFloat()},Spec("CADRE","Opacité","frameAlpha",0f,255f,true){it.frameAlpha.toFloat()},Spec("CADRE","Épaisseur dp","frameWidthDp",0f,12f){it.frameWidthDp},Spec("CADRE","Arrondi dp","cornerRadiusDp",0f,48f){it.cornerRadiusDp},
   Spec("TEXTE","Rouge","textR",0f,255f,true){it.textR.toFloat()},Spec("TEXTE","Vert","textG",0f,255f,true){it.textG.toFloat()},Spec("TEXTE","Bleu","textB",0f,255f,true){it.textB.toFloat()},Spec("TEXTE","Opacité","textAlpha",0f,255f,true){it.textAlpha.toFloat()},Spec("TEXTE","Taille sp","textSizeSp",9f,26f){it.textSizeSp},Spec("TEXTE","Padding horizontal","horizontalPaddingDp",0f,30f){it.horizontalPaddingDp},Spec("TEXTE","Padding vertical","verticalPaddingDp",0f,20f){it.verticalPaddingDp},
   Spec("IMAGES","Opacité image fond","backgroundImageAlpha",0f,255f,true){it.backgroundImageAlpha.toFloat()},Spec("IMAGES","Opacité image cadre","frameImageAlpha",0f,255f,true){it.frameImageAlpha.toFloat()})
  tabs.forEach{tab->val tb=devButton(tab);tb.setOnClickListener{showTab(tab)};tabBtns[tab]=tb;row.addView(tb,LinearLayout.LayoutParams(0,dp(40),1f));val col=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL};specs.filter{it.tab==tab}.forEach{s->addControl(a,col,s)};if(tab=="IMAGES"){col.addView(txt("Import d’images : utilisez les boutons ci-dessous. Les images restent liées au style maître.",11f));col.addView(devButton("RETIRER IMAGE DE FOND").apply{setOnClickListener{StandardButtonLiveStyle.setImage(a,false,null);applyNow(a)}});col.addView(devButton("RETIRER IMAGE DE CADRE").apply{setOnClickListener{StandardButtonLiveStyle.setImage(a,true,null);applyNow(a)}})};val p=ScrollView(a).apply{addView(col);visibility=View.GONE};pages[tab]=p;host.addView(p)}
  val actions=LinearLayout(a);val report=devButton("PARTAGER RAPPORT");val reset=devButton("RÉINITIALISER");val close=devButton("FERMER");actions.addView(report,LinearLayout.LayoutParams(0,dp(44),1f));actions.addView(reset,LinearLayout.LayoutParams(0,dp(44),1f));actions.addView(close,LinearLayout.LayoutParams(0,dp(44),1f));root.addView(actions)
  val dialog=AlertDialog.Builder(a).setView(root).create();report.setOnClickListener{StandardButtonDeveloperReport.share(a)};reset.setOnClickListener{StandardButtonLiveStyle.reset(a);applyNow(a);dialog.dismiss();show(a)};close.setOnClickListener{dialog.dismiss()};dialog.setOnShowListener{dialog.window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setGravity(Gravity.TOP);setLayout(-1,(a.resources.displayMetrics.heightPixels*.46f).roundToInt())};showTab("FOND");applyNow(a)};dialog.show()
 }
 private fun addControl(a:MainActivity,p:LinearLayout,s:Spec){val l=TextView(a).apply{setTextColor(primaryText(a));textSize=12f};val b=SeekBar(a).apply{max=1000};fun value(x:Int)=s.min+(s.max-s.min)*x/1000f;fun prog(v:Float)=(((v-s.min)/(s.max-s.min))*1000).roundToInt().coerceIn(0,1000);fun show(v:Float){l.text=if(s.integer)"${s.label} : ${v.roundToInt()}" else String.format(Locale.FRANCE,"%s : %.2f",s.label,v)};val init=s.get(StandardButtonLiveStyle.current(a));b.progress=prog(init);show(init);b.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(x:SeekBar?,q:Int,u:Boolean){if(!u)return;val v=value(q);if(s.integer)StandardButtonLiveStyle.setInt(a,s.key,v.roundToInt())else StandardButtonLiveStyle.setFloat(a,s.key,v);show(v);applyNow(a)};override fun onStartTrackingTouch(x:SeekBar?){};override fun onStopTrackingTouch(x:SeekBar?){}});p.addView(l);p.addView(b)}
 private fun applyNow(a:MainActivity){StandardButtonLiveStyle.applyTree(a,a.window.decorView);a.window.decorView.invalidate()}
}

object StandardButtonDeveloperReport {
 fun build(a:MainActivity):String{val c=StandardButtonLiveStyle.current(a);return "HORATRACK — RAPPORT STYLE BOUTONS STANDARDS\nGénéré : ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.FRANCE).format(Date())}\nFOND RGBA=${c.backgroundR},${c.backgroundG},${c.backgroundB},${c.backgroundAlpha}\nCADRE RGBA=${c.frameR},${c.frameG},${c.frameB},${c.frameAlpha} largeur=${c.frameWidthDp}dp arrondi=${c.cornerRadiusDp}dp\nTEXTE RGBA=${c.textR},${c.textG},${c.textB},${c.textAlpha} taille=${c.textSizeSp}sp padding=${c.horizontalPaddingDp}/${c.verticalPaddingDp}dp\nImage fond=${c.backgroundImageUri.ifBlank{"AUCUNE"}}\nImage cadre=${c.frameImageUri.ifBlank{"AUCUNE"}}"}
 fun share(a:MainActivity){a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"HoraTrack — rapport style boutons");putExtra(Intent.EXTRA_TEXT,build(a))},"Partager le rapport"))}
}
