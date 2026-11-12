package dev.kokpit.anaekran.hareket

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.arayuz.uf
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi

/** Choreographer behind an interface so tests can count pending callbacks. */
interface KareKaynagi {
    fun iste(cb: Choreographer.FrameCallback)
    fun birak(cb: Choreographer.FrameCallback)

    companion object {
        /** The main thread's Choreographer (call on the main thread). */
        val CHOREOGRAPHER = object : KareKaynagi {
            override fun iste(cb: Choreographer.FrameCallback) = Choreographer.getInstance().postFrameCallback(cb)
            override fun birak(cb: Choreographer.FrameCallback) = Choreographer.getInstance().removeFrameCallback(cb)
        }
    }
}

/**
 * Frame-rate counter for the 96×20 design-px slot (Geist Mono 11 px, palette `mu`).
 *
 * What the number means: frames the APP DREW per second while something moved (see
 * [KareHiziHesabi]), plus the longest main-thread frame in ms — "60 fps 17 ms". It is not the
 * VSYNC rate: Choreographer calls back at ≈60 Hz even when nothing is drawn, so its own rate
 * would always read 60.
 *
 * When nothing is drawn (a still screen) the last measurement stays, dimmed: "this is how
 * the last motion ran". A still screen reading "0" would look broken, and reading "60" would
 * be false. So the in-car check "ekrandaki sayaçta kare hızı kaç?" is answered by watching a
 * motion (intro, park↔drive) and reading the number during it or right after. "— fps" means no
 * motion was measured yet.
 *
 * Cost: an OnDrawListener (one call per drawn frame) and a Choreographer callback per VSYNC
 * only while frames are being drawn; after one still window (500 ms) the callback loop stops
 * and the next drawn frame restarts it. setText only when the shown value changes (≤ 2 per
 * second); in the fixed-size slot a single line keeps its height, so setText invalidates
 * without requestLayout.
 */
@SuppressLint("ViewConstructor")
class KareHiziSayaci(
    context: Context,
    private val kaynak: KareKaynagi = KareKaynagi.CHOREOGRAPHER,
    private val saat: () -> Long = System::nanoTime,
) : TextView(context) {

    private val hesap = KareHiziHesabi()
    private var calisiyor = false
    private var donguVar = false
    private var gozlemci: ViewTreeObserver? = null
    private var son: KareHiziHesabi.Sonuc.Olcum? = null
    private var soluk = false
    private var renk = Palet.GECE.mu

    /** Last value shown (tests). */
    val gosterilen: KareHiziHesabi.Sonuc.Olcum? get() = son

    /** True while a Choreographer callback is pending (tests). */
    val donguBekliyor: Boolean get() = donguVar

    private val geriCagri = Choreographer.FrameCallback { t ->
        donguVar = false
        kare(t)
    }

    private val cizimDinleyici = ViewTreeObserver.OnDrawListener {
        if (!calisiyor) return@OnDrawListener
        hesap.cizildi(saat())
        if (!donguVar) dongu()
    }

    init {
        typeface = Yazi.mono
        setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(11))
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setSingleLine()
        includeFontPadding = false
        setPadding(0, 0, u(8), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        text = context.getString(R.string.kare_hizi_yok)
        renkUygula()
    }

    /** Start measuring (onResume). */
    fun basla() {
        if (calisiyor) return
        calisiyor = true
        hesap.sifirla()
        dinleyiciEkle()
        dongu()
    }

    /** Stop (onPause): no Choreographer callback and no draw listener remain. */
    fun dur() {
        calisiyor = false
        if (donguVar) kaynak.birak(geriCagri)
        donguVar = false
        dinleyiciKaldir()
    }

    fun temaUygula(p: Palet) {
        renk = p.mu
        renkUygula()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A listener added before attach lives on a floating observer that attach merges
        // into the window's one (and kills); follow it so dur() removes it from the right place.
        if (gozlemci?.isAlive == false) gozlemci = viewTreeObserver
        if (calisiyor) dinleyiciEkle()
    }

    override fun onDetachedFromWindow() {
        dinleyiciKaldir()
        super.onDetachedFromWindow()
    }

    private fun dongu() {
        if (!calisiyor || donguVar) return
        donguVar = true
        kaynak.iste(geriCagri)
    }

    private fun kare(t: Long) {
        if (!calisiyor) return
        when (val s = hesap.vsync(t)) {
            null -> dongu()
            is KareHiziHesabi.Sonuc.Olcum -> {
                goster(s)
                dongu()
            }
            KareHiziHesabi.Sonuc.Bos -> {
                // Still screen: keep the last value dimmed and stop waking up every VSYNC.
                bosta()
                hesap.pencereyiKapat()
            }
        }
    }

    private fun goster(o: KareHiziHesabi.Sonuc.Olcum) {
        if (o == son && !soluk) return
        if (o != son) {
            son = o
            text = context.getString(R.string.kare_hizi, o.fps.coerceAtMost(99), o.enUzunMs.coerceAtMost(999))
        }
        if (soluk) {
            soluk = false
            renkUygula()
        }
    }

    private fun bosta() {
        if (soluk || son == null) return
        soluk = true
        renkUygula()
    }

    private fun renkUygula() {
        setTextColor(if (soluk) Color.argb(Math.round(Color.alpha(renk) * SOLUK), Color.red(renk), Color.green(renk), Color.blue(renk)) else renk)
    }

    private fun dinleyiciEkle() {
        if (gozlemci != null) return
        val g = viewTreeObserver
        g.addOnDrawListener(cizimDinleyici)
        gozlemci = g
    }

    private fun dinleyiciKaldir() {
        gozlemci?.let { if (it.isAlive) it.removeOnDrawListener(cizimDinleyici) }
        gozlemci = null
    }

    companion object {
        /** Opacity of a held (still-screen) value. */
        const val SOLUK = 0.45f
    }
}
