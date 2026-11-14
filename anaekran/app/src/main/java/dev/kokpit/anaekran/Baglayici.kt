package dev.kokpit.anaekran

import android.app.Activity
import android.widget.FrameLayout
import android.widget.ImageView
import dev.kokpit.anaekran.arayuz.AltCubuk
import dev.kokpit.anaekran.ekran.EkranKabi
import dev.kokpit.anaekran.ekran.EkranYoneticisi
import dev.kokpit.anaekran.hareket.HareketBaglayici
import dev.kokpit.anaekran.hareket.KareKaynagi
import dev.kokpit.anaekran.hareket.surus.DurgunSaglayici
import dev.kokpit.anaekran.hareket.surus.HizSaglayici
import dev.kokpit.anaekran.veri.Hiz
import dev.kokpit.anaekran.veri.VeriBaglayici

/**
 * Hook for the subsystems that were written in parallel and wired in by MainActivity:
 *  - data (`veri/`, [VeriBaglayici]): climate broadcast, outside temperature, GPS speed and
 *    park/drive, door/boot accessibility service. Its sources write through `AracVerisi.guncelle { … }`.
 *  - motion (`hareket/`, [HareketBaglayici]): intro/transition clips in [Katmanlar.video],
 *    cross-fade over [Katmanlar.sahne], the drive scene in [Katmanlar.surus], frame-rate counter
 *    in [Katmanlar.kareHizi], and the screen transition for [EkranYoneticisi.gecis].
 *
 * Every method has an empty default; all calls arrive on the main thread.
 */
interface Baglayici {
    /** Once, in onCreate: the layer tree exists, nothing is visible yet. */
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
    /**
     * 1b. Drive scene (K-94/K-95, hareket.surus.SurusSahnesi): transparent, full screen, no
     * touches. Its own layer, not inside the drive screen, so a screen change or an open panel
     * never animates it inside a hardware layer; MainActivity shows it only at night on the drive
     * screen once it is ready and no clip/fade runs (İ-M2 K7).
     */
    val surus: FrameLayout,
    /** 2. Video (the motion layer's TextureView and cross-fade pictures). */
    val video: FrameLayout,
    /**
     * 3. Active screen (EkranYoneticisi's container), full screen; screens keep y < 932 except
     * the intro. Touches go to the current screen only (E1: never to one fading out).
     */
    val ekran: EkranKabi,
    /** 4. Warnings: door/boot open, closed confirmation (ekran.UyariKatmani). */
    val uyari: FrameLayout,
    /** 5. Bottom bar. */
    val altCubuk: AltCubuk,
    /** 6. Frame-rate counter slot, top-right corner; a long press opens the diagnostics panel. */
    val kareHizi: FrameLayout,
    val ekranlar: EkranYoneticisi,
)

/**
 * The wired subsystems, in start order (stopped and released in reverse).
 *
 * Order: data first, then motion.
 *  - kur: the data hook asks for the location permission and prepares its sources; the motion
 *    hook comes last so the intro, started right after the hooks, counts its 1.6 s from a
 *    finished onCreate.
 *  - basla (onResume): the climate receiver is registered (a sticky value arrives at once) and GPS
 *    starts before the frame counter begins measuring, so receiver set-up is not counted as a
 *    slow frame.
 *  - dur/birak (reverse): the frame counter and the player stop first, then GPS and the receiver.
 * The two hooks do not depend on each other; the order only keeps start-up noise out of the
 * frame-rate measurement.
 */
object Baglayicilar {
    fun varsayilan(): List<Baglayici> = listOf(VeriBaglayici(), HareketBaglayici())

    /** Tests replace it (fake players, no GPS). */
    var uret: () -> List<Baglayici> = ::varsayilan

    fun liste(): List<Baglayici> = uret()

    /**
     * The ONE per-frame reader of the smoothed speed (HizYumusatici.deger advances its spring on
     * every call): handed to the drive scene and to the drive screen's own frame loop, never
     * both on one frame (MainActivity, K-96). Tests wrap it to count the reads.
     */
    var hiz: HizSaglayici = HizSaglayici(Hiz.yumusatici::deger)

    /** True when the smoothed speed is at rest (the loops may stop). */
    var durgun: DurgunSaglayici = DurgunSaglayici(Hiz.yumusatici::durgun)

    /** Frame source of the drive screen's own speed loop (tests step it by hand). */
    var kareKaynagi: KareKaynagi = KareKaynagi.CHOREOGRAPHER

    /** Clock in the frameTimeNanos timebase (System.nanoTime), for checks between frames. */
    var saat: () -> Long = System::nanoTime
}
