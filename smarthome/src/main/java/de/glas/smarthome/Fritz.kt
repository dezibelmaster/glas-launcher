package de.glas.smarthome

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException

/** An error with a text the user can read as it is. */
class FritzException(msg: String) : Exception(msg)

/**
 * Talks to the FRITZ!Box: logs in with `login_sid.lua` (PBKDF2, MD5 on older boxes) and sends
 * commands to the AHA interface `webservices/homeautoswitch.lua`.
 */
class FritzClient(private val config: Config) {
    data class Config(val host: String, val user: String, val password: String)

    @Volatile private var sid: String? = null

    private val base: String get() = baseUrl(config.host)

    /** Model and FRITZ!OS version from `jason_boxinfo.xml`, which the box gives out without a login; null if it does not. */
    fun boxInfo(): Pair<String, String>? = runCatching {
        val x = XNode.parse(http("$base/jason_boxinfo.xml").second)
        val name = x.text("j:Name") ?: return null
        // "154.08.25" is model 154 with FRITZ!OS 8.25
        val version = x.text("j:Version")?.split('.')?.drop(1)?.joinToString(".")?.trimStart('0') ?: ""
        name to version
    }.getOrNull()

    private fun http(
        url: String, post: String? = null, method: String = if (post != null) "POST" else "GET",
        contentType: String = "application/x-www-form-urlencoded", headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val host = runCatching { URL(url).host }.getOrDefault(config.host)
        val c = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw FritzException("Die Adresse „${config.host}“ ist ungültig.")
        }
        c.connectTimeout = 6000
        c.readTimeout = 15000
        try {
            c.requestMethod = method
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (post != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(post.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to body
        } catch (e: UnknownHostException) {
            throw FritzException("„$host“ nicht gefunden. Ist das Handy im WLAN der FRITZ!Box?")
        } catch (e: SocketTimeoutException) {
            throw FritzException("Die FRITZ!Box antwortet nicht.")
        } catch (e: SSLException) {
            throw FritzException("Sichere Verbindung fehlgeschlagen. Adresse mit http:// versuchen.")
        } catch (e: IOException) {
            throw FritzException("Keine Verbindung zur FRITZ!Box (${e.message ?: e.javaClass.simpleName}).")
        } finally {
            c.disconnect()
        }
    }

    /** Logs in and returns the session id. */
    fun login(): String {
        val info = XNode.parse(http("$base/login_sid.lua?version=2").second)
        val challenge = info.text("Challenge")
            ?: throw FritzException("Unerwartete Antwort. Ist unter „${config.host}“ eine FRITZ!Box?")
        val wait = info.int("BlockTime") ?: 0
        if (wait > 0) throw FritzException("Zu viele Fehlversuche. Bitte $wait Sekunden warten.")
        // Without a user name the box expects the one it proposes itself
        val user = config.user.trim().ifEmpty {
            info.child("Users")?.children?.firstOrNull { it.attrs["last"] == "1" }?.text?.trim() ?: ""
        }
        val response = if (challenge.startsWith("2$")) pbkdf2Response(challenge, config.password)
        else md5Response(challenge, config.password)

        val result = XNode.parse(http("$base/login_sid.lua?version=2", "username=${enc(user)}&response=${enc(response)}").second)
        val s = result.text("SID") ?: throw FritzException("Unerwartete Antwort beim Anmelden.")
        if (s.all { it == '0' }) {
            val block = result.int("BlockTime") ?: 0
            throw FritzException(
                if (block > 0) "Benutzername oder Kennwort falsch. Nächster Versuch in $block Sekunden."
                else "Benutzername oder Kennwort falsch."
            )
        }
        // Rights come as pairs <Name>HomeAuto</Name><Access>2</Access>
        val rights = result.child("Rights")
        if (rights != null && rights.children.none { it.name == "Name" && it.text.trim() == "HomeAuto" }) {
            throw FritzException("Dieser Benutzer darf kein Smart Home steuern. In der FRITZ!Box unter System → FRITZ!Box-Benutzer das Recht „Smart Home“ geben.")
        }
        sid = s
        return s
    }

    /** A valid session id, logging in when there is none yet. */
    fun session(): String = sid ?: login()

    /** Web interface address that opens already logged in with this app's session. */
    fun webUiUrl(): String = "$base/?sid=" + login()

    /**
     * Calls the Smart Home REST API of FRITZ!OS 8.20 and newer (`/api/v0/smarthome/...`), with the session id in the
     * header `Authorization: AVM-SID <sid>`. Returns the JSON answer; logs in again once when the session expired.
     */
    fun rest(method: String, path: String, json: String? = null): String {
        repeat(2) { attempt ->
            val s = session()
            val (code, body) = http(
                "$base/api/v0/smarthome/$path", json, method, "application/json",
                mapOf("Authorization" to "AVM-SID $s", "Accept" to "application/json"),
            )
            when {
                code == 401 -> {
                    sid = null
                    if (attempt == 1) throw FritzException("Die FRITZ!Box hat die Anmeldung abgelehnt.")
                }
                code == 403 -> throw FritzException("Für Geräteeinstellungen braucht der FRITZ!Box-Benutzer zusätzlich das Recht „FRITZ!Box Einstellungen“.")
                code == 404 && body.isBlank() -> throw FritzException("Geräteeinstellungen gibt es in der App erst ab FRITZ!OS 8.20.")
                code >= 300 -> throw FritzException("Die FRITZ!Box meldet Fehler $code" + restError(body))
                else -> return body
            }
        }
        throw FritzException("Anmeldung fehlgeschlagen.")
    }

    private fun restError(body: String) = runCatching {
        val e = org.json.JSONObject(body).optJSONArray("errors")?.optJSONObject(0)
        e?.optString("message")?.takeIf { it.isNotBlank() }?.let { ": $it" }
    }.getOrNull() ?: "."

    /** Sends one AHA command and returns the answer. Logs in again once when the session has expired. */
    fun aha(cmd: String, ain: String? = null, params: Map<String, String> = emptyMap()): String {
        repeat(2) { attempt ->
            val s = sid ?: login()
            val q = StringBuilder("$base/webservices/homeautoswitch.lua?sid=$s&switchcmd=$cmd")
            if (ain != null) q.append("&ain=").append(enc(ain))
            for ((k, v) in params) q.append('&').append(k).append('=').append(enc(v))
            val (code, body) = http(q.toString())
            when {
                code == 401 || code == 403 -> {
                    sid = null
                    if (attempt == 1) throw FritzException("Die FRITZ!Box hat die Anmeldung abgelehnt.")
                }
                code == 400 -> throw FritzException("Die FRITZ!Box kennt den Befehl „$cmd“ nicht. Ist FRITZ!OS aktuell?")
                code >= 300 -> throw FritzException("Die FRITZ!Box meldet Fehler $code.")
                else -> return body
            }
        }
        throw FritzException("Anmeldung fehlgeschlagen.")
    }

    companion object {
        /** The address as typed, with http:// added when no scheme is given. */
        fun baseUrl(host: String): String {
            var b = host.trim().trimEnd('/').ifEmpty { "fritz.box" }
            if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
            return b
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** FRITZ!OS 7.24 and newer: two rounds of PBKDF2-HMAC-SHA256. */
        fun pbkdf2Response(challenge: String, password: String): String {
            val p = challenge.split("$")
            if (p.size < 5) throw FritzException("Unbekanntes Anmeldeverfahren der FRITZ!Box.")
            val hash1 = pbkdf2(password.toByteArray(Charsets.UTF_8), hex(p[2]), p[1].toInt())
            val hash2 = pbkdf2(hash1, hex(p[4]), p[3].toInt())
            return p[4] + "$" + hex(hash2)
        }

        /** Older boxes: MD5 over "challenge-password" in UTF-16LE, characters above 255 become dots. */
        fun md5Response(challenge: String, password: String): String {
            val s = "$challenge-$password".map { if (it.code > 255) '.' else it }.joinToString("")
            val md5 = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_16LE))
            return "$challenge-${hex(md5)}"
        }

        /** PBKDF2 with HMAC-SHA256 for one 32-byte block, on raw bytes (the second round uses the first hash as key). */
        fun pbkdf2(key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            // An empty HMAC key equals a key of zeros, which SecretKeySpec accepts
            mac.init(SecretKeySpec(if (key.isEmpty()) byteArrayOf(0) else key, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val out = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
            }
            return out
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

/** A tiny XML tree, enough for the short answers of the FRITZ!Box. */
class XNode(val name: String, val attrs: Map<String, String>) {
    val children = mutableListOf<XNode>()
    var text = ""

    fun child(n: String) = children.firstOrNull { it.name == n }
    fun text(n: String): String? = child(n)?.text?.trim()
    fun int(n: String): Int? = text(n)?.toIntOrNull()
    fun path(vararg p: String): XNode? {
        var c: XNode? = this
        for (s in p) c = c?.child(s)
        return c
    }

    companion object {
        fun parse(xml: String): XNode {
            try {
                val p = Xml.newPullParser()
                p.setInput(StringReader(xml))
                val root = XNode("#", emptyMap())
                val stack = ArrayList<XNode>().apply { add(root) }
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> {
                            val a = HashMap<String, String>()
                            for (i in 0 until p.attributeCount) a[p.getAttributeName(i)] = p.getAttributeValue(i)
                            val n = XNode(p.name, a)
                            stack[stack.size - 1].children.add(n)
                            stack.add(n)
                        }
                        XmlPullParser.TEXT -> stack[stack.size - 1].text += p.text
                        XmlPullParser.END_TAG -> stack.removeAt(stack.size - 1)
                    }
                    ev = p.next()
                }
                return root.children.firstOrNull() ?: throw FritzException("Leere Antwort der FRITZ!Box.")
            } catch (e: FritzException) {
                throw e
            } catch (e: Exception) {
                throw FritzException("Die Antwort der FRITZ!Box ist unlesbar.")
            }
        }
    }
}
