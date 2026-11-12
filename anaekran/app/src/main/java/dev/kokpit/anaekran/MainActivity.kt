package dev.kokpit.anaekran

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import dev.kokpit.anaekran.arayuz.AltCubuk
import dev.kokpit.anaekran.arayuz.AracHaritasi
import dev.kokpit.anaekran.arayuz.Gorseller
import dev.kokpit.anaekran.arayuz.IsikCizgisi
import dev.kokpit.anaekran.arayuz.KisaNot
import dev.kokpit.anaekran.arayuz.Olcek
import dev.kokpit.anaekran.arayuz.SahneKatmani
import dev.kokpit.anaekran.arayuz.TamEkran
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.ekran.AcilisEkrani
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.ekran.EkranDinleyici
import dev.kokpit.anaekran.ekran.EkranGorunumu
import dev.kokpit.anaekran.ekran.EkranYoneticisi
import dev.kokpit.anaekran.ekran.Ipucu
import dev.kokpit.anaekran.ekran.Ipuclari
import dev.kokpit.anaekran.ekran.KlimaPaneli
import dev.kokpit.anaekran.ekran.ParkEkrani
import dev.kokpit.anaekran.ekran.SurusEkrani
import dev.kokpit.anaekran.ekran.TeshisPaneli
import dev.kokpit.anaekran.ekran.UyariKatmani
import dev.kokpit.anaekran.ekran.UygulamalarEkrani
import dev.kokpit.anaekran.hareket.Goruntu
import dev.kokpit.anaekran.hareket.HareketBaglayici
import dev.kokpit.anaekran.hareket.Klip
import dev.kokpit.anaekran.hareket.KlipOynatici
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.tema.TemaDinleyici
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.uygulama.PaketAcici
import dev.kokpit.anaekran.veri.AracDinleyici
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.ErisilebilirlikDurumu
import dev.kokpit.anaekran.veri.HareketHali
import dev.kokpit.anaekran.veri.Teshis
import dev.kokpit.anaekran.veri.VeriBaglayici

/**
 * The single Activity: portrait, immersive sticky, screen kept on. Builds the root layers in
 * code (no inflation, every size through u()), wires the data and motion hooks
 * ([Baglayicilar]), and decides which screen and which scene picture are in front.
 * Taps and one long press only — no swipe or edge gesture anywhere (K-66).
 *
 * Screen flow:
 *  - intro (ACILIS, Y-1) over klip_giris → after 1.6 s a fade to the PARK scene (SURUS if the
 *    data already says driving); a tap cuts it;
 *  - `hareketHali` SURUS → drive screen, PARK → park screen; at night with the park↔drive clip,
 *    by day with a plain cross-fade; null → the screen stays. Only a CHANGE acts, so a speed
 *    update every second does not touch what the user opened;
 *  - climate or apps panel open when the car starts driving: the panel closes and the drive
 *    screen comes (the road needs the speed, and a passive panel has nothing to finish);
 *    when the car stops with a panel open, the panel stays and closing it lands on park;
 *  - door/boot: the warning layer (UyariKatmani) above whatever screen is in front.
 */
class MainActivity : Activity(), AltCubuk.Olaylar, AracDinleyici, TemaDinleyici, EkranDinleyici {

    lateinit var katmanlar: Katmanlar
        private set
    private lateinit var sahne: SahneKatmani
    private lateinit var isik: IsikCizgisi
    private lateinit var cubukKap: FrameLayout
    private lateinit var not: KisaNot

    /** Door/boot warning layer (tests read it). */
    lateinit var uyari: UyariKatmani
        private set

    /** Hidden diagnostics (long press on the frame counter). */
    lateinit var teshis: TeshisPaneli
        private set

    private val ekranlar get() = katmanlar.ekranlar
    private val altCubuk get() = katmanlar.altCubuk

    private val baglayicilar = Baglayicilar.liste()

    /** The motion hook, if wired (tests may run without it). */
    val hareket: HareketBaglayici? = baglayicilar.filterIsInstance<HareketBaglayici>().firstOrNull()

    private val ana = Handler(Looper.getMainLooper())

    /** True while klip_giris plays: scene changes and park/drive switches wait for its end. */
    var acilisSuruyor = false
        private set
    private var acilisHedefi = Ekran.PARK
    private var acilisSahnesi = 0

    /** The park/drive state the screens follow (changes act, repeats do not). */
    private var uygulananHal: HareketHali? = null

