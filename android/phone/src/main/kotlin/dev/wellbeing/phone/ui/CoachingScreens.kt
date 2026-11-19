package dev.wellbeing.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wellbeing.phone.data.AppDb
import dev.wellbeing.phone.data.PlanRouteEntity
import dev.wellbeing.phone.data.PlanWorkoutEntity
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.path
import dev.wellbeing.phone.data.preview
import dev.wellbeing.phone.data.workout
import dev.wellbeing.phone.record.Coaching
import dev.wellbeing.phone.record.Finished
import dev.wellbeing.phone.record.Plan
import dev.wellbeing.phone.record.RunController
import dev.wellbeing.phone.sync.Sync
import dev.wellbeing.phone.sync.SyncResult
import dev.wellbeing.running.Format
import dev.wellbeing.running.PacePlan
import dev.wellbeing.running.PacerTarget
import dev.wellbeing.running.PlannedStep
import dev.wellbeing.running.RouteTracker
import dev.wellbeing.running.Snapshot
import dev.wellbeing.running.StepEnd
import dev.wellbeing.running.StepKind
import dev.wellbeing.running.StepTarget
import dev.wellbeing.running.TargetState
import dev.wellbeing.running.Units
import dev.wellbeing.running.Workout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Shared pieces

/** Colour of a step kind, as on the web's outline. */
@Composable
fun kindColor(k: StepKind): Pastel {
    val c = LocalColors.current
    return when (k) {
        StepKind.WARMUP -> c.mint
        StepKind.EFFORT -> c.lav
        StepKind.RECOVERY -> c.sky
        StepKind.COOLDOWN -> c.peach
    }
}

/** The steps side by side, each as wide as it lasts and as tall as it is hard. */
@Composable
fun Outline(steps: List<PlannedStep>, modifier: Modifier = Modifier, height: Int = 26) {
    val c = LocalColors.current
    val total = steps.sumOf { WorkoutText.weight(it) }.takeIf { it > 0 } ?: 1.0
    val colors = steps.map { kindColor(it.step.kind).ink }
    Canvas(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(4.dp)) {
        var x = 0f
        val gap = 2.dp.toPx()
        steps.forEachIndexed { i, s ->
            val w = (WorkoutText.weight(s) / total * (size.width - gap * steps.size)).toFloat().coerceAtLeast(2f)
            val h = size.height * when (s.step.kind) {
                StepKind.EFFORT -> 1f
                StepKind.WARMUP -> 0.45f
                StepKind.COOLDOWN -> 0.4f
                StepKind.RECOVERY -> 0.3f
            }
            drawRoundRect(colors[i], Offset(x, size.height - h), Size(w, h), CornerRadius(3.dp.toPx()))
            x += w + gap
        }
    }
}

