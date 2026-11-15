package dev.kokpit.kesif

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * "Kapı özellikleri" (Keşif 0.8, K-116): the door values the vendor app writes, read every 250 ms.
 *
 *  - persist.vendor.cem.cardoor1–5 (1 closed, 2 open; order from the vendor code: front-left,
 *    front-right, rear-left, rear-right, boot; the visit confirms which is which)
 *  - can_ignore_doorinfo and close_avm_door_flag. Where they live is not known from here, so every
 *    likely place is read: system properties under the usual prefixes, any property whose name
 *    contains them (the 2-second getprop scan), and Settings.System / Global / Secure.
 *
 * Read-only: nothing is set, written or sent. Changes go to kayit/kapi-ozellikleri.csv with the time,
 * and into the main recording when one is running.
 */
object DoorProps {

    const val POLL_MS = 250L
    private const val P = "persist.vendor.cem."
    val flagNames = listOf("can_ignore_doorinfo", "close_avm_door_flag")
    private val prefixes = listOf("", P, "persist.vendor.", "persist.sys.", "persist.sys.cem.", "persist.", "sys.", "vendor.")

    class Row(val id: String, val label: String) {
        @Volatile var value = ""
        @Volatile var changes = 0
        @Volatile var lastChangeAt = 0L
        @Volatile var read = false
    }

    data class Change(val at: Long, val id: String, val label: String, val old: String, val new: String)

    val rows: List<Row> = listOf(
        Row("${P}cardoor1", "Kapı 1 (sol ön?)"), Row("${P}cardoor2", "Kapı 2 (sağ ön?)"),
        Row("${P}cardoor3", "Kapı 3 (sol arka?)"), Row("${P}cardoor4", "Kapı 4 (sağ arka?)"),
        Row("${P}cardoor5", "Kapı 5 (bagaj?)"),
    ) + flagNames.map { Row(it, it) }

    val log = CsvLog("kapi-ozellikleri.csv", "zaman,saniye,tur,anahtar,ad,eski,yeni,not")
    private val lock = Any()
    private val recent = ArrayDeque<Change>()
    @Volatile var recording = false; private set
    @Volatile private var startedAt = 0L
    @Volatile private var generation = 0
    @Volatile var lastMark: String? = null; private set
    @Volatile var reads = 0; private set

    /** Where a flag was found: "Settings.System=1 · persist.vendor.cem.x=0", or "" when nowhere. */
    fun readFlag(ctx: Context, name: String): String {
        val found = LinkedHashMap<String, String>()
        for (p in prefixes) {
            val v = LiveProbe.prop(p + name)
            if (v.isNotEmpty()) found[p + name] = v
        }
        // Names the prefix list did not guess, from the full property scan.
        for ((k, v) in LiveProbe.scannedContaining(name)) if (v.isNotEmpty() && k !in found) found[k] = v
        val cr = ctx.contentResolver
        runCatching { Settings.System.getString(cr, name) }.getOrNull()?.let { found["Settings.System"] = it }
        runCatching { Settings.Global.getString(cr, name) }.getOrNull()?.let { found["Settings.Global"] = it }
        runCatching { Settings.Secure.getString(cr, name) }.getOrNull()?.let { found["Settings.Secure"] = it }
        return found.entries.joinToString(" · ") { "${it.key}=${it.value}" }
    }

    fun readValue(ctx: Context, row: Row): String =
        if (row.id in flagNames) readFlag(ctx, row.id)
        else LiveProbe.prop(row.id).ifEmpty { LiveProbe.scannedContaining(row.id).firstOrNull { it.first == row.id }?.second.orEmpty() }

    /** One pass over every row; changes are returned (and kept for the screen). */
    fun poll(ctx: Context, now: Long = System.currentTimeMillis()): List<Change> {
        val out = ArrayList<Change>()
        for (r in rows) {
            val v = readValue(ctx, r)
            synchronized(lock) {
                if (!r.read) { r.read = true; r.value = v; return@synchronized }
                if (v == r.value) return@synchronized
                val c = Change(now, r.id, r.label, r.value, v)
                r.value = v; r.changes++; r.lastChangeAt = now
                recent.addLast(c); while (recent.size > 40) recent.removeFirst()
                out.add(c)
            }
        }
        reads++
        return out
    }

    fun recentChanges(): List<Change> = synchronized(lock) { recent.toList().reversed() }

