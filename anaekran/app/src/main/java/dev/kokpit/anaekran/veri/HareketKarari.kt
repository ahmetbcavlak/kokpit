package dev.kokpit.anaekran.veri

/**
 * Parked/driving decision from GPS speed samples (research decision 9).
 *
 *  - above [surusEsigi] km/h without a break for [surusSuresiNs] -> SURUS
 *  - below [parkEsigi] km/h without a break for [parkSuresiNs] -> PARK
 *  - between the two thresholds: the state stays and both runs restart
 *
 * Durations come from the samples' own clock (Location.elapsedRealtimeNanos), not wall time.
 * A gap longer than [kopuklukNs] between samples (GPS lost) breaks a run, so two short bursts
 * on either side of a tunnel do not add up. Pure logic: no Android calls, one thread.
 */
class HareketKarari(
    private val surusEsigi: Double = 10.0,
    private val surusSuresiNs: Long = 2_000_000_000L,
    private val parkEsigi: Double = 5.0,
    private val parkSuresiNs: Long = 5_000_000_000L,
    private val kopuklukNs: Long = 5_000_000_000L,
) {
    var hal: HareketHali? = null
        private set

    private var ustBaslangic = YOK
    private var altBaslangic = YOK
    private var sonOrnek = YOK

    /** One sample: speed in km/h at [ns]. Returns the (possibly unchanged) state. */
    fun ornek(kmh: Double, ns: Long): HareketHali? {
        if (sonOrnek != YOK && (ns < sonOrnek || ns - sonOrnek > kopuklukNs)) kosulariSifirla()
        sonOrnek = ns
        when {
            kmh > surusEsigi -> {
                altBaslangic = YOK
                if (ustBaslangic == YOK) ustBaslangic = ns
                if (ns - ustBaslangic >= surusSuresiNs) hal = HareketHali.SURUS
            }
            kmh < parkEsigi -> {
                ustBaslangic = YOK
                if (altBaslangic == YOK) altBaslangic = ns
                if (ns - altBaslangic >= parkSuresiNs) hal = HareketHali.PARK
            }
            else -> kosulariSifirla()
        }
        return hal
    }

    /** GPS silent for too long: the state is no longer known. */
    fun unut() {
        hal = null
        kosulariSifirla()
        sonOrnek = YOK
    }

    private fun kosulariSifirla() {
        ustBaslangic = YOK
        altBaslangic = YOK
    }

    private companion object {
        const val YOK = Long.MIN_VALUE
    }
}
