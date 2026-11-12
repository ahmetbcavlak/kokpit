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
 */
class KapiYorumlayici(private val saat: () -> Long = { SystemClock.elapsedRealtime() }) {

    /** A window as the service saw it: id, package, class name, texts (node text and descriptions). */
    data class Pencere(val id: Int, val paket: String?, val sinif: String?, val metinler: List<CharSequence?>)

    private class Kayit(var okunan: KapiMetni.Okunan, var sinif: String, var listede: Boolean, var sonOlay: Long)

    private val kayitlar = LinkedHashMap<Int, Kayit>()
    private val listeDisiSayilan = HashSet<Int>()
    /** com.hcn.autocan window ids of the last list pass, with or without text. */
    private var sonListe: Set<Int> = emptySet()
    private var kapiGoruldu = false
    private var bagajGoruldu = false

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
     * The result of one getWindows() pass. [okunamayan]: ids whose root node was briefly not
     * available; their records are kept as they are (not taken as closed).
     */
    fun pencereler(liste: List<Pencere>, okunamayan: Set<Int> = emptySet()) {
        val gorulen = HashSet<Int>()
        for (p in liste) {
            if (p.paket != KapiMetni.PAKET) continue
            gorulen += p.id
            kayitlar[p.id]?.listede = true
            kaydet(p.id, KapiMetni.sinifla(p.metinler), p.sinif, listede = true, olay = false)
        }
        sonListe = gorulen + sonListe.filter { it in okunamayan }
        val simdi = saat()
        val it = kayitlar.entries.iterator()
        while (it.hasNext()) {
            val (id, k) = it.next()
            if (id in gorulen || id in okunamayan) continue
            when {
                k.listede -> it.remove() // was on screen, is gone: closed
                simdi - k.sonOlay >= ZAMAN_ASIMI_MS -> {
                    it.remove()
                    Teshis.kapiZamanAsimi++
                }
                listeDisiSayilan.add(id) -> Teshis.listeDisiPencere++
            }
        }
    }

    /** Service stopped: nothing is known any more. */
    fun sifirla() {
        kayitlar.clear()
        listeDisiSayilan.clear()
        sonListe = emptySet()
        kapiGoruldu = false
        bagajGoruldu = false
    }

    /** For diagnostics: "id:kapı/bagaj:sınıf:listede". */
    fun ozet(): String = kayitlar.entries.joinToString(" ") { (id, k) ->
        val ne = listOfNotNull("kapı".takeIf { k.okunan.kapi }, "bagaj".takeIf { k.okunan.bagaj }).joinToString("+")
        "$id:$ne:${k.sinif}:${if (k.listede) "listede" else "olaydan"}"
    }.ifEmpty { "açık pencere yok" }

    companion object {
        /** Unconfirmed (never listed) records go this long after their last event. */
        const val ZAMAN_ASIMI_MS = 10_000L
    }
}
