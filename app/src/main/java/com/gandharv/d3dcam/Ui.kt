package com.gandharv.d3dcam

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Tiny helpers for building simple screens in code. */
object Ui {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun column(ctx: Context, padDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
    }

    fun row(ctx: Context, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(ctx, 6)
            })
        }
    }

    fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(ctx, 20), 0, dp(ctx, 6))
    }

    fun note(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        alpha = 0.75f
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
    }

    fun button(ctx: Context, text: String, outlined: Boolean = false, onClick: () -> Unit) =
        (if (outlined) MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(ctx)).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    fun field(ctx: Context, hint: String, password: Boolean = false, value: String = ""): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(ctx).apply {
            this.hint = hint
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            setText(value)
        }
        layout.addView(edit)
        return layout to edit
    }

    /** Pads [v] by the system bars / cutout so content isn't drawn under them (edge-to-edge, Android 15+). */
    fun applySystemBarPadding(v: View) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val b = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                        androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            view.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    fun full(v: View, topDp: Int = 6): View {
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(v.context, topDp) }
        return v
    }
}
