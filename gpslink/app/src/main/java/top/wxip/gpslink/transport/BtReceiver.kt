package top.wxip.gpslink.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import top.wxip.gpslink.core.Protocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * 蓝牙接收端：起一个 RFCOMM 服务端 socket 等发送端连进来。
 *
 * 连上之后同时支持两个方向：读发送端的 LOC，回写 ACK / BEAT。
 * 断开后继续 accept，发送端会自动重连。
 */
class BtReceiver(
    private val context: Context,
    private val onLine: (String, (ByteArray) -> Boolean) -> Unit,
    private val onState: (connected: Boolean, desc: String) -> Unit
) {

    companion object {
        private const val TAG = "BtReceiver"
    }

    @Volatile
    private var running = false

    private var server: BluetoothServerSocket? = null

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var out: OutputStream? = null

    private var worker: Thread? = null

    @Volatile
    var connected = false
        private set

    fun start() {
        if (running) return
        running = true
        worker = Thread({ loop() }, "bt-recv").also {
            it.isDaemon = true
            it.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            onState(false, "蓝牙未开启")
            return
        }

        try {
            server = adapter.listenUsingRfcommWithServiceRecord(
                Protocol.BT_SERVICE_NAME, Protocol.BT_UUID
            )
            onState(false, "等待蓝牙连接…")
        } catch (t: Throwable) {
            Log.w(TAG, "listen failed", t)
            onState(false, "无法监听：${t.message}")
            return
        }

        while (running) {
            var s: BluetoothSocket? = null
            try {
                s = server?.accept() ?: break
                socket = s
                out = s.outputStream
                connected = true
                onState(
                    true,
                    s.remoteDevice?.name ?: s.remoteDevice?.address ?: "已连接"
                )

                val reader = BufferedReader(InputStreamReader(s.inputStream))
                val reply: (ByteArray) -> Boolean = { bytes -> write(bytes) }
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onLine(line, reply)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "accept/read: ${t.message}")
            } finally {
                connected = false
                out = null
                socket = null
                try {
                    s?.close()
                } catch (_: Throwable) {
                }
                if (running) onState(false, "等待蓝牙连接…")
            }
        }
    }

    fun write(bytes: ByteArray): Boolean {
        val o = out ?: return false
        return try {
            synchronized(this) {
                o.write(bytes)
                o.write('\n'.code)
                o.flush()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "bt write failed: ${t.message}")
            false
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        out = null
        worker?.interrupt()
        worker = null
        connected = false
    }
}
