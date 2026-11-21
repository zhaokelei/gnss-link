package top.wxip.gpslink.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 极小 FTP 客户端。
 *
 * 只为自更新服务：连接、列目录、下载、读文本，四件事之外的协议（FTPS、MLSD、
 * 断点续传、主动模式）一律不支持，也没有实现的必要。
 *
 * 被动模式优先 PASV；部分服务器只认 EPSV，故 PASV 失败时降级重试一次。
 * 控制连接用 ISO-8859-1 编解码，保证命令按字节原样传输。
 */
class FtpClient(
    private val host: String,
    private val port: Int = 21,
    private val user: String,
    private val pass: String,
    private val timeoutMs: Int = 15000,
) : Closeable {

    private val ctrl = Socket()
    private lateinit var reader: BufferedReader
    private lateinit var writer: BufferedWriter
    private var loggedIn = false

    data class RemoteFile(val name: String, val size: Long, val isDir: Boolean)

    fun connect(): FtpClient {
        ctrl.connect(InetSocketAddress(host, port), timeoutMs)
        ctrl.soTimeout = timeoutMs
        reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), Charsets.ISO_8859_1))
        writer = BufferedWriter(OutputStreamWriter(ctrl.getOutputStream(), Charsets.ISO_8859_1))

        expect(readReply(), 220)
        command("USER $user", 331, 230)
        if (lastCode == 331) command("PASS $pass", 230)
        loggedIn = true
        return this
    }

    /** 列目录。只解析 Unix 风格 LIST 输出，够用。 */
    fun list(dir: String): List<RemoteFile> {
        openPassive()
        val reply = command("LIST $dir", 150, 125)
        if (lastCode == 125 || lastCode == 150) {
            val files = ArrayList<RemoteFile>()
            dataSocket!!.getInputStream().bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
                for (line in lines) {
                    val f = parseListLine(line) ?: continue
                    files += f
                }
            }
            closeData()
            expectBlocking()
            return files
        }
        // 有些服务器先回 2xx 再回数据，忽略这种边角情况
        check(reply.trim().startsWith("2")) { "LIST 失败：$reply" }
        return emptyList()
    }

    /** 下载远程文件到本地，按 totalBytes 汇报百分比；totalBytes<=0 时只在结束时报 100 */
    fun download(remote: String, dest: File, totalBytes: Long = -1L, onProgress: (Int) -> Unit = {}) {
        dest.parentFile?.mkdirs()
        typeImage()
        openPassive()
        command("RETR $remote", 150, 125)
        require(lastCode == 150 || lastCode == 125) { "RETR 被拒绝：$lastReply" }

        var written = 0L
        var lastPct = -1
        dataSocket!!.getInputStream().use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (totalBytes > 0) {
                        val pct = (written * 100 / totalBytes).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        closeData()
        expectBlocking()
        onProgress(100)
    }

    /** 读远程文本文件（更新日志） */
    fun readText(remote: String): String {
        typeImage()
        openPassive()
        command("RETR $remote", 150, 125)
        require(lastCode == 150 || lastCode == 125) { "RETR 被拒绝：$lastReply" }
        val text = dataSocket!!.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        closeData()
        expectBlocking()
        return text
    }

    override fun close() {
        try {
            if (loggedIn) command("QUIT", 221, 220)
        } catch (_: Throwable) {
            // 断开时服务器可能已关连接，无需处理
        }
        closeData()
        runCatching { ctrl.close() }
    }

    /* ==================== 内部 ==================== */

    private var dataSocket: Socket? = null
    private var lastCode = 0
    private var lastReply = ""

    private fun typeImage() {
        command("TYPE I", 200)
    }

    private fun openPassive() {
        val hostIp: String
        val dataPort: Int
        val reply = command("PASV", 227)
        val m = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(reply)
        if (m != null) {
            val g = m.groupValues
            hostIp = "${g[1]}.${g[2]}.${g[3]}.${g[4]}"
            dataPort = g[5].toInt() * 256 + g[6].toInt()
        } else {
            val epsv = command("EPSV", 229)
            val p = Regex("\\|\\|\\|(\\d+)\\|").find(epsv)
                ?: throw IllegalStateException("无法进入被动模式：$lastReply")
            hostIp = ctrl.inetAddress.hostAddress ?: host
            dataPort = p.groupValues[1].toInt()
        }
        // 被动模式下服务器可能回内网地址（如 10.x/192.168.x），此时改用控制连接的对端 IP
        val target = if (hostIp.startsWith("10.") || hostIp.startsWith("192.168.") ||
            hostIp.startsWith("172.") || hostIp == "127.0.0.1"
        ) ctrl.inetAddress.hostAddress ?: host else hostIp
        dataSocket = Socket().apply {
            connect(InetSocketAddress(target, dataPort), timeoutMs)
            soTimeout = timeoutMs
        }
    }

    private fun closeData() {
        runCatching { dataSocket?.close() }
        dataSocket = null
    }

    /** RETR/LIST 传完后服务器会再发一个 226，读掉它 */
    private fun expectBlocking() {
        if (lastCode == 150 || lastCode == 125 || lastCode == 226) {
            val r = readReply()
            val code = r.take(3).toIntOrNull() ?: 0
            if (code in 200..299) {
                lastCode = code
                lastReply = r
            }
        }
    }

    private fun command(cmd: String, vararg okCodes: Int): String {
        writer.write("$cmd\r\n")
        writer.flush()
        val reply = readReply()
        lastReply = reply
        lastCode = reply.take(3).toIntOrNull() ?: 0
        if (okCodes.isNotEmpty() && lastCode !in okCodes) {
            throw IllegalStateException("FTP $cmd 失败：${reply.trim()}")
        }
        return reply
    }

    /** 读一条完整响应，多行响应（123-xxx）读到 123 空格为止 */
    private fun readReply(): String {
        val sb = StringBuilder()
        val first = reader.readLine() ?: throw IllegalStateException("FTP 连接被关闭")
        sb.append(first).append('\n')
        if (first.length >= 4 && first[3] == '-') {
            val code = first.take(3)
            while (true) {
                val line = reader.readLine() ?: break
                sb.append(line).append('\n')
                if (line.startsWith("$code ")) break
            }
        }
        return sb.toString()
    }

    private fun expect(reply: String, code: Int) {
        val got = reply.take(3).toIntOrNull() ?: 0
        if (got != code) throw IllegalStateException("FTP 期望 $code，收到 ${reply.trim()}")
    }

    /**
     * 解析一条 LIST 输出：
     * -rw-r--r-- 1 1001 www 4898766 Nov 21 16:55 gnss-link-1.2.2.apk
     * 前 8 段是固定字段，第 9 段起才是文件名（文件名可含空格）。
     */
    private fun parseListLine(line: String): RemoteFile? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("total ")) return null
        val parts = t.split(Regex("\\s+"), limit = 9)
        if (parts.size < 9) return null
        val perm = parts[0]
        val isDir = perm.startsWith("d")
        val size = parts[4].toLongOrNull() ?: return null
        val name = parts[8].substringBefore(" -> ") // 跳过软链接的 "-> 目标"
        if (name == "." || name == "..") return null
        return RemoteFile(name, size, isDir)
    }
}
