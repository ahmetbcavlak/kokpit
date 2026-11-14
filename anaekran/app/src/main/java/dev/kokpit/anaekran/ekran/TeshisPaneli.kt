package dev.kokpit.anaekran.ekran

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi

/**
 * Hidden diagnostics: a full-screen, 90 % dark panel over everything, opened by a long press on
 * the frame-rate counter, closed by a tap. Geist Mono 14 px lines from [satirlar] (the data
 * layer's raw climate extras, outside-temperature broadcast, GPS, door events; the clip's
 * first-frame time; screen size, density, the u() scale; the version). Refreshed once a second
 * while open, so the raw climate values can be watched while turning the car's knobs — on the
 * car this is the only way to confirm the climate encoding. Reads only; writes nothing anywhere.
 * Always dark (it is a tool, not part of the day/night design).
 *
 * One action, "Kapı uyarısını sıfırla" ([kapiSifirla]): forgets the app's own door/boot state
 * (a warning stuck after a missed close event). It clears memory inside this app only — nothing
 * is sent to the car or the system (K-29). The panel stays open so the result can be read.
 */
@SuppressLint("ViewConstructor")
class TeshisPaneli(
    context: Context,
    private val satirlar: () -> List<String>,
    private val kapiSifirla: () -> Unit = {},
) : FrameLayout(context) {

    /** The reset row (tests press it). */
    val sifirlaDugmesi = context.metin(Yazi.mono, 14, Palet.GECE.acc).apply {
        setText(R.string.teshis_kapi_sifirla)
        minHeight = u(48) // a finger-sized target, not a 14 px line
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(u(32), u(24), u(32), 0)
        isClickable = true
        setOnClickListener {
            kapiSifirla()
            setText(R.string.teshis_kapi_sifirlandi)
            yenile.run()
        }
    }

    private val yazi = context.metin(Yazi.mono, 14, Palet.GECE.tx).apply {
        setLineSpacing(0f, 1.3f)
        setPadding(u(32), u(16), u(32), u(40))
        isClickable = true
        setOnClickListener { kapat() }
    }

    private val yenile = object : Runnable {
        override fun run() {
            yazi.yaz(satirlar().joinToString("\n"))
            postDelayed(this, YENILEME_MS)
        }
    }

    init {
        setBackgroundColor(Color.argb(230, Color.red(Palet.GECE.bg), Color.green(Palet.GECE.bg), Color.blue(Palet.GECE.bg)))
        isClickable = true
        setOnClickListener { kapat() }
        val sutun = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sifirlaDugmesi, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(yazi, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val kaydir = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(sutun, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(kaydir, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    val acik: Boolean get() = visibility == VISIBLE

    /**
     * Told when the panel opens (true) or closes (false). MainActivity stops the drive scene and
     * the speed loop while it is open: the 90 % panel covers them (see MainActivity).
     */
    var degisti: ((Boolean) -> Unit)? = null

    fun ac() {
        sifirlaDugmesi.setText(R.string.teshis_kapi_sifirla)
        val once = acik
        visibility = VISIBLE
        removeCallbacks(yenile)
        yenile.run()
        if (!once) degisti?.invoke(true)
    }

    fun kapat() {
        removeCallbacks(yenile)
        val once = acik
        visibility = GONE
        if (once) degisti?.invoke(false)
    }

    /** The text on screen (tests). */
    val metin: CharSequence get() = yazi.text

    override fun onDetachedFromWindow() {
        removeCallbacks(yenile)
        super.onDetachedFromWindow()
    }

    companion object {
        const val YENILEME_MS = 1000L
    }
}
