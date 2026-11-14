package dev.kokpit.anaekran.hareket.surus

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Arkaplan
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The drive scene's bitmaps, decoded once on the worker thread (never on the main thread: the
 * body alone is ~40 ms of webp decoding on a Cortex-A7).
 *  - ARGB_8888, not HARDWARE: the body is sampled by the lamp reflection's BitmapShader
 *    (research decision 5; a hardware bitmap takes another sampler path on API 27);
 *  - already at their on-screen size (no runtime scaling, no 5.8 MB 1600×900 decode);
 *  - the city-lights tile (600×60) is generated here, blur included (no live blur).
 *
 * Memory (research decision 15): body 798×272 (0.87 MB) + 6 sprites 101×101 (0.24 MB) + lights
 * 600×60 (0.14 MB) = 1.25 MB, the same again as GPU textures. With the full-screen background
 * (3.15 MB) the set drawn in one frame is ≈ 4.4 MB, well under the 12 MB budget; [boyutlariYaz]
 * logs it on every load.
 */
class SurusVarliklari(
    val govde: Bitmap,
    /** [0] sharp, [1] light blur, [2] full blur. */
    val tekerOn: Array<Bitmap>,
    val tekerArka: Array<Bitmap>,
    val isik: Bitmap,
) {
    /** Bytes of every bitmap held here. */
    val bayt: Long
        get() = (listOf(govde, isik) + tekerOn + tekerArka).sumOf { it.allocationByteCount.toLong() }

    fun boyutlariYaz() {
        val mb = { b: Long -> String.format(java.util.Locale.ROOT, "%.2f", b / 1048576.0) }
        val arka = SAHNE_BAYT
        val toplam = bayt + arka
        Log.i(
            ETIKET,
            "sürüş varlıkları: gövde ${govde.width}×${govde.height} (${mb(govde.allocationByteCount.toLong())} MB), " +
                "teker 6×${tekerOn[0].width}×${tekerOn[0].height} (${mb((tekerOn + tekerArka).sumOf { it.allocationByteCount.toLong() })} MB), " +
                "ışık ${isik.width}×${isik.height} (${mb(isik.allocationByteCount.toLong())} MB); " +
                "toplam ${mb(bayt)} MB; arka plan dahil aynı karede ≈ ${mb(toplam)} MB (bütçe ${BUTCE_MB} MB)",
        )
        if (toplam > BUTCE_MB * 1048576L) Log.w(ETIKET, "sürüş sahnesi bitmap bütçesini aşıyor")
    }

    companion object {
        const val ETIKET = "Kokpit/Surus"

        /** Bitmaps drawn in one frame, MB (research decision 15: HWUI texture cache 18.9 MB). */
        const val BUTCE_MB = 12

        /** One full-screen ARGB_8888 background (the scene layer under the drive scene). */
        const val SAHNE_BAYT = 768L * 1024L * 4L

        /** Decodes on [Arkaplan]'s worker; [sonra] on the main thread (null: failed, logged). */
        fun yukle(res: Resources, sonra: (SurusVarliklari?) -> Unit) {
            Arkaplan.calistir({ coz(res) }) { v ->
                v?.boyutlariYaz()
                sonra(v)
            }
        }

        /** Synchronous decode (worker thread, tests). */
        fun coz(res: Resources): SurusVarliklari {
            val o = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            fun c(id: Int): Bitmap = checkNotNull(BitmapFactory.decodeResource(res, id, o)) { "çözülemedi: $id" }
            return SurusVarliklari(
                govde = c(R.drawable.surus_govde),
                tekerOn = arrayOf(c(R.drawable.surus_teker_on_0), c(R.drawable.surus_teker_on_1), c(R.drawable.surus_teker_on_2)),
                tekerArka = arrayOf(c(R.drawable.surus_teker_arka_0), c(R.drawable.surus_teker_arka_1), c(R.drawable.surus_teker_arka_2)),
                isik = isikDeseni(),
            )
        }

        /**
         * The demo's distant city lights, one 600×60 tile: four radial dots (CSS
         * `radial-gradient(circle at x y, c 0 r0, transparent r1)`) blurred like `filter:blur(1.5px)`
         * (Gaussian σ 1.5, separable, on premultiplied values).
         */
        fun isikDeseni(): Bitmap {
            val w = SurusYerlesim.ISIK_DESEN
            val h = SurusYerlesim.ISIK_H
            // premultiplied r, g, b, a in 0..1
            val p = Array(4) { FloatArray(w * h) }
            for (n in ISIKLAR) {
                val x0 = (n.x - n.r1 - 1).toInt().coerceAtLeast(0)
                val x1 = (n.x + n.r1 + 1).toInt().coerceAtMost(w - 1)
                val y0 = (n.y - n.r1 - 1).toInt().coerceAtLeast(0)
                val y1 = (n.y + n.r1 + 1).toInt().coerceAtMost(h - 1)
                for (y in y0..y1) for (x in x0..x1) {
                    val dx = x + 0.5f - n.x
                    val dy = y + 0.5f - n.y
                    val r = sqrt(dx * dx + dy * dy)
                    val k = when {
                        r <= n.r0 -> 1f
                        r >= n.r1 -> 0f
                        else -> 1f - (r - n.r0) / (n.r1 - n.r0)
                    }
                    if (k <= 0f) continue
                    val a = n.a * k
                    val i = y * w + x
                    // source-over onto what is there (the dots do not overlap in practice)
                    val ters = 1f - a
                    p[0][i] = n.r * a + p[0][i] * ters
                    p[1][i] = n.g * a + p[1][i] * ters
                    p[2][i] = n.b * a + p[2][i] * ters
                    p[3][i] = a + p[3][i] * ters
                }
            }
            for (k in p) bulanikla(k, w, h, BULANIKLIK_SIGMA)
            val renk = IntArray(w * h)
            for (i in renk.indices) {
                val a = p[3][i].coerceIn(0f, 1f)
                if (a <= 0f) continue
                // Bitmap.setPixels wants straight (non-premultiplied) colour
                fun ch(v: Float) = (v / a * 255f + 0.5f).toInt().coerceIn(0, 255)
                renk[i] = ((a * 255f + 0.5f).toInt() shl 24) or (ch(p[0][i]) shl 16) or (ch(p[1][i]) shl 8) or ch(p[2][i])
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(renk, 0, w, 0, 0, w, h) }
        }

        /** Separable Gaussian blur in place; the tile repeats horizontally, so x wraps. */
        private fun bulanikla(v: FloatArray, w: Int, h: Int, sigma: Float) {
            val r = (sigma * 3f).toInt().coerceAtLeast(1)
            val c = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2f * sigma * sigma)) }
            val s = c.sum()
            for (i in c.indices) c[i] /= s
            val t = FloatArray(v.size)
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0f
                for (k in -r..r) a += v[y * w + (x + k).mod(w)] * c[k + r]
                t[y * w + x] = a
            }
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0f
                for (k in -r..r) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    a += t[yy * w + x] * c[k + r]
                }
                v[y * w + x] = a
            }
        }

        private class Isik(val x: Float, val y: Float, val r0: Float, val r1: Float, rgb: Int, val a: Float) {
            val r = ((rgb shr 16) and 255) / 255f
            val g = ((rgb shr 8) and 255) / 255f
            val b = (rgb and 255) / 255f
        }

        /** Demo: `radial-gradient(circle at 20px 30px, rgba(255,196,130,.55) 0 2px, rgba(255,196,130,0) 7px)` etc. */
        private val ISIKLAR = listOf(
            Isik(20f, 30f, 2f, 7f, 0xFFC482, 0.55f),
            Isik(170f, 22f, 1.5f, 6f, 0xA0BEFF, 0.45f),
            Isik(330f, 34f, 2f, 8f, 0xFFD296, 0.40f),
            Isik(470f, 26f, 1.5f, 6f, 0xFFB478, 0.35f),
        )

        private const val BULANIKLIK_SIGMA = 1.5f
    }
}
