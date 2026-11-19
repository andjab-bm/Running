package dev.wellbeing.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.wellbeing.phone.R
import dev.wellbeing.phone.data.Field
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.SettingsStore
import dev.wellbeing.phone.data.ThemeChoice
import dev.wellbeing.phone.record.RunController
import dev.wellbeing.running.Format
import dev.wellbeing.running.Phase
import kotlinx.coroutines.launch

private enum class Tab(val label: Int, val icon: Icon) {
    RUN(R.string.tab_run, Icon.PLAY), PLAN(R.string.tab_plan, Icon.CALENDAR), HISTORY(R.string.tab_history, Icon.LIST), SETTINGS(R.string.tab_settings, Icon.SLIDERS)
}

/**
 * The whole phone interface. A run in progress, a stopped run waiting to be saved and an
 * interrupted run take over the screen; otherwise the three tabs are shown.
 */
@Composable
fun AppRoot(store: SettingsStore, onRunActive: (active: Boolean, keepScreenOn: Boolean) -> Unit) {
    val settings by store.flow.collectAsState(initial = Settings())
    val snapshot by RunController.snapshot.collectAsState()
    val finished by RunController.finished.collectAsState()
    val interrupted by RunController.interrupted.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.RUN) }
    var openRun by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current

    val s = snapshot
    val inRun = s != null && s.phase != Phase.READY && s.phase != Phase.STOPPED
    onRunActive(inRun, settings.keepScreenOn)

    WellbeingTheme(settings.theme) {
        val c = LocalColors.current
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                val f = finished
                when {
                    f != null -> SummaryScreen(f, settings) { tab = Tab.HISTORY; openRun = null }
                    inRun -> {
                        // Leaving a run by the back gesture would be an accident.
                        BackHandler {}
                        LiveScreen(s, settings)
                    }
                    tab == Tab.RUN && interrupted == null -> ReadyScreen(s, settings)
                    tab == Tab.PLAN -> PlanScreen(settings) { tab = Tab.RUN }
                    tab == Tab.HISTORY -> openRun?.let { id ->
                        BackHandler { openRun = null }
                        RunPage(id, settings) { openRun = null }
                    } ?: HistoryScreen(settings) { openRun = it }
                    tab == Tab.SETTINGS -> SettingsScreen(settings, store)
                    else -> Box(Modifier.fillMaxSize())
                }
                interrupted?.let { i ->
                    BottomSheet({}) {
                        Text(stringResource(R.string.interrupted_title), style = Type.h2, color = c.ink)
                        Text(
                            stringResource(R.string.interrupted_body, "${Format.distance(i.snapshot.distance, settings.units)} ${Format.distanceUnit(settings.units)}", Format.duration(i.snapshot.movingMs / 1000.0)),
                            style = Type.body, color = c.ink2,
                        )
                        AppButton(stringResource(R.string.interrupted_resume), { tab = Tab.RUN; RunController.resumeInterrupted(context) }, kind = ButtonKind.PRIMARY, icon = Icon.PLAY, modifier = Modifier.fillMaxWidth())
                        AppButton(stringResource(R.string.interrupted_end), { RunController.endInterrupted() }, icon = Icon.STOP, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (!inRun && finished == null) {
                Row(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in Tab.entries) {
                        val on = t == tab
                        Column(
                            Modifier.weight(1f).clip(Radius.row).background(if (on) c.accentSoft else c.surface)
                                .clickable(role = Role.Tab) { tab = t; openRun = null }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppIcon(t.icon, if (on) c.accent else c.ink3)
                            Text(stringResource(t.label), style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = if (on) c.accent else c.ink3)
                        }
                    }
                }
            } else Box(Modifier.navigationBarsPadding())
        }
    }
}

private fun fieldLabel(f: Field) = when (f) {
    Field.AVG_PACE -> R.string.f_avg_pace
    Field.PACE -> R.string.f_pace
    Field.LAP_PACE -> R.string.f_lap_pace
    Field.HEART_RATE -> R.string.f_hr
    Field.CADENCE -> R.string.f_cadence
    Field.TIME -> R.string.f_time
    Field.ELAPSED -> R.string.f_elapsed
    Field.DISTANCE -> R.string.f_distance
    Field.GAIN -> R.string.f_gain
    Field.CLOCK -> R.string.f_clock
    Field.PACER_GAP -> R.string.f_pacer_gap
    Field.TARGET_PACE -> R.string.f_target_pace
    Field.COMBINED -> R.string.f_combined
}

@Composable
fun SettingsScreen(settings: Settings, store: SettingsStore) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    fun change(f: (Settings) -> Settings) { scope.launch { store.update(f) } }
    var picking by remember { mutableStateOf<Int?>(null) }
    var coaching by rememberSaveable { mutableStateOf(false) }
    var serverOpen by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.tab_settings), style = Type.h1, color = c.ink)
            Segmented(
                listOf(stringResource(R.string.settings_general) to !coaching, stringResource(R.string.settings_coaching) to coaching),
                { i -> coaching = i == 1 },
            )
            if (coaching) {
                CoachingSettings(settings, ::change)
                return@Column
            }
            ServerRow(settings) { serverOpen = true }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_main_screen), style = Type.h3, color = c.ink)
                    listOf(R.string.slot_top, R.string.slot_left, R.string.slot_right, R.string.slot_bottom).forEachIndexed { i, slot ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(slot), style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.ink2)
                            Row(
                                Modifier.fillMaxWidth().clip(Radius.row).background(c.surface2).clickable(role = Role.DropdownList) { picking = i }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(fieldLabel(settings.fields[i])), style = Type.body, color = c.ink, modifier = Modifier.weight(1f))
                                AppIcon(Icon.CHEVRON, c.ink3, 18.dp)
                            }
                        }
                    }
                }
            }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_during), style = Type.h3, color = c.ink)
                    Toggle(stringResource(R.string.autopause), stringResource(R.string.autopause_detail), settings.autoPause) { v -> change { it.copy(autoPause = v) } }
                    Toggle(stringResource(R.string.keep_on), stringResource(R.string.keep_on_detail), settings.keepScreenOn) { v -> change { it.copy(keepScreenOn = v) } }
                }
            }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.appearance), style = Type.h3, color = c.ink)
                    val choices = listOf(ThemeChoice.LIGHT to R.string.theme_light, ThemeChoice.DARK to R.string.theme_dark, ThemeChoice.SYSTEM to R.string.theme_system)
                    Segmented(choices.map { stringResource(it.second) to (settings.theme == it.first) }, { i -> change { it.copy(theme = choices[i].first) } })
                }
            }
        }
        if (serverOpen) ServerSheet(settings, store) { serverOpen = false }
        picking?.let { slot ->
            BottomSheet({ picking = null }) {
                // The combined field needs the full width: it is only offered for the bottom slot.
                for (f in Field.entries.filter { it != Field.COMBINED || slot == 3 }) {
                    val on = settings.fields[slot] == f
                    Row(
                        Modifier.fillMaxWidth().clip(Radius.row).background(if (on) c.accentSoft else c.surface)
                            .clickable(role = Role.RadioButton) { picking = null; change { it.copy(fields = it.fields.toMutableList().also { l -> l[slot] = f }) } }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(fieldLabel(f)), style = Type.strong, color = if (on) c.accent else c.ink, modifier = Modifier.weight(1f))
                        if (on) AppIcon(Icon.CHECK, c.accent, 18.dp)
                    }
                }
            }
        }
    }
}
