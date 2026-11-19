package dev.wellbeing.phone.ui

import android.content.ActivityNotFoundException
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
            Text("Carte", style = Type.h3, color = c.ink)
            val styled = settings.mapStyleUrl.isNotEmpty()
            Column(Modifier.alpha(if (styled) 1f else 0.45f)) {
                Toggle("Fond de carte pendant la course", "", settings.basemap && styled) { v -> if (styled) change { it.copy(basemap = v) } }
            }
            Text(
                if (styled) "Le fond vient du fournisseur de carte configuré sur le serveur de la maison : il voit la zone affichée, pas ta course. " +
                    "Désactivé, le parcours et la trace s'affichent sur un fond uni et aucune demande n'est faite."
                else "Le serveur n'a pas de fond de carte configuré : la carte s'affiche toujours sur un fond uni.",
                style = Type.small, color = c.ink3,
            )
        }
    }
}
