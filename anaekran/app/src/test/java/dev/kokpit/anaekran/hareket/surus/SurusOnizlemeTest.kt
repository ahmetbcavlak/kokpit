package dev.kokpit.anaekran.hareket.surus

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.hareket.KareKaynagi
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the drive scene over the night background (boya_gece) at a steady 0, 50 and 130 km/h
 * (a few seconds of frames, so the camera has settled) into app/build/onizleme-surus/. For eyes:
 * compare with the design pack's referans/hiz-*.png (they also show the HUD, which is not part of
 * the scene). Also writes the 0 km/h frame without lines (for the clip's last frame check).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class SurusOnizlemeTest {

    private val cikti = File("build/onizleme-surus").apply { mkdirs() }

    private class Kaynak : KareKaynagi {
        val bekleyen = ArrayList<Choreographer.FrameCallback>()
        override fun iste(cb: Choreographer.FrameCallback) { bekleyen += cb }
        override fun birak(cb: Choreographer.FrameCallback) { bekleyen.remove(cb) }
    }

    @Test
    fun onizlemeler() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val kaynak = Kaynak()
        var hiz = 0f
        val kok = FrameLayout(a)
        val arka = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.boya_gece)
        }
        val s = SurusSahnesi(a, kaynak) { 0L }
        s.hizSaglayici = HizSaglayici { hiz }
        s.durgunSaglayici = DurgunSaglayici { hiz == 0f }
        s.varliklariKur(SurusVarliklari.coz(a.resources))
        kok.addView(arka, FrameLayout.LayoutParams(768, 1024))
        kok.addView(s, FrameLayout.LayoutParams(768, 1024))
        a.setContentView(kok)
        shadowOf(Looper.getMainLooper()).idle()
        s.basla()

        var t = 1_000_000_000L
        fun ilerle(sn: Double) {
            val n = (sn * 60).toInt()
            repeat(n) {
                t += 16_666_667L
                val l = kaynak.bekleyen.toList()
                kaynak.bekleyen.clear()
                l.forEach { it.doFrame(t) }
                if (kaynak.bekleyen.isEmpty()) s.uyandir()
            }
        }

        fun cek(ad: String) {
            kok.measure(View.MeasureSpec.makeMeasureSpec(768, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY))
            kok.layout(0, 0, 768, 1024)
            val b = Bitmap.createBitmap(768, 1024, Bitmap.Config.ARGB_8888)
            kok.draw(Canvas(b))
            File(cikti, "$ad.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        ilerle(0.1)
        cek("hiz-0")
        hiz = 50f / 3.6f
        ilerle(4.0)
        // a moment without the lamp band, then one with the band mid-body
        while (!s.model.lambaKonumu.isNaN()) ilerle(1.0 / 60)
        cek("hiz-50")
        while (s.model.lambaKonumu.isNaN() || s.model.lambaKonumu < 0.3f) ilerle(1.0 / 60)
        cek("hiz-50-lamba")
        hiz = 130f / 3.6f
        ilerle(5.0)
        while (!s.model.lambaKonumu.isNaN()) ilerle(1.0 / 60)
        cek("hiz-130")
        assertTrue(File(cikti, "hiz-130.png").length() > 10_000)
    }
}
