package dev.kokpit.anaekran.hareket.surus

/**
 * Where the drive scene's pieces sit, in design px (768×1024), from the design pack's
 * hareket/yerlesim.json (`ekran_x = left + x·k`, k 0.60109, left −74.634, top 242.656) and the
 * demo's CSS. The bitmaps were made for exactly these positions by the asset script
 * (/home/claude/hareket-is/0.2/betik/varlik.py, outside the repo):
 *  - surus_govde.webp: the 1600×900 body resampled onto the screen grid (Lanczos on
 *    premultiplied alpha, the fractional left/top included), so it is drawn 1:1 at an integer
 *    position; then cropped to its alpha ≥ 12 box (the faint ground shadow below that changes
 *    the picture by ≤ 2 of 255 levels) and to what any camera scale can show (x −18…780).
 *  - surus_teker_{on,arka}_{0,1,2}.webp: the 168 px sprites resampled to 101 px with the wheel
 *    centre exactly at the sprite centre (50.5, 50.5).
 */
object SurusYerlesim {
    const val TASARIM_W = 768
    const val TASARIM_H = 1024

    /** Body bitmap (cropped) top-left and size. */
    const val GOVDE_X = -18
    const val GOVDE_Y = 401
    const val GOVDE_W = 798
    const val GOVDE_H = 272

    /** The demo's full body box (lamp reflection coordinates): left/top/width/height. */
    const val KUTU_X = -74.634f
    const val KUTU_Y = 242.656f
    const val KUTU_W = 961.749f
    const val KUTU_H = 540.984f

    /** Wheel centres on screen (yerlesim.json merkez_kaynak × k + left/top). */
    const val ON_X = 214.492f
    const val ARKA_X = 596.787f
    const val TEKER_Y = 565.142f

    /** Sprite size, px (168 × k = 100.98). */
    const val SPRITE = 101
    const val SPRITE_YARIM = SPRITE / 2f

    /** Ground contact line (yerlesim.json `temas_cizgisi_ekran_y`). */
    const val TEMAS_Y = 610.8f

    /** Camera scale pivot (demo `transform-origin:466px 611px`). */
    const val KAMERA_PX = 466f
    const val KAMERA_PY = 611f

    /** Near lane line: contact + 46 px, 3 px high (demo top 657). */
    const val YAKIN_Y = 657f
    const val YAKIN_H = 3f

    /** Far lane line: contact − 26 px, 2 px high (demo top 585). */
    const val UZAK_Y = 585f
    const val UZAK_H = 2f

    /** Lane lines fade in over the first and out over the last 12 % of the width. */
    const val SERIT_KENAR = 0.12f

    /** City lights strip: top 447, 60 px high, 600 px pattern tile. */
    const val ISIK_Y = 447
    const val ISIK_H = 60
    const val ISIK_DESEN = 600

    /** Speed lines box behind the tail: left 568, top 421, 200×168, a 1 px line every 12 px. */
    const val IZ_X = 568
    const val IZ_Y = 421
    const val IZ_W = 200
    const val IZ_H = 168
    const val IZ_ARALIK = 12
}
