package dev.kokpit.kesif

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI dump of com.hcn.autocan windows (the door warning dialog) into kayit/arayuz-dokumu.csv:
 * every component's viewIdResourceName, className, text, contentDescription, isVisibleToUser and
 * boundsInScreen, with the likely close button marked. Reading only; runs on the service's worker.
 */
object UiDump {

    private const val FILE = "arayuz-dokumu.csv"
    private const val HEADER = "zaman,dokum_no,sebep,pencere_id,derinlik,view_id,sinif,metin,aciklama,gorunur,tiklanabilir,sol,ust,sag,alt,kapatma_adayi"
    private const val MAX_NODES = 300
    private const val MAX_DEPTH = 15
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    @Volatile private var seq = 0

    fun file(ctx: Context) = File(ctx.filesDir, "kayit/$FILE")

    /** The close button the dump found: its id, label and centre on screen. */
    data class Candidate(val viewId: String, val label: String, val bounds: Rect, val score: Int) {
        val centre get() = "${bounds.centerX()},${bounds.centerY()}"
    }

    /** Whole words of a resource id only: iv_close, btn_cancel, ok; not iv_background or ok_confirm_climate. */
    private val closeId = Regex("(^|[_.])(close|cancel|dismiss|kapat|iptal|ok|back|exit)($|[_.])", RegexOption.IGNORE_CASE)
    private val closeText = Regex("^\\s*(kapat|tamam|iptal|ok|close|cancel|×|x|✕)\\s*$", RegexOption.IGNORE_CASE)

