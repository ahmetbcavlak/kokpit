package dev.kokpit.anaekran.hareket.surus

/*
 * Per-frame callbacks with primitive signatures: a Kotlin `(Long) -> Float` lambda boxes its
 * Long argument and Float result on every call (Function1<Long, Float>), i.e. garbage per frame
 * on a weak Cortex-A7. A `fun interface` with primitive parameters does not. Kotlin lambdas and
 * function references convert to them at the call site:
 *   sahne.hizSaglayici = HizSaglayici(Hiz.yumusatici::deger)
 */

/** Smoothed speed in m/s (≥ 0) for a frame time in the System.nanoTime timebase. */
fun interface HizSaglayici {
    fun hiz(zamanNs: Long): Float
}

/** True when the speed source is at rest and will not change by itself. */
fun interface DurgunSaglayici {
    fun durgun(zamanNs: Long): Boolean
}

/** After every drawn frame: its time and the speed (m/s) the scene read for it. */
fun interface KareDinleyici {
    fun kare(zamanNs: Long, hizMs: Float)
}
