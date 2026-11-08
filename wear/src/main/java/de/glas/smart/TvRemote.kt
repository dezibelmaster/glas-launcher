package de.glas.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Remote control for the TV: presses keys over the TV's debugging port (5555), the same way
 * Bugjaeger or the launcher's Google TV switch do. The first time, the TV asks whether to allow
 * debugging from this watch.
 *
 * Watches usually go online through the phone's Bluetooth, and that way the TV can't be reached.
 * So the remote asks for Wi-Fi and sends everything through it while it is open.
 */
class TvRemote(ctx: Context) {
    sealed interface State {
        object Wifi : State
        class Connecting(val host: String) : State
        object Ready : State
        object NoWifi : State
        object Unreachable : State
        object NotAllowed : State
        class Failed(val reason: String) : State
    }

    var state by mutableStateOf<State>(State.Wifi)
        private set

    private val app = ctx.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var adb: Dadb? = null
    @Volatile private var host = ""
    @Volatile private var network: Network? = null

    private fun set(s: State) {
        main.post { state = s }
    }

    /** Gets Wi-Fi and connects to the TV at [host]. */
    fun start(host: String) {
        this.host = host.trim()
        releaseNetwork()
        state = State.Wifi
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                if (n == network) return
                network = n
                cm.bindProcessToNetwork(n)
                connect()
            }

            override fun onUnavailable() {
                set(State.NoWifi)
            }

            override fun onLost(n: Network) {
                if (n == network) {
                    network = null
                    set(State.NoWifi)
                }
            }
        }
        callback = cb
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.requestNetwork(request, cb, 15_000) }.onFailure { set(State.Failed(it.message ?: "WLAN")) }
    }

    private fun connect() = run {
        set(State.Connecting(host))
        state(open())
    }

    /** On the worker thread, one after the other; nothing once the remote is closed. */
    private fun run(task: () -> Unit) {
        if (!worker.isShutdown) runCatching { worker.execute(task) }
    }

    private fun state(error: Exception?) = set(
        when (error) {
            null -> State.Ready
            // Refused or no route: the TV is off, elsewhere, or debugging is off
            is ConnectException, is NoRouteToHostException -> State.Unreachable
            is SocketTimeoutException ->
                if (error.message.orEmpty().contains("connect", ignoreCase = true)) State.Unreachable else State.NotAllowed
            else -> State.Failed(error.message ?: error.javaClass.simpleName)
        }
    )

    /** Opens the connection on the worker thread; null if it works, otherwise what went wrong. */
    private fun open(): Exception? = try {
        closeAdb()
        val dir = File(app.filesDir, "adb").apply { mkdirs() }
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        // Waits up to a minute for the debugging question on the TV to be answered
        val d = Dadb.create(host, 5555, AdbKeyPair.read(private, public), 5_000, 60_000)
        d.shell("echo ok")
        adb = d
        null
    } catch (e: Exception) {
        closeAdb()
        e
    }

    /** Presses a key on the TV (Android key code, e.g. 19 for up). */
    fun key(code: Int) = run {
        val d = adb ?: return@run
        try {
            d.shell("input keyevent $code")
        } catch (e: Exception) {
            // The connection broke off, e.g. after the watch slept: once more with a new one
            val error = open()
            state(error)
            if (error == null) runCatching { adb?.shell("input keyevent $code") }
        }
    }

    fun stop() {
        releaseNetwork()
        run { closeAdb() }
        worker.shutdown()
    }

    private fun releaseNetwork() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        cm.bindProcessToNetwork(null)
    }

    private fun closeAdb() {
        runCatching { adb?.close() }
        adb = null
    }

    companion object {
        const val UP = 19
        const val DOWN = 20
        const val LEFT = 21
        const val RIGHT = 22
        const val OK = 23
        const val BACK = 4
        const val HOME = 3
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val MUTE = 164
        const val PLAY_PAUSE = 85
    }
}