/** A route's shape on a pastel square, for list rows. */
@Composable
fun RouteThumb(r: PlanRouteEntity, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val pts = remember(r.id, r.version) { r.preview() }
    Canvas(modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(c.sky.fill).padding(8.dp)) {
        if (pts.size < 2) return@Canvas
        val lat0 = pts.minOf { it.first }; val lat1 = pts.maxOf { it.first }
        val lon0 = pts.minOf { it.second }; val lon1 = pts.maxOf { it.second }
        val kx = cos(Math.toRadians((lat0 + lat1) / 2))
        val w = max((lon1 - lon0) * kx, 1e-6); val h = max(lat1 - lat0, 1e-6)
        val k = minOf(size.width / w, size.height / h).toFloat()
        val ox = (size.width - (w * k).toFloat()) / 2; val oy = (size.height - (h * k).toFloat()) / 2
        val path = Path()
        pts.forEachIndexed { i, (la, lo) ->
            val x = ox + ((lo - lon0) * kx * k).toFloat(); val y = size.height - oy - ((la - lat0) * k).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c.sky.ink, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun routeFigures(r: PlanRouteEntity, units: Units) =
    "${Format.distance(r.distance, units).dropLast(1)} ${Format.distanceUnit(units)}" +
        (r.gain?.let { " · ${Format.elevation(it, units)} ${Format.elevationUnit(units)} D+" } ?: " · sans altitude") +
        if (r.isLoop) " · boucle" else ""

/** `450 m`, `1,2 km`: how far the runner is from a point. */
private fun away(meters: Double, units: Units) =
    if (units == Units.METRIC && meters < 1000) "${(meters / 10).roundToInt() * 10} m" else "${Format.distance(meters, units).dropLast(1)} ${Format.distanceUnit(units)}"

// ---------------------------------------------------------------------------------------------
// The Plan tab

/**
 * What the home server delivered: sessions and routes, read-only, usable without network. They are
 * prepared on the web and come with the sync at home.
 */
@Composable
fun PlanScreen(settings: Settings, onUse: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(context).plan() }
    val profile = settings.profileId
    val workouts by remember(profile) { if (profile == null) flowOf(emptyList()) else dao.watchWorkouts(profile) }.collectAsState(initial = emptyList())
    val routes by remember(profile) { if (profile == null) flowOf(emptyList()) else dao.watchRoutes(profile) }.collectAsState(initial = emptyList())
    val syncing by Sync.running.collectAsState()
    val last by Sync.last.collectAsState()
    var open by remember { mutableStateOf<Any?>(null) }
    val u = settings.units

    fun syncNow() { scope.launch { Sync.run(context) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", style = Type.h1, color = c.ink, modifier = Modifier.weight(1f))
                if (workouts.isNotEmpty() || routes.isNotEmpty()) IconButton(Icon.REFRESH, "Synchroniser maintenant", { syncNow() })
            }
            last?.let { r -> SyncOutcome(r.result, r.plan?.let { it.workouts to it.routes }, syncing) }
            if (workouts.isEmpty() && routes.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(Radius.card).background(c.surface).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(c.lav.fill), contentAlignment = Alignment.Center) { AppIcon(Icon.CALENDAR, c.lav.ink, 30.dp) }
                    Text("Rien sur le téléphone", style = Type.h2, color = c.ink)
                    Text(
                        "Les séances et les parcours se préparent dans l'application web, rubrique Planifier : marque-les « sur le téléphone » et ils arrivent ici quand le téléphone se synchronise à la maison.",
                        style = Type.body, color = c.ink2, textAlign = TextAlign.Center,
                    )
                    AppButton(if (syncing) "Synchronisation…" else "Synchroniser maintenant", { syncNow() }, icon = Icon.REFRESH, modifier = Modifier.fillMaxWidth())
                    AppButton("Courir sans plan", onUse, kind = ButtonKind.PRIMARY, icon = Icon.PLAY, modifier = Modifier.fillMaxWidth())
                }
                return@Column
            }
            Text("Reçu de la maison, utilisable sans réseau.", style = Type.small, color = c.ink2)
            Section("Séances", workouts.size)
            for (w in workouts) {
                val parsed = remember(w.id, w.version) { w.workout() }
                PlanRow(onClick = { open = w }, lead = {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(c.lav.fill), contentAlignment = Alignment.Center) { AppIcon(Icon.REPEAT, c.lav.ink, 24.dp) }
                }) {
                    Text(w.name, style = Type.strong, color = c.ink)
                    if (parsed != null) {
                        Text("${WorkoutText.total(parsed, u)} · ${parsed.unfold().size} étapes", style = Type.small, color = c.ink2)
                        Outline(parsed.unfold(), Modifier.padding(top = 6.dp), height = 22)
                    } else Text("Séance illisible : synchronise à nouveau.", style = Type.small, color = c.rose.ink)
                }
            }
            Section("Parcours", routes.size)
            for (r in routes) {
                PlanRow(onClick = { open = r }, lead = { RouteThumb(r) }) {
                    Text(r.name, style = Type.strong, color = c.ink)
                    Text(routeFigures(r, u), style = Type.small, color = c.ink2)
                }
            }
            Text("On ne les modifie pas ici : les changements faits sur le web arrivent à la prochaine synchronisation.", style = Type.small, color = c.ink2)
        }
        when (val item = open) {
            is PlanWorkoutEntity -> BottomSheet({ open = null }) {
                val w = item.workout()
                Text(item.name, style = Type.h2, color = c.ink)
                if (w != null) {
                    Text(WorkoutText.total(w, u), style = Type.body, color = c.ink2)
                    Outline(w.unfold(), height = 34)
                    StepList(w, settings)
                    AppButton("Préparer une course avec", { RunController.choose(RunController.prepared.value.copy(workout = w, pacer = null)); open = null; onUse() }, kind = ButtonKind.PRIMARY, icon = Icon.PLAY, modifier = Modifier.fillMaxWidth())
                }
                AppButton("Fermer", { open = null }, modifier = Modifier.fillMaxWidth())
            }
            is PlanRouteEntity -> BottomSheet({ open = null }) {
                Text(item.name, style = Type.h2, color = c.ink)
                Text(routeFigures(item, u), style = Type.body, color = c.ink2)
                item.path()?.let { p ->
                    AppButton("Préparer une course avec", { RunController.choose(RunController.prepared.value.copy(route = p)); open = null; onUse() }, kind = ButtonKind.PRIMARY, icon = Icon.PLAY, modifier = Modifier.fillMaxWidth())
                }
                AppButton("Fermer", { open = null }, modifier = Modifier.fillMaxWidth())
            }
            else -> Unit
        }
    }
}

