package dev.wellbeing.phone.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.wellbeing.phone.R
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.record.Command
import dev.wellbeing.phone.record.Finished
import dev.wellbeing.phone.record.RunController
import dev.wellbeing.running.Format
import dev.wellbeing.running.Snapshot
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun hasFineLocation(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun StatusRow(icon: Icon, title: String, detail: String, pastel: Pastel?) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().clip(Radius.tile).background(c.surface).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(pastel?.fill ?: c.surface2), contentAlignment = Alignment.Center) {
            AppIcon(icon, pastel?.ink ?: c.ink3)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = Type.strong, color = c.ink)
            Text(detail, style = Type.small, color = c.ink2)
        }
    }
}

/**
 * The ready screen: it opens the session (which starts looking for the position) as soon as
 * precise location is granted, and closes it again if the runner leaves without starting.
 */
@Composable
fun ReadyScreen(s: Snapshot?, settings: Settings) {
    val c = LocalColors.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasFineLocation(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasFineLocation(context)
        refused = !granted
    }
    // Coming back from the system settings may have changed the permission.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasFineLocation(context) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    DisposableEffect(granted) {
        if (granted) RunController.prepare(context)
        onDispose { RunController.leaveReady() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Eyebrow(if (settings.profileName.isNotEmpty()) stringResource(R.string.ready_profile, settings.profileName) else stringResource(R.string.ready_no_profile))
            Text(stringResource(R.string.ready_title), style = Type.h1, color = c.ink, modifier = Modifier.padding(top = 6.dp))
        }
        if (!granted) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.perm_title), style = Type.h3, color = c.ink)
                    Text(stringResource(R.string.perm_body), style = Type.body, color = c.ink2)
                    AppButton(stringResource(R.string.perm_grant), {
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION))
                    }, kind = ButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth())
                    // After a refusal Android may no longer show its dialog: offer the settings page.
                    if (refused) AppButton(stringResource(R.string.perm_settings), {
                        context.startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
            return@Column
        }
        val ok = s?.gpsReady == true
        StatusRow(
            Icon.PIN,
            stringResource(if (ok) R.string.gps_ok else R.string.gps_wait),
            if (ok) stringResource(R.string.gps_ok_detail, s.gpsAccuracy?.roundToInt() ?: 0) else stringResource(R.string.gps_wait_detail),
            if (ok) c.mint else c.butter,
        )
        val hr = s?.heartRate
        StatusRow(
            Icon.WATCH,
            stringResource(if (hr != null) R.string.watch_on else R.string.watch_off),
            if (hr != null) stringResource(R.string.watch_on_detail, hr) else stringResource(R.string.watch_off_detail),
            if (hr != null) c.mint else null,
        )
        StatusRow(Icon.PAUSE, stringResource(if (settings.autoPause) R.string.autopause_on else R.string.autopause_off), stringResource(R.string.autopause_detail), null)
        Spacer(Modifier.weight(1f))
        val start = stringResource(R.string.start)
        Column(
            Modifier.align(Alignment.CenterHorizontally).size(148.dp).clip(CircleShape).background(c.accent)
                .clickable(role = Role.Button) { RunController.command(Command.START) }.semantics { contentDescription = start },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(Icon.PLAY, c.onAccent, 34.dp)
            Text(start, style = Type.h2, color = c.onAccent)
        }
        Text(
            stringResource(if (ok) R.string.start_hint_ready else R.string.start_hint_wait),
            style = Type.small, color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        )
    }
}

@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit, placeholder: String, lines: Int = 1) {
    val c = LocalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.ink2)
        BasicTextField(
            value, onChange,
            textStyle = Type.body.copy(color = c.ink),
            singleLine = lines == 1, minLines = lines, maxLines = if (lines == 1) 1 else 6,
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(Modifier.clip(Radius.row).background(c.surface2).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.ink3)
                    inner()
                }
            },
        )
    }
}

/** Ten chips for the perceived effort; tapping the selected one clears it. */
@Composable
fun RpePicker(value: Int?, onChange: (Int?) -> Unit) {
    val c = LocalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (value != null) stringResource(R.string.rpe_value, value) else stringResource(R.string.rpe), style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.ink2)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..10) {
                val on = value == i
                Box(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.peach.fill else c.surface2)
                        .clickable(role = Role.RadioButton) { onChange(if (on) null else i) },
                    contentAlignment = Alignment.Center,
                ) { Text(i.toString(), style = Type.h3, color = if (on) c.peach.ink else c.ink2) }
            }
        }
    }
}

