package dev.kokpit.anaekran.hareket

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import dev.kokpit.anaekran.arayuz.Arkaplan
import dev.kokpit.anaekran.arayuz.Gorseller

/**
 * A full-screen picture handed to the motion layer: the "old" picture a cross-fade starts
 * from, or the "new" one it ends on. The caller decides which picture (night/day, scene);
 * the motion layer only shows it in its own ImageViews above the video.
 */
sealed interface Goruntu {
    /** Already decoded. A copy is made from its constant state, so the caller's instance is not re-bound. */
    class Hazir(val cizim: Drawable) : Goruntu

    /**
     * A 768×1024 drawable resource, decoded through [Gorseller] (worker thread, shared LRU).
     * Using the same id as the scene layer means the scene layer gets the very same bitmap
     * from the cache, synchronously, when the caller switches it in `bitti`.
     */
    class Kaynak(val id: Int) : Goruntu

    /** A webp in res/raw (clip posters): decoded once on the worker thread, not cached. */
    class Ham(val id: Int) : Goruntu
}

/** Turns a [Goruntu] into a Drawable; the result arrives on the main thread (null = failed). */
fun interface GorselYukleyici {
    fun yukle(res: Resources, g: Goruntu, sonra: (Drawable?) -> Unit)

    companion object {
        val VARSAYILAN = GorselYukleyici { res, g, sonra ->
            when (g) {
                is Goruntu.Hazir -> sonra(g.cizim.constantState?.newDrawable(res)?.mutate() ?: g.cizim)
                // Gorseller does not call back when decoding fails; KlipOynatici's guards cover that.
                is Goruntu.Kaynak -> Gorseller.al(res, g.id) { sonra(BitmapDrawable(res, it)) }
                is Goruntu.Ham -> Arkaplan.calistir({
                    try {
                        res.openRawResource(g.id).use { BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) {
                        Log.w(KlipOynatici.ETIKET, "poster okunamadı", e)
                        null
                    }
                }) { bmp -> sonra(bmp?.let { BitmapDrawable(res, it) }) }
            }
        }
    }
}
