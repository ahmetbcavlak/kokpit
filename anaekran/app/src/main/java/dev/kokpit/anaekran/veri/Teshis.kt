package dev.kokpit.anaekran.veri

import java.util.Locale

/**
 * In-memory diagnostics of the data layer, for a hidden diagnostics line (task 4) and for the
 * first test on the car. Nothing here is written to a file or sent anywhere.
 *
 * Times use the same clock as [AracVerisi] (elapsedRealtime ms, 0 = never). Any thread may write.
 * Only com.hcn.autocan door events are kept; other apps' window content is never stored.
 */
object Teshis {

    /** How many door-service events are kept. */
    const val KAPI_OLAY_SAYISI = 20

    // --- Climate broadcast (ACTION_AIR_INFO) ---
    /** Last raw extras of ACTION_AIR_INFO, "KEY=value, KEY=value" (sorted by key). */
    @Volatile var klimaHam: String? = null; private set
    @Volatile var klimaHamZamani: Long = 0L; private set
    @Volatile var klimaYayinSayisi: Int = 0; private set

    // --- Outside temperature broadcasts ---
    /** Last raw outside-temperature broadcast, "ACTION: KEY=value". */
    @Volatile var disSicaklikHam: String? = null; private set
    @Volatile var disSicaklikHamZamani: Long = 0L; private set

    /** "kayıtlı" / "kayıtlı değil" / error text. */
    @Volatile var yayinAlicisi: String = "kayıtlı değil"
    /** Whether registerReceiver handed back a sticky intent on the last start. */
    @Volatile var yapiskanGeldi: Boolean = false

    // --- GPS ---
    @Volatile var konumIzni: Boolean? = null
    /** "dinliyor", "durdu", "izin yok", "GPS sağlayıcısı yok", ... */
    @Volatile var gps: String = "başlamadı"
    @Volatile var konumSayisi: Int = 0
    /** Fixes that came without a speed (hasSpeed() false); they do not move the hysteresis. */
    @Volatile var hizsizKonumSayisi: Int = 0
    @Volatile var sonKonumZamani: Long = 0L
    /** Time between fixes as they really arrive (minTime 0: not assumed to be 1 Hz). */
    val konumAraligi = OrnekAraligi()
    /** Last raw speed, m/s (null before the first fix with speed). */
    @Volatile var hamHiz: Float? = null
    /** Last speed accuracy, m/s (hasSpeedAccuracy(), API 26+); null when the fix had none. */
    @Volatile var hizDogrulugu: Float? = null
    /** Fixes with speed that also carried a speed accuracy. */
    @Volatile var dogrulukluKonumSayisi: Int = 0

    // --- Door service ---
    @Volatile var kapiServisiBagli: Boolean = false
    /** com.hcn.autocan door windows that were seen in an event but never in getWindows() (research decision 7). */
    @Volatile var listeDisiPencere: Int = 0
    /** Never-listed door records dropped by the 10 s time-out (KapiYorumlayici.ZAMAN_ASIMI_MS). */
    @Volatile var kapiZamanAsimi: Int = 0
    private val kapiOlaylari = ArrayDeque<String>()

    @Volatile var sonHata: String? = null; private set

    private fun simdi() = AracVerisi.saat()

    fun klimaYayini(ham: String) {
        klimaHam = ham
        klimaHamZamani = simdi()
        klimaYayinSayisi++
    }

    fun disSicaklikYayini(ham: String) {
        disSicaklikHam = ham
        disSicaklikHamZamani = simdi()
    }

    fun konumGeldi(hizVar: Boolean) {
        konumSayisi++
        if (!hizVar) hizsizKonumSayisi++
        sonKonumZamani = simdi()
    }

    /** A fix with speed: raw m/s and its accuracy (null = not reported). */
    fun hizGeldi(ms: Float, dogruluk: Float?) {
        hamHiz = ms
        hizDogrulugu = dogruluk
        if (dogruluk != null) dogrulukluKonumSayisi++
    }

    fun kapiOlayi(metin: String) {
        val satir = "${simdi()} $metin"
        synchronized(kapiOlaylari) {
            kapiOlaylari.addLast(satir)
            while (kapiOlaylari.size > KAPI_OLAY_SAYISI) kapiOlaylari.removeFirst()
        }
    }

    /** Newest last. */
    fun kapiOlaylari(): List<String> = synchronized(kapiOlaylari) { kapiOlaylari.toList() }

    fun hata(nerede: String, t: Throwable) {
        sonHata = "$nerede: ${t.javaClass.simpleName} ${t.message.orEmpty()}".trim()
    }

    /** Short Turkish lines for a hidden diagnostics row. */
    fun satirlar(): List<String> {
        val s = simdi()
        fun once(t: Long) = if (t == 0L) "hiç" else "${(s - t) / 1000} sn önce"
        return listOf(
            "Klima yayını: $yayinAlicisi · $klimaYayinSayisi kez · son ${once(klimaHamZamani)}" +
                (if (yapiskanGeldi) " · yapışkan değer geldi" else ""),
            "Klima ham: ${klimaHam ?: "—"}",
            "Dış sıcaklık ham: ${disSicaklikHam ?: "—"} · ${once(disSicaklikHamZamani)}",
            "GPS: $gps · izin ${when (konumIzni) { true -> "var"; false -> "yok"; null -> "?" }} · " +
                "$konumSayisi konum ($hizsizKonumSayisi hızsız) · son ${once(sonKonumZamani)}",
            "GPS aralığı: ${konumAraligi.ozet()}",
            "GPS hız: ham ${hamHiz?.let { ondalik(it, 2) + " m/sn" } ?: "—"} · doğruluk " +
                "${hizDogrulugu?.let { ondalik(it, 2) + " m/sn" } ?: "yok"} ($dogrulukluKonumSayisi konumda) · " +
                yumusatma(),
            "Kapı servisi: ${if (kapiServisiBagli) "bağlı" else "bağlı değil"} · liste dışı pencere $listeDisiPencere · zaman aşımı $kapiZamanAsimi",
        ) + kapiOlaylari().takeLast(5).map { "  $it" } + listOfNotNull(sonHata?.let { "Hata: $it" })
    }

    private fun ondalik(f: Float, basamak: Int) = String.format(Locale.forLanguageTag("tr-TR"), "%.${basamak}f", f)

    /** Smoother state; the smoother is main-thread state, and this text is built on the main thread. */
    private fun yumusatma(): String {
        val y = Hiz.yumusatici
        if (y.bilinmiyor) return "yumuşatma: bilinmiyor"
        return "yumuşatma: ${if (y.sifirda) "sıfır kipi" else "ivme ${ondalik(y.sonIvme, 1)} m/sn²"}" +
            (if (y.eskiMi(System.nanoTime())) " · eski" else "")
    }

    /** Tests only. */
    internal fun sifirla() {
        klimaHam = null; klimaHamZamani = 0L; klimaYayinSayisi = 0
        disSicaklikHam = null; disSicaklikHamZamani = 0L
        yayinAlicisi = "kayıtlı değil"; yapiskanGeldi = false
        konumIzni = null; gps = "başlamadı"; konumSayisi = 0; hizsizKonumSayisi = 0; sonKonumZamani = 0L
        konumAraligi.sifirla(); hamHiz = null; hizDogrulugu = null; dogrulukluKonumSayisi = 0
        kapiServisiBagli = false; listeDisiPencere = 0; kapiZamanAsimi = 0
        synchronized(kapiOlaylari) { kapiOlaylari.clear() }
        sonHata = null
    }
}
