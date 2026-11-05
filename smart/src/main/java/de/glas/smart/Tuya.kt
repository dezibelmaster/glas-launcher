package de.glas.smart

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TuyaException(val code: Int, message: String) : Exception(message)

/**
 * Small client for the Tuya cloud (the platform behind Smart Life), using the user's own
 * cloud project from iot.tuya.com. Every call is blocking: run it off the main thread.
 */
class Tuya(private val clientId: String, private val secret: String, private val host: String) {

    data class Region(val key: String, val label: String, val host: String)

    companion object {
        val regions = listOf(
            Region("eu", "Mitteleuropa (Deutschland, Österreich, Schweiz)", "https://openapi.tuyaeu.com"),
            Region("weu", "Westeuropa", "https://openapi-weaz.tuyaeu.com"),
            Region("us", "USA West", "https://openapi.tuyaus.com"),
            Region("eus", "USA Ost", "https://openapi-ueaz.tuyaus.com"),
            Region("cn", "China", "https://openapi.tuyacn.com"),
            Region("in", "Indien", "https://openapi.tuyain.com"),
        )

        fun region(key: String) = regions.firstOrNull { it.key == key } ?: regions[0]

        // Shared by all screens; a token is valid for about two hours
        private var token: String? = null
        private var tokenUntil = 0L
        private var tokenFor = ""

        @Synchronized
        fun forgetToken() {
            token = null
            tokenUntil = 0
        }

        /** German explanation for the error codes people run into while setting up. */
        fun explain(e: Throwable): String = when {
            e is TuyaException && e.code == 1004 -> "Anmeldung abgelehnt (Signatur ungültig). Bitte Access Secret prüfen."
            e is TuyaException && e.code == 1005 -> "Access ID unbekannt. Bitte Access ID prüfen."
            e is TuyaException && e.code == 1013 -> "Die Uhrzeit des Handys weicht zu stark ab. Bitte automatische Uhrzeit einschalten."
            e is TuyaException && e.code == 1106 ->
                "Keine Berechtigung. Meist passt das Rechenzentrum nicht oder das Smart-Life-Konto ist nicht mit dem Projekt verknüpft."
            e is TuyaException && e.code == 28841002 ->
                "Der kostenlose Cloud-Dienst ist abgelaufen. Auf iot.tuya.com unter Cloud → Cloud Project → Cloud Services → IoT Core verlängern."
            e is TuyaException && e.code == 28841105 ->
                "Dieser Dienst ist im Cloud-Projekt nicht freigeschaltet (Cloud → Projekt → Service API)."
            e is TuyaException && e.code == 2008 -> "Das Gerät unterstützt diesen Befehl nicht."
            e is TuyaException && e.code == 2017 -> "Gerät ist offline."
            e is TuyaException -> "${e.message} (Code ${e.code})"
            e is java.net.UnknownHostException -> "Keine Internetverbindung."
            e is java.net.SocketTimeoutException -> "Zeitüberschreitung. Bitte erneut versuchen."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    // ---- Devices -------------------------------------------------------------------------

    /** All devices of the Smart Life accounts linked to the cloud project, with their current status. */
    fun devices(): List<Device> {
        val out = ArrayList<Device>()
        var lastRow = ""
        do {
            val q = linkedMapOf("size" to "100")
            if (lastRow.isNotEmpty()) q["last_row_key"] = lastRow
            val r = call("GET", "/v1.0/iot-01/associated-users/devices", q) as JSONObject
            val list = r.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until list.length()) out.add(Device.from(list.getJSONObject(i)))
            lastRow = r.optString("last_row_key", "")
        } while (r.optBoolean("has_more", false) && lastRow.isNotEmpty() && out.size < 2000)
        return out
    }

    fun status(id: String): Map<String, Any?> {
        val arr = call("GET", "/v1.0/devices/$id/status") as JSONArray
        return Device.statusMap(arr)
    }

    fun specs(id: String): Spec = Spec.from(call("GET", "/v1.0/devices/$id/specifications") as JSONObject)

    /** Sends one or more data points at once, e.g. work_mode together with colour_data_v2. */
    fun send(id: String, commands: List<Pair<String, Any>>) {
        val arr = JSONArray()
        for ((code, value) in commands) arr.put(JSONObject().put("code", code).put("value", value))
        call("POST", "/v1.0/devices/$id/commands", body = JSONObject().put("commands", arr).toString())
    }

    // ---- Homes, rooms, scenes (optional: need the Smart Home services of the project) ----

    data class Home(val id: String, val name: String)
    data class Room(val id: String, val name: String, val deviceIds: List<String>)
    data class Scene(val homeId: String, val id: String, val name: String)

    fun homes(uid: String): List<Home> {
        val arr = call("GET", "/v1.0/users/$uid/homes") as JSONArray
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Home(o.get("home_id").toString(), o.optString("name"))
        }
    }

