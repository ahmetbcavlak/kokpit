package dev.kokpit.anaekran.hareket

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import dev.kokpit.anaekran.Baglayici
import dev.kokpit.anaekran.Katmanlar
import dev.kokpit.anaekran.ekran.Gecis
import dev.kokpit.anaekran.hareket.surus.DurgunSaglayici
import dev.kokpit.anaekran.hareket.surus.HizSaglayici
import dev.kokpit.anaekran.hareket.surus.KareDinleyici
import dev.kokpit.anaekran.hareket.surus.SurusSahnesi
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.tema.TemaDinleyici

/**
 * Motion subsystem: intro clip, park ↔ drive clips / cross-fades, screen transition, the drive
 * scene (K-94/K-95) and the frame-rate counter. It does not read or change the scene layer, the screens or
 * MainActivity's state; the caller hands in pictures and reacts in `bitti`.
 *
 * Wiring (task 4):
 *  1. `Baglayicilar.liste()`: add one instance, and keep a reference (`val hareket = HareketBaglayici()`).
 *     [kur] puts the counter into `katmanlar.kareHizi`; [basla]/[dur] run it with onResume/onPause;
 *     [birak] frees the player.
 *  2. `katmanlar.ekranlar.gecis = hareket.gecis` (right after [kur]).
 *  3. Intro, in onCreate after [kur] (replaces `acilisBitti`/`ACILIS_MS` and the placeholder's tap):
 *     `hareket.acilisiOynat(hedefId) { ekranlar.goster(Ekran.PARK) }` — `hedefId` is the
 *     picture the scene layer shows right after `bitti` (e.g. `SahneKatmani.kaynak(Ekran.PARK, gunduz)`).
 *     Timing (K-93, [Zamanlama]): the clip's last frame is held 0.3 s after it ends (at least
 *     clip length + 0.3 s from this call), then a 0.6 s smootherstep fade. Taps: a transparent shield cuts on tap by itself;
 *     route other taps (e.g. the ACILIS screen's click) to [kes] as well — repeated calls are harmless.
 *  4. Park ↔ drive, call BEFORE switching the screen/scene:
 *     night: `hareket.gecisOynat(Klip.SURUS, eskiId, yeniId) { … }` (back: `Klip.SURUS_GERI`);
 *     day (no day clip): `hareket.gecisOynat(null, eskiId, yeniId) { … }` — a plain 0.6 s fade.
 *     `eskiId` = the scene on screen now (shown at once as a cover, so the scene layer may switch
 *     immediately after the call), `yeniId` = the scene after the change.
 *  5. In every `bitti`, the scene layer must show the target by the time `bitti` returns: the
 *     overlay is removed in the same main-thread turn. With the `Int` overloads the target was
 *     decoded through `Gorseller`, so `SahneKatmani.goster` gets it from the cache synchronously.
 *     `Sonuc.KESILDI` means a newer call took over. Starting a new call from any callback is
 *     safe (a run started from a KESILDI callback is itself ended with KESILDI).
 *  6. Scene layer while a clip plays (the opaque video covers it, but HWUI would still fill it
 *     every frame):
 *     `hareket.videoGorunurlugu = { v -> katmanlar.sahne.visibility = if (v) View.INVISIBLE else View.VISIBLE }`
 *     — `true` comes when the video's first frame is on screen, `false` before the video is
 *     removed (always before `bitti`, while the opaque target overlay covers the screen), in pairs.
 *     INVISIBLE, not GONE: the scene keeps its size and its bitmap. Can be set before or after [kur].
 *
 * The counter measures fps over the clip / fade / screen-transition / drive-scene spans these
 * components report (HareketIzci); nothing to wire for that.
 *
 * Drive scene (0.2, step 3 wiring; see also [SurusSahnesi]):
 *  7. Create it once, after [kur]: `val sahne = hareket.surusSahnesi(activity)`. It is a
 *     full-screen, transparent view that takes no touches; MainActivity puts it in its own root
 *     layer (`Katmanlar.surus`, MATCH_PARENT) between the scene picture and the video, under
 *     every screen — not inside the SURUS screen, so a screen change or a panel never redraws it
 *     inside a hardware layer (İ-M2 K7). The bitmaps are decoded on the worker
 *     thread right away (≈ 1.25 MB); keep the scene INVISIBLE until `sahne.hazirOlunca { … }`
 *     fires (before that it has no car). The frame-rate counter hears its motion by itself.
 *  8. Speed (veri/HizYumusatici, m/s, System.nanoTime timebase):
 *     `hareket.surusHizi(HizSaglayici(Hiz.yumusatici::deger), DurgunSaglayici(Hiz.yumusatici::durgun))`
 *     (primitive fun interfaces: no boxing per frame). `bilinmiyor` needs nothing here: `deger`
 *     is 0 then and the scene stands still (the HUD shows "—").
 *     Waking: in onResume `Hiz.yumusatici.uyandir = { hareket.surusUyandir() }` (the smoother
 *     calls it when it leaves rest, becomes known or fresh, and in `unut`), in onPause
 *     `Hiz.yumusatici.uyandir = null`. If the screen's own loop also owns `uyandir` (when the
 *     scene is hidden, see below), let one lambda call both. Without any waking the scene still
 *     restarts within 250 ms (it polls `durgun` while stopped).
 *     ONE reader per frame: `deger` advances the smoother on every call, so the SURUS screen must
 *     not call it as well while the scene runs. It takes the scene's value instead:
 *     `hareket.surusKaresi(KareDinleyici { zamanNs, hizMs -> surusEkrani.hizGoster(hizMs * 3.6) })`
 *     (after every drawn frame; while the scene is still the last value holds and is right;
 *     `Hiz.yumusatici.eskiMi(zamanNs)` / `bilinmiyor` may be read in the same callback, they do not
 *     move the spring). The callback comes only while the scene runs. When it does not (day: scene
 *     INVISIBLE; while `Klip.SURUS` plays; before `hazirOlunca`), the screen reads `deger` on its
 *     own frame loop instead — the test is `hareket.surus?.calisiyor`; never both.
 *  9. Running: [basla]/[dur] (onResume/onPause, already wired as a Baglayici) start and stop it;
 *     on top of that it runs only while it is visible (a GONE/INVISIBLE SURUS screen stops it).
 *     Nothing else to call per frame.
 * 10. Night only: the body and wheels are the night car. By day keep 0.1's static picture
 *     (scene layer `sahne_surus_gunduz`) and leave the drive scene INVISIBLE.
 * 11. Scene layer under it at night: `boya_gece` (no car — the scene draws the car). Park → drive
 *     at night: keep the drive scene INVISIBLE while `Klip.SURUS` plays (the clip has its own
 *     car), and in the clip's `bitti` switch the scene layer to `boya_gece`, make the drive
 *     scene VISIBLE and call `sahne.durgundanBasla()`: its first frame equals the clip's last
 *     one (wheel angle 0, camera 1, lines/lights at alpha 0; PSNR 36.5 dB against sahne_surus),
 *     then over 1 s the speed and the lines/lights come in.
 *     Drive → park: hide the scene when `Klip.SURUS_GERI` starts (its poster is sahne_surus, the
 *     same still car; at the switch the car is below 5 km/h, so nearly at rest).
 * 12. Remove `Cizgiler.hizCizgileri` from SurusEkrani's drawing: the scene draws the speed lines
 *     itself (with the speed-dependent opacity), otherwise they are doubled.
 * 13. The SURUS screen's own layers may enter with `Zamanlama.giris(view, sira)`; the screen
 *     change itself already uses the K-93 timings ([YumusakGecis], 1.1 s).
 */
