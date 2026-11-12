package dev.kokpit.anaekran.tema

import java.util.Calendar

fun interface TemaDinleyici {
    fun temaDegisti(palet: Palet)
}

/**
 * The one place that decides night or day. Rule for 0.1: 07:00–18:59 day, otherwise night.
 * Callers poke [kontrolEt] (MainActivity does it every minute while visible); listeners
 * hear only real changes. Main thread only.
 */
object Tema {

    // Placeholder: later this can follow sunrise/sunset for the car's position
    // (needs location; see the data task). Keep the decision inside gunduzMu().
    const val GUNDUZ_BASLA = 7
    const val GECE_BASLA = 19

    /** Current hour 0–23; tests replace it. */
    var saat: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    /** Forced palette for previews/tests; null = follow the clock. */
    private var zorla: Palet? = null

    var palet: Palet = sec()
        private set

    private val dinleyiciler = ArrayList<TemaDinleyici>()

    fun gunduzMu(saat: Int): Boolean = saat in GUNDUZ_BASLA until GECE_BASLA

    fun dinle(d: TemaDinleyici) {
        if (d !in dinleyiciler) dinleyiciler.add(d)
    }

    fun birak(d: TemaDinleyici) {
        dinleyiciler.remove(d)
    }

    /** Re-reads the clock and tells listeners if the palette changed. */
    fun kontrolEt() {
        val yeni = sec()
        if (yeni == palet) return
        palet = yeni
        for (d in dinleyiciler.toList()) d.temaDegisti(yeni)
    }

    /** Pins a palette (previews); pass null to go back to the clock. */
    fun zorla(p: Palet?) {
        zorla = p
        kontrolEt()
    }

    private fun sec(): Palet = zorla ?: if (gunduzMu(saat())) Palet.GUNDUZ else Palet.GECE
}