/** What the last manual or background sync brought, in the Plan tab. */
@Composable
private fun SyncOutcome(result: SyncResult, plan: Pair<Int, Int>?, syncing: Boolean) {
    val c = LocalColors.current
    val (text, pastel) = when {
        syncing -> "Synchronisation en cours…" to c.butter
        result == SyncResult.DONE && plan != null -> "À jour : ${plural(plan.first, "séance")} et ${plural(plan.second, "parcours")} sur le téléphone." to c.mint
        result == SyncResult.METERED -> "Pas de Wi-Fi de la maison : rien n'a été échangé." to c.butter
        result == SyncResult.NOT_CONFIGURED -> "Aucun serveur configuré : voir Réglages." to c.butter
        result == SyncResult.UNREACHABLE -> "Le serveur de la maison ne répond pas. Ce qui est déjà ici reste utilisable." to c.butter
        else -> "La synchronisation n'a pas abouti." to c.rose
    }
    Text(text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = pastel.ink, modifier = Modifier.fillMaxWidth().clip(Radius.row).background(pastel.fill).padding(horizontal = 14.dp, vertical = 10.dp))
}

private fun plural(n: Int, word: String) = "$n $word" + if (n > 1 && !word.endsWith("s")) "s" else ""

@Composable
private fun Section(title: String, count: Int) {
    val c = LocalColors.current
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.h3, color = c.ink, modifier = Modifier.weight(1f))
        Text(count.toString(), style = Type.small, color = c.ink2)
    }
}

@Composable
private fun PlanRow(onClick: () -> Unit, lead: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clip(Radius.tile).background(c.surface).clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), content = content)
        AppIcon(Icon.CHEVRON, c.ink3, 18.dp)
    }
}

@Composable
private fun StepList(w: Workout, settings: Settings) {
    val c = LocalColors.current
    val bounds = settings.zones.bounds.map { it.roundToInt() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in w.items) when (item) {
            is dev.wellbeing.running.WorkoutItem.Single -> StepLine(item.step, settings.units, bounds)
            is dev.wellbeing.running.WorkoutItem.Repeat -> Column(Modifier.fillMaxWidth().clip(Radius.row).background(c.lav.fill).padding(8.dp)) {
                Text("${item.count} ×" + if (item.skipLastRecovery) " · sans la dernière récupération" else "", style = Type.small.copy(fontWeight = FontWeight.Bold), color = c.lav.ink)
                item.steps.forEach { StepLine(it, settings.units, bounds) }
            }
        }
    }
}

@Composable
private fun StepLine(s: dev.wellbeing.running.WorkoutStep, units: Units, bounds: List<Int>) {
    val c = LocalColors.current
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 6.dp, height = 18.dp).clip(RoundedCornerShape(3.dp)).background(kindColor(s.kind).ink))
        Text("${WorkoutText.kind(s.kind)} · ${WorkoutText.end(s.end, units)}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text(WorkoutText.target(s.target, units, bounds), style = Type.small, color = c.ink2)
    }
}

// ---------------------------------------------------------------------------------------------
// The ready screen: what the run is done with

/**
 * Session, route and pace target attached to the run about to start. A session excludes the pacer:
 * its steps' targets apply instead.
 */
