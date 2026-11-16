package dev.kokpit.kesif

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout

/**
 * "Açılış zaman çizelgesi" (Keşif 0.9, wave A: instant start). All times on the elapsedRealtime
 * clock (milliseconds since the kernel started, counting sleep).
 *
 * An opening is either cold (the system booted: BOOT_COMPLETED, or the vendor's quick-boot
 * broadcasts) or warm (the screen came on after the ignition was off: SCREEN_ON). From its start,
 * the first of each is recorded once: Keşif's process start and first frame, the Kokpit window
 * appearing (seen by Keşif's accessibility service, because Kokpit shares no log; a window, not its
 * first frame), the first GPS fix and the first ACTION_AIR_INFO.
 *
 * To catch openings without the user opening Keşif first, "Sonraki açılışları ölç" arms a foreground
 * service (with a notification) that waits for the screen to come on and is started at boot. It
 * disarms itself after 24 h. It only listens; it changes nothing.
 */
object Acilis {
    const val WINDOW_MS = 180_000L
    private const val ARM_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "acilis"
    const val KOKPIT = "dev.kokpit.anaekran"

    data class Mark(val key: String, val atElapsed: Long, val sinceStart: Long, val note: String)

    private val lock = Any()
    @Volatile var openingId = 0; private set
    @Volatile var kind = ""; private set
    @Volatile var startElapsed = 0L; private set
    @Volatile private var startWall = 0L
    private val marks = ArrayList<Mark>()
    private val seen = HashSet<String>()
    @Volatile private var app: Context? = null

    fun active(): Boolean = startElapsed > 0 && SystemClock.elapsedRealtime() - startElapsed < WINDOW_MS

    fun marks(): List<Mark> = synchronized(lock) { marks.toList() }

    fun armedUntil(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("armedUntil", 0L)
    fun armed(ctx: Context) = armedUntil(ctx) > System.currentTimeMillis()

    fun arm(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("armedUntil", System.currentTimeMillis() + ARM_MS).apply()
        Yol.row(ctx, "acilis", "durum", "ölçüm kuruldu", "24 saat")
        startService(ctx)
    }

    fun disarm(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("armedUntil", 0L).apply()
        Yol.row(ctx, "acilis", "durum", "ölçüm kapatıldı", "")
        ctx.stopService(Intent(ctx, AcilisServisi::class.java))
    }

    fun startService(ctx: Context) {
        val i = Intent(ctx, AcilisServisi::class.java)
        runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
            .onFailure { Yol.row(ctx, "acilis", "hata", "servis başlatılamadı", it.javaClass.simpleName) }
    }

    /** A new opening begins at [atElapsed]; marks of the previous one are kept in the CSV only. */
    fun begin(ctx: Context, kind: String, atElapsed: Long, note: String) {
        val a = ctx.applicationContext
        app = a
        synchronized(lock) {
            openingId++
            this.kind = kind
            startElapsed = atElapsed
            startWall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - atElapsed)
            marks.clear(); seen.clear()
        }
        record(a, "başlangıç", atElapsed, note)
    }

    /** The first [key] of the current opening; later ones are ignored. */
    fun first(key: String, atElapsed: Long, note: String = "") {
        val a = app ?: return
        if (!active() || atElapsed < startElapsed - 1000) return
        synchronized(lock) { if (!seen.add(key)) return }
        record(a, key, atElapsed, note)
    }

