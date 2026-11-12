package dev.kokpit.anaekran.hareket

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import dev.kokpit.anaekran.tema.Palet

/**
 * Plays a clip (or nothing) in the video layer and cross-fades to a target picture.
 *
 * Views it owns, all inside [kap] (the video layer), bottom to top:
 *  1. the [Oynatici]'s TextureView, only while a clip plays;
 *  2. `ortu` — the cover: the "old" picture, the clip's poster (its first frame) until the
 *     first decoded frame is on screen, or the window colour for the intro;
 *  3. `ust` — the target picture, faded 0 → 1 over everything below.
 * The video itself never gets alpha (research decision 3). When a run ends every one of these
 * is GONE or removed: no full-screen layer stays behind (overdraw), and the player is released.
 *
 * Timeline of one [oynat]:
 *  - the clip is prepared at once (prepareAsync), the video view is added as soon as the
 *    poster is decoded; the poster is hidden on the first latched frame after RENDERING_START;
 *  - the fade starts when BOTH `tutMs` (since the call) have passed AND the clip has ended —
 *    a slow decoder therefore never gets cut before the car has arrived;
 *  - on completion the TextureView keeps the last frame (no image swap, so no sharpness pop:
 *    the scenes differ from the last frames only by codec softness, PSNR ≈ 34–35 dB);
 *  - [kes] (tap) fades from the current alpha in [KESME_MS];
 *  - player error, or no first frame within [ILK_KARE_SINIR_MS]: the clip is skipped and the
 *    fade starts at once over the poster — the screen is never left black;
 *  - at the end: player released, video view removed, then `bitti` exactly once, then the
 *    target overlay is dropped (same main-thread turn, so the caller must make the scene
 *    layer show the target inside `bitti`).
 *
 * A second [oynat] while one runs ends the first at once (target shown, `bitti(KESILDI)`),
 * and the new run starts from that picture. Main thread only.
 */
