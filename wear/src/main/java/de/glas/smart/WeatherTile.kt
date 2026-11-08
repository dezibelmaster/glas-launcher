package de.glas.smart

import android.text.format.DateFormat
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.util.Date

/** Tile with the weather at a glance: symbol, temperature, today's highs and lows, warnings. Tap opens the app. */
class WeatherTile : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { done ->
            Thread {
                val f = runCatching { WeatherStore.get(applicationContext) }.getOrNull()
                done.set(tile(f))
            }.start()
            "weather tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { done ->
            done.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build())
            "weather tile resources"
        }

    private fun text(value: String, size: Float, color: Int = WHITE, bold: Boolean = false) =
        Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            )
            .build()

    private fun tile(f: Forecast?): TileBuilders.Tile {
        val place = Prefs(this).place
        val column = Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        column.addContent(text(place.name, 13f, DIM))
        if (f == null) {
            column.addContent(text(getString(R.string.load_failed), 14f))
        } else {
            column.addContent(text(Weather.icon(f.code, f.isDay) + " ${f.temp}°", 34f, bold = true))
            column.addContent(text(getString(Weather.describe(f.code)), 14f))
            column.addContent(text("↑ ${f.max}°   ↓ ${f.min}°", 13f, DIM))
            f.warnings.firstOrNull()?.let {
                column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
                column.addContent(text("⚠ " + getString(it.label), 13f, WARN, bold = true))
            }
            column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
            column.addContent(text(getString(R.string.updated, DateFormat.getTimeFormat(this).format(Date(f.time))), 11f, DIM))
        }

        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(column.build())
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            // Fresh weather every half hour
            .setFreshnessIntervalMillis(30 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    companion object {
        private const val RESOURCES = "1"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val DIM = 0xB3FFFFFF.toInt()
        private const val WARN = 0xFFFFC94A.toInt()
    }
}
