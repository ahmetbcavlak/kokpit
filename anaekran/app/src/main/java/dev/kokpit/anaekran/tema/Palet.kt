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
    /**
     * Drive scene lane lines (K-94/K-108): the colour at the demo's 55 % (× each line's own
     * opacity). Not in tokens.json: night is the 0.2 demo's rgba(220,226,236,.55); day is the
     * 0.1 Y-11 speed-line ink at the same 55 %, because the night colour on the day ground
     * (≈ #D9DDE0 at the lane rows) differs by 1–4 levels and the lines would vanish.
     */
    val serit: Int,
    /**
     * Drive scene speed lines behind the tail. Night: the 0.2 demo's rgba(150,180,240,.30);
     * day: 0.1 Y-11's static day speed lines, rgba(40,60,100,.22) — the ones the day scene
     * replaces (the night colour would be a faint blue tint on the light ground).
     */
    val iz: Int,
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
            serit = rgba(220, 226, 236, 0.55),
            iz = rgba(150, 180, 240, 0.30),
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
            serit = rgba(40, 60, 100, 0.55),
            iz = Color.argb(56, 40, 60, 100),
        )

        /** tokens.json "vurgu" (theme-independent accent). */
        val VURGU = Color.parseColor("#F2B35E")

        private fun rgba(r: Int, g: Int, b: Int, a: Double) = Color.argb(Math.round(a * 255).toInt(), r, g, b)
    }
}
