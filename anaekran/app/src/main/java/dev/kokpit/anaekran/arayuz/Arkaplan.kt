package dev.kokpit.anaekran.arayuz

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One low-priority worker thread for decoding and package queries; results come back on main. */
object Arkaplan {

    /** Tests swap in a direct executor (`Executor { it.run() }`). */
    var yurutucu: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kokpit-arka").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }

    private val ana by lazy { Handler(Looper.getMainLooper()) }

    fun <T> calistir(gorev: () -> T, sonra: (T) -> Unit) {
        yurutucu.execute {
            val s = gorev()
            ana.post { sonra(s) }
        }
    }
}

/**
 * Full-screen webp scenes, decoded once off the main thread and kept (research 8).
 * ARGB_8888: the scenes have dark gradients that band in RGB_565.
 */
object Gorseller {

    /** A few full-screen bitmaps (~3 MB each); onTrimMemory empties it. */
    private val onbellek = LruCache<Int, Bitmap>(3)

    fun al(res: Resources, id: Int, sonra: (Bitmap) -> Unit) {
        onbellek.get(id)?.let { sonra(it); return }
        Arkaplan.calistir({
            BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
        }) { bmp ->
            if (bmp != null) {
                onbellek.put(id, bmp)
                sonra(bmp)
            }
        }
    }

    fun bosalt() = onbellek.evictAll()
}
