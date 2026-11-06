package de.glas.smart

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Devices in memory, shared by the list and the detail screen. */
object Repo {
    var devices: List<Device> = emptyList()
    private val specs = HashMap<String, Spec>()

    /** Until when a device shows what the user just set, even if the cloud still reports the old state. */
    val pendingUntil = HashMap<String, Long>()

    fun find(id: String) = devices.firstOrNull { it.id == id }

    fun load(store: Store) {
        if (devices.isNotEmpty()) return
        val json = store.cachedDevices() ?: return
        devices = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { Device.from(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(store: Store) {
        val arr = JSONArray()
        for (d in devices) {
            val st = JSONArray()
            d.status.forEach { (k, v) -> st.put(JSONObject().put("code", k).put("value", v ?: JSONObject.NULL)) }
            arr.put(JSONObject().put("id", d.id).put("name", d.name).put("category", d.category).put("online", d.online)
                .put("uid", d.uid).put("product_name", d.product).put("home_name", d.homeName).put("status", st))
        }
        store.saveDevices(arr.toString())
    }

    fun spec(store: Store, id: String): Spec? = specs[id] ?: store.spec(id)?.also { specs[id] = it }
    fun putSpec(store: Store, id: String, s: Spec) {
        specs[id] = s
        store.saveSpec(id, s)
    }

    /** Takes fresh devices from the cloud but keeps states the user changed a moment ago. */
    fun merge(fresh: List<Device>) {
        val now = System.currentTimeMillis()
        for (f in fresh) {
            val old = find(f.id) ?: continue
            if ((pendingUntil[f.id] ?: 0) > now) f.status.putAll(old.status)
        }
        devices = fresh
    }

    /** Sends commands with an instant local update; reverts and reports if the cloud refuses. */
    fun send(ctx: Context, d: Device, commands: List<Pair<String, Any>>, done: () -> Unit) {
        if (commands.isEmpty()) return
        val store = Store(ctx)
        val before = HashMap(d.status)
        for ((k, v) in commands) d.status[k] = v
        pendingUntil[d.id] = System.currentTimeMillis() + 6000
        done()
        async({ store.api().send(d.id, commands) }) { r ->
            r.onFailure {
                d.status.clear()
                d.status.putAll(before)
                pendingUntil.remove(d.id)
                ctx.toast("${d.name}: ${Tuya.explain(it)}")
                done()
            }
            r.onSuccess { save(store) }
        }
    }
}

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var banner: TextView
    private var loading = false
    private var visible = false
    private val auto = Runnable { refresh(full = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (!store.configured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(28)) }
        scroll.addView(root)
        fitBars(scroll)

        // Header: home name, state, refresh and settings
        val head = horizontal()
        val titles = vertical()
        title = text(30f, face = light).apply { text = "Zuhause" }
        subtitle = text(14f, MUTED)
        titles.addView(title)
        titles.addView(subtitle)
        head.addView(titles, weight())
        head.addView(iconButton("👪") { startActivity(Intent(this, FamilyActivity::class.java)) }, wrap().margins(r = dp(8)))
        head.addView(iconButton("↻") { refresh(full = true) })
        head.addView(iconButton("⚙") { startActivity(Intent(this, SetupActivity::class.java)) }, wrap().margins(l = dp(8)))
        root.addView(head, matchWrap().margins(t = dp(8), b = dp(6)))

        banner = text(14f, TEXT).apply {
            visibility = View.GONE
            background = glass(16f, 0x33FF6B6B, 0x66FF6B6B)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { refresh(full = true) }
        }
        root.addView(banner, matchWrap().margins(t = dp(8)))

        content = vertical()
        root.addView(content, matchWrap())
        setContentView(scroll)

        Repo.load(store)
        render()
        refresh(full = store.roomsLoadedAt < System.currentTimeMillis() - 6 * 3600_000L)
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        visible = true
        render()
        cancel(auto)
        later(30_000, auto)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        cancel(auto)
    }

    private fun iconButton(symbol: String, onClick: () -> Unit) = text(22f).apply {
        text = symbol
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x14FFFFFF)
            setStroke(dp(1), 0x24FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ---- Loading ----

    private fun refresh(full: Boolean) {
        cancel(auto)
        if (visible) later(30_000, auto)
        if (loading) return
        loading = true
        if (Repo.devices.isEmpty()) subtitle.text = "Lade Geräte …"
        val api = store.api()
        async({ api.devices() }) { r ->
            loading = false
            r.onSuccess { list ->
                banner.visibility = View.GONE
                Repo.merge(list)
                Repo.save(store)
                render()
                loadSpecs()
                if (full) loadRooms()
            }
            r.onFailure {
                banner.text = "⚠  ${Tuya.explain(it)}\nAntippen zum Wiederholen"
                banner.visibility = View.VISIBLE
                if (Repo.devices.isEmpty()) subtitle.text = "Keine Verbindung"
            }
        }
    }

    /** Fetches the description of each device once; it tells which controls and units a device has. */
    private fun loadSpecs() {
        val missing = Repo.devices.filter { Repo.spec(store, it.id) == null }
        if (missing.isEmpty()) return
        val api = store.api()
        async({
            for (d in missing) runCatching { api.specs(d.id) }.onSuccess { Repo.putSpec(store, d.id, it) }
        }) { render() }
    }

    /** Rooms and scenes from Smart Life. Needs the Smart Home services in the cloud project; silently skipped otherwise. */
    private fun loadRooms() {
        val uids = Repo.devices.map { it.uid }.filter { it.isNotEmpty() }.distinct()
        if (uids.isEmpty()) return
        val api = store.api()
        async({
            val rooms = JSONArray()
            val scenes = JSONArray()
            var homeName = ""
            for (uid in uids) {
                val homes = runCatching { api.homes(uid) }.getOrDefault(emptyList())
                for (h in homes) {
                    if (homeName.isEmpty()) homeName = h.name
                    runCatching { api.rooms(h.id) }.getOrDefault(emptyList()).forEach {
                        rooms.put(JSONObject().put("name", if (homes.size > 1) "${h.name} · ${it.name}" else it.name).put("devices", JSONArray(it.deviceIds)))
                    }
                    runCatching { api.scenes(h.id) }.getOrDefault(emptyList()).forEach {
                        scenes.put(JSONObject().put("home", it.homeId).put("id", it.id).put("name", it.name))
                    }
                }
            }
            Triple(rooms, scenes, homeName)
        }) { r ->
            r.onSuccess { (rooms, scenes, name) ->
                store.rooms = rooms
                store.scenes = scenes
                store.homeName = name
                store.roomsLoadedAt = System.currentTimeMillis()
                render()
            }
        }
    }

    // ---- Drawing ----

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val devices = Repo.devices
        title.text = store.homeName.ifEmpty { "Zuhause" }

        val on = devices.count { it.online && it.isOn == true }
        if (devices.isNotEmpty()) {
            val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
            subtitle.text = "${devices.size} Geräte · $on an · Stand $time"
        }

        // Quick actions: all lights off, then the tap-to-run scenes from Smart Life
        val quick = ArrayList<View>()
        val litLights = devices.filter { it.online && it.kind == Kind.LIGHT && it.isOn == true }
        if (litLights.isNotEmpty()) quick += pill("💡 Alle Lichter aus (${litLights.size})", AMBER) { allOff(litLights) }
        val scenes = store.scenes
        for (i in 0 until scenes.length()) {
            val s = scenes.getJSONObject(i)
            val scene = Tuya.Scene(s.getString("home"), s.getString("id"), s.getString("name"))
            quick += pill("▶ ${scene.name}") { runScene(scene) }
        }
        if (quick.isNotEmpty()) content.addView(pillRow(quick), matchWrap().margins(t = dp(14)))

        if (devices.isEmpty()) {
            content.addView(text(16f, MUTED).apply {
                text = if (loading) "" else "Noch keine Geräte gefunden.\n\nIst das Smart-Life-Konto im Tuya-Projekt verknüpft? " +
                    "Details unter ⚙ Einstellungen."
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(60), dp(16), dp(16))
            })
            return
        }

        val hidden = store.hidden
        val shown = devices.filter { it.id !in hidden }
        val favs = store.favourites
        val groups = ArrayList<Pair<String, List<Device>>>()
        shown.filter { it.id in favs }.takeIf { it.isNotEmpty() }?.let { groups += "★ Favoriten" to it }

        val rooms = store.rooms
        if (store.byRoom && rooms.length() > 0) {
            val placed = HashSet<String>()
            for (i in 0 until rooms.length()) {
                val room = rooms.getJSONObject(i)
                val ids = room.getJSONArray("devices").let { a -> (0 until a.length()).map { a.getString(it) } }
                val list = shown.filter { it.id in ids }
                placed += ids
                if (list.isNotEmpty()) groups += room.getString("name") to list
            }
            val rest = shown.filter { it.id !in placed }
            // Devices from shared homes whose rooms Tuya doesn't reveal: grouped under the home's name
            for ((home, list) in rest.filter { it.homeName.isNotEmpty() }.groupBy { it.homeName }) groups += "🏠 $home" to list
            rest.filter { it.homeName.isEmpty() }.takeIf { it.isNotEmpty() }?.let { groups += "Ohne Raum" to it }
        } else {
            for (k in Kind.values()) {
                val list = shown.filter { it.kind == k }
                if (list.isNotEmpty()) groups += k.title to list
            }
        }
        if (store.showHidden) {
            devices.filter { it.id in hidden }.takeIf { it.isNotEmpty() }?.let { groups += "Ausgeblendet" to it }
        }

        for ((name, list) in groups) {
            content.addView(sectionTitle(name))
            grid(list.sortedWith(compareBy({ !it.online }, { it.name.lowercase() })))
        }
    }

    private fun grid(list: List<Device>) {
        for (i in list.indices step 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(card(list[i]), weight().margins(r = dp(6)))
            if (i + 1 < list.size) row.addView(card(list[i + 1]), weight().margins(l = dp(6)))
            else row.addView(View(this), weight().margins(l = dp(6)))
            content.addView(row, matchWrap().margins(b = dp(12)))
        }
    }

    private fun accent(d: Device) = if (d.kind == Kind.LIGHT) AMBER else GREEN

    private fun card(d: Device): View {
        val spec = Repo.spec(store, d.id)
        val on = d.online && d.isOn == true
        val accent = accent(d)
        val card = vertical().apply {
            minimumHeight = dp(132)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = if (on) glass(22f, (accent and 0x00FFFFFF) or 0x1F000000, (accent and 0x00FFFFFF) or 0x55000000)
            else glass()
            alpha = if (d.online) 1f else .5f
            isClickable = true
            setOnClickListener { open(d) }
            setOnLongClickListener { menu(d); true }
        }

        val top = horizontal()
        val iconColor = if (on && d.kind == Kind.LIGHT) d.lightColor(spec) else 0xFFFFFFFF.toInt()
        top.addView(text(22f).apply {
            text = d.emoji
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) (iconColor and 0x00FFFFFF) or 0x4D000000 else 0x14FFFFFF)
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(View(this), weight())
        if (d.online && d.isOn != null) {
            top.addView(PowerButton(this, accent).apply {
                this.on = on
                setOnClickListener { toggle(d) }
            }, FrameLayout.LayoutParams(dp(40), dp(40)))
        }
        card.addView(top, matchWrap())

        card.addView(text(16f, face = medium).apply {
            text = (if (d.id in store.favourites) "★ " else "") + d.name
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, matchWrap().margins(t = dp(12)))
        card.addView(text(13f, MUTED).apply {
            text = d.summary(spec)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, matchWrap().margins(t = dp(2)))
        return card
    }

    // ---- Actions ----

    private fun open(d: Device) = startActivity(Intent(this, DeviceActivity::class.java).putExtra("id", d.id))

    private fun toggle(d: Device) {
        val target = d.isOn != true
        Repo.send(this, d, d.powerCommands(target)) { render() }
    }

    private fun allOff(lights: List<Device>) {
        for (d in lights) Repo.send(this, d, d.powerCommands(false)) { render() }
    }

    private fun runScene(s: Tuya.Scene) {
        val api = store.api()
        async({ api.runScene(s) }) { r ->
            r.onSuccess {
                toast("„${s.name}“ ausgeführt")
                later(2500) { refresh(full = false) }
            }
            r.onFailure { toast("${s.name}: ${Tuya.explain(it)}") }
        }
    }

    private fun menu(d: Device) {
        val fav = d.id in store.favourites
        val hidden = d.id in store.hidden
        val items = arrayOf(
            if (fav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
            if (hidden) "Wieder anzeigen" else "Ausblenden",
            "Einstellungen des Geräts",
        )
        AlertDialog.Builder(this).setTitle(d.name).setItems(items) { _, which ->
            when (which) {
                0 -> store.toggleFavourite(d.id)
                1 -> {
                    store.toggleHidden(d.id)
                    if (!hidden) toast("Ausgeblendet. Wieder anzeigen unter ⚙ Einstellungen.")
                }
                2 -> open(d)
            }
            render()
        }.show()
    }
}