    /** Class names reported by TYPE_WINDOW_STATE_CHANGED, per window id (e.g. ...CanDoorWindow). */
    val eventClass = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Class or id names: CanDoorWindow, door_dialog; never OutdoorTemp or IndoorTemp. */
    private val doorName = Regex("(?<!Out|In|out|in)(Door(?![a-z])|(^|[_./])door($|[_./]))")
    /** Visible words, whole words only: "Sol ön kapı açık", "kapısı", "Door open"; not "outdoor". */
    private val doorText = Regex("(?<![\\p{L}])(door|doors|kap[ıi](s[ıi]|y[ıi]|lar[ıi]?|da|dan|n[ıi]n)?|bagaj|trunk|tailgate)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    internal fun doorish(s: CharSequence?, name: Boolean) = s != null && (if (name) doorName else doorText).containsMatchIn(s)

    /**
     * Whether a com.hcn.autocan window is the door warning, not e.g. the climate overlay: its event
     * class (CanDoorWindow), its title or its visible text must speak of doors. Anything else is
     * never acted on.
     */
    fun isDoorWindow(svc: AccessibilityService, w: AccessibilityWindowInfo): Boolean {
        if (doorish(eventClass[w.id], name = true)) return true
        if (doorish(w.title, name = false) || doorish(w.title, name = true)) return true
        val root = w.root ?: return false
        return try {
            if (root.packageName?.toString() != WindowLog.PKG) return false
            var found = false
            var budget = 80
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (found || budget-- <= 0) return
                if (doorish(n.text, false) || doorish(n.contentDescription, false) ||
                    doorish(n.viewIdResourceName?.substringAfter(":id/"), true) || doorish(n.className, true)) { found = true; return }
                if (depth >= 8) return
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    try { visit(c, depth + 1) } finally { c.recycle() }
                    if (found) return
                }
            }
            visit(root, 0)
            found
        } finally { root.recycle() }
    }

    /** Id of the com.hcn.autocan window in front (active, else focused, else top layer), or null. */
    fun activeAutocanWindow(svc: AccessibilityService): Int? = autocanWindows(svc).firstOrNull()?.id

    /** com.hcn.autocan windows on screen, the one in front first. */
    fun autocanWindows(svc: AccessibilityService): List<AccessibilityWindowInfo> =
        svc.windows.orEmpty().filter { w ->
            val r = w.root ?: return@filter false
            try { r.packageName?.toString() == WindowLog.PKG } finally { r.recycle() }
        }.sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }.thenByDescending { it.isFocused }.thenByDescending { it.layer })

    /**
     * Writes one window's tree. Returns the best close-button candidate, or null.
     * Every node obtained here is recycled before returning.
     */
    fun dump(svc: AccessibilityService, windowId: Int, reason: String): Candidate? {
        val w = svc.windows.orEmpty().firstOrNull { it.id == windowId } ?: return null
        val root = w.root ?: return null
        val no = ++seq
        val at = System.currentTimeMillis()
        val windowBounds = Rect().also { w.getBoundsInScreen(it) }
        val rows = ArrayList<List<String>>()
        var best: Candidate? = null
        var count = 0
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (count++ >= MAX_NODES) return
            val b = Rect().also { n.getBoundsInScreen(it) }
            val id = n.viewIdResourceName.orEmpty()
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            val score = score(n, id, text, desc, b, windowBounds)
            if (score > 0 && (best == null || score > best!!.score)) best = Candidate(id, text.ifEmpty { desc }, b, score)
            rows += listOf(stamp.format(Date(at)), no.toString(), reason, windowId.toString(), depth.toString(), id,
                n.className?.toString().orEmpty(), text, desc, yesNo(n.isVisibleToUser), yesNo(n.isClickable),
                b.left.toString(), b.top.toString(), b.right.toString(), b.bottom.toString(), if (score > 0) "aday ($score)" else "")
            if (depth >= MAX_DEPTH) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                try { visit(c, depth + 1) } finally { c.recycle() }
            }
        }
        try { visit(root, 0) } finally { root.recycle() }
        best?.let { c ->
            rows += listOf(stamp.format(Date(at)), no.toString(), "kapatma düğmesi", windowId.toString(), "", c.viewId, "", c.label, "",
                "", "", c.bounds.left.toString(), c.bounds.top.toString(), c.bounds.right.toString(), c.bounds.bottom.toString(),
                "seçilen · merkez ${c.centre}")
        }
        write(svc, rows)
        return best
    }

    /**
     * How much a node looks like the dialog's close control: an id such as btn_close (3), a label such as
     * "Tamam" or "×" (2), or a small clickable image in the window's top-right corner (1). Only
     * clickable, visible nodes count.
     */
    private fun score(n: AccessibilityNodeInfo, id: String, text: String, desc: String, b: Rect, win: Rect): Int {
        if (!n.isClickable || !n.isVisibleToUser) return 0
        if (closeId.containsMatchIn(id.substringAfter(":id/"))) return 3
        if (closeText.matches(text) || closeText.matches(desc)) return 2
        val cls = n.className?.toString().orEmpty()
        val topRight = b.centerX() > win.centerX() && b.centerY() < win.top + win.height() / 3
        if ((cls.endsWith("ImageView") || cls.endsWith("ImageButton")) && topRight && b.width() < win.width() / 4) return 1
        return 0
    }

    private fun yesNo(b: Boolean) = if (b) "evet" else "hayır"

    private fun write(ctx: Context, rows: List<List<String>>) {
        synchronized(lock) {
            val f = file(ctx).apply { parentFile?.mkdirs() }
            val isNew = !f.exists() || f.length() == 0L
            runCatching {
                FileOutputStream(f, true).use { out ->
                    val sb = StringBuilder()
                    if (isNew) sb.append("﻿").append(HEADER).append('\n')
                    rows.forEach { r -> sb.append(r.joinToString(",") { csv(it) }).append('\n') }
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            }.onFailure { WindowLog.lastError = "arayüz dökümü yazılamadı: ${it.message}" }
        }
    }

    private fun csv(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/**
 * "Kapı penceresini kapatmayı dene": one close attempt on the com.hcn.autocan dialog, and how long
 * the dialog took to leave the screen.
 *
 * Limits (agreed for 0.7): only after the user taps the button; only on the door warning window of
 * com.hcn.autocan ([UiDump.isDoorWindow]; the climate overlay is never touched); one attempt per tap.
 * First choice is ACTION_CLICK on the close control found by the UI dump; if there is none,
 * GLOBAL_ACTION_BACK, but only after a fresh check that the door window still exists and is focused,
 * so back can never close another app such as CarPlay. If no door warning is on screen when tapped,
 * the test waits up to [ARM_MS] for one (time to open a door) and then runs once.
 */
object CloseTest {

    const val ARM_MS = 15_000L
    private const val WAIT_CLOSE_MS = 5_000L
    private const val POLL_MS = 50L

    @Volatile private var armedUntil = 0L
    @Volatile var running = false; private set
    /** Set by a tap until its attempt (or its wait) is over: a second tap meanwhile does nothing. */
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    val busy: Boolean get() = pending.get()
    @Volatile var lastResult: String? = null; private set

    val armed: Boolean get() = SystemClock.uptimeMillis() < armedUntil

    /** True once, if a tap armed the test and the time has not run out. */
    fun consumeArm(): Boolean {
        if (!armed) return false
        armedUntil = 0L
        return true
    }

    /** Called by the button. Returns a line for the screen. */
    fun request(): String {
        val svc = PencereGunlugu.instance ?: return "Pencere günlüğü kapalı; önce Erişilebilirlik’ten aç.".also { lastResult = it }
        if (!pending.compareAndSet(false, true)) return "Deneme sürüyor…"
        svc.post {
          try {
            if (UiDump.autocanWindows(svc).any { UiDump.isDoorWindow(svc, it) }) run(svc)
            else {
                val until = SystemClock.uptimeMillis() + ARM_MS
                armedUntil = until
                lastResult = "Kapı penceresi bekleniyor (${ARM_MS / 1000} sn): bir kapıyı aç."
                // Nothing appeared in time: disarm and say so (only if this arm is still the current one).
                svc.postDelayed(ARM_MS + 200) {
                    if (armedUntil == until) {
                        armedUntil = 0L; pending.set(false)
                        lastResult = "${ARM_MS / 1000} sn içinde kapı uyarısı çıkmadı; hiçbir şey yapılmadı."
                    }
                }
            }
          } catch (t: Throwable) {
            armedUntil = 0L; pending.set(false)
            lastResult = "hata: ${t.javaClass.simpleName}; hiçbir şey yapılmadı"
          }
        }
        return "Hazırlanıyor…"
    }

    /** Runs on the service's worker thread. */
    fun run(svc: PencereGunlugu) {
        if (running) return
        running = true
        try {
            // Only the door warning; the climate overlay or any other autocan window is never touched.
            val w = UiDump.autocanWindows(svc).firstOrNull { UiDump.isDoorWindow(svc, it) }
            if (w == null) { finish(svc, "atlandı", "", "", "", "", "kapı uyarısı penceresi yok ya da tanınmadı; hiçbir şey yapılmadı"); return }
            val windowId = w.id
            val candidate = UiDump.dump(svc, windowId, "kapatma testi öncesi")
            val t0 = SystemClock.uptimeMillis()
            var method: String
            var ok: Boolean
            if (candidate != null) {
                method = "ACTION_CLICK"
                ok = when (clickAt(svc, windowId, candidate)) {
                    Click.DONE -> true
                    Click.REFUSED -> false
                    Click.NOT_FOUND -> {
                        finish(svc, "ACTION_CLICK", windowId.toString(), candidate.viewId, candidate.centre, "", "düğme yeniden bulunamadı (pencere değişmiş ya da kapanmış); hiçbir şey yapılmadı")
                        return
                    }
                }
            } else if (doorWindowFocused(svc, windowId)) {
                // Checked again right before acting: the back key goes to the focused window, and
                // it must still be this door warning, never CarPlay or anything else.
                method = "GLOBAL_ACTION_BACK"
                ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } else {
                finish(svc, "yok", windowId.toString(), "", "", "", "kapatma düğmesi bulunamadı ve kapı uyarısı odakta değil; geri tuşu kullanılmadı")
                return
            }
            // How long until the window leaves the screen.
            var closedAfter: Long? = null
            while (SystemClock.uptimeMillis() - t0 < WAIT_CLOSE_MS) {
                if (svc.windows.orEmpty().none { it.id == windowId }) { closedAfter = SystemClock.uptimeMillis() - t0; break }
                Thread.sleep(POLL_MS)
            }
            finish(svc, method, windowId.toString(), candidate?.viewId.orEmpty(), candidate?.centre.orEmpty(),
                closedAfter?.toString().orEmpty(),
                (if (ok) "işlem kabul edildi" else "işlem reddedildi") + " · " +
                    (closedAfter?.let { "pencere $it ms sonra kapandı" } ?: "pencere ${WAIT_CLOSE_MS} ms içinde kapanmadı"))
        } catch (t: Throwable) {
            runCatching { finish(svc, "hata", "", "", "", "", "${t.javaClass.simpleName}: ${t.message.orEmpty()}") }
        } finally {
            running = false
            pending.set(false)
        }
    }

    /** The service stopped: nothing may stay armed or pending. */
    fun reset() { armedUntil = 0L; pending.set(false); running = false }

    /** Fresh window list: this window still there, still com.hcn.autocan, and focused. */
    private fun doorWindowFocused(svc: AccessibilityService, windowId: Int): Boolean {
        val w = svc.windows.orEmpty().firstOrNull { it.id == windowId } ?: return false
        if (!w.isFocused) return false
        val r = w.root ?: return false
        return try { r.packageName?.toString() == WindowLog.PKG } finally { r.recycle() }
    }

    private enum class Click { DONE, REFUSED, NOT_FOUND }

    /** Finds the candidate again in a fresh tree (by id and bounds) and clicks it once. */
    private fun clickAt(svc: AccessibilityService, windowId: Int, c: UiDump.Candidate): Click {
        val root = svc.windows.orEmpty().firstOrNull { it.id == windowId }?.root ?: return Click.NOT_FOUND
        var done = false
        var clicked = false
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (done || depth > 15) return
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (n.isClickable && b == c.bounds && n.viewIdResourceName.orEmpty() == c.viewId) {
                clicked = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                done = true
                return
            }
            for (i in 0 until n.childCount) {
                val ch = n.getChild(i) ?: continue
                try { visit(ch, depth + 1) } finally { ch.recycle() }
                if (done) return
            }
        }
        try { visit(root, 0) } finally { root.recycle() }
        return when { !done -> Click.NOT_FOUND; clicked -> Click.DONE; else -> Click.REFUSED }
    }

    /** One row in pencere-olaylari.csv: sinif = method, metin = close control id, aciklama = its centre. */
    private fun finish(svc: PencereGunlugu, method: String, windowId: String, viewId: String, centre: String, ms: String, note: String) {
        val now = System.currentTimeMillis()
        WindowLog.append(svc, now, "KAPATMA_TESTI", WindowLog.PKG, method, viewId, centre, windowId, "",
            (if (ms.isNotEmpty()) "kapanma_ms=$ms · " else "") + note)
        lastResult = "$method · " + (if (viewId.isNotEmpty()) "$viewId @ $centre · " else "") + note
    }
}
