package de.huyc.studyhelper.web

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** 局域网 IPv4 地址，优先 wlan/eth 网卡。 */
    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
