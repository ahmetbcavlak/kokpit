package dev.kokpit.anaekran.veri

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Listens to the vendor's climate and outside-temperature broadcasts for the whole life of the app
 * (registered in VeriBaglayici.kur on the application context, removed in birak), so the values are
 * current again at once when the Activity comes back from the background. Cheap: a broadcast only
 * arrives when the climate changes.
 *
 * Registered at run time, not in the manifest: from API 26 an implicit broadcast does not reach a
 * manifest receiver (research decision 15). Only receives: it never sends a broadcast or binds the
 * vendor's climate service. The receiver must accept broadcasts from other apps (the vendor app),
 * so any app could send a fake value; the values are only shown, never acted on.
 */
class KlimaKaynagi(private val baglam: Context) {

    private var kayitli = false
    /**
     * The sticky intent registerReceiver returned and we already handled ("action|raw extras").
     * Android also delivers it to onReceive (initial sticky); that one copy is skipped. Matched by
     * content, not only by isInitialStickyBroadcast, so it works whatever the delivery path: skipping a
     * repeat with the very same extras loses nothing, the state is the same.
     */
    private var yapiskanIz: String? = null
    /** Last intent handled by onReceive, in case the sticky copy arrives there before registerReceiver returns. */
    private var sonAlinanIz: String? = null

    private val alici = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent == null) return
            val iz = izi(intent)
            val bekleyen = yapiskanIz
            if (bekleyen != null) {
                yapiskanIz = null
                if (bekleyen == iz) return
            }
            sonAlinanIz = iz
            isle(intent)
        }
    }

    private fun izi(i: Intent) = "${i.action}|${KlimaCozucu.duzle(i.extras)}"

    val kayitliMi: Boolean get() = kayitli

    fun basla() {
        if (kayitli) return
        sonAlinanIz = null
        val filtre = IntentFilter().apply { KlimaCozucu.EYLEMLER.forEach { addAction(it) } }
        try {
            val yapiskan = if (Build.VERSION.SDK_INT >= 33) {
                baglam.registerReceiver(alici, filtre, Context.RECEIVER_EXPORTED)
            } else {
                baglam.registerReceiver(alici, filtre)
            }
            kayitli = true
            Teshis.yayinAlicisi = "kayıtlı"
            Teshis.yapiskanGeldi = yapiskan != null
            // A sticky broadcast (if the vendor sends it that way) is the current state: use it at once.
            if (yapiskan != null) {
                val iz = izi(yapiskan)
                if (iz != sonAlinanIz) {
                    yapiskanIz = iz
                    isle(yapiskan)
                }
            }
        } catch (t: Throwable) {
            Teshis.yayinAlicisi = "kayıt hatası"
            Teshis.hata("klima alıcısı", t)
        }
    }

    fun dur() {
        if (!kayitli) return
        try {
            baglam.unregisterReceiver(alici)
        } catch (t: Throwable) {
            Teshis.hata("klima alıcısı kaldırma", t)
        }
        kayitli = false
        yapiskanIz = null
        Teshis.yayinAlicisi = "kayıtlı değil"
    }

    companion object {
        /** One broadcast → contract. Runs on the main thread; a bad vendor extra must never crash the app. */
        fun isle(intent: Intent) {
            try {
                val b = intent.extras
                when (intent.action) {
                    KlimaCozucu.EYLEM_KLIMA -> {
                        Teshis.klimaYayini(KlimaCozucu.duzle(b))
                        if (b == null) return
                        val k = KlimaCozucu.klima(b)
                        val dis = KlimaCozucu.disSicaklikKlimadan(b)
                        if (k == null && dis == null) return
                        AracVerisi.guncelle {
                            if (k != null) klima(k)
                            if (dis != null) disSicaklik(dis.deger)
                        }
                    }
                    KlimaCozucu.EYLEM_DIS_SICAKLIK, KlimaCozucu.EYLEM_DIS_SICAKLIK_2 -> {
                        Teshis.disSicaklikYayini("${intent.action}: ${KlimaCozucu.duzle(b)}")
                        val dis = KlimaCozucu.disSicaklikYayini(b) ?: return
                        AracVerisi.guncelle { disSicaklik(dis.deger) }
                    }
                }
            } catch (t: Throwable) {
                Teshis.hata("klima yayını", t)
            }
        }
    }
}
