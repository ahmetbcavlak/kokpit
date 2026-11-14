package dev.kokpit.anaekran.hareket.surus

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * The drive scene's state, advanced once per frame by [ilerle] with the frame's `dt` and the
 * smoothed speed. Pure Kotlin (tested on the JVM); [SurusSahnesi] copies the outputs to views.
 *
 * Everything that moves is integrated, never recomputed from absolute time:
 *  - distance driven (m) — lane lines, city lights and the lamp reflection are functions of it;
 *  - wheel angle (degrees) — integrates the shown angular speed;
 * both with the trapezoid rule over the frame (mean of the previous and the current value × dt),
 * so a frame-rate change (60 ↔ 30 fps) or a speed change gives the same positions (no jumps;
 * the error is O(dt²), far below a degree over seconds). Suspension phase advances with time.
 *
 * Wheel: below 30 km/h it turns at the real speed, from 30 km/h (a blurred sprite fully shown)
 * at the design's cap min(real / 3, 0.9 s per turn) — [SurusFormul.gosterilenAcisalHiz]. The
 * switch (on at 30, off below 28 km/h: hysteresis) is eased by a first-order lag
 * (τ [SurusFormul.TAVAN_GECIS_SN]) on the cap's share [pay], so the wheel never jolts and below
 * the switch it stays locked to the road. The share is integrated exactly (exponential, the
 * crossing moment interpolated inside the frame), so 30 and 60 fps still give the same angle.
 * The camera scale follows its target on a critically damped spring (exact closed form, stable
 * for any dt): ≈ 95 % in [SurusFormul.KAMERA_SURE_SN], no overshoot, no kink at the start.
 */
class SurusModeli {

    /** Last speed, m/s. */
    var hiz = 0f
        private set

    val kmh: Float get() = SurusFormul.kmh(hiz)

    /** Distance driven, m (unbounded; Double keeps sub-pixel precision for 10⁶ km). */
    var mesafe = 0.0
        private set

    /** Wheel angle, degrees, counter-clockwise positive, in [0; 360). */
    var tekerAcisi = 0.0
        private set

    /** Suspension phase, radians in [0; 2π). */
    var sustFaz = 0.0
        private set

    /** Camera scale (1 = rest). */
    var kamera = 1f
        private set
    private var kameraHizi = 0f

    private var oncekiHiz = 0f

    /** Cap switched on (hysteresis state). */
    var tavanda = false
        private set

    /** Share of the cap in the shown wheel speed, 0…1, eased towards [tavanda]. */
    var pay = 0f
        private set

    /** Shown wheel angular speed now, rad/s. */
    val gosterilenAcisalHiz: Float get() = SurusFormul.gercekAcisalHiz(hiz) + fark(hiz) * pay

    private fun fark(v: Float) = SurusFormul.bulanikAcisalHiz(v) - SurusFormul.gercekAcisalHiz(v)

    // --- Outputs (functions of the state) ---

    val bulaniklik: Float get() = SurusFormul.bulaniklik(kmh)
    val izOpakligi: Float get() = SurusFormul.izOpakligi(kmh)
    val isikOpakligi: Float get() = SurusFormul.isikOpakligi(kmh)
    val lambaGorunurlugu: Float get() = SurusFormul.hareketPayi(kmh)

    /** Lamp band left edge (share of the body box width), NaN when no band. */
    val lambaKonumu: Float get() = SurusFormul.lambaKonumu(mesafe)

    /** Suspension offset, px (≤ 0). */
    val suspansiyon: Float get() = SurusFormul.suspansiyon(SurusFormul.suspansiyonGenligi(kmh), sustFaz)

    /** Near / far lane line offset to the right, px, in [0; period). */
    val yakinOfset: Float get() = ofset(SurusFormul.PX_M.toDouble(), SurusFormul.seritPeriyodu(1f))
    val uzakOfset: Float get() = ofset(SurusFormul.PX_M.toDouble() * SurusFormul.UZAK_OLCEK, SurusFormul.seritPeriyodu(SurusFormul.UZAK_OLCEK))
    val isikOfset: Float get() = ofset(SurusFormul.PX_M.toDouble() * SurusFormul.ISIK_ORANI, SurusYerlesim.ISIK_DESEN.toFloat())

    private fun ofset(pxM: Double, periyot: Float): Float = (mesafe * pxM).mod(periyot.toDouble()).toFloat()

    /**
     * Nothing moves and nothing will by itself: speed 0 and the camera back at 1. The frame
     * loop may stop (the scene is still).
     */
    val durgun: Boolean get() = hiz <= 0f && kamera == 1f && kameraHizi == 0f

