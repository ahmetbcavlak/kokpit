package dev.kokpit.anaekran.hareket

import dev.kokpit.anaekran.R

/**
 * The three clips (res/raw, design pack 0.2, K-93): H.264 Main 3.1, 768×1024, 30 fps, no
 * B-frames, no audio, faststart, colour tagged bt709/tv. The camera eases in and out
 * (smootherstep); the last frame is held 0.3 s before the 0.6 s scene fade ([Zamanlama]).
 *
 * @property ham raw resource of the mp4.
 * @property poster the clip's first frame, shown above the video until the first decoded
 *   frame is on screen (no black flash). GIRIS has its own 5 KB webp (made from the encoded
 *   clip's first frame; it is a background no scene matches); the transition clips start on a
 *   scene that is already in the APK (PSNR ≈ 35 dB against the first frame), so they cost no bytes.
 * @property sonKare the scene the clip ends on (PSNR 33.8 / 35.2 / 34.9 dB for GIRIS / SURUS /
 *   SURUS_GERI, SSIM ≥ 0.95; only codec softness differs). At night this is the natural `hedef`.
 * @property sureMs clip length (72 frames = 2.4 s; 64 frames = 2.13 s).
 * @property acilis true for the intro: until the poster is decoded the video layer is
 *   covered with the window colour, so the scene layer underneath (already showing the
 *   parked car) never flashes before the car drives in.
 */
enum class Klip(
    val ham: Int,
    val poster: Goruntu,
    val sonKare: Int,
    val sureMs: Long,
    val acilis: Boolean,
) {
    GIRIS(R.raw.klip_giris, Goruntu.Ham(R.raw.klip_giris_ilk), R.drawable.sahne_karsilama, 2400L, true),
    SURUS(R.raw.klip_surus, Goruntu.Kaynak(R.drawable.sahne_park), R.drawable.sahne_surus, 2133L, false),
    SURUS_GERI(R.raw.klip_surus_geri, Goruntu.Kaynak(R.drawable.sahne_surus), R.drawable.sahne_park, 2133L, false),
}
