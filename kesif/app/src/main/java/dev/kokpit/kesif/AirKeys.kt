package dev.kokpit.kesif

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import dalvik.system.PathClassLoader
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * GPS speed for the climate page's guard. Listens to the GPS provider only while the page is on
 * screen. Without a recent fix that carries a speed, the guard says no.
 */
object GpsGuard {
    const val MAX_KMH = 1.0
    const val MAX_AGE_MS = 3000L

    @Volatile private var fix: Location? = null
    @Volatile var listening = false; private set
    private var listener: LocationListener? = null

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        if (listening || !hasPermission(ctx)) return
        val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) { fix = location }
            @Deprecated("API 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        runCatching {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, l, Looper.getMainLooper())
            listener = l
            listening = true
        }
    }

    fun stop(ctx: Context) {
        val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        listener?.let { l -> runCatching { lm?.removeUpdates(l) } }
        listener = null
        listening = false
    }

    /** For tests only. */
    internal fun feed(l: Location?) { fix = l }

    /** null when sending is allowed; otherwise why not. */
    fun blockReason(nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos()): String? {
        val f = fix ?: return "GPS yok"
        val age = (nowElapsedNanos - f.elapsedRealtimeNanos) / 1_000_000
        if (age > MAX_AGE_MS) return "GPS eski (${age / 1000} sn)"
        if (!f.hasSpeed()) return "GPS hız vermiyor"
        val kmh = f.speed * 3.6
        if (kmh > MAX_KMH) return "araç hareket ediyor (%.1f km/sa)".format(Locale("tr"), kmh)
        return null
    }

    fun speedText(): String {
        val f = fix ?: return "—"
        return if (f.hasSpeed()) "%.1f km/sa".format(Locale("tr"), f.speed * 3.6) else "hız yok"
    }
}

/**
 * "Klima komutu" (Keşif 0.8, K-117): four keys of the vendor's climate panel, pressed through the
 * vendor's own service, the way its climate screen does it (CanService.MyBinder → dealAirCtrl →
 * Renault_Raise_DataDecorder.callBackOnTouch: press and release are separate CAN commands).
 *
 *  - Service: com.hcn.autocan, action com.hcn.autocan.CanService, bound without BIND_AUTO_CREATE.
 *  - Interface com.hcn.autocan.aidl.IAirCtrlServices, transaction 1 = airCtrl(int key, int action),
 *    action 0 press, 1 release. The service must report exactly this interface name; and when the
 *    vendor APK can be read, its Stub must say TRANSACTION_airCtrl = 1 and airCtrl(int, int).
 *  - Per tap: airCtrl(key, 0), 100 ms, airCtrl(key, 1). Once the press has been tried, the release
 *    is always sent (finally), and tried once more if it fails, so a key is never left held.
 *  - Only keys 2 fan +, 3 fan −, 4 left temperature +, 5 left temperature −.
 *  - Only when GPS says at most 1 km/h within the last 3 s (checked when the dialog opens and again
 *    right before the press), only after the user confirms, one tap at a time.
 *  - What was sent, the results and the ACTION_AIR_INFO values before and after go to
 *    kayit/klima-tuslari.csv and the live recording.
 */
object AirKeys {

    const val DESCRIPTOR = "com.hcn.autocan.aidl.IAirCtrlServices"
    const val TRANSACTION = 1
    const val PRESS = 0
    const val RELEASE = 1
    const val HOLD_MS = 100L
    private const val WAIT_CONNECT_MS = 3000L
    private const val WAIT_VERIFY_MS = 3000L

    data class Key(val code: Int, val name: String, val watch: String)

    val keys = listOf(
        Key(2, "Fan +", "KEY_VALUE_WIND_LEVEL"), Key(3, "Fan −", "KEY_VALUE_WIND_LEVEL"),
        Key(4, "Sol sıcaklık +", "KEY_VALUE_LEFT_TEMP"), Key(5, "Sol sıcaklık −", "KEY_VALUE_LEFT_TEMP"),
    )
    private val allowed = keys.map { it.code }.toSet()

    val log = CsvLog("klima-tuslari.csv",
        "zaman,tus,ad,gps_hiz,arayuz,bas,birak,once,sonra,degisim_ms,sonuc")

    private val busy = AtomicBoolean(false)
    val running: Boolean get() = busy.get()
    @Volatile var lastResult: String? = null; private set

    /** What the vendor APK says about the interface: null not checked yet, else the verdict. */
    @Volatile var apkCheck: String? = null; private set
    @Volatile var apkMismatch = false; private set

