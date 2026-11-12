package dev.kokpit.anaekran.uygulama

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import dev.kokpit.anaekran.arayuz.Arkaplan
import java.text.Collator
import java.util.Locale

data class Uygulama(val ad: String, val bilesen: ComponentName, val simge: Drawable)

/**
 * Installed launcher apps (queryIntentActivities MAIN/LAUNCHER; no QUERY_ALL_PACKAGES).
 * Runs on the worker thread. Each icon is loaded and drawn into a small bitmap once per
 * process, so scrolling only blits bitmaps (adaptive icons are costly to redraw).
 */
object UygulamaListesi {

    /** Touched only on the worker thread. */
    private val simgeler = HashMap<ComponentName, Drawable>()

    fun yukle(ctx: Context, simgePx: Int, sonra: (List<Uygulama>) -> Unit) {
        val app = ctx.applicationContext
        Arkaplan.calistir({ oku(app, simgePx) }, sonra)
    }

    /** Synchronous; call off the main thread. */
    fun oku(ctx: Context, simgePx: Int): List<Uygulama> {
        val pm = ctx.packageManager
        val niyet = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val bulunan = try {
            pm.queryIntentActivities(niyet, 0)
        } catch (e: RuntimeException) {
            emptyList()
        }
        val siralama = Collator.getInstance(Locale("tr", "TR"))
        return bulunan.asSequence()
            .filter { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val bilesen = ComponentName(ai.packageName, ai.name)
                val ad = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName }
                val simge = simgeler.getOrPut(bilesen) { kucult(ctx, ri.loadIcon(pm), simgePx) }
                Uygulama(ad, bilesen, simge)
            }
            .distinctBy { it.bilesen }
            .sortedWith { a, b -> siralama.compare(a.ad, b.ad) }
            .toList()
    }

    private fun kucult(ctx: Context, d: Drawable?, px: Int): Drawable {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        if (d != null) {
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(bmp))
        }
        return BitmapDrawable(ctx.resources, bmp)
    }
}