/** A sheet rising from the bottom, for confirmations and short forms. */
@Composable
fun BottomSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x73121118)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().imePadding().clip(RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp)).background(c.surface)
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
                .padding(22.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
fun ConfirmSheet(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalColors.current
    BottomSheet(onDismiss) {
        Text(title, style = Type.h2, color = c.ink)
        Text(body, style = Type.body, color = c.ink2)
        AppButton(confirm, onConfirm, kind = ButtonKind.DANGER, modifier = Modifier.fillMaxWidth())
        AppButton(stringResource(R.string.cancel), onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

/** A run is too short to be worth keeping below these figures. */
private const val SHORT_RUN_MS = 10_000L
private const val SHORT_RUN_M = 20.0

@Composable
fun SummaryScreen(f: Finished, settings: Settings, onSaved: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val u = settings.units
    var title by rememberSaveable { mutableStateOf("") }
    var rpe by rememberSaveable { mutableStateOf<Int?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    val short = f.summary.movingMs < SHORT_RUN_MS || f.summary.distance < SHORT_RUN_M
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Eyebrow(stringResource(R.string.summary_eyebrow))
                Text("${Format.distance(f.summary.distance, u)} ${Format.distanceUnit(u)}", style = Type.h1, color = c.ink)
            }
            if (short) Row(Modifier.fillMaxWidth().clip(Radius.row).background(c.butter.fill).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Icon.ALERT, c.butter.ink, 18.dp)
                Text(stringResource(R.string.summary_short), style = Type.small, color = c.butter.ink, modifier = Modifier.padding(start = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniTile(stringResource(R.string.f_time), Format.duration(f.summary.movingMs / 1000.0), "", c.mint, Modifier.weight(1f))
                MiniTile(stringResource(R.string.f_avg_pace), Format.pace(f.summary.avgPace, u), Format.paceUnit(u), c.lav, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniTile(stringResource(R.string.f_gain), Format.elevation(f.summary.gain, u), Format.elevationUnit(u), c.sky, Modifier.weight(1f))
                MiniTile(stringResource(R.string.avg_hr), f.summary.avgHeartRate?.toString() ?: Format.PLACEHOLDER, if (f.summary.avgHeartRate != null) stringResource(R.string.unit_bpm) else "", c.rose, Modifier.weight(1f))
            }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput(stringResource(R.string.title), title, { title = it }, stringResource(R.string.title_default))
                    RpePicker(rpe) { rpe = it }
                    TextInput(stringResource(R.string.note), note, { note = it }, stringResource(R.string.note_hint), lines = 3)
                }
            }
            Card {
                Column {
                    Text(stringResource(R.string.laps), style = Type.h3, color = c.ink, modifier = Modifier.padding(bottom = 8.dp))
                    f.run.laps.forEachIndexed { i, l ->
                        LapRow(
                            stringResource(R.string.lap_n_dist, i + 1, Format.distance(l.distance, u), Format.distanceUnit(u)),
                            Format.duration(l.movingMs / 1000.0),
                            Format.pace(if (l.distance > 1) l.movingMs / 1000.0 / (l.distance / 1000) else null, u) + " " + Format.paceUnit(u),
                            tinted = i % 2 == 0,
                        )
                    }
                }
            }
            // A very short run is discarded by default: deleting comes first.
            val saveButton: @Composable () -> Unit = {
                AppButton(stringResource(R.string.save), { scope.launch { RunController.save(title, rpe, note); onSaved() } }, kind = if (short) ButtonKind.TONAL else ButtonKind.PRIMARY, icon = Icon.CHECK, modifier = Modifier.fillMaxWidth())
            }
            val discardButton: @Composable () -> Unit = {
                AppButton(stringResource(R.string.discard), { confirmDiscard = true }, kind = ButtonKind.DANGER, icon = Icon.TRASH, modifier = Modifier.fillMaxWidth())
            }
            if (short) { discardButton(); saveButton() } else { saveButton(); discardButton() }
        }
        if (confirmDiscard) ConfirmSheet(
            stringResource(R.string.discard_title), stringResource(R.string.discard_body), stringResource(R.string.delete),
            onConfirm = { confirmDiscard = false; scope.launch { RunController.discard() } },
            onDismiss = { confirmDiscard = false },
        )
    }
}
