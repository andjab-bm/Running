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
import dev.wellbeing.phone.data.SyncState
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(stringResource(R.string.tab_history), style = Type.h1, color = c.ink) }
        if (list.isEmpty()) item {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.history_empty_title), style = Type.h3, color = c.ink)
                    Text(stringResource(R.string.history_empty_body), style = Type.body, color = c.ink2)
                }
            }
        } else item {
            val t = Totals.of(list, month)
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
        items(list, key = { it.id }) { run ->
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
                    SyncTag(run)
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
            if (r.sync == SyncState.FAILED && r.failReason != null) Card(color = c.rose.fill) { Text(r.failReason, style = Type.strong, color = c.rose.ink) }
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
                        scope.launch { dao.updateRun(r.copy(title = title.trim(), rpe = rpe, note = note.trim(), journalDirty = r.sync == SyncState.SYNCED)) }
                    }, kind = ButtonKind.PRIMARY, icon = Icon.CHECK, modifier = Modifier.fillMaxWidth())
                    AppButton(stringResource(R.string.cancel), { sheet = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
