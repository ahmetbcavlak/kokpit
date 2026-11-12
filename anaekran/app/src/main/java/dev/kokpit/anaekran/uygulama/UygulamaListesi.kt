package dev.kokpit.anaekran.uygulama

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import dev.kokpit.anaekran.arayuz.Arkaplan
import java.text.Collator
import java.util.Locale

/** One launcher app. [simge] is null until its icon has been drawn (names come first). */
data class Uygulama(val ad: String, val bilesen: ComponentName, val simge: Drawable?)

/**
 * Installed launcher apps (queryIntentActivities MAIN/LAUNCHER; no QUERY_ALL_PACKAGES).
 *
 * Two steps on the worker thread, each delivered to the main thread on its own:
 *  1. names (and icons already in the cache) — the grid appears at once;
 *  2. the missing icons, drawn once into small bitmaps (adaptive icons are costly to redraw),
 *     then the same list with icons.
 * The icon cache is keyed by component and the package's lastUpdateTime, so an app that was
 * updated (new icon) is drawn again; uninstalled apps fall out of the cache on the next load.
 * Every app is read on its own (runCatching): one broken package cannot empty the list; a
 * missing label becomes the package name, a missing icon the system's default icon.
 */
object UygulamaListesi {

    private class Kayit(val guncelleme: Long, val simge: Drawable)

    /** Touched only on the worker thread. */
    private val simgeler = HashMap<ComponentName, Kayit>()

    fun yukle(ctx: Context, simgePx: Int, adlar: (List<Uygulama>) -> Unit, tamam: (List<Uygulama>) -> Unit) {
        val app = ctx.applicationContext
        var bulunan: List<Pair<ResolveInfo, Uygulama>> = emptyList()
        Arkaplan.calistir({ adlariOku(app).also { bulunan = it } }) { liste -> adlar(liste?.map { it.second }.orEmpty()) }
        Arkaplan.calistir({ simgeleriOku(app, bulunan, simgePx) }) { liste -> liste?.let(tamam) }
    }

    /** Synchronous, names and cached icons only; call off the main thread. */
    fun adlariOku(ctx: Context): List<Pair<ResolveInfo, Uygulama>> {
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
            .mapNotNull { ri ->
                runCatching {
                    val ai = ri.activityInfo
                    val bilesen = ComponentName(ai.packageName, ai.name)
                    val ad = runCatching { ri.loadLabel(pm)?.toString()?.trim() }.getOrNull().orEmpty().ifEmpty { ai.packageName }
                    val onbellek = simgeler[bilesen]?.takeIf { it.guncelleme == guncelleme(ctx, ai.packageName) }
                    ri to Uygulama(ad, bilesen, onbellek?.simge)
                }.getOrNull()
            }
            .distinctBy { it.second.bilesen }
            .sortedWith { a, b -> siralama.compare(a.second.ad, b.second.ad) }
            .toList()
    }

    /** Fills in the icons of [liste] (drawing only the missing ones); call off the main thread. */
    fun simgeleriOku(ctx: Context, liste: List<Pair<ResolveInfo, Uygulama>>, simgePx: Int): List<Uygulama> {
        val pm = ctx.packageManager
        val gorulen = HashSet<ComponentName>()
        val sonuc = liste.map { (ri, u) ->
            gorulen += u.bilesen
            if (u.simge != null) return@map u
            val simge = runCatching {
                val d = runCatching { ri.loadIcon(pm) }.getOrNull() ?: pm.defaultActivityIcon
                kucult(ctx, d, simgePx)
            }.getOrNull() ?: return@map u
            simgeler[u.bilesen] = Kayit(guncelleme(ctx, u.bilesen.packageName), simge)
            u.copy(simge = simge)
        }
        simgeler.keys.retainAll(gorulen)
        return sonuc
    }

    /** Synchronous: names and icons (tests). */
    fun oku(ctx: Context, simgePx: Int): List<Uygulama> = simgeleriOku(ctx, adlariOku(ctx), simgePx)

    private fun guncelleme(ctx: Context, paket: String): Long =
        runCatching { ctx.packageManager.getPackageInfo(paket, 0).lastUpdateTime }.getOrDefault(0L)

    private fun kucult(ctx: Context, d: Drawable, px: Int): Drawable {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        return BitmapDrawable(ctx.resources, bmp)
    }
}
