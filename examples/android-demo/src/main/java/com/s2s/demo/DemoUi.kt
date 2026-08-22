package com.s2s.demo

import android.content.Context
import android.graphics.Color
import android.widget.TextView

/** Small gray caption label shared by the demo screens. */
fun Context.label(text: String, padTop: Int = 8): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTextColor(Color.GRAY)
    setPadding(0, padTop, 0, 2)
}
