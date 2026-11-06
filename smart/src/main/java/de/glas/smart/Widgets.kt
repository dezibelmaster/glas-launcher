package de.glas.smart

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.RemoteViews
import android.widget.ScrollView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Home screen widgets: one device to switch, or the last picture of a camera. */
object Widgets {
    const val ACTION_TOGGLE = "de.glas.smart.TOGGLE"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("glas_widgets", Context.MODE_PRIVATE)
    fun deviceOf(ctx: Context, widgetId: Int) = prefs(ctx).getString("w_$widgetId", null)
    fun bind(ctx: Context, widgetId: Int, deviceId: String) = prefs(ctx).edit().putString("w_$widgetId", deviceId).apply()
    fun unbind(ctx: Context, widgetId: Int) = prefs(ctx).edit().remove("w_$widgetId").apply()

    // ---- Camera stills, saved while the live picture runs in the app ----

    private fun snapshotFile(ctx: Context, id: String) = File(File(ctx.filesDir, "snapshots").apply { mkdirs() }, "$id.jpg")

    fun saveSnapshot(ctx: Context, deviceId: String, bmp: Bitmap) {
        val app = ctx.applicationContext
        thread {
            runCatching {
                snapshotFile(app, deviceId).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                prefs(app).edit().putLong("snap_$deviceId", System.currentTimeMillis()).apply()
            }
            later(0) { updateAll(app) }
        }
    }

    // ---- Drawing ----

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (cls in listOf(DeviceWidget::class.java, CameraWidget::class.java)) {
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, cls))) update(ctx, mgr, id)
        }
    }

    fun update(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
        val camera = mgr.getAppWidgetInfo(widgetId)?.provider?.className == CameraWidget::class.java.name
        Repo.load(Store(ctx))
        val deviceId = deviceOf(ctx, widgetId)
        val d = deviceId?.let { Repo.find(it) }
        mgr.updateAppWidget(widgetId, if (camera) cameraViews(ctx, widgetId, deviceId, d) else deviceViews(ctx, widgetId, d))
    }

    private fun deviceViews(ctx: Context, widgetId: Int, d: Device?): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_device)
        if (d == null) {
            v.setTextViewText(R.id.icon, "⚙️")
            v.setTextViewText(R.id.name, "Gerät wählen")
            v.setTextViewText(R.id.state, "Widget neu hinzufügen")
            return v
        }
        val store = Store(ctx)
        val on = d.online && d.isOn == true
        v.setTextViewText(R.id.icon, d.emoji)
        v.setTextViewText(R.id.name, d.name)
        v.setTextViewText(R.id.state, d.summary(Repo.spec(store, d.id)))
        v.setInt(R.id.root, "setBackgroundResource", when {
            !on -> R.drawable.widget_bg
            d.kind == Kind.LIGHT -> R.drawable.widget_bg_light
            else -> R.drawable.widget_bg_plug
        })
        // Switchable devices toggle on tap; everything else opens its page in the app
        val tap = if (d.isOn != null) {
            PendingIntent.getBroadcast(ctx, widgetId, Intent(ctx, DeviceWidget::class.java).setAction(ACTION_TOGGLE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(ctx, widgetId, Intent(ctx, DeviceActivity::class.java).putExtra("id", d.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        v.setOnClickPendingIntent(R.id.root, tap)
        return v
    }

    private fun cameraViews(ctx: Context, widgetId: Int, deviceId: String?, d: Device?): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_camera)
        v.setTextViewText(R.id.name, d?.name ?: "Kamera wählen")
        val file = deviceId?.let { snapshotFile(ctx, it) }
        val bmp = file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        if (bmp != null) {
            v.setImageViewBitmap(R.id.picture, bmp)
            v.setViewVisibility(R.id.empty, android.view.View.GONE)
            val at = prefs(ctx).getLong("snap_$deviceId", 0)
            v.setTextViewText(R.id.time, if (at > 0) SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(at)) else "")
        } else {
            v.setViewVisibility(R.id.empty, android.view.View.VISIBLE)
            v.setTextViewText(R.id.time, "")
        }
        if (deviceId != null) {
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, widgetId,
                Intent(ctx, LiveActivity::class.java).putExtra("id", deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return v
    }

    /** Fetches the current state of the widgets' devices, so they don't show something old. */
    fun refreshFromCloud(ctx: Context, widgetIds: IntArray, done: () -> Unit) {
        val store = Store(ctx)
        if (!store.configured) return done()
        Repo.load(store)
        val ids = widgetIds.toList().mapNotNull { deviceOf(ctx, it) }.distinct()
        thread {
            val api = store.api()
            for (id in ids) {
                val d = Repo.find(id) ?: continue
                runCatching { api.status(id) }.onSuccess {
                    d.status.putAll(it)
                    d.online = true
                }
            }
            Repo.save(store)
            later(0) {
                updateAll(ctx)
                done()
            }
        }
    }
}

class DeviceWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) Widgets.update(ctx, mgr, id)
        val pending = goAsync()
        Widgets.refreshFromCloud(ctx.applicationContext, ids) { pending.finish() }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach { Widgets.unbind(ctx, it) }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Widgets.ACTION_TOGGLE) return super.onReceive(ctx, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val app = ctx.applicationContext
        val store = Store(app)
        Repo.load(store)
        val d = Widgets.deviceOf(app, widgetId)?.let { Repo.find(it) } ?: return
        val pending = goAsync()
        thread {
            val api = store.api()
            // Ask for the real state first: someone may have switched it elsewhere in the meantime
            runCatching { api.status(d.id) }.onSuccess { d.status.putAll(it) }
            val commands = d.powerCommands(d.isOn != true)
            val result = runCatching { api.send(d.id, commands) }
            result.onSuccess { for ((k, v) in commands) d.status[k] = v }
            Repo.save(store)
            later(0) {
                result.onFailure { app.toast("${d.name}: ${Tuya.explain(it)}") }
                Widgets.updateAll(app)
                pending.finish()
            }
        }
    }
}

class CameraWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) Widgets.update(ctx, mgr, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach { Widgets.unbind(ctx, it) }
}

/** Shown when a widget is placed: pick the device it should show. */
class WidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Leaving without a choice removes the widget again
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        val store = Store(this)
        if (!store.configured) {
            toast("Bitte zuerst Glas Smart einrichten.")
            return finish()
        }
        Repo.load(store)
        val mgr = AppWidgetManager.getInstance(this)
        val camera = mgr.getAppWidgetInfo(widgetId)?.provider?.className == CameraWidget::class.java.name
        val devices = Repo.devices.filter { if (camera) it.isCamera else it.isOn != null }
            .sortedBy { it.name.lowercase() }

        val scroll = ScrollView(this)
        val root = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        scroll.addView(root)
        fitBars(scroll)
        setContentView(scroll)
        root.addView(text(26f, face = light).apply { text = if (camera) "Kamera wählen" else "Gerät wählen" },
            matchWrap().margins(t = dp(8), b = dp(12)))
        if (camera) root.addView(text(14f, MUTED).apply {
            text = "Android-Widgets können kein Video abspielen. Das Widget zeigt das letzte Bild, das du in Glas Smart " +
                "gesehen hast; ein Tipp öffnet das Live-Bild."
            setLineSpacing(0f, 1.15f)
        }, matchWrap().margins(b = dp(12)))
        if (devices.isEmpty()) root.addView(text(16f, MUTED).apply {
            text = "Keine passenden Geräte. Öffne Glas Smart einmal, damit die Geräteliste geladen wird."
        })
        for (d in devices) {
            root.addView(horizontal().apply {
                background = glass(18f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                gravity = Gravity.CENTER_VERTICAL
                addView(text(22f).apply { text = d.emoji }, wrap().margins(r = dp(12)))
                addView(text(17f).apply { text = d.name }, weight())
                isClickable = true
                setOnClickListener {
                    Widgets.bind(this@WidgetConfigActivity, widgetId, d.id)
                    Widgets.update(this@WidgetConfigActivity, mgr, widgetId)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }
            }, matchWrap().margins(b = dp(8)))
        }
    }
}
