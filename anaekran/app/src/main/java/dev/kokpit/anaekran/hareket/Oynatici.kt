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
 * Tests swap in a fake; the app uses [DokuOynatici]. All callbacks on the main thread.
 */
interface Oynatici {
    /** The view that shows the video. KlipOynatici adds it under its own image views. */
    val gorunum: View

    /** Starts preparing at once; playback starts as soon as the view's surface exists. */
    fun hazirla(klipHam: Int, d: Dinleyici)

    /** Stops and frees the decoder. Idempotent; no callbacks afterwards. */
    fun birak()

    interface Dinleyici {
        /** MEDIA_INFO_VIDEO_RENDERING_START: the decoder pushed its first frame. */
        fun basladi()

        /** The view has latched a frame after start() (it is drawn in this frame). */
        fun kareGeldi()

        /** OnCompletion. The last frame stays in the view until [birak]. */
        fun bitti()

        fun hata(neden: String)
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
 */
class DokuOynatici(context: Context) : Oynatici, TextureView.SurfaceTextureListener {

    private val doku = TextureView(context).apply {
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
        val mp = MediaPlayer()
        oynatici = mp
        mp.setOnPreparedListener {
            hazir = true
            Log.i(KlipOynatici.ETIKET, "hazır: ${SystemClock.uptimeMillis() - t0} ms")
            dene()
        }
        mp.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                Log.i(KlipOynatici.ETIKET, "RENDERING_START: start'tan ${SystemClock.uptimeMillis() - tBasla} ms")
                dinleyici?.basladi()
            }
            false
        }
        mp.setOnCompletionListener { dinleyici?.bitti() }
        mp.setOnErrorListener { _, what, extra ->
            dinleyici?.hata("MediaPlayer hata $what/$extra")
            true // handled: no OnCompletion after an error
        }
        try {
            doku.resources.openRawResourceFd(klipHam).use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            // Report on the next turn: the caller is still inside its own set-up.
            ana.post { dinleyici?.hata("hazırlanamadı: $e") }
        }
    }

    private fun dene() {
        val mp = oynatici ?: return
        if (!hazir || yuzey == null || basladi) return
        basladi = true
        tBasla = SystemClock.uptimeMillis()
        try {
            mp.start()
        } catch (e: IllegalStateException) {
            dinleyici?.hata("başlatılamadı: $e")
        }
    }

    override fun birak() {
        dinleyici = null
        ana.removeCallbacksAndMessages(null)
        oynatici?.let {
            it.setOnPreparedListener(null)
            it.setOnInfoListener(null)
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            it.release()
        }
        oynatici = null
        yuzey?.release()
        yuzey = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val mp = oynatici ?: return
        val s = Surface(st)
        yuzey = s
        try {
            mp.setSurface(s)
        } catch (e: IllegalStateException) {
            dinleyici?.hata("yüzey verilemedi: $e")
            return
        }
        dene()
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // Called from TextureView.draw when a new buffer was latched. Updates before start()
        // (size/visibility changes) carry no video frame and are ignored.
        if (!basladi || kareBildirildi) return
        kareBildirildi = true
        val d = dinleyici ?: return
        // Posted: the frame is drawn in this pass, so the poster can go in the next one.
        ana.post { if (dinleyici === d) d.kareGeldi() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        oynatici?.let { runCatching { it.setSurface(null) } }
        yuzey?.release()
        yuzey = null
        return true
    }
}
