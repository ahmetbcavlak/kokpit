package dev.kokpit.kesif

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service reachability check for the vendor CAN service (action com.hcn.autocan.CanService).
 *
 * Two parts:
 *  - static, from PackageManager: is there such a service, is it exported, does it need a permission
 *  - one bindService() attempt, only when the user taps the button: did the system allow the bind,
 *    and did a connection arrive. The connection is closed with unbindService() as soon as it
 *    arrives (or after 3 s). No method is called on the binder, not even getInterfaceDescriptor.
 *
 * The bind uses no BIND_AUTO_CREATE flag: if the service is not already running, the test does
 * not start it. If it is running, binding makes the vendor's onBind() run, and the unbind its
 * onUnbind() when we were the only client: vendor code we do not control, run once per tap.
 */
object ServiceProbe {

    const val PKG = "com.hcn.autocan"
    const val ACTION = "com.hcn.autocan.CanService"
    private const val WAIT_MS = 3000L

    @Volatile var running = false; private set
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }
    @Volatile var lastResult: List<Line>? = null; private set

    private fun intent() = Intent(ACTION).setPackage(PKG) // explicit package: required since Android 5

    /** What the package manager says, without touching the service. */
    fun staticInfo(ctx: Context): List<Line> {
        val pm = ctx.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val found = runCatching { pm.queryIntentServices(intent(), flags) }.getOrDefault(emptyList())
        if (found.isEmpty()) {
            val installed = runCatching { pm.getPackageInfo(PKG, 0); true }.getOrDefault(false)
            return listOf(Line("Servis", if (installed) "$ACTION eylemine yanıt veren servis yok" else "$PKG yüklü değil"))
        }
        return found.flatMap { ri ->
            val si = ri.serviceInfo
            listOf(
                Line("Servis", "${si.packageName}/${si.name}"),
                Line("Dışa açık (exported)", if (si.exported) "evet" else "hayır"),
                Line("Gereken izin", si.permission ?: "yok"),
                Line("Etkin", if (si.enabled) "evet" else "hayır"),
                Line("Ayrı süreç", si.processName ?: "—"),
            )
        }
    }

    /** One bind attempt on the main thread; the result lands in [lastResult]. */
    fun runBindTest(ctx: Context, done: () -> Unit) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()
        var connected = false
        var binderPresent = false
        var finished = false
        var bound = false
        var bindError: String? = null

        lateinit var conn: ServiceConnection
        fun finish(note: String) {
            if (finished) return
            finished = true
            if (bound) runCatching { app.unbindService(conn) }
            val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr")).format(Date(started))
            lastResult = listOf(
                Line("Deneme zamanı", stamp),
                Line("bindService() sonucu", if (bindError != null) "hata: $bindError" else bound.toString()),
                Line("Bağlantı geldi mi", if (connected) "evet (${System.currentTimeMillis() - started} ms)" else "hayır"),
                Line("Binder döndü mü", if (binderPresent) "evet" else "hayır"),
                Line("Bağlanılabilir", when {
                    bound && connected && binderPresent -> "true"
                    bound && !connected -> "bilinmiyor (sistem izin verdi ama servis çalışmıyor olabilir)"
                    else -> "false"
                }),
                Line("Not", note),
            )
            running = false
            done()
        }
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected = true
                binderPresent = service != null
                // Closed right away; the binder is never used.
                finish("bağlantı hemen kapatıldı (unbindService); hiçbir metot çağrılmadı")
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        bound = try {
            app.bindService(intent(), conn, 0)
        } catch (e: SecurityException) {
            bindError = "SecurityException (izin gerekli): ${e.message.orEmpty()}"; false
        } catch (t: Throwable) {
            bindError = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"; false
        }
        if (!bound) {
            // The system keeps the connection object even after a refused bind; release it.
            runCatching { app.unbindService(conn) }
            finish(if (bindError != null) "sistem bağlanmaya izin vermedi" else "servis bulunamadı ya da bağlanmaya kapalı"); return
        }
        main.postDelayed({
            finish("$WAIT_MS ms içinde bağlantı gelmedi; servis çalışmıyor olabilir (test servisi başlatmaz). Bağlantı kapatıldı.")
        }, WAIT_MS)
    }

    fun section(): Section? = app?.let { section(it) }

    fun section(ctx: Context): Section = Section(
        "Servis erişilebilirliği · $ACTION",
        staticInfo(ctx) + (lastResult ?: listOf(Line("Bağlanma testi", "henüz denenmedi (canlı ekrandaki düğmeyle yapılır)"))),
    )
}
