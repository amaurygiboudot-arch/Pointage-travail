package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
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
}