class KlipOynatici(
    private val kap: FrameLayout,
    /** Where the optional tap shield goes (the root layer, above everything). */
    private val kalkanKap: FrameLayout? = null,
    private val oynaticiUret: (Context) -> Oynatici = { DokuOynatici(it) },
    private val yukleyici: GorselYukleyici = GorselYukleyici.VARSAYILAN,
) {
    enum class Sonuc {
        /** Clip (if any) played, held, faded. */
        TAMAM,
        /** [kes] before the normal fade. */
        DOKUNULDU,
        /** Player error, decoder too slow or poster missing: faded without the clip. */
        ATLANDI,
        /** A newer [oynat] (or [bitir]) ended this one; the target is on screen. */
        KESILDI,
    }

    /** First-frame timing of the last clip, for logs / a debug line. */
    data class IlkKare(val klip: Klip, val ilkKareMs: Long)

    var sonOlcum: IlkKare? = null
        private set

    val calisiyor: Boolean get() = aktif != null

    private val ctx = kap.context
    private val res = kap.resources
    private val ana = Handler(Looper.getMainLooper())
    private var aktif: Istek? = null
    private var ortu: ImageView? = null
    private var ust: ImageView? = null
    private var kalkan: View? = null

    private inner class Istek(val klip: Klip?, val gecisMs: Long, val bitti: (Sonuc) -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        var hedef: Drawable? = null
        var hedefYok = false
        var oynatici: Oynatici? = null
        var posterGeldi = false
        var tutuldu = false
        var klipBitti = klip == null
        var basladi = false
        var kareGeldi = false
        var solma = false
        var animasyonda = false
        var bekleyenMs = -1L
        var sonuc = Sonuc.TAMAM
        var bittiCagrildi = false

        val tutBitti = Runnable { tutuldu = true; ilerle(this) }
        val zamanAsimi = Runnable { atla(this, "ilk kare ${ILK_KARE_SINIR_MS} ms içinde gelmedi") }
        val guvenlik = Runnable { atla(this, "klip bitmedi") }
        val hedefBekleme = Runnable { bitir(this) }
    }

    /**
     * Starts a run; see the class comment for the timeline.
     *
     * @param klip clip to play, or null for a plain cross-fade [eski] → [hedef].
     * @param eski picture on screen now. Shown at once as the cover, so the caller may switch
     *   the scene layer right after this call. Null: nothing is covered until the poster
     *   arrives (for a plain fade: the scene layer below must keep the old picture until `bitti`).
     * @param hedef picture to end on (the caller's choice: night/day, which scene).
     * @param tutMs minimum time from this call before the fade (intro: 1600).
     * @param gecisMs fade length.
     * @param dokunmaKalkani adds a transparent tap catcher above everything; a tap (DOWN+UP
     *   without movement, no edge gesture) calls [kes]. The caller may route taps to [kes] instead.
     * @param bitti called exactly once per call (unless [birak] comes first), on the main thread.
     */
    fun oynat(
        klip: Klip?,
        eski: Goruntu?,
        hedef: Goruntu,
        tutMs: Long,
        gecisMs: Long,
        dokunmaKalkani: Boolean = false,
        bitti: (Sonuc) -> Unit,
    ) {
        aktif?.let { kesildi(it) }
        val i = Istek(klip, gecisMs, bitti)
        aktif = i
        val o = ortuGorunumu()
        val u = ustGorunumu()

        // Keep what is on screen: a finished/interrupted target becomes the cover.
        u.animate().cancel()
        val onceki = if (u.visibility == View.VISIBLE && u.alpha > 0f) u.drawable else null
        u.setImageDrawable(null)
        u.alpha = 0f
        u.visibility = View.GONE
        if (onceki != null) ortuGoster(onceki)

        if (eski != null) {
            yukleyici.yukle(res, eski) { d -> if (aktif === i && d != null && !i.posterGeldi) ortuGoster(d) }
        } else if (klip?.acilis == true && o.visibility != View.VISIBLE) {
            ortuGoster(ColorDrawable(ACILIS_RENGI))
        }

        yukleyici.yukle(res, hedef) { d ->
            if (aktif !== i) return@yukle
            if (d == null) i.hedefYok = true else i.hedef = d
            if (i.solma && !i.animasyonda) {
                if (d == null) bitir(i) else solmayiCalistir(i, d, i.bekleyenMs)
            }
        }

        if (klip != null) {
            val oy = oynaticiUret(ctx)
            i.oynatici = oy
            oy.hazirla(klip.ham, dinleyici(i))
            yukleyici.yukle(res, klip.poster) { d ->
                if (aktif !== i || i.solma) return@yukle
                if (d == null) {
                    atla(i, "poster çözülemedi")
                    return@yukle
                }
                i.posterGeldi = true
                ortuGoster(d)
                videoyuEkle(i)
            }
            ana.postDelayed(i.zamanAsimi, ILK_KARE_SINIR_MS)
        }

        if (dokunmaKalkani) kalkanEkle()
        if (tutMs <= 0L) i.tutuldu = true else ana.postDelayed(i.tutBitti, tutMs)
        ilerle(i)
    }

    /** Tap: cut the clip and fade fast from the current alpha. No-op when idle. */
    fun kes() {
        val i = aktif ?: return
        if (!i.solma) {
            i.sonuc = Sonuc.DOKUNULDU
            i.tutuldu = true
            i.klipBitti = true
            solmaBaslat(i, KESME_MS)
            return
        }
        val d = i.hedef
        if (i.animasyonda && d != null) {
            val kalan = ((1f - ustGorunumu().alpha) * i.gecisMs).toLong()
            if (kalan > KESME_MS) solmayiCalistir(i, d, KESME_MS)
        } else if (i.bekleyenMs > KESME_MS) {
            i.bekleyenMs = KESME_MS
        }
    }

    /** Ends the running clip at once on its target (e.g. the activity leaves); `bitti(KESILDI)`. */
    fun bitir() {
        aktif?.let { kesildi(it) }
        if (aktif == null) ustuKaldir()
    }

    /** onDestroy: everything stops, views go, no callbacks. */
    fun birak() {
        aktif?.let { temizle(it) }
        aktif = null
        ortu?.let { kap.removeView(it) }
        ust?.let { it.animate().cancel(); kap.removeView(it) }
        ortu = null
        ust = null
    }

    // --- Player events ---

    private fun dinleyici(i: Istek) = object : Oynatici.Dinleyici {
        override fun basladi() {
            if (aktif !== i) return
            i.basladi = true
            ana.removeCallbacks(i.zamanAsimi)
            val ms = SystemClock.uptimeMillis() - i.t0
            i.klip?.let { sonOlcum = IlkKare(it, ms) }
            Log.i(ETIKET, "${i.klip}: ilk kare oynat'tan $ms ms")
            posterKaldir(i)
        }

        override fun kareGeldi() {
            if (aktif !== i) return
            i.kareGeldi = true
            ana.removeCallbacks(i.zamanAsimi)
            posterKaldir(i)
        }

        override fun bitti() {
            if (aktif !== i) return
            i.klipBitti = true
            ana.removeCallbacks(i.guvenlik)
            ilerle(i)
        }

        override fun hata(neden: String) {
            if (aktif === i) atla(i, neden)
        }
    }

    private fun videoyuEkle(i: Istek) {
        val v = i.oynatici?.gorunum ?: return
        if (v.parent == null) kap.addView(v, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Count the first-frame limit from the moment the video can actually draw.
        ana.removeCallbacks(i.zamanAsimi)
        if (!i.basladi && !i.kareGeldi) ana.postDelayed(i.zamanAsimi, ILK_KARE_SINIR_MS)
        val klip = i.klip ?: return
        ana.postDelayed(i.guvenlik, ILK_KARE_SINIR_MS + klip.sureMs + 1000L)
    }

    /** Hide the poster only when a decoded frame is both pushed and latched (no empty texture shows). */
    private fun posterKaldir(i: Istek) {
        if (!i.basladi || !i.kareGeldi || i.solma) return
        ortu?.let {
            it.visibility = View.GONE
            it.setImageDrawable(null)
        }
    }

    private fun atla(i: Istek, neden: String) {
        if (aktif !== i || i.solma) return
        Log.w(ETIKET, "${i.klip}: klip atlandı ($neden)")
        i.sonuc = Sonuc.ATLANDI
        i.tutuldu = true
        i.klipBitti = true
        // If the cover is still up the video is not visible: free the decoder now.
        if (ortu?.visibility == View.VISIBLE) videoyuBirak(i)
        solmaBaslat(i, i.gecisMs)
    }

    // --- Fade ---

    private fun ilerle(i: Istek) {
        if (aktif !== i || i.solma) return
        if (i.tutuldu && i.klipBitti) solmaBaslat(i, i.gecisMs)
    }

    private fun solmaBaslat(i: Istek, ms: Long) {
        i.solma = true
        ana.removeCallbacks(i.tutBitti)
        ana.removeCallbacks(i.zamanAsimi)
        ana.removeCallbacks(i.guvenlik)
        val d = i.hedef
        when {
            d != null -> solmayiCalistir(i, d, ms)
            i.hedefYok -> bitir(i)
            else -> {
                // Target still decoding: wait a little, never forever.
                i.bekleyenMs = ms
                ana.postDelayed(i.hedefBekleme, HEDEF_SINIR_MS)
            }
        }
    }

    private fun solmayiCalistir(i: Istek, d: Drawable, ms: Long) {
        ana.removeCallbacks(i.hedefBekleme)
        val u = ustGorunumu()
        u.animate().cancel()
        if (u.drawable !== d) u.setImageDrawable(d)
        u.visibility = View.VISIBLE
        i.animasyonda = true
        val kalan = (1f - u.alpha).coerceIn(0f, 1f)
        // No withLayer(): an ImageView without background reports hasOverlappingRendering()
        // = false (AOSP oreo-mr1 ImageView l.288), so HWUI folds the alpha into its single
        // bitmap draw. A hardware layer would add a 3 MB texture and one extra full-screen
        // pass for the same per-frame cost.
        u.animate()
            .alpha(1f)
            .setDuration((ms * kalan).toLong())
            .setInterpolator(if (ms <= KESME_MS) HIZLI else SAKIN)
            .withEndAction { if (aktif === i) bitir(i) }
            .start()
    }

    private fun bitir(i: Istek) {
        if (i.bittiCagrildi) return
        temizle(i)
        if (i.hedef == null) ustuKaldir()
        aktif = null
        i.bittiCagrildi = true
        i.bitti(i.sonuc)
        // The caller has switched the scene layer inside bitti; drop the overlay unless a new
        // run (started from bitti) took it over as its cover.
        if (aktif == null) ustuKaldir()
    }

    /** Snaps a running request to its end picture and reports KESILDI. */
    private fun kesildi(i: Istek) {
        val u = ustGorunumu()
        u.animate().cancel()
        val d = i.hedef
        if (d != null) {
            if (u.drawable !== d) u.setImageDrawable(d)
            u.alpha = 1f
            u.visibility = View.VISIBLE
        }
        i.sonuc = Sonuc.KESILDI
        temizle(i)
        aktif = null
        if (!i.bittiCagrildi) {
            i.bittiCagrildi = true
            i.bitti(Sonuc.KESILDI)
        }
    }

    private fun temizle(i: Istek) {
        ana.removeCallbacks(i.tutBitti)
        ana.removeCallbacks(i.zamanAsimi)
        ana.removeCallbacks(i.guvenlik)
        ana.removeCallbacks(i.hedefBekleme)
        i.animasyonda = false
        videoyuBirak(i)
        kalkanKaldir()
        ortu?.let {
            it.visibility = View.GONE
            it.setImageDrawable(null)
        }
    }

    private fun videoyuBirak(i: Istek) {
        val oy = i.oynatici ?: return
        i.oynatici = null
        oy.birak()
        kap.removeView(oy.gorunum)
    }

    // --- Views ---

    private fun ortuGorunumu(): ImageView = ortu ?: resim().also {
        kap.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        ortu = it
    }

    private fun ustGorunumu(): ImageView = ust ?: resim().also {
        it.forceHasOverlappingRendering(false)
        ortuGorunumu() // keep the order: cover below, target above
        kap.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        ust = it
    }

    private fun resim() = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP // same as the scene layer: pixels line up
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private fun ortuGoster(d: Drawable) {
        val o = ortuGorunumu()
        if (o.drawable !== d) o.setImageDrawable(d)
        o.alpha = 1f
        o.visibility = View.VISIBLE
    }

    private fun ustuKaldir() {
        ust?.let {
            it.animate().cancel()
            it.visibility = View.GONE
            it.alpha = 0f
            it.setImageDrawable(null)
        }
    }

    private fun kalkanEkle() {
        val k = kalkanKap ?: return
        if (kalkan != null) return
        val v = DokunmaKalkani(ctx) { kes() }
        k.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        kalkan = v
    }

    private fun kalkanKaldir() {
        kalkan?.let { (it.parent as? FrameLayout)?.removeView(it) }
        kalkan = null
    }

    /**
     * Transparent, draws nothing. Consumes every touch while the intro plays; a tap (DOWN then
     * UP within touch slop) cuts. A swipe — including one from the screen edge — does nothing (K-66).
     */
    @SuppressLint("ViewConstructor")
    private class DokunmaKalkani(context: Context, private val dokunuldu: () -> Unit) : View(context) {
        private val esik = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var x0 = 0f
        private var y0 = 0f
        private var dokunma = false

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; dokunma = true }
                MotionEvent.ACTION_MOVE -> if (Math.hypot((e.x - x0).toDouble(), (e.y - y0).toDouble()) > esik) dokunma = false
                MotionEvent.ACTION_UP -> {
                    if (dokunma) performClick()
                    dokunma = false
                }
                MotionEvent.ACTION_CANCEL -> dokunma = false
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            dokunuldu()
            return true
        }
    }

    companion object {
        const val ETIKET = "Kokpit/Hareket"

        /** No first frame within this time after the video can draw → skip the clip. */
        const val ILK_KARE_SINIR_MS = 1500L

        /** Tap cut. */
        const val KESME_MS = 150L

        /** Target picture still not decoded when the fade should start → end without it. */
        const val HEDEF_SINIR_MS = 1000L

        /** Intro cover until the poster is decoded: the window colour (styles.xml ilk_kare). */
        val ACILIS_RENGI = Palet.GECE.bg

        /** Calm, symmetric ease-in-out (sine-like) for the 350 ms scene fade. */
        val SAKIN = PathInterpolator(0.37f, 0f, 0.63f, 1f)

        /** Tap: starts fast, lands soft (responds at once). */
        val HIZLI = PathInterpolator(0f, 0f, 0.2f, 1f)
    }
}
