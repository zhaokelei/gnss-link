package top.wxip.gpslink

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 扫码页。
 *
 * 只负责取景与回调，解析交给 [top.wxip.gpslink.core.ConnectCode]，
 * 连接动作交回 MainActivity —— 这样扫码页不需要知道任何业务状态。
 *
 * 用 BarcodeCallback 而不是 CaptureManager.decode()：后者把结果写进 Intent，
 * 需要额外处理 onActivityResult，这里直接回调更短，也方便做「防重复触发」。
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RAW = "raw"
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private var handled = false

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            // 相机回调可能连续触发，只认第一帧，否则会重复 startActivity
            if (handled) return
            handled = true
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_RAW, result.text)
            )
            finish()
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = layoutInflater.inflate(R.layout.activity_scan, null)
        setContentView(root)
        barcodeView = root.findViewById(R.id.barcode)
        barcodeView.setStatusText("对准共享端的二维码")
        barcodeView.decodeContinuous(callback)
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
