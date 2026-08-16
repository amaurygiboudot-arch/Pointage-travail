package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
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

        val title = TextView(context).apply {
            text = "AJOUTER UN LIEU"
            setTextColor(context.getColor(R.color.hp_gold))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(5))
        }

        val addButton = AddAddressButton(context).apply {
            tag = "add_address_button"
            text = "+  AJOUTER UNE ADRESSE"
            textSize = 16f
            setTextColor(context.getColor(R.color.hp_gold_light))
            setBackgroundResource(R.drawable.hp_panel)
            isAllCaps = false
        }

        val addressIndex = panel.indexOfChild(addressList)
        if (addressIndex >= 0) {
            panel.addView(title, addressIndex + 1, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            panel.addView(addButton, addressIndex + 2, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply { topMargin = dp(4) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
