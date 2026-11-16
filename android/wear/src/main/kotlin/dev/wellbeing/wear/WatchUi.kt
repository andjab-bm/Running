package dev.wellbeing.wear

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wellbeing.running.Format
import dev.wellbeing.running.Phase
import dev.wellbeing.running.Tokens
import dev.wellbeing.running.Units
import dev.wellbeing.running.WatchProtocol
import dev.wellbeing.running.Zones
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private fun rgb(v: Int) = Color(0xFF000000 or v.toLong())

private val Ground = rgb(Tokens.Watch.GROUND)
private val ButtonFill = rgb(Tokens.Watch.BUTTON)
private val Primary = rgb(Tokens.Watch.PRIMARY)
private val OnPrimary = rgb(Tokens.Watch.ON_PRIMARY)
private val TimeInk = rgb(Tokens.Watch.TIME)
private val PaceInk = rgb(Tokens.Watch.PACE)
private val DistanceInk = rgb(Tokens.Watch.DISTANCE)
private val Muted = rgb(Tokens.Watch.MUTED)
private val AmbientInk = rgb(Tokens.Watch.AMBIENT)
private val Warning = rgb(Tokens.Watch.WARNING)
private val PausedInk = rgb(Tokens.Watch.PAUSED)
private val StopFill = rgb(Tokens.dark.getValue("p-rose"))
private val Good = rgb(Tokens.dark.getValue("i-mint"))
private val ArcIdle = Color(0xFF23222C)

private fun weight(w: Int) = Font(R.font.bricolage_grotesque, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private val Display = FontFamily(weight(500), weight(600), weight(700))

private const val PLAY = "M7 4l13 8-13 8V4z"
private const val PAUSE = "M8 4v16M16 4v16"
private const val STOP = "M7.5 5h9a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5z"
private const val FLAG = "M5 22V4M5 4h13l-2.5 4L18 12H5"
private const val PHONE = "M8.5 2h7a2.5 2.5 0 0 1 2.5 2.5v15a2.5 2.5 0 0 1 -2.5 2.5h-7a2.5 2.5 0 0 1 -2.5 -2.5v-15a2.5 2.5 0 0 1 2.5 -2.5zM11 18h2"
private const val CHECK = "M20 6L9 17l-5-5"

@Composable
private fun Glyph(path: String, tint: Color, size: Dp) {
    val p = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) { drawPath(p, tint, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/**
 * The whole watch app: one screen at a time, chosen from the state the phone sends. Sizes are
 * fractions of the screen width so the 40 mm and 44 mm watches show the same layout.
 */
@Composable
fun WatchApp(ambient: Boolean, permitted: Boolean, onGrant: () -> Unit) {
    val state by WatchSession.state.collectAsState()
    val stateAt by WatchSession.stateAt.collectAsState()
    val hr by WatchSession.heartRate.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ground), contentAlignment = Alignment.Center) {
        val w = maxWidth
        val density = LocalDensity.current
        fun style(fraction: Float, color: Color, weight: Int = 600) = TextStyle(
            fontFamily = Display, fontWeight = FontWeight(weight), color = color, textAlign = TextAlign.Center,
            fontSize = with(density) { (w * fraction).toSp() }, fontFeatureSettings = "tnum",
        )

        val s = state
        val active = s?.phase == Phase.RUNNING || s?.phase == Phase.PAUSED || s?.phase == Phase.AUTO_PAUSED
        // While dimmed the phone only updates once a minute: the figures are then simply older.
        val lost = !ambient && now - stateAt > WatchProtocol.LINK_LOST_MS

        when {
            lost -> Message(PHONE, Warning, stringResource(R.string.unreachable_title), stringResource(if (active) R.string.unreachable_run else R.string.unreachable_idle), w, ::style)
            s == null || s.phase == null -> Message(PHONE, Primary, stringResource(R.string.none_title), stringResource(R.string.none_body), w, ::style)
            s.phase == Phase.STOPPED -> Message(
                CHECK, Good, stringResource(R.string.finished_title),
                "${Format.distance(s.distance, units(s))} ${Format.distanceUnit(units(s))} · ${Format.duration(s.movingMs / 1000.0)}", w, ::style,
            )
            !permitted && !ambient -> Column(Modifier.padding(w * 0.14f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(w * 0.03f)) {
                BasicText(stringResource(R.string.perm_body), style = style(0.05f, Muted))
                val grant = stringResource(R.string.perm_grant)
                Box(Modifier.clip(CircleShape).background(Primary).clickable(role = Role.Button, onClick = onGrant).padding(horizontal = w * 0.08f, vertical = w * 0.035f)) {
                    BasicText(grant, style = style(0.055f, OnPrimary))
                }
            }
            s.phase == Phase.READY -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(w * 0.03f)) {
                BasicText(hr?.let { "$it ${stringResource(R.string.bpm)}" } ?: Format.PLACEHOLDER, style = style(0.06f, Warning))
                RoundButton(PLAY, stringResource(R.string.act_start), w * 0.32f, Primary, OnPrimary) { WatchSession.command(WatchProtocol.CommandType.START) }
                BasicText(stringResource(R.string.ready), style = style(0.044f, Muted))
            }
            ambient -> Metrics(s, hr, w, ambient = true, ::style)
            else -> {
                val pager = rememberPagerState { 2 }
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (page == 0) Metrics(s, hr, w, ambient = false, ::style) else Controls(s, w, ::style)
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = w * 0.05f), horizontalArrangement = Arrangement.spacedBy(w * 0.02f)) {
                    for (i in 0..1) Box(Modifier.size(w * 0.02f).clip(CircleShape).background(if (pager.currentPage == i) Color.White else Color(0xFF44424F)))
                }
            }
        }
        if (!ambient) Feedback(w, ::style)
    }
}

