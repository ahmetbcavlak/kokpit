package dev.kokpit.kesif

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * "Klima +0,5° dene" (K-69: the one exception to K-29; climate only, only on a tap).
 *
 * Guard rails:
 *  - The method comes from the interface listing done on this unit: exactly one parameterless method
 *    whose name says "temperature up" (e.g. tempUp, addTemp). Several or none: the button stays off.
 *  - The user sees the exact method and confirms before anything is sent.
 *  - The service is bound (no BIND_AUTO_CREATE) and asked for its interface name again; the call is
 *    made only if that name is the interface that holds the method. A service that hands out the
 *    climate interface some other way gets nothing.
 *  - Exactly one transaction, written the way AIDL writes a parameterless call (interface token,
 *    no arguments), with the code read from the vendor's Stub. No vendor code runs in this app.
 *  - Then the ACTION_AIR_INFO temperature is watched for up to 3 s, the result is written to
 *    kayit/klima-komutu.csv and the live recording, and the service is unbound.
 */
object AirCommand {

    data class Candidate(val descriptor: String, val iface: String, val method: String, val signature: String, val code: Int)

    private const val WAIT_CONNECT_MS = 3000L
    private const val WAIT_VERIFY_MS = 3000L
    private const val TEMP_KEY = "${LiveProbe.AIR}/KEY_VALUE_LEFT_TEMP"

    @Volatile var candidate: Candidate? = null; private set
    @Volatile private var all: List<Candidate> = emptyList()
    @Volatile private var serviceDescriptor: String? = null
    private val busy = AtomicBoolean(false)
    val running: Boolean get() = busy.get()
    @Volatile var lastResult: String? = null; private set

    /** camelCase and snake_case split into lower-case words: setLeftTempUp → set, left, temp, up. */
    fun words(name: String): List<String> =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.map { it.lowercase(Locale.US) }

    private val tempWords = setOf("temp", "temperature", "sicaklik")
    private val upWords = setOf("up", "inc", "increase", "add", "plus")
    /** Any of these and it is something else: fan, mode, power, the other side, a getter, a callback… */
    private val rejectWords = setOf("fan", "wind", "mode", "power", "unit", "right", "passenger", "dual", "down", "dec",
        "decrease", "minus", "sub", "auto", "sync", "all", "max", "min", "on", "is", "get", "has", "update",
        "upgrade", "upload", "setup", "popup", "backup", "since", "boot", "supported", "level", "speed")
    private val leftWords = setOf("left", "driver", "l")

    /** The only other words allowed in the name; anything else (rear, seat, offset, limit…) means no. */
    private val allowedWords = setOf("set", "do", "left", "driver", "l", "air", "ac", "ctrl", "control")

    /**
     * Whole words only: a temperature word and an "up" word, and every other word from a short
     * allow-list. Unknown words disable the button rather than risk another zone or command.
     */
    fun looksLikeTempUp(name: String): Boolean {
        val w = words(name)
        return w.any { it in tempWords } && w.any { it in upWords } && w.none { it in rejectWords } &&
            w.all { it in tempWords || it in upWords || it in allowedWords }
    }

    fun reset(descriptor: String?) { serviceDescriptor = descriptor; candidate = null; all = emptyList() }

    /** Exactly one candidate, or exactly one that names the left/driver side (the side we can verify). */
    fun choose(found: List<Candidate>) {
        all = found
        candidate = when (found.size) {
            0 -> null
            1 -> found[0]
            else -> found.filter { c -> words(c.method).any { it in leftWords } }.singleOrNull()
        }
    }

    fun candidateText(): String {
        val c = candidate
        val sd = serviceDescriptor
        return when {
            c != null -> "${c.iface}.${c.signature} (#${c.code})" +
                (if (c.descriptor != c.iface) " · arayüz adı ${c.descriptor}" else "") +
                (if (sd != null && sd != c.descriptor) " · servis başka arayüz bildiriyor ($sd): komut gönderilmez" else "")
            all.isEmpty() -> "parametresiz sıcaklık artırma metodu bulunamadı; düğme pasif"
            else -> "birden fazla aday, hangisi belirsiz: " + all.joinToString(", ") { it.method } + "; düğme pasif"
        }
    }

    /** Whether the button may be offered (the final check happens again right before sending). */
    val ready: Boolean get() {
        val c = candidate ?: return false
        val sd = serviceDescriptor
        return !busy.get() && (sd == null || sd == c.descriptor)
    }

    fun confirmText(c: Candidate): String {
        val now = currentTemp()
        return "Araca tek bir komut gidecek:\n\n${c.iface}.${c.method}()  (#${c.code})" +
            (if (c.descriptor != c.iface) "\narayüz adı: ${c.descriptor}" else "") +
            "\n\nŞu anki sıcaklık: ${now.ifEmpty { "bilinmiyor" }}\n" +
            "Sonra eski sıcaklığa araçtaki tuşla dön."
    }

    private const val CONNECTING = 0
    private const val SENDING = 1
    private const val DONE = 2

