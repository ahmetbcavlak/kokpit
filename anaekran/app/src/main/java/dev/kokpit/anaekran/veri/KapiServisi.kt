package dev.kokpit.anaekran.veri

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

/**
 * "Kokpit kapı uyarısı": reads the door/boot popup of com.hcn.autocan and writes
 * [AracDurumu.kapiAcik] / [AracDurumu.bagajAcik].
 *
 * Read only. It never clicks, types, gestures, takes a global action or closes the vendor's window
 * (that is 0.2's question). It writes no file; it only updates [AracVerisi] and keeps the last
 * [Teshis.KAPI_OLAY_SAYISI] com.hcn.autocan events in memory.
 *
 * What it sees: Android delivers window events of every app (no packageNames filter, because
 * TYPE_WINDOWS_CHANGED has no package and would be dropped — research decision 6). For other apps'
 * windows only the package name of the root node is looked at, then dropped; nothing of theirs is kept.
 *
 * Runs on its own, bound by the system, independent of the Activity's life cycle. Same process as the
 * app, so it writes through [AracVerisi.guncelle], which moves the write to the main looper (decision 8).
 * Fetching nodes is a blocking call into the other app, so that work runs on a worker thread.
 */
class KapiServisi : AccessibilityService() {

    private val yorumlayici = KapiYorumlayici()
    private var isci: HandlerThread? = null
    private var isciHandler: Handler? = null
    @Volatile private var taramaBekliyor = false
    /** Last values handed to AracVerisi (worker thread only); the write itself lands later on main. */
    /** Set in onDestroy: a scan still queued on the worker must not write after the "unknown". */
    @Volatile private var bitti = false
    private var sonKapi: Boolean? = null
    private var sonBagaj: Boolean? = null

    override fun onServiceConnected() {
        Teshis.kapiServisiBagli = true
        Teshis.kapiOlayi("servis bağlandı")
        // A popup already on screen is picked up at once. No popup leaves both flags unknown (null):
        // its absence says nothing about a door that was never seen open.
        taramaIste()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        try {
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val paket = e.packageName?.toString()
                    if (paket != KapiMetni.PAKET) return
                    // The event is recycled after this call: copy what is needed first.
                    val sinif = e.className?.toString()
                    val metinler: List<CharSequence?> = ArrayList<CharSequence?>(e.text).apply { add(e.contentDescription) }
                    val id = e.windowId
                    calistir {
                        yorumlayici.durumOlayi(paket, sinif, metinler, id)
                        Teshis.kapiOlayi("durum $id ${sinif.orEmpty()} \"${metinler.filterNotNull().joinToString(" | ")}\"")
                        yaz()
                    }
                    // Confirm through the window list (and read the node text if the event had none).
                    taramaIste()
                }
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> taramaIste()
            }
        } catch (t: Throwable) {
            Teshis.hata("kapı olayı", t)
        }
    }

    /** Coalesces bursts of TYPE_WINDOWS_CHANGED into one pass on the worker. */
    private fun taramaIste() {
        if (taramaBekliyor) return
        taramaBekliyor = true
        calistir {
            taramaBekliyor = false
            tara()
        }
    }

    /** Worker thread. */
    private fun tara() {
        val liste = ArrayList<KapiYorumlayici.Pencere>()
        val okunamayan = HashSet<Int>()
        val pencereler = try { windows } catch (t: Throwable) { Teshis.hata("getWindows", t); return }
        for (w in pencereler.orEmpty()) {
            try {
                val kok = w.root
                if (kok == null) { okunamayan += w.id; continue }
                try {
                    val paket = kok.packageName?.toString()
                    if (paket != KapiMetni.PAKET) continue
                    liste += KapiYorumlayici.Pencere(w.id, paket, kok.className?.toString(), gorunenMetin(kok))
                } finally {
                    kok.recycle()
                }
            } finally {
                w.recycle()
            }
        }
        val once = yorumlayici.ozet()
        yorumlayici.pencereler(liste, okunamayan)
        if (liste.isNotEmpty() || once != yorumlayici.ozet()) {
            Teshis.kapiOlayi("liste ${liste.joinToString(" ") { "${it.id}:\"${it.metinler.filterNotNull().joinToString(" | ")}\"" }.ifEmpty { "boş" }} → ${yorumlayici.ozet()}")
        }
        yaz()
    }

    private fun yaz() {
        if (bitti) return
        // Values are taken here, on the worker; the block only hands them over (it runs later on main).
        val kapi = yorumlayici.kapiAcik()
        val bagaj = yorumlayici.bagajAcik()
        if (kapi == sonKapi && bagaj == sonBagaj) return
        sonKapi = kapi
        sonBagaj = bagaj
        AracVerisi.guncelle {
            kapiAcik(kapi)
            bagajAcik(bagaj)
        }
    }

    /**
     * Text and content descriptions in one com.hcn.autocan window (the root of an overlay is usually a
     * bare layout; the words sit in its children). Bounded like Keşif's: depth 6, 60 nodes; every child recycled.
     */
    private fun gorunenMetin(kok: AccessibilityNodeInfo): List<CharSequence?> {
        val sonuc = ArrayList<CharSequence?>()
        var butce = 60
        fun gez(n: AccessibilityNodeInfo, derinlik: Int) {
            if (butce-- <= 0) return
            n.text?.let { sonuc += it.toString() }
            n.contentDescription?.let { sonuc += it.toString() }
            if (derinlik >= 6) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                try { gez(c, derinlik + 1) } finally { c.recycle() }
            }
        }
        gez(kok, 0)
        return sonuc
    }

    /** All interpretation runs on one thread, in order. */
    private fun calistir(blok: () -> Unit) {
        val sarili = Runnable {
            try { blok() } catch (t: Throwable) { Teshis.hata("kapı servisi", t) }
        }
        yurutucu?.let { it.execute(sarili); return }
        val h = isciHandler ?: HandlerThread("kokpit-kapi").also { it.start(); isci = it }
            .let { Handler(it.looper) }.also { isciHandler = it }
        h.post(sarili)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        // Switched off (or the app updated): the door state is no longer known — not "closed".
        bitti = true
        Teshis.kapiServisiBagli = false
        Teshis.kapiOlayi("servis durdu")
        calistir { yorumlayici.sifirla() }
        AracVerisi.guncelle {
            kapiAcik(null)
            bagajAcik(null)
        }
        isci?.quitSafely()
        isci = null
        isciHandler = null
        super.onDestroy()
    }

    companion object {
        /** Tests run the worker's jobs inline. null = own HandlerThread. */
        @Volatile internal var yurutucu: Executor? = null
    }
}