private fun units(s: WatchProtocol.State) = if (s.imperial) Units.IMPERIAL else Units.METRIC

@Composable
private fun Message(icon: String, tint: Color, title: String, body: String, w: Dp, style: (Float, Color, Int) -> TextStyle) {
    Column(Modifier.padding(horizontal = w * 0.14f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(w * 0.02f)) {
        Glyph(icon, tint, w * 0.16f)
        BasicText(title, style = style(0.08f, Color.White, 600))
        BasicText(body, style = style(0.054f, rgb(Tokens.dark.getValue("ink-2")), 500))
    }
}

/** Time on top, pace large in the centre, distance and heart rate below, zone arc on the rim. */
@Composable
private fun Metrics(s: WatchProtocol.State, hr: Int?, w: Dp, ambient: Boolean, style: (Float, Color, Int) -> TextStyle) {
    val u = units(s)
    val zone = hr?.let { runCatching { Zones(s.zoneBounds.map(Int::toDouble)).of(it) }.getOrNull() }
    val zoneColor = zone?.let { rgb(Tokens.zone(it)) }
    fun ink(c: Color) = if (ambient) AmbientInk else c

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!ambient) Canvas(Modifier.fillMaxSize()) {
            // Five segments along the lower rim; the current zone is lit in its colour.
            val r = size.width * 0.465f
            val stroke = size.width * 0.03f
            for (i in 0 until 5) drawArc(
                if (zone == i + 1 && zoneColor != null) zoneColor else ArcIdle, 140f + i * 54f, 44f, false,
                Offset(size.width / 2 - r, size.height / 2 - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                when (s.phase) { Phase.PAUSED -> stringResource(R.string.paused); Phase.AUTO_PAUSED -> stringResource(R.string.auto_paused); else -> "" },
                style = style(0.044f, ink(PausedInk), 700),
            )
            BasicText(Format.duration(s.movingMs / 1000.0), style = style(0.10f, ink(TimeInk), 600))
            BasicText(Format.pace(s.pace, u), style = style(0.27f, ink(PaceInk), if (ambient) 500 else 600))
            Row(horizontalArrangement = Arrangement.spacedBy(w * 0.07f), verticalAlignment = Alignment.Bottom) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(Format.distance(s.distance, u), style = style(0.11f, ink(DistanceInk), 600))
                    BasicText(" " + Format.distanceUnit(u), style = style(0.042f, ink(Muted), 600), modifier = Modifier.padding(bottom = w * 0.015f))
                }
                BasicText(hr?.toString() ?: Format.PLACEHOLDER, style = style(0.11f, ink(zoneColor ?: Muted), 600))
            }
        }
    }
}

@Composable
private fun Controls(s: WatchProtocol.State, w: Dp, style: (Float, Color, Int) -> TextStyle) {
    val paused = s.phase != Phase.RUNNING
    Row(horizontalArrangement = Arrangement.spacedBy(w * 0.04f), verticalAlignment = Alignment.CenterVertically) {
        Labelled(stringResource(R.string.act_lap), w, style) {
            RoundButton(FLAG, stringResource(R.string.act_lap), w * 0.24f, ButtonFill, Color.White, enabled = !paused) { WatchSession.command(WatchProtocol.CommandType.LAP) }
        }
        Labelled(stringResource(if (paused) R.string.act_resume else R.string.act_pause), w, style) {
            RoundButton(if (paused) PLAY else PAUSE, stringResource(if (paused) R.string.act_resume else R.string.act_pause), w * 0.32f, Primary, OnPrimary) {
                WatchSession.command(if (paused) WatchProtocol.CommandType.RESUME else WatchProtocol.CommandType.PAUSE)
            }
        }
        Labelled(stringResource(R.string.hold), w, style) { HoldButton(STOP, stringResource(R.string.act_stop), w * 0.24f) { WatchSession.command(WatchProtocol.CommandType.STOP) } }
    }
}

@Composable
private fun Labelled(label: String, w: Dp, style: (Float, Color, Int) -> TextStyle, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(w * 0.02f)) {
        content()
        BasicText(label, style = style(0.044f, Muted, 600))
    }
}

@Composable
private fun RoundButton(icon: String, description: String, size: Dp, fill: Color, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (enabled) fill else fill.copy(alpha = 0.35f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, if (enabled) tint else tint.copy(alpha = 0.35f), size * 0.42f) }
}

/** Stop only acts when held: a brush of the sleeve cannot end the run. */
@Composable
private fun HoldButton(icon: String, description: String, size: Dp, onConfirmed: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(size)
            .drawBehind {
                if (progress.value > 0f) drawArc(Warning, -90f, 360f * progress.value, false, style = Stroke(this.size.width * 0.07f, cap = StrokeCap.Round))
            }
            .padding(size * 0.06f).clip(CircleShape).background(StopFill)
            .semantics { contentDescription = description; role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch { progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing)); onConfirmed() }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, Warning, size * 0.42f) }
}

private const val HOLD_MS = 1100

/** Says, for a moment, whether the last command was applied by the phone. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Feedback(w: Dp, style: (Float, Color, Int) -> TextStyle) {
    var shown by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        WatchSession.feedback.collect { applied ->
            shown = applied
            launch { delay(1400); if (shown == applied) shown = null }
        }
    }
    shown?.let {
        BasicText(
            stringResource(if (it) R.string.applied else R.string.not_applied),
            style = style(0.046f, if (it) Good else Warning, 700),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = w * 0.13f),
        )
    }
}