class HareketBaglayici(
    private val oynaticiUret: (Context) -> Oynatici = { DokuOynatici(it) },
    private val yukleyici: GorselYukleyici = GorselYukleyici.VARSAYILAN,
    private val kareKaynagi: KareKaynagi = KareKaynagi.CHOREOGRAPHER,
) : Baglayici, TemaDinleyici {

    private val yumusakGecis = YumusakGecis()

    /** Screen transition for `EkranYoneticisi.gecis`. */
    val gecis: Gecis get() = yumusakGecis

    /** See wiring step 6. */
    var videoGorunurlugu: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            oynatici?.videoGorunurlugu = value
        }

    var oynatici: KlipOynatici? = null
        private set

    var sayac: KareHiziSayaci? = null
        private set

    /** The drive scene, once [surusSahnesi] created it. */
    var surus: SurusSahnesi? = null
        private set

    private var surusHizi: HizSaglayici? = null
    private var surusDurgun: DurgunSaglayici? = null
    private var surusKaresi: KareDinleyici? = null
    private var calisiyor = false

    override fun kur(activity: Activity, katmanlar: Katmanlar) {
        val s = KareHiziSayaci(activity, kareKaynagi)
        s.temaUygula(Tema.palet)
        // The slot has a fixed 96×20 design-px size; a single line that keeps its height makes
        // setText invalidate only, without requestLayout (TextView.checkForRelayout).
        katmanlar.kareHizi.addView(
            s,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        sayac = s
        yumusakGecis.izci = s
        oynatici = KlipOynatici(katmanlar.video, katmanlar.kok, oynaticiUret, yukleyici).also {
            it.izci = s
            it.videoGorunurlugu = videoGorunurlugu
        }
        Tema.dinle(this)
    }

    override fun basla() {
        calisiyor = true
        sayac?.basla()
        surus?.basla()
    }

    /**
     * The clip is not cut here: a permission dialog pauses us while our window stays visible,
     * and must not cut the intro. When another app really comes to the front, the window goes
     * out of sight and the player releases the decoder by itself (Oynatici.Dinleyici.pencereGizlendi).
     */
    override fun dur() {
        calisiyor = false
        surus?.dur()
        sayac?.dur()
    }

    override fun birak() {
        calisiyor = false
        surus?.birak()
        surus = null
        sayac?.dur()
        yumusakGecis.izci = null
        oynatici?.birak()
        Tema.birak(this)
    }

    override fun temaDegisti(palet: Palet) {
        sayac?.temaUygula(palet)
    }

    /**
     * Intro: `klip_giris` (2.4 s) plays, its last frame is held 0.3 s, then a 0.6 s fade to
     * [hedef] (K-93; at least clip length + 0.3 s after this call); a tap cuts with a 0.25 s fade.
     * [bitti] once: TAMAM, DOKUNULDU, ATLANDI (clip failed: faded straight to [hedef]) or KESILDI.
     */
    fun acilisiOynat(hedef: Goruntu, bitti: (KlipOynatici.Sonuc) -> Unit) {
        gerekli().oynat(
            Klip.GIRIS, null, hedef, ACILIS_TUT_MS, ACILIS_GECIS_MS,
            dokunmaKalkani = true, sonTutMs = Zamanlama.KLIP_SONU_TUT_MS, bitti = bitti,
        )
    }

    /** [acilisiOynat] with a drawable resource id. */
    fun acilisiOynat(hedefId: Int, bitti: (KlipOynatici.Sonuc) -> Unit) = acilisiOynat(Goruntu.Kaynak(hedefId), bitti)

    /**
     * Park ↔ drive (K-93). With a [klip] (2.13 s): plays it, holds its last frame 0.3 s, fades
     * to [yeni] in 0.6 s. Without: plain cross-fade [eski] → [yeni] in 0.6 s. A call while
     * another runs ends that one at once (its `bitti(KESILDI)`) and starts from its target.
     */
    fun gecisOynat(klip: Klip?, eski: Goruntu?, yeni: Goruntu, bitti: (KlipOynatici.Sonuc) -> Unit) {
        if (klip == null) {
            gerekli().oynat(null, eski, yeni, 0L, DUZ_GECIS_MS, dokunmaKalkani = false, bitti = bitti)
        } else {
            gerekli().oynat(
                klip, eski, yeni, Zamanlama.klipTutMs(klip), KLIP_GECIS_MS,
                dokunmaKalkani = false, sonTutMs = Zamanlama.KLIP_SONU_TUT_MS, bitti = bitti,
            )
        }
    }

    /** [gecisOynat] with drawable resource ids. */
    fun gecisOynat(klip: Klip?, eskiId: Int, yeniId: Int, bitti: (KlipOynatici.Sonuc) -> Unit) =
        gecisOynat(klip, Goruntu.Kaynak(eskiId), Goruntu.Kaynak(yeniId), bitti)

    /** Tap: cut the running clip / fade quickly. No-op when idle. */
    fun kes() {
        oynatici?.kes()
    }

    /**
     * The drive scene (wiring steps 7–13 above). Created on the first call and kept; its bitmaps
     * start decoding at once. It runs between [basla] and [dur] while it is visible.
     */
    fun surusSahnesi(context: Context): SurusSahnesi = surus ?: SurusSahnesi(context, kareKaynagi).also {
        it.izci = sayac
        it.hizSaglayici = surusHizi
        it.durgunSaglayici = surusDurgun
        it.kareDinleyici = surusKaresi
        it.varliklariYukle()
        if (calisiyor) it.basla()
        surus = it
    }

    /**
     * Speed for the drive scene: [hiz] smoothed m/s for a frame time, [durgun] true when the
     * smoother is at rest (normally `Hiz.yumusatici::deger` / `::durgun`). May be set before or
     * after [surusSahnesi].
     */
    fun surusHizi(hiz: HizSaglayici, durgun: DurgunSaglayici) {
        surusHizi = hiz
        surusDurgun = durgun
        surus?.let {
            it.hizSaglayici = hiz
            it.durgunSaglayici = durgun
            it.uyandir()
        }
    }

    /**
     * Per-frame speed for the HUD, the same value the scene used ([SurusSahnesi.kareDinleyici]).
     * May be set before or after [surusSahnesi].
     */
    fun surusKaresi(dinleyici: KareDinleyici?) {
        surusKaresi = dinleyici
        surus?.kareDinleyici = dinleyici
    }

    /** A new speed may have arrived (call on every GPS sample): a stopped drive scene restarts at once. */
    fun surusUyandir() {
        surus?.uyandir()
    }

    private fun gerekli(): KlipOynatici = checkNotNull(oynatici) { "HareketBaglayici.kur() önce çağrılmalı" }

    companion object {
        /** Intro hold from the call: clip length + 0.3 s (K-93). */
        val ACILIS_TUT_MS: Long = Zamanlama.klipTutMs(Klip.GIRIS)
        const val ACILIS_GECIS_MS = Zamanlama.SAHNE_GECIS_MS
        const val KLIP_GECIS_MS = Zamanlama.SAHNE_GECIS_MS
        const val DUZ_GECIS_MS = Zamanlama.SAHNE_GECIS_MS
    }
}
