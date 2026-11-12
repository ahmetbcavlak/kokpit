package dev.kokpit.anaekran.arayuz

import android.widget.ImageView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.ekran.Ekran

/**
 * Bottom layer: one opaque 768×1024 picture (texture + car, composited ahead of time).
 * The bitmap is decoded on the worker thread; until it arrives the previous picture (or the
 * window colour) stays. Which picture is decided by [kaynak]; the cross-fade between two
 * pictures is the motion layer's (MainActivity calls `HareketBaglayici.gecisOynat` before
 * [goster]).
 */
class SahneKatmani(val gorunum: ImageView, private val ilkKare: () -> Unit) {

    /** The picture asked for last (0 = none yet). */
    var istenen = 0
        private set

    private var ilkKareBitti = false

    init {
        gorunum.scaleType = ImageView.ScaleType.CENTER_CROP
        gorunum.importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun goster(id: Int) {
        if (id == istenen) return
        istenen = id
        Gorseller.al(gorunum.resources, id) { bmp ->
            if (id != istenen) return@al
            gorunum.setImageBitmap(bmp)
            if (!ilkKareBitti) {
                ilkKareBitti = true
                ilkKare()
            }
        }
    }

    companion object {
        /**
         * The picture for a screen. Night: the design package's ready scenes (texture + car,
         * pixel-identical to the clips' last frames, K-47). Day: the day car composited onto
         * boya_gunduz with cars.json `map` (k 0.601, left −74.6, top 242.7) at build time
         * (sahne_park_gunduz, sahne_surus_gunduz). There is no day picture with the boot open:
         * the day park scene stays and the HUD points at the boot. The climate and apps panels
         * show the bare texture.
         *
         * [ekran] ACILIS is not asked for: MainActivity uses the intro's target screen.
         */
        fun kaynak(ekran: Ekran, gunduz: Boolean, bagajAcik: Boolean): Int = when (ekran) {
            Ekran.ACILIS, Ekran.PARK -> when {
                gunduz -> R.drawable.sahne_park_gunduz
                bagajAcik -> R.drawable.sahne_bagaj
                else -> R.drawable.sahne_park
            }
            Ekran.SURUS -> if (gunduz) R.drawable.sahne_surus_gunduz else R.drawable.sahne_surus
            Ekran.KLIMA, Ekran.UYGULAMALAR -> if (gunduz) R.drawable.boya_gunduz else R.drawable.boya_gece
        }

        /** Which cars.json frame (anchor points) a scene shows; null = no car in it. */
        fun kare(id: Int): AracHaritasi.Kare? = when (id) {
            R.drawable.sahne_park -> AracHaritasi.Kare.PARK_GECE
            R.drawable.sahne_park_gunduz -> AracHaritasi.Kare.PARK_GUNDUZ
            R.drawable.sahne_bagaj -> AracHaritasi.Kare.BAGAJ
            R.drawable.sahne_surus -> AracHaritasi.Kare.SURUS_GECE
            R.drawable.sahne_surus_gunduz -> AracHaritasi.Kare.SURUS_GUNDUZ
            else -> null
        }
    }
}
