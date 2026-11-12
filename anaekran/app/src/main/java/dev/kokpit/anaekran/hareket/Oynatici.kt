package dev.kokpit.anaekran.hareket

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View

/**
 * One clip playback: a view to put in the video layer and a player behind it.
 * Tests swap in a fake; the app uses [DokuOynatici]. All callbacks on the main thread, never
 * from inside a draw or a call into the player (they are posted).
 */
interface Oynatici {
    /** The view that shows the video. KlipOynatici adds it under its own image views. */
    val gorunum: View

    /** Starts preparing at once; playback starts as soon as the view's surface exists. */
    fun hazirla(klipHam: Int, d: Dinleyici)

    /** Stops and frees the decoder. Idempotent; no callbacks afterwards. Returns the release time in ms (0 if nothing was held). */
    fun birak(): Long

    interface Dinleyici {
        /** MEDIA_INFO_VIDEO_RENDERING_START: the decoder pushed its first frame. */
        fun basladi()

        /** The view has latched a frame after start() (it is drawn in this frame). */
        fun kareGeldi()

        /** OnCompletion. The last frame stays in the view until [birak]. */
        fun bitti()

        fun hata(neden: String)

        /**
         * The window went out of sight (another app — CarPlay/ZLink — came to the front). The
         * decoder has ALREADY been released: the head unit's hardware H.264 decoder is needed
         * by the app in front. Not sent for a dialog over us: the window stays visible then.
         */
        fun pencereGizlendi()
    }
}

/**
 * Platform MediaPlayer on a TextureView (no Media3; research decision 2).
 *
 * Why TextureView and not SurfaceView on API 27: attaching a SurfaceView makes ViewRootImpl
 * switch the whole window to PixelFormat.TRANSLUCENT (requestTransparentRegion →
 * PFLAG_REQUEST_TRANSPARENT_REGIONS, AOSP oreo-mr1 ViewRootImpl ~l.1800) and the flag is
 * never cleared, so after the first clip the full-screen app window is blended by
 * SurfaceFlinger for the rest of the session, and the format change re-creates the window
 * surface (risk of a flash at the moment the clip is added). A SurfaceView also punches a
 * hole: the scene layer below it is cleared in that rectangle. TextureView is an ordinary
 * view: no window change, the image views above it blend normally, it keeps showing its
 * last latched frame after completion, and it is removed like any view. Its cost — one
 * GPU texture copy per video frame — lasts only for the 0.8–1.33 s clip.
 *
 * [oynaticiUret] / [kaynakVer] exist for tests (Robolectric's ShadowMediaPlayer).
 */
class DokuOynatici(
    context: Context,
    private val oynaticiUret: () -> MediaPlayer = { MediaPlayer() },
    private val kaynakVer: (MediaPlayer, Context, Int) -> Unit = { mp, c, id ->
        c.resources.openRawResourceFd(id).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
    },
) : Oynatici, TextureView.SurfaceTextureListener {

    /** Releases the decoder the moment the window is no longer visible. */
    private inner class Doku(c: Context) : TextureView(c) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != View.VISIBLE) pencereGizlendi()
        }
    }

    private val doku: TextureView = Doku(context).apply {
        isOpaque = true // the video is opaque: HWUI can skip blending
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override val gorunum: View get() = doku

    private val ana = Handler(Looper.getMainLooper())
    private var oynatici: MediaPlayer? = null
    private var yuzey: Surface? = null
    private var dinleyici: Oynatici.Dinleyici? = null
    private var hazir = false
    private var basladi = false
    private var kareBildirildi = false
    private var t0 = 0L
    private var tBasla = 0L

    init {
        doku.surfaceTextureListener = this
    }

    override fun hazirla(klipHam: Int, d: Oynatici.Dinleyici) {
        dinleyici = d
        t0 = SystemClock.uptimeMillis()
        val mp = oynaticiUret()
        oynatici = mp
        mp.setOnPreparedListener {
            hazir = true
            Log.i(KlipOynatici.ETIKET, "hazır: ${SystemClock.uptimeMillis() - t0} ms")
            dene()
        }
        mp.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                Log.i(KlipOynatici.ETIKET, "RENDERING_START: start'tan ${SystemClock.uptimeMillis() - tBasla} ms")
                bildir { it.basladi() }
            }
            false
        }
        mp.setOnCompletionListener { bildir { it.bitti() } }
        mp.setOnErrorListener { _, what, extra ->
            hataBildir("MediaPlayer hata $what/$extra")
            true // handled: no OnCompletion after an error
        }
        try {
            kaynakVer(mp, doku.context, klipHam)
            mp.prepareAsync()
        } catch (e: Exception) {
            hataBildir("hazırlanamadı: $e")
        }
    }

    private fun dene() {
        val mp = oynatici ?: return
        if (!hazir || yuzey == null || basladi) return
        basladi = true
        tBasla = SystemClock.uptimeMillis()
        try {
            mp.start()
        } catch (e: RuntimeException) {
            hataBildir("başlatılamadı: $e")
        }
    }

    /** Posted: listeners may remove this view, which must not happen inside draw or a player call. */
    private fun bildir(olay: (Oynatici.Dinleyici) -> Unit) {
        val d = dinleyici ?: return
        ana.post { if (dinleyici === d) olay(d) }
    }

    private fun hataBildir(neden: String) = bildir { it.hata(neden) }

    private fun pencereGizlendi() {
        val d = dinleyici ?: return
        if (oynatici == null) return
        Log.i(KlipOynatici.ETIKET, "pencere gizlendi: çözücü bırakıldı")
        birak()
        // birak() cleared the listener; tell the owner once, on the next turn.
        ana.post { d.pencereGizlendi() }
    }

    override fun birak(): Long {
        dinleyici = null
        ana.removeCallbacksAndMessages(null)
        var ms = 0L
        oynatici?.let {
            it.setOnPreparedListener(null)
            it.setOnInfoListener(null)
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            val t = SystemClock.uptimeMillis()
            it.release()
            ms = SystemClock.uptimeMillis() - t
            Log.i(KlipOynatici.ETIKET, "release: $ms ms")
        }
        oynatici = null
        yuzey?.release()
        yuzey = null
        return ms
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val mp = oynatici ?: return // released before the surface came
        val s = Surface(st)
        yuzey = s
        try {
            mp.setSurface(s)
        } catch (e: RuntimeException) {
            // IllegalStateException / IllegalArgumentException (surface already released)
            hataBildir("yüzey verilemedi: $e")
            return
        }
        dene()
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // Called from TextureView.draw when a new buffer was latched. Updates before start()
        // (size/visibility changes) carry no video frame and are ignored.
        if (!basladi || kareBildirildi) return
        kareBildirildi = true
        // Posted: the frame is drawn in this pass, so the poster can go in the next one.
        bildir { it.kareGeldi() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        oynatici?.let { runCatching { it.setSurface(null) } }
        yuzey?.release()
        yuzey = null
        return true
    }
}
