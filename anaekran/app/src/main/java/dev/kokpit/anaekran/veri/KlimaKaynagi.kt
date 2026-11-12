package dev.kokpit.anaekran.veri

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Listens to the vendor's climate and outside-temperature broadcasts while the app is in front.
 *
 * Registered at run time, not in the manifest: from API 26 an implicit broadcast does not reach a
 * manifest receiver (research decision 15). Only receives: it never sends a broadcast or binds the
 * vendor's climate service. The receiver must accept broadcasts from other apps (the vendor app),
 * so any app could send a fake value; the values are only shown, never acted on.
 */
class KlimaKaynagi(private val baglam: Context) {

    private var kayitli = false

    private val alici = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent != null) isle(intent)
        }
    }

    val kayitliMi: Boolean get() = kayitli

    fun basla() {
        if (kayitli) return
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
            if (yapiskan != null) isle(yapiskan)
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
