package de.glas.smarthome

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Backend {
    fun load(): Snapshot
    fun setTemp(d: Device, raw: Int)
    fun setOn(d: Device, on: Boolean)
    fun setLevel(d: Device, percent: Int)
    fun blind(d: Device, target: String)
    /** Minutes of boost or window-open; 0 ends it. */
    fun boost(d: Device, minutes: Int)
    fun window(d: Device, minutes: Int)
    fun apply(t: Template)
}

class FritzBackend(config: FritzClient.Config) : Backend {
    private val c = FritzClient(config)
    private var templates: List<Template> = emptyList()
    private var templatesAt = 0L

    override fun load(): Snapshot {
        val devices = Parser.devices(XNode.parse(c.aha("getdevicelistinfos")))
        // Templates rarely change; ask every five minutes. Older FRITZ!OS does not know them at all.
        if (SystemClock.elapsedRealtime() - templatesAt > 300_000 || templatesAt == 0L) {
            templates = runCatching { Parser.templates(XNode.parse(c.aha("gettemplatelistinfos"))) }.getOrDefault(templates)
            templatesAt = SystemClock.elapsedRealtime()
        }
        return Snapshot(devices, templates)
    }

    override fun setTemp(d: Device, raw: Int) {
        c.aha("sethkrtsoll", d.ain, mapOf("param" to raw.toString()))
    }

    override fun setOn(d: Device, on: Boolean) {
        if (d.switchOn != null) c.aha(if (on) "setswitchon" else "setswitchoff", d.ain)
        else c.aha("setsimpleonoff", d.ain, mapOf("onoff" to if (on) "1" else "0"))
    }

    override fun setLevel(d: Device, percent: Int) {
        c.aha("setlevelpercentage", d.ain, mapOf("level" to percent.coerceIn(0, 100).toString()))
    }

    override fun blind(d: Device, target: String) {
        c.aha("setblind", d.ain, mapOf("target" to target))
    }

    private fun end(minutes: Int) = if (minutes <= 0) "0" else (System.currentTimeMillis() / 1000 + minutes * 60L).toString()

    override fun boost(d: Device, minutes: Int) {
        c.aha("sethkrboost", d.ain, mapOf("endtimestamp" to end(minutes)))
    }

    override fun window(d: Device, minutes: Int) {
        c.aha("sethkrwindowopen", d.ain, mapOf("endtimestamp" to end(minutes)))
    }

    override fun apply(t: Template) {
        c.aha("applytemplate", t.ain)
    }
}

/** State of the home on the screen, loaded on one background thread so the FRITZ!Box gets one request at a time. */
object Home {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var key = ""
    var backend: Backend? = null
        private set
    var devices: List<Device> = emptyList()
        private set
    var templates: List<Template> = emptyList()
        private set
    var updated = 0L
        private set
    var error: String? = null
        private set
    var loading = false
        private set
    /** Values just set on the phone win over the box for a few seconds, until the box has caught up. */
    private val overrides = HashMap<String, Pair<Long, Device>>()
    val listeners = LinkedHashSet<() -> Unit>()
    var onError: ((String) -> Unit)? = null

    fun configure(store: Store) {
        val k = when {
            store.demo -> "demo"
            store.configured -> "${store.host}|${store.user}|${store.password.hashCode()}"
            else -> ""
        }
        if (k == key) return
        key = k
        devices = emptyList(); templates = emptyList(); error = null; updated = 0; overrides.clear(); loading = false
        backend = when {
            store.demo -> DemoBackend()
            store.configured -> FritzBackend(FritzClient.Config(store.host, store.user, store.password))
            else -> null
        }
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun message(t: Throwable) = (t as? FritzException)?.message ?: (t.message ?: t.javaClass.simpleName)

    fun refresh() {
        val b = backend ?: return
        if (loading) return
        loading = true
        changed()
        io.execute {
            val r = runCatching { b.load() }
            main.post {
                if (b !== backend) return@post
                loading = false
                r.onSuccess { s ->
                    val now = SystemClock.elapsedRealtime()
                    overrides.entries.removeAll { now - it.value.first > 6000 }
                    devices = s.devices.map { overrides[it.id]?.second ?: it }
                    templates = s.templates
                    updated = System.currentTimeMillis()
                    error = null
                }.onFailure { error = message(it) }
                changed()
            }
        }
    }

    fun device(id: String) = devices.firstOrNull { it.id == id }

    /** Shows [d] right away, before the box confirms it. */
    fun local(d: Device) {
        overrides[d.id] = SystemClock.elapsedRealtime() to d
        devices = devices.map { if (it.id == d.id) d else it }
        changed()
    }

    fun send(refreshAfter: Long = 1500, action: (Backend) -> Unit) {
        val b = backend ?: return
        io.execute {
            val r = runCatching { action(b) }
            main.post {
                r.exceptionOrNull()?.let {
                    overrides.clear()
                    onError?.invoke(message(it))
                }
                main.postDelayed({ refresh() }, if (r.isFailure) 0 else refreshAfter)
            }
        }
    }
}

/** Settings of the app. The password is encrypted with a key that never leaves this phone's keystore. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    var host: String
        get() = p.getString("host", "") ?: ""
        set(v) = p.edit().putString("host", v.trim()).apply()
    var user: String
        get() = p.getString("user", "") ?: ""
        set(v) = p.edit().putString("user", v.trim()).apply()
    var password: String
        get() = decrypt(p.getString("password", "") ?: "")
        set(v) = p.edit().putString("password", encrypt(v)).apply()
    var demo: Boolean
        get() = p.getBoolean("demo", false)
        set(v) = p.edit().putBoolean("demo", v).apply()
    var filter: String
        get() = p.getString("filter", "all") ?: "all"
        set(v) = p.edit().putString("filter", v).apply()

    val configured get() = host.isNotBlank()

    fun logout() = p.edit().remove("host").remove("user").remove("password").putBoolean("demo", false).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    private fun encrypt(s: String): String = runCatching {
        if (s.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(c.iv + c.doFinal(s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrDefault("")

    private fun decrypt(s: String): String = runCatching {
        if (s.isEmpty()) return ""
        val all = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrDefault("")

    private companion object {
        const val ALIAS = "glas-smarthome"
    }
}
