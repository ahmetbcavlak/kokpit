package dev.kokpit.anaekran.veri

import android.os.SystemClock
import java.util.Locale

/** The words of the vendor's door popup, in one place. */
object KapiMetni {

    /** The vendor app that shows the door/boot popup (CanDoorWindow). */
    const val PAKET = "com.hcn.autocan"

    /** Word stems, compared after [sade]: "kapı", "kapılar", "kapısı" → kapi…; "bagaj" → bagaj…; "açık" → acik…. */
    const val KAPI_KOKU = "kapi"
    const val BAGAJ_KOKU = "bagaj"
    const val ACIK_KOKU = "acik"

    private val TR = Locale("tr")
    private val KELIME = Regex("""[\p{L}\p{N}]+""")

    /**
     * "KAPI AÇIK", "Kapı açık", "kapi acik", "KAPİ AÇIK" all become "kapi acik". Turkish lower-casing
     * first (I -> ı, İ -> i), then ı/ç/ş/ğ/ö/ü folded, so a vendor text without Turkish letters still matches.
     */
    fun sade(s: CharSequence): String {
        val k = s.toString().lowercase(TR)
        val b = StringBuilder(k.length)
        var bosluk = false
        for (ch in k) {
            val c = when (ch) {
                'ı' -> 'i'; 'ç' -> 'c'; 'ş' -> 's'; 'ğ' -> 'g'; 'ö' -> 'o'; 'ü' -> 'u'
                '̇' -> continue // combining dot left by some İ forms
                else -> ch
            }
            if (c.isWhitespace()) {
                if (!bosluk && b.isNotEmpty()) b.append(' ')
                bosluk = true
            } else {
                b.append(c)
                bosluk = false
            }
        }
        return b.toString().trimEnd()
    }

    /**
     * One text, word by word: a door word (kapi…) and an "açık" word in the same text means a door is
     * open; a boot word (bagaj…) and "açık" the boot. "Kapı kapalı" has no "açık" and says nothing.
     * A door word right after "bagaj" is the boot lid ("bagaj kapısı açık"), not a door.
     */
    private fun oku(metin: String): Okunan {
        val k = KELIME.findAll(sade(metin)).map { it.value }.toList()
        if (k.none { it.startsWith(ACIK_KOKU) }) return Okunan(kapi = false, bagaj = false)
        var kapi = false
        var bagaj = false
        for ((i, w) in k.withIndex()) {
            if (w.startsWith(BAGAJ_KOKU)) bagaj = true
            else if (w.startsWith(KAPI_KOKU) && !(i > 0 && k[i - 1].startsWith(BAGAJ_KOKU))) kapi = true
        }
        return Okunan(kapi, bagaj)
    }

    /**
     * What the texts of one window say: each text on its own, and all of them joined once more, so words
     * split over several nodes (["Kapı", "açık"]) still match. Door and boot are independent.
     */
    fun sinifla(metinler: Iterable<CharSequence?>): Okunan {
        val dolu = metinler.filterNotNull().map { it.toString() }.filter { it.isNotBlank() }
        var sonuc = Okunan(kapi = false, bagaj = false)
        for (m in dolu) sonuc += oku(m)
        if (dolu.size > 1) sonuc += oku(dolu.joinToString(" "))
        return sonuc
    }

    data class Okunan(val kapi: Boolean, val bagaj: Boolean) {
        val bos: Boolean get() = !kapi && !bagaj
        operator fun plus(o: Okunan) = Okunan(kapi || o.kapi, bagaj || o.bagaj)
    }
}

