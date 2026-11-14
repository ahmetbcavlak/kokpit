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
import dev.kokpit.anaekran.ekran.EkranKabi
import dev.kokpit.anaekran.ekran.EkranYoneticisi
import dev.kokpit.anaekran.ekran.HizDongusu
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
import dev.kokpit.anaekran.hareket.YumusakGecis
import dev.kokpit.anaekran.hareket.Zamanlama
import dev.kokpit.anaekran.hareket.surus.KareDinleyici
import dev.kokpit.anaekran.hareket.surus.SurusSahnesi
import dev.kokpit.anaekran.tema.Gunes
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.SonKonum
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.tema.TemaDinleyici
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.uygulama.PaketAcici
import dev.kokpit.anaekran.veri.AracDinleyici
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.ErisilebilirlikDurumu
import dev.kokpit.anaekran.veri.HareketHali
import dev.kokpit.anaekran.veri.Hiz
import dev.kokpit.anaekran.veri.KapiServisi
import dev.kokpit.anaekran.veri.Teshis
import dev.kokpit.anaekran.veri.VeriBaglayici

/**
 * The single Activity: portrait, immersive sticky, screen kept on. Builds the root layers in
 * code (no inflation, every size through u()), wires the data and motion hooks
 * ([Baglayicilar]), and decides which screen and which scene picture are in front.
 * Taps and one long press only — no swipe or edge gesture anywhere (K-66).
 *
 * Screen flow:
 *  - intro (ACILIS, Y-1) over klip_giris (2.4 s) → its last frame held 0.3 s, then a 0.6 s fade
 *    to the PARK scene (SURUS if the data already says driving; K-93, timings in hareket/); a tap
 *    cuts it;
 *  - `hareketHali` SURUS → drive screen, PARK → park screen; at night with the park↔drive clip,
 *    by day with a plain cross-fade; null → the screen stays. Only a CHANGE acts, so a speed
 *    update every second does not touch what the user opened;
 *  - climate or apps panel open when the car starts driving: the panel closes and the drive
 *    screen comes (the road needs the speed, and a passive panel has nothing to finish);
 *    when the car stops with a panel open, the panel stays and closing it lands on park;
 *  - door/boot: the warning layer (UyariKatmani) above whatever screen is in front.
 *
 * Drive scene (0.2, K-94/K-95; [surusKatmaniniGuncelle]): its own root layer between the scene
 * picture and the video. Shown only at night, with the drive screen in front, once its bitmaps
 * are ready, while no clip/fade of the scene picture runs and the diagnostics panel is closed;
 * then the scene picture under it is boya_gece (the scene draws the car). Park → drive: hidden
 * while klip_surus plays; in the clip's `bitti` it appears over the still car (sahne_surus) from
 * rest (`durgundanBasla`) and the picture under it switches to boya_gece. Drive → park, a panel,
 * day: hidden before the clip/fade starts, whose cover is the still car again.
 *
 * Speed digits and arc (K-96): one reader of the smoothed speed per frame — the drive scene's
 * frame while it runs (HareketBaglayici.surusKaresi), otherwise the drive screen's own loop
 * ([HizDongusu]); the test is `hareket.surus?.calisiyor`.
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

    /** The picture the scene layer shows / was last asked for (tests, diagnostics). */
    val sahneResmi: Int get() = sahne.istenen

    /** The drive scene (null without the motion hook). */
    var surusSahnesi: SurusSahnesi? = null
        private set

    /** The drive scene is VISIBLE (and the scene picture under it is boya_gece). */
    var surusGorunur = false
        private set

    /** A clip / fade of the scene picture runs (started by [sahneGecisi], until its `bitti`). */
    private var sahneGecisiSuruyor = false

    /** [sahneGecisi] waits for the still car's decode before hiding the drive scene. */
    private var ortuBekleniyor = false
    private var sahneGecisNo = 0

    /** Between onResume and onPause. */
    private var devam = false

    /** The drive screen, once built (the speed goes straight to it every frame). */
    private var surusEkrani: SurusEkrani? = null

    /** Per-frame speed for the drive HUD, from whichever loop reads it (see the class notes). */
    private val hudKaresi = KareDinleyici { t, v ->
        val e = surusEkrani
        if (e != null) {
            val y = Hiz.yumusatici
            e.hizGoster(v * MS_KMH, y.bilinmiyor, y.eskiMi(t))
        }
    }

    /** The drive screen's own speed loop (when the drive scene does not run). */
    lateinit var hizDongusu: HizDongusu
        private set

    /**
     * Unknown/stale can change while both loops are stopped (parked: no frames): checked once a
     * second while the drive screen is in front. Reads only the flags, never the spring.
     */
    private val hizEtiketi = object : Runnable {
        override fun run() {
            val y = Hiz.yumusatici
            surusEkrani?.durumGoster(y.bilinmiyor, y.eskiMi(Baglayicilar.saat()))
            ana.postDelayed(this, ETIKET_MS)
        }
    }

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
        // Day/night follows the sun at the last GPS fix (read-only), else Istanbul; checked every minute.
        val uyg = applicationContext
        Tema.konum = { SonKonum.oku(uyg) }
        Tema.kontrolEt()
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

        hizDongusu = HizDongusu(
            Baglayicilar.kareKaynagi, Baglayicilar.hiz, Baglayicilar.durgun,
            { hareket?.surus?.calisiyor == true }, hudKaresi,
        )
        teshis.degisti = { surusKatmaniniGuncelle() }
        katmanlar.ekran.gecisSuruyor = { (ekranlar.gecis as? YumusakGecis)?.suruyor ?: ekranlar.gecisSuruyor }

        for (b in baglayicilar) b.kur(this, katmanlar)
        hareket?.let {
            ekranlar.gecis = it.gecis
            // Wiring step 6: while the opaque video is on screen the scene layer is not drawn.
            it.videoGorunurlugu = { video -> sahneGorunur(!video) }
            surusSahnesiniKur(it)
        }
        acilisiBaslat()
    }

    /**
     * HareketBaglayici steps 7–8: the drive scene in its own root layer, INVISIBLE until
     * [surusKatmaniniGuncelle] shows it; the same speed reader and HUD callback as the screen's loop.
     */
    private fun surusSahnesiniKur(h: HareketBaglayici) {
        val s = h.surusSahnesi(this)
        s.visibility = View.INVISIBLE
        katmanlar.surus.addView(s, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        h.surusHizi(Baglayicilar.hiz, Baglayicilar.durgun)
        h.surusKaresi(hudKaresi)
        surusSahnesi = s
        s.hazirOlunca = { surusKatmaniniGuncelle() }
    }

    private fun katmanlariKur(): Katmanlar {
        val kok = FrameLayout(this)
        val tam = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

        // 1. Scene
        val sahneGorunumu = ImageView(this)
        // Once the opaque scene is drawn, drop the window background: one full-screen fill less per frame.
        sahne = SahneKatmani(sahneGorunumu) { window.setBackgroundDrawable(null) }
        kok.addView(sahneGorunumu, tam)

        // 1b. Drive scene (transparent; filled in onCreate after the motion hook's kur)
        val surus = FrameLayout(this)
        kok.addView(surus, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 2. Video (motion layer)
        val video = FrameLayout(this)
        kok.addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 3. Screen: full height (the intro draws its bottom line at y 944; other screens stop at 932).
        // Touches go to the screen in front only (E1).
        val ekranKap = EkranKabi(this)
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
        teshis = TeshisPaneli(this, ::teshisSatirlari, ::kapiUyarisiniSifirla)
        kok.addView(teshis, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val yonetici = EkranYoneticisi(ekranKap, ::ekranUret)
        return Katmanlar(kok, sahneGorunumu, surus, video, ekranKap, uyariKap, cubuk, kareHizi, yonetici)
    }

    private fun ekranUret(e: Ekran): EkranGorunumu = when (e) {
        Ekran.ACILIS -> AcilisEkrani(this) { hareket?.kes() }
        Ekran.PARK -> ParkEkrani(this, ::ipucuSec, ::ipucuDokunuldu)
        Ekran.SURUS -> SurusEkrani(this) { VeriBaglayici.izinVar(this) }.also { surusEkrani = it }
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
        if (acilisHedefi == Ekran.SURUS) teshis.kapat()
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
     * plain 0.6 s fade (K-93, HareketBaglayici). The scene layer switches at once (the motion
     * layer covers it with the old picture) and again in `bitti` (wiring step 5); KESILDI starts
     * nothing. A visible drive scene is hidden first and the cover is the still car
     * (sahne_surus): the clip back to park starts on it, and so does any fade away from it.
     *
     * The cover must be on screen in the same turn the scene is hidden (İ-F2 Ö-2): if sahne_surus
     * is not decoded (memory trimmed), the moving car stays until it is, then the change runs
     * ([ORTU_BEKLEME_MS] at most, should the decode never call back).
     */
    private fun sahneGecisi(yeni: Int, klip: Klip?) {
        if (surusGorunur && !Gorseller.hazirMi(R.drawable.sahne_surus)) {
            val no = ++sahneGecisNo
            sahneGecisiSuruyor = true
            ortuBekleniyor = true
            val devamEt = Runnable {
                if (no != sahneGecisNo || !ortuBekleniyor) return@Runnable
                ortuBekleniyor = false
                sahneGecisiSuruyor = false
                sahneGecisi(yeni, klip)
                surusKatmaniniGuncelle()
            }
            Gorseller.al(resources, R.drawable.sahne_surus) { devamEt.run() }
            ana.postDelayed(devamEt, ORTU_BEKLEME_MS)
            return
        }
        ortuBekleniyor = false
        var eski = sahne.istenen
        if (surusGorunur) {
            surusGizle()
            eski = R.drawable.sahne_surus
        }
        if (yeni == eski) {
            sahne.goster(yeni)
            return
        }
        val h = hareket
        if (h != null && eski != 0 && !acilisSuruyor) {
            val k = klip?.takeIf { (it.poster as? Goruntu.Kaynak)?.id == eski && it.sonKare == yeni }
            val no = ++sahneGecisNo
            sahneGecisiSuruyor = true
            // The drive scene will need boya_gece under it at the end: decode it while the clip plays.
            if (yeni == R.drawable.sahne_surus) Gorseller.al(resources, R.drawable.boya_gece) {}
            h.gecisOynat(k, eski, yeni) { s ->
                if (s != KlipOynatici.Sonuc.KESILDI) sahne.goster(yeni)
                if (no == sahneGecisNo) {
                    sahneGecisiSuruyor = false
                    surusKatmaniniGuncelle()
                }
            }
        }
        sahne.goster(yeni)
    }

    /**
     * What the scene layer should show for [e] now: boya_gece under a visible drive scene at
     * night. At dawn (palette already day, scene still visible) the day car, so [sahneyiGuncelle]
     * starts the fade whose cover is the still night car (İ-F2 Ö-1).
     */
    private fun istenenSahne(e: Ekran): Int =
        if (e == Ekran.SURUS && surusGorunur && !Tema.palet.gunduz) R.drawable.boya_gece else sahneKaynagi(e)

    /**
     * Shows or hides the drive scene by the rules in the class notes. Shown over the still car,
     * it starts from rest (its first frame equals the picture) and the picture under it becomes
     * boya_gece in the same turn. Rule kept everywhere: scene hidden ⇒ the picture has the car.
     * Leaving the drive screen and dusk hide it in [sahneGecisi] (the cover is the still car);
     * what is left for here is the diagnostics panel, behind which the still car comes back.
     * Re-checks the speed loop.
     */
    private fun surusKatmaniniGuncelle() {
        val s = surusSahnesi
        if (s != null) {
            val goster = !Tema.palet.gunduz && !acilisSuruyor && !sahneGecisiSuruyor &&
                ekranlar.gecerli == Ekran.SURUS && s.hazir && !teshis.acik
            if (goster && !surusGorunur) {
                surusGorunur = true
                s.visibility = View.VISIBLE
                if (sahne.istenen != R.drawable.boya_gece) s.durgundanBasla()
                sahne.goster(R.drawable.boya_gece)
                // The still car is the cover when the scene goes: keep it decoded (Ö-2).
                Gorseller.al(resources, R.drawable.sahne_surus) {}
            } else if (!goster && surusGorunur && !ortuBekleniyor) {
                surusGizle()
                if (ekranlar.gecerli == Ekran.SURUS) sahne.goster(sahneKaynagi(Ekran.SURUS))
            }
        }
        hizDongusunuGuncelle()
    }

    private fun surusGizle() {
        surusGorunur = false
        surusSahnesi?.visibility = View.INVISIBLE
        hizDongusunuGuncelle()
    }

    /**
     * The HUD's own loop runs only with the drive screen in front, resumed and not covered by the
     * diagnostics; the once-a-second unknown/stale check likewise.
     */
    private fun hizDongusunuGuncelle() {
        if (!::hizDongusu.isInitialized) return
        val etkin = devam && ekranlar.gecerli == Ekran.SURUS && !teshis.acik
        hizDongusu.etkin = etkin
        hizDongusu.yenile()
        ana.removeCallbacks(hizEtiketi)
        if (etkin) hizEtiketi.run()
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
        val yeni = istenenSahne(g)
        if (yeni != sahne.istenen) sahneGecisi(yeni, null)
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
        // Driving: the diagnostics panel (full-screen, over everything) never stays over the road.
        if (hedef == Ekran.SURUS) teshis.kapat()
        val g = ekranlar.gecerli ?: return
        when {
            g == Ekran.ACILIS -> return
            g.temel -> ekranlar.goster(hedef)
            hedef == Ekran.SURUS -> ekranlar.goster(Ekran.SURUS)
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
        // Picture first: at dusk it hides the drive scene and fades to the day car; at dawn the
        // fade to the night car runs before the scene may show.
        sahneyiGuncelle()
        surusKatmaniniGuncelle()
    }

    override fun ekranDegisti(eski: Ekran?, yeni: Ekran) {
        altCubuk.etkinYap(if (yeni == Ekran.UYGULAMALAR) AltCubuk.Simge.UYGULAMALAR else null)
        // Y-1 has no bottom bar; it fades in with the first real screen.
        if (yeni == Ekran.ACILIS) {
            cubukKap.visibility = View.INVISIBLE
        } else if (cubukKap.visibility != View.VISIBLE) {
            // K-93 interface entrance, with the first screen (alpha only: a rise would lift the bar off the edge).
            Zamanlama.giris(cubukKap, 0, 0f).start()
        }
        uyari.guncelle(AracVerisi.durum, yeni)
        isikGuncelle()
        surusKatmaniniGuncelle()
        if (yeni == Ekran.SURUS) {
            // A still loop holds an old value: one frame from whichever reader runs.
            hareket?.surusUyandir()
        }
    }

    private fun uyariDegisti() {
        (ekranlar.kurulduysa(Ekran.PARK) as? ParkEkrani)?.let {
            it.uyariSusturuldu = uyari.susturuldu
            it.baslikUyarida = uyari.parkBasliginiKullaniyor
        }
        isikGuncelle()
    }

    /**
     * Diagnostics' "Kapı uyarısını sıfırla": in-app memory only (the door service's records, the
     * shared kapiAcik/bagajAcik → null). Nothing goes to the car or the system (K-29).
     */
    private fun kapiUyarisiniSifirla() {
        KapiServisi.uygulamaIciSifirla()
    }

    /** The light line points at what needs attention (IsikCizgisi.Mod). */
    private fun isikGuncelle() {
        val d = AracVerisi.durum
        val g = ekranlar.gecerli
        val acik = (d.kapiAcik == true || d.bagajAcik == true) && !uyari.susturuldu
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
            "Görünüm: ${ekranlar.gecerli} (temel ${ekranlar.temel}) · tema ${Tema.palet.ad} · uyarı ${uyari.gorunum}${if (uyari.susturuldu) " (gizlendi)" else ""} · ışık ${isik.mod}",
            temaSatiri(),
            "Araç: hareket ${d.hareketHali ?: "?"} · hız ${d.hiz ?: "—"} · kapı ${d.kapiAcik ?: "?"} · bagaj ${d.bagajAcik ?: "?"} · dış ${d.disSicaklik ?: "—"}",
            "Klima (çözülen): ${d.klima ?: "—"}",
            "Erişilebilirlik servisi ayarı: ${if (ErisilebilirlikDurumu.etkinMi(this)) "açık" else "kapalı"}",
            "Klip: ${olcum?.let { "${it.klip} · ilk kare ${it.ilkKareMs?.let { ms -> "$ms ms" } ?: "gelmedi"} · bırakma ${it.birakmaMs?.let { ms -> "$ms ms" } ?: "—"}" } ?: "ölçülmedi"}",
            "Kapı kayıtları: liste dışı pencere ${Teshis.listeDisiPencere} · zaman aşımıyla düşen ${Teshis.kapiZamanAsimi}",
            "Kare hızı sayacı: ${hareket?.sayac?.text ?: "—"}",
            surusSatiri(),
            hizSatiri(),
            "",
        ) + Teshis.satirlar()
    }

    /** Drive scene: state, 60/30 tempo (research decision 8), the panel's refresh rate. */
    private fun surusSatiri(): String {
        val s = surusSahnesi ?: return "Sürüş sahnesi: yok"
        @Suppress("DEPRECATION") // Activity.getDisplay is API 30; the unit runs 27.
        val hz = windowManager.defaultDisplay.refreshRate
        val durum = when {
            !s.hazir -> "yükleniyor"
            !surusGorunur -> "gizli"
            !s.calisiyor -> "görünür, durdu"
            s.donguBekliyor -> "çalışıyor"
            else -> "görünür, durgun"
        }
        val t = s.tempo
        val tempo = if (t.yariHiz) "yarı hız (her 2. kare)" else "tam hız"
        return "Sürüş sahnesi: $durum · tempo $tempo · yarıya iniş ${t.yariyaInisSayisi} · " +
            "ekran ${String.format(java.util.Locale.ROOT, "%.1f", hz)} Hz · son hız ${String.format(java.util.Locale.ROOT, "%.1f", s.sonHizMs * MS_KMH)} km/sa"
    }

    /** Who reads the smoothed speed now (K-96 single reader). */
    private fun hizSatiri(): String {
        val sahneOkur = hareket?.surus?.calisiyor == true
        val d = hizDongusu
        return "Hız göstergesi: okuyan ${if (sahneOkur) "sürüş sahnesi" else "ekran döngüsü"} · ekran döngüsü " +
            "${if (!d.etkin) "kapalı" else if (d.bekliyor) "çalışıyor" else "durgun"} · ${d.okumaSayisi} okuma"
    }

    private fun temaSatiri(): String {
        val k = Tema.gecerliKonum()
        val g = Gunes.gun(System.currentTimeMillis(), k.enlem, k.boylam)
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
        val kaynak = if (k == Tema.ISTANBUL) "varsayılan İstanbul" else "son GPS"
        val gun = when (g.tur) {
            Gunes.Tur.NORMAL -> "doğum ${f.format(java.util.Date(g.dogum!!))} · batım ${f.format(java.util.Date(g.batim!!))}"
            Gunes.Tur.KUTUP_GECESI -> "güneş doğmuyor"
            Gunes.Tur.GECE_YARISI_GUNESI -> "güneş batmıyor"
        }
        return "Tema: $gun · konum $kaynak (${String.format(java.util.Locale.ROOT, "%.2f, %.2f", k.enlem, k.boylam)})"
    }

    // --- Lifecycle ---

    override fun onResume() {
        super.onResume()
        TamEkran.uygula(this)
        ana.removeCallbacks(dakika)
        dakika.run()
        for (b in baglayicilar) b.basla()
        // One lambda wakes both readers; each one decides by itself whether it runs (step 8).
        Hiz.yumusatici.uyandir = {
            hareket?.surusUyandir()
            hizDongusu.uyandir()
        }
        devam = true
        hizDongusunuGuncelle()
        // onTrimMemory may have emptied the cache while away: the cover must be ready (Ö-2).
        if (surusGorunur) Gorseller.al(resources, R.drawable.sahne_surus) {}
        // Settings may have changed while we were away (service switched on, permission given).
        ipuclariniYenile()
        ekranlar.veriDegisti(AracVerisi.durum)
    }

    override fun onPause() {
        devam = false
        hizDongusunuGuncelle()
        Hiz.yumusatici.uyandir = null
        for (b in baglayicilar.asReversed()) b.dur()
        // Paused but maybe still visible (a dialog, a translucent window): no frame moves the
        // number any more and GPS has stopped (the smoother is reset), so show "—", not a frozen value (Ö-3).
        surusEkrani?.durumGoster(Hiz.yumusatici.bilinmiyor, true)
        ana.removeCallbacks(dakika)
        super.onPause()
    }

    override fun onDestroy() {
        for (b in baglayicilar.asReversed()) b.birak()
        ana.removeCallbacksAndMessages(null)
        // Closing the panel must not show the released drive scene again (K-1).
        teshis.degisti = null
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

        /** m/s → km/h. */
        private const val MS_KMH = 3.6f

        /** Unknown/stale check while the loops are stopped. */
        private const val ETIKET_MS = 1000L

        /** Longest wait for the still car's decode before a change away from the drive scene. */
        private const val ORTU_BEKLEME_MS = 400L
    }
}
