package dev.kokpit.anaekran.veri

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

    // --- Door service ---
    @Volatile var kapiServisiBagli: Boolean = false
    /** com.hcn.autocan door windows that were seen in an event but never in getWindows() (research decision 7). */
    @Volatile var listeDisiPencere: Int = 0
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
            "Kapı servisi: ${if (kapiServisiBagli) "bağlı" else "bağlı değil"} · liste dışı pencere $listeDisiPencere",
        ) + kapiOlaylari().takeLast(5).map { "  $it" } + listOfNotNull(sonHata?.let { "Hata: $it" })
    }

    /** Tests only. */
    internal fun sifirla() {
        klimaHam = null; klimaHamZamani = 0L; klimaYayinSayisi = 0
        disSicaklikHam = null; disSicaklikHamZamani = 0L
        yayinAlicisi = "kayıtlı değil"; yapiskanGeldi = false
        konumIzni = null; gps = "başlamadı"; konumSayisi = 0; hizsizKonumSayisi = 0; sonKonumZamani = 0L
        kapiServisiBagli = false; listeDisiPencere = 0
        synchronized(kapiOlaylari) { kapiOlaylari.clear() }
        sonHata = null
    }
}
