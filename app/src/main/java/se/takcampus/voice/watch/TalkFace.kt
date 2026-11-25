package se.takcampus.voice.watch

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** A round handset-style control surface. Coordinates are fractions of the available diameter. */
internal class TalkFace(context: Context) : FrameLayout(context) {
    private val ink = Color.rgb(13, 36, 22)
    private val ivory = Color.rgb(250, 245, 223)
    private val chip = Color.rgb(41, 68, 54)
    private val dot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(dp(9), dp(9))
    }

    val status = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
        compoundDrawablePadding = dp(4)
        fit(11, 24)
    }
    val gear = key("⚙︎", Color.TRANSPARENT, Color.WHITE, 22, 32).apply { contentDescription = "Settings" }
    val channel = key("", chip, Color.WHITE, 12, 17).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
        setPadding(dp(6), 0, dp(13), 0)
    }
    private val chevron = TextView(context).apply {
        text = "›"; gravity = Gravity.CENTER; textSize = 18f
        setTextColor(Color.WHITE); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val repair = key("Repair", chip, Color.WHITE, 10, 15)
    val pad = key("PTT", ivory, ink, 16, 42)
    val note = TextView(context).apply {
        gravity = Gravity.CENTER; textSize = 11f; setSingleLine()
        ellipsize = TextUtils.TruncateAt.END; setTextColor(Color.rgb(207, 219, 209))
    }
    private var lastOnline: Boolean? = null
    private var lastPadStyle = -1

    init {
        listOf(status, gear, channel, chevron, repair, pad, note).forEach { addView(it, LayoutParams(1, 1)) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun TextView.fit(min: Int, max: Int) {
        setSingleLine()
        setHorizontallyScrolling(false)
        ellipsize = TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(min, max, 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private fun surface(color: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(0x337e9987), GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
        }, null)

    private fun key(label: String, fill: Int, color: Int, min: Int, max: Int) = Button(context).apply {
        text = label; isAllCaps = false; gravity = Gravity.CENTER
        setPadding(dp(3), 0, dp(3), 0); setTextColor(color)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = surface(fill, 13); fit(min, max)
    }

    fun colors(online: Boolean, onAir: Boolean, enabled: Boolean) {
        if (lastOnline != online) {
            lastOnline = online
            setBackgroundColor(if (online) Color.rgb(7, 48, 28) else Color.rgb(24, 29, 28))
            dot.setColor(if (online) Color.rgb(111, 239, 162) else Color.LTGRAY)
        }
        val style = if (onAir) 2 else if (enabled) 1 else 0
        if (lastPadStyle != style) {
            lastPadStyle = style
            pad.background = surface(when (style) { 2 -> Color.rgb(184, 47, 55); 1 -> ivory; else -> Color.rgb(73, 83, 76) }, 18)
            pad.setTextColor(if (style == 1) ink else Color.WHITE)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val side = minOf(width, height)
        fun place(view: View, left: Float, top: Float, right: Float, bottom: Float) {
            val params = view.layoutParams as LayoutParams
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.leftMargin = (width - side) / 2 + (left * side).roundToInt()
            params.topMargin = (height - side) / 2 + (top * side).roundToInt()
            params.width = ((right - left) * side).roundToInt()
            params.height = ((bottom - top) * side).roundToInt()
        }
        val channelEnd = if (repair.visibility == VISIBLE) .62f else .91f
        place(status, .18f, .09f, .67f, .31f)
        place(gear, .66f, .075f, .845f, .305f)
        place(channel, .074f, .322f, channelEnd, .495f)
        place(chevron, channelEnd - .071f, .322f, channelEnd - .014f, .495f)
        place(repair, .645f, .322f, .926f, .495f)
        place(note, .075f, .495f, .925f, .59f)
        place(pad, .045f, if (note.visibility == VISIBLE) .60f else .53f, .955f, .992f)
        // The physical screen clips the lowest corners. Keep the label inside the visible chord.
        pad.setPadding(dp(5), 0, dp(5), (side * .07f).roundToInt())
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
