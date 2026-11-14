package dev.kokpit.anaekran.hareket.surus

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * The drive scene's formulas (design pack 0.2 §2, K-94/K-95; values and CSS from
 * referans/Y-3h-SurusHareketi.dc.html). Pure functions of the smoothed speed `v` (m/s) or of
 * km/h; no state, no Android. [SurusModeli] integrates them over time.
 */
object SurusFormul {
    /** On screen 1 m = 142 px (yerlesim.json `ekranda_1_metre_px`). */
    const val PX_M = 142f

    /** Wheel radius: circumference 1.986 m (yerlesim.json `teker_cevresi_m`). */
    const val TEKER_YARICAP_M = 0.316f

    const val KMH_ORAN = 3.6f

    // --- Rim blur (sprite 0 sharp, 1 light 24° blur, 2 full 72° disc) ---
    const val NET_KMH = 12f
    const val YARI_KMH = 30f
    const val TAM_KMH = 45f

    /** On a blurred sprite the shown rotation is at most this share of the real one... */
    const val BULANIK_HIZ_ORANI = 1f / 3f

    /** ...and never faster than one turn in this many seconds. */
    const val BULANIK_EN_KISA_TUR_SN = 0.9f

    // --- Lane lines: 3 m dash + 6 m gap ---
    const val SERIT_DOLU_M = 3f
    const val SERIT_BOS_M = 6f
    const val SERIT_PERIYOT_M = SERIT_DOLU_M + SERIT_BOS_M

    /** Far line: same pattern at 0.8 scale and 0.8 speed, 35 % opaque. */
    const val UZAK_OLCEK = 0.8f
    const val UZAK_OPAKLIK = 0.35f

    /** Near line opacity (demo `opacity:0.55`). */
    const val YAKIN_OPAKLIK = 0.55f

    /** Distant city lights move at speed × 142 × 0.04 px/s. */
    const val ISIK_ORANI = 0.04f

    /** City lights: 60 % when standing, 100 % when moving (demo `bokehop`). */
    const val ISIK_DURAN_OPAKLIK = 0.6f

    // --- Lamp reflection: every 35 m, sweeps during the first 18 % of the period ---
    const val LAMBA_ARALIK_M = 35f
    const val LAMBA_SUPURME = 0.18f

    /** Band left edge, as a share of the body box width: −40 % → 110 % (demo keyframes). */
    const val LAMBA_BAS = -0.40f
    const val LAMBA_SON = 1.10f

    /** Band width (34 % of the body box), skew (−18°), peak opacity (38 %, screen blend). */
    const val LAMBA_GENISLIK = 0.34f
    const val LAMBA_EGIM_DERECE = -18f
    const val LAMBA_OPAKLIK = 0.38f

    // --- Speed lines behind the tail ---
    const val IZ_TAM_KMH = 80f

    // --- Suspension: 1.1 s up, 1.1 s down, amplitude min(1, km/h / 60) × 0.9 px ---
    const val SUSPANSIYON_YARIM_SN = 1.1f
    const val SUSPANSIYON_TAM_KMH = 60f
    const val SUSPANSIYON_GENLIK_PX = 0.9f

    // --- Camera: scale 1 − 0.035 × min(km/h, 130) / 130, eased over ~1.6 s ---
    const val KAMERA_KUCULME = 0.035f
    const val KAMERA_EN_COK_KMH = 130f
    const val KAMERA_SURE_SN = 1.6f

    /**
     * Below this speed the "moving" looks (city lights at full, lamp reflection) fade in and out
     * linearly instead of switching at exactly 0 like the demo (its speed jumps; ours glides).
     */
    const val HAREKET_ESIK_KMH = 5f

    fun kmh(v: Float): Float = v * KMH_ORAN

    /** Real wheel angular speed, rad/s. */
    fun gercekAcisalHiz(v: Float): Float = v / TEKER_YARICAP_M

    /** Cap on a blurred sprite's shown rotation, rad/s: min(real / 3, one turn per 0.9 s). */
    fun bulanikAcisalHiz(v: Float): Float =
        min(gercekAcisalHiz(v) * BULANIK_HIZ_ORANI, (2.0 * PI / BULANIK_EN_KISA_TUR_SN).toFloat())

