package dev.wellbeing.phone.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.wellbeing.phone.record.WatchStart
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wellbeing.phone.data.PACER_MARGINS
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.record.CuePlayer
import dev.wellbeing.phone.record.Cues
import dev.wellbeing.phone.record.VoiceStatus
import dev.wellbeing.running.Announcer
import dev.wellbeing.running.Cue
import dev.wellbeing.running.PlannedStep
import dev.wellbeing.running.StepEnd
import dev.wellbeing.running.StepKind
import dev.wellbeing.running.StepTarget
import dev.wellbeing.running.TargetState
import dev.wellbeing.running.Units
import dev.wellbeing.running.WorkoutStep

/** One sample per kind of cue that matters most, as on the prototype's "Essayer" chips. */
internal fun cueSamples(now: Long): List<Pair<String, Cue>> {
    val effort = PlannedStep(WorkoutStep(StepKind.EFFORT, StepEnd.Distance(400.0), StepTarget.Pace(300.0, 315.0)), 3, 6)
    return listOf(
        "Changement d'étape" to Cue.StepStarted(now, effort, 5, 14),
        "Alerte de cible" to Cue.TargetOut(now, TargetState.TOO_FAST, StepTarget.Pace(300.0, 315.0)),
        "Annonce" to Cue.Split(now, 3_000.0, (17 * 60 + 40) * 1000L, 352.0),
        "Hors parcours" to Cue.OffRoute(now, 60.0),
    )
}

/** The Coaching part of Réglages: channels, samples, the regular announcement and the alerts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachingSettings(settings: Settings, change: ((Settings) -> Settings) -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    // The speech engine runs while this screen is shown, for the samples and the voice check.
    val player: CuePlayer = remember { Cues.acquire(context) }
    DisposableEffect(Unit) { onDispose { Cues.release() } }
    val voice by Cues.voice.collectAsState()

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Signaux pendant la course", style = Type.h3, color = c.ink)
            Toggle("Voix", "Annonces parlées en français, par le moteur du téléphone", settings.cueVoice) { v -> change { it.copy(cueVoice = v) } }
            if (settings.cueVoice && voice == VoiceStatus.MISSING) {
                Column(
                    Modifier.fillMaxWidth().clip(Radius.row).background(c.butter.fill).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppIcon(Icon.ALERT, c.butter.ink, 18.dp)
                        Text(
                            "Aucune voix française hors ligne sur ce téléphone. Installe-la dans Paramètres › Système › Langues › Synthèse vocale › " +
                                "Moteur Google › Installer les données vocales › Français. En attendant, le son et la vibration fonctionnent.",
                            style = Type.small, color = c.butter.ink,
                        )
                    }
                    Text(
                        "Installer une voix",
                        style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.butter.ink,
                        modifier = Modifier.clip(Radius.pill).clickable(role = Role.Button) {
                            try {
                                context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: ActivityNotFoundException) {
                                // No engine offers it: the steps above remain.
                            }
                        }.padding(vertical = 4.dp),
                    )
                }
            }
            Toggle("Sons", "Un motif sonore par type de signal", settings.cueSound) { v -> change { it.copy(cueSound = v) } }
            Toggle("Vibrations", "Un motif par type, utile écran éteint", settings.cueVibration) { v -> change { it.copy(cueVibration = v) } }
            Text("La musique baisse le temps d'une annonce puis reprend. Pendant un appel, seule la vibration est donnée.", style = Type.small, color = c.ink3)
        }
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Essayer", style = Type.h3, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, _) in cueSamples(0)) {
                    Row(
                        Modifier.clip(Radius.pill).background(c.surface2)
                            .clickable(role = Role.Button) { cueSamples(System.currentTimeMillis()).first { it.first == label }.second.let(player::test) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppIcon(Icon.PLAY, c.ink2, 13.dp)
                        Text(label, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                    }
                }
            }
            Text("Avec les canaux choisis ci-dessus, sans démarrer de course.", style = Type.small, color = c.ink3)
        }
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Annonce régulière", style = Type.h3, color = c.ink)
            Toggle("Annoncer la distance", "Distance, temps et allure de la dernière portion", settings.announce) { v -> change { it.copy(announce = v) } }
            val unit = if (settings.units == Units.IMPERIAL) "mi" else "km"
            Column(Modifier.alpha(if (settings.announce) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Segmented(
                    Announcer.INTERVALS.map { v -> "${if (v == 0.5) "0,5" else v.toInt().toString()} $unit" to (settings.announceEvery == v) },
                    { i -> if (settings.announce) change { it.copy(announceEvery = Announcer.INTERVALS[i]) } },
                )
                Toggle("Ajouter l'allure moyenne", "", settings.announceAvgPace) { v -> if (settings.announce) change { it.copy(announceAvgPace = v) } }
                Toggle("Ajouter la fréquence cardiaque", "", settings.announceHeartRate) { v -> if (settings.announce) change { it.copy(announceHeartRate = v) } }
            }
        }
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Alertes", style = Type.h3, color = c.ink)
            Toggle("Hors de la cible d'une étape", "Après 15 s hors cible, au plus toutes les 30 s", settings.alertTarget) { v -> change { it.copy(alertTarget = v) } }
            Toggle("Avance ou retard sur l'objectif", "Au-delà de ${settings.pacerMargin} s, au plus une fois par minute", settings.alertPacer) { v -> change { it.copy(alertPacer = v) } }
            if (settings.alertPacer) {
                Segmented(PACER_MARGINS.map { m -> "$m s" to (settings.pacerMargin == m) }, { i -> change { it.copy(pacerMargin = PACER_MARGINS[i]) } })
            }
            Toggle("Hors parcours", "À plus de 40 m du parcours pendant 10 s", settings.alertOffRoute) { v -> change { it.copy(alertOffRoute = v) } }
            Text("Les réglages changés pendant une course s'appliquent à la suivante.", style = Type.small, color = c.ink3)
        }
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Montre", style = Type.h3, color = c.ink)
            Toggle("Vibrations sur la montre", "Étapes, alertes et hors parcours, même écran éteint", settings.watchVibrations) { v -> change { it.copy(watchVibrations = v) } }
            StartFromWatch(settings, change)
        }
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Carte", style = Type.h3, color = c.ink)
            val styled = settings.mapStyleUrl.isNotEmpty()
            Column(Modifier.alpha(if (styled) 1f else 0.45f)) {
                Toggle("Fond de carte pendant la course", "", settings.basemap && styled) { v -> if (styled) change { it.copy(basemap = v) } }
            }
            Text(
                if (styled) "Le fond est téléchargé sur internet (OpenFreeMap par défaut, ou le fournisseur du serveur de la maison) : il voit la zone affichée, pas ta course. " +
                    "Désactivé, le parcours et la trace s'affichent sur un fond uni et aucune demande n'est faite."
                else "Aucun fond de carte configuré : la carte s'affiche sur un fond uni.",
                style = Type.small, color = c.ink3,
            )
        }
    }
}

/**
 * The "start from the watch" option: off by default; turning it on explains and asks for the two
 * grants it needs, and it stays off when either is refused or later withdrawn.
 */
