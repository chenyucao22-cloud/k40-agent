package dev.channing.androidagent

import android.app.Activity
import android.view.WindowManager
import android.widget.*

fun Activity.secureWindow() { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
fun Activity.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(24, 24, 24, 24)
}
fun LinearLayout.label(value: String, size: Float = 16f): TextView = TextView(context).apply {
    text = value; textSize = size; setPadding(0, 12, 0, 12)
}.also { addView(it) }
fun LinearLayout.button(value: String, click: () -> Unit): Button = Button(context).apply {
    text = value; filterTouchesWhenObscured = true
    setOnClickListener { click() }
}.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }
