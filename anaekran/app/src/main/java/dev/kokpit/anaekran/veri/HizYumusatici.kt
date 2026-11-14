package dev.kokpit.anaekran.veri

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Smoothed GPS speed for the frame loop (design pack 0.2 §3, K-96; research notes 0.2 §5–6,
 * decisions 9, 10, 12). Pure Kotlin: no Android calls, so it is tested on the JVM alone.
 *
 * Screen contract (what to draw each frame, in this order)
 *  1. [bilinmiyor] true -> show "—" (no GPS speed yet, GPS stopped, or 60 s without a sample).
 *  2. otherwise the number = round([deger] × 3.6) km/h, rounded on every frame so the digits step
 *     through the in-between values;
 *  3. and [eski] true (5 s without a sample) -> the value stays, the "gps" label is drawn at 40 %.
 *  AracDurumu.hiz (rounded, null after 5 s) is no longer the screens' primary source for the number.
 *
 * Contract
 *  - Units: metres per second everywhere (in and out).
 *  - Clock: every `zamanNs` is in the System.nanoTime() timebase, the same as Choreographer's
 *    `frameTimeNanos`. NOT elapsedRealtimeNanos (that one also counts deep sleep; the two clocks
 *    drift apart after the head unit suspends). [HizKaynagi] stamps samples with System.nanoTime()
 *    at arrival for this reason.
 *  - Writer: [ornek], [unut] and [sifirla], called by [HizKaynagi] (wired by [VeriBaglayici]) for every
 *    GPS fix that has a speed. Readers: the frame loop (hareket/, ekran/) calls [deger] once per frame
 *    and may call [durgun], [eski], [eskiMi], [bilinmiyor]. Main thread only, writer and readers alike;
 *    there are no locks.
 *  - Waking a stopped loop: the frame loop's owner sets [uyandir]. It is called on the main thread
 *    from [ornek] only when a stopped loop would miss something: at rest -> moving ([durgun] true ->
 *    false), unknown -> known, stale -> fresh; and from [unut] when a known value becomes unknown.
 *    Steady samples (parked noise in zero mode, a constant speed) never call it, so GPS at 5–10 Hz
 *    does not wake a parked screen. The owner restarts its Choreographer loop there and stops it
 *    again when [durgun] is true.
 *  - The shared instance is [Hiz.yumusatici].
 *
 * Model
 *  - Target T: the last raw speed, plus a forward prediction for the first [TAHMIN_SN] after the
 *    sample (`T = max(0, son + ivme × min(0.5 s, since sample))`, acceleration against a sample
 *    [IVME_EN_KISA_SN]…[IVME_EN_UZUN_SN] older, clamped to [[IVME_EN_AZ]; [IVME_EN_COK]]). After 0.5 s
 *    the target holds the predicted value until the next sample, however long that takes ("stay at the
 *    last value" = the value on screen; going back to the raw sample would make the number climb again
 *    after braking). [ESKI_SN] (5 s) without a sample: [eski] = true, the value stays.
 *  - Zero mode (parked noise): raw < max([SIFIR_GIRIS], min(accuracy, [SIFIR_DOGRULUK_EN_COK])) on
 *    [SIFIR_ORNEK] consecutive samples -> T = 0; one sample ≥ the exit threshold -> back to the raw
 *    speed. A sample whose accuracy is larger than its speed cannot end zero mode (it still counts
 *    towards entering and feeds the target). Starts in zero mode (nothing known yet = parked until a real speed comes),
 *    so a parked car never shows a stray "1".
 *  - Shown value x: critically damped spring towards T, exact closed form per frame (stable for any dt),
 *    ω = [OMEGA]; dt is clamped to [0; [DT_EN_COK]] so a stalled frame never makes the number jump.
 *    The spring's rate is clipped each frame so it never overshoots T; x ≥ 0; with T = 0 the value
 *    snaps to 0 below [OTURMA_MS]. The moving target is taken at the middle of each step, and a step
 *    that contains a sample's arrival is split there, so 30 and 60 fps give the same curve.
 *  - The first sample while [bilinmiyor] is shown at once: there is no known value to animate from
 *    (opening the app while driving shows "50", not a fake 0→50 acceleration).
 */
