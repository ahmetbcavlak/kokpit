package dev.kokpit.anaekran.tema

import kotlin.math.PI
import kotlin.math.acos
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset for one place and day, with NOAA's simplified solar-position formulas
 * ("General Solar Position Calculations", NOAA Global Monitoring Laboratory: fractional year,
 * equation of time, declination, then the hour angle at a zenith of 90.833° — the sun's upper
 * limb on the horizon with standard refraction). No library, no time-zone data: the results
 * are instants (epoch ms), so they compare directly with System.currentTimeMillis()
 * (only java.time.LocalDate for the day of the year).
 * Accuracy of the simplified series is about a minute at mid latitudes, far inside what a
 * day/night palette needs. Pure functions, any thread.
 *
 * Edge cases never throw: near the poles the hour-angle argument leaves [-1, 1] — above 1 the
 * sun does not rise that day (polar night), below -1 it does not set (midnight sun).
 */
object Gunes {

    /** One day at one place. [dogum]/[batim] are null when the sun does not cross the horizon. */
    data class Gun(val dogum: Long?, val batim: Long?, val tur: Tur)

    enum class Tur { NORMAL, KUTUP_GECESI, GECE_YARISI_GUNESI }

    private const val GUN_MS = 86_400_000L
    private const val ZENIT = 90.833

    /**
     * The sunrise/sunset of the local (solar) day that contains [anMs], at latitude [enlem]
     * (north +) and longitude [boylam] (east +). The day is the UTC date of the local mean solar
     * time, so for Istanbul (≈ UTC+2 solar) both events fall on the same calendar day as the clock.
     */
    fun gun(anMs: Long, enlem: Double, boylam: Double): Gun {
        val lat = enlem.coerceIn(-89.9, 89.9)
        val lon = ((boylam + 540.0) % 360.0) - 180.0
        // Midnight (UTC) of the local solar date.
        val yerel = anMs + (lon / 15.0 * 3_600_000.0).toLong()
        val gunNo = Math.floorDiv(yerel, GUN_MS)
        val gece = gunNo * GUN_MS
        val tarih = LocalDate.ofEpochDay(gunNo) // java.time: API 26, the unit runs 27
        val yilUzunlugu = tarih.lengthOfYear().toDouble()

        // Fractional year (radians) at local noon: UTC hour 12 − lon/15, so (hour − 12)/24 = −lon/360.
        val g = 2.0 * PI / yilUzunlugu * (tarih.dayOfYear - 1 - lon / 360.0)
        val denklem = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val egim = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)

        val latR = Math.toRadians(lat)
        val arg = cos(Math.toRadians(ZENIT)) / (cos(latR) * cos(egim)) - tan(latR) * tan(egim)
        if (arg.isNaN()) return Gun(null, null, Tur.KUTUP_GECESI)
        if (arg > 1.0) return Gun(null, null, Tur.KUTUP_GECESI)
        if (arg < -1.0) return Gun(null, null, Tur.GECE_YARISI_GUNESI)
        val ha = Math.toDegrees(acos(arg))

        // Minutes after 00:00 UTC of that date.
        val dogumDk = 720.0 - 4.0 * (lon + ha) - denklem
        val batimDk = 720.0 - 4.0 * (lon - ha) - denklem
        return Gun(gece + (dogumDk * 60_000.0).toLong(), gece + (batimDk * 60_000.0).toLong(), Tur.NORMAL)
    }

    /**
     * Day = sunrise ≤ [anMs] < sunset (the real rise and set, not civil twilight: in the ~30 min
     * of twilight the sky is already too dark for the light palette's bright scene in a car).
     * Polar night is always night, midnight sun always day.
     */
    fun gunduzMu(anMs: Long, enlem: Double, boylam: Double): Boolean {
        val g = gun(anMs, enlem, boylam)
        return when (g.tur) {
            Tur.KUTUP_GECESI -> false
            Tur.GECE_YARISI_GUNESI -> true
            Tur.NORMAL -> anMs >= g.dogum!! && anMs < g.batim!!
        }
    }
}