    private fun record(a: Context, key: String, atElapsed: Long, note: String) {
        val since = atElapsed - startElapsed
        synchronized(lock) { marks.add(Mark(key, atElapsed, since, note)) }
        val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - atElapsed)
        Yol.log.post(a, wall, listOf("acilis", "$kind #$openingId", key, "+$since ms", "açılıştan ${atElapsed} ms · $note".trim(' ', '·')))
    }

    /** Process start, and the time the system says it booted, when Keşif starts. */
    fun onProcessStart(ctx: Context) {
        app = ctx.applicationContext
        val procStart = if (Build.VERSION.SDK_INT >= 24) Process.getStartElapsedRealtime() else SystemClock.elapsedRealtime()
        // Keşif started within 3 min of boot and no opening is running: count it as a cold opening.
        if (!active() && procStart < WINDOW_MS) begin(ctx, "soğuk", 0L, "çekirdek açılışı (BOOT_COMPLETED’ı Keşif açılmadan gelmiş olabilir)")
        first("keşif süreci", procStart, "süreç başladı")
        if (armed(ctx)) startService(ctx)
    }

    /** From MainActivity.onResume: the next frame drawn is Keşif's first frame of this opening. */
    fun watchFirstFrame() {
        if (!active()) return
        Choreographer.getInstance().postFrameCallback { ns -> first("keşif ilk kare", Yol.uptimeToElapsed(ns / 1_000_000)) }
    }

    /** From the accessibility service: a window of Kokpit or ZLINK came to the front (time only). */
    fun onWindow(pkg: String, eventUptime: Long) {
        if (pkg == KOKPIT) first("kokpit penceresi", Yol.uptimeToElapsed(eventUptime), "erişilebilirlik: pencere göründü (ilk kare değil)")
        else if (pkg.contains("zlink", true)) first("zlink penceresi", Yol.uptimeToElapsed(eventUptime), pkg)
    }

    fun onAirInfo(sticky: Boolean) {
        first("ilk klima yayını", SystemClock.elapsedRealtime(), if (sticky) "yapışkan (önceki yayın)" else "ACTION_AIR_INFO")
    }

    fun onFix(l: Location) {
        if (l.elapsedRealtimeNanos <= 0) return
        val fixAt = l.elapsedRealtimeNanos / 1_000_000
        if (fixAt < startElapsed) return // a cached fix from before this opening
        first("ilk GPS konumu", fixAt, "doğruluk ${l.accuracy.toInt()} m · teslim +${SystemClock.elapsedRealtime() - fixAt} ms")
    }
}

/** Boot broadcasts. BOOT_COMPLETED reaches a manifest receiver even on Android 8; the others are tried too. */
class KesifAcilis : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            val now = SystemClock.elapsedRealtime()
            val action = intent?.action.orEmpty()
            // A boot: the opening started at kernel start (elapsed 0); "sistem hazır" is this broadcast.
            if (!Acilis.active() || Acilis.kind != "soğuk") Acilis.begin(ctx, "soğuk", 0L, "çekirdek açılışı")
            Acilis.first("sistem hazır", now, action.substringAfterLast('.'))
            if (Acilis.armed(ctx)) Acilis.startService(ctx)
        } catch (t: Throwable) {
            // Never crash at boot.
        }
    }
}

/**
 * Armed only: keeps Keşif alive between openings and listens for the screen coming on. While an
 * opening is being measured (3 min) it also listens for GPS and ACTION_AIR_INFO.
 */
