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
    private var oncekiOmega = 0f

    // --- Outputs (functions of the state) ---

    val bulaniklik: Float get() = SurusFormul.bulaniklik(kmh)
    val izOpakligi: Float get() = SurusFormul.izOpakligi(kmh)
    val isikOpakligi: Float get() = SurusFormul.isikOpakligi(kmh)
    val lambaGorunurlugu: Float get() = SurusFormul.hareketPayi(kmh)

    /** Lamp band left edge (share of the body box width), null when no band. */
    val lambaKonumu: Float? get() = SurusFormul.lambaKonumu(mesafe)

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
        val omega = SurusFormul.gosterilenAcisalHiz(yeni)
        if (dt > 0f) {
            mesafe += 0.5 * (oncekiHiz + yeni) * dt
            val derece = 0.5 * (oncekiOmega + omega) * dt * 180.0 / PI
            tekerAcisi = (tekerAcisi + derece).mod(360.0)
            sustFaz = (sustFaz + 2.0 * PI * dt / (2.0 * SurusFormul.SUSPANSIYON_YARIM_SN)).mod(2.0 * PI)
            kameraIlerle(dt, SurusFormul.kameraHedefi(SurusFormul.kmh(yeni)))
        }
        hiz = yeni
        oncekiHiz = yeni
        oncekiOmega = omega
    }

    /**
     * Back to the rest look without moving anything that is "in the world" (lane lines, wheel
     * angle and the lamp keep their positions): camera 1, suspension at 0. For a scene that was
     * hidden while the car was moving and must now start from the clip's still last frame.
     */
    fun dinlenmeyeAl() {
        kamera = 1f
        kameraHizi = 0f
        sustFaz = 0.0
        hiz = 0f
        oncekiHiz = 0f
        oncekiOmega = 0f
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
        private const val KAMERA_OTURMA = 1e-5f
        private const val KAMERA_OTURMA_HIZ = 1e-4f
    }
}
