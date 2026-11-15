package dev.kokpit.kesif

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Finds broadcast-action-like string constants in a vendor app's code, so Keşif can listen for the
 * vendor's own broadcasts (Bluetooth, music) without knowing their names in advance.
 *
 * Read-only: the APK (or, for a pre-optimised system app whose APK holds no classes.dex, the .vdex
 * next to it) is opened for reading and only the dex string table is parsed. No vendor code is
 * loaded or run.
 */
object DexStrings {

    private const val MAX_DEX = 16L * 1024 * 1024

    private val dotted = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){1,8}$")
    private val upper = Regex("^[A-Z][A-Z0-9_]{5,80}$")
    private val keywords = Regex("bt|blue|a2dp|avrcp|music|media|id3|song|track|play|phone|call|zlink|carplay|link|connect", RegexOption.IGNORE_CASE)
    private val excludedPrefix = listOf("android.permission.", "android.intent.extra.", "android.intent.category.", "java.", "javax.",
        "kotlin.", "kotlinx.", "androidx.", "android.support.", "com.google.", "org.", "dalvik.", "sun.", "libcore.", "okhttp3.", "retrofit2.")
    private val fileExt = Regex("\\.(png|jpe?g|webp|gif|xml|so|java|kt|mp3|mp4|wav|ogg|db|txt|json|html?|js|css|apk|dex|ttf|otf|properties|conf|cfg|bin|dat)$", RegexOption.IGNORE_CASE)

    /** Whether a string looks like a broadcast action worth listening to. */
    fun looksLikeAction(s: String): Boolean {
        if (s.length < 6 || s.length > 100) return false
        if (!keywords.containsMatchIn(s)) return false
        if (excludedPrefix.any { s.startsWith(it) } || fileExt.containsMatchIn(s)) return false
        return dotted.matches(s) || (upper.matches(s) && s.contains("ACTION"))
    }

    /**
     * Calls [sink] with the ASCII strings of every dex image of an app: classes*.dex from the APK,
     * else the dex images inside the .vdex files beside it (pre-optimised system apps). One image is
     * held at a time; a file that is too large or cannot be read is skipped.
     */
    fun forEachString(apk: String, sink: (String) -> Unit) {
        var any = false
        runCatching {
            ZipFile(apk).use { z ->
                for (e in z.entries().toList()) {
                    if (!e.name.matches(Regex("classes\\d*\\.dex")) || e.size !in 1..MAX_DEX) continue
                    // Exactly the entry's size, read once: no growing buffer and copies on a 32-bit heap.
                    val dex = ByteArray(e.size.toInt())
                    z.getInputStream(e).use { java.io.DataInputStream(it).readFully(dex) }
                    any = true
                    strings(dex, 0).forEach(sink)
                }
            }
        }
        if (any) return
        val dir = File(apk).parentFile ?: return
        val base = File(apk).nameWithoutExtension
        val vdex = dir.resolve("oat").listFiles()?.flatMap { arch -> arch.listFiles()?.toList().orEmpty() }
            ?.filter { it.isFile && it.name == "$base.vdex" && it.length() in 1..MAX_DEX }.orEmpty()
        for (f in vdex) runCatching {
            val bytes = RandomAccessFile(f, "r").use { r -> ByteArray(r.length().toInt()).also { r.readFully(it) } }
            // A vdex holds the original dex files whole; each starts with "dex\n0" and its own size.
            var i = 0
            while (i < bytes.size - 0x70) {
                if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'e'.code.toByte() && bytes[i + 2] == 'x'.code.toByte() &&
                    bytes[i + 3] == '\n'.code.toByte() && bytes[i + 4] == '0'.code.toByte()) {
                    val size = le32(bytes, i + 0x20)
                    if (size >= 0x70 && size <= bytes.size - i) {
                        strings(bytes, i, size).forEach(sink); i += size; continue
                    }
                }
                i++
            }
        }
    }

    /**
     * The ASCII strings of one dex string table that starts at [base] in [buf] and is [size] bytes
     * long. Offsets in a dex are relative to its start. Malformed input gives what could be read.
     */
    fun strings(buf: ByteArray, base: Int, size: Int = buf.size - base): List<String> {
        val out = ArrayList<String>()
        val end = base + size
        if (size < 0x70 || end > buf.size) return out
        val count = le32(buf, base + 0x38)
        val idsOff = le32(buf, base + 0x3C)
        if (count <= 0 || idsOff <= 0 || idsOff.toLong() + count.toLong() * 4 > size) return out
        for (n in 0 until count) {
            val rel = le32(buf, base + idsOff + n * 4)
            if (rel <= 0 || rel >= size) continue
            var p = base + rel
            // uleb128 utf16 length, then MUTF-8 bytes up to a 0.
            var len = 0; var shift = 0
            while (p < end && shift < 35) {
                val b = buf[p++].toInt() and 0xFF
                len = len or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (len < 6 || len > 100 || p + len > end) continue
            var ascii = true
            for (k in 0 until len) { val b = buf[p + k].toInt(); if (b <= 0x20 || b >= 0x7F) { ascii = false; break } }
            if (ascii) out.add(String(buf, p, len, Charsets.US_ASCII))
        }
        return out
    }

    /** Action-like strings from all the given APKs, at most [limit], ACTION-ish names first. */
    fun actions(apks: List<String>, limit: Int = 150): List<String> {
        val found = LinkedHashSet<String>()
        for (apk in apks) try {
            forEachString(apk) { if (looksLikeAction(it)) found.add(it) }
        } catch (t: Throwable) {
            // OutOfMemoryError on a huge app: skip that app, keep the rest.
        }
        return found.sortedWith(compareBy<String> {
            when {
                it.contains("action", ignoreCase = true) -> 0
                it.contains(".bt", ignoreCase = true) || it.contains("blue", ignoreCase = true) -> 1
                else -> 2
            }
        }.thenBy { it }).take(limit)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        if (o < 0 || o + 4 > b.size) -1
        else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
