package dev.wellbeing.phone.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wellbeing.phone.R
import dev.wellbeing.phone.data.Field
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.record.Command
import dev.wellbeing.phone.record.RunController
import dev.wellbeing.running.Format
import dev.wellbeing.running.Phase
import dev.wellbeing.running.Sample
import dev.wellbeing.running.Snapshot
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.max

/** The time figures of the fields, apart from drawing so they are unit-tested. */
object FieldTime {
    /** Label of the field while running. The combined field counts moving time, labelled "Temps". */
    fun label(f: Field) = when (f) {
        Field.ELAPSED -> R.string.f_elapsed
        Field.TIME, Field.COMBINED -> R.string.f_time
        else -> null
    }

    /** Moving time stops with a pause; elapsed time does not. */
    fun value(f: Field, s: Snapshot): String? = when (f) {
        Field.ELAPSED -> Format.duration(s.elapsedMs / 1000.0)
        Field.TIME, Field.COMBINED -> Format.duration(s.movingMs / 1000.0)
        else -> null
    }
}

/** The pacer's figures as fields: a placeholder when the run has no pace target. */
object FieldPacer {
    /** `+0:12` ahead, `−0:30` behind. */
    fun gap(state: dev.wellbeing.running.Pacer.State?): String =
        state?.gapS?.let { (if (it >= 0) "+" else "−") + Format.duration(kotlin.math.abs(it)) } ?: Format.PLACEHOLDER

    fun target(state: dev.wellbeing.running.Pacer.State?, u: dev.wellbeing.running.Units): String =
        state?.let { Format.pace(it.targetPace, u) } ?: Format.PLACEHOLDER
}

/** What a field shows: its label, icon, colour family, value and unit. */
private class FieldView(val label: Int, val icon: Icon, val pastel: Pastel, val value: String, val unit: String = "")

@Composable
private fun fieldView(f: Field, s: Snapshot, settings: Settings): FieldView {
    val c = LocalColors.current
    val u = settings.units
    val pacer = RunController.coaching.collectAsState().value?.pacer
    return when (f) {
        Field.TIME -> FieldView(FieldTime.label(f)!!, Icon.CLOCK, c.mint, FieldTime.value(f, s)!!)
        Field.ELAPSED -> FieldView(FieldTime.label(f)!!, Icon.CLOCK, c.mint, FieldTime.value(f, s)!!)
        // The combined field shows moving time, so it stops with the run (change insights-and-coaching).
        Field.COMBINED -> FieldView(FieldTime.label(f)!!, Icon.CLOCK, c.mint, FieldTime.value(f, s)!!)
        Field.DISTANCE -> FieldView(R.string.f_distance, Icon.ROUTE, c.sky, Format.distance(s.distance, u), Format.distanceUnit(u))
        Field.PACE -> FieldView(R.string.f_pace, Icon.GAUGE, c.lav, Format.pace(s.pace, u), Format.paceUnit(u))
        Field.AVG_PACE -> FieldView(R.string.f_avg_pace, Icon.GAUGE, c.lav, Format.pace(s.avgPace, u), Format.paceUnit(u))
        Field.LAP_PACE -> FieldView(R.string.f_lap_pace, Icon.FLAG, c.lav, Format.pace(s.lapPace, u), Format.paceUnit(u))
        Field.HEART_RATE -> FieldView(R.string.f_hr, Icon.HEART, c.rose, s.heartRate?.toString() ?: Format.PLACEHOLDER, stringResource(R.string.unit_bpm))
        Field.CADENCE -> FieldView(R.string.f_cadence, Icon.STEPS, c.mint, s.cadence?.toString() ?: Format.PLACEHOLDER, stringResource(R.string.unit_spm))
        Field.GAIN -> FieldView(R.string.f_gain, Icon.MOUNTAIN, c.sky, Format.elevation(s.gain, u), Format.elevationUnit(u))
        Field.CLOCK -> FieldView(R.string.f_clock, Icon.CLOCK, c.butter, LocalTime.now().let { "%d:%02d".format(it.hour, it.minute) })
        Field.PACER_GAP -> FieldView(R.string.f_pacer_gap, Icon.TARGET, c.peach, FieldPacer.gap(pacer))
        Field.TARGET_PACE -> FieldView(R.string.f_target_pace, Icon.TARGET, c.peach, FieldPacer.target(pacer, u), Format.paceUnit(u))
    }
}