    /**
     * Rim blur level b ∈ [0; 2]: 0–12 km/h 0 (sharp); 12–30 0 → 1; 30–45 1 → 2; above 45 only 2.
     * b ∈ [0; 1): sprite 0 below at alpha 1, sprite 1 above at alpha b; b ∈ [1; 2]: sprite 1
     * below at alpha 1, sprite 2 above at alpha b − 1 (research decision 3: no three-way blend).
     */
    fun bulaniklik(kmh: Float): Float = when {
        kmh <= NET_KMH -> 0f
        kmh < YARI_KMH -> (kmh - NET_KMH) / (YARI_KMH - NET_KMH)
        kmh < TAM_KMH -> 1f + (kmh - YARI_KMH) / (TAM_KMH - YARI_KMH)
        else -> 2f
    }

    /**
     * Shown wheel angular speed, rad/s (positive; the wheel turns counter-clockwise because the
     * car moves to the left). Up to 30 km/h the real speed (sprites 0 and 1); while sprite 2 fades
     * in (30–45 km/h) it eases from the real speed to the blurred cap by the same share; from
     * 45 km/h only the cap. The demo switches to the cap at 30 km/h in one step (its speed
     * jumps); a gliding GPS speed would show that as a visible jolt of the wheel.
     */
    fun gosterilenAcisalHiz(v: Float): Float {
        val b = bulaniklik(kmh(v))
        val gercek = gercekAcisalHiz(v)
        if (b <= 1f) return gercek
        val t = b - 1f
        return gercek + (bulanikAcisalHiz(v) - gercek) * t
    }

    /** Near lane line speed, px/s (to the right). */
    fun yakinSeritHizi(v: Float): Float = v * PX_M

    /** Far lane line speed, px/s. */
    fun uzakSeritHizi(v: Float): Float = v * PX_M * UZAK_OLCEK

    /** City lights speed, px/s. */
    fun isikHizi(v: Float): Float = v * PX_M * ISIK_ORANI

    /** Speed lines opacity: min(1, km/h / 80). */
    fun izOpakligi(kmh: Float): Float = (kmh / IZ_TAM_KMH).coerceIn(0f, 1f)

    /** Suspension amplitude, px: min(1, km/h / 60) × 0.9. */
    fun suspansiyonGenligi(kmh: Float): Float = (kmh / SUSPANSIYON_TAM_KMH).coerceIn(0f, 1f) * SUSPANSIYON_GENLIK_PX

    /**
     * Suspension offset (px, ≤ 0 = up) at phase [faz] (radians, 2π per 2.2 s): the demo's
     * `translateY(0 → −bob)` 1.1 s ease-in-out alternate, as a raised cosine.
     */
    fun suspansiyon(genlik: Float, faz: Double): Float = (-genlik * (1.0 - cos(faz)) / 2.0).toFloat() + 0f // + 0f: no −0

    /** Camera scale target: 1 − 0.035 × min(km/h, 130) / 130. */
    fun kameraHedefi(kmh: Float): Float = 1f - KAMERA_KUCULME * min(kmh.coerceAtLeast(0f), KAMERA_EN_COK_KMH) / KAMERA_EN_COK_KMH

    /** 0 at a standstill → 1 from [HAREKET_ESIK_KMH]. */
    fun hareketPayi(kmh: Float): Float = (kmh / HAREKET_ESIK_KMH).coerceIn(0f, 1f)

    /** City lights opacity: 0.6 standing → 1 moving. */
    fun isikOpakligi(kmh: Float): Float = ISIK_DURAN_OPAKLIK + (1f - ISIK_DURAN_OPAKLIK) * hareketPayi(kmh)

    /**
     * Lamp band left edge as a share of the body box width, or null when no band is on the body
     * (82 % of every 35 m). [mesafeM] is the distance driven.
     */
    fun lambaKonumu(mesafeM: Double): Float? {
        val faz = (mesafeM.mod(LAMBA_ARALIK_M.toDouble()) / LAMBA_ARALIK_M).toFloat()
        if (faz >= LAMBA_SUPURME) return null
        return LAMBA_BAS + (LAMBA_SON - LAMBA_BAS) * (faz / LAMBA_SUPURME)
    }

    /** Lane dash period on screen, px (near 1278, far 1022.4). */
    fun seritPeriyodu(olcek: Float): Float = SERIT_PERIYOT_M * PX_M * olcek

    /** Lane dash length on screen, px (near 426, far 340.8). */
    fun seritDolu(olcek: Float): Float = SERIT_DOLU_M * PX_M * olcek
}
