package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** One stable, window-sized viewport for the clock and its celestial overlay. */
class CelestialHomePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val scroll = (parent as? View)?.parent as? ThemedBackgroundScrollView
        val available = scroll?.availableHomePanelHeight(this)
        if (available != null) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY))
        } else {
            // Ordinary embedding retains its parent's measurement contract.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Both layers must re-record their display lists in the same layout pass
        // after a real window/inset change, not on their different sensor ticks.
        findViewById<View>(R.id.heroClockPermanent)?.invalidate()
        findViewById<View>(R.id.sunIndicator)?.invalidate()
    }
}
