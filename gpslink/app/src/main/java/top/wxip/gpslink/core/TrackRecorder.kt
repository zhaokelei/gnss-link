package top.wxip.gpslink.core

import android.content.Context
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轨迹记录与回放。
 *
 * 记录格式：JSON Lines，每行是一个 LOC 帧（复用 [LocationFrame.toJson]），
 * 额外带一个 `dt` 字段 —— 相对首帧的毫秒偏移，用单调时钟计算，
 * 因此回放时的节奏与真实采集完全一致，且不受系统时间被改动的影响。
 *
 * 轨迹存在的意义：链路出问题时可以离线复现，不必再让两台真机对着跑。
 */
object TrackRecorder {

    private const val DIR = "tracks"
    private const val MAX_POINTS = 200_000

    private var writer: BufferedWriter? = null
    private var current: File? = null
    private var firstAt = 0L
    private var points = 0

    val isRecording: Boolean get() = writer != null
    val currentFile: File? get() = current
    val pointCount: Int get() = points

    fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 开始记录，返回新建的轨迹文件；失败返回 null */
    @Synchronized
    fun start(ctx: Context): File? {
        stop()
        val name = "track-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        val file = File(dir(ctx), name)
        return try {
            writer = file.bufferedWriter()
            current = file
            firstAt = 0L
            points = 0
            file
        } catch (t: Throwable) {
            writer = null
            current = null
            null
        }
    }

    /** 追加一帧；未在记录状态时静默忽略 */
    @Synchronized
    fun appendFrame(frame: LocationFrame) {
        val w = writer ?: return
        if (points >= MAX_POINTS) {
            stop()
            return
        }
        try {
            val now = SystemClock.elapsedRealtime()
            if (firstAt == 0L) firstAt = now
            val o = frame.toJson("", 0L)
            o.put("dt", (now - firstAt).coerceAtLeast(0L))
            w.write(o.toString())
            w.newLine()
            points++
            // 每 20 帧落一次盘，兼顾崩溃可恢复与写入开销
            if (points % 20 == 0) w.flush()
        } catch (_: Throwable) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        if (points == 0) current?.delete()
        current = null
        firstAt = 0L
        points = 0
    }

    data class Info(
        val file: File,
        val points: Int,
        val durationMs: Long,
        val bytes: Long
    ) {
        val name: String get() = file.name
    }

    /** 扫描轨迹目录，按时间倒序（新的在前） */
    fun list(ctx: Context): List<Info> = try {
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.mapNotNull { f ->
                val frames = read(f)
                if (frames.isEmpty()) null
                else Info(
                    file = f,
                    points = frames.size,
                    durationMs = frames.last().first,
                    bytes = f.length()
                )
            }
            ?.sortedByDescending { it.file.name }
            ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** 读出「相对毫秒偏移 → 帧」列表，已按时间升序 */
    fun read(file: File): List<Pair<Long, LocationFrame>> = try {
        file.useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                try {
                    val o = org.json.JSONObject(line)
                    val frame = LocationFrame.fromJson(o) ?: return@mapNotNull null
                    o.optLong("dt", 0L) to frame
                } catch (_: Throwable) {
                    null
                }
            }.sortedBy { it.first }.toList()
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun delete(file: File): Boolean = try {
        if (current?.absolutePath == file.absolutePath) stop()
        file.delete()
    } catch (_: Throwable) {
        false
    }
}