    /** Theme check + clock, once a minute while visible (never per second). */
    private val dakika = object : Runnable {
        override fun run() {
            Tema.kontrolEt()
            ekranlar.dakikaGecti()
            val simdi = System.currentTimeMillis()
            ana.postDelayed(this, 60_000L - simdi % 60_000L + 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Olcek.kur(resources.displayMetrics.widthPixels)
        Yazi.yukle(resources)
        AracHaritasi.yukle(this)

        katmanlar = katmanlariKur()
        setContentView(katmanlar.kok)
        TamEkran.kur(this)

        ekranlar.gecisOncesi = ::gecisOncesi
        uyari.degisti = { uyariDegisti() }
        val p = Tema.palet
        altCubuk.temaUygula(p)
        isik.temaUygula(p)
        not.temaUygula(p)
        uyari.temaUygula(p)
        altCubuk.guncelle(AracVerisi.durum)
        Tema.dinle(this)
        AracVerisi.dinle(this)
        ekranlar.dinle(this)

        for (b in baglayicilar) b.kur(this, katmanlar)
        hareket?.let {
            ekranlar.gecis = it.gecis
            // Wiring step 6: while the opaque video is on screen the scene layer is not drawn.
            it.videoGorunurlugu = { video -> sahneGorunur(!video) }
        }
        acilisiBaslat()
    }

    private fun katmanlariKur(): Katmanlar {
        val kok = FrameLayout(this)
        val tam = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

        // 1. Scene
        val sahneGorunumu = ImageView(this)
        // Once the opaque scene is drawn, drop the window background: one full-screen fill less per frame.
        sahne = SahneKatmani(sahneGorunumu) { window.setBackgroundDrawable(null) }
        kok.addView(sahneGorunumu, tam)

        // 2. Video (motion layer)
        val video = FrameLayout(this)
        kok.addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 3. Screen: full height (the intro draws its bottom line at y 944; other screens stop at 932)
        val ekranKap = FrameLayout(this)
        kok.addView(ekranKap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 4. Warnings
        val uyariKap = FrameLayout(this)
        uyari = UyariKatmani(this)
        uyariKap.addView(uyari, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        kok.addView(uyariKap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 5. Bottom bar: glow + light line (design y 861–933) over the bar (932–1024)
        cubukKap = FrameLayout(this)
        isik = IsikCizgisi(this)
        val cubuk = AltCubuk(this, this)
        cubukKap.addView(cubuk, FrameLayout.LayoutParams(MATCH_PARENT, u(AltCubuk.YUKSEKLIK), Gravity.BOTTOM))
        cubukKap.addView(isik, FrameLayout.LayoutParams(MATCH_PARENT, u(IsikCizgisi.YUKSEKLIK), Gravity.TOP))
        kok.addView(
            cubukKap,
            FrameLayout.LayoutParams(MATCH_PARENT, u(AltCubuk.YUKSEKLIK + IsikCizgisi.YUKSEKLIK - 1), Gravity.BOTTOM),
        )
        not = KisaNot(this)
        kok.addView(
            not,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = u(AltCubuk.YUKSEKLIK + 24)
            },
        )

        // 6. Frame-rate slot: top-right corner, the strip above the status line (design 0–20 px).
        // A long press (not an edge swipe) opens the diagnostics panel.
        val kareHizi = FrameLayout(this).apply {
            isLongClickable = true
            setOnLongClickListener {
                teshis.ac()
                true
            }
        }
        kok.addView(kareHizi, FrameLayout.LayoutParams(u(KARE_HIZI_GENISLIK), u(KARE_HIZI_YUKSEKLIK), Gravity.TOP or Gravity.END))

        // 7. Diagnostics, above everything, hidden.
        teshis = TeshisPaneli(this, ::teshisSatirlari)
        kok.addView(teshis, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val yonetici = EkranYoneticisi(ekranKap, ::ekranUret)
        return Katmanlar(kok, sahneGorunumu, video, ekranKap, uyariKap, cubuk, kareHizi, yonetici)
    }

    private fun ekranUret(e: Ekran): EkranGorunumu = when (e) {
        Ekran.ACILIS -> AcilisEkrani(this) { hareket?.kes() }
        Ekran.PARK -> ParkEkrani(this, ::ipucuSec, ::ipucuDokunuldu)
        Ekran.SURUS -> SurusEkrani(this) { VeriBaglayici.izinVar(this) }
        Ekran.KLIMA -> KlimaPaneli(this, kapat = { ekranlar.geri() }, pasif = { not.goster(getString(R.string.not_klima_pasif)) })
        Ekran.UYGULAMALAR -> UygulamalarEkrani(this) { uyg ->
            if (PaketAcici.ac(this, uyg.bilesen) != PaketAcici.Sonuc.ACILDI) {
                not.goster(getString(R.string.not_acilamadi, uyg.ad))
            }
        }
    }

    // --- Intro ---

    /**
     * Wiring step 3: the intro clip plays, its fade lands on the target screen's scene and its
     * `bitti` shows that screen. Taps during the clip go to [HareketBaglayici.kes] (its own tap
     * shield, and the intro screen's click).
     */
    private fun acilisiBaslat() {
        acilisHedefi = if (AracVerisi.durum.hareketHali == HareketHali.SURUS) Ekran.SURUS else Ekran.PARK
        uygulananHal = AracVerisi.durum.hareketHali
        acilisSahnesi = sahneKaynagi(acilisHedefi)
        val h = hareket
        if (h == null) {
            ekranlar.goster(Ekran.ACILIS)
            acilisiBitir(null)
            return
        }
        acilisSuruyor = true
        ekranlar.goster(Ekran.ACILIS)
        h.acilisiOynat(acilisSahnesi) { s -> acilisiBitir(s) }
    }

    /**
     * Wiring step 5: inside `bitti` the scene layer already shows the intro's target (set when the
     * intro started, decoded long before the fade ends). Then data that changed during the clip
     * is applied (park/drive, boot picture, theme); starting a new motion call from `bitti` is
     * safe for every result, KESILDI included.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun acilisiBitir(s: KlipOynatici.Sonuc?) {
        acilisSuruyor = false
        ekranlar.goster(acilisHedefi)
        val d = AracVerisi.durum
        if (d.hareketHali != uygulananHal) hareketHaliDegisti(d.hareketHali) else sahneyiGuncelle()
    }

    // --- Scene ---

    /** The scene picture for a screen; the intro shows its target's. */
    private fun sahneKaynagi(e: Ekran): Int =
        SahneKatmani.kaynak(if (e == Ekran.ACILIS) acilisHedefi else e, Tema.palet.gunduz, AracVerisi.durum.bagajAcik == true)

    /**
     * Wiring step 4, run by EkranYoneticisi before every screen change: the clip / cross-fade
     * starts before the screen and the scene switch. Park → drive at night plays klip_surus,
     * drive → park klip_surus_geri; by day (no day clips) and for panels a plain fade.
     */
    private fun gecisOncesi(eski: Ekran?, yeni: Ekran) {
        when (eski) {
            null -> sahne.goster(sahneKaynagi(yeni))
            Ekran.ACILIS -> sahne.goster(acilisSahnesi)
            else -> {
                val klip = when {
                    Tema.palet.gunduz -> null
                    eski == Ekran.PARK && yeni == Ekran.SURUS -> Klip.SURUS
                    eski == Ekran.SURUS && yeni == Ekran.PARK -> Klip.SURUS_GERI
                    else -> null
                }
                sahneGecisi(sahneKaynagi(yeni), klip)
            }
        }
    }

    /**
     * Cross-fades the scene layer to [yeni]. The clip is used only when it really starts on the
     * picture on screen and ends on [yeni] (e.g. not from the boot-open picture), otherwise a
     * plain 350 ms fade. The scene layer switches at once (the motion layer covers it with the
     * old picture) and again in `bitti` (wiring step 5); KESILDI starts nothing.
     */
    private fun sahneGecisi(yeni: Int, klip: Klip?) {
        val eski = sahne.istenen
        if (yeni == eski) return
        val h = hareket
        if (h != null && eski != 0 && !acilisSuruyor) {
            val k = klip?.takeIf { (it.poster as? Goruntu.Kaynak)?.id == eski && it.sonKare == yeni }
            h.gecisOynat(k, eski, yeni) { s -> if (s != KlipOynatici.Sonuc.KESILDI) sahne.goster(yeni) }
        }
        sahne.goster(yeni)
    }

    /**
     * Hides the scene layer while an opaque clip covers it (HWUI would still fill it every
     * frame) and shows it again before the clip goes: HareketBaglayici.videoGorunurlugu calls
     * in pairs, `false` always before `bitti`. INVISIBLE, not GONE: size and bitmap stay.
     */
    private fun sahneGorunur(gorunur: Boolean) {
        val v = if (gorunur) View.VISIBLE else View.INVISIBLE
        if (katmanlar.sahne.visibility != v) katmanlar.sahne.visibility = v
    }

    /** Data or theme changed the picture of the screen in front (boot opened, day began). */
    private fun sahneyiGuncelle() {
        if (acilisSuruyor) return
        val g = ekranlar.gecerli ?: return
        if (g == Ekran.ACILIS) return
        sahneGecisi(sahneKaynagi(g), null)
    }

    // --- Park / drive ---

    private fun hareketHaliDegisti(h: HareketHali?) {
        if (acilisSuruyor) return // applied when the intro ends
        uygulananHal = h
        val hedef = when (h) {
            HareketHali.SURUS -> Ekran.SURUS
            HareketHali.PARK -> Ekran.PARK
            null -> return // unknown: the screen stays
        }
        val g = ekranlar.gecerli ?: return
        when {
            g == Ekran.ACILIS -> return
            g.temel -> ekranlar.goster(hedef)
            hedef == Ekran.SURUS -> {
                teshis.kapat()
                ekranlar.goster(Ekran.SURUS)
            }
            else -> ekranlar.temeliAyarla(Ekran.PARK)
        }
    }

    // --- Bottom bar ---

    override fun klimaDokunuldu() = ekranlar.acKapa(Ekran.KLIMA)

    override fun simgeDokunuldu(s: AltCubuk.Simge) {
        when (s) {
            AltCubuk.Simge.UYGULAMALAR -> ekranlar.acKapa(Ekran.UYGULAMALAR)
            AltCubuk.Simge.MUZIK -> when (PaketAcici.ac(this, PaketAcici.MEDYA)) {
                PaketAcici.Sonuc.ACILDI -> Unit
                PaketAcici.Sonuc.YOK -> not.goster(getString(R.string.not_muzik_yok))
                PaketAcici.Sonuc.HATA -> not.goster(getString(R.string.not_acilamadi, getString(s.ad)))
            }
            else -> when (PaketAcici.ac(this, PaketAcici.ZLINK)) {
                PaketAcici.Sonuc.ACILDI -> Unit
                PaketAcici.Sonuc.YOK -> not.goster(getString(R.string.not_zlink_yok, getString(s.ad)))
                PaketAcici.Sonuc.HATA -> not.goster(getString(R.string.not_acilamadi, getString(s.ad)))
            }
        }
    }

    /** The calm note line (tests read it). */
    val kisaNot: KisaNot get() = not

    // --- Hints ---

    private fun ipucuSec(): Ipucu? = Ipuclari.sec(ErisilebilirlikDurumu.etkinMi(this), VeriBaglayici.izinVar(this))

    private fun ipucuDokunuldu(i: Ipucu, ayarlariAc: Boolean) {
        Ipuclari.kapat(i)
        if (ayarlariAc) {
            val sonuc = when (i) {
                Ipucu.ERISILEBILIRLIK -> PaketAcici.ayarAc(this, Settings.ACTION_ACCESSIBILITY_SETTINGS)
                Ipucu.KONUM -> PaketAcici.ayarAc(this, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageName)
            }
            if (sonuc != PaketAcici.Sonuc.ACILDI) not.goster(getString(R.string.not_ayarlar_acilamadi))
        }
        ipuclariniYenile()
    }

    private fun ipuclariniYenile() {
        (ekranlar.kurulduysa(Ekran.PARK) as? ParkEkrani)?.ipucuYenile()
    }

    // --- Listeners ---

    override fun durumDegisti(durum: AracDurumu) {
        altCubuk.guncelle(durum)
        ekranlar.veriDegisti(durum)
        uyari.guncelle(durum, ekranlar.gecerli)
        isikGuncelle()
        if (durum.hareketHali != uygulananHal) hareketHaliDegisti(durum.hareketHali) else sahneyiGuncelle()
    }

    override fun temaDegisti(palet: Palet) {
        altCubuk.temaUygula(palet)
        isik.temaUygula(palet)
        not.temaUygula(palet)
        uyari.temaUygula(palet)
        ekranlar.temaDegisti(palet)
        sahneyiGuncelle()
    }

    override fun ekranDegisti(eski: Ekran?, yeni: Ekran) {
        altCubuk.etkinYap(if (yeni == Ekran.UYGULAMALAR) AltCubuk.Simge.UYGULAMALAR else null)
        // Y-1 has no bottom bar; it fades in with the first real screen.
        if (yeni == Ekran.ACILIS) {
            cubukKap.visibility = View.INVISIBLE
        } else if (cubukKap.visibility != View.VISIBLE) {
            cubukKap.alpha = 0f
            cubukKap.visibility = View.VISIBLE
            cubukKap.animate().alpha(1f).setDuration(CUBUK_MS).withLayer().start()
        }
        uyari.guncelle(AracVerisi.durum, yeni)
        isikGuncelle()
    }

    private fun uyariDegisti() {
        (ekranlar.kurulduysa(Ekran.PARK) as? ParkEkrani)?.baslikUyarida = uyari.parkBasliginiKullaniyor
    }

    /** The light line points at what needs attention (IsikCizgisi.Mod). */
    private fun isikGuncelle() {
        val d = AracVerisi.durum
        val g = ekranlar.gecerli
        val acik = d.kapiAcik == true || d.bagajAcik == true
        isik.modUygula(
            when {
                g == null || g == Ekran.ACILIS || !acik -> IsikCizgisi.Mod.NORMAL
                ekranlar.temel == Ekran.SURUS -> IsikCizgisi.Mod.ALARM
                d.bagajAcik == true -> IsikCizgisi.Mod.BAGAJ
                else -> IsikCizgisi.Mod.KAPI
            },
        )
    }

    // --- Diagnostics ---

    private fun teshisSatirlari(): List<String> {
        val dm = resources.displayMetrics
        val pi = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        @Suppress("DEPRECATION") // longVersionCode is API 28; the unit runs 27.
        val kod = pi?.versionCode ?: 0
        val d = AracVerisi.durum
        val olcum = hareket?.oynatici?.sonOlcum
        return listOf(
            getString(R.string.teshis_baslik, pi?.versionName ?: "?", kod),
            "Ekran: ${dm.widthPixels}×${dm.heightPixels} px · ${dm.densityDpi} dpi (yoğunluk ${dm.density}) · u() ölçeği ${String.format(java.util.Locale.ROOT, "%.3f", Olcek.oran)}",
            "Görünüm: ${ekranlar.gecerli} (temel ${ekranlar.temel}) · tema ${Tema.palet.ad} · uyarı ${uyari.gorunum} · ışık ${isik.mod}",
            "Araç: hareket ${d.hareketHali ?: "?"} · hız ${d.hiz ?: "—"} · kapı ${d.kapiAcik ?: "?"} · bagaj ${d.bagajAcik ?: "?"} · dış ${d.disSicaklik ?: "—"}",
            "Klima (çözülen): ${d.klima ?: "—"}",
            "Erişilebilirlik servisi ayarı: ${if (ErisilebilirlikDurumu.etkinMi(this)) "açık" else "kapalı"}",
            "Klip: ${olcum?.let { "${it.klip} · ilk kare ${it.ilkKareMs?.let { ms -> "$ms ms" } ?: "gelmedi"} · bırakma ${it.birakmaMs?.let { ms -> "$ms ms" } ?: "—"}" } ?: "ölçülmedi"}",
            "Kapı kayıtları: liste dışı pencere ${Teshis.listeDisiPencere} · zaman aşımıyla düşen ${Teshis.kapiZamanAsimi}",
            "Kare hızı sayacı: ${hareket?.sayac?.text ?: "—"}",
            "",
        ) + Teshis.satirlar()
    }

    // --- Lifecycle ---

    override fun onResume() {
        super.onResume()
        TamEkran.uygula(this)
        ana.removeCallbacks(dakika)
        dakika.run()
        for (b in baglayicilar) b.basla()
        // Settings may have changed while we were away (service switched on, permission given).
        ipuclariniYenile()
        ekranlar.veriDegisti(AracVerisi.durum)
    }

    override fun onPause() {
        for (b in baglayicilar.asReversed()) b.dur()
        ana.removeCallbacks(dakika)
        super.onPause()
    }

    override fun onDestroy() {
        for (b in baglayicilar.asReversed()) b.birak()
        ana.removeCallbacksAndMessages(null)
        teshis.kapat()
        Tema.birak(this)
        AracVerisi.birak(this)
        ekranlar.birak(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) TamEkran.uygula(this)
    }

    /** singleTask: the launcher icon brings us back; close any panel. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        teshis.kapat()
        ekranlar.geri()
    }

    // onBackPressed is deprecated from API 33 for predictive back; the unit runs API 27, where
    // it is the only back hook without AndroidX.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (teshis.acik) {
            teshis.kapat()
            return
        }
        val uygulamalar = ekranlar.gecerli == Ekran.UYGULAMALAR &&
            (ekranlar.gorunum(Ekran.UYGULAMALAR) as UygulamalarEkrani).geri()
        if (uygulamalar) return
        if (ekranlar.geri()) return
        // Base screen: go behind like a launcher would, never finish — the activity (and its
        // state) stays, so coming back does not replay the intro; GPS stops in onPause.
        moveTaskToBack(true)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Gorseller.bosalt()
    }

    companion object {
        private const val KARE_HIZI_GENISLIK = 96
        private const val KARE_HIZI_YUKSEKLIK = 20
        private const val CUBUK_MS = 220L
    }
}
