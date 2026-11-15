package dev.kokpit.kesif

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * An append-only CSV file in the app's own kayit/ folder (Keşif 0.8 logs). Every row is flushed and
 * synced, so a power cut loses at most the row being written. A half line left by a power cut is
 * closed with a newline before the next row. Nothing is written outside the app's folder.
 */
class CsvLog(private val name: String, private val header: String) {

    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile var lastError: String? = null; private set

    fun file(ctx: Context) = File(ctx.filesDir, "kayit/$name")

    /** The time as written in the first column. */
    fun time(at: Long): String = synchronized(lock) { stamp.format(Date(at)) }

    fun append(ctx: Context, at: Long, cells: List<String>) = appendAll(ctx, listOf(at to cells))

    /** One writer thread per file: rows stay in order and a burst of events never piles up threads. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "kesif-csv-$name").apply { isDaemon = true } }

    /** Queues the rows for the writer thread; never blocks the caller. */
    fun post(ctx: Context, rows: List<Pair<Long, List<String>>>) {
        if (rows.isEmpty()) return
        val app = ctx.applicationContext
        runCatching { writer.execute { appendAll(app, rows) } }
    }

    fun post(ctx: Context, at: Long, cells: List<String>) = post(ctx, listOf(at to cells))

    /** Several rows in one write and one sync. */
    fun appendAll(ctx: Context, rows: List<Pair<Long, List<String>>>) {
        if (rows.isEmpty()) return
        synchronized(lock) {
            runCatching {
                val f = file(ctx).apply { parentFile?.mkdirs() }
                val isNew = !f.exists() || f.length() == 0L
                val needsNewline = !isNew && runCatching {
                    RandomAccessFile(f, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
                }.getOrDefault(false)
                val sb = StringBuilder()
                if (isNew) sb.append("﻿").append(header).append('\n') // BOM: Excel shows ı, ş, ğ
                if (needsNewline) sb.append('\n')
                for ((at, cells) in rows) sb.append((listOf(stamp.format(Date(at))) + cells).joinToString(",") { csvCell(it) }).append('\n')
                FileOutputStream(f, true).use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                lastError = null
            }.onFailure { lastError = "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        }
    }
}
