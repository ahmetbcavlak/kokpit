package dev.kokpit.kesif

import android.os.Build

/**
 * Build and MCU identity of the unit, for the text report, the CSV recording and the package export.
 *
 * The MCU version has no standard property; head units keep it under vendor-specific names
 * (e.g. sys.mcu.version, persist.sys.mcu_ver). Rather than guess one name, every property whose
 * key mentions "mcu" is listed. Read-only: getprop with no arguments, nothing is set.
 */
object DeviceInfo {

    /** Property keys never shown, same rule as the report's property list. */
    private val blocked = listOf("serial", "imei", "mac", "meid", "uuid", "bootloader")

    data class Prop(val key: String, val value: String)

    /** Properties whose key contains "mcu", sorted by key. */
    fun mcuProps(): List<Prop> = runCatching {
        val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
        // Same watchdog as the live probe: a hung getprop is killed after 3 s.
        val dog = kotlin.concurrent.thread(isDaemon = true) { runCatching { Thread.sleep(3000); p.destroy() } }
        val text = try { p.inputStream.bufferedReader().readText() } finally { dog.interrupt(); p.destroy() }
        text.lineSequence().mapNotNull { line ->
            val a = line.indexOf("]: [")
            if (!line.startsWith("[") || a < 0 || !line.endsWith("]")) return@mapNotNull null
            val k = line.substring(1, a)
            val v = line.substring(a + 4, line.length - 1).trim()
            val kl = k.lowercase()
            if (!kl.contains("mcu") || blocked.any { kl.contains(it) }) null else Prop(k, v)
        }.sortedBy { it.key }.toList()
    }.getOrDefault(emptyList())

    /** Label/value pairs; the same list goes into the report, the CSV and paketler.csv. */
    /** Fills the cache off the main thread; MainActivity calls this at start. */
    fun warmUp() { kotlin.concurrent.thread(isDaemon = true) { runCatching { lines() } } }

    /** Worked out once per run: the build does not change while the app is open. */
    fun lines(): List<Line> = cached

    private val cached: List<Line> by lazy {
        val mcu = mcuProps()
        listOf(
            Line("Build.DISPLAY", Build.DISPLAY),
            Line("Build.FINGERPRINT", Build.FINGERPRINT),
            Line("Build.ID", Build.ID),
            Line("Build.VERSION.INCREMENTAL", Build.VERSION.INCREMENTAL),
            Line("Build.TIME", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(Build.TIME))),
        ) + if (mcu.isEmpty()) listOf(Line("MCU sürümü", "anahtarında “mcu” geçen sistem değişkeni yok"))
        else mcu.map { Line(it.key, it.value.ifEmpty { "(boş)" }) }
    }

    fun section() = Section("Derleme ve MCU", lines())
}
