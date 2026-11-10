package com.shishyams.aicaller

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.google.android.material.card.MaterialCardView

/** A card that is always exactly as tall as it is wide (used for the home-screen tiles). */
class SquareCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
    }
}