class HizYumusatici {

    /** Shown speed, m/s (≥ 0). */
    private var x = 0.0
    /** Rate of the shown speed, m/s². */
    private var v = 0.0
    private var sonKareNs = YOK

    private var sonHiz = 0.0
    private var sonOrnekNs = YOK
    private var ivme = 0.0
    private var sifirKipi = true
    private var altSayac = 0
    /** Target just before the newest sample, for the part of the next frame step that lies before it. */
    private var oncekiHedef = 0.0
    private var bolunecek = false

    /** Recent samples for the acceleration: a ring of [GECMIS] (1.6 s at 20 Hz), [gecmisBas] = oldest. */
    private val gecmisHiz = DoubleArray(GECMIS)
    private val gecmisNs = LongArray(GECMIS)
    private var gecmisSayi = 0
    private var gecmisBas = 0

    /**
     * Set by the frame loop's owner; called on the main thread when a stopped loop must run again
     * (see the class notes). null = nobody to wake.
     */
    var uyandir: (() -> Unit)? = null

    /** No value to show ("—"): before the first sample, after [sifirla] and after [unut]. */
    var bilinmiyor: Boolean = true
        private set

    /** True when no sample came for more than [ESKI_SN] (or none yet). Refreshed by [deger], [ornek] and [eskiMi]; the "gps" label at 40 %. */
    var eski: Boolean = true
        private set

    /** For the diagnostics line: zero mode on. */
    val sifirda: Boolean get() = sifirKipi

    /** For the diagnostics line: the last raw speed, m/s; null before the first sample. */
    val sonHam: Float? get() = if (sonOrnekNs == YOK) null else sonHiz.toFloat()

    /** For the diagnostics line: the acceleration used for the prediction, m/s². */
    val sonIvme: Float get() = ivme.toFloat()

    /**
     * A new GPS speed: [hizMs] in m/s (Location.getSpeed()), [zamanNs] its arrival time in the
     * System.nanoTime() timebase, [dogruluk] the speed accuracy in m/s (Location.getSpeedAccuracyMetersPerSecond()
     * when hasSpeedAccuracy()), else null. NaN/infinite speeds are ignored; a negative one counts as 0.
     */
    fun ornek(hizMs: Float, zamanNs: Long, dogruluk: Float?) {
        if (hizMs.isNaN() || hizMs.isInfinite()) return
        val ham = max(0.0, hizMs.toDouble())
        val onceDurgun = durgun(zamanNs)
        val onceBilinmiyor = bilinmiyor
        val onceEski = eskiMi(zamanNs)
        oncekiHedef = hedef(zamanNs)
        bolunecek = true
        // Acceleration: against the newest earlier sample that is at least 0.5 s older (at 1 Hz: the
        // previous one; at 20 Hz: about the 10th back). Samples closer together are too noisy; one older
        // than 2 s is from before a gap. If the ring holds nothing old enough (above ~60 Hz): no prediction.
        ivme = 0.0
        for (k in gecmisSayi - 1 downTo 0) {
            val i = (gecmisBas + k) % GECMIS
            val ara = (zamanNs - gecmisNs[i]) / NS_PER_SN
            if (ara < IVME_EN_KISA_SN) continue
            if (ara <= IVME_EN_UZUN_SN) ivme = ((ham - gecmisHiz[i]) / ara).coerceIn(IVME_EN_AZ, IVME_EN_COK)
            break
        }
        gecmisEkle(ham, zamanNs)

        val d = dogruluk?.toDouble()?.takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() }
        // The accuracy raises the thresholds, but by at most 1.5 m/s: a wild accuracy never zeroes a moving car.
        val giris = max(SIFIR_GIRIS, min(d ?: 0.0, SIFIR_DOGRULUK_EN_COK))
        val cikis = max(SIFIR_CIKIS, giris + (SIFIR_CIKIS - SIFIR_GIRIS))
        // A speed smaller than its own accuracy is no evidence of moving: it cannot end zero mode
        // (parked multipath). It still counts towards entering: parked noise (0.3 ± 0.8) must reach 0.
        val belirsiz = d != null && d > ham
        when {
            ham >= cikis -> if (!belirsiz) { sifirKipi = false; altSayac = 0 }
            ham < giris -> { altSayac++; if (altSayac >= SIFIR_ORNEK) sifirKipi = true }
            else -> altSayac = 0 // in between: neither enters nor leaves zero mode, breaks the run
        }
        if (sifirKipi) ivme = 0.0

