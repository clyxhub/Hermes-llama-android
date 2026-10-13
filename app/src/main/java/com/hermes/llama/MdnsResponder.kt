package com.hermes.llama

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections

/**
 * Minimal, dependency-free mDNS / DNS-SD responder (RFC 6762 / RFC 6763).
 *
 * Advertises this device on the local network as `<host>.local` (an A record
 * carrying our LAN IPv4) plus an `_http._tcp.local` service pointing at the live
 * server port, so people on the same Wi-Fi can type a name instead of an IP:
 *
 *     http://calisto-local.local:8080/v1
 *
 * mDNS is the zero-configuration standard and resolves the `.local` suffix.
 * (A custom TLD such as `.ai` is not resolved by mDNS-aware clients unless it is
 * separately delegated in public DNS; `.local` is what works out of the box.)
 */
object MdnsResponder {

    private const val TAG = "HermesLlama"
    private const val MDNS_ADDR = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val SVC_TYPE = "_http._tcp.local"

    const val DEFAULT_HOST = "calisto-local"
    const val SERVICE_NAME = "CALISTO local ai"

    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var rxThread: Thread? = null
    @Volatile private var txThread: Thread? = null
    @Volatile private var lock: WifiManager.MulticastLock? = null

    @Volatile var hostLabel: String = DEFAULT_HOST
        private set
    @Volatile var port: Int = 8080
        private set

    val isRunning: Boolean get() = socket?.isClosed == false

    /** The resolvable host name, e.g. `calisto-local.local`. */
    fun hostname(): String = "$hostLabel.local"

    /** The URL to hand to other machines on the network. */
    fun url(p: Int = port): String = "http://${hostname()}:$p"

