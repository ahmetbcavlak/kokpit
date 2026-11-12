package dev.kokpit.kesif

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Writes every change the probe sees to a CSV file in the app's own storage.
 *
 * The "recording" flag survives a restart: if the ignition cuts power, the next start continues
 * the same file (when it was written to in the last [RESUME_WINDOW_MS]; otherwise a new file).
 * Data is forced to storage at least once a second and at every marker, so a power cut loses
 * at most about a second. Nothing is written outside the app's folder.
 */
object Recorder {

    private const val PREFS = "kayit"
    private const val RESUME_WINDOW_MS = 3 * 60 * 60 * 1000L
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val SYNC_MS = 1000L
    private const val HEADER = "zaman,saniye,tur,anahtar,eski,yeni,not"

    private val lock = Any()
    private lateinit var dir: File
    private var out: FileOutputStream? = null
    private var lastSync = 0L
    @Volatile var file: File? = null; private set
    @Volatile var rows = 0; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var lastMark: String? = null; private set
    @Volatile var lastError: String? = null; private set
    @Volatile private var pendingResume: Runnable? = null
    @Volatile private var syncNow = false
    @Volatile var approxRows = false; private set
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val nameFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile var recording = false; private set

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        dir = File(ctx.filesDir, "kayit").apply { mkdirs() }
        LiveProbe.listener = { e -> write(e.at, e.kind, e.key, e.old, e.new, "") }
        if (recording) return
        val prefs = prefs() ?: return
        val name = prefs.getString("file", null) ?: return
        // Wait for the first readings, so the restart rows carry real values.
        val r = Runnable { pendingResume = null; thread { resume(name, prefs.getLong("startedAt", 0L)) } }
        pendingResume = r
        main.postDelayed(r, 2500)
    }

    private fun resume(name: String, started: Long) {
        if (recording) return
        val f = File(dir, name)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < RESUME_WINDOW_MS && f.length() < MAX_BYTES
        if (fresh) open(f, if (started > 0) started else System.currentTimeMillis(), resumed = true)
        // A recording left on from hours ago is not continued; it ends instead of following every drive.
        else prefs()?.edit()?.remove("file")?.remove("startedAt")?.commit()
    }

    fun start() {
        // A marker tapped while the resume is still pending continues the old file instead of starting a new one.
        pendingResume?.let { r ->
            main.removeCallbacks(r); pendingResume = null
            val name = prefs()?.getString("file", null)
            if (name != null) { resume(name, prefs()?.getLong("startedAt", 0L) ?: 0L); if (recording) return }
        }
        if (recording) return
        val now = System.currentTimeMillis()
        open(File(dir, "oturum-${nameFmt.format(Date(now))}.csv"), now, resumed = false)
    }

    private fun open(f: File, started: Long, resumed: Boolean) {
        synchronized(lock) {
            if (recording) return
            val isNew = !f.exists() || f.length() == 0L
            // A power cut can leave a half line; start on a fresh line so rows never merge.
            val needsNewline = !isNew && runCatching {
                RandomAccessFile(f, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
            }.getOrDefault(false)
            out = runCatching { FileOutputStream(f, true) }.getOrElse {
                lastError = "Kayıt dosyası açılamadı: ${it.message}"
                return
            }
            file = f
            startedAt = started
            rows = if (isNew) 0 else (f.length() / 80).toInt() // rough; counting lines would block writers
            approxRows = !isNew
            recording = true
            lastError = null
            if (isNew) raw("\uFEFF" + HEADER) // BOM so Excel shows ı, ş, ğ correctly
            if (needsNewline) raw("")
            prefs()?.edit()?.putString("file", f.name)?.putLong("startedAt", started)?.commit()
        }
        val now = System.currentTimeMillis()
        write(now, "oturum", "", "", "", if (resumed) "kayıt yeniden açıldı (uygulama yeniden başladı)" else "kayıt başladı · Keşif ${BuildConfigCompat.version}")
        // First rows: what every known key reads right now, so later changes have a starting point.
        LiveProbe.snapshot().forEach { (k, v) -> write(now, "ilk", k, "", v, "") }
        syncNow = true
    }

    fun stop() {
        write(System.currentTimeMillis(), "oturum", "", "", "", "kayıt durdu")
        synchronized(lock) {
            runCatching { out?.flush(); out?.close() }
            out = null
            recording = false
            prefs()?.edit()?.remove("file")?.remove("startedAt")?.commit()
        }
    }

    /** Closes the file without ending the recording; the next [init] continues it. */
    fun suspend() {
        pendingResume?.let { main.removeCallbacks(it); pendingResume = null }
        if (!recording) return
        write(System.currentTimeMillis(), "oturum", "", "", "", "uygulama kapandı")
        synchronized(lock) {
            runCatching { out?.flush(); out?.close() }
            out = null
            recording = false
        }
    }

    /** A marker the user taps on the screen, e.g. "3.1". Starts a recording if none is running. */
    fun mark(item: String) {
        if (!recording) start()
        lastMark = item
        write(System.currentTimeMillis(), "isaret", "", "", "", item)
        syncNow = true
    }

    private fun write(at: Long, kind: String, key: String, old: String, new: String, note: String) {
        synchronized(lock) {
            if (out == null) return
            val sec = "%.2f".format(Locale.US, (at - startedAt) / 1000.0)
            raw(listOf(stamp.format(Date(at)), sec, kind, key, old, new, note).joinToString(",") { csv(it) })
            rows++
            if ((file?.length() ?: 0) > MAX_BYTES) rotateLocked(at)
        }
    }

    /** Must hold [lock]. A very long recording continues in a new file, swapped in place so nothing is lost. */
    private fun rotateLocked(at: Long) {
        val next = File(dir, "oturum-${nameFmt.format(Date(at))}-devam.csv")
        val o = runCatching { FileOutputStream(next, true) }.getOrNull() ?: return
        runCatching { out?.flush(); out?.close() }
        out = o
        file = next
        rows = 0
        approxRows = false
        raw("\uFEFF" + HEADER)
        raw(listOf(stamp.format(Date(at)), "%.2f".format(Locale.US, (at - startedAt) / 1000.0), "oturum", "", "", "", "önceki dosya doldu, kayıt burada sürüyor").joinToString(","))
        prefs()?.edit()?.putString("file", next.name)?.apply()
    }

    /**
     * Called from the probe's own thread, never the main thread: forces the file to storage once a
     * second, or right away after a marker. The slow fsync runs outside the lock so writers never wait on it.
     */
    fun syncIfDue() {
        val now = System.currentTimeMillis()
        if (!syncNow && now - lastSync < SYNC_MS) return
        val o = synchronized(lock) { out?.also { runCatching { it.flush() } } } ?: return
        syncNow = false
        lastSync = now
        runCatching { o.fd.sync() } // the file may close meanwhile; then there is nothing left to sync
    }

    /** Must hold [lock]. */
    private fun raw(line: String) {
        val o = out ?: return
        runCatching { o.write((line + "\n").toByteArray(Charsets.UTF_8)) }
            .onFailure { lastError = "Kayıt yazılamadı: ${it.message}" }
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---------- files for the web server ----------

    fun files(): List<File> =
        if (!::dir.isInitialized) emptyList()
        else (dir.listFiles { f -> f.isFile && f.name.startsWith("oturum-") && f.name.endsWith(".csv") }?.toList() ?: emptyList())
            .sortedByDescending { it.name }

    /** Only names from [files] are accepted, never a path from the request. */
    fun byName(name: String): File? = files().firstOrNull { it.name == name }

    fun latest(): File? = file?.takeIf { it.exists() } ?: files().firstOrNull()

    fun duration(now: Long = System.currentTimeMillis()): String {
        if (!recording) return "—"
        val s = ((now - startedAt) / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    fun rowsText() = (if (approxRows) "~" else "") + rows

    fun json(): JSONObject = JSONObject().put("on", recording).put("file", file?.name ?: "")
        .put("rows", rowsText()).put("duration", duration()).put("mark", lastMark ?: "").put("error", lastError ?: "")
        .put("files", JSONArray().apply { files().take(20).forEach { put(JSONObject().put("name", it.name).put("bytes", it.length())) } })

    /** The last [limit] rows of the CSV as JSON, for /kayit.json. Reads only the file's tail. */
    fun asJson(f: File, limit: Int = 2000): String {
        val tailBytes = 400L * 1024
        val text = RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val start = (len - tailBytes).coerceAtLeast(0)
            r.seek(start)
            val b = ByteArray((len - start).toInt())
            r.readFully(b)
            String(b, Charsets.UTF_8).let { if (start > 0) it.substringAfter('\n') else it }
        }
        val head = HEADER.split(",")
        val out = JSONArray()
        text.lineSequence().filter { it.isNotBlank() && !it.removePrefix("\uFEFF").startsWith("zaman,") }
            .toList().takeLast(limit)
            .forEach { line ->
                val cells = parseCsv(line)
                out.put(JSONObject().apply { head.forEachIndexed { i, h -> put(h, cells.getOrElse(i) { "" }) } })
            }
        return out.toString()
    }

    private fun parseCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}

/** BuildConfig is turned off by default in AGP 8; read the version from the package instead. */
object BuildConfigCompat {
    @Volatile var version = "?"
}
