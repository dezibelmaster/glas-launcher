package de.glas.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text

/** D-pad, OK, back, home, mute and play/pause on the screen; the crown or bezel sets the volume. */
@Composable
fun RemoteScreen() {
    val ctx = LocalContext.current
    val remote = remember { TvRemote(ctx) }
    DisposableEffect(Unit) {
        remote.start(Prefs(ctx).tvHost)
        onDispose { remote.stop() }
    }
    Box(
        Modifier.fillMaxSize().background(sky(null, false)),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = remote.state) {
            TvRemote.State.Ready -> Pad(remote)
            TvRemote.State.Wifi -> Waiting(stringResource(R.string.wifi_wait), null)
            is TvRemote.State.Connecting -> Waiting(stringResource(R.string.connecting, s.host), stringResource(R.string.allow_hint))
            else -> {
                val message = when (s) {
                    TvRemote.State.NoWifi -> stringResource(R.string.no_wifi)
                    TvRemote.State.Unreachable -> stringResource(R.string.unreachable)
                    TvRemote.State.NotAllowed -> stringResource(R.string.not_allowed) + "\n" + stringResource(R.string.allow_hint)
                    is TvRemote.State.Failed -> stringResource(R.string.failed, s.reason)
                    else -> ""
                }
                Column(Modifier.padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                    CompactChip(
                        modifier = Modifier.padding(top = 8.dp),
                        label = { Text(stringResource(R.string.retry)) },
                        colors = glassChip(),
                        onClick = { remote.start(Prefs(ctx).tvHost) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Waiting(message: String, hint: String?) {
    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), indicatorColor = Color.White)
        Text(message, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        if (hint != null) Text(hint, color = Dim, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Pad(remote: TvRemote) {
    val haptic = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    // Rotation adds up; one volume step per notch-sized turn
    val turned = remember { floatArrayOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { e ->
                turned[0] += e.verticalScrollPixels
                while (turned[0] > 48f) { turned[0] -= 48f; remote.key(TvRemote.VOLUME_UP); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                while (turned[0] < -48f) { turned[0] += 48f; remote.key(TvRemote.VOLUME_DOWN); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        fun press(code: Int) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            remote.key(code)
        }
        Key("OK", 54.dp, 0.dp, 0.dp, strong = true) { press(TvRemote.OK) }
        Key("▲", 38.dp, 0.dp, (-58).dp) { press(TvRemote.UP) }
        Key("▼", 38.dp, 0.dp, 58.dp) { press(TvRemote.DOWN) }
        Key("◀", 38.dp, (-58).dp, 0.dp) { press(TvRemote.LEFT) }
        Key("▶", 38.dp, 58.dp, 0.dp) { press(TvRemote.RIGHT) }
        Key("↩", 32.dp, (-50).dp, (-50).dp) { press(TvRemote.BACK) }
        Key("⌂", 32.dp, 50.dp, (-50).dp) { press(TvRemote.HOME) }
        Key("🔇", 32.dp, (-50).dp, 50.dp) { press(TvRemote.MUTE) }
        Key("⏯", 32.dp, 50.dp, 50.dp) { press(TvRemote.PLAY_PAUSE) }
    }
}

@Composable
private fun Key(label: String, size: Dp, x: Dp, y: Dp, strong: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.offset(x, y).size(size),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color.White.copy(alpha = if (strong) .30f else .16f),
            contentColor = Color.White,
        ),
    ) {
        Text(label, fontSize = if (strong) 16.sp else 15.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}