@Composable
fun Attachments(settings: Settings) {
    val c = LocalColors.current
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).plan() }
    val profile = settings.profileId
    val workouts by remember(profile) { if (profile == null) flowOf(emptyList()) else dao.watchWorkouts(profile) }.collectAsState(initial = emptyList())
    val routes by remember(profile) { if (profile == null) flowOf(emptyList()) else dao.watchRoutes(profile) }.collectAsState(initial = emptyList())
    val plan by RunController.prepared.collectAsState()
    val here by RunController.position.collectAsState()
    var picking by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    val u = settings.units
    val w = plan.workout
    val r = plan.route

    Column(Modifier.fillMaxWidth().clip(Radius.card).background(c.surface).padding(6.dp)) {
        AttachRow(Icon.REPEAT, c.mint, if (w != null) w.name else "Séance", if (w != null) "${WorkoutText.total(w, u)} · ${w.unfold().size} étapes" else "Aucune · course libre",
            action = if (w != null) "x" else "Choisir", onAction = { if (w != null) RunController.choose(plan.copy(workout = null)) else picking = "w" }) {
            if (w != null) Outline(w.unfold(), Modifier.padding(top = 6.dp), height = 20)
        }
        val distanceToStart = r?.let { route -> here?.let { RouteTracker(route).distanceToStart(it.lat, it.lon) } }
        AttachRow(Icon.ROUTE, c.sky, r?.name ?: "Parcours",
            if (r != null) "${Format.distance(r.length, u).dropLast(1)} ${Format.distanceUnit(u)}" + (distanceToStart?.let { d -> if (d < 30) " · tu es au départ" else " · départ à ${away(d, u)} d'ici" } ?: "")
            else "Aucun",
            action = if (r != null) "x" else "Choisir", onAction = { if (r != null) RunController.choose(plan.copy(route = null, pacer = (plan.pacer as? PacerTarget.Pace))) else picking = "r" })
        if (w != null) {
            AttachRow(Icon.TARGET, Pastel(c.surface2, c.ink3), "Allure cible", "Indisponible avec une séance : ce sont les cibles des étapes qui s'appliquent.", action = null, onAction = {})
        } else PacerRow(plan, settings) { preview = true }
    }

    picking?.let { kind ->
        BottomSheet({ picking = null }) {
            Text(if (kind == "w") "Séance" else "Parcours", style = Type.h2, color = c.ink)
            if (kind == "w") {
                if (workouts.isEmpty()) Text("Aucune séance sur le téléphone. Prépare-les sur le web, rubrique Planifier.", style = Type.body, color = c.ink2)
                for (e in workouts) {
                    val parsed = e.workout() ?: continue
                    PlanRow(onClick = { RunController.choose(plan.copy(workout = parsed, pacer = null)); picking = null }, lead = {}) {
                        Text(e.name, style = Type.strong, color = c.ink)
                        Text("${WorkoutText.total(parsed, u)} · ${parsed.unfold().size} étapes", style = Type.small, color = c.ink2)
                        Outline(parsed.unfold(), Modifier.padding(top = 6.dp), height = 20)
                    }
                }
            } else {
                if (routes.isEmpty()) Text("Aucun parcours sur le téléphone.", style = Type.body, color = c.ink2)
                for (e in routes) {
                    val p = e.path() ?: continue
                    PlanRow(onClick = {
                        val pacer = (plan.pacer as? PacerTarget.Time) ?: plan.pacer?.let { PacerTarget.Time(settings.pacerTime ?: defaultTime(p.length, settings), p.ele != null) }
                        RunController.choose(plan.copy(route = p, pacer = pacer))
                        picking = null
                    }, lead = { RouteThumb(e) }) {
                        Text(e.name, style = Type.strong, color = c.ink)
                        Text(routeFigures(e, u), style = Type.small, color = c.ink2)
                        here?.let { h -> Text("Départ à ${away(RouteTracker(p).distanceToStart(h.lat, h.lon), u)} d'ici", style = Type.small, color = c.ink2) }
                    }
                }
            }
            AppButton("Fermer", { picking = null }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (preview) {
        val route = r
        val target = plan.pacer as? PacerTarget.Time
        if (route != null && target != null) BottomSheet({ preview = false }) {
            val pp = remember(route, target) { PacePlan.of(target, route) }
            val unit = if (u == Units.IMPERIAL) Format.METERS_PER_MILE else 1000.0
            val even = target.seconds / route.length * 1000
            Text("Objectif ${Format.duration(target.seconds)}", style = Type.h2, color = c.ink)
            Text(route.name + " · " + if (target.gradeAdjusted && route.ele != null) "effort régulier : plus lent en montée, plus vite en descente" else "allure constante", style = Type.small, color = c.ink2)
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(vertical = 4.dp)) {
                    for (h in listOf(Format.distanceUnit(u), "Allure visée", "Temps cumulé")) Text(h.uppercase(), style = Type.eyebrow, color = c.ink3, modifier = Modifier.weight(1f))
                }
                pp.splits(unit).forEachIndexed { i, sp ->
                    val tint = when {
                        sp.pace > even + 4 -> c.peach.ink
                        sp.pace < even - 4 -> c.mint.ink
                        else -> c.ink
                    }
                    Row(Modifier.fillMaxWidth().clip(Radius.row).background(if (i % 2 == 0) c.surface2 else Color.Transparent).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(if (abs(sp.end - (i + 1) * unit) < 1) "${i + 1}" else Format.distance(sp.end, u), style = Type.figure(16), color = c.ink, modifier = Modifier.weight(1f))
                        Text("${Format.pace(sp.pace, u)} ${Format.paceUnit(u)}", style = Type.figure(16), color = tint, modifier = Modifier.weight(1f))
                        Text(Format.duration(sp.time), style = Type.figure(16), color = c.ink, modifier = Modifier.weight(1f))
                    }
                }
            }
            AppButton("OK", { preview = false }, kind = ButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A first proposal for a route's target time: six minutes per kilometre, to the minute. */
private fun defaultTime(length: Double, settings: Settings) = (length / 1000 * settings.pacerPace / 60).roundToInt() * 60.0

@Composable
private fun AttachRow(icon: Icon, pastel: Pastel, title: String, detail: String, action: String?, onAction: () -> Unit, extra: @Composable () -> Unit = {}) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(pastel.fill), contentAlignment = Alignment.Center) { AppIcon(icon, pastel.ink, 20.dp) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = Type.strong, color = c.ink)
            Text(detail, style = Type.small, color = c.ink2)
            extra()
        }
        when (action) {
            null -> Unit
            "x" -> IconButton(Icon.X, "Retirer $title", onAction)
            else -> Text(action, style = Type.strong, color = c.accent, modifier = Modifier.clip(Radius.pill).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun PacerRow(plan: Plan, settings: Settings, onPreview: () -> Unit) {
    val c = LocalColors.current
    val u = settings.units
    val route = plan.route
    val on = plan.pacer != null
    val perUnit = if (u == Units.IMPERIAL) Format.METERS_PER_MILE / 1000 else 1.0
    Column {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(c.lav.fill), contentAlignment = Alignment.Center) { AppIcon(Icon.TARGET, c.lav.ink, 20.dp) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Allure cible", style = Type.strong, color = c.ink)
                Text(
                    if (!on) "Désactivée · tu peux courir contre un chrono" else if (route != null) "Objectif de temps sur ${route.name}" else "Allure régulière, sur la distance courue",
                    style = Type.small, color = c.ink2,
                )
            }
            SwitchOnly(on, "Allure cible") { v ->
                RunController.choose(
                    plan.copy(
                        pacer = if (!v) null else if (route != null) PacerTarget.Time(settings.pacerTime ?: defaultTime(route.length, settings), route.ele != null)
                        else PacerTarget.Pace(settings.pacerPace),
                    ),
                )
            }
        }
        when (val t = plan.pacer) {
            is PacerTarget.Pace -> Stepper("Allure visée", "${Format.pace(t.secondsPerKm, u)} ${Format.paceUnit(u)}", "5 secondes plus vite", "5 secondes plus lent",
                { RunController.choose(plan.copy(pacer = PacerTarget.Pace((t.secondsPerKm - 5 / perUnit).coerceAtLeast(150.0)))) },
                { RunController.choose(plan.copy(pacer = PacerTarget.Pace((t.secondsPerKm + 5 / perUnit).coerceAtMost(900.0)))) })
            is PacerTarget.Time -> if (route != null) Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper("Temps visé", Format.duration(t.seconds), "Une minute de moins", "Une minute de plus",
                    { RunController.choose(plan.copy(pacer = t.copy(seconds = (t.seconds - 60).coerceAtLeast(60.0)))) },
                    { RunController.choose(plan.copy(pacer = t.copy(seconds = t.seconds + 60))) })
                Text("Soit ${Format.pace(t.seconds / (route.length / 1000), u)} ${Format.paceUnit(u)} en moyenne sur ${Format.distance(route.length, u).dropLast(1)} ${Format.distanceUnit(u)}.", style = Type.small, color = c.ink2)
                if (route.ele != null) {
                    Segmented(listOf("Effort régulier" to t.gradeAdjusted, "Allure constante" to !t.gradeAdjusted), { i -> RunController.choose(plan.copy(pacer = t.copy(gradeAdjusted = i == 0))) })
                    Text(if (t.gradeAdjusted) "Plus lent en montée, plus vite en descente, même temps à l'arrivée." else "La même allure du départ à l'arrivée.", style = Type.small, color = c.ink2)
                } else Text("Parcours sans altitude : allure constante seulement.", style = Type.small, color = c.ink2)
                Text("Voir le détail par ${if (u == Units.IMPERIAL) "mile" else "kilomètre"}", style = Type.strong, color = c.accent, modifier = Modifier.clip(Radius.pill).clickable(role = Role.Button, onClick = onPreview).padding(vertical = 6.dp))
            }
            null -> Unit
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, less: String, more: String, onLess: () -> Unit, onMore: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.padding(horizontal = 10.dp).clip(Radius.pill).background(c.surface2).padding(4.dp).semantics { contentDescription = "$label : $value" }, verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icon.MINUS, less, onLess)
        Text(value, style = Type.figure(22), color = c.ink, modifier = Modifier.padding(horizontal = 14.dp))
        IconButton(Icon.PLUS, more, onMore)
    }
}

// ---------------------------------------------------------------------------------------------
// Live: the step page and the pacer bar

/** The first live page of a guided run: the current step, what is left of it, its target, the next one. */
@Composable
fun ColumnScope.StepPage(s: Snapshot, coaching: Coaching, settings: Settings) {
    val c = LocalColors.current
    val u = settings.units
    val bounds = settings.zones.bounds.map { it.roundToInt() }
    val p = coaching.step
    if (coaching.complete || p == null) {
        Column(
            Modifier.weight(1.4f).fillMaxWidth().clip(Radius.card).background(c.mint.fill).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(Icon.CHECK, c.mint.ink, 40.dp)
            Text("Séance terminée", style = Type.h2, color = c.ink, modifier = Modifier.padding(top = 8.dp))
            Text("Les ${coaching.steps.size} étapes sont faites. Le temps et la distance continuent jusqu'à ce que tu arrêtes la course.", style = Type.body, color = c.ink2, textAlign = TextAlign.Center)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTile("Temps", Format.duration(s.movingMs / 1000.0), "", c.mint, Modifier.weight(1f).fillMaxHeight())
            MiniTile("Distance", Format.distance(s.distance, u), Format.distanceUnit(u), c.sky, Modifier.weight(1f).fillMaxHeight())
        }
        return
    }
    val step = p.planned.step
    val kind = kindColor(step.kind)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(WorkoutText.kind(step.kind), style = Type.small.copy(fontWeight = FontWeight.Bold), color = kind.ink, modifier = Modifier.clip(Radius.pill).background(kind.fill).padding(horizontal = 12.dp, vertical = 4.dp))
        if (p.planned.round != null) Text("Répétition ${p.planned.round} sur ${p.planned.rounds}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.ink2)
        Spacer(Modifier.weight(1f))
        Text("Étape ${p.index + 1} sur ${p.count}", style = Type.small.copy(fontWeight = FontWeight.Bold), color = c.ink2)
    }
    // What is left of the step, or the time spent in it when it ends on lap.
    val open = step.end == StepEnd.Lap
    val leftS = p.remainingS
    val leftM = p.remainingM
    val (value, unit) = when {
        leftS != null -> Format.duration(leftS) to ""
        leftM != null -> if (u == Units.METRIC && leftM < 1000) "${(leftM / 10).roundToInt() * 10}" to "m" else Format.distance(leftM, u) to Format.distanceUnit(u)
        else -> Format.duration(p.movingMs / 1000.0) to ""
    }
    val done = when (val e = step.end) {
        is StepEnd.Time -> (p.movingMs / 1000.0 / e.seconds).toFloat()
        is StepEnd.Distance -> (p.distance / e.meters).toFloat()
        StepEnd.Lap -> 0f
    }.coerceIn(0f, 1f)
    Column(Modifier.weight(1.3f).fillMaxWidth().clip(Radius.card).background(kind.fill).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
        Text((if (open) "Depuis le début de l'étape" else "Reste").uppercase(), style = Type.label, color = kind.ink)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Type.figure(84), color = c.ink, maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, style = Type.strong, color = c.ink2, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
        }
        if (open) Text("Appuie sur « Étape suivante » quand tu es prêt.", style = Type.small, color = c.ink2)
        else Box(Modifier.fillMaxWidth().height(8.dp).clip(Radius.pill).background(c.surface.copy(alpha = 0.6f))) {
            Box(Modifier.fillMaxWidth(done).fillMaxHeight().clip(Radius.pill).background(kind.ink))
        }
    }
    TargetTile(step.target, p.target, s, settings, bounds, Modifier.weight(1f).fillMaxWidth())
    val next = coaching.steps.getOrNull(p.index + 1)
    Row(Modifier.fillMaxWidth().clip(Radius.tile).background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val nk = next?.let { kindColor(it.step.kind) } ?: c.peach
        Box(Modifier.size(30.dp).clip(CircleShape).background(nk.fill), contentAlignment = Alignment.Center) { AppIcon(if (next != null) Icon.CHEVRON else Icon.FLAG, nk.ink, 16.dp) }
        Column(Modifier.padding(start = 12.dp)) {
            Text("ENSUITE", style = Type.eyebrow, color = c.ink3)
            Text(if (next != null) "${WorkoutText.kind(next.step.kind)} · ${WorkoutText.end(next.step.end, u)}" else "Fin de la séance", style = Type.strong, color = c.ink)
            if (next != null && next.step.target != StepTarget.None) Text(WorkoutText.target(next.step.target, u, bounds), style = Type.small, color = c.ink2)
        }
    }
}

