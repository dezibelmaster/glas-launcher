package de.glas.smart

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.RemoteInput
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.input.RemoteInputIntentHelper

/** Results of the last place search, for the list of places. */
object PlaceSearch {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Place>?>(null)
    var failed by mutableStateOf(false)

    fun run(name: String) {
        query = name
        results = null
        failed = false
        val main = Handler(Looper.getMainLooper())
        Thread {
            val r = runCatching { Weather.search(name) }
            main.post {
                results = r.getOrNull() ?: emptyList()
                failed = r.isFailure
            }
        }.start()
    }
}

private const val KEY_TEXT = "text"

/** Opens the watch's keyboard or voice input; [done] gets what was entered. */
@Composable
private fun rememberTextInput(done: (String) -> Unit): (String) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = r.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        if (!text.isNullOrEmpty()) done(text)
    }
    return { label ->
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(KEY_TEXT).setLabel(label).build()))
        launcher.launch(intent)
    }
}

@Composable
fun SettingsScreen(onResults: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    // Changed values show at once
    var version by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(false) }
    var locationFailed by remember { mutableStateOf(false) }
    val searchLabel = stringResource(R.string.search_hint)
    val tvLabel = stringResource(R.string.tv_hint)
    val myLocation = stringResource(R.string.my_location_name)

    val search = rememberTextInput { name ->
        PlaceSearch.run(name)
        onResults()
    }
    val tvInput = rememberTextInput { host ->
        prefs.tvHost = host
        version++
    }
    fun locate() {
        locating = true
        locationFailed = false
        currentLocation(ctx) { lat, lon ->
            locating = false
            if (lat == null || lon == null) {
                locationFailed = true
            } else {
                prefs.place = Place(myLocation, "", lat, lon)
                WeatherStore.reset(ctx)
                version++
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else locationFailed = true
    }

    val list = rememberScalingLazyListState()
    Scaffold(
        modifier = Modifier.background(sky(null, false)),
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = list) },
    ) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Read so that the list is drawn anew after a change
            val place = version.let { prefs.place }
            item { ListHeader { Text(stringResource(R.string.place)) } }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.search_place)) },
                    secondaryLabel = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Text("🔍", fontSize = 16.sp) },
                    colors = glassChip(),
                    onClick = { search(searchLabel) },
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.my_location)) },
                    secondaryLabel = when {
                        locating -> ({ Text(stringResource(R.string.searching)) })
                        locationFailed -> ({ Text(stringResource(R.string.no_location)) })
                        else -> null
                    },
                    icon = { Text("📍", fontSize = 16.sp) },
                    colors = glassChip(),
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                )
            }
            item { ListHeader { Text(stringResource(R.string.tv)) } }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tv_address)) },
                    secondaryLabel = { Text(version.let { prefs.tvHost }) },
                    icon = { Text("📺", fontSize = 16.sp) },
                    colors = glassChip(),
                    onClick = { tvInput(tvLabel) },
                )
            }
            item {
                val name = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
                Text(
                    stringResource(R.string.app_name) + " · " + stringResource(R.string.version, name),
                    color = Dim, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }
}

/** The places found; picking one makes it the place for the weather. */
@Composable
fun PlacesScreen(onPicked: () -> Unit) {
    val ctx = LocalContext.current
    val list = rememberScalingLazyListState()
    val results = PlaceSearch.results
    Scaffold(
        modifier = Modifier.background(sky(null, false)),
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = list) },
    ) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { ListHeader { Text(PlaceSearch.query, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            when {
                results == null -> item { Text(stringResource(R.string.searching), color = Dim) }
                PlaceSearch.failed -> item { Retry(R.string.load_failed) { PlaceSearch.run(PlaceSearch.query) } }
                results.isEmpty() -> item { Text(stringResource(R.string.no_results), color = Dim) }
                else -> items(results) { p ->
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(p.region, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = glassChip(),
                        onClick = {
                            Prefs(ctx).place = p
                            WeatherStore.reset(ctx)
                            onPicked()
                        },
                    )
                }
            }
        }
    }
}

/** A rough current position (a city's accuracy is plenty for the weather); null if there is none. */
@SuppressLint("MissingPermission")
private fun currentLocation(ctx: Context, done: (Double?, Double?) -> Unit) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (provider == null) return done(null, null)
    val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000L) return done(last.latitude, last.longitude)
    try {
        lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { loc ->
            val l = loc ?: last
            done(l?.latitude, l?.longitude)
        }
    } catch (e: Exception) {
        done(last?.latitude, last?.longitude)
    }
}
