package dev.kokpit.kesif

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * "Kısa ses" (Keşif 0.9, wave C: sound design). A 300 ms tone, played two ways:
 *  A) USAGE_ASSISTANCE_SONIFICATION with AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, focus released after;
 *  B) the same sound with no focus request.
 * Before, during and after, the music state is written: AudioManager.isMusicActive and, when
 * notification access is on, the playing state of every media session. The user judges by ear and
 * taps what they heard. No volume or setting is changed.
 */
object ShortSound {
    const val MS = 300
    private const val RATE = 44_100

    @Volatile var playing = false; private set
    @Volatile var last: List<String> = emptyList(); private set
    @Volatile var lastMode = ""; private set

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** A 300 ms, 880 Hz tone with 15 ms fades, as a 16-bit mono WAV in the cache (written once). */
    fun file(ctx: Context): File {
        val f = File(ctx.cacheDir, "ses/kisa-ses-300ms.wav")
        if (f.isFile && f.length() > 44) return f
        f.parentFile?.mkdirs()
        val n = RATE * MS / 1000
        val fade = RATE * 15 / 1000
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            pcm.putShort((sin(2 * PI * 880 * i / RATE) * env * 0.6 * Short.MAX_VALUE).toInt().toShort())
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + n * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(n * 2)
        }
        FileOutputStream(f).use { it.write(h.array()); it.write(pcm.array()) }
        return f
    }

    private fun musicState(ctx: Context): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sessions = MediaProbe.sessions(ctx)?.joinToString(" | ") { "${it.pkg}: ${it.state.substringBefore(" ·")}" }
        return "müzik çalıyor: ${if (am.isMusicActive) "evet" else "hayır"} · oturumlar: ${sessions ?: "okunamadı (bildirim erişimi)"}"
    }

    fun volumes(ctx: Context): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun v(s: Int) = runCatching { "${am.getStreamVolume(s)}/${am.getStreamMaxVolume(s)}" }.getOrDefault("?")
        return "sistem ${v(AudioManager.STREAM_SYSTEM)} · medya ${v(AudioManager.STREAM_MUSIC)} · bildirim ${v(AudioManager.STREAM_NOTIFICATION)}"
    }

    /** Plays once; [withFocus] chooses A or B. Snapshots at 0, 150, 600 and 1500 ms. */
    fun play(ctx: Context, withFocus: Boolean, done: () -> Unit): Boolean {
        if (playing) return false
        playing = true
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val mode = if (withFocus) "A · odak (geçici, kısarak)" else "B · odaksız"
        lastMode = mode
        val lines = ArrayList<String>()
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var req: AudioFocusRequest? = null
        var player: MediaPlayer? = null
        val t0 = System.currentTimeMillis()
        val ended = java.util.concurrent.atomic.AtomicBoolean(false)
        fun snap(tag: String) {
            thread(isDaemon = true) {
                val s = runCatching { musicState(app) }.getOrDefault("okunamadı")
                synchronized(lines) { lines.add("$tag: $s") }
                Yol.row(app, "ses", mode, tag, s)
            }
        }
        fun finish(note: String) {
            if (!ended.compareAndSet(false, true)) return
            runCatching { player?.release() }
            if (Build.VERSION.SDK_INT >= 26) req?.let { r -> runCatching { am.abandonAudioFocusRequest(r) } }
            Yol.row(app, "ses", mode, "bitti", note)
            main.postDelayed({
                last = listOf(mode) + synchronized(lines) { lines.toList() }
                playing = false
                done()
            }, 1600)
        }
        try {
            snap("önce")
            Yol.row(app, "ses", mode, "ses düzeyleri", volumes(app))
            if (withFocus && Build.VERSION.SDK_INT >= 26) {
                req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
                val res = am.requestAudioFocus(req!!)
                val text = when (res) { AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "verildi"; AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "reddedildi"; else -> "gecikmeli ($res)" }
                synchronized(lines) { lines.add("odak: $text") }
                Yol.row(app, "ses", mode, "odak isteği", text)
            }
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file(app).path)
                setOnCompletionListener { finish("${System.currentTimeMillis() - t0} ms") }
                setOnErrorListener { _, w, x -> finish("oynatma hatası $w/$x"); true }
                prepare()
                start()
            }
            main.postDelayed({ snap("çalarken (150 ms)") }, 150)
            main.postDelayed({ snap("sonra (600 ms)") }, 600)
            main.postDelayed({ snap("sonra (1500 ms)") }, 1500)
            main.postDelayed({ finish("zaman aşımı") }, 3000)
        } catch (t: Throwable) {
            finish("hata: ${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
        return true
    }

    fun rate(ctx: Context, what: String) = Yol.row(ctx, "ses", lastMode.ifEmpty { "?" }, "kulakla", what)
}

class SoundPage(private val a: Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val vol = ui.label("", 14f, Palette.text, mono = true)
    private val result = ui.label("Henüz çalınmadı.", 14f, Palette.text, mono = true)

    init {
        val (r, content) = ui.frame("5 · Kısa ses", "300 ms · iki yol · kulakla değerlendir", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("Spotify çalarken önce A’yı, sonra B’yi dene. Her denemeden sonra ne duyduğuna dokun.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(vol.apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.button("A · odakla\n(müziği kısar)", primary = true, size = 16f) { play(true) }, LinearLayout.LayoutParams(0, ui.dp(72), 1f).apply { rightMargin = ui.dp(10) })
                addView(ui.button("B · odaksız", primary = true, size = 16f) { play(false) }, LinearLayout.LayoutParams(0, ui.dp(72), 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(72)).apply { bottomMargin = ui.dp(10) })
            val ratings = listOf("Müzik kısıldı", "Müzik durdu", "Müzik değişmedi", "Ses duyulmadı")
            for (row in ratings.chunked(2)) addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { i, t ->
                    addView(ui.button(t, primary = false, size = 15f) { ShortSound.rate(a, t); android.widget.Toast.makeText(a, "Yazıldı: $t", android.widget.Toast.LENGTH_SHORT).show() },
                        LinearLayout.LayoutParams(0, ui.dp(56), 1f).apply { if (i == 0) rightMargin = ui.dp(10) })
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(56)).apply { bottomMargin = ui.dp(10) })
        }
        ui.card(content) {
            addView(ui.label("Son deneme", 17f, Palette.accent, bold = true))
            addView(result.apply { setPadding(0, ui.dp(6), 0, 0) })
        }
    }

    private fun play(focus: Boolean) {
        if (ShortSound.play(a, focus) { tick() }) result.text = "Çalıyor…"
    }

    private var lastVol = 0L
    override fun tick() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastVol > 2000) { lastVol = now; vol.text = "Ses düzeyleri: " + ShortSound.volumes(a) }
        if (!ShortSound.playing && ShortSound.last.isNotEmpty()) result.text = ShortSound.last.joinToString("\n")
    }
}
