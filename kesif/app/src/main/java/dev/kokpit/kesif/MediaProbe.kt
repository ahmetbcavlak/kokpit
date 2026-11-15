package dev.kokpit.kesif

import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Notification listener, needed only so MediaSessionManager.getActiveSessions() answers (Android
 * gives the list of media sessions to enabled listeners). It reads; it never cancels, snoozes or
 * replies to a notification. Of the notifications it sees, only media ones and those of ZLINK and the
 * vendor's com.hcn.* apps are kept; everything else is dropped on the spot.
 */
class KesifBildirim : NotificationListenerService() {
    override fun onListenerConnected() {
        MediaProbe.listenerConnected = true
        val current = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        MediaProbe.setCurrentNotifications(current)
    }
    override fun onListenerDisconnected() { MediaProbe.listenerConnected = false }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { MediaProbe.onNotification(it, posted = true) } }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { sbn?.let { MediaProbe.onNotification(it, posted = false) } }
}

/**
 * "Medya ve bağlantı" (Keşif 0.8): what music and connection information this unit exposes to an
 * ordinary app. Read-only: no transport control is sent, no notification is touched, no setting is
 * changed, Bluetooth is not switched or paired.
 *
 *  - active media sessions (package, metadata, cover art presence and size, playback state, actions)
 *  - media notifications (ZLINK, the vendor's Bluetooth music app, anything with a media session)
 *  - Bluetooth broadcasts: ACL connect/disconnect, A2DP sink, AVRCP controller, A2DP, headset client
 *  - vendor broadcasts: action names found in the vendor apps' code (see [DexStrings])
 *  - Google Play services and Play Store installed or not, ZLINK's package, network state
 *
 * Bluetooth and vendor broadcasts and media notifications are written to kayit/medya.csv whenever the
 * app runs (vendor ones at most once a second per action outside a measurement). The 30-second
 * measurement adds snapshots and every media-session change.
 */
object MediaProbe {

    const val MEASURE_MS = 30_000L
    private const val SNAPSHOT_MS = 5_000L

    val log = CsvLog("medya.csv", "zaman,saniye,olcum,tur,kaynak,anahtar,deger")
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val lock = Any()

    @Volatile var listenerConnected = false
    private val recent = ArrayDeque<String>()
    private val counts = linkedMapOf<String, Int>()
    private val lastWrite = hashMapOf<String, Long>()
    private val notifications = linkedMapOf<String, String>() // key → summary, current ones only

    private var btReceiver: BroadcastReceiver? = null
    private var vendorReceiver: BroadcastReceiver? = null
    @Volatile var vendorActions: List<String> = emptyList(); private set
    @Volatile var vendorScan = "taranmadı"; private set

    // measurement
    @Volatile var measuring = false; private set
    @Volatile private var measureId = ""
    @Volatile private var measureStart = 0L
    private val callbacks = ArrayList<Pair<MediaController, MediaController.Callback>>()
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    @Volatile var lastMeasure: String? = null; private set

    val btActions = listOf(
        BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED,
        BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
        "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED",
        "android.bluetooth.a2dp-sink.profile.action.AUDIO_CONFIG_CHANGED",
        "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED",
        "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED",
    )

