package de.glas.smart

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Settings and cached data, kept only on this phone. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("glas_smart", Context.MODE_PRIVATE)

    var clientId: String
        get() = p.getString("client_id", "") ?: ""
        set(v) = p.edit().putString("client_id", v.trim()).apply()
    var secret: String
        get() = p.getString("secret", "") ?: ""
        set(v) = p.edit().putString("secret", v.trim()).apply()
    var region: String
        get() = p.getString("region", "eu") ?: "eu"
        set(v) = p.edit().putString("region", v).apply()

    val configured get() = clientId.isNotEmpty() && secret.isNotEmpty()

    fun api() = Tuya(clientId, secret, Tuya.region(region).host)

    fun clear() {
        p.edit().clear().apply()
        Tuya.forgetToken()
    }

    // ---- Favourites and hidden devices ----

    private fun set(key: String) = p.getStringSet(key, emptySet())!!.toSet()
    private fun toggle(key: String, id: String) {
        val s = set(key).toMutableSet()
        if (!s.remove(id)) s.add(id)
        p.edit().putStringSet(key, s).apply()
    }

    val favourites get() = set("favourites")
    fun toggleFavourite(id: String) = toggle("favourites", id)
    val hidden get() = set("hidden")
    fun toggleHidden(id: String) = toggle("hidden", id)
    var showHidden: Boolean
        get() = p.getBoolean("show_hidden", false)
        set(v) = p.edit().putBoolean("show_hidden", v).apply()

    /** Group by the rooms from Smart Life (if the cloud project allows it) or by type of device. */
    var byRoom: Boolean
        get() = p.getBoolean("by_room", true)
        set(v) = p.edit().putBoolean("by_room", v).apply()

    // ---- Cache, so the list shows up instantly and specifications are fetched only once ----

    fun saveDevices(json: String) = p.edit().putString("devices", json).apply()
    fun cachedDevices(): String? = p.getString("devices", null)

    fun spec(id: String): Spec? = p.getString("spec_$id", null)?.let {
        try {
            Spec.from(JSONObject(it))
        } catch (e: Exception) {
            null
        }
    }

    fun saveSpec(id: String, s: Spec) = p.edit().putString("spec_$id", s.toJson().toString()).apply()

    /** Rooms as [{name, devices:[ids]}] and scenes as [{home, id, name}]. */
    var rooms: JSONArray
        get() = JSONArray(p.getString("rooms", "[]"))
        set(v) = p.edit().putString("rooms", v.toString()).apply()
    var scenes: JSONArray
        get() = JSONArray(p.getString("scenes", "[]"))
        set(v) = p.edit().putString("scenes", v.toString()).apply()
    var homeName: String
        get() = p.getString("home_name", "") ?: ""
        set(v) = p.edit().putString("home_name", v).apply()
    var roomsLoadedAt: Long
        get() = p.getLong("rooms_at", 0)
        set(v) = p.edit().putLong("rooms_at", v).apply()
}

private val pool = Executors.newFixedThreadPool(3)
private val main = Handler(Looper.getMainLooper())

/** Runs [work] in the background and hands the result to [done] on the main thread. */
fun <T> async(work: () -> T, done: (Result<T>) -> Unit) {
    pool.execute {
        val r = runCatching(work)
        main.post { done(r) }
    }
}

fun later(ms: Long, r: Runnable) = main.postDelayed(r, ms)
fun cancel(r: Runnable) = main.removeCallbacks(r)