@Composable
private fun StartFromWatch(settings: Settings, change: ((Settings) -> Settings) -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    var grants by remember { mutableStateOf(WatchStart.grants(context)) }
    var asking by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    fun settle() {
        grants = WatchStart.grants(context)
        if (grants.all) {
            change { it.copy(startFromWatch = true) }
            asking = false
            refused = false
        } else if (asking) {
            refused = true
        }
    }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { settle() }
    val askBattery = {
        try {
            battery.launch(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        } catch (_: ActivityNotFoundException) {
            settle()
        }
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        grants = WatchStart.grants(context)
        if (ok && !grants.batteryExempt) askBattery() else settle()
    }
    // A grant withdrawn in the phone's settings turns the option off.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        grants = WatchStart.grants(context)
        if (settings.startFromWatch && !grants.all) change { it.copy(startFromWatch = false) }
    }
    val on = settings.startFromWatch && grants.all
    Toggle(
        "Démarrer depuis la montre",
        if (on) "Activé : la montre propose de démarrer quand rien n'est préparé" else "Le téléphone démarre une course libre à la demande de la montre, application fermée",
        on,
    ) { v ->
        if (!v) { change { it.copy(startFromWatch = false) }; asking = false } else if (grants.all) change { it.copy(startFromWatch = true) } else { asking = true; refused = false }
    }
    if (asking) {
        Column(
            Modifier.fillMaxWidth().clip(Radius.row).background(c.surface2).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Deux autorisations sont nécessaires", style = Type.strong, color = c.ink)
            Text(
                "1. La localisation « Toujours autoriser » : Android ne laisse démarrer le GPS d'une course qu'avec elle quand l'application est fermée. " +
                    "Elle ne sert qu'aux courses ; rien n'est suivi en dehors.",
                style = Type.small, color = c.ink2,
            )
            Text(
                "2. Ne pas optimiser la batterie pour l'application : c'est ce qui lui permet de démarrer à la demande de la montre. " +
                    "Hors course, elle ne fait rien de plus.",
                style = Type.small, color = c.ink2,
            )
            if (refused) Text("Une autorisation a été refusée : l'option reste désactivée.", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.rose.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton("Continuer", {
                    when {
                        !grants.fineLocation -> refused = true
                        !grants.backgroundLocation -> background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        !grants.batteryExempt -> askBattery()
                        else -> settle()
                    }
                }, kind = ButtonKind.PRIMARY)
                AppButton("Annuler", { asking = false; refused = false })
            }
        }
    }
}
