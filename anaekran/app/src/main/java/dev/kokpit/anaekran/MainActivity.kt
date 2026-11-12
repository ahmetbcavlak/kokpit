package dev.kokpit.anaekran

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import dev.kokpit.anaekran.arayuz.AltCubuk
import dev.kokpit.anaekran.arayuz.Gorseller
import dev.kokpit.anaekran.arayuz.IsikCizgisi
import dev.kokpit.anaekran.arayuz.KisaNot
import dev.kokpit.anaekran.arayuz.Olcek
import dev.kokpit.anaekran.arayuz.SahneKatmani
import dev.kokpit.anaekran.arayuz.TamEkran
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.ekran.EkranDinleyici
import dev.kokpit.anaekran.ekran.EkranGorunumu
import dev.kokpit.anaekran.ekran.EkranYoneticisi
import dev.kokpit.anaekran.ekran.KlimaPaneli
import dev.kokpit.anaekran.ekran.UygulamalarEkrani
import dev.kokpit.anaekran.ekran.YerTutucuEkran
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.tema.TemaDinleyici
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.uygulama.PaketAcici
import dev.kokpit.anaekran.veri.AracDinleyici
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.AracVerisi

/**
 * The single Activity: portrait, immersive sticky, screen kept on. Builds the six root
 * layers in code (no inflation, every size through u()), routes taps, and calls the
 * [Baglayici] hooks. Taps only — no swipe or edge gesture anywhere (K-66).
 */
class MainActivity : Activity(), AltCubuk.Olaylar, AracDinleyici, TemaDinleyici, EkranDinleyici {

    lateinit var katmanlar: Katmanlar
        private set
    private lateinit var sahne: SahneKatmani
    private lateinit var isik: IsikCizgisi
    private lateinit var not: KisaNot
    private val ekranlar get() = katmanlar.ekranlar
    private val altCubuk get() = katmanlar.altCubuk

    private val baglayicilar = Baglayicilar.liste()
    private val ana = Handler(Looper.getMainLooper())

    /** Theme check + clock, once a minute while visible. */
    private val dakika = object : Runnable {
        override fun run() {
            Tema.kontrolEt()
            ekranlar.dakikaGecti()
            val simdi = System.currentTimeMillis()
            ana.postDelayed(this, 60_000L - simdi % 60_000L + 50L)
        }
    }

    /**
     * 0.1 stand-in for the intro clip: ACILIS for 1.6 s, then PARK (a tap skips it).
     * Task 4 replaces this with the motion task's "clip finished" signal.
     */
    private val acilisBitti = Runnable { if (ekranlar.gecerli == Ekran.ACILIS) ekranlar.goster(Ekran.PARK) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Olcek.kur(resources.displayMetrics.widthPixels)
        Yazi.yukle(resources)

        katmanlar = katmanlariKur()
        setContentView(katmanlar.kok)
        TamEkran.kur(this)

        val p = Tema.palet
        temaDegisti(p)
        altCubuk.guncelle(AracVerisi.durum)
        Tema.dinle(this)
        AracVerisi.dinle(this)
        ekranlar.dinle(this)

        ekranlar.goster(Ekran.ACILIS)
        ana.postDelayed(acilisBitti, ACILIS_MS)

        for (b in baglayicilar) b.kur(this, katmanlar)
    }

    private fun katmanlariKur(): Katmanlar {
        val kok = FrameLayout(this)
        val tam = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

        // 1. Scene
        val sahneGorunumu = ImageView(this)
        // Once the opaque scene is drawn, drop the window background: one full-screen fill less per frame.
        sahne = SahneKatmani(sahneGorunumu) { window.setBackgroundDrawable(null) }
        kok.addView(sahneGorunumu, tam)

        // 2. Video (motion task)
        val video = FrameLayout(this)
        kok.addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 3. Screen: everything above the bottom bar
        val ekranKap = FrameLayout(this)
        kok.addView(ekranKap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = u(AltCubuk.YUKSEKLIK) })

