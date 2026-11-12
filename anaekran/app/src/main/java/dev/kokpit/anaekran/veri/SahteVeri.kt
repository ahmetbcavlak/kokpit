package dev.kokpit.anaekran.veri

/** Made-up scenarios for tests and previews. Never used by the running app on the car. */
object SahteVeri {

    private val klima215Oto = Klima(
        sicaklik = Sicaklik.Derece(21.5), fan = 3, havaYonu = HavaYonu.YUZ_AYAK,
        ac = true, oto = true, buzCozme = false,
    )

    /** Parked at night: climate 21.5° auto, 14° outside, doors closed, standing still. */
    fun parkGece() = AracDurumu(klima = klima215Oto, kapi = KapiDurumu.KAPALI, disSicaklik = 14.0, hiz = 0.0)

    /** Driving at 62 km/h. */
    fun surus62() = parkGece().copy(hiz = 62.0)

    fun kapiAcik() = parkGece().copy(kapi = KapiDurumu.KAPI_ACIK)

    fun bagajAcik() = parkGece().copy(kapi = KapiDurumu.BAGAJ_ACIK)

    /** Only the climate is known (the other sources are not running yet). */
    fun klima215Oto() = AracDurumu(klima = klima215Oto)

    /** Writes a scenario into [AracVerisi] through its normal write path. */
    fun uygula(d: AracDurumu) = AracVerisi.guncelle { hepsi(d) }
}
