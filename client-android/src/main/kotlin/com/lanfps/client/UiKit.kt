package com.lanfps.client

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A handful of helpers that build the menu widgets in code.
 *
 * There are no XML layouts and no AndroidX in this project: the whole UI is
 * five screens of plain framework Views, so a tiny styling kit is less code
 * (and far fewer moving parts) than a resource-driven theme.
 */
object UiKit {

    const val BG = 0xFF0D1117.toInt()
    const val PANEL = 0xFF161B22.toInt()
    const val PANEL_HI = 0xFF1F2630.toInt()
    const val LINE = 0xFF2C333D.toInt()
    const val ACCENT = 0xFFF2A33C.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val TEXT_DIM = 0xFF8B949E.toInt()
    const val GOOD = 0xFF6CD084.toInt()
    const val BAD = 0xFFE0584C.toInt()
    const val RED_TEAM = 0xFFEC6E60.toInt()
    const val BLUE_TEAM = 0xFF6EA5F5.toInt()

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
            .toInt()

    fun rounded(color: Int, radiusPx: Int, strokePx: Int = 0, strokeColor: Int = LINE) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun title(ctx: Context, s: String, sizeSp: Float = 26f, color: Int = TEXT): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
        }

    fun label(ctx: Context, s: String, sizeSp: Float = 12f, color: Int = TEXT_DIM): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        }

    fun mono(ctx: Context, s: String, sizeSp: Float = 12f, color: Int = TEXT): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = android.graphics.Typeface.MONOSPACE
        }

    fun field(ctx: Context, initial: String, hint: String, numeric: Boolean = false): EditText =
        EditText(ctx).apply {
            setText(initial)
            setHint(hint)
            setHintTextColor(TEXT_DIM)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = true
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            background = rounded(PANEL_HI, dp(ctx, 6f), dp(ctx, 1f), LINE)
            setPadding(dp(ctx, 12f), dp(ctx, 9f), dp(ctx, 12f), dp(ctx, 9f))
        }

    fun button(ctx: Context, s: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = s
            isAllCaps = true
            setTextColor(if (primary) 0xFF14181D.toInt() else TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            stateListAnimator = null
            val r = dp(ctx, 6f)
            val normal = if (primary) rounded(ACCENT, r) else rounded(PANEL_HI, r, dp(ctx, 1f), LINE)
            val pressed = if (primary) {
                rounded(0xFFFFBE5C.toInt(), r)
            } else {
                rounded(0xFF2A323D.toInt(), r, dp(ctx, 1f), ACCENT)
            }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
            setPadding(dp(ctx, 18f), dp(ctx, 10f), dp(ctx, 18f), dp(ctx, 10f))
            setOnClickListener { onClick() }
        }

    fun panel(ctx: Context, vertical: Boolean = true): LinearLayout = LinearLayout(ctx).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        background = rounded(PANEL, dp(ctx, 10f), dp(ctx, 1f), LINE)
        setPadding(dp(ctx, 16f), dp(ctx, 14f), dp(ctx, 16f), dp(ctx, 14f))
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun spacer(ctx: Context, heightDp: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, heightDp),
        )
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, dp(ctx, 1f)),
        ).apply {
            topMargin = dp(ctx, 8f)
            bottomMargin = dp(ctx, 8f)
        }
    }

    fun lp(
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        margins: IntArray? = null,
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight).apply {
        margins?.let { setMargins(it[0], it[1], it[2], it[3]) }
    }

    fun teamColor(team: com.lanfps.shared.Team): Int = when (team) {
        com.lanfps.shared.Team.RED -> RED_TEAM
        com.lanfps.shared.Team.BLUE -> BLUE_TEAM
        else -> TEXT
    }

    fun dim(color: Int, factor: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )
}