    /**
     * Reads the vendor's Stub without running vendor logic (class loaded with initialize = false;
     * reading its TRANSACTION_ constant initialises only that constants-holder class). A readable APK
     * that disagrees with transaction 1 / airCtrl(int, int) blocks the buttons; an unreadable one does not.
     */
    fun checkApk(ctx: Context): String {
        apkCheck?.let { return it }
        val verdict = runCatching {
            val apk = ctx.packageManager.getApplicationInfo(ServiceProbe.PKG, 0).sourceDir
            val loader = PathClassLoader(apk, ClassLoader.getSystemClassLoader())
            val iface = Class.forName(DESCRIPTOR, false, loader)
            val stub = Class.forName("$DESCRIPTOR\$Stub", false, loader)
            val desc = runCatching { stub.getDeclaredField("DESCRIPTOR").apply { isAccessible = true }.get(null) as? String }.getOrNull()
            val f = stub.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && it.name == "TRANSACTION_airCtrl" }
            val code = f?.let { it.isAccessible = true; it.getInt(null) }
            val m = iface.declaredMethods.firstOrNull { it.name == "airCtrl" }
            val sig = m?.parameterTypes?.map { it.name }
            val okSig = sig == listOf("int", "int")
            when {
                desc != null && desc != DESCRIPTOR -> { apkMismatch = true; "APK uyuşmuyor: arayüz adı $desc" }
                code == null || m == null -> { apkMismatch = true; "APK uyuşmuyor: airCtrl bulunamadı" }
                code != TRANSACTION || !okSig -> { apkMismatch = true; "APK uyuşmuyor: airCtrl #$code (${sig?.joinToString()})" }
                else -> "APK doğruladı: airCtrl(int, int) #$code"
            }
        }.getOrElse { "APK’dan doğrulanamadı (${it.javaClass.simpleName}); servis arayüz adı yine denetlenir" }
        apkCheck = verdict
        return verdict
    }

    /** For tests only. */
    internal fun setApkCheck(verdict: String?, mismatch: Boolean) { apkCheck = verdict; apkMismatch = mismatch }

    /** null when a key may be offered; otherwise why not. */
    fun blockReason(): String? = when {
        busy.get() -> "önceki komut sürüyor"
        apkMismatch -> apkCheck
        else -> GpsGuard.blockReason()
    }

    fun confirmText(k: Key): String {
        val air = LiveProbe.airValues()
        return "Araca tek bir tuş basışı gidecek:\n\n${k.name}  (tuş ${k.code})\n" +
            "airCtrl(${k.code}, 0), 100 ms sonra airCtrl(${k.code}, 1)\n\n" +
            "Şu an: sıcaklık ${air["KEY_VALUE_LEFT_TEMP"].orEmpty().ifEmpty { "?" }} · fan ${air["KEY_VALUE_WIND_LEVEL"].orEmpty().ifEmpty { "?" }} · GPS ${GpsGuard.speedText()}\n\n" +
            "Beklenmedik bir şey olursa araçtaki tuşla geri al."
    }

    private const val CONNECTING = 0
    private const val SENDING = 1
    private const val DONE = 2

    /**
     * One key, after the user confirmed. State moves forward once: CONNECTING → SENDING → DONE, or
     * CONNECTING → DONE. Only the worker that wins CONNECTING → SENDING may press, so a timeout, a
     * late or repeated connection can never press, or press twice. The binding is released only
     * after the worker is done (a release must still be able to reach the service).
     */
    fun send(ctx: Context, k: Key, done: () -> Unit): Boolean {
        if (k.code !in allowed) { lastResult = "izinli olmayan tuş ${k.code}"; return false }
        GpsGuard.blockReason()?.let { lastResult = "gönderilmedi: $it"; return false }
        if (apkMismatch) { lastResult = "gönderilmedi: $apkCheck"; return false }
        if (!busy.compareAndSet(false, true)) return false
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val state = AtomicInteger(CONNECTING)
        val connectedOnce = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val bound = AtomicBoolean(false)
        val speed = GpsGuard.speedText()
        lateinit var conn: ServiceConnection
        if (!Recorder.recording) runCatching { Recorder.mark("klima tuş ${k.code}") }

        fun finish(result: String, iface: String, press: String, release: String, before: String, after: String, ms: String) {
            if (!finished.compareAndSet(false, true)) return
            state.set(DONE)
            if (bound.get()) runCatching { app.unbindService(conn) }
            val at = System.currentTimeMillis()
            lastResult = "${k.name}: $result"
            thread(isDaemon = true) {
                log.append(app, at, listOf(k.code.toString(), k.name, speed, iface, press, release, before, after, ms, result))
            }
            Recorder.event(at, "komut", "airCtrl ${k.code} ${k.name}", before, "$after · $result")
            busy.set(false)
            done()
        }
        val timeout = Runnable {
            if (state.compareAndSet(CONNECTING, DONE)) finish("bağlantı gelmedi; komut gönderilmedi", "", "", "", "", "", "")
        }
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (!connectedOnce.compareAndSet(false, true)) return // a restart of the service: ignored
                main.removeCallbacks(timeout)
                if (state.get() != CONNECTING) return
                if (service == null) { main.post { if (state.compareAndSet(CONNECTING, DONE)) finish("servis boş bağlantı döndü; komut gönderilmedi", "", "", "", "", "", "") }; return }
                thread(name = "kesif-klima-tus") { work(service, k, state, main, ::finish) }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val ok = runCatching { app.bindService(Intent(ServiceProbe.ACTION).setPackage(ServiceProbe.PKG), conn, 0) }.getOrDefault(false)
        bound.set(ok)
        if (!ok) {
            runCatching { app.unbindService(conn) }
            state.set(DONE)
            finish("servise bağlanılamadı; komut gönderilmedi", "", "", "", "", "", "")
            return true
        }
        main.postDelayed(timeout, WAIT_CONNECT_MS)
        return true
    }

    private fun work(service: IBinder, k: Key, state: AtomicInteger, main: Handler,
                     finish: (String, String, String, String, String, String, String) -> Unit) {
        var iface = ""
        var press = ""
        var release = ""
        var before = ""
        fun end(result: String, after: String = "", ms: String = "") {
            val i = iface; val p = press; val r = release; val b = before
            main.post { finish(result, i, p, r, b, after, ms) }
        }
        try {
            iface = runCatching { service.interfaceDescriptor }.getOrNull() ?: "?"
            if (iface != DESCRIPTOR) { state.set(DONE); end("servis $iface arayüzünü bildiriyor, $DESCRIPTOR değil; komut gönderilmedi"); return }
            // The guard again, at the last moment: the car may have started moving while the dialog was open.
            GpsGuard.blockReason()?.let { state.set(DONE); end("gönderilmedi: $it"); return }
            before = air(k)
            // The single point where a command can leave: only from CONNECTING, only once.
            if (!state.compareAndSet(CONNECTING, SENDING)) return
            try {
                press = call(service, k.code, PRESS)
                Thread.sleep(HOLD_MS)
            } finally {
                release = call(service, k.code, RELEASE)
                if (release != "tamam") {
                    Thread.sleep(50)
                    release += " · ikinci deneme: " + call(service, k.code, RELEASE)
                }
            }
            // Wait for ACTION_AIR_INFO to show the change, from the release on.
            val t0 = SystemClock.uptimeMillis()
            var after = before
            var ms: Long? = null
            while (SystemClock.uptimeMillis() - t0 < WAIT_VERIFY_MS) {
                after = air(k)
                if (after != before) { ms = SystemClock.uptimeMillis() - t0; break }
                Thread.sleep(50)
            }
            val result = when {
                press != "tamam" || !release.startsWith("tamam") && !release.endsWith("tamam") -> "gönderildi, servis hata verdi (bas: $press, bırak: $release)"
                ms == null -> "gönderildi · $WAIT_VERIFY_MS ms içinde klima yayınında değişiklik görülmedi"
                else -> "gönderildi · $before → $after, $ms ms içinde klima yayınıyla görüldü"
            }
            end(result, after, ms?.toString().orEmpty())
        } catch (t: Throwable) {
            end("hata: ${t.javaClass.simpleName} ${t.message.orEmpty()} (bas: ${press.ifEmpty { "gitmedi" }}, bırak: ${release.ifEmpty { "gitmedi" }})")
        }
    }

    /** One airCtrl call, written the way AIDL writes it: interface token, then the two ints. */
    private fun call(service: IBinder, key: Int, action: Int): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(key)
            data.writeInt(action)
            if (!service.transact(TRANSACTION, data, reply, 0)) "servis tanımadı (transact false)"
            else { reply.readException(); "tamam" }
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        } finally { data.recycle(); reply.recycle() }
    }

    /** The watched value and the other side of the pair, e.g. "sıcaklık 22.0℃ · fan 3". */
    private fun air(k: Key): String {
        val v = LiveProbe.airValues()
        return "sıcaklık ${v["KEY_VALUE_LEFT_TEMP"].orEmpty().ifEmpty { "?" }} · fan ${v["KEY_VALUE_WIND_LEVEL"].orEmpty().ifEmpty { "?" }}"
    }
}

