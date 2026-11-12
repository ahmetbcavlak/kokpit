package dev.kokpit.anaekran.arayuz

/**
 * The design is drawn in 768×1024 pixels; the unit's density is unknown, so dp is not used.
 * Every size goes through [u]/[uf]: design px × screen width / 768.
 */
object Olcek {
    const val TASARIM_GENISLIK = 768f
    const val TASARIM_YUKSEKLIK = 1024f

    var oran: Float = 1f
        private set

    fun kur(ekranGenisligi: Int) {
        if (ekranGenisligi > 0) oran = ekranGenisligi / TASARIM_GENISLIK
    }
}

/** Design pixels → device pixels (rounded). */
fun u(px: Number): Int = Math.round(px.toFloat() * Olcek.oran)

/** Design pixels → device pixels (exact, for text sizes and strokes). */
fun uf(px: Number): Float = px.toFloat() * Olcek.oran