@Composable
private fun Label(text: String, icon: Icon?, color: Color, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { AppIcon(icon, color, 16.dp); Spacer(Modifier.width(6.dp)) }
        Text(text.uppercase(), style = Type.label, color = color, maxLines = 1)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Figure(value: String, unit: String, size: Int, ink: Color, unitInk: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = Type.figure(size), color = ink, maxLines = 1, softWrap = false)
        if (unit.isNotEmpty()) Text(unit, style = Type.strong, color = unitInk, modifier = Modifier.padding(start = 6.dp, bottom = (size / 7).dp), maxLines = 1)
    }
}

/** One field of the main screen. [size] is the figure size approved for that slot. */
@Composable
private fun FieldTile(f: Field, s: Snapshot, settings: Settings, track: List<Sample>, size: Int, modifier: Modifier, hero: Boolean = false) {
    val c = LocalColors.current
    val v = fieldView(f, s, settings)
    when {
        f == Field.COMBINED -> Row(modifier.clip(Radius.card).background(c.sky.fill).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Label(stringResource(v.label), Icon.CLOCK, c.sky.ink)
                Figure(v.value, "", 44, c.ink, c.ink2)
                Spacer(Modifier.height(6.dp))
                Label(stringResource(R.string.f_distance), Icon.ROUTE, c.sky.ink)
                Figure(Format.distance(s.distance, settings.units), Format.distanceUnit(settings.units), 44, c.ink, c.ink2)
            }
            Spacer(Modifier.width(14.dp))
            MiniMap(track, Modifier.weight(1f).fillMaxHeight())
        }
        f == Field.HEART_RATE && s.heartRate != null -> {
            val zone = settings.zones.of(s.heartRate!!)
            Column(modifier.clip(Radius.card).background(c.zone(zone)).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
                Label(stringResource(v.label), v.icon, c.zoneInk) {
                    Text("Z$zone", style = Type.h3, color = c.zoneInk, modifier = Modifier.clip(Radius.pill).background(c.zoneInk.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 1.dp))
                }
                Figure(v.value, v.unit, size, c.zoneInk, c.zoneInk)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (z in 1..5) Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.zoneInk.copy(alpha = if (z == zone) 1f else 0.16f)))
                }
            }
        }
        else -> Column(
            modifier.clip(Radius.card).background(v.pastel.fill).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = if (hero && f == Field.AVG_PACE) Arrangement.Top else Arrangement.Center,
        ) {
            Label(stringResource(v.label), v.icon, v.pastel.ink)
            Figure(v.value, v.unit, size, c.ink, c.ink2)
            if (hero && f == Field.AVG_PACE) SplitBars(s, settings, Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** One bar per completed split, taller when faster, plus a lighter bar for the split in progress. */
@Composable
private fun SplitBars(s: Snapshot, settings: Settings, modifier: Modifier) {
    val c = LocalColors.current
    val unitKm = settings.splitMeters / 1000.0
    class Bar(val n: Int, val pace: Double, val current: Boolean)
    val bars = (s.splitsMs.mapIndexed { i, ms -> Bar(i + 1, ms / 1000.0 / unitKm, false) } +
        listOfNotNull(s.currentSplitPace?.let { Bar(s.splitsMs.size + 1, it, true) })).takeLast(MAX_BARS)
    if (bars.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.BottomStart) { Text(stringResource(R.string.km_hint), style = Type.small, color = c.ink2) }
        return
    }
    val lo = bars.minOf { it.pace } - 8
    val hi = bars.maxOf { it.pace } + 8
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        for (b in bars) {
            Column(Modifier.weight(1f, fill = false).widthIn(max = 44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                // Figures use the full ink: the secondary ink falls short of 7:1 on the pastel fills.
                Text(Format.pace(b.pace, settings.units), style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink, maxLines = 1)
                val h = (0.3 + 0.7 * (hi - b.pace) / (hi - lo)).toFloat()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(h).clip(RoundedCornerShape(8.dp, 8.dp, 4.dp, 4.dp))
                            .background(if (b.current) c.lav.ink.copy(alpha = 0.4f) else c.accent),
                    )
                }
                Text(b.n.toString(), style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink)
            }
        }
    }
}

private const val MAX_BARS = 8

