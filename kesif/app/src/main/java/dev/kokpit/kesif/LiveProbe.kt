package dev.kokpit.kesif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Read-only probe for the vehicle values the vendor CAN app (com.hcn.autocan) publishes.
 *
 * What it does: reads system properties and listens to the vendor's broadcasts.
 * What it never does: bind the CAN service, send a broadcast, set a property or write a setting.
 *
 * Two kinds of reading:
 *  - known keys (doors, speed, radar, angle, lights), every [FAST_MS] through SystemProperties.get
 *  - a full `getprop` scan every [SCAN_MS], so keys we do not know yet (gear, headlights) show up
 *    as soon as they change
 */
object LiveProbe {

    const val FAST_MS = 250L
    const val SCAN_MS = 2000L
    private const val MAX_EVENTS = 300

    enum class Status { NONE, PRESENT, LIVE }

    /** State of one key, either a property or a broadcast extra ("ACTION/EXTRA"). */
    class KeyState(var value: String, val firstAt: Long) {
        var lastChangeAt = 0L
        var changes = 0
    }

    data class Event(val at: Long, val kind: String, val key: String, val old: String, val new: String)

    /** A card on the screen: a vehicle signal and the keys it is made of. */
    class Signal(val id: String, val title: String, val item: String, val fixed: List<Pair<String, String>>, val discover: Regex? = null)

    private const val P = "persist.vendor.cem."
    const val AIR = "ACTION_AIR_INFO"

    val signals = listOf(
        Signal("kapi", "Kapılar ve bagaj", "3.1", listOf(
            "${P}cardoor1" to "Sol ön", "${P}cardoor2" to "Sağ ön",
            "${P}cardoor3" to "Sol arka", "${P}cardoor4" to "Sağ arka", "${P}cardoor5" to "Bagaj",
        )),
        Signal("hiz", "Hız", "9.4", listOf(
            "${P}speed" to "Hız", "persist.sys.cem.speed.from.can" to "CAN'dan mı",
        )),
        Signal("vites", "Vites", "3.6", emptyList(),
            discover = Regex("gear|reverse|backcar|back_car|shift|dang|prnd", RegexOption.IGNORE_CASE)),
        Signal("direksiyon", "Direksiyon açısı", "3.4", listOf("${P}angle" to "Açı (ham)")),
        Signal("radar", "Park sensörü", "3.5", (0..7).map { "${P}radar$it" to "R$it" }),
        Signal("far", "Far, sinyal, dörtlü", "3.3", listOf(
            "${P}light0" to "light0", "${P}light1" to "light1", "${P}light2" to "light2",
        ), discover = Regex("light|lamp|beam|headl|fog", RegexOption.IGNORE_CASE)),
        Signal("klima", "Klima", "3.7", listOf(
            "$AIR/KEY_VALUE_LEFT_TEMP" to "Sol", "$AIR/KEY_VALUE_RIGHT_TEMP" to "Sağ",
            "$AIR/KEY_VALUE_WIND_LEVEL" to "Fan", "$AIR/KEY_VALUE_ACFLAG" to "A/C",
            "$AIR/KEY_VALUE_BIGAUTOFLAG" to "Oto",
        ), discover = Regex("^(ACTION_AIR_INFO|hcn\\.air\\.outdoor_temp|ACTION_OUTDOOR_TEMP_DATA)/")),
    )

    val actions = listOf(AIR, "hcn.air.outdoor_temp", "ACTION_OUTDOOR_TEMP_DATA", "android.intent.action.VolumdChangeAuto")

    private val fixedPropKeys: List<String> =
        signals.flatMap { s -> s.fixed.map { it.first } }.filter { !it.contains('/') }

    /** Properties that change by themselves and say nothing about the car. */
    private val noisy = Regex("^(sys\\.sysctl|sys\\.usb|init\\.svc|ro\\.|dev\\.|vendor\\.debug|debug\\.|net\\.|dhcp\\.|wifi\\.|wlan\\.|gsm\\.|persist\\.sys\\.timezone|sys\\.oem_unlock|sys\\.boot|log\\.tag|service\\.bootanim|hwservicemanager|bt\\.|bluetooth\\.)")

    private val lock = Any()
    private val known = linkedMapOf<String, KeyState>()      // fixed keys + broadcast extras
    private val scanned = hashMapOf<String, KeyState>()      // every property seen by the full scan
    private val broadcastCount = linkedMapOf<String, Int>()
    private val broadcastAt = hashMapOf<String, Long>()
    private val events = ArrayDeque<Event>()

