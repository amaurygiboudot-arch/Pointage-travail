package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class SalaryNavBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(7), 0, dp(7))
        setBackgroundResource(R.drawable.hp_nav_panel)

        addTab("◷\nAUJOURD'HUI", "today", false)
        addTab("▥\nHISTORIQUE", "history", false)
        addTab("◔\nANALYSES", "analytics", false)
        addTab("€\nSALAIRE", "salary", true)
        addTab("⚙\nPARAMÈTRES", "settings", false, 13f)
    }

    private fun addTab(label: String, target: String, active: Boolean, size: Float = 14f) {
        val appearance = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = appearance.getString("mode", "auto") ?: "auto"
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && systemDark)
        val activeColor = Color.parseColor(if (dark) "#F3D58A" else "#795600")
        val inactiveColor = Color.parseColor(if (dark) "#CFC7B8" else "#555555")

        val tab = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = size
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setTextColor(if (active) activeColor else inactiveColor)
            isClickable = !active
            isFocusable = !active
            if (!active) setOnClickListener {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("open_tab", target)
                }
                context.startActivity(intent)
                (context as? android.app.Activity)?.finish()
            }
        }
        addView(tab, LayoutParams(0, dp(58), 1f))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