    /** At app start: Bluetooth broadcasts are heard from the start, so a phone connecting is never missed. */
    fun start(ctx: Context) {
        val a = ctx.applicationContext
        app = a
        if (btReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                try { intent?.let { onBroadcast("bt", it) } } catch (t: Throwable) { note("hata", "bt", t.javaClass.simpleName) }
            }
        }
        btReceiver = r
        runCatching { register(a, r, IntentFilter().apply { btActions.forEach { addAction(it) } }) }
            .onFailure { note("hata", "bt-dinleyici", it.message.orEmpty()) }
    }

    fun stop(ctx: Context) {
        val a = ctx.applicationContext
        btReceiver?.let { runCatching { a.unregisterReceiver(it) } }; btReceiver = null
        vendorReceiver?.let { runCatching { a.unregisterReceiver(it) } }; vendorReceiver = null
        stopMeasure(a, "uygulama kapandı")
    }

    private fun register(a: Context, r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else a.registerReceiver(r, f)
    }

    /** Once, when the page first opens: find the vendor's action names in its code and listen to them. */
    fun scanVendor(ctx: Context, done: () -> Unit) {
        if (vendorScan != "taranmadı") { done(); return }
        vendorScan = "taranıyor…"
        val a = ctx.applicationContext
        thread(name = "kesif-uretici", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            val apks = vendorPackages(a).mapNotNull { p ->
                runCatching { a.packageManager.getApplicationInfo(p, 0) }.getOrNull()?.let { listOf(it.sourceDir) + (it.splitSourceDirs?.toList() ?: emptyList()) }
            }.flatten()
            val acts = runCatching { DexStrings.actions(apks) }.getOrDefault(emptyList()).filter { it !in btActions && it != LiveProbe.AIR }
            vendorActions = acts
            vendorScan = "${apks.size} dosya tarandı, ${acts.size} eylem adı dinleniyor (${(System.currentTimeMillis() - t0) / 1000} sn)"
            main.post {
                if (acts.isNotEmpty() && vendorReceiver == null) {
                    val r = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            try { intent?.let { onBroadcast("uretici", it) } } catch (t: Throwable) { note("hata", "uretici", t.javaClass.simpleName) }
                        }
                    }
                    vendorReceiver = r
                    runCatching { register(a, r, IntentFilter().apply { acts.forEach { addAction(it) } }) }
                        .onFailure { vendorScan += " · dinleyici kurulamadı: ${it.message}" }
                }
                val now = System.currentTimeMillis()
                thread(isDaemon = true) { log.appendAll(a, acts.map { now to listOf("", measureId, "uretici-eylem", "dex", it, "dinleniyor") }) }
                done()
            }
        }
    }

    // ---------- packages and network ----------

    fun zlinkPackages(ctx: Context): List<String> = runCatching {
        val pm = ctx.packageManager
        pm.getInstalledApplications(0).filter { ai ->
            ai.packageName.contains("zlink", true) || runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault("").contains("zlink", true)
        }.map { it.packageName }.sorted()
    }.getOrDefault(emptyList())

    fun vendorPackages(ctx: Context): List<String> = runCatching {
        ctx.packageManager.getInstalledApplications(0).map { it.packageName }.filter { it.startsWith("com.hcn.") }.sorted()
    }.getOrDefault(emptyList()) + zlinkPackages(ctx)

    @Volatile private var pkgCache: Pair<Long, List<Line>>? = null
    @Volatile private var pkgLoading = false

    /**
     * Listing every app is slow on this CPU: the screen gets a copy at most 30 s old, refreshed off
     * the main thread; [fresh] (measurement snapshots, already on a worker) reads it again now.
     */
    fun packageLines(ctx: Context, fresh: Boolean = false): List<Line> {
        if (fresh) return readPackageLines(ctx).also { pkgCache = System.currentTimeMillis() to it }
        val c = pkgCache
        if ((c == null || System.currentTimeMillis() - c.first > 30_000) && !pkgLoading) {
            pkgLoading = true
            val a = ctx.applicationContext
            thread(isDaemon = true) { try { pkgCache = System.currentTimeMillis() to readPackageLines(a) } finally { pkgLoading = false } }
        }
        return c?.second ?: listOf(Line("Paketler", "okunuyor…"))
    }

    private fun readPackageLines(ctx: Context): List<Line> {
        val pm = ctx.packageManager
        fun ver(p: String): String = runCatching {
            val pi = pm.getPackageInfo(p, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            val enabled = pi.applicationInfo?.enabled ?: true
            "kurulu · ${pi.versionName} ($code)" + if (!enabled) " · kapalı" else ""
        }.getOrElse { "kurulu değil" }
        val hcn = runCatching {
            pm.getInstalledApplications(0).filter { it.packageName.startsWith("com.hcn.") }
                .map { "${it.packageName} (${runCatching { pm.getApplicationLabel(it) }.getOrDefault("")})" }.sorted()
        }.getOrDefault(emptyList())
        return listOf(
            Line("Google Play hizmetleri", ver("com.google.android.gms")),
            Line("Play Store", ver("com.android.vending")),
            Line("ZLINK paketi", zlinkPackages(ctx).joinToString { "$it · ${ver(it)}" }.ifEmpty { "bulunamadı" }),
            Line("Üretici paketleri", hcn.joinToString("\n").ifEmpty { "yok" }),
        )
    }

    @Suppress("DEPRECATION")
    fun networkLines(ctx: Context): List<Line> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return listOf(Line("Ağ", "okunamadı"))
        val info = runCatching { cm.activeNetworkInfo }.getOrNull()
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        return listOf(
            Line("Ağ", if (info == null) "bağlı değil" else "${info.typeName} · ${if (info.isConnected) "bağlı" else info.state.name}"),
            Line("İnternet", when {
                caps == null -> "yok"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "var (doğrulandı)"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "ağ internet diyor, doğrulanmadı"
                else -> "yok"
            }),
        )
    }

    fun bluetoothLines(): List<Line> {
        val ad = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return listOf(Line("Bluetooth", "adaptör yok"))
        val profiles = listOf(1 to "HFP", 2 to "A2DP", 11 to "A2DP alıcı", 12 to "AVRCP kumanda", 16 to "HFP istemci")
        val states = profiles.joinToString(" · ") { (p, n) ->
            "$n " + runCatching { stateName(ad.getProfileConnectionState(p)) }.getOrDefault("?")
        }
        return listOf(
            Line("Bluetooth", runCatching { if (ad.isEnabled) "açık" else "kapalı" }.getOrDefault("okunamadı")),
            Line("Profiller", states),
            Line("Eşleşmiş cihazlar", runCatching { ad.bondedDevices.joinToString { it.name ?: "?" } }.getOrDefault("okunamadı").ifEmpty { "yok" }),
        )
    }

    private fun stateName(s: Int) = when (s) { 0 -> "kopuk"; 1 -> "bağlanıyor"; 2 -> "bağlı"; 3 -> "kopuyor"; else -> "?$s" }

    // ---------- media sessions ----------

    fun listenerEnabled(ctx: Context): Boolean {
        val flat = runCatching { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") }.getOrNull().orEmpty()
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.let { c -> c.packageName == ctx.packageName } == true }
    }

    private fun controllers(ctx: Context): List<MediaController>? {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        return runCatching { msm.getActiveSessions(ComponentName(ctx, KesifBildirim::class.java)) }.getOrNull()
    }

    private val actionNames = listOf(
        PlaybackState.ACTION_PLAY to "play", PlaybackState.ACTION_PAUSE to "pause", PlaybackState.ACTION_PLAY_PAUSE to "play/pause",
        PlaybackState.ACTION_STOP to "stop", PlaybackState.ACTION_SKIP_TO_NEXT to "sonraki", PlaybackState.ACTION_SKIP_TO_PREVIOUS to "önceki",
        PlaybackState.ACTION_SEEK_TO to "sar", PlaybackState.ACTION_FAST_FORWARD to "ileri", PlaybackState.ACTION_REWIND to "geri",
        PlaybackState.ACTION_PLAY_FROM_SEARCH to "aramadan çal", PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "kimlikten çal",
        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "kuyruk", PlaybackState.ACTION_SET_RATING to "puan",
    )

    fun stateText(s: PlaybackState?): String {
        if (s == null) return "durum yok"
        val st = when (s.state) {
            PlaybackState.STATE_PLAYING -> "çalıyor"; PlaybackState.STATE_PAUSED -> "duraklatıldı"; PlaybackState.STATE_STOPPED -> "durdu"
            PlaybackState.STATE_BUFFERING -> "yükleniyor"; PlaybackState.STATE_CONNECTING -> "bağlanıyor"; PlaybackState.STATE_NONE -> "yok"
            PlaybackState.STATE_ERROR -> "hata"; else -> "durum ${s.state}"
        }
        val acts = actionNames.filter { s.actions and it.first != 0L }.joinToString(",") { it.second }
        return "$st · konum ${s.position / 1000} sn · hız ${s.playbackSpeed} · eylemler: ${acts.ifEmpty { "yok" }}"
    }

    private fun bmp(m: MediaMetadata, key: String, name: String): String? =
        runCatching { m.getBitmap(key) }.getOrNull()?.let { b: Bitmap -> "$name ${b.width}×${b.height}" }

    fun metadataText(m: MediaMetadata?): String {
        if (m == null) return "üst veri yok"
        fun t(k: String) = runCatching { m.getString(k) }.getOrNull()
        val art = listOfNotNull(bmp(m, MediaMetadata.METADATA_KEY_ART, "kapak"), bmp(m, MediaMetadata.METADATA_KEY_ALBUM_ART, "albüm kapağı"),
            bmp(m, MediaMetadata.METADATA_KEY_DISPLAY_ICON, "simge"))
        val uris = listOfNotNull(t(MediaMetadata.METADATA_KEY_ART_URI), t(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)).map { "adres " + it.take(60) }
        val dur = runCatching { m.getLong(MediaMetadata.METADATA_KEY_DURATION) }.getOrDefault(0L)
        return "başlık: ${t(MediaMetadata.METADATA_KEY_TITLE) ?: "—"} · sanatçı: ${t(MediaMetadata.METADATA_KEY_ARTIST) ?: "—"} · " +
            "albüm: ${t(MediaMetadata.METADATA_KEY_ALBUM) ?: "—"} · süre: ${if (dur > 0) "${dur / 1000} sn" else "—"} · " +
            "kapak: ${(art + uris).joinToString(", ").ifEmpty { "yok" }} · anahtarlar: ${runCatching { m.keySet().size }.getOrDefault(0)}"
    }

    data class Session(val pkg: String, val meta: String, val state: String)

    /** null when the list cannot be had (listener off). */
    fun sessions(ctx: Context): List<Session>? = controllers(ctx)?.map {
        Session(it.packageName, metadataText(it.metadata), stateText(it.playbackState))
    }

    // ---------- notifications ----------

    private fun interesting(ctx: Context?, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val p = sbn.packageName.orEmpty()
        return p.startsWith("com.hcn.") || p.contains("zlink", true) ||
            n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            n.category == Notification.CATEGORY_TRANSPORT ||
            n.extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("Media")
    }

    private fun summary(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val e = n?.extras
        fun cs(k: String) = runCatching { e?.getCharSequence(k)?.toString() }.getOrNull()
        val actions = n?.actions?.joinToString(",") { it.title?.toString().orEmpty() }.orEmpty()
        return "başlık: ${cs(Notification.EXTRA_TITLE) ?: "—"} · metin: ${cs(Notification.EXTRA_TEXT) ?: "—"} · alt: ${cs(Notification.EXTRA_SUB_TEXT) ?: "—"} · " +
            "oturum: ${if (e?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) "var" else "yok"} · tür: ${n?.category ?: "—"} · " +
            "şablon: ${e?.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$') ?: "—"} · düğmeler: ${actions.ifEmpty { "yok" }}"
    }

    fun setCurrentNotifications(list: List<StatusBarNotification>) {
        synchronized(lock) {
            notifications.clear()
            list.filter { interesting(app, it) }.forEach { notifications["${it.packageName}#${it.id}"] = summary(it) }
        }
    }

    fun onNotification(sbn: StatusBarNotification, posted: Boolean) {
        if (!interesting(app, sbn)) return // other apps' notifications are never kept
        val key = "${sbn.packageName}#${sbn.id}"
        val s = summary(sbn)
        synchronized(lock) { if (posted) notifications[key] = s else notifications.remove(key) }
        note(if (posted) "bildirim" else "bildirim-kalkti", sbn.packageName, s, key = "bildirim:$key")
    }

    fun currentNotifications(): List<Pair<String, String>> = synchronized(lock) { notifications.toList() }

    // ---------- broadcasts and the event log ----------

    private fun flatten(b: Bundle?): String {
        if (b == null) return ""
        return b.keySet().sorted().joinToString(" · ") { k ->
            val v = runCatching {
                @Suppress("DEPRECATION")
                when (val x = b.get(k)) {
                    is BluetoothDevice -> "${runCatching { x.name }.getOrNull() ?: "?"} (${x.address.take(8)}…)"
                    is IntArray -> x.joinToString(",")
                    is ByteArray -> "${x.size} bayt"
                    is Bundle -> "{" + flatten(x) + "}"
                    else -> x.toString()
                }
            }.getOrDefault("?")
            "${k.substringAfterLast('.')}=${v.take(200)}"
        }
    }

    private fun onBroadcast(kind: String, intent: Intent) {
        val action = intent.action ?: return
        note(kind, action, flatten(intent.extras), key = "$kind:$action")
    }

    /** One event: kept in memory for the screen, and written to medya.csv (vendor ones rate-limited). */
    private fun note(kind: String, source: String, value: String, key: String = "$kind:$source") {
        val now = System.currentTimeMillis()
        val write: Boolean
        synchronized(lock) {
            counts[key] = (counts[key] ?: 0) + 1
            recent.addLast("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))} $kind $source ${value.take(160)}")
            while (recent.size > 60) recent.removeFirst()
            val last = lastWrite[key] ?: 0L
            write = measuring || kind != "uretici" || now - last >= 1000
            if (write) lastWrite[key] = now
        }
        if (kind == "bt") Recorder.event(now, "bt", source, "", value.take(200))
        val a = app ?: return
        val id = measureId
        val sc = sec(now)
        if (write) thread(isDaemon = true) { log.append(a, now, listOf(sc, id, kind, source, "", value)) }
    }

    fun recentEvents(): List<String> = synchronized(lock) { recent.toList().reversed() }
    fun eventCounts(): List<Pair<String, Int>> = synchronized(lock) { counts.toList() }

    private fun sec(at: Long) = if (measuring && measureStart > 0) "%.2f".format(Locale.US, (at - measureStart) / 1000.0) else ""

    // ---------- the 30-second measurement ----------

    private fun snapshot(a: Context, tag: String, id: String, start: Long) {
        val now = System.currentTimeMillis()
        val rows = ArrayList<Pair<Long, List<String>>>()
        val sc = "%.2f".format(Locale.US, (now - start) / 1000.0)
        fun row(kind: String, src: String, k: String, v: String) { rows.add(now to listOf(sc, id, kind, src, k, v)) }
        (packageLines(a, fresh = true) + networkLines(a) + bluetoothLines()).forEach { row("$tag-durum", "sistem", it.label, it.value) }
        row("$tag-durum", "sistem", "Bildirim erişimi", if (listenerEnabled(a)) "açık" else "kapalı")
        val s = sessions(a)
        if (s == null) row("$tag-oturum", "medya", "oturumlar", "okunamadı (bildirim erişimi kapalı)")
        else if (s.isEmpty()) row("$tag-oturum", "medya", "oturumlar", "etkin medya oturumu yok")
        else s.forEach { row("$tag-oturum", it.pkg, it.meta, it.state) }
        currentNotifications().forEach { (k, v) -> row("$tag-bildirim", k.substringBefore('#'), k, v) }
        log.appendAll(a, rows)
    }

    /** Starts the 30-second measurement; false when one is already running. */
    fun measure(ctx: Context, done: () -> Unit): Boolean {
        if (measuring) return false
        val a = ctx.applicationContext
        app = a
        measureStart = System.currentTimeMillis()
        measureId = "olcum-" + SimpleDateFormat("HHmmss", Locale.US).format(Date(measureStart))
        measuring = true
        lastMeasure = "Ölçüm sürüyor…"
        val id = measureId
        val start = measureStart
        thread(isDaemon = true) {
            log.append(a, start, listOf("0.00", id, "olcum", "", "", "başladı · Keşif ${BuildConfigCompat.version}"))
            snapshot(a, "ilk", id, start)
            main.post { if (measuring) attachCallbacks(a) }
        }
        for (t in SNAPSHOT_MS until MEASURE_MS step SNAPSHOT_MS) main.postDelayed({
            if (measuring && measureId == id) thread(isDaemon = true) { sessionsSnapshot(a, "ara", id, start) }
        }, t)
        main.postDelayed({ if (measureId == id) stopMeasure(a, "30 sn doldu"); done() }, MEASURE_MS)
        return true
    }

    private fun sessionsSnapshot(a: Context, tag: String, id: String, start: Long) {
        val now = System.currentTimeMillis()
        val s = sessions(a) ?: return
        val sc = "%.2f".format(Locale.US, (now - start) / 1000.0)
        log.appendAll(a, s.map { now to listOf(sc, id, "$tag-oturum", it.pkg, it.meta, it.state) })
    }

    private fun attachCallbacks(a: Context) {
        val msm = a.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val cn = ComponentName(a, KesifBildirim::class.java)
        fun watch(list: List<MediaController>?) {
            callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
            callbacks.clear()
            list.orEmpty().forEach { c ->
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = note("oturum-ustveri", c.packageName, metadataText(metadata))
                    override fun onPlaybackStateChanged(state: PlaybackState?) = note("oturum-durum", c.packageName, stateText(state))
                    override fun onSessionDestroyed() = note("oturum-bitti", c.packageName, "")
                }
                runCatching { c.registerCallback(cb, main); callbacks.add(c to cb) }
            }
        }
        val l = MediaSessionManager.OnActiveSessionsChangedListener { list ->
            note("oturumlar", "medya", list.orEmpty().joinToString { it.packageName }.ifEmpty { "yok" })
            if (measuring) watch(list)
        }
        runCatching {
            msm.addOnActiveSessionsChangedListener(l, cn, main)
            sessionsListener = l
            watch(msm.getActiveSessions(cn))
        }.onFailure { note("hata", "medya", "oturumlar izlenemedi: ${it.javaClass.simpleName} (bildirim erişimi kapalı olabilir)") }
    }

    private fun stopMeasure(a: Context, why: String) {
        if (!measuring) return
        val msm = a.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionsListener?.let { l -> runCatching { msm?.removeOnActiveSessionsChangedListener(l) } }
        sessionsListener = null
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        val now = System.currentTimeMillis()
        val id = measureId
        val start = measureStart
        measuring = false
        measureId = ""
        lastMeasure = "Son ölçüm $id: $why · medya.csv"
        thread(isDaemon = true) {
            snapshot(a, "son", id, start)
            log.append(a, now, listOf("%.2f".format(Locale.US, (now - start) / 1000.0), id, "olcum", "", "", "bitti · $why"))
        }
    }

    fun sectionLines(ctx: Context): List<Line> =
        listOf(Line("Bildirim erişimi", if (listenerEnabled(ctx)) "açık" else "kapalı"), Line("Üretici yayınları", vendorScan)) +
            packageLines(ctx) + networkLines(ctx) + bluetoothLines()
}

