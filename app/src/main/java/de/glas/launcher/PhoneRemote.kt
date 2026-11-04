package de.glas.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * "Einstellen per Handy": a small web server in the launcher. The TV shows a QR code with the
 * address and a one-time key; the phone opens the page in its browser and changes the settings there.
 * Runs only while switched on in the settings and stops by itself after [IDLE_MINUTES] without use.
 */
object PhoneRemote {
    const val IDLE_MINUTES = 30
    private val PORTS = 8765..8775

    @Volatile private var server: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var lastUse = 0L
    @Volatile var key = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val phones = mutableSetOf<String>()
    private var wrongKeys = 0
    private var wrongKeysSince = 0L

    val running get() = server != null

    /** Called on the main thread: true when settings changed, false when the server started or stopped. */
    fun addListener(l: (Boolean) -> Unit) = listeners.add(l)
    fun removeListener(l: (Boolean) -> Unit) = listeners.remove(l)

    /** Tells the open screens that the phone changed something. */
    fun changed() = notify(true)

    private fun notify(settings: Boolean) = main.post { listeners.forEach { it(settings) } }

    /** Starts the server, if it isn't running yet. False if no port was free. */
    @Synchronized
    fun start(ctx: Context): Boolean {
        if (server != null) return true
        val app = ctx.applicationContext
        val socket = PORTS.firstNotNullOfOrNull { port ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
        } ?: return false
        key = newKey()
        phones.clear()
        lastUse = System.currentTimeMillis()
        val workers = Executors.newFixedThreadPool(6)
        server = socket
        pool = workers
        Thread({ acceptLoop(app, socket, workers) }, "glas-phone").start()
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 60_000)
        notify(false)
        return true
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
        pool?.shutdownNow()
        pool = null
        key = ""
        main.removeCallbacks(idleCheck)
        notify(false)
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (server == null) return
            if (System.currentTimeMillis() - lastUse > IDLE_MINUTES * 60_000L) stop()
            else main.postDelayed(this, 60_000)
        }
    }

    /** The address to open on the phone, with the key after the #, or null without a network. */
    fun url(): String? {
        val port = server?.localPort ?: return null
        return address()?.let { "http://$it:$port/#$key" }
    }

    /** The TV's address in the home network (WLAN or LAN cable). */
    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            // Prefer the usual home network names over VPNs and the like
            .sortedBy { n -> listOf("wlan", "eth").indexOfFirst { n.name.startsWith(it) }.let { if (it < 0) 9 else it } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** Short enough to type: 8 letters and digits without look-alikes like 0/O and 1/l. */
    private fun newKey(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    /** Whether a request may use the API. Too many wrong keys block everyone for a minute. */
    @Synchronized
    internal fun allowed(given: String?): Boolean {
        val now = System.currentTimeMillis()
        if (now - wrongKeysSince > 60_000) { wrongKeys = 0; wrongKeysSince = now }
        if (wrongKeys >= 10) return false
        val k = key
        val ok = given != null && k.isNotEmpty() && given.length == k.length &&
            given.indices.fold(0) { acc, i -> acc or (given[i].code xor k[i].code) } == 0
        if (!ok) wrongKeys++
        return ok
    }

    /** First request of a phone: say so on the TV, so nobody changes settings unnoticed. */
    internal fun used(ctx: Context, phone: String) {
        lastUse = System.currentTimeMillis()
        val first = synchronized(phones) { phones.add(phone) }
        if (first) main.post { ctx.toast(tr("Ein Handy ist mit dem Glas Launcher verbunden ({0})", phone)) }
    }

    // ---- HTTP ----

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        private val headers: Map<String, String>,
        val input: InputStream,
        val length: Long,
        val phone: String,
    ) {
        fun header(name: String) = headers[name.lowercase()]

        /** The whole body as text; only for small requests, not for uploads. */
        fun text(limit: Int = 2_000_000): String {
            if (length > limit) throw HttpError(413, tr("Die Anfrage ist zu groß"))
            val buf = ByteArray(length.toInt())
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) throw IOException("body cut off")
                read += n
            }
            return String(buf, Charsets.UTF_8)
        }
    }

    class Response(val code: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    class HttpError(val code: Int, message: String) : Exception(message)

    private fun acceptLoop(ctx: Context, socket: ServerSocket, workers: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            } catch (e: IOException) {
                continue
            }
            runCatching { workers.execute { serve(ctx, client) } }.onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(ctx: Context, client: Socket) = client.use { s ->
        try {
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                if (headers.size > 100) return
            }
            val target = parts[1]
            val path = decode(target.substringBefore('?'))
            val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
            }
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            val phone = s.inetAddress?.hostAddress ?: "?"
            val req = Request(parts[0].uppercase(), path, query, headers, input, length, phone)
            val res = try {
                PhoneApi.handle(ctx, req)
            } catch (e: HttpError) {
                PhoneApi.error(e.code, e.message ?: "")
            } catch (e: Exception) {
                PhoneApi.error(500, tr("Das hat nicht geklappt ({0})", e.message ?: e.javaClass.simpleName))
            }
            write(s, res)
        } catch (e: IOException) {
            // The phone went away; nothing to answer
        }
    }

    private fun write(s: Socket, res: Response) {
        val reason = when (res.code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val head = StringBuilder()
            .append("HTTP/1.1 ${res.code} $reason\r\n")
            .append("Content-Type: ${res.type}\r\n")
            .append("Content-Length: ${res.body.size}\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("Connection: close\r\n")
        for ((k, v) in res.headers) head.append("$k: $v\r\n")
        head.append("\r\n")
        val out = s.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(res.body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16_384) throw IOException("line too long")
        }
        return buf.toString("UTF-8")
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    // ---- On the TV ----

    /** Starts the server if needed and shows the QR code to scan with the phone. */
    fun show(activity: Activity) {
        if (address() == null) return activity.toast(tr("Der Fernseher ist mit keinem Netzwerk verbunden"))
        if (!start(activity)) return activity.toast(tr("Einstellen per Handy ließ sich nicht starten"))
        val url = url() ?: return

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val qrSize = activity.dp(240)
        val qr = ImageView(activity).apply {
            setImageBitmap(qrCode(url, qrSize))
            setBackgroundColor(Color.WHITE)
            val pad = activity.dp(10)
            setPadding(pad, pad, pad, pad)
        }
        val steps = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(activity, 16f).apply {
                text = tr("1. Handy ins selbe WLAN wie den Fernseher\n2. QR-Code mit der Kamera scannen\n3. Oder diese Adresse im Browser öffnen:")
                setLineSpacing(0f, 1.35f)
            })
            addView(Ui.text(activity, 19f).apply {
                text = url
                setPadding(0, activity.dp(12), 0, activity.dp(12))
                setTextIsSelectable(false)
            })
            addView(Ui.text(activity, 13f, color = Ui.MUTED).apply {
                text = tr("Die Adresse gilt, bis du „Einstellen per Handy“ ausschaltest, spätestens nach {0} Minuten ohne Nutzung.", IDLE_MINUTES)
                setLineSpacing(0f, 1.25f)
            })
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(qr, LinearLayout.LayoutParams(qrSize + activity.dp(20), qrSize + activity.dp(20)))
            addView(steps, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(28) })
        }
        val done = Ui.row(activity, tr("Fertig")) { dialog.dismiss() }
        val off = Ui.row(activity, tr("Ausschalten")) { dialog.dismiss(); stop(); activity.toast(tr("Einstellen per Handy ist aus")) }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(28), activity.dp(24), activity.dp(28), activity.dp(24))
            addView(Ui.text(activity, 22f).apply {
                text = tr("Einstellen per Handy")
                setPadding(0, 0, 0, activity.dp(16))
            })
            addView(top)
            addView(done, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(20) })
            addView(off, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(6) })
        }
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Ui.panel(activity))
        dialog.show()
        dialog.window?.setLayout(activity.dp(820), WRAP_CONTENT)
        done.requestFocus()
    }

    private fun qrCode(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
