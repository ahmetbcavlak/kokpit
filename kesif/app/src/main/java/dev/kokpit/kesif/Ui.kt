package dev.kokpit.kesif

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A full screen opened from the live screen (Keşif 0.8). Back returns to the live screen. */
interface Page {
    val root: View
    /** Called every [LiveProbe.FAST_MS] while the page is on screen. */
    fun tick()
    fun onShow() {}
    fun onHide() {}
}

/** View helpers shared by the 0.8 pages; same look as the live screen. */
class Ui(val a: Activity) {

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun label(t: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(a).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else -> Typeface.DEFAULT
        }
    }

    fun button(t: String, primary: Boolean, size: Float = if (primary) 18f else 16f, onClick: () -> Unit) = Button(a).apply {
        text = t
        isAllCaps = false
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setTextColor(if (primary) Color.parseColor("#10161B") else Palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setLineSpacing(0f, 0.95f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(if (primary) Palette.accent else Palette.surface)
            cornerRadius = dp(14).toFloat()
            if (!primary) setStroke(dp(1), Palette.line)
        }
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { onClick() }
    }

    fun card(parent: LinearLayout, block: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(16).toFloat() }
            block()
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        return c
    }

    /** Fixed title bar with a back button, and a scrolling column below it. */
    fun frame(title: String, subtitle: String, onBack: () -> Unit): Pair<LinearLayout, LinearLayout> {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.bg) }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Palette.surface)
            addView(button("← Canlı veri", primary = false) { onBack() }, LinearLayout.LayoutParams(dp(150), dp(54)).apply { rightMargin = dp(14) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 20f, Palette.text, bold = true))
                addView(label(subtitle, 13f, Palette.muted))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
        root.addView(ScrollView(a).apply { addView(content, MATCH_PARENT, WRAP_CONTENT) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root to content
    }
}

/** CSV cell quoting shared by the 0.8 logs. */
fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
