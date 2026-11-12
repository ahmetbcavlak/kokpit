package dev.kokpit.kesif

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Window event log for com.hcn.autocan, to see when the door warning popup (CanDoorWindow) appears.
 *
 * It only listens. It never calls performAction, dispatchGesture, performGlobalAction or anything
 * that clicks, touches or types.
 *
 * What it sees vs what it keeps: Android delivers window events of every app to the service, and
 * for TYPE_WINDOWS_CHANGED the root node of every window is fetched to learn its package. Anything
 * not from com.hcn.autocan is dropped on the spot and never written anywhere.
 *
 * Why window content access is on (res/xml/pencere_gunlugu.xml): TYPE_WINDOWS_CHANGED is delivered
 * only with flagRetrieveInteractiveWindows, which needs canRetrieveWindowContent, and it arrives
 * without a package name, so the package filter cannot be in the XML.
 *
 * Fetching nodes is a blocking call into the other app, and the file is fsynced per event, so all
 * of that runs on a worker thread, never on the main thread the screen uses.
 */
class PencereGunlugu : AccessibilityService() {

    /** Window ids of com.hcn.autocan seen in the last TYPE_WINDOWS_CHANGED, to log opened/closed. */
    private val autocanWindows = HashMap<Int, String>()
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    override fun onServiceConnected() {
        WindowLog.connected = true
        post { WindowLog.append(this, System.currentTimeMillis(), "hizmet", "", "", "", "", "", "", "pencere günlüğü açıldı") }
    }

    /** The worker thread is made on first use (events can arrive before onServiceConnected). */
    private fun post(block: () -> Unit) {
        val h = handler ?: HandlerThread("kesif-pencere").also { it.start(); worker = it }
            .let { Handler(it.looper) }.also { handler = it }
        h.post {
            try { block() } catch (t: Throwable) { WindowLog.lastError = "${t.javaClass.simpleName}: ${t.message.orEmpty()}" }
        }
    }

    /** The event's own time (uptime clock) turned into wall-clock time. */
    private fun wallTime(e: AccessibilityEvent): Long =
        if (e.eventTime <= 0) System.currentTimeMillis()
        else System.currentTimeMillis() - (SystemClock.uptimeMillis() - e.eventTime).coerceAtLeast(0)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        try {
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (e.packageName?.toString() != WindowLog.PKG) return
                    // The event object is recycled after this call; copy what is needed first.
                    val at = wallTime(e)
                    val cls = e.className?.toString().orEmpty()
                    val text = e.text.joinToString(" | ")
                    val desc = e.contentDescription?.toString().orEmpty()
                    val win = e.windowId.toString()
                    post { WindowLog.append(this, at, "TYPE_WINDOW_STATE_CHANGED", WindowLog.PKG, cls, text, desc, win, "", "") }
                }
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    val at = wallTime(e)
                    post { windowsChanged(at) }
                }
            }
        } catch (t: Throwable) {
            WindowLog.lastError = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        }
    }

    /** Runs on the worker thread. */
    private fun windowsChanged(at: Long) {
        val now = HashMap<Int, String>()
        val details = HashMap<Int, List<String>>()
        val unknown = HashSet<Int>()
        for (w in windows.orEmpty()) {
            val root = w.root
            if (root == null) { unknown += w.id; continue } // briefly unavailable: keep its last state
            try {
                if (root.packageName?.toString() != WindowLog.PKG) continue
                now[w.id] = w.title?.toString().orEmpty()
                val (text, desc) = visibleText(root)
                details[w.id] = listOf(root.className?.toString().orEmpty(), text, desc, typeName(w.type))
            } finally {
                root.recycle()
            }
        }
        for (id in unknown) autocanWindows[id]?.let { now[id] = it }
        for ((id, title) in now) if (id !in autocanWindows && id in details) {
            val d = details.getValue(id)
            WindowLog.append(this, at, "TYPE_WINDOWS_CHANGED", WindowLog.PKG, d[0], d[1], d[2], id.toString(), title, "pencere açıldı · ${d[3]}")
        }
        for ((id, title) in autocanWindows) if (id !in now) {
            WindowLog.append(this, at, "TYPE_WINDOWS_CHANGED", WindowLog.PKG, "", "", "", id.toString(), title, "pencere kapandı")
        }
        autocanWindows.clear()
        autocanWindows.putAll(now)
    }

    /**
     * Text and content descriptions inside one com.hcn.autocan window: the root of an overlay is
     * usually a bare layout, the words sit in its children. Bounded (depth 6, 60 nodes); every
     * child node is recycled.
     */
    private fun visibleText(root: AccessibilityNodeInfo): Pair<String, String> {
        val texts = ArrayList<String>()
        val descs = ArrayList<String>()
        var budget = 60
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (budget-- <= 0) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { descs += it }
            if (depth >= 6) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                try { visit(c, depth + 1) } finally { c.recycle() }
            }
        }
        visit(root, 0)
        return texts.distinct().joinToString(" | ") to descs.distinct().joinToString(" | ")
    }

    private fun typeName(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "uygulama penceresi"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "sistem penceresi"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "klavye"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "erişilebilirlik katmanı"
        else -> "tür $t"
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        WindowLog.connected = false
        worker?.quitSafely()
        super.onDestroy()
    }
}