@Composable
private fun TargetTile(target: StepTarget, state: TargetState, s: Snapshot, settings: Settings, bounds: List<Int>, modifier: Modifier) {
    val c = LocalColors.current
    val u = settings.units
    val hr = target is StepTarget.HrZone
    val pastel = if (hr) c.rose else c.lav
    Column(modifier.clip(Radius.card).background(pastel.fill).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(if (target == StepTarget.None) Icon.GAUGE else Icon.TARGET, pastel.ink, 16.dp)
            Text(
                (if (target == StepTarget.None) "Allure · sans cible" else "Cible · ${WorkoutText.target(target, u, bounds)}").uppercase(),
                style = Type.label, color = pastel.ink, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            WorkoutText.verdict(state)?.let { v ->
                val vp = if (state == TargetState.IN) c.mint else c.peach
                Text(v, style = Type.small.copy(fontWeight = FontWeight.Bold), color = vp.ink, modifier = Modifier.clip(Radius.pill).background(vp.fill).padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (hr) s.heartRate?.toString() ?: Format.PLACEHOLDER else Format.pace(s.pace, u), style = Type.figure(54), color = c.ink, maxLines = 1)
            Text(if (hr) "bpm" else Format.paceUnit(u), style = Type.strong, color = c.ink2, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        }
        when {
            target == StepTarget.None -> Text("Cours à ton rythme.", style = Type.small, color = c.ink2)
            state == TargetState.UNKNOWN && hr -> Text("Pas de fréquence cardiaque : cible affichée sans suivi.", style = Type.small, color = c.ink2)
            else -> Unit
        }
    }
}

/** Ahead of or behind the pace target, told apart by sign, word and arrow, not only by colour. */
@Composable
fun PacerBar(coaching: Coaching, settings: Settings) {
    val c = LocalColors.current
    val u = settings.units
    val st = coaching.pacer ?: return
    val gap = st.gapS
    val pastel = when {
        gap == null || abs(gap) < 1 -> Pastel(c.surface, c.ink2)
        gap > 0 -> c.mint
        else -> c.peach
    }
    Row(Modifier.fillMaxWidth().clip(Radius.tile).background(pastel.fill).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(when { gap == null || abs(gap) < 1 -> Icon.EQUAL; gap > 0 -> Icon.UP; else -> Icon.DOWN }, pastel.ink, 22.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(gap?.let { (if (it >= 0) "+" else "−") + Format.duration(abs(it)) } ?: Format.PLACEHOLDER, style = Type.figure(30), color = c.ink)
            Text(
                when {
                    gap == null -> "en attente de distance"
                    abs(gap) < 1 -> "dans les temps"
                    gap > 0 -> "d'avance" + if (st.finished) " à l'arrivée" else ""
                    else -> "de retard" + if (st.finished) " à l'arrivée" else ""
                },
                style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = pastel.ink,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("ALLURE CIBLE", style = Type.eyebrow, color = c.ink3)
            Text("${Format.pace(st.targetPace, u)} ${Format.paceUnit(u)}", style = Type.figure(20), color = c.ink)
            if (st.approximate) Text("approximative, hors parcours", style = Type.small, color = c.ink2)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The summary: steps, pacer and route

/** The session as run: each step with its time and pace against its target, and those not run. */
@Composable
fun SummarySteps(f: Finished, settings: Settings) {
    val c = LocalColors.current
    val steps = f.steps ?: return
    val u = settings.units
    val bounds = settings.zones.bounds.map { it.roundToInt() }
    val laps = f.run.laps
    val done = minOf(laps.size, steps.size)
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.workoutName ?: "Séance", style = Type.h3, color = c.ink, modifier = Modifier.weight(1f))
                Text("${minOf(done, steps.size)} étape${if (done > 1) "s" else ""} sur ${steps.size}", style = Type.small, color = c.ink2)
            }
            for (i in 0 until done) {
                val l = laps[i]
                val st = steps[i].step
                val pace = if (l.distance > 1) l.movingMs / 1000.0 / (l.distance / 1000) else null
                val met = when (val t = st.target) {
                    is StepTarget.Pace -> pace?.let { it in t.fast..t.slow }
                    is StepTarget.HrZone -> f.summary.lapHeartRates.getOrNull(i)?.let { hr -> bounds.getOrNull(t.zone - 1)?.let { lo -> bounds.getOrNull(t.zone)?.let { hi -> hr in lo until hi } } }
                    StepTarget.None -> null
                }
                // The step the stop interrupted is the last lap, closed by the stop.
                val cut = i == laps.lastIndex && !l.manual && i < steps.lastIndex
                StepRow(
                    WorkoutText.label(steps[i]) + if (cut) " · interrompue" else "",
                    if (st.target != StepTarget.None) WorkoutText.target(st.target, u, bounds) else WorkoutText.end(st.end, u),
                    Format.duration(l.movingMs / 1000.0),
                    if (st.kind == StepKind.EFFORT && l.distance > 50) "${Format.pace(pace, u)} ${Format.paceUnit(u)}" else WorkoutText.distance(l.distance, u),
                    met, faded = false,
                )
            }
            for (i in done until steps.size) StepRow(WorkoutText.label(steps[i]), "non courue", "", "", null, faded = true)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Dot(c.mint.ink); Text("cible tenue", style = Type.small, color = c.ink2)
                Dot(c.peach.ink); Text("cible manquée", style = Type.small, color = c.ink2)
            }
        }
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(color))

@Composable
private fun StepRow(name: String, detail: String, time: String, figure: String, met: Boolean?, faded: Boolean) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(when (met) { true -> c.mint.ink; false -> c.peach.ink; null -> c.surface3 }))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(name, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (faded) c.ink3 else c.ink)
            Text(detail, style = Type.small, color = if (faded) c.ink3 else c.ink2)
        }
        Text(time, style = Type.figure(16), color = c.ink, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
        Text(figure, style = Type.figure(16), color = c.ink, modifier = Modifier.width(88.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun SummaryPacer(f: Finished, settings: Settings) {
    val c = LocalColors.current
    val u = settings.units
    val target = f.pacerTarget ?: return
    val gap = f.pacer?.gapS
    val pastel = if (gap == null) Pastel(c.surface, c.ink2) else if (gap >= 0) c.mint else c.peach
    Column(Modifier.fillMaxWidth().clip(Radius.card).background(pastel.fill).padding(18.dp)) {
        Eyebrow("ALLURE CIBLE")
        Text(
            when (target) {
                is PacerTarget.Pace -> "Objectif ${Format.pace(target.secondsPerKm, u)} ${Format.paceUnit(u)}"
                is PacerTarget.Time -> "Objectif ${Format.duration(target.seconds)}" + (f.routeName?.let { " sur $it" } ?: "")
            },
            style = Type.strong, color = c.ink, modifier = Modifier.padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(gap?.let { Format.duration(abs(it)) } ?: Format.PLACEHOLDER, style = Type.figure(30), color = c.ink)
            Text(
                (if (gap == null) "" else if (gap >= 0) " d'avance" else " de retard") + if (f.pacer?.finished == true) " à l'arrivée" else " à l'arrêt",
                style = Type.strong, color = c.ink2, modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
