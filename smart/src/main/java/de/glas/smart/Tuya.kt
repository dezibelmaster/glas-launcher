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

        // Homes of the linked accounts, including shared ones, for devices the account doesn't own
        private var homesCache: List<Home> = emptyList()
        private var homesAt = 0L
        private var homesFor = ""
        private val refusedHomes = HashSet<String>()

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
        addMemberHomeDevices(out)
        return out
    }

    /**
     * Devices in homes where the linked account is only a member (shared with it in Smart Life).
     * The call above lists only the account's own devices, so ask each home for its devices too.
     * Homes that refuse are skipped until the app restarts, to spare the API quota.
     */
    private fun addMemberHomeDevices(out: MutableList<Device>) {
        val now = System.currentTimeMillis()
        if (now - homesAt > 6 * 3600_000L || homesFor != host + clientId) {
            val uids = out.map { it.uid }.filter { it.isNotEmpty() }.distinct()
            homesCache = uids.flatMap { uid -> runCatching { homes(uid) }.getOrDefault(emptyList()) }.distinctBy { it.id }
            homesAt = now
            homesFor = host + clientId
        }
        val known = out.map { it.id }.toHashSet()
        for (home in homesCache) {
            if (home.id in refusedHomes) continue
            val arr = try {
                call("GET", "/v1.0/homes/${home.id}/devices") as? JSONArray ?: continue
            } catch (e: TuyaException) {
                if (e.code == 1106 || e.code == 28841105) refusedHomes += home.id
                continue
            } catch (e: Exception) {
                continue
            }
            for (i in 0 until arr.length()) {
                val d = Device.from(arr.getJSONObject(i))
                d.homeName = home.name
                if (d.id.isNotEmpty() && known.add(d.id)) out.add(d)
            }
        }
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

    /** Short-lived HLS address of a camera's live picture (cloud service "IoT Video Live Stream"). */
    fun streamUrl(id: String, type: String = "hls"): String {
        val r = call("POST", "/v1.0/devices/$id/stream/actions/allocate",
            body = JSONObject().put("type", type).toString()) as JSONObject
        return r.getString("url")
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

    // ---- Family: members of a home, like "Home management" in Smart Life ----

    data class Member(val uid: String, val name: String, val account: String, val admin: Boolean, val owner: Boolean)

    fun members(homeId: String): List<Member> {
        val arr = call("GET", "/v1.0/homes/$homeId/members") as JSONArray
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val account = listOf("member_account", "username", "account", "email", "mobile")
                .map { k -> o.optString(k) }.firstOrNull { v -> v.isNotBlank() } ?: ""
            Member(
                uid = o.optString("uid"),
                name = o.optString("name").ifBlank { o.optString("nick_name") }.ifBlank { account },
                account = account,
                admin = o.optBoolean("admin", false) || o.optInt("role", 0) >= 1,
                owner = o.optBoolean("owner", false) || o.optInt("role", 0) == 2,
            )
        }
    }

    /** Adds a Smart Life account (e-mail or phone number) to the home. */
    fun addMember(homeId: String, account: String, countryCode: String, name: String, admin: Boolean) {
        val member = JSONObject().put("country_code", countryCode).put("member_account", account)
            .put("admin", admin).put("name", name)
        call("POST", "/v1.0/homes/$homeId/members",
            body = JSONObject().put("app_schema", "smartlife").put("member", member).toString())
    }

    fun setAdmin(homeId: String, uid: String, admin: Boolean) {
        call("PUT", "/v1.0/homes/$homeId/members/$uid", body = JSONObject().put("admin", admin).toString())
    }

    fun removeMember(homeId: String, uid: String) {
        call("DELETE", "/v1.0/homes/$homeId/members/$uid")
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
