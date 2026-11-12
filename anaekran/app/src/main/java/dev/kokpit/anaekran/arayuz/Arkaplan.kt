package dev.kokpit.anaekran.arayuz

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    /**
     * Runs [gorev] on the worker; [sonra] gets its result on the main thread. Anything the job
     * throws (a vendor PackageManager surprise, OutOfMemoryError while decoding) is logged and
     * arrives as null: the single worker thread must never die, or every later job is lost.
     */
    fun <T> calistir(gorev: () -> T, sonra: (T?) -> Unit) {
        yurutucu.execute {
            val s = try {
                gorev()
            } catch (t: Throwable) {
                Log.w("Kokpit/Arkaplan", "arka plan işi başarısız", t)
                null
            }
            ana.post { sonra(s) }
        }
    }
}

/**
 * Full-screen 768×1024 webp scenes, decoded once off the main thread and kept (research 8).
 * ARGB_8888: the scenes have dark gradients that band in RGB_565. The car pictures are not
 * decoded on the unit at all: they are already composited into the scenes at their on-screen
 * size (cars.json map, 962×541), so a 1600×900 car bitmap (5.8 MB) never exists in memory.
 */
object Gorseller {

    /** A few full-screen bitmaps (~3 MB each); onTrimMemory empties it. */
    private val onbellek = LruCache<Int, Bitmap>(3)

    fun al(res: Resources, id: Int, sonra: (Bitmap) -> Unit) {
        onbellek.get(id)?.let { sonra(it); return }
        Arkaplan.calistir({
            BitmapFactory.decodeResource(
                res, id,
                BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }) { bmp ->
            if (bmp != null) {
                onbellek.put(id, bmp)
                sonra(bmp)
            }
        }
    }

    fun bosalt() = onbellek.evictAll()
}