class AcilisServisi : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var screen: BroadcastReceiver? = null
    private var air: BroadcastReceiver? = null
    private var gps: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("acilis", "Açılış ölçümü", NotificationManager.IMPORTANCE_LOW))
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "acilis") else Notification.Builder(this)
        val n = b.setSmallIcon(applicationInfo.icon).setContentTitle("Keşif açılışı ölçüyor")
            .setContentText("Kontak açılınca zaman çizelgesi kaydedilir · 24 saatte kapanır").setOngoing(true).build()
        startForeground(41, n)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                try {
                    when (i?.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            Acilis.begin(this@AcilisServisi, "sıcak", SystemClock.elapsedRealtime(), "ekran açıldı")
                            // Keşif already on screen: its next drawn frame is its first of this opening.
                            if (LiveProbe.visible) Acilis.watchFirstFrame()
                            listen()
                        }
                        Intent.ACTION_SCREEN_OFF -> Yol.row(this@AcilisServisi, "acilis", "olay", "ekran kapandı", "")
                    }
                } catch (t: Throwable) { }
            }
        }
        screen = r
        registerReceiver(r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        if (Acilis.active()) listen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Acilis.armed(this)) { stopSelf(); return START_NOT_STICKY }
        main.removeCallbacks(expire)
        main.postDelayed(expire, (Acilis.armedUntil(this) - System.currentTimeMillis()).coerceAtLeast(1000))
        return START_STICKY
    }

    private val expire = Runnable { if (!Acilis.armed(this)) stopSelf() }
    private val quiet = Runnable { unlisten() }

    /** GPS and climate broadcasts for the current opening only, then off again. */
    private fun listen() {
        main.removeCallbacks(quiet)
        main.postDelayed(quiet, Acilis.WINDOW_MS)
        if (air == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { runCatching { Acilis.onAirInfo(isInitialStickyBroadcast) } }
            }
            air = r
            runCatching { registerReceiver(r, IntentFilter(LiveProbe.AIR)) }
        }
        if (gps == null && GpsGuard.hasPermission(this)) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) { runCatching { Acilis.onFix(location) } }
                @Deprecated("API 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            @Suppress("MissingPermission")
            runCatching { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper()); gps = l }
        }
    }

    private fun unlisten() {
        air?.let { runCatching { unregisterReceiver(it) } }; air = null
        gps?.let { l -> runCatching { (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l) } }; gps = null
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unlisten()
        screen?.let { runCatching { unregisterReceiver(it) } }; screen = null
        super.onDestroy()
    }
}

class AcilisPage(private val a: Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val state = ui.label("", 15f, Palette.text, bold = true)
    private val table = ui.label("", 15f, Palette.text, mono = true)
    private val arm = ui.button("Sonraki açılışları ölç", primary = true) { toggle() }

    init {
        val (r, content) = ui.frame("3 · Açılış zaman çizelgesi", "Kontak açılışından itibaren · elapsedRealtime", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("“Sonraki açılışları ölç”e dokun, sonra kontağı kapat ve aç (sıcak açılış). Ekran 3 günde bir baştan açıldığında (soğuk açılış) da ölçer. Keşif bildirimle arkada bekler, 24 saatte kendiliğinden kapanır. Kokpit için erişilebilirlikteki “Keşif pencere günlüğü” açık olmalı.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(state.apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(arm, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(60)))
        }
        ui.card(content) {
            addView(ui.label("Son açılış", 17f, Palette.accent, bold = true))
            addView(table.apply { setPadding(0, ui.dp(6), 0, 0) })
        }
    }

    private fun toggle() {
        if (Acilis.armed(a)) Acilis.disarm(a) else {
            if (!GpsGuard.hasPermission(a)) runCatching { a.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 18) }
            Acilis.arm(a)
        }
        tick()
    }

    override fun tick() {
        val armed = Acilis.armed(a)
        arm.text = if (armed) "Ölçümü kapat" else "Sonraki açılışları ölç"
        state.text = (if (armed) "● Kurulu · ${(Acilis.armedUntil(a) - System.currentTimeMillis()) / 3_600_000} saat kaldı" else "Kurulu değil") +
            (if (!WindowLog.enabled(a)) " · pencere günlüğü kapalı: Kokpit görülmez" else "") +
            (if (!GpsGuard.hasPermission(a)) " · konum izni yok: GPS görülmez" else "")
        val m = Acilis.marks()
        table.text = if (m.isEmpty()) "Henüz açılış ölçülmedi." else
            "${Acilis.kind} açılış #${Acilis.openingId}" + (if (Acilis.active()) " · ölçülüyor" else "") + "\n\n" +
                m.joinToString("\n") { "+${"%6d".format(it.sinceStart)} ms  ${it.key}" + if (it.note.isNotEmpty()) "\n            ${it.note}" else "" }
    }
}
