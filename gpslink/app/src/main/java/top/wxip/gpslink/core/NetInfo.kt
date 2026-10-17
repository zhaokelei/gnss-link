package top.wxip.gpslink.core

import java.net.Inet4Address
import java.net.NetworkInterface

/** 取本机局域网 IPv4，用于界面显示和排障 */
object NetInfo {

    fun localIpv4(): String {
        return try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }
}
