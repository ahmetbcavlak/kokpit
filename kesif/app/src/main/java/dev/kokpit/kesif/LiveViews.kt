package dev.kokpit.kesif

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

object Palette {
    val bg = Color.parseColor("#0E1114")
    val surface = Color.parseColor("#171C21")
    val line = Color.parseColor("#2A333B")
    val text = Color.parseColor("#ECEEEA")
    val muted = Color.parseColor("#9AA5AD")
    val faint = Color.parseColor("#5E6870")
    val accent = Color.parseColor("#F0A030")
    val live = Color.parseColor("#4CC38A")

    fun of(s: LiveProbe.Status) = when (s) {
        LiveProbe.Status.NONE -> faint
        LiveProbe.Status.PRESENT -> accent
        LiveProbe.Status.LIVE -> live
    }
}

/** Top view of the car: four doors and the boot, an open one turns amber. */
class DoorsView(ctx: Context) : View(ctx) {
    /** Values for cardoor1..5: front-left, front-right, rear-left, rear-right, boot. */
    var values: List<String> = List(5) { "" }
        set(v) { if (field != v) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bw = w * 0.42f; val left = (w - bw) / 2; val top = h * 0.04f; val bh = h * 0.92f
        p.style = Paint.Style.FILL; p.color = Palette.line
        c.drawRoundRect(RectF(left, top, left + bw, top + bh), bw * 0.32f, bw * 0.32f, p)
        // Doors: front pair, rear pair, then the boot.
        val dw = bw * 0.16f
        val rows = listOf(top + bh * 0.30f to top + bh * 0.50f, top + bh * 0.52f to top + bh * 0.72f)
        fun color(v: String) = when (v) { "2" -> Palette.accent; "" -> Palette.faint; "1" -> Palette.muted; else -> Palette.text }
        rows.forEachIndexed { i, (y0, y1) ->
            p.color = color(values.getOrElse(i * 2) { "" })
            c.drawRoundRect(RectF(left - dw * 0.35f, y0, left + dw * 0.65f, y1), 6f, 6f, p)
            p.color = color(values.getOrElse(i * 2 + 1) { "" })
            c.drawRoundRect(RectF(left + bw - dw * 0.65f, y0, left + bw + dw * 0.35f, y1), 6f, 6f, p)
        }
        p.color = color(values.getOrElse(4) { "" })
        c.drawRoundRect(RectF(left + bw * 0.18f, top + bh * 0.84f, left + bw * 0.82f, top + bh * 0.97f), 8f, 8f, p)
        // Windscreen hint, so front is obvious.
        p.color = Palette.surface
        c.drawRoundRect(RectF(left + bw * 0.16f, top + bh * 0.16f, left + bw * 0.84f, top + bh * 0.27f), 10f, 10f, p)
    }
}

/** Steering wheel turned by the raw angle value. The scale is not known yet, so the number is the truth. */
class WheelView(ctx: Context) : View(ctx) {
    var angle: Float? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) * 0.42f
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f
        p.color = if (angle == null) Palette.faint else Palette.muted
        c.drawCircle(cx, cy, r, p)
        val a = angle ?: return
        c.save()
        c.rotate(a.coerceIn(-900f, 900f), cx, cy)
        p.color = Palette.accent; p.strokeWidth = r * 0.14f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy - r, cx, cy - r * 0.55f, p)
        p.color = Palette.muted; p.strokeWidth = r * 0.12f
        c.drawLine(cx - r, cy, cx + r, cy, p)
        c.drawLine(cx, cy, cx, cy + r, p)
        c.restore()
    }
}

/** Eight bars for radar0..7. Height follows the raw value, an empty key is a dash. */
class RadarView(ctx: Context) : View(ctx) {
    var values: List<String> = List(8) { "" }
        set(v) { if (field != v) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val n = 8; val gap = width * 0.02f
        val bw = (width - gap * (n - 1)) / n
        val textH = height * 0.22f
        tp.textSize = textH * 0.7f
        val nums = values.map { it.toFloatOrNull() }
        val max = (nums.filterNotNull().maxOrNull() ?: 0f).coerceAtLeast(1f)
        for (i in 0 until n) {
            val x = i * (bw + gap)
            val v = nums.getOrNull(i)
            p.color = Palette.line
            c.drawRoundRect(RectF(x, 0f, x + bw, height - textH), 6f, 6f, p)
            if (v != null && v > 0) {
                val top = (height - textH) * (1 - v / max)
                p.color = Palette.accent
                c.drawRoundRect(RectF(x, top, x + bw, height - textH), 6f, 6f, p)
            }
            tp.color = if (v == null) Palette.faint else Palette.text
            c.drawText(values.getOrElse(i) { "" }.ifEmpty { "—" }, x + bw / 2, height - textH * 0.2f, tp)
        }
    }
}
