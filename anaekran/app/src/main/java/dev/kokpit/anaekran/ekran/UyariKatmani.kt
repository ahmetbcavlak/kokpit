package dev.kokpit.anaekran.ekran

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.widget.ImageView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.AracHaritasi
import dev.kokpit.anaekran.arayuz.Cam
import dev.kokpit.anaekran.arayuz.Cizgiler
import dev.kokpit.anaekran.arayuz.Cizim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.MutlakDuzen.Hiza
import dev.kokpit.anaekran.arayuz.SahneKatmani
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.renkle
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.HareketHali
import dev.kokpit.anaekran.veri.Teshis

/**
 * The warning layer (above the screens, below the bottom bar): door / boot open and the short
 * "closed" confirmation. Fed only by kapiAcik / bagajAcik — true shows, false is "closed",
 * null (service off, nothing seen yet) shows nothing and is never read as closed.
 *
 *  - Park screen in front — Y-4 (door) / Y-10 (boot): the title block at (40, 92) in Geist 500 34
 *    with its line ("Araç duruyor · kapatınca bu uyarı kendiliğinden kalkar"), and for the door
 *    the warm floor glow under the car. The park screen hides its own title meanwhile and
 *    colours its HUD; the scene layer switches to the boot picture (night).
 *  - Drive screen or a panel — Y-14: the red glass card at (24, 68, 720, 112) with the warning
 *    triangle; on the drive screen also the red ring on the open door/boot of the car.
 *  - Door and boot both open: one title, "Kapı ve bagaj açık", both HUD items in accent.
 *  - Closed (true → false, nothing else open) — the K-68 "kapandı" state in Y-13's voice: on the
 *    park screen the scene dims (Y-13: brightness .55) under a centred serif "Kapı kapandı.";
 *    elsewhere the same card as Y-14 in the calm `ok` tint (the speed must stay readable while
 *    driving). It stays [KAPANDI_MS] and goes by itself; a tap removes it at once.
 *    Not after the door service's 10 s time-out (Teshis.kapiZamanAsimi rose with the false):
 *    then the popup was only never heard of again, not seen closing.
 *  - During the intro nothing is shown (the warning comes when the park/drive screen is in front).
 *
 * Nothing here is clickable except the confirmation (tap to dismiss): touches fall through.
 */
