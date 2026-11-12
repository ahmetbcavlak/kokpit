package dev.kokpit.kesif

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Serial port permission check: who owns the MCU/CAN serial device nodes and who may open them.
 *
 * Only metadata is read: Os.stat() and `ls -lZ` (both stat the node; neither opens it). No port is
 * opened, read or written. On SELinux-enforcing units even stat may be denied; that is reported.
 */
object SerialPorts {

    val PATHS = listOf("/dev/ttyMT1", "/dev/ttyMT2", "/dev/ttyS2", "/dev/ttyS3")

    /** Android's fixed user/group ids (system/core/include/private/android_filesystem_config.h). */
    private val AID = mapOf(
        0 to "root", 1000 to "system", 1001 to "radio", 1002 to "bluetooth", 1003 to "graphics",
        1004 to "input", 1005 to "audio", 1006 to "camera", 1007 to "log", 1008 to "compass",
        1009 to "mount", 1010 to "wifi", 1013 to "media", 1014 to "dhcp", 1015 to "sdcard_rw",
        1017 to "keystore", 1018 to "usb", 1021 to "gps", 1023 to "media_rw", 1024 to "mtp",
        1036 to "logd", 1041 to "audioserver", 1047 to "cameraserver", 2000 to "shell",
        2001 to "cache", 3001 to "net_bt_admin", 3002 to "net_bt", 3003 to "inet", 9997 to "everybody",
    )

    data class Result(val path: String, val lines: List<Line>)

    fun check(): List<Result> = PATHS.map { path ->
        val lines = ArrayList<Line>()
        try {
            val st = Os.stat(path)
            val mode = st.st_mode
            // A real stat always carries a file type; an all-zero answer is not trustworthy.
            if (mode and OsConstants.S_IFMT == 0) throw IllegalStateException("stat boş döndü")
            lines += Line("İzinler", permString(mode) + " (" + "%04o".format(mode and 0xFFF) + ")")
            lines += Line("Sahip (UID)", "${st.st_uid} ${name(st.st_uid)}")
            lines += Line("Grup (GID)", "${st.st_gid} ${name(st.st_gid)}")
            lines += Line("Tür", when {
                OsConstants.S_ISCHR(mode) -> "karakter aygıtı"
                OsConstants.S_ISBLK(mode) -> "blok aygıtı"
                OsConstants.S_ISREG(mode) -> "düz dosya"
                else -> "diğer"
            })
            if (OsConstants.S_ISCHR(mode)) lines += Line("Aygıt no", "${(st.st_rdev shr 8) and 0xFFF}, ${(st.st_rdev and 0xFF) or ((st.st_rdev shr 12) and 0xFFF00)}")
            // What a normal app such as Keşif could do, from the bits alone (SELinux may still deny).
            val other = mode and 0x7
            lines += Line("Diğer kullanıcılar", listOfNotNull(
                if (other and 4 != 0) "okuyabilir" else null,
                if (other and 2 != 0) "yazabilir" else null,
            ).ifEmpty { listOf("erişemez") }.joinToString(", "))
        } catch (e: Throwable) {
            val msg = (e as? android.system.ErrnoException)?.let { errnoName(it.errno) } ?: e.javaClass.simpleName
            lines += Line("Os.stat", "okunamadı: $msg" + if (!File(path).exists() && msg.contains("ENOENT")) " (aygıt yok)" else "")
        }
        lines += Line("ls -lZ", ls(path))
        Result(path, lines)
    }

    fun sections(): List<Section> = check().map { Section("Seri port · ${it.path}", it.lines) }

    private fun ls(path: String): String = runCatching {
        val p = ProcessBuilder("ls", "-lZ", path).redirectErrorStream(true).start()
        val dog = kotlin.concurrent.thread(isDaemon = true) { runCatching { Thread.sleep(3000); p.destroy() } }
        try { p.inputStream.bufferedReader().readText().trim() } finally { dog.interrupt(); p.destroy() }
    }.getOrElse { "çalıştırılamadı: ${it.javaClass.simpleName}" }.ifEmpty { "(boş çıktı)" }

    private fun name(id: Int) = AID[id]?.let { "($it)" } ?: if (id >= 10000) "(uygulama)" else ""

    private fun errnoName(errno: Int): String = when (errno) {
        OsConstants.ENOENT -> "ENOENT (yok)"
        OsConstants.EACCES -> "EACCES (izin yok, büyük olasılıkla SELinux)"
        OsConstants.EPERM -> "EPERM (izin yok)"
        else -> "errno $errno"
    }

    /** e.g. crw-rw---- from st_mode. */
    fun permString(mode: Int): String {
        val type = when {
            OsConstants.S_ISCHR(mode) -> 'c'
            OsConstants.S_ISBLK(mode) -> 'b'
            OsConstants.S_ISDIR(mode) -> 'd'
            OsConstants.S_ISLNK(mode) -> 'l'
            OsConstants.S_ISFIFO(mode) -> 'p'
            OsConstants.S_ISSOCK(mode) -> 's'
            else -> '-'
        }
        val bits = "rwxrwxrwx"
        return type + (8 downTo 0).joinToString("") { i -> if (mode and (1 shl i) != 0) bits[8 - i].toString() else "-" }
    }
}
