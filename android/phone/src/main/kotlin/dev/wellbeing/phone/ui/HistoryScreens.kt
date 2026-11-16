package dev.wellbeing.phone.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.wellbeing.phone.R
import dev.wellbeing.phone.data.AppDb
import dev.wellbeing.phone.data.LapEntity
import dev.wellbeing.phone.data.RunEntity
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.RunState
import dev.wellbeing.phone.data.ServerActivityEntity
import dev.wellbeing.phone.data.SyncState
import dev.wellbeing.phone.sync.Sync
import dev.wellbeing.phone.sync.SyncResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import dev.wellbeing.running.Format
import dev.wellbeing.running.Sample
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.FRENCH)
fun runDate(t: Long): String = dateFormat.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))

/** Totals of the runs started in the current week (from Monday) or the current month. */
data class Totals(val runs: Int, val distance: Double, val movingMs: Long) {
    companion object {
        fun of(runs: List<RunEntity>, month: Boolean, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Totals {
            val from = if (month) today.withDayOfMonth(1) else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val set = runs.filter { it.startT >= start }
            return Totals(set.size, set.sumOf { it.distance }, set.sumOf { it.movingMs })
        }
    }
}

@Composable
fun SyncTag(run: RunEntity) {
    val c = LocalColors.current
    when {
        run.sync == SyncState.FAILED -> Tag(stringResource(R.string.sync_failed), c.rose, Icon.ALERT)
        run.sync == SyncState.SYNCED && !run.journalDirty -> Tag(stringResource(R.string.sync_synced), c.mint, Icon.CHECK)
        else -> Tag(stringResource(R.string.sync_pending), c.butter, Icon.CLOCK)
    }
}

@Composable
private fun runTitle(run: RunEntity) = run.title.ifEmpty { stringResource(R.string.title_default) }

@Composable
fun HistoryScreen(settings: Settings, onOpen: (Long) -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val runs by remember { AppDb.get(context).runs().watchSaved() }.collectAsState(initial = null)
    var month by rememberSaveable { mutableStateOf(false) }
    val u = settings.units
    val list = runs ?: return

    val scope = rememberCoroutineScope()
    val report by Sync.last.collectAsState()
    val syncing by Sync.running.collectAsState()
    val remote by remember(settings.profileId) {
        settings.profileId?.let { AppDb.get(context).runs().watchServerActivities(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    // Activities already on this phone appear once: only the others are added from the server.
    val known = list.mapNotNull { it.serverActivityId }.toSet()
    val serverOnly = remote.filter { it.id !in known }
    val pending = list.count { it.sync == SyncState.PENDING || it.journalDirty }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tab_history), style = Type.h1, color = c.ink, modifier = Modifier.weight(1f))
                IconButton(Icon.REFRESH, stringResource(R.string.sync_now), { scope.launch { Sync.run(context) } })
            }
            val status = when {
                syncing -> stringResource(R.string.sync_running)
                else -> when (report?.result) {
                    SyncResult.DONE -> stringResource(R.string.sync_done)
                    SyncResult.UNREACHABLE -> stringResource(R.string.sync_unreachable)
                    SyncResult.METERED -> stringResource(R.string.sync_metered)
                    SyncResult.NOT_CONFIGURED -> stringResource(R.string.sync_not_configured)
                    SyncResult.NOT_OUR_SERVER -> stringResource(R.string.sync_not_ours)
                    SyncResult.PROFILE_MISSING -> stringResource(R.string.profile_missing)
                    null -> if (settings.serverUrl.isBlank()) stringResource(R.string.sync_not_configured) else ""
                }
            }
            val line = listOf(status, if (pending > 0) stringResource(R.string.sync_pending_count, pending) else "").filter { it.isNotEmpty() }.joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = Type.small, color = if (report?.result == SyncResult.PROFILE_MISSING) c.rose.ink else c.ink2, modifier = Modifier.padding(top = 4.dp))
        }
        if (list.isEmpty() && serverOnly.isEmpty()) item {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.history_empty_title), style = Type.h3, color = c.ink)
                    Text(stringResource(R.string.history_empty_body), style = Type.body, color = c.ink2)
                }
            }
        } else item {
            // Totals cover the whole profile: runs of this phone plus those only on the server.
            val t = Totals.of(list + serverOnly.map { RunEntity(state = RunState.SAVED, startT = it.startT, distance = it.distance, movingMs = it.movingMs) }, month)
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Segmented(listOf(stringResource(R.string.week) to !month, stringResource(R.string.month) to month), { month = it == 1 })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniTile(stringResource(R.string.runs), t.runs.toString(), "", c.butter, Modifier.weight(1f))
                        MiniTile(stringResource(R.string.f_distance), Format.distance(t.distance, u).dropLast(1), Format.distanceUnit(u), c.sky, Modifier.weight(1.2f))
                        MiniTile(stringResource(R.string.f_time), Format.duration(t.movingMs / 1000.0), "", c.mint, Modifier.weight(1.3f))
                    }
                }
            }
        }
        // One list in date order: negative ids are activities that exist only on the server.
        val rows = (list + serverOnly.map {
            RunEntity(id = -it.id, state = RunState.SAVED, startT = it.startT, distance = it.distance, movingMs = it.movingMs, title = it.title, sync = SyncState.SYNCED, serverActivityId = it.id)
        }).sortedByDescending { it.startT }
        items(rows, key = { it.id }) { run ->
            Row(
                Modifier.fillMaxWidth().clip(Radius.tile).background(c.surface).clickable(role = Role.Button) { onOpen(run.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(runTitle(run), style = Type.strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(runDate(run.startT), style = Type.small, color = c.ink2)
                    Text(
                        "${Format.distance(run.distance, u)} ${Format.distanceUnit(u)} · ${Format.duration(run.movingMs / 1000.0)} · ${Format.pace(paceOf(run), u)} ${Format.paceUnit(u)}",
                        style = Type.figure(13), color = c.ink2,
                    )
                    Spacer(Modifier.height(5.dp))
                    if (run.id < 0) Tag(stringResource(R.string.sync_server_only), Pastel(c.surface2, c.ink2), Icon.CLOUD) else SyncTag(run)
                }
                AppIcon(Icon.CHEVRON, c.ink3, 18.dp)
            }
        }
    }
}

