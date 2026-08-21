package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatButton

class FirebaseAccountButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    init {
        text = "COMPTE GOOGLE"
        setOnClickListener {
            context.startActivity(Intent(context, FirebaseAccountActivity::class.java))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            val container = parent as? ViewGroup ?: return@post
            if (container.indexOfChild(this) != container.childCount - 1) {
                val params = layoutParams
                container.removeView(this)
                container.addView(this, params)
            }
        }
    }
}
