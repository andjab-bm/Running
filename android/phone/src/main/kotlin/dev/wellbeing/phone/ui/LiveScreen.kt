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

/** What a field shows: its label, icon, colour family, value and unit. */
private class FieldView(val label: Int, val icon: Icon, val pastel: Pastel, val value: String, val unit: String = "")

@Composable
private fun fieldView(f: Field, s: Snapshot, settings: Settings): FieldView {
    val c = LocalColors.current
    val u = settings.units
    return when (f) {
        Field.TIME -> FieldView(R.string.f_time, Icon.CLOCK, c.mint, Format.duration(s.movingMs / 1000.0))
        Field.ELAPSED, Field.COMBINED -> FieldView(R.string.f_elapsed, Icon.CLOCK, c.mint, Format.duration(s.elapsedMs / 1000.0))
        Field.DISTANCE -> FieldView(R.string.f_distance, Icon.ROUTE, c.sky, Format.distance(s.distance, u), Format.distanceUnit(u))
        Field.PACE -> FieldView(R.string.f_pace, Icon.GAUGE, c.lav, Format.pace(s.pace, u), Format.paceUnit(u))
        Field.AVG_PACE -> FieldView(R.string.f_avg_pace, Icon.GAUGE, c.lav, Format.pace(s.avgPace, u), Format.paceUnit(u))
        Field.LAP_PACE -> FieldView(R.string.f_lap_pace, Icon.FLAG, c.lav, Format.pace(s.lapPace, u), Format.paceUnit(u))
        Field.HEART_RATE -> FieldView(R.string.f_hr, Icon.HEART, c.rose, s.heartRate?.toString() ?: Format.PLACEHOLDER, stringResource(R.string.unit_bpm))
        Field.CADENCE -> FieldView(R.string.f_cadence, Icon.STEPS, c.mint, s.cadence?.toString() ?: Format.PLACEHOLDER, stringResource(R.string.unit_spm))
        Field.GAIN -> FieldView(R.string.f_gain, Icon.MOUNTAIN, c.sky, Format.elevation(s.gain, u), Format.elevationUnit(u))
        Field.CLOCK -> FieldView(R.string.f_clock, Icon.CLOCK, c.butter, LocalTime.now().let { "%d:%02d".format(it.hour, it.minute) })
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
                Label(stringResource(R.string.f_elapsed), Icon.CLOCK, c.sky.ink)
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
                Text(Format.pace(b.pace, settings.units), style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink2, maxLines = 1)
                val h = (0.3 + 0.7 * (hi - b.pace) / (hi - lo)).toFloat()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(h).clip(RoundedCornerShape(8.dp, 8.dp, 4.dp, 4.dp))
                            .background(if (b.current) c.lav.ink.copy(alpha = 0.4f) else c.accent),
                    )
                }
                Text(b.n.toString(), style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink2)
            }
        }
    }
}

private const val MAX_BARS = 8

/** The area reserved for a map: until then, the shape of the trace so far, with no basemap. */
@Composable
fun MiniMap(track: List<Sample>, modifier: Modifier, live: Boolean = true) {
    val c = LocalColors.current
    Box(modifier.clip(Radius.tile).background(c.surface.copy(alpha = 0.92f))) {
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            if (track.size < 2) return@Canvas
            val lat0 = track.minOf { it.lat }; val lat1 = track.maxOf { it.lat }
            val lon0 = track.minOf { it.lon }; val lon1 = track.maxOf { it.lon }
            // Longitudes shrink with latitude: keep the trace in proportion.
            val kx = cos(Math.toRadians((lat0 + lat1) / 2))
            val w = max((lon1 - lon0) * kx, 1e-6); val h = max(lat1 - lat0, 1e-6)
            val k = minOf(size.width / w, size.height / h).toFloat()
            val ox = (size.width - (w * k).toFloat()) / 2; val oy = (size.height - (h * k).toFloat()) / 2
            fun at(p: Sample) = Offset(ox + ((p.lon - lon0) * kx * k).toFloat(), size.height - oy - ((p.lat - lat0) * k).toFloat())
            val step = max(1, track.size / 400)
            val path = Path()
            track.filterIndexed { i, _ -> i % step == 0 || i == track.lastIndex }.forEachIndexed { i, p ->
                val o = at(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, c.accent, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (live) drawCircle(c.accent, 5.dp.toPx(), at(track.last()))
        }
        if (track.size < 2 && live) {
            Canvas(Modifier.fillMaxSize().padding(22.dp)) {
                drawCircle(c.surface3, size.minDimension / 2.6f, style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 14f)), cap = StrokeCap.Round))
            }
        }
        if (live) Text(stringResource(R.string.minimap), style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink3, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp))
    }
}

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
    var locked by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState { 2 }
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
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (page == 0) {
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
            for (i in 0..1) {
                val on = pager.currentPage == i
                val label = stringResource(if (i == 0) R.string.page_metrics else R.string.page_lap)
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
                    RoundControl(Icon.FLAG, stringResource(R.string.act_lap), stringResource(R.string.act_lap), { RunController.command(Command.LAP) })
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
