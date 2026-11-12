package dev.kokpit.anaekran.hareket

import dev.kokpit.anaekran.R

/**
 * The three clips (res/raw, H.264 Main, 768×1024, 30 fps, no audio, faststart).
 *
 * @property ham raw resource of the mp4.
 * @property poster the clip's first frame, shown above the video until the first decoded
 *   frame is on screen (no black flash). GIRIS has its own 5 KB webp (its first frame is an
 *   empty background that no scene matches); the transition clips start on a scene that is
 *   already in the APK (PSNR ≈ 35 dB against the first frame), so they cost no bytes.
 * @property sonKare the scene the clip ends on (K-47; PSNR ≈ 34–35 dB, only codec softness
 *   differs). At night this is the natural `hedef`.
 * @property sureMs clip length.
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
    GIRIS(R.raw.klip_giris, Goruntu.Ham(R.raw.klip_giris_ilk), R.drawable.sahne_karsilama, 1333L, true),
    SURUS(R.raw.klip_surus, Goruntu.Kaynak(R.drawable.sahne_park), R.drawable.sahne_surus, 800L, false),
    SURUS_GERI(R.raw.klip_surus_geri, Goruntu.Kaynak(R.drawable.sahne_surus), R.drawable.sahne_park, 800L, false),
}
