package dev.kokpit.anaekran.ekran

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.uygulama.Uygulama
import dev.kokpit.anaekran.uygulama.UygulamaListesi
import dev.kokpit.anaekran.veri.AracDurumu

/**
 * Installed apps as a 4-column grid (a ListView of rows: view recycling and a footer without
 * AndroidX). Sorted by name, without Kokpit itself; a tap opens the app. Names appear first,
 * icons when they are drawn (UygulamaListesi). The status line (time, outside temperature)
 * is the same as on Y-5. The last row is the small "Yazı tipi lisansları" link, which swaps
 * the list for the OFL texts.
 */
class UygulamalarEkrani(context: Context, private val ac: (Uygulama) -> Unit) : EkranGorunumu {

    private val kok = MutlakDuzen(context)
    override val gorunum: View = kok

    private var palet = Palet.GECE
    private var uygulamalar: List<Uygulama> = emptyList()
    private var yuklendi = false

    private val satir: DurumSatiri
    private val baslik = context.metin(Yazi.sansOrta, 34, palet.tx, -0.015f).apply { setText(R.string.ekran_uygulamalar) }
    private val durum = context.metin(Yazi.sans, 16, palet.mu).apply { setText(R.string.yukleniyor) }
    private val geriDugmesi = context.metin(Yazi.sansOrta, 17, palet.tx).apply {
        setText(R.string.geri)
        gravity = Gravity.CENTER
        visibility = View.GONE
        setOnClickListener { lisansiKapat() }
    }

    private val lisansYazisi = context.metin(Yazi.sans, 14, palet.mu).apply {
        setLineSpacing(0f, 1.35f)
        setTextIsSelectable(false)
    }
    private val lisans = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(lisansYazisi, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
    }

    private val lisansSatiri = context.metin(Yazi.sans, 14, palet.mu).apply {
        setText(R.string.lisanslar)
        gravity = Gravity.CENTER
        minHeight = u(72)
        setOnClickListener { lisansiAc() }
    }

    private val satirAdaptoru = SatirAdaptoru()
    private val liste = ListView(context).apply {
        divider = null
        dividerHeight = 0
        selector = ColorDrawable(Color.TRANSPARENT)
        cacheColorHint = Color.TRANSPARENT
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addFooterView(lisansSatiri, null, false)
        adapter = satirAdaptoru
    }

    init {
        satir = DurumSatiri(kok)
        kok.ekle(baslik, 40, 84)
        kok.ekle(durum, 40, 134)
        kok.ekle(geriDugmesi, 744, 76, 120, 76, MutlakDuzen.Hiza.SAG)
        kok.ekle(liste, 24, 176, 720, 756)
        kok.ekle(lisans, 40, 176, 688, 740)
    }

    override fun gosterildi() {
        lisansiKapat()
        // Fresh list each time (new installs show up); icons come from the per-app cache.
        satir.saatiYaz()
        UygulamaListesi.yukle(gorunum.context, u(SIMGE), ::listeGeldi, ::listeGeldi)
    }

    private fun listeGeldi(liste: List<Uygulama>) {
        uygulamalar = liste
        yuklendi = true
        durumuYaz()
        satirAdaptoru.notifyDataSetChanged()
    }

    override fun veriDegisti(d: AracDurumu) = satir.veri(d)
    override fun dakikaGecti() = satir.saatiYaz()

    private fun durumuYaz() {
        val c = gorunum.context
        durum.text = when {
            !yuklendi -> c.getString(R.string.yukleniyor)
            uygulamalar.isEmpty() -> c.getString(R.string.uygulama_yok)
            else -> c.resources.getQuantityString(R.plurals.uygulama_sayisi, uygulamalar.size, uygulamalar.size)
        }
    }

    override fun temaDegisti(p: Palet) {
        palet = p
        satir.tema(p)
        baslik.setTextColor(p.tx)
        durum.setTextColor(p.mu)
        geriDugmesi.setTextColor(p.tx)
        lisansYazisi.setTextColor(p.mu)
        lisansSatiri.setTextColor(p.mu)
        satirAdaptoru.notifyDataSetChanged()
    }

    private fun lisansiAc() {
        if (lisansYazisi.text.isNullOrEmpty()) lisansYazisi.text = lisansMetni(gorunum.context)
        liste.visibility = View.GONE
        lisans.visibility = View.VISIBLE
        geriDugmesi.visibility = View.VISIBLE
        baslik.setText(R.string.lisanslar)
        durum.setText(R.string.lisans_alt)
    }

    private fun lisansiKapat() {
        lisans.visibility = View.GONE
        liste.visibility = View.VISIBLE
        geriDugmesi.visibility = View.GONE
        baslik.setText(R.string.ekran_uygulamalar)
        durumuYaz()
    }

    /** True if the licence page was open (back key closes it first). */
    fun geri(): Boolean {
        if (lisans.visibility != View.VISIBLE) return false
        lisansiKapat()
        return true
    }

    private inner class SatirAdaptoru : BaseAdapter() {
        override fun getCount() = (uygulamalar.size + SUTUN - 1) / SUTUN
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val satir = (convertView as? LinearLayout) ?: yeniSatir(parent.context)
            for (i in 0 until SUTUN) {
                val hucre = satir.getChildAt(i) as LinearLayout
                val uyg = uygulamalar.getOrNull(position * SUTUN + i)
                hucre.visibility = if (uyg == null) View.INVISIBLE else View.VISIBLE
                if (uyg == null) {
                    hucre.setOnClickListener(null)
                    continue
                }
                (hucre.getChildAt(0) as ImageView).setImageDrawable(uyg.simge)
                (hucre.getChildAt(1) as TextView).apply {
                    text = uyg.ad
                    setTextColor(palet.tx)
                }
                hucre.contentDescription = uyg.ad
                hucre.setOnClickListener { ac(uyg) }
            }
            return satir
        }

        private fun yeniSatir(c: Context) = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(SUTUN) {
                val hucre = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(u(8), u(18), u(8), u(14))
                    isClickable = true
                    addView(ImageView(c), LinearLayout.LayoutParams(u(SIMGE), u(SIMGE)))
                    addView(
                        c.metin(Yazi.sans, 15, palet.tx).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setLineSpacing(0f, 1.15f)
                        },
                        LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = u(10) },
                    )
                }
                addView(hucre, LinearLayout.LayoutParams(0, u(SATIR), 1f))
            }
        }
    }

    companion object {
        private const val SUTUN = 4
        private const val SIMGE = 64
        private const val SATIR = 150
        private val LISANSLAR = listOf(
            "Geist" to "licenses/Geist-OFL.txt",
            "Geist Mono" to "licenses/GeistMono-OFL.txt",
            "Instrument Serif" to "licenses/InstrumentSerif-OFL.txt",
            "Barlow Condensed" to "licenses/BarlowCondensed-OFL.txt",
        )

        fun lisansMetni(c: Context): String = buildString {
            append(c.getString(R.string.lisans_giris)).append("\n\n")
            for ((ad, yol) in LISANSLAR) {
                append("— ").append(ad).append(" —\n\n")
                append(runCatching { c.assets.open(yol).bufferedReader().use { it.readText() } }.getOrDefault(c.getString(R.string.lisans_okunamadi)))
                append("\n\n")
            }
        }
    }
}
