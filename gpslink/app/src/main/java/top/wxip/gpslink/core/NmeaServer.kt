package top.wxip.gpslink.core

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * NMEA over TCP 服务端。
 *
 * 用 10110 端口 —— 这是 NMEA over IP 的行业约定端口，车机导航、船用设备、
 * 上位机软件默认都认它。
 *
 * 为什么走 TCP 而不是 USB：Android 的 UsbManager 只有 host 与 AOA accessory
 * 两种角色，没有把手机变成 CDC-ACM 从设备的公开 API（`/dev/ttyGS0` 需要 root）。
 * 所以「手机输出 NMEA 给车机」在免 root 前提下，只有网络与蓝牙两条路可走。
 */
class NmeaServer(
    private val port: Int,
    private val log: (String) -> Unit
) {

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var running = false

    val clientCount: Int get() = clients.size
    val isRunning: Boolean get() = running
    val actualPort: Int get() = server?.localPort ?: port

    fun start(): Boolean {
        if (running) return true
        return try {
            server = ServerSocket(port).apply { reuseAddress = true }
            running = true
            acceptThread = Thread({ acceptLoop() }, "nmea-accept").also {
                it.isDaemon = true
                it.start()
            }
            log("NMEA 输出已启动，监听 0.0.0.0:${actualPort}")
            true
        } catch (t: Throwable) {
            log("NMEA 端口 $port 绑定失败：${t.message}")
            running = false
            false
        }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                server?.accept() ?: break
            } catch (t: Throwable) {
                if (running) log("NMEA 接入异常：${t.message}")
                break
            }
            clients.add(socket)
            log("NMEA 客户端接入 ${socket.inetAddress?.hostAddress}（当前 ${clients.size} 个）")
            // 只保留最近几个连接，防止异常客户端把列表撑爆
            while (clients.size > MAX_CLIENTS) {
                val oldest = clients.removeAt(0)
                closeQuietly(oldest)
            }
        }
    }

    /** 向所有已连接客户端推送一帧；没有客户端时直接返回 */
    fun publish(payload: ByteArray) {
        if (clients.isEmpty()) return
        for (socket in clients) {
            try {
                socket.getOutputStream().apply {
                    write(payload)
                    flush()
                }
            } catch (t: Throwable) {
                clients.remove(socket)
                closeQuietly(socket)
                log("NMEA 客户端断开：${t.message}")
            }
        }
    }

    fun stop() {
        running = false
        closeQuietly(server)
        server = null
        clients.forEach { closeQuietly(it) }
        clients.clear()
        acceptThread = null
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val MAX_CLIENTS = 4
    }
}
