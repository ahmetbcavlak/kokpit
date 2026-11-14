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

/**
 * One low-priority worker thread for decoding (scenes, posters); results come back on main.
 * The app list has its own single thread (UygulamaListesi.yurutucu), so a scene decode never
 * waits behind dozens of icon draws.
 */
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
    fun <T> calistir(gorev: () -> T, sonra: (T?) -> Unit) = calistir(yurutucu, gorev, sonra)

    /** [calistir] on another executor ([y]), with the same failure rule. */
    fun <T> calistir(y: Executor, gorev: () -> T, sonra: (T?) -> Unit) {
        y.execute {
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
 * Requests for a picture that is already being decoded join that job (one decode, every
 * caller called back), e.g. the scene layer and the motion layer asking for the same scene.
 * Main thread only.
 * ARGB_8888: the scenes have dark gradients that band in RGB_565. The car pictures are not
 * decoded on the unit at all: they are already composited into the scenes at their on-screen
 * size (cars.json map, 962×541), so a 1600×900 car bitmap (5.8 MB) never exists in memory.
 */
object Gorseller {

    /** A few full-screen bitmaps (~3 MB each); onTrimMemory empties it. */
    private val onbellek = LruCache<Int, Bitmap>(3)

    /** id → callers waiting for the decode in flight (main thread only). */
    private val bekleyen = HashMap<Int, ArrayList<(Bitmap) -> Unit>>()

    /** Decodes started so far (tests). */
    internal var cozumSayisi = 0
        private set

    fun al(res: Resources, id: Int, sonra: (Bitmap) -> Unit) {
        onbellek.get(id)?.let { sonra(it); return }
        bekleyen[id]?.let { it += sonra; return }
        bekleyen[id] = arrayListOf(sonra)
        cozumSayisi++
        Arkaplan.calistir({
            BitmapFactory.decodeResource(
                res, id,
                BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }) { bmp ->
            val cagiranlar = bekleyen.remove(id).orEmpty()
            if (bmp != null) {
                onbellek.put(id, bmp)
                for (c in cagiranlar) c(bmp)
            }
        }
    }

    fun bosalt() = onbellek.evictAll()

    /** True when [id] is decoded and cached (al() would call back synchronously). */
    fun hazirMi(id: Int): Boolean = onbellek.get(id) != null
}
