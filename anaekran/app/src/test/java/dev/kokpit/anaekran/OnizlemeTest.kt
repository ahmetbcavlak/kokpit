package dev.kokpit.anaekran

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import dev.kokpit.anaekran.arayuz.AltCubuk
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.ekran.Ipuclari
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.HareketHali
import dev.kokpit.anaekran.veri.Hiz
import dev.kokpit.anaekran.veri.SahteVeri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the head unit's 768×1024 screen (mdpi = 1 design px per pixel, Android 8.1) with
 * fake data and writes PNG previews to app/build/onizleme/. The PNGs are for eyes, not asserts:
 * compare them with the design package's referans/Y-*.png (0.2 drive: tasarim-0.2 referans/hiz-*.png).
 * Drive previews feed the smoothed speed (Hiz.yumusatici) and step the drive scene's frames
 * (TestYardim.kare), so the HUD and the moving car show what the unit draws at that speed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class OnizlemeTest {

    private val cikti = File("build/onizleme").apply { mkdirs() }
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun hazirla() {
        TestYardim.esZamanli()
        Tema.zorla(Palet.GECE)
        AracVerisi.sifirla()
        listOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.maps" to "Haritalar",
            "com.android.settings" to "Ayarlar",
            "com.hcn.radio" to "Radyo",
            "com.android.chrome" to "Chrome",
            "com.zjinnova.zlink" to "ZLink",
            "com.hcn.AutoMediaPlayer" to "Medya",
            "org.ornek.cokuzun" to "Çok Uzun Adlı Bir Uygulama Örneği",
            "com.android.gallery3d" to "Galeri",
        ).forEach { (p, ad) -> TestYardim.uygulamaKur(p, ad) }
        // Service on and permission given: no hint card, except in the hint preview.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "dev.kokpit.anaekran/.veri.KapiServisi")
    }

    @After
    fun temizle() {
        Tema.zorla(null)
        AracVerisi.sifirla()
        Hiz.yumusatici.sifirla()
    }

    /** [sn] seconds of frames at a steady [kmh] (a GPS sample every second; null = no samples). */
    private fun sur(kmh: Float?, sn: Double) {
        val n = (sn * 60).toInt()
        for (i in 0 until n) {
            if (kmh != null && i % 60 == 0) Hiz.yumusatici.ornek(kmh / 3.6f, TestYardim.simdiNs(), null)
            TestYardim.kare()
        }
    }

    @Test
    fun onizlemeler() {
        SahteVeri.uygula(SahteVeri.parkGece())
        val c = TestYardim.ac()
        val a = c.get()
        val k = a.katmanlar

        // Fonts really are the embedded ones: Barlow Condensed is far narrower than the default.
        val varsayilan = Paint().apply { textSize = 40f; typeface = Typeface.DEFAULT }.measureText("2222")
        val barlow = Paint().apply { textSize = 40f; typeface = Yazi.hafif }.measureText("2222")
        assertTrue("Barlow Condensed loaded ($barlow vs $varsayilan)", barlow < varsayilan * 0.85f)

        // Intro: the moment before the fade — Y-1 over the clip's last frame (sahne_karsilama).
        assertEquals(Ekran.ACILIS, k.ekranlar.gecerli)
        val sonKare = android.widget.ImageView(a).apply { setImageResource(R.drawable.sahne_karsilama) }
        k.video.addView(sonKare)
        cek(a, "01-acilis-gece")
        k.video.removeView(sonKare)
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.PARK, k.ekranlar.gecerli)
        cek(a, "02-park-gece")

        AracVerisi.guncelle { kapiAcik(true) }
        TestYardim.gecisleriBitir()
        cek(a, "03-kapi-acik-park-gece")
        AracVerisi.guncelle { kapiAcik(false) }
        TestYardim.bekle(1200) // K-93 entrance of the veil (the confirmation stays 2.5 s)
        cek(a, "04-kapandi-gece")
        TestYardim.gecisleriBitir()

        AracVerisi.guncelle { bagajAcik(true) }
        TestYardim.gecisleriBitir()
        cek(a, "05-bagaj-acik-gece")
        AracVerisi.guncelle { kapiAcik(true) }
        TestYardim.gecisleriBitir()
        cek(a, "06-kapi-bagaj-acik-gece")
        AracVerisi.guncelle { kapiAcik(false); bagajAcik(false) }
        TestYardim.gecisleriBitir()

        AracVerisi.guncelle { hiz(62.0); hareketHali(HareketHali.SURUS) }
        Hiz.yumusatici.ornek(62f / 3.6f, TestYardim.simdiNs(), null)
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.SURUS, k.ekranlar.gecerli)
        assertTrue(a.surusGorunur)
        sur(62f, 3.0)
        cek(a, "07-surus-62-gece")
        AracVerisi.guncelle { kapiAcik(true) }
        sur(62f, 1.2) // the card's K-93 entrance
        cek(a, "08-kapi-acik-surus-gece")
        AracVerisi.guncelle { kapiAcik(false) }
        TestYardim.gecisleriBitir()
        AracVerisi.guncelle { hiz(null) }
        Hiz.yumusatici.unut() // 60 s without GPS: unknown
        sur(null, 0.5)
        cek(a, "09-surus-hiz-yok-gece")

        AracVerisi.guncelle { hiz(0.0); hareketHali(HareketHali.PARK) }
        TestYardim.gecisleriBitir()
        k.altCubuk.klimaAlani.performClick()
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.KLIMA, k.ekranlar.gecerli)
        cek(a, "10-klima-gece")

        k.altCubuk.klimaAlani.performClick()
        k.altCubuk.dugme(AltCubuk.Simge.UYGULAMALAR).performClick()
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.UYGULAMALAR, k.ekranlar.gecerli)
        cek(a, "11-uygulamalar-gece")
        k.altCubuk.dugme(AltCubuk.Simge.UYGULAMALAR).performClick()
        TestYardim.gecisleriBitir()

        k.kareHizi.performLongClick()
        cek(a, "12-teshis-gece")
        a.teshis.kapat()

        // Hint: door service off (first of the two hints).
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        Ipuclari.sifirla()
        c.pause().resume()
        TestYardim.bosalt()
        cek(a, "13-ipucu-gece")
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "dev.kokpit.anaekran/.veri.KapiServisi")
        c.pause().resume()

        Tema.zorla(Palet.GUNDUZ)
        TestYardim.gecisleriBitir()
        cek(a, "14-park-gunduz")
        AracVerisi.guncelle { hiz(62.0); hareketHali(HareketHali.SURUS) }
        TestYardim.gecisleriBitir()
        sur(62f, 2.0)
        cek(a, "15-surus-62-gunduz")

        c.pause().stop().destroy()

        // Intro by day (İ-4): the clip and its last frame are night pictures, so Y-1 keeps the night
        // palette — light text, not the day palette's dark ink.
        AracVerisi.sifirla()
        SahteVeri.uygula(SahteVeri.parkGece())
        val c2 = TestYardim.ac()
        val a2 = c2.get()
        assertEquals(Ekran.ACILIS, a2.katmanlar.ekranlar.gecerli)
        assertEquals(Palet.GUNDUZ, Tema.palet)
        val sonKare2 = android.widget.ImageView(a2).apply { setImageResource(R.drawable.sahne_karsilama) }
        a2.katmanlar.video.addView(sonKare2)
        cek(a2, "16-acilis-gunduz")
        a2.katmanlar.video.removeView(sonKare2)
        c2.pause().stop().destroy()

        surusOnizlemeleri()
    }

    /** 0.2 drive (K-94…K-96): the HUD over the moving scene at night, unknown / stale, day 62. */
    private fun surusOnizlemeleri() {
        Tema.zorla(Palet.GECE)
        AracVerisi.sifirla()
        Hiz.yumusatici.sifirla()
        SahteVeri.uygula(SahteVeri.parkGece())
        val c = TestYardim.acVeGec()
        val a = c.get()
        Hiz.yumusatici.ornek(0f, TestYardim.simdiNs(), null)
        AracVerisi.guncelle { hareketHali(HareketHali.SURUS) }
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.SURUS, a.katmanlar.ekranlar.gecerli)
        assertTrue(a.surusGorunur)
        sur(0f, 1.5)
        cek(a, "17-surus-0-gece")
        sur(50f, 5.0)
        cek(a, "18-surus-50-gece")
        sur(130f, 6.0)
        cek(a, "19-surus-130-gece")
        Hiz.yumusatici.unut()
        sur(null, 0.5)
        cek(a, "20-surus-bilinmiyor-gece")
        sur(62f, 3.0)
        sur(null, 5.5) // no sample for > 5 s: the value stays, "gps" fades
        cek(a, "21-surus-eski-gece")
        Tema.zorla(Palet.GUNDUZ)
        TestYardim.gecisleriBitir()
        sur(62f, 3.0)
        cek(a, "22-surus-62-gunduz")
        c.pause().stop().destroy()
    }

    private fun cek(a: MainActivity, ad: String) {
        val v: View = a.window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(768, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 768, 1024)
        val bmp = Bitmap.createBitmap(768, 1024, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Tema.palet.bg)
        v.draw(cv)
        File(cikti, "$ad.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("onizleme: ${File(cikti, "$ad.png").absolutePath}")
    }
}