    /** Starts reading and writing; the page calls it when it opens. Survives leaving the page. */
    fun startRecording(ctx: Context) {
        if (recording) return
        val app = ctx.applicationContext
        recording = true
        startedAt = System.currentTimeMillis()
        val gen = ++generation
        thread(name = "kesif-kapi", isDaemon = true) {
            poll(app) // first values
            val now = System.currentTimeMillis()
            log.appendAll(app, listOf(now to listOf("0.00", "oturum", "", "", "", "", "kayıt başladı · Keşif ${BuildConfigCompat.version}")) +
                rows.map { now to listOf("0.00", "ilk", it.id, it.label, "", it.value.ifEmpty { "(yok)" }, "") })
            while (recording && gen == generation) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val changes = poll(app)
                if (changes.isNotEmpty()) {
                    log.appendAll(app, changes.map { c -> c.at to listOf(sec(c.at), "deger", c.id, c.label, c.old.ifEmpty { "(yok)" }, c.new.ifEmpty { "(yok)" }, "") })
                    changes.forEach { Recorder.event(it.at, "kapi-ozellik", it.id, it.old, it.new) }
                }
                val spent = android.os.SystemClock.elapsedRealtime() - t0
                runCatching { Thread.sleep((POLL_MS - spent).coerceAtLeast(20)) }
            }
        }
    }

    fun stopRecording(ctx: Context) {
        if (!recording) return
        recording = false
        generation++
        val now = System.currentTimeMillis()
        log.append(ctx.applicationContext, now, listOf(sec(now), "oturum", "", "", "", "", "kayıt durdu"))
    }

    fun mark(ctx: Context, note: String) {
        if (!recording) startRecording(ctx)
        lastMark = note
        val now = System.currentTimeMillis()
        // Off the main thread: the write is synced to storage.
        thread(isDaemon = true) { log.append(ctx.applicationContext, now, listOf(sec(now), "isaret", "", "", "", "", note)) }
        Recorder.event(now, "isaret", "", "", note)
    }

    private fun sec(at: Long) = "%.2f".format(java.util.Locale.US, (at - startedAt).coerceAtLeast(0) / 1000.0)

    fun doorText(v: String) = when (v) { "1" -> "kapalı"; "2" -> "AÇIK"; "" -> "yok"; else -> "ham $v" }

    fun sectionLines(): List<Line> = rows.map { Line(it.label, it.value.ifEmpty { "(yok)" } + " · ${it.changes} değişim") }
}

class DoorPage(private val a: Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val table = ui.label("", 15f, Palette.text, mono = true)
    private val events = ui.label("", 13f, Palette.muted, mono = true)
    private val status = ui.label("", 14f, Palette.muted)
    private val recButton = ui.button("Kaydı durdur", primary = true) { toggle() }

    init {
        val (r, content) = ui.frame("Kapı özellikleri", "Yalnız okur · 250 ms · kapi-ozellikleri.csv", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("Sıra: her kapıyı aç, 3 sn bekle, kapat. Değer 1 kapalı, 2 açık. Ayarı değiştirmeden önce ve sonra işarete dokun.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                val marks = listOf("Ayar Açık\nturu", "Ayar Kapalı\nturu", "Serbest\nişaret")
                marks.forEachIndexed { i, t ->
                    addView(ui.button(t, primary = false, size = 15f) { mark(t.replace('\n', ' ')) },
                        LinearLayout.LayoutParams(0, ui.dp(60), 1f).apply { if (i < marks.size - 1) rightMargin = ui.dp(8) })
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(60)))
            addView(status.apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
            addView(recButton, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(56)))
        }
        ui.card(content) {
            addView(ui.label("Canlı değerler", 17f, Palette.accent, bold = true))
            addView(table.apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        ui.card(content) {
            addView(ui.label("Son değişiklikler", 17f, Palette.accent, bold = true))
            addView(events.apply { setPadding(0, ui.dp(8), 0, 0) })
        }
    }

    override fun onShow() { DoorProps.startRecording(a) }

    private fun toggle() {
        if (DoorProps.recording) DoorProps.stopRecording(a) else DoorProps.startRecording(a)
        tick()
    }

    private fun mark(note: String) {
        DoorProps.mark(a, note)
        Toast.makeText(a, "$note işaretlendi", Toast.LENGTH_SHORT).show()
    }

    override fun tick() {
        val now = System.currentTimeMillis()
        table.text = DoorProps.rows.joinToString("\n\n") { r ->
            val v = r.value
            val shown = if (r.id in DoorProps.flagNames) v.ifEmpty { "hiçbir yerde bulunamadı" } else "${v.ifEmpty { "—" }}  ${DoorProps.doorText(v)}"
            "${r.label}\n  $shown" + if (r.changes > 0) "\n  ${r.changes} değişim · ${LiveProbe.ago(r.lastChangeAt, now)}" else ""
        }
        events.text = DoorProps.recentChanges().take(15).joinToString("\n") { c ->
            "${DoorProps.log.time(c.at).substring(11)}  ${c.label}: ${c.old.ifEmpty { "(yok)" }} → ${c.new.ifEmpty { "(yok)" }}"
        }.ifEmpty { "Henüz değişiklik yok." }
        recButton.text = if (DoorProps.recording) "Kaydı durdur" else "Kaydı başlat"
        status.text = (if (DoorProps.recording) "● Kayıt açık" else "Kayıt kapalı") +
            " · ${DoorProps.reads} okuma" + (DoorProps.lastMark?.let { " · son işaret: $it" } ?: "") +
            (if (!LiveProbe.canReadProps) " · özellikler yalnız 2 sn’lik taramadan" else "") +
            (DoorProps.log.lastError?.let { " · yazma hatası: $it" } ?: "")
    }
}
