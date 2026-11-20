// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Tiny programmatic-UI helpers: the screens are simple, and code is easier to keep consistent than XML here. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.attrColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
}

fun Context.column(pad: Int = 16, build: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    build()
}

fun Context.row(build: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    build()
}

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply { addView(child); isFillViewport = true }

fun Context.text(s: CharSequence, size: Float = 16f, bold: Boolean = false, color: Int? = null): TextView = TextView(this).apply {
    text = s
    textSize = size
    if (bold) setTypeface(typeface, Typeface.BOLD)
    color?.let { setTextColor(it) }
    setTextIsSelectable(false)
}

fun Context.header(s: String): TextView = text(s, 14f, true, attrColor(androidx.appcompat.R.attr.colorPrimary)).apply {
    setPadding(0, dp(20), 0, dp(6))
}

fun Context.button(label: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
    val style = if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle
    return MaterialButton(this, null, style).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}

fun Context.iconButton(@DrawableRes icon: Int, desc: String, sizeDp: Int = 48, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
    setImageResource(icon)
    contentDescription = desc
    val tv = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
    setBackgroundResource(tv.resourceId)
    imageTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorOnSurface))
    minimumWidth = dp(sizeDp); minimumHeight = dp(sizeDp)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    setOnClickListener { onClick() }
}

fun Context.switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch = MaterialSwitch(this).apply {
    text = label
    textSize = 16f
    isChecked = checked
    setPadding(0, dp(6), 0, dp(6))
    setOnCheckedChangeListener { _, v -> onChange(v) }
}

fun Context.dialog(title: String): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(this).setTitle(title)

fun Context.info(title: String, msg: String): AlertDialog = dialog(title).setMessage(msg).setPositiveButton("OK", null).show()

fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun weight1() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

fun fmtTime(sec: Double): String {
    if (sec.isNaN() || sec < 0) return "0:00"
    val s = sec.toLong()
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}
