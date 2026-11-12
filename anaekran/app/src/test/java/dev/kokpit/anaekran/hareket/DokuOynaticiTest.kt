package dev.kokpit.anaekran.hareket

import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowMediaPlayer.State
import org.robolectric.shadows.util.DataSource

/** The MediaPlayer + TextureView player against Robolectric's ShadowMediaPlayer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class DokuOynaticiTest {

    private class Kayit : Oynatici.Dinleyici {
        val olaylar = ArrayList<String>()
        override fun basladi() { olaylar += "basladi" }
        override fun kareGeldi() { olaylar += "kare" }
        override fun bitti() { olaylar += "bitti" }
        override fun hata(neden: String) { olaylar += "hata" }
        override fun pencereGizlendi() { olaylar += "gizlendi" }
    }

    private lateinit var act: Activity
    private lateinit var mp: MediaPlayer
    private lateinit var o: DokuOynatici
    private val d = Kayit()

    @Before
    fun hazirla() {
        act = Robolectric.buildActivity(Activity::class.java).setup().get()
        HareketTestYardim.gercekSure()
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("klip"), ShadowMediaPlayer.MediaInfo(800, 50))
        o = DokuOynatici(
            act,
            oynaticiUret = { MediaPlayer().also { mp = it } },
            kaynakVer = { m, _, _ -> m.setDataSource("klip") },
        )
        act.setContentView(FrameLayout(act).apply { addView(o.gorunum) })
    }

    private val doku get() = o.gorunum as TextureView
    private fun yuzeyGeldi() = doku.surfaceTextureListener!!.onSurfaceTextureAvailable(SurfaceTexture(false), 768, 1024)
    private fun bekle(ms: Long) = HareketTestYardim.bekle(ms)

    @Test
    fun hazirVeYuzeyGelinceBaslar_olaylarErtelenir() {
        o.hazirla(1, d)
        bekle(100)
        assertEquals("yüzey yok: başlamaz", State.PREPARED, shadowOf(mp).state)
        yuzeyGeldi()
        assertEquals(State.STARTED, shadowOf(mp).state)
        shadowOf(mp).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0)
        assertTrue("olay oynatıcı çağrısının içinde gelmez", d.olaylar.isEmpty())
        doku.surfaceTextureListener!!.onSurfaceTextureUpdated(doku.surfaceTexture ?: SurfaceTexture(false))
        bekle(16)
        assertEquals(listOf("basladi", "kare"), d.olaylar)
        shadowOf(mp).invokeCompletionListener()
        bekle(16)
        assertEquals("bitti", d.olaylar.last())
        o.birak()
        assertEquals(State.END, shadowOf(mp).state)
    }

    @Test
    fun hazirlanirkenBirak_geriCagriYok() {
        o.hazirla(1, d)
        o.birak()
        bekle(300)
        yuzeyGeldi()
        bekle(100)
        assertTrue(d.olaylar.isEmpty())
        assertEquals(State.END, shadowOf(mp).state)
    }

    @Test
    fun yuzeyGelmedenHata_birKez_vePesindenBittiGelmez() {
        o.hazirla(1, d)
        shadowOf(mp).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004)
        assertTrue("ertelenir", d.olaylar.isEmpty())
        bekle(16)
        assertEquals(listOf("hata"), d.olaylar)
        bekle(1000)
        assertEquals(listOf("hata"), d.olaylar)
    }

    @Test
    fun birakSonrasiYuzeyGelirse_birseyOlmaz() {
        o.hazirla(1, d)
        bekle(100)
        o.birak()
        yuzeyGeldi()
        bekle(100)
        assertTrue(d.olaylar.isEmpty())
        assertEquals(State.END, shadowOf(mp).state)
        assertEquals("ikinci birak zararsız", 0L, o.birak())
    }

    @Test
    fun pencereGizlenince_cozucuHemenBirakilir_birKezBildirilir() {
        o.hazirla(1, d)
        bekle(100)
        yuzeyGeldi()
        doku.dispatchWindowVisibilityChanged(View.GONE)
        assertEquals("hemen", State.END, shadowOf(mp).state)
        bekle(16)
        assertEquals(listOf("gizlendi"), d.olaylar)
        doku.dispatchWindowVisibilityChanged(View.GONE)
        bekle(16)
        assertEquals(listOf("gizlendi"), d.olaylar)
    }

    @Test
    fun pencereGorunurKalirsa_dokunulmaz() {
        o.hazirla(1, d)
        bekle(100)
        yuzeyGeldi()
        doku.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(State.STARTED, shadowOf(mp).state)
    }
}