class AirPage(private val a: Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val guard = ui.label("", 16f, Palette.text, bold = true)
    private val values = ui.label("", 15f, Palette.text, mono = true)
    private val apk = ui.label("", 13f, Palette.muted)
    private val result = ui.label("Henüz gönderilmedi.", 14f, Palette.text, mono = true)
    private val buttons = AirKeys.keys.map { k -> k to ui.button(k.name, primary = false, size = 20f) { confirm(k) } }
    private var dialogOpen = false

    init {
        val (r, content) = ui.frame("Klima komutu (K-117)", "Araca giden tek komut · yalnız park hâlinde", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("Her dokunuş üreticinin klima tuşuna bir kez basıp bırakır (airCtrl, 100 ms). Önce onay sorulur. GPS hızı 1 km/sa’i aşarsa ya da GPS yoksa düğmeler pasif. Beklenmedik bir şey olursa araçtaki tuşla geri al ve dur.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(guard)
            addView(values.apply { setPadding(0, ui.dp(6), 0, ui.dp(4)) })
            addView(apk.apply { setPadding(0, 0, 0, ui.dp(10)) })
            for (i in buttons.indices step 2) {
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(buttons[i].second, LinearLayout.LayoutParams(0, ui.dp(84), 1f).apply { rightMargin = ui.dp(10) })
                    addView(buttons[i + 1].second, LinearLayout.LayoutParams(0, ui.dp(84), 1f))
                }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(84)).apply { bottomMargin = ui.dp(10) })
            }
        }
        ui.card(content) {
            addView(ui.label("Son sonuç", 17f, Palette.accent, bold = true))
            addView(result.apply { setPadding(0, ui.dp(6), 0, 0) })
            addView(ui.label("Her deneme klima-tuslari.csv’ye ve açıksa ana kayda yazılır.", 13f, Palette.faint).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
    }

    override fun onShow() {
        if (!GpsGuard.hasPermission(a)) runCatching { a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 17) }
        GpsGuard.start(a)
        if (AirKeys.apkCheck == null) thread(isDaemon = true) { AirKeys.checkApk(a) }
    }

    override fun onHide() { GpsGuard.stop(a) }

    private fun confirm(k: AirKeys.Key) {
        AirKeys.blockReason()?.let { result.text = "Gönderilmedi: $it"; return }
        if (dialogOpen) return
        dialogOpen = true
        android.app.AlertDialog.Builder(a)
            .setTitle(k.name)
            .setMessage(AirKeys.confirmText(k))
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Gönder") { _, _ ->
                result.text = "Gönderiliyor…"
                if (!AirKeys.send(a, k) { result.text = AirKeys.lastResult ?: "" }) result.text = AirKeys.lastResult ?: "Önceki komut sürüyor"
            }
            .setOnDismissListener { dialogOpen = false }
            .show()
    }

    override fun tick() {
        if (!GpsGuard.listening && GpsGuard.hasPermission(a)) GpsGuard.start(a)
        val block = AirKeys.blockReason()
        guard.text = when {
            !GpsGuard.hasPermission(a) -> "Konum izni yok: izin verilmeden düğmeler pasif."
            block == null -> "Hazır · GPS ${GpsGuard.speedText()}"
            else -> "Pasif: $block"
        }
        guard.setTextColor(if (block == null && GpsGuard.hasPermission(a)) Palette.live else Palette.accent)
        val v = LiveProbe.airValues()
        values.text = "Sıcaklık ${v["KEY_VALUE_LEFT_TEMP"].orEmpty().ifEmpty { "—" }} · Fan ${v["KEY_VALUE_WIND_LEVEL"].orEmpty().ifEmpty { "—" }} · A/C ${v["KEY_VALUE_ACFLAG"].orEmpty().ifEmpty { "—" }}"
        apk.text = AirKeys.apkCheck ?: "APK denetleniyor…"
        val on = block == null
        buttons.forEach { (_, b) -> b.isEnabled = on; b.alpha = if (on) 1f else 0.45f }
        AirKeys.lastResult?.let { if (!AirKeys.running && result.text.toString() != it) result.text = it }
    }
}
