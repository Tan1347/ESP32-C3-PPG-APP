package org.tan.ppgtoolapp.data.network

import android.content.Context
import android.util.Log
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 自定义 DNS 解析器，对 GitHub 相关域名使用优选 IP，其余域名走系统 DNS。
 *
 * 优先级：
 * 1. 本地测速缓存（用户测速后保存）
 * 2. 远程 hosts 缓存（从 hellogithub.com 获取）
 * 3. 系统 DNS（回退）
 */
class GitHubDns(private val context: Context) : Dns {

    companion object {
        private const val TAG = "GitHubDns"

        private val GITHUB_SUFFIXES = listOf(
            "github.com",
            "github.io",
            "githubusercontent.com",
            "githubassets.com",
            "githubapp.com",
            "githubstatus.com",
            "fastly.net"
        )
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        // 只对 GitHub 相关域名做优选 IP 替换
        if (isGitHubDomain(hostname)) {
            val bestIp = GitHubHostsHelper.getBestIp(context, hostname)
            if (bestIp != null) {
                Log.d(TAG, "域名 $hostname 使用优选 IP: $bestIp")
                return listOf(InetAddress.getByAddress(hostname, parseIpBytes(bestIp)))
            }
        }

        // 回退到系统 DNS
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun isGitHubDomain(hostname: String): Boolean {
        val lower = hostname.lowercase()
        return GITHUB_SUFFIXES.any { lower == it || lower.endsWith(".$it") }
    }

    private fun parseIpBytes(ip: String): ByteArray {
        val parts = ip.split(".")
        if (parts.size != 4) throw UnknownHostException("Invalid IP: $ip")
        return byteArrayOf(
            parts[0].toInt().toByte(),
            parts[1].toInt().toByte(),
            parts[2].toInt().toByte(),
            parts[3].toInt().toByte()
        )
    }
}
