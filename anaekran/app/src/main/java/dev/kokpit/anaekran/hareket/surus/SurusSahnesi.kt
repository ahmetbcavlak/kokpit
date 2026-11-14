package dev.kokpit.anaekran.hareket.surus

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.kokpit.anaekran.arayuz.Olcek
import dev.kokpit.anaekran.hareket.HareketIzci
import dev.kokpit.anaekran.hareket.KareKaynagi
import kotlin.math.floor

/**
 * Drive scene "the car is going" (K-94, K-95; design pack 0.2 §2): wheels turning under the
 * body, lane lines and distant lights flowing, a lamp reflection sweeping the body, speed lines,
 * suspension and a camera that pulls back with speed. The HUD is NOT here (it stays in the
 * screen layer, fixed); the background picture neither (the scene layer under it: boya_gece).
 * Full screen, transparent, takes no touches and is invisible to accessibility.
 *
 * Views, bottom to top (demo z order), all in design px inside a 768×1024 container scaled by
 * Olcek.oran (1 on the head unit):
 *  1. [ArkaKatman] — city lights + far lane line (onDraw, invalidated per frame);
 *  2. [IzKatmani] — speed lines (static drawing, view alpha only);
 *  3. `dunya` — camera scale (pivot 466, 611) and suspension (translationY) as view properties:
 *     front and rear wheel, three ImageView sprites each (rotation per frame, alpha by blur),
 *     the body ImageView (1:1 bitmap, integer position), the lamp reflection [YansimaKatmani];
 *  4. [OnKatman] — near lane line.
 * Containers never get an alpha (research decision 2); the lower sprite of a pair stays at
 * alpha 1 and only the upper one fades (decision 3).
 *
 * One Choreographer loop (research decision 8), only while [basla]ed AND visible on screen
 * (onVisibilityAggregated: a GONE/INVISIBLE screen or ancestor stops it by itself):
 *  - time = `frameTimeNanos` only; dt = (t − previous) clamped to [0; [DT_EN_COK]] s, 0 on the
 *    first frame after a (re)start — so nothing jumps after a stall or a pause;
 *  - speed = [hizSaglayici](t) (m/s, ≥ 0) × the start ramp of [durgundanBasla];
 *  - [SurusModeli] advances angle and distance by dt × speed (trapezoid), so 30 or 60 fps show
 *    the same motion;
 *  - when the speed is 0, [durgunSaglayici](t) says the smoother is at rest and the camera has
 *    settled, the loop stops by itself (a still scene costs no frames). It restarts on
 *    [uyandir], and by itself: while stopped, [durgunSaglayici] is polled every [YOKLAMA_MS];
 *  - when the frames get slow, [KareTemposu] halves the rate to an even 30 fps.
 * [izci] (the frame-rate counter) hears "motion started" when the loop runs and "ended" when it
 * stops. Main thread only.
 *
 * Pixel snapping (research decision 7): the suspension's ±0.9 px is a translation on the same
 * view as the camera scale. Whenever it is non-zero the speed is > 0, so the scale is < 1 and
 * HWUI never sees a pure translation (it would round it to whole pixels and switch to nearest
 * sampling). At rest the scale snaps to exactly 1 and the offset to 0: the body is drawn 1:1.
 */
