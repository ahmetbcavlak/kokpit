package dev.kokpit.anaekran.veri

import android.os.Bundle
import java.util.Locale

/**
 * Turns the vendor's climate broadcasts into [Klima] and an outside temperature.
 *
 * All broadcast and extra names live here, so the first test on the car can correct them in one
 * place. What is confirmed and what is not:
 *  - Actions ACTION_AIR_INFO, hcn.air.outdoor_temp, ACTION_OUTDOOR_TEMP_DATA: the ones Kokpit Keşif
 *    (LiveProbe) listens to; climate over ACTION_AIR_INFO was seen on the car on 12 Nov.
 *  - Extras KEY_VALUE_LEFT_TEMP, _RIGHT_TEMP, _WIND_LEVEL, _ACFLAG, _BIGAUTOFLAG: Keşif's climate card
 *    (LiveProbe.kt), the source of the 12 Nov result. Temperature encoding seen on the car (13 Nov,
 *    0.1 test; design pack 0.2 BENIOKU §4): TEXT, "LOW", "HI" or degrees with a dot decimal and the
 *    U+2103 sign ("23.0℃"); "0.0℃" while the engine is off. The other values' encoding (0/1 flags?
 *    fan 0–8 as raw numbers?) is not recorded, so only unambiguous values are accepted, the rest is null.
 *  - Air direction, demist: present on the unit (12 Nov) but no confirmed extra name, so [Klima.havaYonu]
 *    and [Klima.buzCozme] are never filled in 0.1.
 *  - Single zone: [Klima.sicaklik] is LEFT_TEMP only. RIGHT_TEMP is kept in the raw diagnostics: the unit
 *    is single-zone ("DUAL hep 0", the passenger side follows the driver's), so a second number on screen
 *    would only invite a difference that the car does not have; if LEFT_TEMP is missing the value is unknown.
 *  - Outside-temperature extra names: only the names the project has on record ([DIS_SICAKLIK]):
 *    `hcn.air.outdoor_temp` (research notes, decision 15) and `ACTION_OUTDOOR_TEMP_DATA` (the other
 *    outside-temperature name in Keşif's LiveProbe). Any other key is NOT read as a temperature
 *    (K-74, no guessing from "temp", "value" or "the only extra"); it shows raw in [Teshis] only.
 * [Teshis.klimaHam] shows every raw key on the car, so names and encodings can be fixed after the first look.
 *
 * Rule: an unknown or out-of-range value becomes null, never a default "off" or 0.
 */
object KlimaCozucu {

    const val EYLEM_KLIMA = "ACTION_AIR_INFO"
    const val EYLEM_DIS_SICAKLIK = "hcn.air.outdoor_temp"
    const val EYLEM_DIS_SICAKLIK_2 = "ACTION_OUTDOOR_TEMP_DATA"
    val EYLEMLER = listOf(EYLEM_KLIMA, EYLEM_DIS_SICAKLIK, EYLEM_DIS_SICAKLIK_2)

    // Confirmed names (Keşif climate card).
    const val SOL_SICAKLIK = "KEY_VALUE_LEFT_TEMP"
    /** Diagnostics only (single zone). */
    const val SAG_SICAKLIK = "KEY_VALUE_RIGHT_TEMP"
    const val FAN = "KEY_VALUE_WIND_LEVEL"
    const val AC = "KEY_VALUE_ACFLAG"
    const val OTO = "KEY_VALUE_BIGAUTOFLAG"

    /**
     * The only extra names read as the outside temperature, in its own broadcasts and inside
     * ACTION_AIR_INFO. Add a name here only after it has been seen carrying the value on the car.
     */
    val DIS_SICAKLIK = listOf(EYLEM_DIS_SICAKLIK, EYLEM_DIS_SICAKLIK_2)

    /** The keys that make a broadcast a climate state message. */
    private val KLIMA_ANAHTARLARI = listOf(SOL_SICAKLIK, SAG_SICAKLIK, FAN, AC, OTO)

    /**
     * Plausible set temperatures in °C. Kept wide on purpose: the unit's real range and step are not
     * confirmed (about 15–32 is usual); a value outside (e.g. a coded LOW/HI or a half-degree count)
     * stays null and visible in [Teshis].
     */
    private val AYAR_ARALIGI = 10.0..35.0
    private val DIS_ARALIGI = -45.0..65.0
    const val FAN_EN_COK = 8

    /**
     * Climate from one ACTION_AIR_INFO extras bundle. null = the bundle carries none of the climate
     * keys (then the current climate is left as it is). A present bundle is read as a full state:
     * a key missing from it is unknown (null), not "unchanged".
     */
    fun klima(b: Bundle): Klima? {
        if (KLIMA_ANAHTARLARI.none { b.containsKey(it) }) return null
        return Klima(
            sicaklik = sicaklik(deger(b, SOL_SICAKLIK)),
            fan = fan(deger(b, FAN)),
            havaYonu = null, // no confirmed key
            ac = bayrak(deger(b, AC)),
            oto = bayrak(deger(b, OTO)),
            buzCozme = null, // no confirmed key
        )
    }