    /**
     * One frame: [dt] seconds since the previous frame (0 on the first frame after a start: only
     * the outputs are refreshed), [v] the smoothed speed in m/s (negative counts as 0).
     */
    fun ilerle(dt: Float, v: Float) {
        val yeni = if (v.isNaN()) 0f else v.coerceAtLeast(0f)
        val k1 = SurusFormul.kmh(yeni)
        if (dt > 0f) {
            mesafe += 0.5 * (oncekiHiz + yeni) * dt
            val k0 = SurusFormul.kmh(oncekiHiz)
            // Cap switch with hysteresis; f = share of the frame before the crossing.
            var f = 1f
            val eskiHedef = if (tavanda) 1f else 0f
            if (!tavanda && k1 >= SurusFormul.TAVAN_GIRIS_KMH) {
                f = kesisim(k0, k1, SurusFormul.TAVAN_GIRIS_KMH)
                tavanda = true
            } else if (tavanda && k1 < SurusFormul.TAVAN_CIKIS_KMH) {
                f = kesisim(k0, k1, SurusFormul.TAVAN_CIKIS_KMH)
                tavanda = false
            }
            val yeniHedef = if (tavanda) 1f else 0f
            val payIntegrali = payIlerle(f * dt, eskiHedef) + payIlerle((1f - f) * dt, yeniHedef)
            val gercek = 0.5 * (SurusFormul.gercekAcisalHiz(oncekiHiz) + SurusFormul.gercekAcisalHiz(yeni)) * dt
            val tavanPayi = 0.5 * (fark(oncekiHiz) + fark(yeni)) * payIntegrali
            tekerAcisi = (tekerAcisi + (gercek + tavanPayi) * 180.0 / PI).mod(360.0)
            sustFaz = (sustFaz + 2.0 * PI * dt / (2.0 * SurusFormul.SUSPANSIYON_YARIM_SN)).mod(2.0 * PI)
            kameraIlerle(dt, SurusFormul.kameraHedefi(k1))
        } else {
            // (Re)start: no history to ease from — the steady state for the current speed.
            if (k1 >= SurusFormul.TAVAN_GIRIS_KMH) tavanda = true else if (k1 < SurusFormul.TAVAN_CIKIS_KMH) tavanda = false
            pay = if (tavanda) 1f else 0f
        }
        hiz = yeni
        oncekiHiz = yeni
    }

    /** Fraction of the way from [a] to [b] where [esik] is crossed. */
    private fun kesisim(a: Float, b: Float, esik: Float): Float =
        if (b == a) 0f else ((esik - a) / (b - a)).coerceIn(0f, 1f)

    /** Advances [pay] for [h] s towards [hedef] (exact first-order lag); returns the integral of pay over it. */
    private fun payIlerle(h: Float, hedef: Float): Double {
        if (h <= 0f) return 0.0
        val tau = SurusFormul.TAVAN_GECIS_SN.toDouble()
        val e = exp(-h / tau)
        val p0 = pay.toDouble()
        val integral = hedef * h + (p0 - hedef) * tau * (1.0 - e)
        var p1 = hedef + (p0 - hedef) * e
        if (abs(p1 - hedef) < 1e-4) p1 = hedef.toDouble()
        pay = p1.toFloat()
        return integral
    }

    /**
     * Back to the clip's still last frame (the drive scene appears over it): camera 1, suspension
     * 0, wheel angle 0 (the sprites' own pose = the clip's wheels), distance 0 (lane lines at 0,
     * lamp band outside the body), speed 0, no cap. The scene then ramps the speed in.
     */
    fun dinlenmeyeAl() {
        kamera = 1f
        kameraHizi = 0f
        sustFaz = 0.0
        hiz = 0f
        oncekiHiz = 0f
        tekerAcisi = 0.0
        mesafe = 0.0
        tavanda = false
        pay = 0f
    }

    private fun kameraIlerle(dt: Float, hedef: Float) {
        val w = KAMERA_OMEGA
        val a = kamera - hedef
        val b = kameraHizi + w * a
        val e = exp(-w * dt)
        var x = hedef + (a + b * dt) * e
        var hz = (kameraHizi - w * b * dt) * e
        // Settled: snap, so the scene at rest is exactly 1:1 (crisp, pixel-aligned body).
        if (abs(x - hedef) < KAMERA_OTURMA && abs(hz) < KAMERA_OTURMA_HIZ) {
            x = hedef
            hz = 0f
        }
        kamera = x
        kameraHizi = hz
    }

    companion object {
        /**
         * Camera spring: critically damped, ω = 3 /s → (1 + ωt)·e^(−ωt) = 4.8 % left at 1.6 s
         * (the demo's 1.6 s ease).
         */
        const val KAMERA_OMEGA = 3f
        /** Settled below 1e-4 of scale (≤ 0.05 px across the screen from the pivot). */
        private const val KAMERA_OTURMA = 1e-4f
        private const val KAMERA_OTURMA_HIZ = 1e-4f
    }
}
