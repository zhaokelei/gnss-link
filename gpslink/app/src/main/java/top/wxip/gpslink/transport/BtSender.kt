package top.wxip.gpslink.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import top.wxip.gpslink.core.Protocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * 蓝牙发送端：连到已配对的接收端，把 JSON 按行写过去。
 *
 * 为什么用经典蓝牙 RFCOMM 而不是 BLE：
 *  - 1Hz~10Hz 的定位帧每包才 250 字节，RFCOMM 的带宽绰绰有余；
 *  - 不需要 GATT 那套 service / characteristic / MTU 协商，代码量少一半；
 *  - 断线重连只需要重新 connect()。
 *
 * 帧格式用换行分隔（NDJSON）：RFCOMM 是字节流，不像 UDP 有天然边界。
 */
class BtSender(
    private val context: Context,
    private val mac: String,
    private val onLine: (String) -> Unit,
    private val onState: (connected: Boolean, desc: String) -> Unit
) {

    companion object {
        private const val TAG = "BtSender"
    }

    @Volatile
    private var running = false

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
        worker = Thread({ loop() }, "bt-send").also {
            it.isDaemon = true
            it.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        while (running) {
            var s: BluetoothSocket? = null
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null || !adapter.isEnabled) {
                    onState(false, "蓝牙未开启")
                    Thread.sleep(2000)
                    continue
                }

                val device = adapter.getRemoteDevice(mac)
                s = device.createRfcommSocketToServiceRecord(Protocol.BT_UUID)
                s.connect()

                socket = s
                out = s.outputStream
                connected = true
                onState(true, device.name ?: mac)

                val reader = BufferedReader(InputStreamReader(s.inputStream))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onLine(line)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bt loop: ${t.message}")
            } finally {
                connected = false
                out = null
                socket = null
                try {
                    s?.close()
                } catch (_: Throwable) {
                }
                if (running) {
                    onState(false, "蓝牙连接断开，重试中…")
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    fun send(bytes: ByteArray): Boolean {
        val o = out ?: return false
        return try {
            synchronized(this) {
                o.write(bytes)
                o.write('\n'.code)
                o.flush()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "bt send failed: ${t.message}")
            false
        }
    }

    fun stop() {
        running = false
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
