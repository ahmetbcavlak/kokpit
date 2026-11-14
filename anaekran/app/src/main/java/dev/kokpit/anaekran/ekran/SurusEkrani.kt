package dev.kokpit.anaekran.ekran

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.arayuz.Cizgiler
import dev.kokpit.anaekran.arayuz.Cizim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.MutlakDuzen.Hiza
import dev.kokpit.anaekran.arayuz.Olcek
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.renkle
import dev.kokpit.anaekran.arayuz.saydam
import dev.kokpit.anaekran.arayuz.uf
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drive screen, Y-3 (night) / Y-11 (day): the HUD only — the GPS speed in big Barlow Condensed
 * 200 with the 0–180 arc above it, "km/sa · gps", the climate HUD on the left, the ruler. The
 * car is not here: at night the drive scene (its own root layer, under this screen) draws the
 * moving car, the lane lines and the speed lines; by day the scene picture has the still car
 * and this screen keeps 0.1's static speed lines. HUD fixed, the world moves (K-94).
 * Not drawn: "bu yolculuk" (no trip source), the CarPlay/Haritalar row, the media card (K-74);
 * no door/boot HUD (not in Y-3 — the warning layer handles open doors).
 *
 * Speed (K-96, HizYumusatici's screen contract): the owner calls [hizGoster] on every frame with
 * the smoothed speed — from the drive scene's frame while it runs, else from its own loop
 * ([HizDongusu]); AracDurumu.hiz is not read any more.
 *  - unknown: "—" in the muted colour, no arc progress, and the unit line says calmly that GPS
 *    is awaited (or that the location permission is missing);
 *  - known: the rounded in-between value on every frame; the text is written only when the
 *    whole number changes (the TextView has a fixed size, so it never re-measures the screen);
 *    the arc follows the unrounded value ([HizYayi], a small view of its own, so a frame
 *    re-records a dozen arc operations, not the whole screen);
 *  - stale (5 s without a sample): the value stays, the "gps" word drops to 40 %.
 */
class SurusEkrani(context: Context, private val konumIzni: () -> Boolean) : EkranGorunumu {

    private val kok = MutlakDuzen(context)
    override val gorunum: View = kok

    private var palet = Palet.GECE

    private val cizim = Cizim(context) { ciz(it) }
    private val yay = HizYayi(context)
    private val satir: DurumSatiri
    private val sayi = context.metin(Yazi.ince, 232, palet.tx, -0.02f, tabular = true).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        maxLines = 1
    }
    private val birim = context.metin(Yazi.mono, 13, palet.mu, 0.06f)
    private val klimaEtiket = context.metin(Yazi.mono, 12, palet.hud, 0.04f).apply { setText(R.string.klima) }
    private val klimaDeger = context.metin(Yazi.sansOrta, 18, palet.tx, tabular = true)

    /** Whole km/h on screen; [YOK] = the dash. */
    private var tam = BOS
    /** Last smoothed km/h shown (for [durumGoster]). */
    private var sonKmh = 0f
    private var bilinmiyor = true
    private var eski = false
    private var izin = true
    /** Unit line state: 0 normal, 1 stale (gps at 40 %), 2 waiting for GPS, 3 no permission. */
    private var birimDurumu = -1
    private val rakamlar = arrayOfNulls<String>(RAKAM_SAYISI)

    init {
        kok.ekle(cizim, 0, 0, 768, 932)
        kok.ekle(yay, 0, HizYayi.UST, 768, HizYayi.YUKSEKLIK)
        satir = DurumSatiri(kok)
        // 232 px, line-height 0.8 at top 168, centred. Fixed box: "188" with tabular digits + slack.
        val genislik = Math.ceil((sayi.paint.measureText("188") / uf(1)).toDouble()).toInt() + 24
        val fm = sayi.paint.fontMetrics
        val yukseklik = Math.ceil(((fm.descent - fm.ascent) / uf(1)).toDouble()).toInt()
        kok.ekle(sayi, 384, 168f + (232f * 0.8f - yukseklik) / 2f, genislik, yukseklik, Hiza.ORTA)
        kok.ekleMetin(birim, 384, 364, hiza = Hiza.ORTA)
        kok.ekleMetin(klimaEtiket, 40, 250)
        kok.ekleMetin(klimaDeger, 40, 250 + 12 * 1.3f + 8)
        izin = konumIzni()
        ciz(bilinmiyorDegisti = true)
    }

    private fun ciz(c: Canvas) {
        val p = palet
        // Night: the drive scene draws the speed lines itself (opacity by speed); drawing them
        // here too would double them (HareketBaglayici step 12). By day there is no scene.
        if (p.gunduz) Cizgiler.hizCizgileri(c, p)
        Cizgiler.hizYayiTaban(c, p)
        // Night ruler under the car, between it and the near lane line (0.2 demo Y-3h, y 646.8);
        // by day 0.1's Y-11 place (602).
        Cizgiler.cetvel(c, p, if (p.gunduz) 602f else 646.8f)
        Cizgiler.arti(c, p, 721f, 396f)
    }

    /**
     * One frame of the smoothed speed: [kmh] km/h (≥ 0; ignored when [bilinmiyorMu]), [eskiMi] =
     * no sample for 5 s. Allocation-free unless the whole number changes for the first time.
     */
    fun hizGoster(kmh: Float, bilinmiyorMu: Boolean, eskiMi: Boolean) {
        val bDegisti = bilinmiyorMu != bilinmiyor
        bilinmiyor = bilinmiyorMu
        eski = eskiMi
        if (!bilinmiyorMu) sonKmh = if (kmh.isNaN() || kmh < 0f) 0f else kmh
        ciz(bDegisti)
    }

    /** Unknown/stale changed while no frame comes (stopped loops): redraw with the last value. */
    fun durumGoster(bilinmiyorMu: Boolean, eskiMi: Boolean) = hizGoster(sonKmh, bilinmiyorMu, eskiMi)

    private fun ciz(bilinmiyorDegisti: Boolean) {
        val yeni = if (bilinmiyor) YOK else sonKmh.roundToInt().coerceAtLeast(0)
        if (yeni != tam) {
            tam = yeni
            sayi.yaz(if (yeni == YOK) Bicim.YOK else rakam(yeni))
        }
        if (bilinmiyorDegisti) sayi.renkle(if (bilinmiyor) palet.mu else palet.tx)
        yay.ayarla(if (bilinmiyor) -1f else sonKmh)
        birimYaz()
    }

    private fun rakam(n: Int): String {
        if (n >= RAKAM_SAYISI) return n.toString()
        return rakamlar[n] ?: n.toString().also { rakamlar[n] = it }
    }

    private fun birimYaz(zorla: Boolean = false) {
        val d = when {
            bilinmiyor -> if (izin) 2 else 3
            eski -> 1
            else -> 0
        }
        if (d == birimDurumu && !zorla) return
        birimDurumu = d
        val c = gorunum.context
        when (d) {
            2 -> birim.text = c.getString(R.string.hiz_bekleniyor)
            3 -> birim.text = c.getString(R.string.hiz_izin_yok)
            else -> {
                val m = c.getString(R.string.hiz_birim)
                val i = m.lastIndexOf(GPS)
                birim.text = if (d == 1 && i >= 0) {
                    SpannableString(m).apply {
                        setSpan(ForegroundColorSpan(saydam(palet.mu, ESKI_OPAKLIK)), i, i + GPS.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    m
                }
            }
        }
    }

    override fun veriDegisti(d: AracDurumu) {
        val c = gorunum.context
        satir.veri(d)
        val i = konumIzni()
        if (i != izin) {
            izin = i
            birimYaz()
        }
        val k = d.klima
        klimaDeger.yaz(
            when {
                k == null || k.sicaklik == null -> Bicim.YOK
                k.oto == true -> "${Bicim.klimaDerece(k.sicaklik)} · ${c.getString(R.string.oto)}"
                else -> Bicim.klimaDerece(k.sicaklik)
            },
        )
    }

    override fun temaDegisti(p: Palet) {
        palet = p
        satir.tema(p)
        sayi.renkle(if (bilinmiyor) p.mu else p.tx)
        birim.renkle(p.mu)
        birimYaz(zorla = true) // the stale span carries the colour
        klimaEtiket.renkle(p.hud)
        klimaDeger.renkle(p.tx)
        yay.palet = p
        cizim.invalidate()
    }

    override fun dakikaGecti() = satir.saatiYaz()
    override fun gosterildi() = satir.saatiYaz()

    /** The number on screen (tests). */
    val hizMetni: CharSequence get() = sayi.text
    val birimMetni: CharSequence get() = birim.text

    /** True when the unit line's "gps" is drawn at 40 % (tests). */
    val gpsSoluk: Boolean get() = birimDurumu == 1

    /** The km/h the arc shows; < 0 = no progress (tests). */
    val yayKmh: Float get() = yay.kmh

    companion object {
        private const val YOK = -1
        private const val BOS = Int.MIN_VALUE
        private const val RAKAM_SAYISI = 300
        private const val GPS = "gps"

        /** Design pack 0.2 §3: "gps" label soluk = 40 %. */
        const val ESKI_OPAKLIK = 0.4f
    }
}

/**
 * The moving part of the speed arc (Cizgiler.hizYayiIlerleme) in a 768 × [YUKSEKLIK] strip at
 * design y [UST], so a new value re-records and damages only this strip. Redrawn only when the
 * value moved by at least [ESIK] km/h (≈ 0.2 px along the arc): a settled speed costs nothing.
 */
@SuppressLint("ViewConstructor")
class HizYayi(context: Context) : View(context) {

    var palet: Palet = Palet.GECE
        set(value) {
            field = value
            invalidate()
        }

    /** km/h drawn; < 0 = no progress. */
    var kmh = -1f
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun ayarla(yeni: Float) {
        val y = if (yeni < 0f) -1f else yeni
        if (y == kmh) return
        if (y >= 0f && kmh >= 0f && abs(y - kmh) < ESIK) return
        kmh = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (kmh < 0f) return
        val s = canvas.save()
        canvas.scale(Olcek.oran, Olcek.oran)
        canvas.translate(0f, -UST.toFloat())
        Cizgiler.hizYayiIlerleme(canvas, palet, kmh.toDouble())
        canvas.restoreToCount(s)
    }

    override fun hasOverlappingRendering() = false

    companion object {
        /** Strip top / height, design px: ticks from y 148, the end dot down to y 255. */
        const val UST = 136
        const val YUKSEKLIK = 128
        const val ESIK = 0.05f
    }
}
