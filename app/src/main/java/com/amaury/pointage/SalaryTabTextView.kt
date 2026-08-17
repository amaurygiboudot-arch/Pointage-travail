package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class SalaryTabTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            context.startActivity(Intent(context, SalaryActivity::class.java))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { installAddressUi() }
    }

    private fun installAddressUi() {
        val root = rootView ?: return
        val panel = root.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return
        val addressList = root.findViewById<EditText>(R.id.workplaceAddress) ?: return

        if (panel.findViewWithTag<AddAddressButton>("add_address_button") != null) return

        // La liste reste visible mais ne déclenche plus le clavier / validation accidentelle.
        addressList.isFocusable = false
        addressList.isFocusableInTouchMode = false
        addressList.isCursorVisible = false
        addressList.isLongClickable = false
        addressList.hint = "Aucune adresse — utilise le bouton +"
        addressList.setPadding(dp(12), dp(10), dp(12), dp(10))
        addressList.setBackgroundResource(R.drawable.hp_panel)

        val appearance = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = appearance.getString("mode", "auto") ?: "auto"
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
        val buttonBackground = Color.parseColor(if (dark) "#181818" else "#FFFFFF")
        val buttonText = Color.parseColor(if (dark) "#F3D58A" else "#111111")

        val addButton = AddAddressButton(context).apply {
            tag = "add_address_button"
            text = "+  AJOUTER UNE ADRESSE"
            textSize = 16f
            setBackgroundResource(R.drawable.hp_panel)
            backgroundTintList = ColorStateList.valueOf(buttonBackground)
            setTextColor(buttonText)
            isAllCaps = false
        }

        val addressIndex = panel.indexOfChild(addressList)
        if (addressIndex >= 0) {
            panel.addView(addButton, addressIndex + 1, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply { topMargin = dp(8) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
