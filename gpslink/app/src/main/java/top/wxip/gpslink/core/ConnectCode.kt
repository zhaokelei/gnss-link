package top.wxip.gpslink.core

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 局域网连接串的编解码。
 *
 * 二维码里放的是自定义 scheme，好处是：
 * 1. 扫码 App 不会当成网址去打开浏览器；
 * 2. 我们自己解析，能直接跳出「是否连接」的确认框。
 *
 * 格式：gpslink://connect?ip=192.168.1.5&port=7007&sid=8888&role=receiver
 */
object ConnectCode {

    private const val SCHEME = "gpslink"
    private const val HOST = "connect"

    data class Payload(val ip: String, val port: Int, val sid: String)

    fun encode(ip: String, port: Int, sid: String): String = buildString {
        append(SCHEME).append("://").append(HOST)
        append("?ip=").append(encodeValue(ip))
        append("&port=").append(port)
        append("&sid=").append(encodeValue(sid))
    }

    /**
     * 解析扫码结果。
     *
     * 既支持自定义 scheme，也容忍直接粘贴的 `ip:port` 纯文本——
     * 有些车机自带扫码 App 会把自定义 scheme 吃掉只剩正文。
     * 任何字段缺失或非法都返回 null，由调用方提示「无法识别」。
     */
    fun decode(raw: String?): Payload? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        if (text.startsWith("$SCHEME://")) {
            val query = text.substringAfter('?', "")
            if (query.isEmpty()) return null
            val map = query.split('&').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null
                else pair.substring(0, i) to decodeValue(pair.substring(i + 1))
            }.toMap()
            val ip = map["ip"].orEmpty().trim()
            val port = map["port"]?.toIntOrNull() ?: return null
            val sid = map["sid"].orEmpty().trim().ifBlank { "8888" }
            if (ip.isEmpty() || port !in 1024..65535) return null
            return Payload(ip, port, sid)
        }

        // 兜底：直接把 "192.168.1.5:7007" 贴进来也能用
        val m = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})$").find(text) ?: return null
        val ip = m.groupValues[1]
        val port = m.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1024..65535) return null
        return Payload(ip, port, "8888")
    }

    private fun encodeValue(v: String): String =
        java.net.URLEncoder.encode(v, "UTF-8")

    private fun decodeValue(v: String): String = try {
        java.net.URLDecoder.decode(v, "UTF-8")
    } catch (_: Throwable) {
        v
    }

    /**
     * 生成二维码位图。
     *
     * 不依赖任何 Android 侧扫码库，只用 zxing-core 的编码器，
     * 因此 minSdk 19 也能用。失败返回 null，界面显示占位文字。
     */
    fun qrBitmap(content: String, sizePx: Int): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