/**
 * Door and boot state from com.hcn.autocan's windows. Pure logic, one thread (the service's worker).
 *
 * Each flag on its own: null until a popup with its words has been seen, true while such a popup is
 * on screen, false once it has closed. The door popup says nothing about the boot and the reverse,
 * so the absence of a popup never makes an unseen flag "closed".
 *
 * Sources (research decisions 6 and 7):
 *  1. TYPE_WINDOW_STATE_CHANGED from com.hcn.autocan: the event's own text and class name. Opens a
 *     record for the event's window id. If that id was in the last window list (even without text,
 *     e.g. the list pass ran just before the event), the record counts as listed at once.
 *  2. TYPE_WINDOWS_CHANGED → getWindows(): the com.hcn.autocan windows on screen and their text.
 *     A window that was listed and is no longer listed has closed → its record goes.
 *
 * Time-out only for records that were NEVER seen in the window list (popup not interactive,
 * decision 7): from [ZAMAN_ASIMI_MS] after their last event, the next pass drops them. A record
 * confirmed by the list never times out, so a door that stays open is not called closed by a timer
 * (Keşif 0.6 saw the popup open and close through the window list on the car).
 *
 * Closed by the user (0.2.2 fix): the popup has a close (X) button, and a popup the user closed says
 * nothing about the door, which may still be open. A com.hcn.autocan TYPE_VIEW_CLICKED in a window
 * ([tiklama]) at most [KAPATMA_MS] before that window leaves the list, or while its leaving is
 * still being held (below), counts as "closed by the user": its flags go back to unknown (null),
 * not to closed (false), so no "Kapı kapandı." follows.
 * The criterion is the click's window and time only: Keşif 0.7's UI dump of the button's view id was
 * not carried into the code or the guide, so no id or position is matched (the click's class and text
 * go to the diagnostics, for a narrower criterion later). A click whose window id is unknown (-1 or a
 * window not seen) counts for every door window of the package. Any click is enough: a popup that
 * closes by itself within 1.5 s of an unrelated click in it becomes "unknown", the safe side.
 *
 * Order of click and leaving (R1 ÖNEMLİ-2): on API 27 View.performClick sends TYPE_VIEW_CLICKED
 * after the onClick listener, so the window may already be gone from the list when the click is
 * interpreted. A listed window that leaves is therefore not decided at once: its record is HELD
 * for [kapanisBeklemesi] (still counted as open) and the service schedules another pass after that
 * time ([kapanisBekleniyor]); a click that comes in meanwhile still makes it "closed by the user".
 * The click's time is the time the event arrived (the service takes it on the event's own thread
 * and passes it in), not the time the worker got to it. If the vendor's X removes the window
 * before the click event is even sent (View detached first), no click comes at all: then the
 * result is "closed", as in 0.2 — Teshis' event list shows whether "tıklama" lines appear.
 *
 * Decision (R1 KÜÇÜK-10): after the user's X our own door warning goes too (kapiAcik null: the park
 * HUD shows "—"), although the door may still be open. Why: on this path the popup is the only
 * evidence; with it gone the state is unknown, and K-74 shows only verified data — "unknown" is
 * neither "open" nor "closed". The user dismissed the vendor's warning on purpose and is not given a
 * second one for the same popup. Cost if wrong: a door left open after an X is not warned about
 * until the vendor shows its popup again. Not on the properties path, where the X changes nothing.
 */
