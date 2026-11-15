package dev.kokpit.anaekran.veri

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

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
 * windows only the package name of the root node is looked at (once per window id, then cached),
 * and dropped; nothing of theirs is kept.
 *
 * Configuration (res/xml/kapi_servisi.xml) is Keşif 0.6's, which caught the popup on the car. No
 * flagIncludeNotImportantViews: it widens the node tree; if the car shows the popup text cannot be
 * read, 0.2 adds it.
 *
 * 0.2.2 (K-116): once the vendor's system properties can be read ([KapiOzellikleri.birincil]), they are
 * the door source and this service writes no door state any more; it keeps interpreting and logging
 * to [Teshis] only. While they cannot be read, it writes exactly as in 0.2, with one fix: a popup the
 * user closed with its X gives "unknown", not "closed" (TYPE_VIEW_CLICKED, see [KapiYorumlayici]).
 * Click events come from every app as well (no packageNames); anything not com.hcn.autocan's is dropped
 * at once, on the binder thread, before any of its content is looked at.
 *
 * Runs on its own, bound by the system, independent of the Activity's life cycle. Same process as the
 * app, so it writes through [AracVerisi.guncelle], which moves the write to the main looper (decision 8).
 * Fetching nodes is a blocking call into the other app, so that work runs on one worker thread, in order.
 */
class KapiServisi : AccessibilityService() {

    private val yorumlayici = KapiYorumlayici()
    private var isci: HandlerThread? = null
    private var isciHandler: Handler? = null

    /** Scan requests and the newest request a started scan already covers (worker side). */
    private val istenen = AtomicInteger(0)
    private var karsilanan = 0

    /** Window id → package of the root node, for windows seen before (worker thread only). */
    private val paketler = HashMap<Int, String>()

    /** This instance's generation; a write from an older (destroyed) instance is dropped on main. */
    private var nesil = 0

