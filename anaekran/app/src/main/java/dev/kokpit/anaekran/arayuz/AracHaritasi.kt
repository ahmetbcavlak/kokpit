package dev.kokpit.anaekran.arayuz

import android.content.Context
import android.graphics.PointF
import android.util.Log
import org.json.JSONObject

/**
 * Anchor points on the car for the HUD guide lines, from the design package's cars.json
 * (assets/cars.json): `frames.<ad>.a` are points in the 1600×900 car picture, `map` places the
 * picture on the 768×1024 screen (x · k + left, y · k + top). Results are design px.
 *
 * Which point a label uses (read off the references):
 *  - klima → `tavan` (Y-2: 477,386),
 *  - kapılar → the middle of `kapi_on_sol` and `kapi_arka_sol` (Y-2: 546,471; Y-14 ring 471,481),
 *  - bagaj → `bagaj` (Y-2: 646,448; Y-10: 594,465).
 * cars.json also lists `kapi` (park-gece-kapi.webp) and `ust` (ust-gece.webp); those pictures are
 * not in the design package, so they are not used.
 */
object AracHaritasi {

    enum class Kare(val ad: String) {
        PARK_GECE("gece"),
        PARK_GUNDUZ("gunduz"),
        BAGAJ("bagaj"),
        SURUS_GECE("sgece"),
        SURUS_GUNDUZ("sgunduz"),
    }

    class Noktalar(val tavan: PointF?, val kapi: PointF?, val bagaj: PointF?)

    private val BOS = Noktalar(null, null, null)

    @Volatile private var kareler: Map<Kare, Noktalar>? = null

    /** Reads cars.json once (5 KB). A broken file only drops the guide lines. */
    fun yukle(c: Context) {
        if (kareler != null) return
        kareler = try {
            c.assets.open("cars.json").bufferedReader().use { cozumle(it.readText()) }
        } catch (t: Throwable) {
            Log.w("Kokpit/AracHaritasi", "cars.json okunamadı", t)
            emptyMap()
        }
    }

    fun noktalar(k: Kare?): Noktalar = k?.let { kareler?.get(it) } ?: BOS

    internal fun cozumle(json: String): Map<Kare, Noktalar> {
        val kok = JSONObject(json)
        val map = kok.getJSONObject("map")
        val k = map.getDouble("k").toFloat()
        val sol = map.getDouble("left").toFloat()
        val ust = map.getDouble("top").toFloat()
        val frames = kok.getJSONObject("frames")
        val sonuc = HashMap<Kare, Noktalar>()
        for (kare in Kare.values()) {
            val a = frames.optJSONObject(kare.ad)?.optJSONObject("a") ?: continue
            fun nokta(ad: String): PointF? = a.optJSONArray(ad)?.let {
                PointF(it.getDouble(0).toFloat() * k + sol, it.getDouble(1).toFloat() * k + ust)
            }
            val on = nokta("kapi_on_sol")
            val arka = nokta("kapi_arka_sol")
            val kapi = if (on != null && arka != null) PointF((on.x + arka.x) / 2f, (on.y + arka.y) / 2f) else null
            sonuc[kare] = Noktalar(nokta("tavan"), kapi, nokta("bagaj"))
        }
        return sonuc
    }
}
