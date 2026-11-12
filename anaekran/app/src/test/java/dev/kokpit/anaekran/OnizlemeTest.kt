package dev.kokpit.anaekran

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.SahteVeri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the head unit's 768×1024 screen (mdpi = 1 design px per pixel, Android 8.1) with
 * fake data and writes PNG previews to app/build/onizleme/. The PNGs are for eyes, not asserts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class OnizlemeTest {

    private val cikti = File("build/onizleme").apply { mkdirs() }

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

        k.ekranlar.goster(Ekran.PARK)
        TestYardim.bosalt()
        cek(a, "1-park-gece")

        k.altCubuk.klimaAlani.performClick()
        TestYardim.bosalt()
        assertEquals(Ekran.KLIMA, k.ekranlar.gecerli)
        cek(a, "2-klima-gece")

        k.altCubuk.klimaAlani.performClick() // toggle closes
        k.altCubuk.dugme(dev.kokpit.anaekran.arayuz.AltCubuk.Simge.UYGULAMALAR).performClick()
        TestYardim.bosalt()
        assertEquals(Ekran.UYGULAMALAR, k.ekranlar.gecerli)
        cek(a, "3-uygulamalar-gece")

        // Day palette and the driving placeholder, for a side-by-side look.
        k.ekranlar.goster(Ekran.PARK)
        Tema.zorla(Palet.GUNDUZ)
        TestYardim.bosalt()
        cek(a, "4-park-gunduz")
        Tema.zorla(Palet.GECE)
        SahteVeri.uygula(SahteVeri.surus62())
        k.ekranlar.goster(Ekran.SURUS)
        TestYardim.bosalt()
        cek(a, "5-surus-gece")

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
