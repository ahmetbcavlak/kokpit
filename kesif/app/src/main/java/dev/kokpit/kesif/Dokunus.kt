package dev.kokpit.kesif

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import java.util.Locale

/**
 * "Dokunuş" (Keşif 0.9, wave A: 100 ms response). For each touch: from the touch's own time
 * (MotionEvent.getEventTime) to the frame that draws the answer (Choreographer's frame time of the
 * next frame), and to the frame after it, when that picture is on the glass. All on the monotonic
 * clock. 20 touches, then the average and the 95th percentile.
 */
object TouchStats {
    const val COUNT = 20

    data class Tap(val n: Int, val dispatchMs: Double, val frameMs: Double, val shownMs: Double)

    private val taps = ArrayList<Tap>()

    /** true only for the add that completes the set (so the summary is written once). */
    @Synchronized fun add(t: Tap): Boolean { if (taps.size >= COUNT) return false; taps.add(t); return taps.size == COUNT }
    @Synchronized fun all(): List<Tap> = taps.toList()
    @Synchronized fun clear() = taps.clear()
    val done: Boolean get() = all().size >= COUNT

    fun mean(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

    /** Nearest-rank 95th percentile. */
    fun p95(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val rank = Math.ceil(0.95 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }

    fun summary(): List<Pair<String, String>> {
        val t = all()
        fun f(x: Double) = String.format(Locale.US, "%.1f ms", x)
        return listOf(
            "dokunuş" to "${t.size}",
            "kareye ortalama" to f(mean(t.map { it.frameMs })), "kareye p95" to f(p95(t.map { it.frameMs })),
            "ekrana ortalama" to f(mean(t.map { it.shownMs })), "ekrana p95" to f(p95(t.map { it.shownMs })),
            "işleme ortalama" to f(mean(t.map { it.dispatchMs })),
        )
    }
}

/** The touch target: flips colour on each touch so every touch needs a new frame. */
@SuppressLint("ViewConstructor")
class TouchTarget(a: Activity, private val onTap: (Long, Long) -> Unit) : View(a) {
    private var on = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.color = if (on) Palette.accent else Palette.line
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 32f, 32f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            on = !on
            invalidate()
            onTap(e.eventTime, SystemClock.uptimeMillis())
        }
        return true
    }
}

class TouchPage(private val a: Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val result = ui.label("", 16f, Palette.text, mono = true)
    private var n = TouchStats.all().size

    init {
        val (r, content) = ui.frame("4 · Dokunuş", "Dokunuştan ekrandaki kareye · 20 dokunuş", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("Aşağıdaki alana 20 kez, aralıklı dokun (sürükleme yok). Her dokunuşta renk değişir; renk değişimini çizen karenin zamanı ölçülür.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(TouchTarget(a) { ev, handled -> tap(ev, handled) }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(300)).apply { bottomMargin = ui.dp(10) })
            addView(ui.button("Sıfırla", primary = false) { TouchStats.clear(); n = 0; tick() }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(54)))
        }
        ui.card(content) {
            addView(ui.label("Sonuç", 17f, Palette.accent, bold = true))
            addView(result.apply { setPadding(0, ui.dp(6), 0, 0) })
        }
    }

    private fun tap(eventUptime: Long, handledUptime: Long) {
        if (TouchStats.done) return
        val no = ++n
        val ch = Choreographer.getInstance()
        ch.postFrameCallback { f1 ->
            ch.postFrameCallback { f2 ->
                val t = TouchStats.Tap(no, (handledUptime - eventUptime).toDouble(),
                    f1 / 1e6 - eventUptime, f2 / 1e6 - eventUptime)
                val last = TouchStats.add(t)
                Yol.row(a, "dokunus", "dokunus", "#$no", String.format(Locale.US, "kare %.1f ms · ekran %.1f ms · işleme %.1f ms", t.frameMs, t.shownMs, t.dispatchMs))
                if (last) Yol.rows(a, "dokunus", "ozet", TouchStats.summary())
                tick()
            }
        }
    }

    override fun tick() {
        val t = TouchStats.all()
        result.text = if (t.isEmpty()) "Henüz dokunuş yok." else
            "${t.size} / ${TouchStats.COUNT}\n" + TouchStats.summary().drop(1).joinToString("\n") { "${it.first}: ${it.second}" } +
                if (TouchStats.done) "\n\nTamam: sonuç yol-olcumleri.csv’de." else ""
    }
}
