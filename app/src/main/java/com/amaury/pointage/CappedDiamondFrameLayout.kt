package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Keeps the real clickable diamond child centered and no wider than 108dp.
 * The frame itself may fill a weighted column, but blank space around the child
 * is not part of the button hit area. On narrow windows the child shrinks to fit.
 */
class CappedDiamondFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val capPx: Int
        get() = (108f * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
        val target = min(capPx, min(availableWidth, if (availableHeight > 0) availableHeight else capPx))

        setMeasuredDimension(
            resolveSize(availableWidth, widthMeasureSpec),
            resolveSize(capPx, heightMeasureSpec)
        )

        val childSpec = MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) child.measure(childSpec, childSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val childLeft = (width - child.measuredWidth) / 2
            val childTop = (height - child.measuredHeight) / 2
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight
            )
        }
    }
}
