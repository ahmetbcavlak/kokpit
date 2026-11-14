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
 *  - kur: registers the climate/outside-temperature receiver for the app's life (application context);
 *    asks for the location permission if it was never asked before.
 *  - every GPS speed also goes into [Hiz.yumusatici] (smoothed speed for the frame loop, see [HizYumusatici]);
 *    it is reset when GPS stops.
 *  - basla (onResume): starts GPS if permitted. The permission dialog pauses and resumes the Activity,
 *    so a fresh grant is picked up here.
 *  - dur (onPause): stops GPS (the climate receiver stays: values are current when the app returns).
 *  - birak (onDestroy): stops GPS and unregisters the receiver.
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
        klima = KlimaKaynagi(uyg).also { it.basla() }
        hiz = HizKaynagi(
            uyg.getSystemService(Context.LOCATION_SERVICE) as? LocationManager,
            { izinVar(uyg) },
            yumusatici = Hiz.yumusatici,
        )
        val izin = izinVar(uyg)
        Teshis.konumIzni = izin
        if (izin) return
        val tercih = uyg.getSharedPreferences(TERCIHLER, Context.MODE_PRIVATE)
        // Asked once per install. After a denial the app does not ask again on every start (the screens
        // show the calm hint R.string.konum_izni_ipucu when Teshis.konumIzni is false); the speed stays null.
        if (tercih.getBoolean(IZIN_SORULDU, false)) return
        tercih.edit().putBoolean(IZIN_SORULDU, true).apply()
        try {
            activity.requestPermissions(IZINLER, IZIN_ISTEGI)
        } catch (t: Throwable) {
            Teshis.hata("konum izni isteği", t)
        }
    }

    override fun basla() {
        hiz?.basla()
    }

    override fun dur() {
        hiz?.dur()
    }

    override fun birak() {
        hiz?.dur()
        klima?.dur()
        klima = null
        hiz = null
    }

    companion object {
        const val IZIN_ISTEGI = 2701
        internal const val TERCIHLER = "kokpit_veri"
        internal const val IZIN_SORULDU = "konum_izni_soruldu"

        /** FINE for GPS_PROVIDER; COARSE with it (lint CoarseFineLocation; one dialog on API 27). */
        val IZINLER = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        fun izinVar(c: Context): Boolean =
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
