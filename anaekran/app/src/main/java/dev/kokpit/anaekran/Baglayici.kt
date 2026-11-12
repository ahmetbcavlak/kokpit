package dev.kokpit.anaekran

import android.app.Activity
import android.widget.FrameLayout
import android.widget.ImageView
import dev.kokpit.anaekran.arayuz.AltCubuk
import dev.kokpit.anaekran.ekran.EkranYoneticisi

/**
 * Hook for the subsystems that are written in parallel and wired in later:
 *  - task 2 (data): `veri/` — climate broadcast, outside temperature, GPS speed, door/boot
 *    accessibility service. Its sources write through `AracVerisi.guncelle { … }`.
 *  - task 3 (motion): `hareket/` — intro/transition clips in [Katmanlar.video], cross-fade
 *    over [Katmanlar.sahne], frame-rate counter in [Katmanlar.kareHizi], and an
 *    `ekran.Gecis` for [EkranYoneticisi.gecis].
 *
 * Each task implements one Baglayici in its own package and tests it on its own;
 * MainActivity is not edited by them. Task 4 adds the instances to [Baglayicilar.liste].
 * Every method has an empty default; all calls arrive on the main thread.
 */
interface Baglayici {
    /** Once, at the end of onCreate: the layer tree exists, nothing is visible yet. */
    fun kur(activity: Activity, katmanlar: Katmanlar) {}

    /** onResume: start listening / drawing (GPS, receivers, frame counter). */
    fun basla() {}

    /** onPause: stop everything that costs battery or CPU while another app is in front. */
    fun dur() {}

    /** onDestroy: release players, unregister for good. */
    fun birak() {}
}

/** The root layers, bottom to top, and the screen switcher. */
class Katmanlar(
    val kok: FrameLayout,
    /** 1. Scene picture (opaque, full screen). */
    val sahne: ImageView,
    /** 2. Video (empty; the motion task adds its TextureView/SurfaceView here). */
    val video: FrameLayout,
    /** 3. Active screen (EkranYoneticisi's container), above the bar's top edge. */
    val ekran: FrameLayout,
    /** 4. Warnings: door/boot open (empty). */
    val uyari: FrameLayout,
    /** 5. Bottom bar. */
    val altCubuk: AltCubuk,
    /** 6. Frame-rate counter slot, top-right corner (empty). */
    val kareHizi: FrameLayout,
    val ekranlar: EkranYoneticisi,
)

/** The wired subsystems, in start order (stopped in reverse). Edited only in task 4. */
object Baglayicilar {
    fun liste(): List<Baglayici> = emptyList()
}
