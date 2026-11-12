package dev.kokpit.anaekran.arayuz

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.widget.ImageView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.tema.Palet

/**
 * Bottom layer: one opaque 768×1024 picture per screen and theme. The bitmap is decoded on
 * the worker thread; until it arrives the previous picture (or the window colour) stays.
 * The motion task cross-fades on top of this view; it must not change [goster]'s choice.
 */
class SahneKatmani(val gorunum: ImageView, private val ilkKare: () -> Unit) {

    private var istenen = 0
    private var ilkKareBitti = false

    init {
        gorunum.scaleType = ImageView.ScaleType.CENTER_CROP
        gorunum.importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun goster(ekran: Ekran, p: Palet) {
        val id = kaynak(ekran, p.gunduz)
        // Y-5: the climate panel dims its texture to 70 %.
        gorunum.colorFilter = if (ekran == Ekran.KLIMA && !p.gunduz) KARART else null
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
        private val KARART = PorterDuffColorFilter(Color.rgb(179, 179, 179), PorterDuff.Mode.MULTIPLY)

        /**
         * 0.1 mapping. Night uses the ready scenes (texture + car). Day has only the texture:
         * the day car (arac/park_gunduz) is placed over it by the screens task (cars.json map).
         */
        fun kaynak(ekran: Ekran, gunduz: Boolean): Int = if (gunduz) R.drawable.boya_gunduz else when (ekran) {
            Ekran.ACILIS -> R.drawable.sahne_karsilama
            Ekran.PARK -> R.drawable.sahne_park
            Ekran.SURUS -> R.drawable.sahne_surus
            Ekran.KLIMA, Ekran.UYGULAMALAR -> R.drawable.boya_gece
        }
    }
}
