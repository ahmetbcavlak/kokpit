package dev.kokpit.anaekran.tema

import android.graphics.Color

/** One colour set from tokens.json (ARGB ints). Names follow the token keys. */
data class Palet(
    val ad: String,
    val gunduz: Boolean,
    val bg: Int,
    val tx: Int,
    val mu: Int,
    val hud: Int,
    val hair: Int,
    val glass: Int,
    val gline: Int,
    val ghi: Int,
    val dock: Int,
    val danger: Int,
    val ok: Int,
    val acc: Int,
    val onacc: Int,
) {
    companion object {
        /** tokens.json "gece". */
        val GECE = Palet(
            ad = "gece", gunduz = false,
            bg = Color.parseColor("#05070B"),
            tx = Color.parseColor("#F2F1EC"),
            mu = Color.parseColor("#A3A9B4"),
            hud = Color.parseColor("#6B7384"),
            hair = rgba(255, 255, 255, 0.09),
            glass = rgba(12, 17, 28, 0.62),
            gline = rgba(255, 255, 255, 0.08),
            ghi = rgba(255, 255, 255, 0.05),
            dock = Color.parseColor("#030407"),
            danger = Color.parseColor("#FF6B5E"),
            ok = Color.parseColor("#5FD08A"),
            acc = Color.parseColor("#F2B35E"),
            onacc = Color.parseColor("#1A1206"),
        )

        /** tokens.json "gunduz". */
        val GUNDUZ = Palet(
            ad = "gunduz", gunduz = true,
            bg = Color.parseColor("#E9ECF0"),
            tx = Color.parseColor("#0B0F16"),
            mu = Color.parseColor("#4F5968"),
            hud = Color.parseColor("#7C8696"),
            hair = rgba(11, 15, 22, 0.10),
            glass = rgba(255, 255, 255, 0.62),
            gline = rgba(11, 15, 22, 0.08),
            ghi = rgba(255, 255, 255, 0.7),
            dock = Color.parseColor("#F4F5F7"),
            danger = Color.parseColor("#C9372C"),
            ok = Color.parseColor("#1F8A4C"),
            acc = Color.parseColor("#8A4F00"),
            onacc = Color.parseColor("#FFFFFF"),
        )

        /** tokens.json "vurgu" (theme-independent accent). */
        val VURGU = Color.parseColor("#F2B35E")

        private fun rgba(r: Int, g: Int, b: Int, a: Double) = Color.argb(Math.round(a * 255).toInt(), r, g, b)
    }
}
