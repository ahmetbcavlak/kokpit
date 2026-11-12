package dev.kokpit.anaekran.veri

import java.util.Locale

/** The words of the vendor's door popup, in one place. */
object KapiMetni {

    /** The vendor app that shows the door/boot popup (CanDoorWindow). */
    const val PAKET = "com.hcn.autocan"

    /** Compared after [sade]: lower case, Turkish letters folded to ASCII, single spaces. */
    val KAPI = listOf("kapi acik")
    val BAGAJ = listOf("bagaj acik")

    private val TR = Locale("tr")

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

    /** What a set of texts says: door words and boot words are independent (both may appear). */
    fun sinifla(metinler: Iterable<CharSequence?>): Okunan {
        var kapi = false
        var bagaj = false
        for (m in metinler) {
            if (m.isNullOrBlank()) continue
            val s = sade(m)
            if (KAPI.any { s.contains(it) }) kapi = true
            if (BAGAJ.any { s.contains(it) }) bagaj = true
        }
        return Okunan(kapi, bagaj)
    }

    data class Okunan(val kapi: Boolean, val bagaj: Boolean) {
        val bos: Boolean get() = !kapi && !bagaj
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
 *     record for the event's window id; this is the fallback when getWindows() does not list the popup.
 *  2. TYPE_WINDOWS_CHANGED → getWindows(): the com.hcn.autocan windows on screen and their text.
 *     A window that was listed and is no longer listed has closed → its record goes.
 *
 * No time-out in 0.1: a record that was never listed stays until a list shows it and it goes away
 * (or the service stops). A false "closed" while a door is open is the worse error; Keşif 0.6 saw the
 * popup open and close through the window list on the car. [Teshis.listeDisiPencere] counts the
 * other case so the first test shows it.
 */
class KapiYorumlayici {

    /** A window as the service saw it: id, package, class name, texts (node text and descriptions). */
    data class Pencere(val id: Int, val paket: String?, val sinif: String?, val metinler: List<CharSequence?>)

    private class Kayit(var okunan: KapiMetni.Okunan, var sinif: String, var listede: Boolean)

    private val kayitlar = LinkedHashMap<Int, Kayit>()
    private val listeDisiSayilan = HashSet<Int>()
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

    private fun kaydet(id: Int, okunan: KapiMetni.Okunan, sinif: String?, listede: Boolean) {
        if (okunan.bos) return
        if (okunan.kapi) kapiGoruldu = true
        if (okunan.bagaj) bagajGoruldu = true
        val k = kayitlar[id]
        if (k == null) {
            kayitlar[id] = Kayit(okunan, sinif.orEmpty(), listede)
        } else {
            k.okunan = okunan // the same window may change its words (door → boot)
            if (sinif != null) k.sinif = sinif
            if (listede) k.listede = true
        }
    }

    /**
     * TYPE_WINDOW_STATE_CHANGED. Returns false when the event is not com.hcn.autocan's (ignored).
     * Text without door/boot words does not clear a record: the same window may have been read better before.
     */
    fun durumOlayi(paket: CharSequence?, sinif: CharSequence?, metinler: List<CharSequence?>, pencereId: Int): Boolean {
        if (paket?.toString() != KapiMetni.PAKET) return false
        kaydet(pencereId, KapiMetni.sinifla(metinler), sinif?.toString(), listede = false)
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
            kaydet(p.id, KapiMetni.sinifla(p.metinler), p.sinif, listede = true)
        }
        val it = kayitlar.entries.iterator()
        while (it.hasNext()) {
            val (id, k) = it.next()
            if (id in gorulen || id in okunamayan) continue
            if (k.listede) it.remove()
            else if (listeDisiSayilan.add(id)) Teshis.listeDisiPencere++
        }
    }

    /** Service stopped: nothing is known any more. */
    fun sifirla() {
        kayitlar.clear()
        listeDisiSayilan.clear()
        kapiGoruldu = false
        bagajGoruldu = false
    }

    /** For diagnostics: "id:kapı/bagaj:sınıf:listede". */
    fun ozet(): String = kayitlar.entries.joinToString(" ") { (id, k) ->
        val ne = listOfNotNull("kapı".takeIf { k.okunan.kapi }, "bagaj".takeIf { k.okunan.bagaj }).joinToString("+")
        "$id:$ne:${k.sinif}:${if (k.listede) "listede" else "olaydan"}"
    }.ifEmpty { "açık pencere yok" }
}