private fun paceOf(run: RunEntity): Double? = if (run.distance < 20) null else run.movingMs / 1000.0 / (run.distance / 1000)

@Composable
fun RunPage(runId: Long, settings: Settings, onBack: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(context).runs() }
    val run by remember(runId) { dao.watchRun(runId) }.collectAsState(initial = null)
    var laps by remember { mutableStateOf<List<LapEntity>>(emptyList()) }
    var track by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val u = settings.units
    LaunchedEffect(runId) {
        laps = dao.laps(runId)
        track = dao.samples(runId).map { Sample(it.t, it.lat, it.lon, it.ele, it.dist, it.cadence, it.speed) }
    }
    if (runId < 0) {
        ServerRunPage(-runId, settings, onBack)
        return
    }
    val r = run ?: return

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(Icon.BACK, stringResource(R.string.back), onBack)
                Spacer(Modifier.weight(1f))
                SyncTag(r)
                IconButton(Icon.EDIT, stringResource(R.string.edit), { sheet = "edit" })
            }
            Column {
                Eyebrow(runDate(r.startT))
                Text(runTitle(r), style = Type.h1, color = c.ink)
            }
            if (r.sync == SyncState.FAILED && r.failReason != null) Card(color = c.rose.fill) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(r.failReason, style = Type.strong, color = c.rose.ink)
                    AppButton(stringResource(R.string.retry), {
                        scope.launch { dao.updateRun(r.copy(sync = SyncState.PENDING, failReason = null)); Sync.run(context) }
                    }, icon = Icon.REFRESH)
                }
            }
            MiniMap(track, Modifier.fillMaxWidth().height(190.dp), live = false)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniTile(stringResource(R.string.f_distance), Format.distance(r.distance, u), Format.distanceUnit(u), c.sky, Modifier.weight(1f))
                MiniTile(stringResource(R.string.f_time), Format.duration(r.movingMs / 1000.0), "", c.mint, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniTile(stringResource(R.string.f_avg_pace), Format.pace(paceOf(r), u), Format.paceUnit(u), c.lav, Modifier.weight(1f))
                MiniTile(stringResource(R.string.f_gain), Format.elevation(r.gain, u), Format.elevationUnit(u), c.sky, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniTile(stringResource(R.string.avg_hr), r.avgHr?.toString() ?: Format.PLACEHOLDER, if (r.avgHr != null) stringResource(R.string.unit_bpm) else "", c.rose, Modifier.weight(1f))
                MiniTile(stringResource(R.string.effort), r.rpe?.let { "$it/10" } ?: Format.PLACEHOLDER, "", c.peach, Modifier.weight(1f))
            }
            if (r.note.isNotEmpty()) Card {
                Column { Eyebrow(stringResource(R.string.note)); Text(r.note, style = Type.body, color = c.ink) }
            }
            Card {
                Column {
                    Text(stringResource(R.string.laps), style = Type.h3, color = c.ink, modifier = Modifier.padding(bottom = 8.dp))
                    laps.forEachIndexed { i, l ->
                        LapRow(
                            stringResource(R.string.lap_n_dist, i + 1, Format.distance(l.distance, u), Format.distanceUnit(u)),
                            Format.duration(l.movingMs / 1000.0),
                            Format.pace(if (l.distance > 1) l.movingMs / 1000.0 / (l.distance / 1000) else null, u) + " " + Format.paceUnit(u),
                            tinted = i % 2 == 0,
                        )
                    }
                }
            }
            AppButton(stringResource(R.string.share_fit), {
                val file = File(context.filesDir, r.fitPath ?: return@AppButton)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            }, icon = Icon.SHARE, modifier = Modifier.fillMaxWidth())
            AppButton(stringResource(R.string.delete_local), { sheet = "delete" }, kind = ButtonKind.DANGER, icon = Icon.TRASH, modifier = Modifier.fillMaxWidth())
        }

        when (sheet) {
            "delete" -> {
                val synced = r.sync == SyncState.SYNCED
                ConfirmSheet(
                    stringResource(if (synced) R.string.delete_synced_title else R.string.discard_title),
                    stringResource(if (synced) R.string.delete_synced_body else R.string.delete_pending_body),
                    stringResource(if (synced) R.string.delete_local else R.string.delete_forever),
                    onConfirm = {
                        sheet = null
                        scope.launch {
                            r.fitPath?.let { File(context.filesDir, it).delete() }
                            dao.deleteRun(r.id)
                            onBack()
                        }
                    },
                    onDismiss = { sheet = null },
                )
            }
            "edit" -> {
                var title by rememberSaveable { mutableStateOf(r.title) }
                var rpe by rememberSaveable { mutableStateOf(r.rpe) }
                var note by rememberSaveable { mutableStateOf(r.note) }
                BottomSheet({ sheet = null }) {
                    Text(stringResource(R.string.edit_title), style = Type.h2, color = c.ink)
                    TextInput(stringResource(R.string.title), title, { title = it }, stringResource(R.string.title_default))
                    RpePicker(rpe) { rpe = it }
                    TextInput(stringResource(R.string.note), note, { note = it }, stringResource(R.string.note_hint), lines = 3)
                    if (r.sync == SyncState.SYNCED) Text(stringResource(R.string.edit_synced_hint), style = Type.small, color = c.ink2)
                    AppButton(stringResource(R.string.save), {
                        sheet = null
                        // An uploaded run is marked so the change is sent at the next sync.
                        scope.launch {
                            dao.updateRun(r.copy(title = title.trim(), rpe = rpe, note = note.trim(), journalDirty = r.sync == SyncState.SYNCED))
                            if (r.sync == SyncState.SYNCED) Sync.schedule(context)
                        }
                    }, kind = ButtonKind.PRIMARY, icon = Icon.CHECK, modifier = Modifier.fillMaxWidth())
                    AppButton(stringResource(R.string.cancel), { sheet = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** An activity that exists only on the server: its summary, from the last list retrieved. */
@Composable
private fun ServerRunPage(activityId: Long, settings: Settings, onBack: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    var a by remember { mutableStateOf<ServerActivityEntity?>(null) }
    val u = settings.units
    LaunchedEffect(activityId) {
        a = settings.profileId?.let { AppDb.get(context).runs().watchServerActivities(it).first().firstOrNull { x -> x.id == activityId } }
    }
    val r = a ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icon.BACK, stringResource(R.string.back), onBack)
            Spacer(Modifier.weight(1f))
            Tag(stringResource(R.string.sync_server_only), Pastel(c.surface2, c.ink2), Icon.CLOUD)
        }
        Column {
            Eyebrow(runDate(r.startT))
            Text(r.title.ifEmpty { stringResource(R.string.title_default) }, style = Type.h1, color = c.ink)
        }
        Text(stringResource(R.string.server_only_note), style = Type.small, color = c.ink2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTile(stringResource(R.string.f_distance), Format.distance(r.distance, u), Format.distanceUnit(u), c.sky, Modifier.weight(1f))
            MiniTile(stringResource(R.string.f_time), Format.duration(r.movingMs / 1000.0), "", c.mint, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTile(stringResource(R.string.f_avg_pace), Format.pace(if (r.distance < 20) null else r.movingMs / 1000.0 / (r.distance / 1000), u), Format.paceUnit(u), c.lav, Modifier.weight(1f))
            MiniTile(stringResource(R.string.avg_hr), r.avgHr?.toString() ?: Format.PLACEHOLDER, if (r.avgHr != null) stringResource(R.string.unit_bpm) else "", c.rose, Modifier.weight(1f))
        }
    }
}