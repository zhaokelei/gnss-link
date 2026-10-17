package top.wxip.gpslink.core

import java.util.UUID

/**
 * 局域网 UDP 协议常量。
 *
 * 所有报文都是单包 JSON（UTF-8），小于 1400 字节，不拆包不重传 ——
 * 定位数据是"最新值有效"的流，丢一帧无所谓，重传反而增加延迟。
 */
object Protocol {

    /** 蓝牙 RFCOMM 的固定 UUID，两端必须一致 */
    val BT_UUID: UUID = UUID.fromString("6f1d2c3a-8e4b-4a57-9c21-7d3f5b8e9a02")

    /** 蓝牙服务名，配对后在系统里能看到 */
    const val BT_SERVICE_NAME = "GNSS Link"

    const val VERSION = 1

    /** 默认 UDP 端口，与参考项目 gnsslink 保持一致 */
    const val DEFAULT_PORT = 7007

    /** 默认采样间隔：1Hz */
    const val DEFAULT_INTERVAL_MS = 1000

    /** 可选采样档位（毫秒），对应 1 / 2 / 5 / 10 Hz */
    val INTERVAL_OPTIONS = intArrayOf(1000, 500, 200, 100)

    /** 默认注入间隔：10Hz。接收端在两次定位帧之间做外推，让蓝点连续移动 */
    const val DEFAULT_INJECT_MS = 100

    /** 可选注入档位（毫秒），对应 1 / 5 / 10 Hz */
    val INJECT_OPTIONS = intArrayOf(1000, 200, 100)

    /** 单个 UDP 包上限，避免超过局域网 MTU 被分片 */
    const val MAX_PACKET = 1400

    /** 发送端 -> 接收端：一帧定位数据 */
    const val T_LOC = "LOC"

    /** 接收端 -> 发送端：收到 LOC 的回执，用于测 RTT 与探活 */
    const val T_ACK = "ACK"

    /** 接收端 -> 发送端：主动心跳，即使 LOC 没到也告诉对方"我还在" */
    const val T_BEAT = "BEAT"

    /** 发送端 -> 接收端：卫星状态快照（每 2 秒一次，不跟定位帧同频） */
    const val T_SAT = "SAT"

    /** 发送端 -> 广播：寻找接收端 */
    const val T_WHO = "WHO"

    /** 接收端 -> 发送端：应答发现请求，带上自己的地址 */
    const val T_HERE = "HERE"

    /** 接收端 -> 发送端：拒绝（房间码不匹配等），便于排查 */
    const val T_BYE = "BYE"
}