    fun rooms(homeId: String): List<Room> {
        val r = call("GET", "/v1.0/homes/$homeId/rooms") as JSONObject
        val arr = r.optJSONArray("rooms") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val roomId = o.get("room_id").toString()
            val devs = call("GET", "/v1.0/homes/$homeId/rooms/$roomId/devices") as JSONArray
            Room(roomId, o.optString("name"), (0 until devs.length()).map { i -> devs.getJSONObject(i).optString("id") })
        }
    }

    fun scenes(homeId: String): List<Scene> {
        val arr = call("GET", "/v1.1/homes/$homeId/scenes") as JSONArray
        return (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            if (!o.optBoolean("enabled", true)) null
            else Scene(homeId, o.optString("scene_id"), o.optString("name"))
        }
    }

    fun runScene(s: Scene) {
        call("POST", "/v1.0/homes/${s.homeId}/scenes/${s.id}/trigger", body = "")
    }

    // ---- Signing and transport -----------------------------------------------------------

    private fun accessToken(): String = synchronized(Tuya::class.java) {
        val key = "$host|$clientId"
        val now = System.currentTimeMillis()
        token?.let { if (tokenFor == key && now < tokenUntil) return it }
        val r = raw("GET", "/v1.0/token", mapOf("grant_type" to "1"), null, null) as JSONObject
        val t = r.getString("access_token")
        token = t
        tokenFor = key
        // Renew a minute early
        tokenUntil = now + (r.optLong("expire_time", 7200) - 60) * 1000
        t
    }

    private fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): Any {
        return try {
            raw(method, path, query, body, accessToken())
        } catch (e: TuyaException) {
            // Token expired or revoked: fetch a new one and try once more
            if (e.code == 1010 || e.code == 1011 || e.code == 1012) {
                forgetToken()
                raw(method, path, query, body, accessToken())
            } else throw e
        }
    }

    private fun raw(method: String, path: String, query: Map<String, String>, body: String?, access: String?): Any {
        val q = query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = if (q.isEmpty()) path else "$path?$q"
        val bodyBytes = (body ?: "").toByteArray()
        val t = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val stringToSign = "$method\n${sha256(bodyBytes)}\n\n$url"
        val sign = hmac(clientId + (access ?: "") + t + nonce + stringToSign)

        val con = URL(host + url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = method
            con.connectTimeout = 10_000
            con.readTimeout = 15_000
            con.setRequestProperty("client_id", clientId)
            con.setRequestProperty("sign", sign)
            con.setRequestProperty("sign_method", "HMAC-SHA256")
            con.setRequestProperty("t", t)
            con.setRequestProperty("nonce", nonce)
            con.setRequestProperty("lang", "de")
            if (access != null) con.setRequestProperty("access_token", access)
            if (method == "POST" || method == "PUT") {
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(bodyBytes) }
            }
            val stream = if (con.responseCode in 200..299) con.inputStream else con.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw TuyaException(con.responseCode, "Unerwartete Antwort vom Server (HTTP ${con.responseCode})")
            }
            if (!json.optBoolean("success", false)) {
                throw TuyaException(json.optInt("code", -1), json.optString("msg", "Unbekannter Fehler"))
            }
            return json.opt("result") ?: JSONObject()
        } finally {
            con.disconnect()
        }
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun hmac(msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray()).joinToString("") { "%02X".format(it) }
    }
}