@Composable
private fun RoundControl(icon: Icon, label: String, description: String, onClick: () -> Unit, big: Boolean = false) {
    val c = LocalColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (big) 104.dp else 76.dp).clip(CircleShape).background(if (big) c.accent else c.surface)
                .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { AppIcon(icon, if (big) c.onAccent else c.ink, if (big) 40.dp else 28.dp) }
        if (!big) Text(label, style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink2, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The screens shown while a run is in progress: metrics and current lap, with the controls. */
@Composable
fun LiveScreen(s: Snapshot, settings: Settings) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val track by RunController.track.collectAsState()
    val coaching by RunController.coaching.collectAsState()
    var locked by rememberSaveable { mutableStateOf(false) }
    // A guided run opens on its step page, before the metrics and the lap.
    val guided = coaching?.steps?.isNotEmpty() == true
    // Step (guided runs only), main, lap, map.
    val pages = if (guided) 4 else 3
    val pager = rememberPagerState { pages }
    val mainPage = if (guided) 1 else 0
    val mapPage = pages - 1
    val screenOn by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = screenOn.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val miniMap = MiniMapInput(
        path = coaching?.path,
        style = basemapStyle(settings, c.isDark),
        active = visible && pager.settledPage == mainPage,
        onOpen = { scope.launch { pager.animateScrollToPage(mapPage) } },
    )
    val paused = s.phase != Phase.RUNNING
    val f = settings.fields

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (text, pastel) = when (s.phase) {
                Phase.RUNNING -> R.string.state_running to c.mint
                Phase.AUTO_PAUSED -> R.string.state_auto_paused to c.butter
                else -> R.string.state_paused to c.butter
            }
            Row(Modifier.clip(Radius.pill).background(pastel.fill).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(pastel.ink)); Spacer(Modifier.width(7.dp))
                Text(stringResource(text), style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = pastel.ink)
            }
            if (locked) Tag(stringResource(R.string.locked), Pastel(c.surface2, c.ink2), Icon.LOCK)
            Spacer(Modifier.weight(1f))
            AppIcon(Icon.PIN, if (s.gpsReady) c.mint.ink else c.ink3.copy(alpha = 0.5f), 18.dp)
            AppIcon(Icon.WATCH, if (s.heartRate != null) c.mint.ink else c.ink3.copy(alpha = 0.4f), 18.dp)
        }
        if (s.phase == Phase.AUTO_PAUSED || s.phase == Phase.PAUSED) {
            Row(Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp).fillMaxWidth().clip(Radius.row).background(c.butter.fill).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Icon.PAUSE, c.butter.ink, 18.dp); Spacer(Modifier.width(8.dp))
                Text(stringResource(if (s.phase == Phase.AUTO_PAUSED) R.string.banner_auto else R.string.banner_paused), style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.butter.ink)
            }
        }

        Box(Modifier.weight(1f)) {
            HorizontalPager(pager, userScrollEnabled = !locked, modifier = Modifier.fillMaxSize().alpha(if (paused) 0.62f else 1f)) { page ->
                val kind = if (guided) page else page + 1
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (kind == 0) StepPage(s, coaching!!, settings)
                    else if (kind == 3) {
                        // The map exists only while it is the page shown and the screen is on.
                        if (visible && pager.settledPage == page) MapPage(s, settings, track, coaching, locked) else MapPlaceholder()
                    } else if (kind == 1) CompositionLocalProvider(LocalMiniMap provides miniMap) {
                        coaching?.takeIf { it.pacer != null }?.let { PacerBar(it, settings) }
                        val bars = f[0] == Field.AVG_PACE
                        FieldTile(f[0], s, settings, track, if (bars) 76 else 92, Modifier.weight(if (bars) 1.7f else 1.25f).fillMaxWidth(), hero = true)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FieldTile(f[1], s, settings, track, 54, Modifier.weight(1f).fillMaxHeight())
                            FieldTile(f[2], s, settings, track, 54, Modifier.weight(1f).fillMaxHeight())
                        }
                        FieldTile(f[3], s, settings, track, 72, Modifier.weight(1.15f).fillMaxWidth())
                    } else LapPage(s, settings)
                }
            }
            // While locked, every touch on the figures is swallowed.
            if (locked) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.changes.any { it.pressed && !it.previousPressed }) Toast.makeText(context, R.string.locked_toast, Toast.LENGTH_SHORT).show()
                        e.changes.forEach { it.consume() }
                    }
                }
            })
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), horizontalArrangement = Arrangement.Center) {
            for (i in 0 until pages) {
                val on = pager.currentPage == i
                val label = stringResource(listOf(R.string.page_step, R.string.page_metrics, R.string.page_lap, R.string.page_map)[if (guided) i else i + 1])
                Box(Modifier.padding(horizontal = 4.dp).size(width = 26.dp, height = 16.dp).clickable(enabled = !locked) { scope.launch { pager.animateScrollToPage(i) } }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = if (on) 22.dp else 8.dp, height = 8.dp).clip(Radius.pill).background(if (on) c.accent else c.surface3))
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            when {
                locked -> HoldToConfirm({ locked = false }, stringResource(R.string.unlock_hold)) { p ->
                    // The pill fills from the left as the hold progresses.
                    Box(Modifier.clip(Radius.pill).background(c.surface).drawBehind { drawRect(c.accentSoft, size = size.copy(width = size.width * p)) }) {
                        Row(Modifier.padding(horizontal = 26.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(Icon.LOCK, c.ink, 20.dp); Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.unlock_hold), style = Type.strong, color = c.ink)
                        }
                    }
                }
                paused -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HoldToConfirm({ RunController.command(Command.STOP) }, stringResource(R.string.act_stop)) { p ->
                            Box(Modifier.size(76.dp).holdRing(p, c.rose.ink).clip(CircleShape).background(c.rose.fill), contentAlignment = Alignment.Center) { AppIcon(Icon.STOP, c.rose.ink, 28.dp) }
                        }
                        Text(stringResource(R.string.hold), style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                    }
                    RoundControl(Icon.PLAY, "", stringResource(R.string.act_resume), { RunController.command(Command.RESUME) }, big = true)
                    RoundControl(Icon.LOCK, stringResource(R.string.act_lock), stringResource(R.string.act_lock), { locked = true })
                }
                else -> {
                    val lapLabel = stringResource(if (guided && coaching?.complete == false) R.string.act_next_step else R.string.act_lap)
                    RoundControl(Icon.FLAG, lapLabel, lapLabel, { RunController.command(Command.LAP) })
                    RoundControl(Icon.PAUSE, "", stringResource(R.string.act_pause), { RunController.command(Command.PAUSE) }, big = true)
                    RoundControl(Icon.LOCK, stringResource(R.string.act_lock), stringResource(R.string.act_lock), { locked = true })
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LapPage(s: Snapshot, settings: Settings) {
    val c = LocalColors.current
    val u = settings.units
    Column(Modifier.weight(1.25f).fillMaxWidth().clip(Radius.card).background(c.lav.fill).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
        Label(stringResource(R.string.lap_n, s.lapNumber), Icon.FLAG, c.lav.ink)
        Figure(Format.duration(s.lapMovingMs / 1000.0), "", 92, c.ink, c.ink2)
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().clip(Radius.card).background(c.sky.fill).padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
            Label(stringResource(R.string.f_distance), null, c.sky.ink)
            Figure(Format.distance(s.lapDistance, u), Format.distanceUnit(u), 54, c.ink, c.ink2)
        }
        Column(Modifier.weight(1f).fillMaxHeight().clip(Radius.card).background(c.lav.fill).padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
            Label(stringResource(R.string.f_lap_pace), null, c.lav.ink)
            Figure(Format.pace(s.lapPace, u), Format.paceUnit(u), 54, c.ink, c.ink2)
        }
    }
    Column(Modifier.weight(1.15f).fillMaxWidth().clip(Radius.card).background(c.surface).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val last = s.lastLap
        Label(stringResource(if (last != null) R.string.laps_previous else R.string.laps_none), null, c.ink2)
        if (last == null) Text(stringResource(R.string.laps_hint), style = Type.small, color = c.ink2)
        else LapRow(stringResource(R.string.lap_n, s.lapNumber - 1), Format.duration(last.movingMs / 1000.0), Format.pace(if (last.distance > 1) last.movingMs / 1000.0 / (last.distance / 1000) else null, u) + " " + Format.paceUnit(u), tinted = true)
    }
}

@Composable
fun LapRow(name: String, time: String, pace: String, tinted: Boolean) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().clip(Radius.row).background(if (tinted) c.surface2 else Color.Transparent).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.small, color = c.ink, modifier = Modifier.weight(1.4f))
        Text(time, style = Type.figure(16), color = c.ink, modifier = Modifier.weight(1f))
        Text(pace, style = Type.figure(16), color = c.ink)
    }
}
