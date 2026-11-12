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
 * compare them with the design package's referans/Y-*.png.
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
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.SURUS, k.ekranlar.gecerli)
        cek(a, "07-surus-62-gece")
        AracVerisi.guncelle { kapiAcik(true) }
        cek(a, "08-kapi-acik-surus-gece")
        AracVerisi.guncelle { kapiAcik(false) }
        TestYardim.gecisleriBitir()
        AracVerisi.guncelle { hiz(null) }
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