        sonHiz = ham
        sonOrnekNs = zamanNs
        eski = false
        if (bilinmiyor) {
            bilinmiyor = false
            x = if (sifirKipi) 0.0 else ham
            v = 0.0
            oncekiHedef = x // nothing to animate from: no pull towards the empty state before this sample
        }
        if ((onceDurgun && !durgun(zamanNs)) || onceBilinmiyor || onceEski) uyandir?.invoke()
    }

    /**
     * The speed to show on the frame at [zamanNs] (Choreographer frameTimeNanos), m/s, ≥ 0 (never NaN;
     * 0 while [bilinmiyor], when the screen draws "—" instead). Call once per frame: it advances the
     * spring by the time since the previous call (0 on the first).
     */
    fun deger(zamanNs: Long): Float {
        val onceki = sonKareNs
        val tumDt = if (onceki == YOK) 0.0 else (zamanNs - onceki) / NS_PER_SN
        val dt = tumDt.coerceIn(0.0, DT_EN_COK)
        sonKareNs = zamanNs
        eskiMi(zamanNs)
        if (bolunecek && onceki != YOK && tumDt <= DT_EN_COK && sonOrnekNs in (onceki + 1)..zamanNs) {
            // A sample arrived inside this step: the part before it still runs towards the old target,
            // so the result does not depend on where the frames fall (30 vs 60 fps).
            ilerle(oncekiHedef, (sonOrnekNs - onceki) / NS_PER_SN)
            val dt2 = (zamanNs - sonOrnekNs) / NS_PER_SN
            ilerle(hedef(zamanNs - (dt2 * NS_PER_SN / 2).toLong()), dt2)
        } else {
            // The target moves during the prediction ramp; taken at the middle of the step, the result
            // hardly depends on the frame rate (at the end of the step, 30 fps would run ahead of 60 fps).
            ilerle(hedef(zamanNs - (dt * NS_PER_SN / 2).toLong()), dt)
        }
        bolunecek = false
        return x.toFloat()
    }

    /** The parked frame loop may stop: target 0, shown 0, spring at rest. Wakes again on the next real speed. */
    fun durgun(zamanNs: Long): Boolean = hedef(zamanNs) == 0.0 && x == 0.0 && abs(v) < DURGUN_HIZ

    /** [eski] for [zamanNs] without moving the spring (when the frame loop is stopped). */
    fun eskiMi(zamanNs: Long): Boolean {
        eski = sonOrnekNs == YOK || (zamanNs - sonOrnekNs) / NS_PER_SN > ESKI_SN
        return eski
    }

    /**
     * GPS silent for 60 s ([HizKaynagi.HAL_ESKIME_NS]): the value is no longer known ([bilinmiyor], the
     * screen shows "—"). Calls [uyandir] if a value was known, so a stopped loop redraws.
     */
    fun unut() {
        val bilinen = !bilinmiyor
        sifirla()
        if (bilinen) uyandir?.invoke()
    }

    /** Back to "nothing known" without waking anyone (GPS stopped, app paused): next sample is shown at once. */
    fun sifirla() {
        x = 0.0; v = 0.0; sonKareNs = YOK
        bilinmiyor = true
        sonHiz = 0.0; sonOrnekNs = YOK; ivme = 0.0
        sifirKipi = true; altSayac = 0
        oncekiHedef = 0.0; bolunecek = false
        gecmisSayi = 0; gecmisBas = 0
        eski = true
    }

    /** Target speed at [zamanNs], m/s. Exposed for tests and the diagnostics line. */
    fun hedef(zamanNs: Long): Double {
        if (sifirKipi || sonOrnekNs == YOK) return 0.0
        val gecen = max(0.0, (zamanNs - sonOrnekNs) / NS_PER_SN)
        return max(0.0, sonHiz + ivme * min(TAHMIN_SN, gecen))
    }

    private fun ilerle(t: Double, dt: Double) {
        var a = x - t
        // No overshoot: towards the target the rate may not exceed ω·|a| (then a and b = v + ω·a keep one sign).
        if (a > 0.0) v = max(v, -OMEGA * a) else if (a < 0.0) v = min(v, -OMEGA * a)
        if (dt > 0.0) {
            val b = v + OMEGA * a
            val e = exp(-OMEGA * dt)
            a = (a + b * dt) * e
            v = (v - OMEGA * b * dt) * e
            x = t + a
        }
        if (x <= 0.0) { x = 0.0; if (v < 0.0) v = 0.0 }
        if (t == 0.0 && x < OTURMA_MS) { x = 0.0; v = 0.0 }
    }

    private fun gecmisEkle(h: Double, ns: Long) {
        val i: Int
        if (gecmisSayi == GECMIS) { i = gecmisBas; gecmisBas = (gecmisBas + 1) % GECMIS } else { i = (gecmisBas + gecmisSayi) % GECMIS; gecmisSayi++ }
        gecmisHiz[i] = h
        gecmisNs[i] = ns
    }

    companion object {
        /** Spring's natural frequency, 1/s: (1 + ωt)e^(−ωt) leaves ≈ 2.7 % after 1 s (research decision 9). */
        const val OMEGA = 5.5
        /** Longest frame step, s (3 frames at 60 Hz). */
        const val DT_EN_COK = 0.05
        /** With target 0 the shown value snaps to 0 below this, m/s (0.54 km/h). */
        const val OTURMA_MS = 0.15

        /** Forward prediction horizon, s (design §3, research decision 10). */
        const val TAHMIN_SN = 0.5
        /** Acceleration only from two samples at least this far apart, s. */
        const val IVME_EN_KISA_SN = 0.5
        /** … and at most this far apart (older = from before a gap), s. */
        const val IVME_EN_UZUN_SN = 2.0
        /** Acceleration clamp, m/s²: braking ≈ 1 g, pulling away ≈ 0.4 g. */
        const val IVME_EN_AZ = -9.0
        const val IVME_EN_COK = 4.0
        /** No sample for this long: [eski], s. */
        const val ESKI_SN = 5.0

        // Zero mode (research decision 12). Proposed numbers, TO BE CALIBRATED IN THE CAR: set the entry
        // threshold to the p99 of the parked `speed` log + 0.2 m/s; walking-pace traffic (0.8–1.7 m/s) must stay above it.
        /** Enter: raw below max(this, min(speed accuracy, [SIFIR_DOGRULUK_EN_COK])), m/s (1.8 km/h). */
        const val SIFIR_GIRIS = 0.5
        /**
         * The accuracy raises the entry threshold by at most this much, m/s (5.4 km/h): a bad accuracy
         * (20 m/s seen in reviews) must never put a moving car into zero mode (research §6: drop above 1.5).
         */
        const val SIFIR_DOGRULUK_EN_COK = 1.5
        /** Leave: raw at or above this, m/s (2.9 km/h); raised by the same gap when the accuracy raises the entry. */
        const val SIFIR_CIKIS = 0.8
        /** Consecutive samples below the entry threshold to enter. */
        const val SIFIR_ORNEK = 2

        private const val DURGUN_HIZ = 1e-3
        private const val GECMIS = 32
        private const val NS_PER_SN = 1e9
        private const val YOK = Long.MIN_VALUE
    }
}

/**
 * The app's single smoothed speed. [HizKaynagi] writes every GPS speed into it (main thread);
 * the frame loop reads `Hiz.yumusatici.deger(frameTimeNanos)` (main thread). See [HizYumusatici].
 */
object Hiz {
    val yumusatici = HizYumusatici()
}