class KapiYorumlayici(
    private val saat: () -> Long = { SystemClock.elapsedRealtime() },
    /** How long a listed window that left is held before it counts as closed (tests of other rules pass 0). */
    private val kapanisBeklemesi: Long = KAPANIS_BEKLEME_MS,
) {

    /** A window as the service saw it: id, package, class name, texts (node text and descriptions). */
    data class Pencere(val id: Int, val paket: String?, val sinif: String?, val metinler: List<CharSequence?>)

    private class Kayit(var okunan: KapiMetni.Okunan, var sinif: String, var listede: Boolean, var sonOlay: Long) {
        /** When the listed window was first missing from the list ([YOK] while it is on screen). */
        var kayboldu: Long = YOK
    }

    private val kayitlar = LinkedHashMap<Int, Kayit>()
    private val listeDisiSayilan = HashSet<Int>()
    /** com.hcn.autocan window ids of the last list pass, with or without text. */
    private var sonListe: Set<Int> = emptySet()
    private var kapiGoruldu = false
    private var bagajGoruldu = false
    /** Window id → time of the last com.hcn.autocan click in it; [HER_PENCERE] for a click of unknown window. */
    private val tiklamalar = HashMap<Int, Long>()

    /** Door: true = a "Kapı açık" popup is open, false = it was seen and has closed, null = never seen. */
    fun kapiAcik(): Boolean? = when {
        kayitlar.values.any { it.okunan.kapi } -> true
        kapiGoruldu -> false
        else -> null
    }

    /** Boot, the same way. */
    fun bagajAcik(): Boolean? = when {
        kayitlar.values.any { it.okunan.bagaj } -> true
        bagajGoruldu -> false
        else -> null
    }

    /** Some record waits for the window list to confirm it (the service then schedules a later pass). */
    fun dogrulanmamisVar(): Boolean = kayitlar.values.any { !it.listede }

    /** Some listed window has left and is held (the service schedules a pass after [kapanisBeklemesi]). */
    fun kapanisBekleniyor(): Boolean = kayitlar.values.any { it.kayboldu != YOK }

    /** A door/boot record exists: a popup is (or was just) on screen. The service subscribes to clicks only then. */
    fun kayitVar(): Boolean = kayitlar.isNotEmpty()

    /** The hold, for the service's later pass. */
    val bekleme: Long get() = kapanisBeklemesi

    /** This interpreter's clock (any thread): the service stamps a click with it on arrival. */
    fun simdi(): Long = saat()

    private fun kaydet(id: Int, okunan: KapiMetni.Okunan, sinif: String?, listede: Boolean, olay: Boolean) {
        if (okunan.bos) return
        if (okunan.kapi) kapiGoruldu = true
        if (okunan.bagaj) bagajGoruldu = true
        val simdi = saat()
        val k = kayitlar[id]
        if (k == null) {
            kayitlar[id] = Kayit(okunan, sinif.orEmpty(), listede, simdi)
        } else {
            k.okunan = okunan // the same window may change its words (door → boot)
            if (sinif != null) k.sinif = sinif
            if (listede) k.listede = true
            if (olay) k.sonOlay = simdi
        }
    }

    /**
     * TYPE_WINDOW_STATE_CHANGED. Returns false when the event is not com.hcn.autocan's (ignored).
     * Text without door/boot words does not clear a record: the same window may have been read better before.
     */
    fun durumOlayi(paket: CharSequence?, sinif: CharSequence?, metinler: List<CharSequence?>, pencereId: Int): Boolean {
        if (paket?.toString() != KapiMetni.PAKET) return false
        kaydet(pencereId, KapiMetni.sinifla(metinler), sinif?.toString(), listede = pencereId in sonListe, olay = true)
        return true
    }

    /**
     * TYPE_VIEW_CLICKED at [an] (this interpreter's clock, taken when the event arrived). Returns
     * false when the event is not com.hcn.autocan's (ignored). Only the time is kept; the record's
     * flags change when (and if) the window then leaves the list.
     */
    fun tiklama(paket: CharSequence?, pencereId: Int, an: Long = saat()): Boolean {
        if (paket?.toString() != KapiMetni.PAKET) return false
        val bilinen = pencereId >= 0 && (pencereId in kayitlar || pencereId in sonListe)
        val anahtar = if (bilinen) pencereId else HER_PENCERE
        tiklamalar[anahtar] = maxOf(an, tiklamalar[anahtar] ?: Long.MIN_VALUE)
        return true
    }

    /**
     * A click in window [id] (or of unknown window) from [KAPATMA_MS] before the window left
     * ([kayboldu]) up to [simdi] (a click that came while the leaving was held counts too).
     */
    private fun tiklandi(id: Int, kayboldu: Long, simdi: Long): Boolean =
        listOfNotNull(tiklamalar[id], tiklamalar[HER_PENCERE]).any { it in (kayboldu - KAPATMA_MS)..simdi }

    /**
     * The result of one getWindows() pass. [okunamayan]: ids whose root node was briefly not
     * available; their records are kept as they are (not taken as closed).
     */
    fun pencereler(liste: List<Pencere>, okunamayan: Set<Int> = emptySet()) {
        val gorulen = HashSet<Int>()
        for (p in liste) {
            if (p.paket != KapiMetni.PAKET) continue
            gorulen += p.id
            kayitlar[p.id]?.let {
                it.listede = true
                it.kayboldu = YOK // back in the list: not leaving after all
            }
            kaydet(p.id, KapiMetni.sinifla(p.metinler), p.sinif, listede = true, olay = false)
        }
        sonListe = gorulen + sonListe.filter { it in okunamayan }
        val simdi = saat()
        val it = kayitlar.entries.iterator()
        while (it.hasNext()) {
            val (id, k) = it.next()
            if (id in gorulen || id in okunamayan) continue
            if (k.listede && k.kayboldu == YOK) k.kayboldu = simdi
            when {
                k.listede && tiklandi(id, k.kayboldu, simdi) -> {
                    // Closed by the user (X): the door/boot it showed is unknown now, not closed.
                    it.remove()
                    if (k.okunan.kapi) kapiGoruldu = false
                    if (k.okunan.bagaj) bagajGoruldu = false
                    Teshis.kullaniciKapatti++
                    Teshis.kapiOlayi("pencere $id kullanıcı kapattı → bilinmiyor")
                }
                // Was on screen, is gone by itself (no click during the hold): closed.
                k.listede && simdi - k.kayboldu >= kapanisBeklemesi -> it.remove()
                k.listede -> Unit // held: a click may still come (the service looks again)
                simdi - k.sonOlay >= ZAMAN_ASIMI_MS -> {
                    it.remove()
                    Teshis.kapiZamanAsimi++
                }
                listeDisiSayilan.add(id) -> Teshis.listeDisiPencere++
            }
        }
        // Clicks are only kept for windows still on screen or held (and the package-wide one while it
        // can still count for a held window).
        tiklamalar.entries.removeAll { (id, t) ->
            if (id == HER_PENCERE) simdi - t > KAPATMA_MS + kapanisBeklemesi else id !in gorulen && id !in okunamayan && id !in kayitlar
        }
    }

    /** Service stopped: nothing is known any more. */
    fun sifirla() {
        kayitlar.clear()
        listeDisiSayilan.clear()
        sonListe = emptySet()
        kapiGoruldu = false
        bagajGoruldu = false
        tiklamalar.clear()
    }

    /** For diagnostics: "id:kapı/bagaj:sınıf:listede". */
    fun ozet(): String = kayitlar.entries.joinToString(" ") { (id, k) ->
        val ne = listOfNotNull("kapı".takeIf { k.okunan.kapi }, "bagaj".takeIf { k.okunan.bagaj }).joinToString("+")
        "$id:$ne:${k.sinif}:${if (k.listede) "listede" else "olaydan"}"
    }.ifEmpty { "açık pencere yok" }

    companion object {
        /** Unconfirmed (never listed) records go this long after their last event. */
        const val ZAMAN_ASIMI_MS = 10_000L
        /** A window that leaves the list this soon after a click in it was closed by the user. */
        const val KAPATMA_MS = 1_500L
        /**
         * A listed window that left is held this long before it counts as closed (R1 ÖNEMLİ-2: 300–500 ms).
         * 400 ms: a click event sent after the window went needs one binder hop and the worker's queue;
         * the "Kapı kapandı." comes this much later for a real close, under a glance.
         */
        const val KAPANIS_BEKLEME_MS = 400L
        private const val HER_PENCERE = Int.MIN_VALUE
        private const val YOK = Long.MIN_VALUE
    }
}