    @Volatile private var running = false
    @Volatile private var generation = 0
    /** Set by the activity: when the screen is hidden and nothing records, the costly full scan pauses. */
    @Volatile var visible = true
    @Volatile private var firstScanDone = false
    private var receiver: BroadcastReceiver? = null
    /** signal id → keys matching its discover pattern, worked out once per new key. */
    private val discovered = hashMapOf<String, MutableSet<String>>()
    @Volatile var startedAt = 0L; private set
    @Volatile var scanCount = 0; private set
    @Volatile var scanKeys = 0; private set
    @Volatile var scanMs = 0L; private set
    @Volatile var method = "—"; private set
    @Volatile var lastError: String? = null; private set

    /** Called for every change, outside the lock. The recorder hooks in here. */
    @Volatile var listener: ((Event) -> Unit)? = null

    private val getMethod by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        }.getOrNull()
    }

    private fun readProp(key: String): String {
        getMethod?.let { m ->
            runCatching { return (m.invoke(null, key, "") as String).trim() }
        }
        return ""
    }

    fun start(ctx: Context) {
        if (running) return
        running = true
        val gen = ++generation
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        method = if (getMethod != null) "SystemProperties + getprop" else "yalnız getprop"
        registerReceiver(ctx.applicationContext)
        // The generation stops an older loop that wakes after a quick stop/start from running twice.
        thread(name = "kesif-live", isDaemon = true) { loop(gen) }
    }

    fun stop(ctx: Context) {
        running = false
        generation++
        receiver?.let { runCatching { ctx.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun registerReceiver(app: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                // A vendor extra we cannot unpack must never crash the app (it runs on the main thread).
                try {
                    val action = intent?.action ?: return
                    val extras = intent.extras?.let { flatten(it) } ?: emptyMap()
                    val now = System.currentTimeMillis()
                    val out = ArrayList<Event>()
                    synchronized(lock) {
                        broadcastCount[action] = (broadcastCount[action] ?: 0) + 1
                        broadcastAt[action] = now
                        // Broadcasts are usually sent on a change, so their first arrival counts too.
                        for ((k, v) in extras) update(known, "$action/$k", v, now, "yayin", out, firstIsChange = true)
                    }
                    emit(out)
                } catch (t: Throwable) {
                    lastError = "Yayın okunamadı: ${t.javaClass.simpleName}"
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        runCatching {
            // The broadcasts come from the vendor app, so on Android 13+ the receiver must accept other apps.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(r, filter)
        }.onFailure { lastError = "Yayın dinleyici: ${it.message}" }
    }

    private fun loop(gen: Int) {
        var nextScan = 0L
        while (running && gen == generation) {
            val t0 = SystemClock.elapsedRealtime()
            val now = System.currentTimeMillis()
            val out = ArrayList<Event>()
            if (getMethod != null) {
                val values = fixedPropKeys.map { it to readProp(it) }
                synchronized(lock) { values.forEach { (k, v) -> update(known, k, v, now, "deger", out) } }
            }
            if (t0 >= nextScan && (visible || Recorder.recording)) {
                nextScan = t0 + SCAN_MS
                scan(now, out)
            }
            emit(out)
            if (Recorder.recording) Recorder.syncIfDue()
            val spent = SystemClock.elapsedRealtime() - t0
            runCatching { Thread.sleep((FAST_MS - spent).coerceAtLeast(50)) }
        }
    }

    private fun scan(now: Long, out: MutableList<Event>) {
        val t0 = SystemClock.elapsedRealtime()
        var proc: Process? = null
        val text = try {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            proc = p
            // A watchdog kills getprop if it hangs, so the loop never stalls.
            val dog = thread(isDaemon = true) { runCatching { Thread.sleep(3000); p.destroy() } }
            val s = p.inputStream.bufferedReader().readText()
            p.waitFor()
            dog.interrupt()
            s
        } catch (t: Throwable) {
            lastError = "getprop: ${t.message}"
            return
        } finally {
            runCatching { proc?.destroy() }
        }
        val parsed = HashMap<String, String>(512)
        for (line in text.lineSequence()) {
            val a = line.indexOf("]: [")
            if (!line.startsWith("[") || a < 0 || !line.endsWith("]")) continue
            parsed[line.substring(1, a)] = line.substring(a + 4, line.length - 1).trim()
        }
        synchronized(lock) {
            for ((k, v) in parsed) {
                if (k in fixedPropKeys) {
                    // Without SystemProperties the scan is the only reader of the known keys.
                    if (getMethod == null) update(known, k, v, now, "deger", out)
                    continue
                }
                // After the first scan, a key that appears is news too (e.g. one created on the first R).
                update(scanned, k, v, now, "bilinmeyen", out, firstIsChange = firstScanDone)
            }
            scanCount++
            scanKeys = parsed.size
            firstScanDone = true
        }
        scanMs = SystemClock.elapsedRealtime() - t0
    }

    /** Must hold [lock]. A key seen for the first time is a change only when [firstIsChange]. */
    private fun update(map: MutableMap<String, KeyState>, key: String, v: String, now: Long, kind: String, out: MutableList<Event>, firstIsChange: Boolean = false) {
        var s = map[key]
        if (s == null) {
            for (sig in signals) if (sig.discover?.containsMatchIn(key) == true) discovered.getOrPut(sig.id) { HashSet() }.add(key)
            if (!firstIsChange || v.isEmpty()) { map[key] = KeyState(v, now); return }
            s = KeyState("", now)
            map[key] = s
        }
        if (s.value == v) return
        val e = Event(now, if (s.changes == 0 && s.value.isEmpty() && firstIsChange) "yeni" else kind, key, s.value, v)
        s.value = v
        s.lastChangeAt = now
        s.changes++
        events.addLast(e)
        while (events.size > MAX_EVENTS) events.removeFirst()
        out.add(e)
    }

    /** For tests and previews only: feeds one value as if it had been read. */
    internal fun feed(key: String, value: String, unknown: Boolean = false, at: Long = System.currentTimeMillis()) {
        val out = ArrayList<Event>()
        synchronized(lock) { update(if (unknown) scanned else known, key, value, at, if (unknown) "bilinmeyen" else "deger", out) }
        emit(out)
    }

    private fun emit(out: List<Event>) {
        val l = listener ?: return
        out.forEach { runCatching { l(it) } }
    }

    private fun flatten(b: Bundle): Map<String, String> =
        b.keySet().sorted().associateWith { k ->
            runCatching {
                @Suppress("DEPRECATION")
                when (val v = b.get(k)) {
                    is IntArray -> v.joinToString(",")
                    is ByteArray -> v.joinToString(",")
                    is FloatArray -> v.joinToString(",")
                    is Array<*> -> v.joinToString(",")
                    else -> v.toString()
                }
            }.getOrDefault("?")
        }

    // ---------- reading the state ----------

    data class KeyView(val key: String, val label: String, val value: String, val firstAt: Long, val lastChangeAt: Long, val changes: Int)
    data class SignalView(val signal: Signal, val keys: List<KeyView>, val status: Status, val lastChangeAt: Long, val changes: Int)

    private fun view(key: String, label: String, s: KeyState?) =
        KeyView(key, label, s?.value ?: "", s?.firstAt ?: 0, s?.lastChangeAt ?: 0, s?.changes ?: 0)

    fun signal(sig: Signal): SignalView = synchronized(lock) {
        val keys = ArrayList<KeyView>()
        sig.fixed.forEach { (k, label) -> keys.add(view(k, label, known[k])) }
        // Discovered keys count only once they change, so an unrelated match cannot colour the card.
        val fixedKeys = sig.fixed.map { it.first }.toSet()
        discovered[sig.id].orEmpty()
            .filter { it !in fixedKeys }
            .mapNotNull { k -> (known[k] ?: scanned[k])?.takeIf { it.changes > 0 }?.let { k to it } }
            .sortedWith(compareBy<Pair<String, KeyState>> { if (it.second.changes > 30) 1 else 0 }.thenByDescending { it.second.lastChangeAt })
            .take(6)
            .forEach { (k, st) -> keys.add(view(k, shortKey(k), st)) }
        val changes = keys.sumOf { it.changes }
        val status = when {
            keys.any { it.changes > 0 } -> Status.LIVE
            keys.any { it.value.isNotEmpty() } -> Status.PRESENT
            else -> Status.NONE
        }
        SignalView(sig, keys, status, keys.maxOfOrNull { it.lastChangeAt } ?: 0, changes)
    }

    fun signals(): List<SignalView> = signals.map { signal(it) }

    /**
     * Properties outside the known list that changed at least once. Keys that change all the time
     * (clocks, counters) and system noise go to the end, so a gear or light key stays on top.
     */
    fun unknownChanges(limit: Int = 12): List<KeyView> = synchronized(lock) {
        scanned.entries.filter { it.value.changes > 0 }
            .sortedWith(compareBy<Map.Entry<String, KeyState>> { if (it.value.changes > 30 || noisy.containsMatchIn(it.key)) 1 else 0 }
                .thenByDescending { it.value.lastChangeAt })
            .take(limit)
            .map { view(it.key, it.key, it.value) }
    }

    /** All car-related properties the scan can see, for the report. */
    fun carProps(): List<KeyView> = synchronized(lock) {
        val re = Regex("cem|canbus|\\.can\\.|hcn|car|radar|gear|air", RegexOption.IGNORE_CASE)
        (known.entries.filter { !it.key.contains('/') } + scanned.entries.filter { re.containsMatchIn(it.key) })
            .sortedBy { it.key }
            .map { view(it.key, it.key, it.value) }
    }

    fun recentEvents(limit: Int = 40): List<Event> = synchronized(lock) { events.toList().takeLast(limit).reversed() }

    fun broadcasts(): List<Triple<String, Int, Long>> = synchronized(lock) {
        actions.map { Triple(it, broadcastCount[it] ?: 0, broadcastAt[it] ?: 0L) }
    }

    /** Every current value of the known keys, for the first rows of a recording. */
    fun snapshot(): List<Pair<String, String>> = synchronized(lock) {
        known.map { it.key to it.value.value } +
            scanned.filter { carRe.containsMatchIn(it.key) }.map { it.key to it.value.value }.sortedBy { it.first }
    }
    private val carRe = Regex("cem|canbus|hcn|radar|gear|air", RegexOption.IGNORE_CASE)

    fun shortKey(k: String) = k.removePrefix(P).removePrefix("persist.").removePrefix("vendor.")

    // ---------- text helpers ----------

    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        if (at == 0L) return "değişmedi"
        val s = (now - at) / 1000
        return when {
            s < 1 -> "şimdi"
            s < 60 -> "$s sn önce"
            s < 3600 -> "${s / 60} dk önce"
            else -> "${s / 3600} sa önce"
        }
    }

    fun statusText(s: Status) = when (s) {
        Status.NONE -> "veri yok"
        Status.PRESENT -> "değer var · değişmedi (eski olabilir)"
        Status.LIVE -> "değişti"
    }

    fun doorText(v: String) = when (v) {
        "1" -> "kapalı"
        "2" -> "AÇIK"
        "" -> "yok"
        else -> "ham $v"
    }

    // ---------- report and web ----------

    fun sections(): List<Section> {
        val now = System.currentTimeMillis()
        val live = signals().flatMap { sv ->
            listOf(Line("${sv.signal.title} (${sv.signal.item})", "${statusText(sv.status)} · ${sv.changes} değişim")) +
                sv.keys.map { Line("   ${it.label} · ${it.key}", "${it.value.ifEmpty { "(boş)" }} · ${ago(it.lastChangeAt, now)}") }
        }
        val diag = listOf(
            Line("Okuma yöntemi", method),
            Line("Tam tarama", "$scanCount kez · son taramada $scanKeys değişken · $scanMs ms"),
            Line("Hata", lastError ?: "yok"),
        ) + broadcasts().map { (a, n, at) -> Line("Yayın · $a", if (n == 0) "hiç gelmedi" else "$n kez · ${ago(at, now)}") }
        val unknown = unknownChanges(30).map { Line(it.key, "${it.value} · ${it.changes} değişim · ${ago(it.lastChangeAt, now)}") }
        return listOf(
            Section("Canlı araç verisi", live),
            Section("Canlı veri tanısı", diag),
            Section("Listede olmayan değişen değişkenler", unknown.ifEmpty { listOf(Line("—", "Henüz yok")) }),
            Section("Araçla ilgili tüm değişkenler", carProps().map { Line(it.key, it.value.ifEmpty { "(boş)" }) }.ifEmpty { listOf(Line("—", "Okunamadı")) }),
        )
    }

    /** Live sections, plus the CAN service check (it changes when the user runs the bind test). */
    fun attach(r: Report): Report = r.copy(sections = sections() + listOfNotNull(ServiceProbe.section()) + r.sections)

    fun json(): String {
        val now = System.currentTimeMillis()
        fun kv(k: KeyView) = JSONObject().put("key", k.key).put("label", k.label).put("value", k.value)
            .put("changes", k.changes).put("ago", ago(k.lastChangeAt, now))
        val o = JSONObject()
        o.put("now", now)
        o.put("signals", JSONArray().apply {
            signals().forEach { sv ->
                put(JSONObject().put("id", sv.signal.id).put("title", sv.signal.title).put("item", sv.signal.item)
                    .put("status", sv.status.name).put("statusText", statusText(sv.status))
                    .put("changes", sv.changes).put("ago", ago(sv.lastChangeAt, now))
                    .put("keys", JSONArray().apply { sv.keys.forEach { put(kv(it)) } }))
            }
        })
        o.put("unknown", JSONArray().apply { unknownChanges(20).forEach { put(kv(it)) } })
        o.put("events", JSONArray().apply {
            recentEvents(30).forEach { e ->
                put(JSONObject().put("at", e.at).put("kind", e.kind).put("key", e.key).put("old", e.old).put("new", e.new))
            }
        })
        o.put("diag", JSONObject().put("method", method).put("scans", scanCount).put("scanKeys", scanKeys)
            .put("scanMs", scanMs).put("error", lastError ?: ""))
        o.put("recording", Recorder.json())
        return o.toString()
    }
}
