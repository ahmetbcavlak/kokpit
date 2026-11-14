package dev.kokpit.anaekran.veri

import java.util.Locale

/**
 * Measured time between GPS fixes (research decision 11): the code does not assume 1 Hz, it measures
 * what the chip really delivers with minTime 0 and keeps a summary for the diagnostics panel.
 * If the intervals turn out far from 1 s on the car, the request goes back to minTime 1000.
 *
 * Times are the fixes' arrival times on our clock (SystemClock.elapsedRealtimeNanos()); the fix's own
 * stamp is not the measurement time on Oreo (research decision 10). Thread-safe (synchronized): written
 * on the main thread, read by the diagnostics text.
 */
class OrnekAraligi(private val sonSayisi: Int = SON_SAYISI) {

    private var oncekiNs = YOK
    private val son = LongArray(sonSayisi)
    private var sonAdet = 0
    private var sonBas = 0

    /** Number of measured intervals. */
    var sayi: Int = 0; private set
    var toplamNs: Long = 0L; private set
    var enKisaNs: Long = 0L; private set
    var enUzunNs: Long = 0L; private set

    /** One fix arrived at [ns]. The first one (and the first after [kes]) only sets the start. */
    @Synchronized
    fun ornek(ns: Long) {
        val once = oncekiNs
        oncekiNs = ns
        if (once == YOK || ns < once) return
        val ara = ns - once
        if (sayi == 0) { enKisaNs = ara; enUzunNs = ara } else {
            if (ara < enKisaNs) enKisaNs = ara
            if (ara > enUzunNs) enUzunNs = ara
        }
        sayi++
        toplamNs += ara
        son[(sonBas + sonAdet) % sonSayisi] = ara
        if (sonAdet < sonSayisi) sonAdet++ else sonBas = (sonBas + 1) % sonSayisi
    }

    /** GPS stopped (pause): the next fix starts a new run, so the pause is not counted as an interval. */
    @Synchronized
    fun kes() { oncekiNs = YOK }

    @Synchronized
    fun ortalamaNs(): Long = if (sayi == 0) 0L else toplamNs / sayi

    /** The last up to [sonSayisi] intervals, oldest first, ns. */
    @Synchronized
    fun son(): List<Long> = List(sonAdet) { son[(sonBas + it) % sonSayisi] }

    /** "ort 1,00 sn · en kısa 0,98 · en uzun 1,03 · 42 aralık · son: 1,00 1,01 …" or "ölçülmedi". */
    @Synchronized
    fun ozet(): String {
        if (sayi == 0) return "ölçülmedi"
        fun sn(ns: Long) = String.format(TR, "%.2f", ns / 1e9)
        return "ort ${sn(ortalamaNs())} sn · en kısa ${sn(enKisaNs)} · en uzun ${sn(enUzunNs)} · $sayi aralık · " +
            "son: ${son().joinToString(" ") { sn(it) }}"
    }

    @Synchronized
    fun sifirla() {
        oncekiNs = YOK; sonAdet = 0; sonBas = 0
        sayi = 0; toplamNs = 0L; enKisaNs = 0L; enUzunNs = 0L
    }

    companion object {
        const val SON_SAYISI = 10
        private const val YOK = Long.MIN_VALUE
        private val TR = Locale.forLanguageTag("tr-TR")
    }
}