@SuppressLint("ViewConstructor")
class SurusSahnesi(
    context: Context,
    private val kareKaynagi: KareKaynagi = KareKaynagi.CHOREOGRAPHER,
    /** Clock for the idle poll (System.nanoTime timebase, like frameTimeNanos). */
    private val saat: () -> Long = System::nanoTime,
) : ViewGroup(context) {

    /**
     * Smoothed speed in m/s for a frame time (normally `Hiz.yumusatici::deger`). Null = 0.
     * Called exactly ONCE per drawn frame (HizYumusatici.deger advances its spring on every
     * call). Other readers on the same frame (the speed digits, the arc) must not call it again:
     * they take the value through [kareDinleyici] / [sonHizMs].
     */
    var hizSaglayici: HizSaglayici? = null

    /**
     * Told after every drawn frame with its frame time and the speed read from [hizSaglayici]
     * for it (m/s, ≥ 0, before the [durgundanBasla] ramp — the real speed, for the HUD digits).
     * Not called while the loop is stopped (still scene: the last value holds) or on frames
     * skipped at half rate.
     */
    var kareDinleyici: KareDinleyici? = null

    /** The speed read on the last drawn frame, m/s (see [kareDinleyici]). */
    var sonHizMs = 0f
        private set

    /**
     * True when the speed source is at rest and will not change by itself (normally
     * `Hiz.yumusatici::durgun`). Null: "at rest" whenever the speed is 0.
     */
    var durgunSaglayici: DurgunSaglayici? = null

    /** Told when the scene starts / stops moving (frame-rate counter). */
    var izci: HareketIzci? = null
        set(value) {
            if (field === value) return
            if (izAcik) field?.hareketBitti()
            field = value
            if (izAcik) value?.hareketBasladi()
        }

    /** The state (tests, diagnostics). */
    val model = SurusModeli()

    /** 60/30 fps decision (diagnostics: [KareTemposu.yariHiz]). */
    val tempo = KareTemposu()

    /**
     * Bitmaps are in place. Until then the scene draws no car (only lines and lights): keep it
     * INVISIBLE until [hazirOlunca] fires (decoding takes ≈ 50–100 ms after [varliklariYukle]).
     */
    var hazir = false
        private set

    /** Called once when the bitmaps are in place; at once if they already are. */
    var hazirOlunca: (() -> Unit)? = null
        set(value) {
            field = value
            if (hazir && value != null) {
                field = null
                value()
            }
        }

    /** A frame callback is pending (tests). */
    val donguBekliyor: Boolean get() = bekliyor

    /** The loop is allowed to run: started and visible. */
    val calisiyor: Boolean get() = basladi && gorunur

    private val ic = SurusKabi(context)
    private val dunya = SurusKabi(context)
    private val arka = ArkaKatman(context)
    private val iz = IzKatmani(context)
    private val yansima = YansimaKatmani(context)
    private val on = OnKatman(context)
    private val govde = resim()
    private val tekerOn = Array(3) { resim() }
    private val tekerArka = Array(3) { resim() }

    private val ana = Handler(Looper.getMainLooper())
    private var basladi = false
    private var gorunur = false
    private var bekliyor = false
    private var izAcik = false
    private var sonNs = YOK
    private var birakildi = false
    private var yukleniyor = false

    /** Start ramp: speed multiplier 0 → 1 (smoothstep) over [rampaNs], from the first frame after [durgundanBasla]. */
    private var rampaNs = 0L
    private var rampaBasNs = YOK

    /** Current ramp value 0…1 (1 = no ramp): multiplies the speed and the lines' / lights' alpha. */
    private var rampaDegeri = 1f

    private val geriCagri = Choreographer.FrameCallback { t ->
        bekliyor = false
        kare(t)
    }

    private val yoklama = object : Runnable {
        override fun run() {
            if (!calisiyor || bekliyor) return
            if (durgunSaglayici?.durgun(saat()) == false) iste() else ana.postDelayed(this, YOKLAMA_MS)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        clipChildren = true
        val w = SurusYerlesim.TASARIM_W
        addView(ic, LayoutParams(w, SurusYerlesim.TASARIM_H))
        ic.pivotX = 0f
        ic.pivotY = 0f

        ic.ekle(arka, 0, ArkaKatman.Y, w, ArkaKatman.H)
        ic.ekle(iz, SurusYerlesim.IZ_X, SurusYerlesim.IZ_Y, SurusYerlesim.IZ_W, SurusYerlesim.IZ_H)
        ic.ekle(dunya, 0, 0, w, SurusYerlesim.TASARIM_H)
        ic.ekle(on, 0, OnKatman.Y, w, OnKatman.H)
        dunya.pivotX = SurusYerlesim.KAMERA_PX
        dunya.pivotY = SurusYerlesim.KAMERA_PY

        for (v in tekerOn) tekerEkle(v, SurusYerlesim.ON_X)
        for (v in tekerArka) tekerEkle(v, SurusYerlesim.ARKA_X)
        dunya.ekle(govde, SurusYerlesim.GOVDE_X, SurusYerlesim.GOVDE_Y, SurusYerlesim.GOVDE_W, SurusYerlesim.GOVDE_H)
        dunya.ekle(yansima, SurusYerlesim.GOVDE_X, SurusYerlesim.GOVDE_Y, SurusYerlesim.GOVDE_W, SurusYerlesim.GOVDE_H)
        uygula()
    }

    private fun resim() = ImageView(context).apply {
        // 1:1 bitmaps: CENTER keeps them unscaled; BitmapDrawable filters (FILTER_BITMAP_FLAG) when rotated.
        scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }

    private fun tekerEkle(v: ImageView, merkezX: Float) {
        val sol = merkezX - SurusYerlesim.SPRITE_YARIM
        val ust = SurusYerlesim.TEKER_Y - SurusYerlesim.SPRITE_YARIM
        val x = floor(sol).toInt()
        val y = floor(ust).toInt()
        dunya.ekle(v, x, y, SurusYerlesim.SPRITE, SurusYerlesim.SPRITE)
        v.translationX = sol - x
        v.translationY = ust - y
        v.pivotX = SurusYerlesim.SPRITE_YARIM
        v.pivotY = SurusYerlesim.SPRITE_YARIM
    }

    /** Puts the decoded bitmaps in; the scene shows the car from now on. */
    fun varliklariKur(v: SurusVarliklari) {
        if (birakildi) return
        govde.setImageBitmap(v.govde)
        govde.alpha = 1f
        for (i in 0..2) {
            tekerOn[i].setImageBitmap(v.tekerOn[i])
            tekerArka[i].setImageBitmap(v.tekerArka[i])
        }
        arka.isik = v.isik
        yansima.govdeAyarla(v.govde)
        hazir = true
        uygula()
        arka.invalidate()
        hazirOlunca?.let {
            hazirOlunca = null
            it()
        }
    }

    /** Decodes the bitmaps on the worker thread and puts them in (idempotent). */
    fun varliklariYukle() {
        if (hazir || yukleniyor) return
        yukleniyor = true
        SurusVarliklari.yukle(resources) { v ->
            yukleniyor = false
            if (v != null) varliklariKur(v) else Log.w(SurusVarliklari.ETIKET, "sürüş varlıkları çözülemedi")
        }
    }

    // --- Life cycle ---

    /** onResume: the loop may run (when also visible). */
    fun basla() {
        if (birakildi || basladi) return
        basladi = true
        durumDegisti()
    }

    /** onPause: no frame callback, no poll. State (positions) is kept. */
    fun dur() {
        if (!basladi) return
        basladi = false
        durumDegisti()
    }

    /** A new speed may have arrived (e.g. every AracVerisi update): restart a stopped loop. */
    fun uyandir() {
        if (calisiyor && !bekliyor) iste()
    }

    /**
     * The scene appears over a still picture of the car (the end of the park → drive clip):
     * start from the rest look (camera 1, no suspension) and bring the speed in over [sureMs]
     * (smoothstep), so the first frame equals the clip's last one and the car then picks up the
     * real speed. First frame: wheel angle 0 and distance 0 (the sprites' and the lamp band's
     * rest pose), camera 1, and the lines, lights (and speed lines, speed 0) at alpha 0 — none of
     * them is in the clip; they fade in with the same ramp.
     */
    fun durgundanBasla(sureMs: Long = RAMPA_MS) {
        model.dinlenmeyeAl()
        rampaNs = sureMs * 1_000_000L
        rampaBasNs = BEKLIYOR
        rampaDegeri = 0f
        uygula()
        uyandir()
    }

    /** onDestroy: stops for good, drops the bitmaps' views. */
    fun birak() {
        dur()
        birakildi = true
        ana.removeCallbacks(yoklama)
        govde.setImageDrawable(null)
        for (v in tekerOn + tekerArka) v.setImageDrawable(null)
        arka.isik = null
        yansima.govdeBirak()
        hazirOlunca = null
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (gorunur == isVisible) return
        gorunur = isVisible
        durumDegisti()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val hz = display?.refreshRate ?: 0f
        if (hz > 1f) {
            tempo.vsyncNs = (1e9 / hz).toLong()
            Log.i(SurusVarliklari.ETIKET, "ekran yenileme ${"%.1f".format(java.util.Locale.ROOT, hz)} Hz")
        }
    }

    override fun onDetachedFromWindow() {
        if (gorunur) {
            gorunur = false
            durumDegisti()
        }
        super.onDetachedFromWindow()
    }

    private fun durumDegisti() {
        if (calisiyor) {
            sonNs = YOK
            tempo.sifirla()
            iste()
        } else {
            if (bekliyor) kareKaynagi.birak(geriCagri)
            bekliyor = false
            ana.removeCallbacks(yoklama)
            izKapa()
        }
    }

    private fun iste() {
        if (!calisiyor || bekliyor) return
        ana.removeCallbacks(yoklama)
        bekliyor = true
        kareKaynagi.iste(geriCagri)
    }

    // --- Frame ---

    private fun kare(t: Long) {
        if (!calisiyor) return
        if (!tempo.ciz(t)) {
            iste()
            return
        }
        val dt = if (sonNs == YOK) 0f else ((t - sonNs) / 1e9f).coerceIn(0f, DT_EN_COK)
        sonNs = t
        val s = hizSaglayici
        val okunan = if (s == null) 0f else s.hiz(t)
        val ham = if (okunan.isNaN()) 0f else okunan.coerceAtLeast(0f)
        sonHizMs = ham
        rampaDegeri = rampa(t)
        model.ilerle(dt, ham * rampaDegeri)
        uygula()
        kareDinleyici?.kare(t, ham)
        val d = durgunSaglayici
        val durgun = model.durgun && rampaBasNs == YOK && (d == null || d.durgun(t))
        if (durgun) {
            // Still: stop waking up every VSYNC; the poll / uyandir() restart it. The gap until
            // then must not count as a slow frame.
            izKapa()
            sonNs = YOK
            tempo.sifirla()
            ana.removeCallbacks(yoklama)
            if (durgunSaglayici != null) ana.postDelayed(yoklama, YOKLAMA_MS)
        } else {
            izAc()
            iste()
        }
    }

    private fun rampa(t: Long): Float {
        if (rampaBasNs == YOK) return 1f
        if (rampaBasNs == BEKLIYOR) rampaBasNs = t
        val x = if (rampaNs <= 0L) 1f else ((t - rampaBasNs).toFloat() / rampaNs).coerceIn(0f, 1f)
        if (x >= 1f) rampaBasNs = YOK
        return x * x * (3f - 2f * x)
    }

    /** Copies the model to the views (property setters skip unchanged values). */
    private fun uygula() {
        val m = model
        val o = Olcek.oran
        if (ic.scaleX != o) {
            ic.scaleX = o
            ic.scaleY = o
        }
        dunya.scaleX = m.kamera
        dunya.scaleY = m.kamera
        dunya.translationY = m.suspansiyon

        val r = (-m.tekerAcisi).toFloat() // counter-clockwise
        val b = m.bulaniklik
        tekerUygula(tekerOn, b, r)
        tekerUygula(tekerArka, b, r)

        // Lines and lights: re-record only when what they draw changed.
        val io = m.isikOfset
        val iop = m.isikOpakligi
        val uo = m.uzakOfset
        if (io != arka.isikOfset || iop != arka.isikOpakligi || uo != arka.uzakOfset) {
            arka.isikOfset = io
            arka.isikOpakligi = iop
            arka.uzakOfset = uo
            arka.invalidate()
        }
        val yo = m.yakinOfset
        if (yo != on.ofset) {
            on.ofset = yo
            on.invalidate()
        }
        val g = rampaDegeri
        arka.alpha = g
        on.alpha = g
        iz.alpha = m.izOpakligi * g

        val onceki = yansima.ciziyor
        val lk = m.lambaKonumu
        val lg = m.lambaGorunurlugu
        val degisti = lk.toBits() != yansima.konum.toBits() || lg != yansima.gorunurluk
        yansima.konum = lk
        yansima.gorunurluk = lg
        // Re-record only while a band is (or was, to clear it) on the body, and it moved.
        if (degisti && (yansima.ciziyor || onceki)) yansima.invalidate()
    }

    private fun tekerUygula(v: Array<ImageView>, b: Float, r: Float) {
        val a0: Float
        val a1: Float
        val a2: Float
        when {
            b <= 0f -> { a0 = 1f; a1 = 0f; a2 = 0f }
            b < 1f -> { a0 = 1f; a1 = b; a2 = 0f }
            b < 2f -> { a0 = 0f; a1 = 1f; a2 = b - 1f }
            else -> { a0 = 0f; a1 = 0f; a2 = 1f }
        }
        if (!hazir) {
            for (x in v) x.alpha = 0f
            return
        }
        sprite(v[0], a0, r)
        sprite(v[1], a1, r)
        sprite(v[2], a2, r)
    }

    private fun sprite(v: ImageView, a: Float, r: Float) {
        if (a > 0f) v.rotation = r
        v.alpha = a
    }

    private fun izAc() {
        if (izAcik) return
        izAcik = true
        izci?.hareketBasladi()
    }

    private fun izKapa() {
        if (!izAcik) return
        izAcik = false
        izci?.hareketBitti()
    }

    // --- View ---

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        ic.measure(
            MeasureSpec.makeMeasureSpec(SurusYerlesim.TASARIM_W, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(SurusYerlesim.TASARIM_H, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(
            View.getDefaultSize(Math.round(SurusYerlesim.TASARIM_W * Olcek.oran), widthMeasureSpec),
            View.getDefaultSize(Math.round(SurusYerlesim.TASARIM_H * Olcek.oran), heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        ic.layout(0, 0, SurusYerlesim.TASARIM_W, SurusYerlesim.TASARIM_H)
    }

    /** No touches: they go to whatever lies under the scene. */
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false

    override fun hasOverlappingRendering() = false
    override fun shouldDelayChildPressedState() = false

    /** Test / diagnostics view access. */
    internal val dunyaGorunumu: View get() = dunya
    internal val tekerOnGorunumleri: Array<ImageView> get() = tekerOn
    internal val tekerArkaGorunumleri: Array<ImageView> get() = tekerArka
    internal val yansimaGorunumu: YansimaKatmani get() = yansima
    internal val izGorunumu: View get() = iz
    internal val arkaGorunumu: View get() = arka
    internal val onGorunumu: View get() = on

    companion object {
        /** Longest frame step, s (research decision 8). */
        const val DT_EN_COK = 0.05f

        /** Idle poll of [durgunSaglayici] while the loop is stopped. */
        const val YOKLAMA_MS = 250L

        /** [durgundanBasla] default ramp. */
        const val RAMPA_MS = 1000L

        private const val YOK = Long.MIN_VALUE
        private const val BEKLIYOR = Long.MIN_VALUE + 1
    }
}