    @Synchronized
    fun start(context: Context, host: String, port: Int): Boolean {
        stop()
        this.hostLabel = sanitize(host)
        this.port = port
        return try {
            // Multicast on Android needs an explicit lock or the Wi-Fi stack drops it.
            lock = runCatching {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.createMulticastLock("calisto-mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()

            val ms = MulticastSocket(MDNS_PORT)
            ms.reuseAddress = true
            val group = InetAddress.getByName(MDNS_ADDR)
            joinOnAllInterfaces(ms, group)
            ms.timeToLive = 255
            socket = ms

            rxThread = Thread({ receiveLoop(ms) }, "calisto-mdns-rx").apply { isDaemon = true; start() }
            txThread = Thread({ announceLoop(ms) }, "calisto-mdns-tx").apply { isDaemon = true; start() }
            Log.i(TAG, "mDNS advertising ${hostname()} -> :$port")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS start failed: ${t.message}")
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { rxThread?.interrupt() }
        runCatching { txThread?.interrupt() }
        rxThread = null
        txThread = null
        runCatching { socket?.close() }
        socket = null
        runCatching { if (lock?.isHeld == true) lock?.release() }
        lock = null
    }

    // --- internals ----------------------------------------------------------

    private fun joinOnAllInterfaces(ms: MulticastSocket, group: InetAddress) {
        var joined = false
        runCatching {
            val nifs = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (nif in Collections.list(nifs)) {
                runCatching {
                    if (nif.isUp && nif.supportsMulticast() && !nif.isLoopback) {
                        ms.joinGroup(InetSocketAddress(group, MDNS_PORT), nif)
                        joined = true
                    }
                }
            }
        }
        if (!joined) runCatching { ms.joinGroup(group) }
    }

    private fun receiveLoop(ms: MulticastSocket) {
        val buf = ByteArray(1500)
        while (!ms.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                ms.receive(pkt)
            } catch (t: Throwable) {
                break
            }
            runCatching { onQuery(ms, pkt.data, pkt.length) }
        }
    }

    private fun onQuery(ms: MulticastSocket, data: ByteArray, len: Int) {
        if (len < 12) return
        val flags = u16(data, 2)
        if (flags and 0x8000 != 0) return          // a response, not a query
        val qd = u16(data, 4)
        if (qd <= 0) return
        var off = 12
        var matched = false
        var i = 0
        while (i < qd && off < len) {
            val (name, next) = readName(data, off)
            off = next + 4                          // skip QTYPE + QCLASS
            if (name.equals(hostname(), true) || name.equals(SVC_TYPE, true)) matched = true
            i++
        }
        if (matched) send(ms, buildResponse(0))
    }

    private fun announceLoop(ms: MulticastSocket) {
        try {
            repeat(2) {                             // gratuitous first announcement
                runCatching { send(ms, buildResponse(0)) }
                Thread.sleep(1_000)
            }
            while (!ms.isClosed) {
                runCatching { send(ms, buildResponse(0)) }
                Thread.sleep(30_000)
            }
        } catch (ignored: InterruptedException) {
        }
    }

    private fun send(ms: MulticastSocket, payload: ByteArray) {
        if (payload.isEmpty() || ms.isClosed) return
        val group = InetAddress.getByName(MDNS_ADDR)
        ms.send(DatagramPacket(payload, payload.size, group, MDNS_PORT))
    }

    /** Authoritative response: A + PTR + SRV + TXT. */
    private fun buildResponse(id: Int): ByteArray {
        val ip = localIpv4() ?: return ByteArray(0)
        val host = hostname()
        val instance = "$SERVICE_NAME.$SVC_TYPE"
        val out = ByteArrayOutputStream(256)
        out.w16(id)
        out.w16(0x8400)                             // QR=1, AA=1
        out.w16(0)                                  // QDCOUNT
        out.w16(4)                                  // ANCOUNT
        out.w16(0)                                  // NSCOUNT
        out.w16(0)                                  // ARCOUNT

        // A : host.local -> ip
        out.wName(host); out.w16(1); out.w16(1); out.w32(120); out.w16(4); out.write(ip)

        // PTR : _http._tcp.local -> instance
        out.wName(SVC_TYPE); out.w16(12); out.w16(1); out.w32(4500)
        out.w16(nameLen(instance)); out.wName(instance)

        // SRV : instance -> host:port
        out.wName(instance); out.w16(33); out.w16(1); out.w32(120)
        out.w16(6 + nameLen(host)); out.w16(0); out.w16(0); out.w16(port); out.wName(host)

        // TXT : instance -> "path=/"
        val txt = "path=/".toByteArray(Charsets.UTF_8)
        out.wName(instance); out.w16(16); out.w16(1); out.w32(4500)
        out.w16(txt.size + 1); out.write(txt.size); out.write(txt)

        return out.toByteArray()
    }

    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start
        while (i < data.size) {
            val l = data[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 == 0xC0) { i += 2; break }   // compression pointer
            i++
            if (i + l > data.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, i, l, Charsets.UTF_8))
            i += l
        }
        return sb.toString() to i
    }

    private fun nameLen(name: String): Int {
        var n = 1
        for (label in name.split('.')) if (label.isNotEmpty()) n += 1 + label.toByteArray(Charsets.UTF_8).size
        return n
    }

    private fun localIpv4(): ByteArray? {
        runCatching {
            val nifs = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in Collections.list(nifs)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in Collections.list(nif.inetAddresses)) {
                    if (!ia.isLoopbackAddress && ia.address.size == 4) return ia.address
                }
            }
        }
        return null
    }

    private fun sanitize(host: String): String {
        val h = host.trim().lowercase().removeSuffix(".local")
            .replace(Regex("[^a-z0-9-]"), "-")
            .trim('-')
        return h.ifBlank { DEFAULT_HOST }
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun ByteArrayOutputStream.w16(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.w32(v: Int) { w16((v shr 16) and 0xFFFF); w16(v and 0xFFFF) }

    private fun ByteArrayOutputStream.wName(name: String) {
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val b = label.toByteArray(Charsets.UTF_8)
            write(b.size)
            write(b)
        }
        write(0)
    }
}