        // 4. Warnings (door/boot; screens task)
        val uyari = FrameLayout(this)
        kok.addView(uyari, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 5. Bottom bar: glow + light line (design y 861–933) over the bar (932–1024)
        val cubukKap = FrameLayout(this)
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

        // 6. Frame-rate slot: top-right corner, the strip above the status line (design 0–20 px)
        val kareHizi = FrameLayout(this)
        kok.addView(kareHizi, FrameLayout.LayoutParams(u(KARE_HIZI_GENISLIK), u(KARE_HIZI_YUKSEKLIK), Gravity.TOP or Gravity.END))

        val yonetici = EkranYoneticisi(ekranKap, ::ekranUret)
        return Katmanlar(kok, sahneGorunumu, video, ekranKap, uyari, cubuk, kareHizi, yonetici)
    }

    private fun ekranUret(e: Ekran): EkranGorunumu = when (e) {
        Ekran.ACILIS -> YerTutucuEkran(this, e) { ana.removeCallbacks(acilisBitti); acilisBitti.run() }
        Ekran.PARK, Ekran.SURUS -> YerTutucuEkran(this, e)
        Ekran.KLIMA -> KlimaPaneli(this) { ekranlar.geri() }
        Ekran.UYGULAMALAR -> UygulamalarEkrani(this) { uyg ->
            if (PaketAcici.ac(this, uyg.bilesen) != PaketAcici.Sonuc.ACILDI) {
                not.goster(getString(R.string.not_acilamadi, uyg.ad))
            }
        }
    }

    // --- Bottom bar ---

    override fun klimaDokunuldu() = ekranlar.acKapa(Ekran.KLIMA)

    override fun simgeDokunuldu(s: AltCubuk.Simge) {
        when (s) {
            AltCubuk.Simge.UYGULAMALAR -> ekranlar.acKapa(Ekran.UYGULAMALAR)
            AltCubuk.Simge.MUZIK -> if (PaketAcici.ac(this, PaketAcici.MEDYA) != PaketAcici.Sonuc.ACILDI) {
                not.goster(getString(R.string.not_muzik_yok))
            }
            else -> if (PaketAcici.ac(this, PaketAcici.ZLINK) != PaketAcici.Sonuc.ACILDI) {
                not.goster(getString(R.string.not_zlink_yok, getString(s.ad)))
            }
        }
    }

    /** The calm note line (tests read it). */
    val kisaNot: KisaNot get() = not

    // --- Listeners ---

    override fun durumDegisti(durum: AracDurumu) {
        altCubuk.guncelle(durum)
        ekranlar.veriDegisti(durum)
    }

    override fun temaDegisti(palet: Palet) {
        altCubuk.temaUygula(palet)
        isik.temaUygula(palet)
        not.temaUygula(palet)
        ekranlar.temaDegisti(palet)
        ekranlar.gecerli?.let { sahne.goster(it, palet) }
    }

    override fun ekranDegisti(eski: Ekran?, yeni: Ekran) {
        sahne.goster(yeni, Tema.palet)
        altCubuk.etkinYap(if (yeni == Ekran.UYGULAMALAR) AltCubuk.Simge.UYGULAMALAR else null)
    }

    // --- Lifecycle ---

    override fun onResume() {
        super.onResume()
        TamEkran.uygula(this)
        ana.removeCallbacks(dakika)
        dakika.run()
        for (b in baglayicilar) b.basla()
    }

    override fun onPause() {
        for (b in baglayicilar.asReversed()) b.dur()
        ana.removeCallbacks(dakika)
        super.onPause()
    }

    override fun onDestroy() {
        for (b in baglayicilar.asReversed()) b.birak()
        ana.removeCallbacksAndMessages(null)
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
        ekranlar.geri()
    }

    // onBackPressed is deprecated from API 33 for predictive back; the unit runs API 27, where
    // it is the only back hook without AndroidX.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        val uygulamalar = ekranlar.gecerli == Ekran.UYGULAMALAR &&
            (ekranlar.gorunum(Ekran.UYGULAMALAR) as UygulamalarEkrani).geri()
        if (uygulamalar) return
        if (ekranlar.geri()) return
        super.onBackPressed()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Gorseller.bosalt()
    }

    companion object {
        const val ACILIS_MS = 1600L
        private const val KARE_HIZI_GENISLIK = 96
        private const val KARE_HIZI_YUKSEKLIK = 20
    }
}
