package dev.kokpit.anaekran.tema

import android.content.Context
import android.location.LocationManager
import dev.kokpit.anaekran.veri.VeriBaglayici

fun interface TemaDinleyici {
    fun temaDegisti(palet: Palet)
}

/**
 * The one place that decides night or day. Rule (K-88, changed in İ-4): day from sunrise to
 * sunset at the car's position ([Gunes], NOAA's simplified formulas), night otherwise. The fixed
 * 07:00–19:00 of the first 0.1 builds is gone: in November Istanbul is dark at 16:45, and two
 * hours of the bright day scene in the dark dazzle the driver.
 *
 * Position: the last known GPS fix ([SonKonum], read-only, permission checked), else Istanbul
 * ([ISTANBUL]). Across Turkey the error of the default is under an hour at the extreme east.
 *
 * Callers poke [kontrolEt] (MainActivity does it every minute while visible, with the clock);
 * listeners hear only real changes. Main thread only.
 */
object Tema {

    data class Konum(val enlem: Double, val boylam: Double)

    /** Used when no GPS fix is known (41.01 N, 28.98 E). */
    val ISTANBUL = Konum(41.01, 28.98)

    /** Wall clock in epoch ms; tests replace it. */
    var simdi: () -> Long = { System.currentTimeMillis() }

    /** The car's position, null = not known; MainActivity sets [SonKonum], tests replace it. */
    var konum: () -> Konum? = { null }

    /** Forced palette for previews/tests; null = follow the sun. */
    private var zorla: Palet? = null

    var palet: Palet = sec()
        private set

    private val dinleyiciler = ArrayList<TemaDinleyici>()

    /** Day at [anMs] for [k] (sunrise ≤ now < sunset). */
    fun gunduzMu(anMs: Long, k: Konum = ISTANBUL): Boolean = Gunes.gunduzMu(anMs, k.enlem, k.boylam)

    /** The position the decision uses now (diagnostics too). */
    fun gecerliKonum(): Konum = gecerli(runCatching { konum() }.getOrNull()) ?: ISTANBUL

    fun dinle(d: TemaDinleyici) {
        if (d !in dinleyiciler) dinleyiciler.add(d)
    }

    fun birak(d: TemaDinleyici) {
        dinleyiciler.remove(d)
    }

    /** Re-reads the clock (and position) and tells listeners if the palette changed. */
    fun kontrolEt() {
        val yeni = sec()
        if (yeni == palet) return
        palet = yeni
        for (d in dinleyiciler.toList()) d.temaDegisti(yeni)
    }

    /** Pins a palette (previews); pass null to go back to the sun. */
    fun zorla(p: Palet?) {
        zorla = p
        kontrolEt()
    }

    private fun sec(): Palet = zorla ?: if (gunduzMu(simdi(), gecerliKonum())) Palet.GUNDUZ else Palet.GECE

    /** A usable fix: finite, in range, and not the 0/0 some stacks report before a real fix. */
    private fun gecerli(k: Konum?): Konum? = k?.takeIf {
        !it.enlem.isNaN() && !it.boylam.isNaN() && it.enlem in -90.0..90.0 && it.boylam in -180.0..180.0 &&
            !(it.enlem == 0.0 && it.boylam == 0.0)
    }
}

/**
 * The last known GPS position, for the theme only. Reads LocationManager.getLastKnownLocation —
 * no request, no listener, no write; GPS itself is run by the data layer (HizKaynagi), whose
 * fixes make this value current. Without the permission, or if the system refuses
 * (SecurityException, vendor surprises), the answer is null and the theme uses Istanbul.
 */
object SonKonum {
    fun oku(c: Context): Tema.Konum? {
        if (!VeriBaglayici.izinVar(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { Tema.Konum(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}
