package dev.kokpit.anaekran.veri

/*
 * Vehicle data contract (Ana Ekran 0.1). Only verified signals (K-74):
 * climate (ACTION_AIR_INFO), outside temperature (hcn.air.outdoor_temp), GPS speed,
 * door/boot warning (own AccessibilityService). Every field may be null = "unknown":
 * the UI shows "—" and never guesses (a missing door signal is NOT "closed").
 *
 * Owner from task 2 on: the data agent may add fields here; keep them nullable with a time.
 */

/** Set temperature: a number or one of the unit's end stops. */
sealed class Sicaklik {
    data class Derece(val c: Double) : Sicaklik()
    data object Low : Sicaklik()
    data object Hi : Sicaklik()
}

/** Air direction as the climate unit reports it. */
enum class HavaYonu { YUZ, YUZ_AYAK, AYAK, AYAK_CAM, CAM }

data class Klima(
    val sicaklik: Sicaklik? = null,
    /** Fan level 0–8 (0 = off). */
    val fan: Int? = null,
    val havaYonu: HavaYonu? = null,
    val ac: Boolean? = null,
    val oto: Boolean? = null,
    /** Front windscreen demist. */
    val buzCozme: Boolean? = null,
)

enum class KapiDurumu { KAPALI, KAPI_ACIK, BAGAJ_ACIK }

/**
 * Immutable snapshot. Each value carries the time of its last update
 * (SystemClock.elapsedRealtime() in ms, 0 = never), so screens can tell stale from fresh.
 */
data class AracDurumu(
    val klima: Klima? = null,
    val klimaZamani: Long = 0L,
    val kapi: KapiDurumu? = null,
    val kapiZamani: Long = 0L,
    /** Outside temperature in °C. */
    val disSicaklik: Double? = null,
    val disSicaklikZamani: Long = 0L,
    /** Speed in km/h, source: GPS (LocationManager). */
    val hiz: Double? = null,
    val hizZamani: Long = 0L,
)