/** The CSV behind the service: kayit/pencere-olaylari.csv, plus a row in the live recording when one runs. */
object WindowLog {

    const val PKG = "com.hcn.autocan"
    private const val FILE = "pencere-olaylari.csv"
    private const val HEADER = "zaman,olay,paket,sinif,metin,aciklama,pencere_id,pencere_basligi,not"
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile var connected = false
    @Volatile var count = 0; private set
    @Volatile var lastAt = 0L; private set
    @Volatile var lastError: String? = null

    fun file(ctx: Context) = File(ctx.filesDir, "kayit/$FILE")

    fun append(ctx: Context, now: Long, type: String, pkg: String, cls: String, text: String, desc: String,
               windowId: String, title: String, note: String) {
        synchronized(lock) {
            val f = file(ctx).apply { parentFile?.mkdirs() }
            val isNew = !f.exists() || f.length() == 0L
            runCatching {
                FileOutputStream(f, true).use { out ->
                    if (isNew) out.write(("﻿" + HEADER + "\n").toByteArray(Charsets.UTF_8))
                    val line = listOf(stamp.format(Date(now)), type, pkg, cls, text, desc, windowId, title, note)
                        .joinToString(",") { csv(it) } + "\n"
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.fd.sync() // rare events; each one is forced to storage
                }
            }.onFailure { lastError = "yazılamadı: ${it.message}" }
        }
        if (type != "hizmet") synchronized(lock) { count++; lastAt = now }
        // Also into the live recording, so a door test (3.1) shows the popup next to the door value.
        if (type != "hizmet") Recorder.event(now, "pencere", "$type $cls", "", listOf(text, desc).filter { it.isNotEmpty() }.joinToString(" · "))
    }

    /** Whether the user switched the service on in Settings > Accessibility. */
    fun enabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, PencereGunlugu::class.java)
        val list = runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull().orEmpty()
        // Settings may hold the short form (pkg/.Class); compare as components, not as strings.
        return list.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }
    }

    fun statusText(ctx: Context): String = when {
        !enabled(ctx) -> "Kapalı · Ayarlar > Erişilebilirlik > Kokpit Keşif pencere günlüğü"
        !connected -> "Açık ama henüz bağlanmadı"
        count == 0 -> "Açık · com.hcn.autocan’dan henüz olay gelmedi"
        else -> "Açık · $count olay · son: ${LiveProbe.ago(lastAt)}"
    } + (lastError?.let { " · hata: $it" } ?: "")

    private fun csv(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