@SuppressLint("ViewConstructor")
class UyariKatmani(
    context: Context,
    /** Records dropped by the door service's 10 s time-out so far (Teshis.kapiZamanAsimi). */
    private val zamanAsimiSayaci: () -> Int = { Teshis.kapiZamanAsimi },
) : MutlakDuzen(context) {

    private var palet = Palet.GECE
    private var durum = AracDurumu()
    private var ekran: Ekran? = null
    private var oncekiKapi: Boolean? = null
    private var oncekiBagaj: Boolean? = null
    private var sonZamanAsimi = zamanAsimiSayaci()

    /** What is shown now (tests, MainActivity's light line). */
    enum class Gorunum { YOK, PARK_BASLIGI, KART, KAPANDI_PARK, KAPANDI_KART }

    var gorunum: Gorunum = Gorunum.YOK
        private set

    /** Hears every [gorunum] change (MainActivity: park title, light line). */
    var degisti: ((Gorunum) -> Unit)? = null

    /** True while this layer uses the park screen's title area (Y-4/Y-10 title, Y-13 look). */
    val parkBasliginiKullaniyor: Boolean
        get() = gorunum == Gorunum.PARK_BASLIGI || gorunum == Gorunum.KAPANDI_PARK

    private var halka: PointF? = null
    private var isilti = false
    private val cizim = Cizim(context) { ciz(it) }

    // Y-4 / Y-10 title block
    private val parkBaslik = context.metin(Yazi.sansOrta, 34, palet.tx, -0.015f)
    private val parkAlt = context.metin(Yazi.sans, 16, palet.mu)

    // Y-14 card
    private val kart = MutlakDuzen(context)
    private val kartSimge = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val kartBaslik = context.metin(Yazi.sansOrta, 26, palet.tx)
    private val kartAlt = context.metin(Yazi.sans, 16, palet.mu)

    // Y-13 look
    private val kapandi = MutlakDuzen(context).apply {
        isClickable = true
        setOnClickListener { kapandiyiBitir() }
    }
    private val kapandiBaslik = context.metin(Yazi.serif, 72, palet.tx)
    private val kapandiAlt = context.metin(Yazi.sans, 18, palet.mu)

    private val kapandiBitti = Runnable { kapandiyiBitir() }
    private var kapandiMetni = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        ekle(cizim, 0, 0, 768, 932)
        ekleMetin(parkBaslik, 40, 92, satir = 1.15f)
        ekleMetin(parkAlt, 40, 92 + 34 * 1.15f + 12, satir = 1.4f, w = 640)
        ekle(kart, 24, 68, 720, 112)
        kart.ekle(kartSimge, 24, 39, 34, 34)
        // Title 26 + 4 + subtitle 16 (normal line heights), centred in the 112 px card.
        val blok = 26 * 1.3f + 4 + 16 * 1.3f
        val ust = (112 - blok) / 2f
        kart.ekleMetin(kartBaslik, 76, ust)
        kart.ekleMetin(kartAlt, 76, ust + 26 * 1.3f + 4)
        ekle(kapandi, 0, 0, 768, 932)
        kapandi.ekleMetin(kapandiBaslik, 384, 130, satir = 1f, hiza = Hiza.ORTA)
        kapandi.ekleMetin(kapandiAlt, 384, 130 + 72 + 14, hiza = Hiza.ORTA)
        kart.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        gizle()
    }

    /** New data and/or a new screen in front. */
    fun guncelle(d: AracDurumu, e: Ekran?) {
        // A record the service dropped by its 10 s time-out (popup never seen in the window list)
        // also turns true into false, but nobody saw it close: the warning goes without "kapandı".
        val sayac = zamanAsimiSayaci()
        val zamanAsimiyla = sayac != sonZamanAsimi
        sonZamanAsimi = sayac
        val kapandiOldu = !zamanAsimiyla &&
            ((oncekiKapi == true && d.kapiAcik == false) || (oncekiBagaj == true && d.bagajAcik == false))
        val kapiKapandi = oncekiKapi == true && d.kapiAcik == false
        val bagajKapandi = oncekiBagaj == true && d.bagajAcik == false
        oncekiKapi = d.kapiAcik
        oncekiBagaj = d.bagajAcik
        durum = d
        ekran = e
        val acik = d.kapiAcik == true || d.bagajAcik == true
        if (acik) {
            removeCallbacks(kapandiBitti)
            kapandiMetni = 0
        } else if (kapandiOldu && e != null && e != Ekran.ACILIS) {
            kapandiMetni = when {
                kapiKapandi && bagajKapandi -> R.string.kapandi_ikisi
                kapiKapandi -> R.string.kapandi_kapi
                else -> R.string.kapandi_bagaj
            }
            removeCallbacks(kapandiBitti)
            postDelayed(kapandiBitti, KAPANDI_MS)
        }
        yenile()
    }

    private fun kapandiyiBitir() {
        removeCallbacks(kapandiBitti)
        kapandiMetni = 0
        yenile()
    }

    private fun yenile() {
        val once = gorunum
        yenileIc()
        if (gorunum != once) degisti?.invoke(gorunum)
    }

    private fun yenileIc() {
        val d = durum
        val e = ekran
        val c = context
        val kapi = d.kapiAcik == true
        val bagaj = d.bagajAcik == true
        gizle()
        halka = null
        isilti = false
        if (e == null || e == Ekran.ACILIS) {
            gorunum = Gorunum.YOK
            cizim.invalidate()
            return
        }
        val duruyor = d.hareketHali == HareketHali.PARK
        gorunum = when {
            (kapi || bagaj) && e == Ekran.PARK -> {
                parkBaslik.yaz(c.getString(baslikMetni(kapi, bagaj)))
                val alt = c.getString(if (kapi) R.string.uyari_kapi_alt else R.string.uyari_bagaj_alt)
                parkAlt.yaz(if (duruyor) c.getString(R.string.arac_duruyor) + c.getString(R.string.ayrac) + alt else buyukHarf(alt))
                parkBaslik.visibility = VISIBLE
                parkAlt.visibility = VISIBLE
                // Y-4: the open door lights the floor under the car (not in Y-10).
                isilti = kapi && !bagaj
                Gorunum.PARK_BASLIGI
            }
            kapi || bagaj -> {
                kartGoster(
                    c.getString(baslikMetni(kapi, bagaj)),
                    if (e == Ekran.SURUS) c.getString(R.string.uyari_surus_alt) else buyukHarf(c.getString(R.string.uyari_kapi_alt)),
                    R.drawable.ic_uyari, palet.danger,
                )
                if (e == Ekran.SURUS) {
                    val n = AracHaritasi.noktalar(SahneKatmani.kare(SahneKatmani.kaynak(Ekran.SURUS, palet.gunduz, false)))
                    halka = if (kapi) n.kapi else n.bagaj
                }
                Gorunum.KART
            }
            kapandiMetni != 0 && e == Ekran.PARK -> {
                kapandiBaslik.yaz(c.getString(kapandiMetni))
                kapandiAlt.yaz(c.getString(R.string.kapandi_alt))
                kapandi.visibility = VISIBLE
                Gorunum.KAPANDI_PARK
            }
            kapandiMetni != 0 -> {
                kartGoster(c.getString(kapandiMetni).removeSuffix("."), c.getString(R.string.kapandi_alt), R.drawable.ic_tamam, palet.ok)
                Gorunum.KAPANDI_KART
            }
            else -> Gorunum.YOK
        }
        cizim.invalidate()
    }

    private fun baslikMetni(kapi: Boolean, bagaj: Boolean) = when {
        kapi && bagaj -> R.string.uyari_ikisi
        kapi -> R.string.uyari_kapi
        else -> R.string.uyari_bagaj
    }

    private fun buyukHarf(s: String) = s.replaceFirstChar { it.titlecase(DurumSatiri.TR) }

    private fun kartGoster(baslik: String, alt: String, simge: Int, ton: Int) {
        kartBaslik.yaz(baslik)
        kartAlt.yaz(alt)
        kartSimge.setImageResource(simge)
        kartSimge.imageTintList = ColorStateList.valueOf(ton)
        kart.background = if (ton == palet.danger) Cam.arka(palet, 26f, ton, 0.22f, 0.10f) else Cam.arka(palet, 26f, ton, 0.16f, 0.06f)
        kart.contentDescription = "$baslik. $alt"
        kart.visibility = VISIBLE
    }

    private fun gizle() {
        parkBaslik.visibility = GONE
        parkAlt.visibility = GONE
        kart.visibility = GONE
        kapandi.visibility = GONE
    }

    private fun ciz(c: Canvas) {
        if (isilti) Cizgiler.isilti(c, ISILTI, palet.acc, 0.22f)
        halka?.let { Cizgiler.halka(c, palet, it) }
    }

    fun temaUygula(p: Palet) {
        palet = p
        parkBaslik.renkle(p.tx)
        parkAlt.renkle(p.mu)
        kartBaslik.renkle(p.tx)
        kartAlt.renkle(p.mu)
        kapandiBaslik.renkle(p.tx)
        kapandiAlt.renkle(p.mu)
        // Y-13: the scene at brightness .55 → a 45 % veil of the background colour.
        kapandi.setBackgroundColor(Color.argb(115, Color.red(p.bg), Color.green(p.bg), Color.blue(p.bg)))
        yenile()
    }

    /** Texts on screen (tests). */
    internal val metinler: List<CharSequence>
        get() = listOf(parkBaslik, parkAlt, kartBaslik, kartAlt, kapandiBaslik, kapandiAlt)
            .filter { gorunurMu(it) }
            .map { it.text }

    private fun gorunurMu(v: android.view.View): Boolean {
        var x: android.view.View = v
        while (x !== this) {
            if (x.visibility != VISIBLE) return false
            x = x.parent as? android.view.View ?: return false
        }
        return true
    }

    companion object {
        /**
         * How long the "closed" confirmation stays: 2.5 s. Long enough to read a two-word title at
         * a glance (glance guidance for in-car displays keeps single glances ≤ 2 s) and short
         * enough not to hold the park screen behind a veil; a tap ends it sooner.
         */
        const val KAPANDI_MS = 2500L

        /** Y-4 floor glow: radial in (316, 573, 460, 110), accent at .22. */
        private val ISILTI = RectF(316f, 573f, 776f, 683f)
    }
}