    /** Outside temperature inside ACTION_AIR_INFO, if one of the known names is present. */
    fun disSicaklikKlimadan(b: Bundle): Okunan? =
        DIS_SICAKLIK.firstOrNull { b.containsKey(it) }?.let { Okunan(disSicaklik(deger(b, it))) }

    /**
     * Outside temperature from its own broadcast: only a known extra name ([DIS_SICAKLIK]).
     * null = nothing to read (the current value stays); unknown extras are left to the raw diagnostics.
     */
    fun disSicaklikYayini(b: Bundle?): Okunan? {
        if (b == null || b.isEmpty) return null
        val ad = DIS_SICAKLIK.firstOrNull { b.containsKey(it) } ?: return null
        return Okunan(disSicaklik(deger(b, ad)))
    }

    /** A value that was present in the broadcast; [deger] is null when it could not be read. */
    data class Okunan(val deger: Double?)

    // --- single values ---

    /**
     * Set temperature: "LOW"/"HI" (and LO/HIGH), or degrees as text ("23.0℃", "23.0 °C", "23.0°", "23.0")
     * or as a number. 0 ("0.0℃", engine off) and values outside [AYAR_ARALIGI] are unknown (null).
     */
    fun sicaklik(v: Any?): Sicaklik? {
        if (v is String) {
            if (v.length > METIN_EN_UZUN) return null
            when (v.trim().uppercase(Locale.ROOT)) {
                "LO", "LOW" -> return Sicaklik.Low
                "HI", "HIGH" -> return Sicaklik.Hi
            }
        }
        val d = derece(v) ?: return null
        if (d == 0.0) return null // "0.0℃": engine off, not a set temperature
        return if (d in AYAR_ARALIGI) Sicaklik.Derece(d) else null
    }

    fun fan(v: Any?): Int? {
        val d = sayi(v) ?: return null
        if (d != Math.floor(d)) return null
        val n = d.toInt()
        return if (n in 0..FAN_EN_COK) n else null
    }

    /** 0/1 or a real boolean; anything else is unknown. */
    fun bayrak(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        null -> null
        else -> when (sayi(v)) {
            0.0 -> false
            1.0 -> true
            else -> if (v is String && v.length <= METIN_EN_UZUN) v.trim().lowercase(Locale.ROOT).toBooleanStrictOrNull() else null
        }
    }

    /** Outside temperature, number or text with the same degree suffixes; 0 is a real value here. */
    fun disSicaklik(v: Any?): Double? {
        val d = derece(v) ?: return null
        return if (d in DIS_ARALIGI) d else null
    }

    /** A trailing degree sign: "℃" (U+2103), "°C", "°", with any spaces around it. */
    private val DERECE_EKI = Regex("\\s*(\u2103|°\\s*[Cc]?)\\s*$")

    /** [sayi] for temperatures: a text value may end in a degree sign ([DERECE_EKI]). */
    private fun derece(v: Any?): Double? =
        if (v is String) { if (v.length > METIN_EN_UZUN) null else sayi(v.trim().replace(DERECE_EKI, "")) } else sayi(v)

    /** Longer text is not a value of these keys (and is not worth a regex): null. */
    const val METIN_EN_UZUN = 32

    /**
     * The only accepted number text: optional sign, digits, optionally a dot or comma and digits
     * ("23", "-4", "23.0", "23,5"). Rejects what toDouble() would also take: "1e1", "23d", "0x1.8p4",
     * "NaN", "Infinity", ".5", "5." (0.1.1 review).
     */
    private val SAYI_METNI = Regex("""^[+-]?\d+([.,]\d+)?$""")

    /**
     * Int, Long, Float, Double or text. Text must match [SAYI_METNI] after trimming and is read with a dot
     * decimal, independent of the Locale; a comma is also taken as the decimal mark ("23,0"), as no unit
     * would use it as a thousands separator for these small numbers.
     */
    private fun sayi(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> {
            val m = v.trim()
            if (v.length > METIN_EN_UZUN || !SAYI_METNI.matches(m)) null
            else m.replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
        }
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun deger(b: Bundle, k: String): Any? = b.get(k)

    /** "KEY=value, KEY=value", sorted; arrays joined with commas (same as Keşif's LiveProbe). */
    fun duzle(b: Bundle?): String {
        if (b == null) return "(extras yok)"
        return b.keySet().sorted().joinToString(", ") { k ->
            val v = runCatching {
                when (val x = deger(b, k)) {
                    is IntArray -> x.joinToString(",")
                    is ByteArray -> x.joinToString(",")
                    is FloatArray -> x.joinToString(",")
                    is Array<*> -> x.joinToString(",")
                    else -> x.toString()
                }
            }.getOrDefault("?")
            "$k=$v"
        }.ifEmpty { "(boş)" }
    }
}