class MediaPage(private val a: android.app.Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: android.widget.LinearLayout
    private val access = ui.label("", 15f, Palette.text)
    private val sessions = ui.label("", 14f, Palette.text, mono = true)
    private val notes = ui.label("", 13f, Palette.text, mono = true)
    private val system = ui.label("", 13f, Palette.text, mono = true)
    private val events = ui.label("", 12f, Palette.muted, mono = true)
    private val measureStatus = ui.label("", 14f, Palette.muted)
    private val measureButton = ui.button("30 sn ölçümü başlat", primary = true) { measure() }
    private var lastSlow = 0L

    init {
        val (r, content) = ui.frame("Medya ve bağlantı", "Yalnız okur · medya.csv", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("Bildirim erişimi", 17f, Palette.accent, bold = true))
            addView(ui.label("Medya oturumlarını görmek için gerekli. Keşif bildirimlere dokunmaz, yanıtlamaz, silmez; yalnız medya bildirimlerini ve ZLINK ile üretici uygulamalarınınkini okur.", 13f, Palette.faint).apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) })
            addView(access.apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(ui.button("Bildirim erişimi ayarını aç", primary = false) {
                runCatching { a.startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
                    .onFailure { android.widget.Toast.makeText(a, "Ayar açılamadı", android.widget.Toast.LENGTH_SHORT).show() }
            }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)))
        }
        ui.card(content) {
            addView(ui.label("30 saniyelik ölçüm", 17f, Palette.accent, bold = true))
            addView(ui.label("Müzik çalarken başlat. Başta, her 5 sn’de ve sonda oturumların, bildirimlerin, Bluetooth ve ağ durumunun fotoğrafını; arada her değişikliği medya.csv’ye yazar.", 13f, Palette.faint).apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) })
            addView(measureStatus.apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(measureButton, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(60)))
        }
        ui.card(content) { addView(ui.label("Medya oturumları", 17f, Palette.accent, bold = true)); addView(sessions.apply { setPadding(0, ui.dp(6), 0, 0) }) }
        ui.card(content) { addView(ui.label("Medya bildirimleri", 17f, Palette.accent, bold = true)); addView(notes.apply { setPadding(0, ui.dp(6), 0, 0) }) }
        ui.card(content) { addView(ui.label("Paketler, ağ, Bluetooth, üretici yayınları", 17f, Palette.accent, bold = true)); addView(system.apply { setPadding(0, ui.dp(6), 0, 0) }) }
        ui.card(content) { addView(ui.label("Olay günlüğü (son 60)", 17f, Palette.accent, bold = true)); addView(events.apply { setPadding(0, ui.dp(6), 0, 0) }) }
    }

    override fun onShow() {
        MediaProbe.scanVendor(a) { }
        lastSlow = 0L
    }

    private fun measure() {
        if (!MediaProbe.measure(a) { tick() }) android.widget.Toast.makeText(a, "Ölçüm zaten sürüyor", android.widget.Toast.LENGTH_SHORT).show()
        tick()
    }

    override fun tick() {
        val now = System.currentTimeMillis()
        val enabled = MediaProbe.listenerEnabled(a)
        access.text = if (enabled) "Açık" + (if (MediaProbe.listenerConnected) " · bağlı" else " · bağlanması bekleniyor") else "Kapalı: aşağıdaki düğmeyle “Kokpit Keşif”i aç."
        measureButton.isEnabled = !MediaProbe.measuring
        measureButton.alpha = if (MediaProbe.measuring) 0.5f else 1f
        measureStatus.text = MediaProbe.lastMeasure ?: "Henüz ölçüm yok." + (MediaProbe.log.lastError?.let { " · yazma hatası: $it" } ?: "")
        events.text = MediaProbe.recentEvents().joinToString("\n").ifEmpty { "Henüz olay yok." }
        notes.text = MediaProbe.currentNotifications().joinToString("\n\n") { (k, v) -> "$k\n  $v" }
            .ifEmpty { if (enabled) "Medya bildirimi yok." else "Bildirim erişimi kapalı." }
        // Sessions, packages and network: once a second is plenty and keeps the screen light.
        if (now - lastSlow < 1000) return
        lastSlow = now
        val s = MediaProbe.sessions(a)
        sessions.text = when {
            s == null -> "Okunamadı: bildirim erişimi kapalı."
            s.isEmpty() -> "Etkin medya oturumu yok."
            else -> s.joinToString("\n\n") { "${it.pkg}\n  ${it.meta}\n  ${it.state}" }
        }
        system.text = (MediaProbe.packageLines(a) + MediaProbe.networkLines(a) + MediaProbe.bluetoothLines() +
            Line("Üretici yayınları", MediaProbe.vendorScan) +
            MediaProbe.eventCounts().takeLast(12).map { Line("Sayaç", "${it.first}: ${it.second}") })
            .joinToString("\n") { "${it.label}: ${it.value}" }
    }
}
