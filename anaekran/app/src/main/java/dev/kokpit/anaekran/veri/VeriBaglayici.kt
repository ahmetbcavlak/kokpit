package dev.kokpit.anaekran.veri

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import dev.kokpit.anaekran.Baglayici
import dev.kokpit.anaekran.Katmanlar

/**
 * The data layer's hook into MainActivity (task 4 adds it to Baglayicilar.liste()).
 *
 *  - kur: asks for the location permission once if it is missing; prepares the sources.
 *  - basla (onResume): registers the climate/outside-temperature receiver, starts GPS if permitted.
 *    The permission dialog pauses and resumes the Activity, so a fresh grant is picked up here.
 *  - dur (onPause): stops GPS, unregisters the receiver.
 *  - birak (onDestroy): the same, for good.
 *
 * The door service ([KapiServisi]) is not started here: the system binds it on its own once the user
 * has switched it on, independent of the Activity. Nothing here sends anything to the car.
 */
class VeriBaglayici : Baglayici {

    private var klima: KlimaKaynagi? = null
    private var hiz: HizKaynagi? = null

    /** For tests. */
    internal val klimaKaynagi: KlimaKaynagi? get() = klima
    internal val hizKaynagi: HizKaynagi? get() = hiz

    override fun kur(activity: Activity, katmanlar: Katmanlar) = hazirla(activity)

    /** [kur] without the layer tree (tests). */
    internal fun hazirla(activity: Activity) {
        val uyg = activity.applicationContext
        klima = KlimaKaynagi(uyg)
        hiz = HizKaynagi(uyg.getSystemService(Context.LOCATION_SERVICE) as? LocationManager, { izinVar(uyg) })
        val izin = izinVar(uyg)
        Teshis.konumIzni = izin
        if (!izin) {
            // Once per Activity creation; a denial leaves the speed null, nothing else changes.
            try {
                activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), IZIN_ISTEGI)
            } catch (t: Throwable) {
                Teshis.hata("konum izni isteği", t)
            }
        }
    }

    override fun basla() {
        klima?.basla()
        hiz?.basla()
    }

    override fun dur() {
        hiz?.dur()
        klima?.dur()
    }

    override fun birak() {
        dur()
        klima = null
        hiz = null
    }

    companion object {
        const val IZIN_ISTEGI = 2701

        fun izinVar(c: Context): Boolean =
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