    /** Last values handed to AracVerisi (worker thread only); the write itself lands later on main. */
    private var sonKapi: Boolean? = null
    private var sonBagaj: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        nesil = ++etkinNesil
        if (yurutucu == null) {
            val t = HandlerThread("kokpit-kapi").also { it.start() }
            isci = t
            isciHandler = Handler(t.looper)
        }
    }

    /** Public so tests can call it as the system does. */
    public override fun onServiceConnected() {
        sifirlayici = kendiSifirlayicisi
        Teshis.kapiServisiBagli = true
        Teshis.kapiOlayi("servis bağlandı")
        // Checked when the block runs (main): the properties may have taken over meanwhile.
        AracVerisi.guncelle { if (!KapiOzellikleri.birincil) kapiKaynagi(KapiKaynagi.ERISILEBILIRLIK) }
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
                        // Not confirmed by the list yet: look again after the time-out (decision 7).
                        if (yorumlayici.dogrulanmamisVar()) gecikmeliTara()
                    }
                    // Always a pass after this event: confirms it and reads node text the event lacked.
                    taramaIste()
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val paket = e.packageName?.toString()
                    if (paket != KapiMetni.PAKET) return
                    // Copied before the event is recycled: window id, class and text only (no getSource()).
                    val id = e.windowId
                    val sinif = e.className?.toString().orEmpty()
                    val metin = (ArrayList<CharSequence?>(e.text).apply { add(e.contentDescription) })
                        .filterNotNull().joinToString(" | ")
                    calistir {
                        yorumlayici.tiklama(paket, id)
                        Teshis.kapiOlayi("tıklama $id $sinif \"$metin\"")
                    }
                    taramaIste()
                }
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> taramaIste()
                else -> Unit // only the three types above are subscribed (res/xml/kapi_servisi.xml)
            }
        } catch (t: Throwable) {
            Teshis.hata("kapı olayı", t)
        }
    }

    /**
     * Asks for a pass. Bursts collapse: a pass covers every request made before it started, and a request
     * made after a pass started always gets a pass of its own (counters, not a single "pending" flag).
     */
    private fun taramaIste() {
        val no = istenen.incrementAndGet()
        calistir {
            if (no <= karsilanan) return@calistir
            karsilanan = istenen.get()
            tara()
        }
    }

    private fun gecikmeliTara() {
        val ms = KapiYorumlayici.ZAMAN_ASIMI_MS + 500L
        val r = Runnable { taramaIste() }
        gecikmeli?.let { it(ms, r); return }
        isciHandler?.postDelayed(r, ms)
    }

    /** Worker thread. */
    private fun tara() {
        val liste = ArrayList<KapiYorumlayici.Pencere>()
        val okunamayan = HashSet<Int>()
        val simdikiler = HashSet<Int>()
        val pencereler = try { windows } catch (t: Throwable) { Teshis.hata("getWindows", t); return }
        for (w in pencereler.orEmpty()) {
            try {
                val id = w.id
                simdikiler += id
                val bilinen = paketler[id]
                // A window known to be another app's: its root is not fetched again.
                if (bilinen != null && bilinen != KapiMetni.PAKET) continue
                val kok = w.root
                if (kok == null) { okunamayan += id; continue }
                try {
                    val paket = kok.packageName?.toString() ?: continue
                    paketler[id] = paket
                    if (paket != KapiMetni.PAKET) continue
                    liste += KapiYorumlayici.Pencere(id, paket, kok.className?.toString(), gorunenMetin(kok))
                } finally {
                    kok.recycle()
                }
            } finally {
                w.recycle()
            }
        }
        paketler.keys.retainAll(simdikiler)
        val once = yorumlayici.ozet()
        yorumlayici.pencereler(liste, okunamayan)
        if (liste.isNotEmpty() || once != yorumlayici.ozet()) {
            Teshis.kapiOlayi("liste ${liste.joinToString(" ") { "${it.id}:\"${it.metinler.filterNotNull().joinToString(" | ")}\"" }.ifEmpty { "boş" }} → ${yorumlayici.ozet()}")
        }
        yaz()
    }

    private fun yaz() {
        val n = nesil
        if (n != etkinNesil) return
        // The properties are the source: this service only logs (checked again on main below).
        if (KapiOzellikleri.birincil) return
        // Values are taken here, on the worker; the block only hands them over (it runs later on main).
        val kapi = yorumlayici.kapiAcik()
        val bagaj = yorumlayici.bagajAcik()
        if (kapi == sonKapi && bagaj == sonBagaj) return
        sonKapi = kapi
        sonBagaj = bagaj
        AracVerisi.guncelle {
            // Checked again on main: the service may have been destroyed after the post.
            if (n == etkinNesil && !KapiOzellikleri.birincil) {
                kapiAcik(kapi)
                bagajAcik(bagaj)
                kapiKaynagi(KapiKaynagi.ERISILEBILIRLIK)
            }
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
            butce--
            n.text?.let { sonuc += it.toString() }
            n.contentDescription?.let { sonuc += it.toString() }
            if (derinlik >= 6) return
            for (i in 0 until n.childCount) {
                if (butce <= 0) break
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
        isciHandler?.post(sarili)
    }

    /**
     * The diagnostics panel's "Kapı uyarısını sıfırla": forgets this instance's in-memory records and
     * last written values on the worker, then writes "unknown" in order after any write already posted.
     * Memory only — no window, node or system call.
     */
    private val kendiSifirlayicisi = Runnable {
        calistir {
            yorumlayici.sifirla()
            sonKapi = null
            sonBagaj = null
            Teshis.kapiOlayi("uygulama içi sıfırlama")
            val n = nesil
            AracVerisi.guncelle {
                if (n == etkinNesil && !KapiOzellikleri.birincil) {
                    kapiAcik(null)
                    bagajAcik(null)
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        // Switched off (or the app updated): the door state is no longer known — not "closed".
        // A newer generation makes every write still queued from this instance a no-op.
        etkinNesil++
        if (sifirlayici === kendiSifirlayicisi) sifirlayici = null
        Teshis.kapiServisiBagli = false
        Teshis.kapiOlayi("servis durdu")
        calistir { yorumlayici.sifirla() }
        AracVerisi.guncelle {
            // With the properties as source the door state does not depend on this service.
            if (!KapiOzellikleri.birincil) {
                kapiAcik(null)
                bagajAcik(null)
                kapiKaynagi(KapiKaynagi.YOK)
            }
        }
        isci?.quitSafely()
        isci = null
        isciHandler = null
        super.onDestroy()
    }

    companion object {
        /** Tests run the worker's jobs inline. null = own HandlerThread. */
        @Volatile internal var yurutucu: Executor? = null
        /** Tests catch the delayed re-scan here. null = the worker's postDelayed. */
        @Volatile internal var gecikmeli: ((Long, Runnable) -> Unit)? = null
        /** Generation of the live service instance (changes in onCreate and onDestroy). */
        @Volatile internal var etkinNesil = 0

        /** The connected instance's reset (a Runnable, not the Service itself: no static Context). */
        @Volatile private var sifirlayici: Runnable? = null

        /**
         * Clears the door/boot state inside the app (any thread): the running service's records, if it
         * is connected, and the shared state's kapiAcik/bagajAcik/kapilar (→ null, unknown). With the
         * properties as source, their next pass (≤ 250 ms while the app is in front) writes the current
         * values again. Sends nothing.
         */
        fun uygulamaIciSifirla() {
            sifirlayici?.run()
            AracVerisi.guncelle {
                kapiAcik(null)
                bagajAcik(null)
                kapilar(AracDurumu.KAPILAR_BILINMIYOR)
                // Set where the nulls land (main), so the properties' rewrite always comes after them.
                KapiOzellikleri.yenidenYaz = true
            }
        }
    }
}
