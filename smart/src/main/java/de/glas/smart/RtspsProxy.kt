package de.glas.smart

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Tuya hands out camera streams as rtsps:// (RTSP inside TLS), which the player can't open itself.
 * This forwards a plain rtsp:// connection on the phone itself to the camera server through TLS,
 * and swaps the addresses inside the RTSP messages so both sides see the address they expect.
 */
class RtspsProxy(remoteUrl: String) {
    private val uri = URI(remoteUrl)
    private val host = uri.host
    private val port = if (uri.port > 0) uri.port else 443
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val local = "rtsp://127.0.0.1:${server.localPort}"
    private val remote = "rtsps://$host" + if (uri.port > 0) ":${uri.port}" else ""
    private val sockets = ArrayList<Socket>()
    @Volatile private var closed = false

    /** Address for the player: same path and login, but pointing at this proxy. */
    val localUrl: String = "rtsp://" + (uri.rawUserInfo?.let { "$it@" } ?: "") + "127.0.0.1:${server.localPort}" +
        (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")

    init {
        thread(name = "rtsps-accept", isDaemon = true) {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                thread(name = "rtsps-conn", isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        val tls = try {
            (SSLSocketFactory.getDefault().createSocket(host, port) as javax.net.ssl.SSLSocket).apply { startHandshake() }
        } catch (e: Exception) {
            runCatching { client.close() }
            return
        }
        synchronized(sockets) { sockets += client; sockets += tls }
        thread(name = "rtsps-up", isDaemon = true) {
            pump(BufferedInputStream(client.getInputStream()), tls.getOutputStream()) { fixAuth(swap(it, local, remote)) }
            closeAll()
        }
        pump(BufferedInputStream(tls.getInputStream()), client.getOutputStream()) { swapBack(it) }
        closeAll()
    }

    // The server may write its address with or without the port
    private fun swapBack(s: String): String {
        var r = s
        if (uri.port > 0) r = r.replace("rtsps://$host:${uri.port}", local)
        r = r.replace("rtsps://$host:443", local).replace("rtsps://$host", local)
        return r
    }

    private fun swap(s: String, from: String, to: String) = s.replace(from, to)

    private val user = uri.rawUserInfo?.substringBefore(':')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    private val pass = uri.rawUserInfo?.substringAfter(':', "")?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    /**
     * The player signs its login (Digest) with the address it knows, 127.0.0.1, but the server checks
     * the signature against its own address. Recompute it for the real address with the login from the URL.
     */
    private fun fixAuth(msg: String): String {
        if (user == null || pass == null) return msg
        val line = Regex("(?im)^authorization:\\s*digest\\s+(.*)$").find(msg) ?: return msg
        val params = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(line.groupValues[1])
            .associate { it.groupValues[1].lowercase() to (it.groupValues[3].ifEmpty { it.groupValues[2] }) }
        val realm = params["realm"] ?: return msg
        val nonce = params["nonce"] ?: return msg
        val method = msg.substringBefore(' ')
        val requestUri = msg.substringAfter(' ').substringBefore(' ')
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$requestUri")
        val qop = params["qop"]
        val response = if (qop != null) md5("$ha1:$nonce:${params["nc"]}:${params["cnonce"]}:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        val header = buildString {
            append("Authorization: Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$requestUri\", response=\"$response\"")
            params["opaque"]?.let { append(", opaque=\"$it\"") }
            params["algorithm"]?.let { append(", algorithm=$it") }
            if (qop != null) append(", qop=$qop, nc=${params["nc"]}, cnonce=\"${params["cnonce"]}\"")
        }
        return msg.replace(line.value.trimEnd('\r'), header)
    }

    private fun md5(s: String) = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1))
        .joinToString("") { "%02x".format(it) }

    /**
     * Copies messages one by one. Binary media frames ('$', channel, 2-byte length) pass untouched;
     * RTSP text messages get their addresses rewritten and Content-Length fixed.
     */
    private fun pump(input: InputStream, out: OutputStream, rewrite: (String) -> String) {
        try {
            while (!closed) {
                input.mark(1)
                val first = input.read()
                if (first < 0) return
                if (first == '$'.code) {
                    val head = ByteArray(3)
                    readFully(input, head)
                    val len = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
                    val data = ByteArray(len)
                    readFully(input, data)
                    synchronized(out) {
                        out.write(first)
                        out.write(head)
                        out.write(data)
                        out.flush()
                    }
                    continue
                }
                input.reset()
                val header = readHeader(input)
                val length = Regex("(?im)^content-length:\\s*(\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(length)
                readFully(input, body)
                val newBody = rewrite(String(body, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)
                var newHeader = rewrite(header)
                if (length > 0) newHeader = newHeader.replace(Regex("(?im)^(content-length:\\s*)\\d+"), "$1${newBody.size}")
                synchronized(out) {
                    out.write(newHeader.toByteArray(Charsets.ISO_8859_1))
                    out.write(newBody)
                    out.flush()
                }
            }
        } catch (e: Exception) {
            // Connection closed by either side
        }
    }

    private fun readHeader(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A || (last4 and 0xFFFF) == 0x0A0A) break
            if (buf.size() > 64 * 1024) throw java.io.IOException("header too long")
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun closeAll() {
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        closeAll()
    }
}