    /**
     * One attempt for the candidate the user confirmed ([c], captured when the dialog opened).
     * State moves only forward, once: CONNECTING → SENDING → DONE, or CONNECTING → DONE.
     * Only the first connection counts; the worker sends only if it wins CONNECTING → SENDING,
     * so a timeout, a late connection or a service restart can never send, or send twice.
     */
    fun send(ctx: Context, c: Candidate, done: () -> Unit): Boolean {
        if (candidate != c) { lastResult = "aday değişti; yeniden onayla"; return false }
        if (!busy.compareAndSet(false, true)) return false
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val state = AtomicInteger(CONNECTING)
        val connectedOnce = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val bound = AtomicBoolean(false)
        lateinit var conn: ServiceConnection
        // A recording makes the command show up next to the climate values.
        if (!Recorder.recording) runCatching { Recorder.mark("klima +0,5") }

        fun finish(result: String, before: String, after: String, ms: String) {
            if (!finished.compareAndSet(false, true)) return
            state.set(DONE)
            if (bound.get()) runCatching { app.unbindService(conn) }
            val at = System.currentTimeMillis()
            lastResult = result
            log(app, at, c, before, after, ms, result)
            Recorder.event(at, "komut", "${c.iface}.${c.method}", before, after)
            busy.set(false)
            done()
        }
        val timeout = Runnable {
            if (state.compareAndSet(CONNECTING, DONE)) {
                finish("bağlantı gelmedi (servis çalışmıyor olabilir); komut gönderilmedi", "", "", "")
            }
        }
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (!connectedOnce.compareAndSet(false, true)) return // a restart of the service: ignored
                main.removeCallbacks(timeout)
                if (state.get() != CONNECTING) return
                // A vendor call that hangs must not leave the button dead or the service bound.
                main.postDelayed({
                    when {
                        state.compareAndSet(CONNECTING, DONE) -> finish("servis yanıt vermedi; komut gönderilmedi", "", "", "")
                        state.get() == SENDING -> finish("gönderildi, servisten yanıt gelmedi", "", "", "")
                    }
                }, 10_000)
                if (service == null) { main.post { finish("servis boş bağlantı döndü; komut gönderilmedi", "", "", "") }; return }
                thread(name = "kesif-klima") {
                    var sent = false
                    var before = ""
                    try {
                        val reported = runCatching { service.interfaceDescriptor }.getOrNull()
                        if (reported != c.descriptor) {
                            main.post { finish("servis ${reported ?: "?"} arayüzünü bildiriyor, ${c.descriptor} değil; komut gönderilmedi", "", "", "") }
                            return@thread
                        }
                        before = currentTemp()
                        // The single point where a command can leave: only from CONNECTING, only once.
                        if (!state.compareAndSet(CONNECTING, SENDING)) return@thread
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        val accepted = try {
                            data.writeInterfaceToken(c.descriptor)
                            val r = service.transact(c.code, data, reply, 0)
                            sent = true
                            if (r) reply.readException()
                            r
                        } finally { data.recycle(); reply.recycle() }
                        if (!accepted) { main.post { finish("gönderildi ama servis çağrıyı tanımadı (transact false)", before, "", "") }; return@thread }
                        // Verification window starts after the send.
                        val t0 = SystemClock.uptimeMillis()
                        var after = before
                        var ms: Long? = null
                        while (SystemClock.uptimeMillis() - t0 < WAIT_VERIFY_MS) {
                            after = currentTemp()
                            if (after.isNotEmpty() && after != before) { ms = SystemClock.uptimeMillis() - t0; break }
                            Thread.sleep(50)
                        }
                        val result = when {
                            ms == null -> "gönderildi · ${WAIT_VERIFY_MS} ms içinde ACTION_AIR_INFO'da değişiklik görülmedi (${before.ifEmpty { "değer yok" }})"
                            before.isEmpty() -> "gönderildi · önceki değer bilinmiyordu; ${ms} ms sonra ACTION_AIR_INFO $after dedi (doğrulama eksik)"
                            else -> "gönderildi · sıcaklık $before → $after, ${ms} ms içinde ACTION_AIR_INFO ile doğrulandı"
                        }
                        val fAfter = after
                        main.post { finish(result, before, fAfter, ms?.toString().orEmpty()) }
                    } catch (t: Throwable) {
                        val what = if (sent) "gönderildi, sonra hata" else "gönderilmedi, hata"
                        main.post { finish("$what: ${t.javaClass.simpleName} ${t.message.orEmpty()}", before, "", "") }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val ok = runCatching { app.bindService(Intent(ServiceProbe.ACTION).setPackage(ServiceProbe.PKG), conn, 0) }.getOrDefault(false)
        bound.set(ok)
        if (!ok) {
            runCatching { app.unbindService(conn) }
            state.set(DONE)
            finish("servise bağlanılamadı; komut gönderilmedi", "", "", "")
            return true
        }
        main.postDelayed(timeout, WAIT_CONNECT_MS)
        return true
    }

    private fun currentTemp(): String =
        LiveProbe.signals().firstOrNull { it.signal.id == "klima" }?.keys?.firstOrNull { it.key == TEMP_KEY }?.value.orEmpty()

    fun file(ctx: Context) = java.io.File(ctx.filesDir, "kayit/klima-komutu.csv")

    private fun log(ctx: Context, at: Long, c: Candidate, before: String, after: String, ms: String, result: String) {
        runCatching {
            val f = file(ctx).apply { parentFile?.mkdirs() }
            val isNew = !f.exists() || f.length() == 0L
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(at))
            fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
            java.io.FileOutputStream(f, true).use { out ->
                if (isNew) out.write("﻿zaman,arayuz,metot,kod,once,sonra,dogrulama_ms,sonuc\n".toByteArray(Charsets.UTF_8))
                out.write((listOf(stamp, c.iface, c.method, c.code.toString(), before, after, ms, result).joinToString(",") { q(it) } + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }
    }
}
