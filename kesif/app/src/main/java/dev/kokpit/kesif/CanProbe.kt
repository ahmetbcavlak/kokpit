package dev.kokpit.kesif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Read-only probe for the values the vendor CAN app (com.hcn.autocan) publishes.
 * It only reads system properties and listens to broadcasts. It never binds the
 * CAN service, never sends a broadcast and never writes anything.
 */
object CanProbe {

    val doorKeys = listOf(
        "persist.vendor.cem.cardoor1" to "Sol ön",
        "persist.vendor.cem.cardoor2" to "Sağ ön",
        "persist.vendor.cem.cardoor3" to "Sol arka",
        "persist.vendor.cem.cardoor4" to "Sağ arka",
        "persist.vendor.cem.cardoor5" to "Bagaj",
    )
    val propKeys: List<String> = doorKeys.map { it.first } + listOf(
        "persist.vendor.cem.speed",
        "persist.sys.cem.speed.from.can",
        "persist.vendor.cem.light0",
        "persist.vendor.cem.light1",
        "persist.vendor.cem.light2",
    ) + (0..7).map { "persist.vendor.cem.radar$it" } + listOf("persist.vendor.cem.angle")

    val actions = listOf(
        "ACTION_AIR_INFO",
        "hcn.air.outdoor_temp",
        "ACTION_OUTDOOR_TEMP_DATA",
        "android.intent.action.VolumdChangeAuto",
    )

    data class Broadcast(val at: Long, val count: Int, val extras: Map<String, String>)
    data class Change(val at: Long, val key: String, val old: String, val new: String)

    private val props = linkedMapOf<String, String>()
    private val broadcasts = linkedMapOf<String, Broadcast>()
    private val changes = ArrayDeque<Change>()
    private val lock = Any()

    @Volatile private var running = false
    private var receiver: BroadcastReceiver? = null

    private val getMethod by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        }.getOrNull()
    }

    private fun readProp(key: String): String {
        getMethod?.let { m ->
            runCatching { return (m.invoke(null, key, "") as String).trim() }
        }
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        }.getOrDefault("")
    }

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val extras = intent.extras?.let { flatten(it) } ?: emptyMap()
                synchronized(lock) {
                    val prev = broadcasts[action]
                    broadcasts[action] = Broadcast(System.currentTimeMillis(), (prev?.count ?: 0) + 1, extras)
                    if (prev != null) {
                        for ((k, v) in extras) {
                            val o = prev.extras[k]
                            if (o != null && o != v) log("$action/$k", o, v)
                        }
                    }
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        runCatching {
            // The broadcasts come from the vendor app, so the receiver must accept other apps on Android 13+.
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(r, filter)
        }
        thread(name = "can-probe", isDaemon = true) {
            while (running) {
                for (k in propKeys) {
                    val v = readProp(k)
                    synchronized(lock) {
                        val old = props[k]
                        if (old != null && old != v) log(k, old, v)
                        props[k] = v
                    }
                }
                Thread.sleep(500)
            }
        }
    }

    fun stop(ctx: Context) {
        running = false
        receiver?.let { runCatching { ctx.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun flatten(b: Bundle): Map<String, String> =
        b.keySet().sorted().associateWith { k -> runCatching { @Suppress("DEPRECATION") b.get(k).toString() }.getOrDefault("?") }

    private fun log(key: String, old: String, new: String) {
        changes.addLast(Change(System.currentTimeMillis(), key, old, new))
        while (changes.size > 50) changes.removeFirst()
    }

    fun prop(key: String): String = synchronized(lock) { props[key] ?: "" }
    fun broadcast(action: String): Broadcast? = synchronized(lock) { broadcasts[action] }
    fun changeLog(): List<Change> = synchronized(lock) { changes.toList() }
    fun allProps(): Map<String, String> = synchronized(lock) { LinkedHashMap(props) }

    fun doorText(v: String) = when (v) {
        "1" -> "kapalı"
        "2" -> "AÇIK"
        "" -> "yok"
        else -> "ham: $v"
    }

    fun ago(at: Long?): String {
        if (at == null) return "hiç gelmedi"
        val s = (System.currentTimeMillis() - at) / 1000
        return "$s sn önce"
    }

    private fun air(key: String): String = broadcast("ACTION_AIR_INFO")?.extras?.get(key) ?: "—"

    /** Lines for the on-screen card and the text report. */
    fun lines(): List<Line> = buildList {
        doorKeys.forEach { (k, name) -> add(Line("Kapı · $name", doorText(prop(k)))) }
        add(Line("Hız", prop("persist.vendor.cem.speed").ifEmpty { "yok" } +
            " (CAN'dan: ${prop("persist.sys.cem.speed.from.can").ifEmpty { "?" }})"))
        add(Line("Sinyal sol / sağ / dörtlü",
            listOf("light0", "light1", "light2").joinToString(" / ") { prop("persist.vendor.cem.$it").ifEmpty { "—" } }))
        add(Line("Radar 0–7", (0..7).joinToString(" ") { prop("persist.vendor.cem.radar$it").ifEmpty { "—" } }))
        add(Line("Direksiyon açısı", prop("persist.vendor.cem.angle").ifEmpty { "yok" }))
        add(Line("Klima sol / sağ", "${air("KEY_VALUE_LEFT_TEMP")} / ${air("KEY_VALUE_RIGHT_TEMP")}"))
        add(Line("Fan / A/C / Auto", "${air("KEY_VALUE_WIND_LEVEL")} / ${air("KEY_VALUE_ACFLAG")} / ${air("KEY_VALUE_BIGAUTOFLAG")}"))
        actions.forEach { a ->
            val b = broadcast(a)
            add(Line("Yayın · $a", if (b == null) "hiç gelmedi" else "${b.count} kez · son: ${ago(b.at)}"))
        }
    }

    private val tf = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("tr"))

    fun changeLines(): List<Line> = changeLog().reversed().map {
        Line(tf.format(Date(it.at)), "${it.key}: ${it.old} → ${it.new}")
    }

    fun section(): Section = Section("Canlı CAN", lines())

    fun changeSection(): Section =
        Section("CAN değişiklik günlüğü (son 50)", changeLines().ifEmpty { listOf(Line("—", "Henüz değişiklik yok")) })

    fun detailSection(): Section = Section("CAN ham değerleri", buildList {
        allProps().forEach { (k, v) -> add(Line(k, v.ifEmpty { "(boş)" })) }
        actions.forEach { a ->
            broadcast(a)?.extras?.forEach { (k, v) -> add(Line("$a/$k", v)) }
        }
    })

    /** The report with live CAN sections first, so every request is fresh. */
    fun attach(r: Report): Report = r.copy(sections = listOf(section(), changeSection(), detailSection()) + r.sections)

    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    fun json(): String = buildString {
        append("{\"props\":{")
        append(allProps().entries.joinToString(",") { "${js(it.key)}:${js(it.value)}" })
        append("},\"broadcasts\":{")
        append(actions.mapNotNull { a ->
            broadcast(a)?.let { b ->
                "${js(a)}:{\"at\":${b.at},\"count\":${b.count},\"extras\":{" +
                    b.extras.entries.joinToString(",") { "${js(it.key)}:${js(it.value)}" } + "}}"
            }
        }.joinToString(","))
        append("},\"changes\":[")
        append(changeLog().joinToString(",") { "{\"at\":${it.at},\"key\":${js(it.key)},\"old\":${js(it.old)},\"new\":${js(it.new)}}" })
        append("]}")
    }
}
