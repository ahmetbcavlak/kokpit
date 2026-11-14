package dev.kokpit.anaekran.veri

/*
 * Vehicle data contract (Ana Ekran 0.1). Only verified signals (K-74):
 * climate (ACTION_AIR_INFO), outside temperature (hcn.air.outdoor_temp), GPS speed,
 * door/boot warning (own AccessibilityService). Every field may be null = "unknown":
 * the UI shows "—" and never guesses (a missing door signal is NOT "closed").
 *
 * Owner from task 2 on: the data agent may add fields here; keep them nullable with a time.
 * 0.2 adds the raw GPS stream ([AracDurumu.hamHiz], [AracDurumu.hamHizOrnekNs]); the smoothed speed for
 * animation is HizYumusatici (Hiz.yumusatici), outside this snapshot.
 */

/** Set temperature: a number or one of the unit's end stops. */
sealed class Sicaklik {
    data class Derece(val c: Double) : Sicaklik()
    data object Low : Sicaklik()
    data object Hi : Sicaklik()
}

/** Air direction as the climate unit reports it. */
enum class HavaYonu { YUZ, YUZ_AYAK, AYAK, AYAK_CAM, CAM }

/**
 * Climate as ACTION_AIR_INFO reports it. Only fields with a known source are filled (see KlimaCozucu):
 * sicaklik = KEY_VALUE_LEFT_TEMP, fan = KEY_VALUE_WIND_LEVEL, ac = KEY_VALUE_ACFLAG, oto = KEY_VALUE_BIGAUTOFLAG.
 * [havaYonu] and [buzCozme] have no confirmed key yet and stay null in 0.1.
 */
data class Klima(
    /** Single zone (12 Nov: DUAL always 0): the driver's (left) value; the right one is diagnostics only. */
    val sicaklik: Sicaklik? = null,
    /** Fan level 0–8 (0 = off). */
    val fan: Int? = null,
    val havaYonu: HavaYonu? = null,
    val ac: Boolean? = null,
    val oto: Boolean? = null,
    /** Front windscreen demist. Not confirmed: always null in 0.1. */
    val buzCozme: Boolean? = null,
)

/** Old single door state; kept only for [AracDurumu.kapi] until the screens move to kapiAcik/bagajAcik. */
enum class KapiDurumu { KAPALI, KAPI_ACIK, BAGAJ_ACIK }

/**
 * Parked or driving, decided from GPS speed with hysteresis (see HareketKarari):
 * above 10 km/h for 2 s -> SURUS, below 5 km/h for 5 s -> PARK. null = not known yet / GPS lost too long.
 */
enum class HareketHali { PARK, SURUS }

/**
 * Immutable snapshot. Each value carries the time of its last update
 * (SystemClock.elapsedRealtime() in ms, 0 = never), so screens can tell stale from fresh.
 * Two snapshots that differ only in their times are the same state for listeners ([ayniDegerler]).
 */
data class AracDurumu(
    val klima: Klima? = null,
    val klimaZamani: Long = 0L,
    /**
     * Door: true while com.hcn.autocan's "Kapı açık" popup is on screen, false once that popup has
     * closed, null until the popup has been seen at all (or the door service is off). Says nothing about the boot.
     */
    val kapiAcik: Boolean? = null,
    val kapiAcikZamani: Long = 0L,
    /** Boot: the same for the "Bagaj açık" popup. Door and boot may both be open. */
    val bagajAcik: Boolean? = null,
    val bagajAcikZamani: Long = 0L,
    /** Outside temperature in °C. */
    val disSicaklik: Double? = null,
    val disSicaklikZamani: Long = 0L,
    /** Speed in km/h, source: GPS (LocationManager). null when the last fix with speed is older than 5 s. */
    val hiz: Double? = null,
    val hizZamani: Long = 0L,
    /**
     * Raw GPS speed in m/s (Location.getSpeed(), not rounded, ≥ 0), written on every fix with a speed;
     * null with [hiz] (fix older than 5 s, GPS stopped). Screens animate with HizYumusatici (Hiz.yumusatici),
     * not with this value; it is here for anything that needs the plain sample.
     */
    val hamHiz: Float? = null,
    /**
     * Arrival time of that fix, SystemClock.elapsedRealtimeNanos() (0 = never). On Oreo the fix's own stamp
     * is not the measurement time either (research decision 10), so the arrival is used. Part of the VALUE,
     * not an update time: every fix is a change, so listeners wake once per fix while it comes (≈ 1/s, accepted).
     */
    val hamHizOrnekNs: Long = 0L,
    /** Parked/driving from the GPS speed; kept through short GPS gaps (tunnels), see HizKaynagi. */
    val hareketHali: HareketHali? = null,
    val hareketHaliZamani: Long = 0L,
) {
    /**
     * Old single door state, derived: door open > boot open > both false = KAPALI > otherwise null.
     * Only so the skeleton screens keep compiling; task 4 moves them to [kapiAcik]/[bagajAcik].
     */
    @Deprecated("Use kapiAcik and bagajAcik")
    val kapi: KapiDurumu?
        get() = when {
            kapiAcik == true -> KapiDurumu.KAPI_ACIK
            bagajAcik == true -> KapiDurumu.BAGAJ_ACIK
            kapiAcik == false && bagajAcik == false -> KapiDurumu.KAPALI
            else -> null
        }

    @Deprecated("Use kapiAcikZamani and bagajAcikZamani")
    val kapiZamani: Long get() = maxOf(kapiAcikZamani, bagajAcikZamani)

    /** Same values, update times ignored. */
    fun ayniDegerler(o: AracDurumu): Boolean = zamansiz() == o.zamansiz()

    private fun zamansiz() = copy(
        klimaZamani = 0L, kapiAcikZamani = 0L, bagajAcikZamani = 0L,
        disSicaklikZamani = 0L, hizZamani = 0L, hareketHaliZamani = 0L,
    )
}
