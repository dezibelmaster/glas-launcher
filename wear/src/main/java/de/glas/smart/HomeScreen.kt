package de.glas.smart

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onRemote: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val f = WeatherStore.forecast
    val place = Prefs(ctx).place
    // Opens with the temperature in the middle
    val list = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Scaffold(
        modifier = Modifier.background(sky(f?.code, f?.isDay ?: true)),
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = list) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = list,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Header(place, f) }
            if (f == null) {
                item {
                    if (WeatherStore.loading) Text(stringResource(R.string.loading), color = Dim, textAlign = TextAlign.Center)
                    else Retry(R.string.load_failed) { WeatherStore.load(ctx, force = true) }
                }
            } else {
                items(f.warnings) { w ->
                    Glass(tint = Warn) {
                        Text("⚠ " + stringResource(w.label), color = Warn, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
                item { Hours(f) }
                item { Details(f) }
                item { Text(stringResource(R.string.days), color = Dim, fontSize = 12.sp) }
                items(f.days) { DayRow(it) }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.remote)) },
                    secondaryLabel = { Text(stringResource(R.string.remote_sub), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Text("📺", fontSize = 18.sp) },
                    colors = glassChip(),
                    onClick = onRemote,
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings)) },
                    icon = { Text("⚙", fontSize = 18.sp) },
                    colors = glassChip(),
                    onClick = onSettings,
                )
            }
            if (f != null) item { Footer(f) }
        }
    }
}

@Composable
fun glassChip() = ChipDefaults.chipColors(
    backgroundColor = Color.White.copy(alpha = .16f),
    contentColor = Color.White,
    secondaryContentColor = Dim,
    iconColor = Color.White,
)

@Composable
private fun Header(place: Place, f: Forecast?) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(place.name, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (f != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Weather.icon(f.code, f.isDay), fontSize = 30.sp)
                Spacer(Modifier.width(6.dp))
                Text("${f.temp}°", fontSize = 42.sp, fontWeight = FontWeight.Light, color = Color.White)
            }
            Text(stringResource(Weather.describe(f.code)), fontSize = 14.sp, color = Color.White)
            Text("↑ ${f.max}°   ↓ ${f.min}°", fontSize = 13.sp, color = Dim)
        }
    }
}

@Composable
private fun Hours(f: Forecast) {
    Glass {
        Column {
            Text(stringResource(R.string.hours), color = Dim, fontSize = 11.sp)
            for (row in f.hours.take(8).chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (h in row) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(h.time.take(2), color = Dim, fontSize = 11.sp)
                            Text(Weather.icon(h.code, h.isDay), fontSize = 16.sp)
                            Text("${h.temp}°", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Details(f: Forecast) {
    Glass {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(stringResource(R.string.feels_like), "${f.feelsLike}°")
            Line(stringResource(R.string.wind), "${f.wind} km/h · " + stringResource(R.string.gusts, f.gusts))
            Line(stringResource(R.string.humidity), "${f.humidity} %")
            Line(stringResource(R.string.sun), "↑ ${f.sunrise}  ↓ ${f.sunset}")
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Dim, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DayRow(d: Day) {
    val name = if (d.date == LocalDate.now()) stringResource(R.string.today)
    else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    Glass {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(44.dp), maxLines = 1)
            Text(Weather.icon(d.code, true), fontSize = 16.sp)
            Text(
                if (d.rain >= 20) "💧${d.rain}%" else "",
                color = Dim, fontSize = 10.sp, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text("${d.max}° ", color = Color.White, fontSize = 13.sp)
            Text("${d.min}°", color = Dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Footer(f: Forecast) {
    val ctx = LocalContext.current
    val time = DateFormat.getTimeFormat(ctx).format(Date(f.time))
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val stale = WeatherStore.error != null
        Text(
            stringResource(R.string.updated, time) + if (stale) " · " + stringResource(R.string.offline) else "",
            color = Dim, fontSize = 11.sp, textAlign = TextAlign.Center,
        )
        CompactChip(
            modifier = Modifier.padding(top = 4.dp),
            label = { Text(stringResource(if (WeatherStore.loading) R.string.loading else R.string.refresh)) },
            colors = glassChip(),
            onClick = { WeatherStore.load(ctx, force = true) },
        )
    }
}

@Composable
fun Retry(message: Int, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(message), color = Dim, textAlign = TextAlign.Center)
        CompactChip(
            modifier = Modifier.padding(top = 4.dp).wrapContentSize(),
            label = { Text(stringResource(R.string.retry)) },
            colors = glassChip(),
            onClick = onRetry,
        )
    }
}
