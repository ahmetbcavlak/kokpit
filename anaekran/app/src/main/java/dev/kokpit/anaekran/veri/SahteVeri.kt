package dev.kokpit.anaekran.veri

/** Made-up scenarios for tests and previews. Never used by the running app on the car. */
object SahteVeri {

    // havaYonu/buzCozme stay null: no confirmed key (same as on the car in 0.1).
    private val klima215Oto = Klima(sicaklik = Sicaklik.Derece(21.5), fan = 3, ac = true, oto = true)

    /** Parked at night: climate 21.5° auto, 14° outside, doors closed, standing still. */
    fun parkGece() = AracDurumu(
        klima = klima215Oto, kapiAcik = false, bagajAcik = false, disSicaklik = 14.0, hiz = 0.0, hareketHali = HareketHali.PARK,
    )

    /** Driving at 62 km/h. */
    fun surus62() = parkGece().copy(hiz = 62.0, hareketHali = HareketHali.SURUS)

    fun kapiAcik() = parkGece().copy(kapiAcik = true)

    fun bagajAcik() = parkGece().copy(bagajAcik = true)

    /** Door and boot open at once. */
    fun kapiVeBagajAcik() = parkGece().copy(kapiAcik = true, bagajAcik = true)

    /** Only the climate is known (the other sources are not running yet). */
    fun klima215Oto() = AracDurumu(klima = klima215Oto)

    /** Writes a scenario into [AracVerisi] through its normal write path. */
    fun uygula(d: AracDurumu) = AracVerisi.guncelle { hepsi(d) }
}
