package com.amaury.pointage

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

object LuxuryUiInstaller {
    private const val TAG_CLOCK = "hp_luxury_analog_clock"

    fun install(activity: MainActivity) {
        val digital = activity.findViewById<TextClock>(R.id.clockDigital) ?: return
        val parent = digital.parent as? LinearLayout ?: return

        val analog = (parent.findViewWithTag<View>(TAG_CLOCK) as? HpAnalogClockView)
            ?: HpAnalogClockView(activity).apply {
                tag = TAG_CLOCK
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(activity, 320)
                ).apply {
                    topMargin = dp(activity, 8)
                    bottomMargin = dp(activity, 10)
                }
                parent.addView(this, parent.indexOfChild(digital))
            }

        digital.visibility = View.GONE

        val buttons = activity.findViewById<LinearLayout>(R.id.pointageButtons)
        fun syncTodayVisibility() {
            analog.visibility = if (buttons?.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            digital.visibility = View.GONE
        }
        syncTodayVisibility()
        buttons?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncTodayVisibility() }
        digital.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (digital.visibility == View.VISIBLE) digital.visibility = View.GONE
        }

        activity.findViewById<TextView>(R.id.logoText)?.apply {
            text = "♛\nH  P\nT R A V A I L"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D6A84B"))
            typeface = Typeface.create("serif", Typeface.BOLD)
            textSize = 20f
            letterSpacing = 0.08f
        }

        activity.findViewById<TextView>(R.id.statusCard)?.apply {
            setTextColor(Color.parseColor("#F4EFE3"))
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = 17f
            letterSpacing = 0.04f
        }

        activity.findViewById<TextView>(R.id.contentTitle)?.apply {
            setTextColor(Color.parseColor("#D6A84B"))
            typeface = Typeface.create("serif", Typeface.BOLD)
            letterSpacing = 0.08f
        }

        activity.findViewById<TextView>(R.id.historyText)?.apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            letterSpacing = 0.03f
        }

        // The luxury styling above sets colors intended for a dark background.
        // Re-apply the selected Light / Dark / Automatic appearance last so those
        // decorative defaults cannot make text disappear in light mode.
        AppearanceManager.apply(activity)
    }

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
