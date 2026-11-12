package dev.kokpit.anaekran.uygulama

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Opens other apps (research 7). Never throws: a missing package or a refused start
 * comes back as a [Sonuc] and the caller shows a calm note.
 */
object PaketAcici {

    /** CarPlay, map and phone all live in the ZLink app. */
    const val ZLINK = "com.zjinnova.zlink"
    /** The head unit's own media player. */
    const val MEDYA = "com.hcn.AutoMediaPlayer"

    enum class Sonuc { ACILDI, YOK, HATA }

    fun ac(ctx: Context, paket: String): Sonuc {
        val intent = try {
            ctx.packageManager.getLaunchIntentForPackage(paket)
        } catch (e: RuntimeException) {
            null
        } ?: return Sonuc.YOK
        return baslat(ctx, intent)
    }

    /** A launcher activity from the app list. */
    fun ac(ctx: Context, bilesen: ComponentName): Sonuc =
        baslat(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(bilesen))

    private fun baslat(ctx: Context, intent: Intent): Sonuc {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            Sonuc.ACILDI
        } catch (e: ActivityNotFoundException) {
            Sonuc.YOK
        } catch (e: RuntimeException) { // SecurityException (not exported) and vendor surprises
            Sonuc.HATA
        }
    }
}
