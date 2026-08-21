package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class SalaryTabTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : TextView(context, attrs, defStyleAttr) {
    companion object { private const val SALARY_PANEL_TAG = "integrated_salary_panel" }
    init { isClickable = true; isFocusable = true; setOnClickListener { showIntegratedSalaryTab() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); WidgetThemeSync.install(context); (context as? Activity)?.let { ButtonReliefInstaller.install(it) }; post { applyTabTypography(); installTabButtonStyle(); installAddressUi(); installSalaryAutoHide() } }

    private fun showIntegratedSalaryTab() {
        val root=rootView?:return; val contentPanel=root.findViewById<LinearLayout>(R.id.contentPanel)?:return
        val contentTitle=root.findViewById<TextView>(R.id.contentTitle); val historyText=root.findViewById<TextView>(R.id.historyText); val analyticsPanel=root.findViewById<View>(R.id.analyticsPdfPanel); val gpsPanel=root.findViewById<View>(R.id.gpsSettingsPanel); val statusCard=root.findViewById<View>(R.id.statusCard); val pointageButtons=root.findViewById<View>(R.id.pointageButtons); val shiftControl=root.findViewById<ShiftControlView>(R.id.shiftControlView)
        var salaryPanel=contentPanel.findViewWithTag<SalaryPanelView>(SALARY_PANEL_TAG); if(salaryPanel==null){ salaryPanel=SalaryPanelView(context).apply{tag=SALARY_PANEL_TAG;visibility=View.GONE}; contentPanel.addView(salaryPanel,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)}) }
        statusCard?.visibility=View.GONE;pointageButtons?.visibility=View.GONE;historyText?.visibility=View.GONE;analyticsPanel?.visibility=View.GONE;gpsPanel?.visibility=View.GONE;shiftControl?.visibility=View.VISIBLE;shiftControl?.refresh();contentTitle?.visibility=View.VISIBLE;contentTitle?.text="S A L A I R E";salaryPanel.visibility=View.VISIBLE;salaryPanel.refresh();selectTab(R.id.tabSalary)
    }
    private fun installSalaryAutoHide(){ val root=rootView?:return;val contentPanel=root.findViewById<LinearLayout>(R.id.contentPanel)?:return;fun hide(){val salary=contentPanel.findViewWithTag<SalaryPanelView>(SALARY_PANEL_TAG);val shift=root.findViewById<View>(R.id.shiftControlView);val other=root.findViewById<View>(R.id.pointageButtons)?.visibility==View.VISIBLE||root.findViewById<View>(R.id.historyText)?.visibility==View.VISIBLE||root.findViewById<View>(R.id.analyticsPdfPanel)?.visibility==View.VISIBLE||root.findViewById<View>(R.id.gpsSettingsPanel)?.visibility==View.VISIBLE;if(other){salary?.visibility=View.GONE;shift?.visibility=View.GONE};syncSelectedTabFromVisiblePanel()};listOf(root.findViewById<View>(R.id.pointageButtons),root.findViewById<View>(R.id.historyText),root.findViewById<View>(R.id.analyticsPdfPanel),root.findViewById<View>(R.id.gpsSettingsPanel)).forEach{it?.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->hide()}} }
    private fun applyTabTypography(){val ids=listOf(R.id.tabToday,R.id.tabHistory,R.id.tabAnalytics,R.id.tabSalary,R.id.tabSettings);ids.forEach{id->rootView.findViewById<TextView>(id)?.apply{textSize=12f;typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD);maxLines=2;includeFontPadding=false;gravity=Gravity.CENTER;setPadding(dp(4),dp(3),dp(4),dp(3));minimumWidth=0;minWidth=0;val raw=text.toString();val br=raw.indexOf('\n');if(br>0&&text !is SpannableString){val s=SpannableString(raw);s.setSpan(RelativeSizeSpan(1.55f),0,br,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);text=s}}}}
    private fun installTabButtonStyle(){val ids=listOf(R.id.tabToday,R.id.tabHistory,R.id.tabAnalytics,R.id.tabSalary,R.id.tabSettings);ids.forEach{id->val tab=rootView.findViewById<TextView>(id)?:return@forEach;tab.setOnTouchListener{_,e->if(e.actionMasked==MotionEvent.ACTION_UP)selectTab(id);false}};syncSelectedTabFromVisiblePanel()}
    private fun syncSelectedTabFromVisiblePanel(){val root=rootView?:return;val id=when{root.findViewById<View>(R.id.gpsSettingsPanel)?.visibility==View.VISIBLE->R.id.tabSettings;root.findViewById<View>(R.id.analyticsPdfPanel)?.visibility==View.VISIBLE->R.id.tabAnalytics;root.findViewById<LinearLayout>(R.id.contentPanel)?.findViewWithTag<SalaryPanelView>(SALARY_PANEL_TAG)?.visibility==View.VISIBLE->R.id.tabSalary;root.findViewById<View>(R.id.pointageButtons)?.visibility==View.VISIBLE->R.id.tabToday;else->R.id.tabHistory};selectTab(id)}
    private fun selectTab(activeId:Int){listOf(R.id.tabToday,R.id.tabHistory,R.id.tabAnalytics,R.id.tabSalary,R.id.tabSettings).forEach{id->rootView.findViewById<TextView>(id)?.let{it.isSelected=id==activeId;styleTab(it,id==activeId)}}}

    private fun styleTab(tab:TextView,active:Boolean){
        val theme=AppThemeCatalog.current(context);val dark=AppThemeCatalog.useDarkPalette(context)
        val text=if(dark)theme.darkText else theme.lightText;val accent=if(dark)theme.accentLight else theme.accent
        // Pas de blanc injecté : le fond reprend uniquement les couleurs normales du thème/boutons.
        val normalFill=if(dark) theme.darkPanel else theme.lightPanel
        val activeFill=blend(normalFill, accent, if(dark).18f else .12f)
        val fill=if(active) activeFill else normalFill
        val outer=rounded(Color.TRANSPARENT,accent,2f,15f);val blue=rounded(Color.TRANSPARENT,Color.rgb(5,91,220),2.5f,13f);val inner=rounded(fill,if(active)theme.accentLight else accent,1f,11f)
        tab.background=LayerDrawable(arrayOf(outer,blue,inner)).apply{setLayerInset(1,dp(3),dp(3),dp(3),dp(3));setLayerInset(2,dp(7),dp(7),dp(7),dp(7))}
        tab.setTextColor(text);tab.alpha=if(active)1f else .78f
    }
    private fun rounded(fill:Int,stroke:Int,strokeDp:Float,radiusDp:Float)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=radiusDp*resources.displayMetrics.density;setColor(fill);setStroke((strokeDp*resources.displayMetrics.density).toInt().coerceAtLeast(1),stroke)}
    private fun blend(a:Int,b:Int,amount:Float):Int{val t=amount.coerceIn(0f,1f);return Color.argb(Color.alpha(a).coerceAtLeast(180),(Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())}
    private fun installAddressUi(){val root=rootView?:return;val panel=root.findViewById<LinearLayout>(R.id.gpsSettingsPanel)?:return;val addressList=root.findViewById<EditText>(R.id.workplaceAddress)?:return;if(panel.findViewWithTag<AddAddressButton>("add_address_button")!=null)return;addressList.isFocusable=false;addressList.isFocusableInTouchMode=false;addressList.isCursorVisible=false;addressList.isLongClickable=false;addressList.hint="Aucune adresse — utilise le bouton +";addressList.setPadding(dp(12),dp(10),dp(12),dp(10));addressList.setBackgroundResource(R.drawable.hp_panel);val appearance=context.getSharedPreferences("appearance_settings",Context.MODE_PRIVATE);val mode=appearance.getString("mode","auto")?:"auto";val systemDark=(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;val dark=when(mode){"light"->false;"dark"->true;else->systemDark};val buttonBackground=Color.parseColor(if(dark)"#181818" else "#FFFFFF");val buttonText=Color.parseColor(if(dark)"#F3D58A" else "#111111");val addButton=AddAddressButton(context).apply{tag="add_address_button";text="+  AJOUTER UNE ADRESSE";textSize=16f;setBackgroundResource(R.drawable.hp_panel);backgroundTintList=ColorStateList.valueOf(buttonBackground);setTextColor(buttonText);isAllCaps=false};val index=panel.indexOfChild(addressList);if(index>=0)panel.addView(addButton,index+1,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)).apply{topMargin=dp(8)})}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
