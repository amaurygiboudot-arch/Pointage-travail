package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class SalaryTabTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : TextView(context, attrs, defStyleAttr) {
    companion object {
        private const val SALARY_PANEL_TAG = "integrated_salary_panel"
        private const val INFO_SHEET_TAG = SalaryInformationSheetView.TAG
        private const val CONTROL_HEIGHT_DP = 54
    }
    private var autoHidePosted = false
    init { isClickable = true; isFocusable = true; setOnClickListener { showIntegratedSalaryTab() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); WidgetThemeSync.install(context); (context as? Activity)?.let { ButtonReliefInstaller.install(it) }; post { applyTabTypography(); installTabButtonStyle(); installAddressUi(); installSalaryAutoHide(); normalizeFrameSizes() } }

    private fun showIntegratedSalaryTab() {
        val root = rootView ?: return
        val contentPanel = root.findViewById<LinearLayout>(R.id.contentPanel) ?: return
        val contentTitle = root.findViewById<TextView>(R.id.contentTitle)
        val historyText = root.findViewById<TextView>(R.id.historyText)
        val analyticsPanel = root.findViewById<View>(R.id.analyticsPdfPanel)
        val gpsPanel = root.findViewById<View>(R.id.gpsSettingsPanel)
        val statusCard = root.findViewById<View>(R.id.statusCard)
        val pointageButtons = root.findViewById<View>(R.id.pointageButtons)
        val shiftControl = root.findViewById<ShiftControlView>(R.id.shiftControlView)

        // Une seule façade Salaire : le panneau intégré. Les anciennes briques sont gardées
        // uniquement comme secours interne et ne sont jamais affichées en double.
        var salaryPanel = contentPanel.findViewWithTag<SalaryPanelView>(SALARY_PANEL_TAG)
        if (salaryPanel == null) {
            salaryPanel = SalaryPanelView(context).apply { tag = SALARY_PANEL_TAG; visibility = View.GONE }
            contentPanel.addView(salaryPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        CompanyBasePauseInstaller.install(salaryPanel)

        // V2SalaryExtrasWatcher réorganise ce même panneau en accès unique :
        // Ajouter entreprise, fiche renseignements, entreprises, contrat, fiche salaire,
        // droits/congés/repos. Ne pas afficher une seconde fiche de renseignements ici.
        contentPanel.findViewWithTag<SalaryInformationSheetView>(INFO_SHEET_TAG)?.visibility = View.GONE

        statusCard?.visibility = View.GONE
        pointageButtons?.visibility = View.GONE
        historyText?.visibility = View.GONE
        analyticsPanel?.visibility = View.GONE
        gpsPanel?.visibility = View.GONE
        shiftControl?.visibility = View.VISIBLE
        shiftControl?.refresh()
        contentTitle?.visibility = View.VISIBLE
        contentTitle?.text = "S A L A I R E"
        salaryPanel.visibility = View.VISIBLE
        salaryPanel.refresh()
        // Force un passage de layout afin que le watcher V2 déjà installé raccorde immédiatement
        // ses accès, y compris au premier affichage de l'onglet Salaire.
        salaryPanel.requestLayout()
        selectTab(R.id.tabSalary)
        normalizeFrameSizes()
    }

    private fun installSalaryAutoHide() {
        val root = rootView ?: return; val contentPanel = root.findViewById<LinearLayout>(R.id.contentPanel) ?: return
        fun scheduleCheck() { if (autoHidePosted) return; autoHidePosted = true; post { autoHidePosted = false; val salary=contentPanel.findViewWithTag<SalaryPanelView>(SALARY_PANEL_TAG); val info=contentPanel.findViewWithTag<SalaryInformationSheetView>(INFO_SHEET_TAG); val shift=root.findViewById<View>(R.id.shiftControlView); val other=root.findViewById<View>(R.id.pointageButtons)?.visibility==View.VISIBLE || root.findViewById<View>(R.id.historyText)?.visibility==View.VISIBLE || root.findViewById<View>(R.id.analyticsPdfPanel)?.visibility==View.VISIBLE || root.findViewById<View>(R.id.gpsSettingsPanel)?.visibility==View.VISIBLE; if(other){salary?.visibility=View.GONE;info?.visibility=View.GONE;shift?.visibility=View.GONE}; syncSelectedTabFromVisiblePanel() } }
        listOf(root.findViewById<View>(R.id.pointageButtons),root.findViewById<View>(R.id.historyText),root.findViewById<View>(R.id.analyticsPdfPanel),root.findViewById<View>(R.id.gpsSettingsPanel)).forEach{v->v?.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->scheduleCheck()}}
    }
    private fun applyTabTypography(){listOf(R.id.tabToday,R.id.tabHistory,R.id.tabAnalytics,R.id.tabSalary,R.id.tabSettings).forEach{id->rootView.findViewById<TextView>(id)?.apply{textSize=12f;typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD);maxLines=2;ellipsize=TextUtils.TruncateAt.END;includeFontPadding=false;gravity=Gravity.CENTER;setPadding(dp(4),dp(3),dp(4),dp(3));minimumWidth=0;minWidth=0;val raw=text.toString();val br=raw.indexOf('\n');if(br>0&&text !is SpannableString){val s=SpannableString(raw);s.setSpan(RelativeSizeSpan(1.45f),0,br,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);text=s}}}}
    private fun installTabButtonStyle(){listOf(R.id.tabToday,R.id.tabHistory,R.id.tabAnalytics,R.id.tabSalary,R.id.tabSettings).forEach{id->val tab=rootView.findViewById<TextView>(id)?:return@forEach;tab.setOnTouchListener{_,e->if(e.actionMasked==MotionEvent.ACTION_UP)selectTab(id);false}};syncSelectedTabFromVisiblePanel()}
    private fun syncSelectedTabFromVisiblePanel(){val root=rootView?:return;val id=when{root.findViewById<View>(R.id.gpsSettingsPanel)?.visibility==View.VISIBLE->R.id.tabSettings;root.findViewById<View>(R.id.analyticsPdfPanel)?.visibility==View.VISIBLE->R.id.tabAnalytics;root.findViewById<LinearLayout>(R.id.contentPanel)?.findViewWithTag<SalaryPanelView>(SALARY_PANEL_TAG)?.visibility==View.VISIBLE->R.id.tabSalary;root.findViewById<View>(R.id.pointageButtons)?.visibility==View.VISIBLE->R.id.tabToday;else->R.id.tabHistory};selectTab(id)}
    private fun selectTab(activeId:Int){listOf(R.id.tabToday,R.id.tabHistory,R.id.tabAnalytics,R.id.tabSalary,R.id.tabSettings).forEach{id->rootView.findViewById<TextView>(id)?.let{val selected=id==activeId;if(it.isSelected!=selected)it.isSelected=selected;styleTab(it,selected)}}}
    private fun styleTab(tab:TextView,active:Boolean){val theme=AppThemeCatalog.current(context);val dark=AppThemeCatalog.useDarkPalette(context);val activeText=if(dark)theme.darkText else theme.lightText;val inactiveText=if(dark)theme.darkHint else theme.lightHint;tab.elevation=if(active)3f*resources.displayMetrics.density else 0f;tab.alpha=if(active)1f else .78f;tab.setTextColor(if(active)activeText else inactiveText);tab.backgroundTintList=null;when(theme.id){"natural_carbon"->if(tab.background !is CarbonCompositeDrawable)tab.background=CarbonCompositeDrawable(context);"signature_gold"->if(tab.background is CarbonCompositeDrawable||tab.background==null)tab.background=context.getDrawable(R.drawable.hp_panel)?.mutate()}}
    private fun normalizeFrameSizes(){val root=rootView?:return;normalizeRecursive(root);listOf(R.id.tabToday,R.id.tabHistory,R.id.tabAnalytics,R.id.tabSalary,R.id.tabSettings).forEach{id->root.findViewById<TextView>(id)?.let{fitText(it,true)}};ThemeFrameStyler.apply(root)}
    private fun normalizeRecursive(view:View){val id=runCatching{view.resources.getResourceEntryName(view.id)}.getOrNull().orEmpty();val protected=id=="entryButton"||id=="pauseButton"||id=="exitButton"||id=="settingsButton";if(!protected&&view.visibility!=View.GONE){when(view){is Button->{view.layoutParams?.let{lp->if(lp.height!=dp(CONTROL_HEIGHT_DP)){lp.height=dp(CONTROL_HEIGHT_DP);view.layoutParams=lp}};fitText(view,false)};is Switch->{view.layoutParams?.let{lp->if(lp.height!=dp(CONTROL_HEIGHT_DP)){lp.height=dp(CONTROL_HEIGHT_DP);view.layoutParams=lp}};fitText(view,false)}}};if(view is ViewGroup)for(i in 0 until view.childCount)normalizeRecursive(view.getChildAt(i))}
    private fun fitText(view:TextView,navigation:Boolean){view.gravity=Gravity.CENTER;view.includeFontPadding=false;view.maxLines=2;view.ellipsize=TextUtils.TruncateAt.END;view.setPadding(dp(if(navigation)4 else 14),dp(5),dp(if(navigation)4 else 14),dp(5));if(!navigation)view.setTextSize(TypedValue.COMPLEX_UNIT_SP,14f)}
    private fun installAddressUi(){val root=rootView?:return;val panel=root.findViewById<LinearLayout>(R.id.gpsSettingsPanel)?:return;val addressList=root.findViewById<EditText>(R.id.workplaceAddress)?:return;if(panel.findViewWithTag<AddAddressButton>("add_address_button")!=null)return;addressList.isFocusable=false;addressList.isFocusableInTouchMode=false;addressList.isCursorVisible=false;addressList.isLongClickable=false;addressList.hint="Aucune adresse — utilise le bouton +";addressList.setPadding(dp(12),dp(10),dp(12),dp(10));val theme=AppThemeCatalog.current(context);val dark=AppThemeCatalog.useDarkPalette(context);val addButton=AddAddressButton(context).apply{tag="add_address_button";text="+  AJOUTER UNE ADRESSE";textSize=14f;isAllCaps=false;backgroundTintList=null;background=when(theme.id){"natural_carbon"->CarbonCompositeDrawable(context);else->context.getDrawable(R.drawable.hp_panel)?.mutate()};setTextColor(if(dark)theme.darkText else theme.lightText)};val index=panel.indexOfChild(addressList);if(index>=0){panel.addView(addButton,index+1,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(CONTROL_HEIGHT_DP)).apply{topMargin=dp(8)});post{normalizeFrameSizes()}}}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
