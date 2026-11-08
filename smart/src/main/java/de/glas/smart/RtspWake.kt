package de.glas.smart

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Starts the camera's RTSP stream for a few seconds without showing it, to wake a doorbell that sits in
 * standby: Tuya's media server then has to pull the picture from the camera, much like Smart Life's first
 * attempt does. A tiny RTSP client of its own (OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN), because the
 * player gives up on these cameras' SDP before it ever sends PLAY. Blocking: call it off the main thread.
 */
object RtspWake {

    /** Returns a short German report of how far it got, for the diagnostic line under the picture. */
    fun run(url: String, seconds: Int = 6): String {
        val uri = URI(url)
        val host = uri.host ?: return "keine Adresse"
        val port = if (uri.port > 0) uri.port else if (url.startsWith("rtsps", true)) 443 else 554
        val user = uri.rawUserInfo?.substringBefore(':')?.let { URLDecoder.decode(it, "UTF-8") }
        val pass = uri.rawUserInfo?.substringAfter(':', "")?.let { URLDecoder.decode(it, "UTF-8") }
        // Address without the login, as it goes into the requests
        val base = url.substringBefore("://") + "://" + host + (if (uri.port > 0) ":${uri.port}" else "") +
            (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")

        val socket = if (url.startsWith("rtsps", true)) {
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply { startHandshake() }
        } else java.net.Socket(host, port)
        socket.soTimeout = 8000
        var step = "Verbindung"
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val client = Client(input, out, user, pass)

            step = "OPTIONS"
            client.request("OPTIONS", base)
            step = "DESCRIBE"
            val describe = client.request("DESCRIBE", base, "Accept: application/sdp\r\n")
            if (describe.status != 200) return "DESCRIBE ${describe.status}"
            val contentBase = describe.header("content-base")?.trimEnd('/') ?: base.trimEnd('/')
            val control = Regex("m=video[\\s\\S]*?a=control:(\\S+)").find(describe.body)?.groupValues?.get(1)
                ?: Regex("a=control:(\\S+)").findAll(describe.body).map { it.groupValues[1] }.lastOrNull { it != "*" }
                ?: return "keine Videospur"
            val trackUrl = if (control.contains("://")) control else "$contentBase/$control"

            step = "SETUP"
            val setup = client.request("SETUP", trackUrl, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
            if (setup.status != 200) return "SETUP ${setup.status}"
            val session = setup.header("session")?.substringBefore(';') ?: return "keine Sitzung"

            step = "PLAY"
            val play = client.request("PLAY", contentBase, "Session: $session\r\nRange: npt=0.000-\r\n")
            if (play.status != 200) return "PLAY ${play.status}"

            // Let the media flow for a moment, then hang up properly
            step = "Empfang"
            var bytes = 0L
            val until = System.currentTimeMillis() + seconds * 1000L
            val buf = ByteArray(16 * 1024)
            socket.soTimeout = 2000
            while (System.currentTimeMillis() < until) {
                val n = try { input.read(buf) } catch (e: java.net.SocketTimeoutException) { 0 }
                if (n < 0) break
                bytes += n
            }
            runCatching { client.send("TEARDOWN", contentBase, "Session: $session\r\n") }
            return "Stream lief, ${bytes / 1024} kB empfangen"
        } catch (e: Exception) {
            return "$step: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { socket.close() }
        }
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers[name.lowercase()]
    }

    private class Client(val input: InputStream, val out: OutputStream, val user: String?, val pass: String?) {
        var cseq = 0
        var auth: ((String, String) -> String)? = null

        fun send(method: String, url: String, extra: String = "") {
            val authHeader = auth?.invoke(method, url)?.let { "Authorization: $it\r\n" } ?: ""
            val msg = "$method $url RTSP/1.0\r\nCSeq: ${++cseq}\r\nUser-Agent: GlasSmart\r\n$authHeader$extra\r\n"
            out.write(msg.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        /** Sends a request and reads the answer; logs in and repeats once on 401. */
        fun request(method: String, url: String, extra: String = ""): Response {
            send(method, url, extra)
            var r = read()
            if (r.status == 401 && user != null && pass != null) {
                val challenge = r.header("www-authenticate") ?: return r
                auth = authFor(challenge)
                send(method, url, extra)
                r = read()
            }
            return r
        }

        private fun authFor(challenge: String): (String, String) -> String {
            if (challenge.startsWith("Basic", true)) {
                val token = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
                return { _, _ -> "Basic $token" }
            }
            val realm = Regex("realm=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1) ?: ""
            val nonce = Regex("nonce=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1) ?: ""
            return { method, uri ->
                val ha1 = md5("$user:$realm:$pass")
                val ha2 = md5("$method:$uri")
                "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"${md5("$ha1:$nonce:$ha2")}\""
            }
        }

        /** Reads one RTSP answer, skipping media frames ('$' + channel + length) that may come in between. */
        fun read(): Response {
            while (true) {
                input.mark(1)
                val first = input.read()
                if (first < 0) throw java.io.EOFException("Verbindung beendet")
                if (first == '$'.code) {
                    val head = ByteArray(3)
                    readFully(head)
                    val len = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
                    readFully(ByteArray(len))
                    continue
                }
                input.reset()
                break
            }
            val headerBytes = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw java.io.EOFException("Verbindung beendet")
                headerBytes.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
            }
            val lines = headerBytes.toString("ISO-8859-1").split("\r\n")
            val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val headers = lines.drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length).also { readFully(it) }
            return Response(status, headers, String(body, Charsets.ISO_8859_1))
        }

        private fun readFully(b: ByteArray) {
            var off = 0
            while (off < b.size) {
                val n = input.read(b, off, b.size - off)
                if (n < 0) throw java.io.EOFException("Verbindung beendet")
                off += n
            }
        }

        private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it) }
    }
}
