package com.example.screenrec

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

object Net {
    private fun isPrivate(ip: String): Boolean {
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /** Direcciones IPv4 del teléfono, las de red local primero. */
    fun localIps(): List<String> {
        val all = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return all
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { all.add(it) }
                    }
                }
            }
        } catch (ex: Exception) {
        }
        return all.sortedBy { if (isPrivate(it)) 0 else 1 }
    }

    fun randomKey(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}
