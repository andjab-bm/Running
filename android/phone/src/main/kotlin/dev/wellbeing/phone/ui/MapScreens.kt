package dev.wellbeing.phone.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.wellbeing.phone.R
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.record.Coaching
import dev.wellbeing.running.Format
import dev.wellbeing.running.RoutePath
import dev.wellbeing.running.RouteTracker
import dev.wellbeing.running.Sample
import dev.wellbeing.running.Snapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import kotlin.coroutines.resume
import kotlin.math.max

/** What the mini-map needs beyond the trace, provided by the live screen. */
data class MiniMapInput(
    val path: RoutePath?,
    /** The basemap style, or null for the plain background. */
    val style: String?,
    /** The screen is on and the main page is the one shown: only then may a snapshot be taken. */
    val active: Boolean,
    val onOpen: () -> Unit,
)

val LocalMiniMap = compositionLocalOf<MiniMapInput?> { null }

/** The plain background: the basemap's land colour and nothing else, so no request is ever made. */
private fun emptyStyle(land: Color) = """{"version":8,"sources":{},"layers":[{"id":"land","type":"background","paint":{"background-color":"${hex(land)}"}}]}"""

private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

private fun Sample.ll() = LatLon(lat, lon)

private fun route(path: RoutePath) = path.lat.indices.map { LatLon(path.lat[it], path.lon[it]) }

/** Takes one still image of the basemap for [frame], from the same style and cache as the map screen. */
private suspend fun snapshot(context: Context, style: String, frame: MercatorFrame, density: Float): Bitmap? =
    suspendCancellableCoroutine { cont ->
        MapLibre.getInstance(context)
        val c = frame.center
        val snapshotter = MapSnapshotter(
            context,
            MapSnapshotter.Options(frame.width.toInt().coerceIn(1, 1024), frame.height.toInt().coerceIn(1, 1024))
                .withStyleBuilder(Style.Builder().fromUri(style))
                .withCameraPosition(CameraPosition.Builder().target(LatLng(c.lat, c.lon)).zoom(frame.zoom).build())
                .withPixelRatio(density)
                .withLogo(false),
        )
        cont.invokeOnCancellation { snapshotter.cancel() }
        snapshotter.start({ s -> if (cont.isActive) cont.resume(s.bitmap) }, { _ -> if (cont.isActive) cont.resume(null) })
    }

/**
 * The mini-map of the combined field: the route under the trace, ending at the position, over a
 * still image of the basemap when it is on. The image is taken again only when the trace grows out
 * of it, and only while [MiniMapInput.active]; with the basemap off nothing is requested.
 */
@Composable
fun MiniMap(track: List<Sample>, modifier: Modifier, live: Boolean = true) {
    val c = LocalColors.current
    val input = if (live) LocalMiniMap.current else null
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var frame by remember { mutableStateOf<MercatorFrame?>(null) }
    var image by remember { mutableStateOf<Pair<MercatorFrame, ImageBitmap>?>(null) }
    val label = stringResource(R.string.minimap)
    val base = modifier.clip(Radius.tile).background(if (input?.style != null) c.mapLand else c.surface.copy(alpha = 0.92f))
    BoxWithConstraints(
        if (input != null) base.clickable(role = Role.Button, onClickLabel = stringResource(R.string.page_map)) { input.onOpen() } else base,
    ) {
        val w = maxWidth.value.toDouble()
        val h = maxHeight.value.toDouble()
        val points = track.map { it.ll() } + (input?.path?.let(::route) ?: emptyList())
        val f = nextFrame(frame, GeoBounds.of(points), w, h)
        SideEffect { if (f != frame) frame = f }
        val style = input?.style
        if (style != null && input.active && f != null && image?.first != f) {
            LaunchedEffect(f, style) {
                snapshot(context.applicationContext, style, f, density)?.let { image = f to it.asImageBitmap() }
            }
        }
        val img = image?.takeIf { it.first == f && style != null }
        if (img != null) Image(img.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            val fr = f ?: return@Canvas
            if (track.size < 2 && input?.path == null) return@Canvas
            fun at(p: LatLon): Offset {
                val (x, y) = fr.project(p.lat, p.lon)
                return Offset((x * size.width / fr.width).toFloat(), (y * size.height / fr.height).toFloat())
            }
            input?.path?.let { line(route(it), c.surface, 6.dp.toPx(), ::at); line(route(it), c.sky.ink, 3.5.dp.toPx(), ::at) }
            if (track.size >= 2) {
                val step = max(1, track.size / 400)
                line(track.filterIndexed { i, _ -> i % step == 0 || i == track.lastIndex }.map { it.ll() }, c.accent, 3.5.dp.toPx(), ::at)
            }
            if (live && track.isNotEmpty()) {
                drawCircle(c.surface, 7.dp.toPx(), at(track.last().ll()))
                drawCircle(c.accent, 5.dp.toPx(), at(track.last().ll()))
            }
        }
        if (track.size < 2 && live && input?.path == null) {
            Canvas(Modifier.fillMaxSize().padding(22.dp)) {
                drawCircle(c.surface3, size.minDimension / 2.6f, style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 14f)), cap = StrokeCap.Round))
            }
        }
        if (live) {
            Text(
                label,
                style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink2,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp).clip(Radius.pill).background(c.surface).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

private fun DrawScope.line(points: List<LatLon>, color: Color, width: Float, at: (LatLon) -> Offset) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { i, p -> val o = at(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------------------------------------------------------------- map page

private const val FOLLOW_ZOOM = 16.0

private fun lineFeature(points: List<LatLon>) = Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) }))

private fun linesFeature(lines: List<List<LatLon>>) =
    Feature.fromGeometry(MultiLineString.fromLngLats(lines.map { l -> l.map { Point.fromLngLat(it.lon, it.lat) } }))

private fun pointFeature(p: LatLon) = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))

private val empty = FeatureCollection.fromFeatures(emptyList<Feature>())

/** A chevron pointing east, the direction of a line for icons placed along it. */
private fun chevron(color: Int, density: Float): Bitmap {
    val s = (14 * density).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2.4f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val path = android.graphics.Path().apply { moveTo(s * 0.32f, s * 0.2f); lineTo(s * 0.68f, s * 0.5f); lineTo(s * 0.32f, s * 0.8f) }
    android.graphics.Canvas(b).drawPath(path, p)
    return b
}

private fun square(fill: Int, ring: Int, density: Float): Bitmap {
    val s = (14 * density).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(b)
    cv.drawRect(0f, 0f, s.toFloat(), s.toFloat(), Paint().apply { color = ring })
    val i = 2.5f * density
    cv.drawRect(i, i, s - i, s - i, Paint().apply { color = fill })
    return b
}

/** Sources and layers of the run: the same on the basemap and on the plain background. */
private fun addRunLayers(style: Style, c: AppColors, density: Float) {
    style.addImage("chev", chevron(c.surface.toArgb(), density))
    style.addImage("end", square(c.ink.toArgb(), c.surface.toArgb(), density))
    for (id in listOf("ahead", "done", "trace", "start", "end", "me")) style.addSource(GeoJsonSource(id))
    val round = arrayOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
    style.addLayer(LineLayer("ahead-casing", "ahead").withProperties(*round, PropertyFactory.lineColor(c.surface.toArgb()), PropertyFactory.lineWidth(9f)))
    style.addLayer(LineLayer("ahead", "ahead").withProperties(*round, PropertyFactory.lineColor(c.sky.ink.toArgb()), PropertyFactory.lineWidth(5.5f)))
    style.addLayer(
        SymbolLayer("ahead-chevrons", "ahead").withProperties(
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), PropertyFactory.symbolSpacing(70f),
            PropertyFactory.iconImage("chev"), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), PropertyFactory.iconSize(0.7f),
        ),
    )
    style.addLayer(LineLayer("done", "done").withProperties(*round, PropertyFactory.lineColor(c.sky.ink.toArgb()), PropertyFactory.lineOpacity(0.3f), PropertyFactory.lineWidth(5.5f)))
    style.addLayer(LineLayer("trace", "trace").withProperties(*round, PropertyFactory.lineColor(c.accent.toArgb()), PropertyFactory.lineWidth(4f)))
    style.addLayer(
        CircleLayer("start", "start").withProperties(
            PropertyFactory.circleColor(c.mint.ink.toArgb()), PropertyFactory.circleRadius(6f),
            PropertyFactory.circleStrokeColor(c.surface.toArgb()), PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(SymbolLayer("end", "end").withProperties(PropertyFactory.iconImage("end"), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
    style.addLayer(
        CircleLayer("me", "me").withProperties(
            PropertyFactory.circleColor(c.accent.toArgb()), PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(c.surface.toArgb()), PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

private fun updateRunLayers(style: Style, track: List<Sample>, path: RoutePath?, state: RouteTracker.State?) {
    fun set(id: String, f: Feature?) = style.getSourceAs<GeoJsonSource>(id)?.let { if (f == null) it.setGeoJson(empty) else it.setGeoJson(f) }
    if (path != null) {
        val split = splitRoute(path, state?.joinedAt?.takeIf { state.joined }, state?.along?.takeIf { state.joined })
        set("ahead", if (split.ahead.isEmpty()) null else linesFeature(split.ahead))
        set("done", split.done.takeIf { it.size >= 2 }?.let(::lineFeature))
        set("start", pointFeature(LatLon(path.lat[0], path.lon[0])))
        set("end", pointFeature(LatLon(path.lat.last(), path.lon.last())))
    }
    val step = max(1, track.size / 2000)
    set("trace", track.filterIndexed { i, _ -> i % step == 0 || i == track.lastIndex }.map { it.ll() }.takeIf { it.size >= 2 }?.let(::lineFeature))
    set("me", track.lastOrNull()?.let { pointFeature(it.ll()) })
}

/**
 * The map page: the route, the trace and the position over the basemap (or the plain background),
 * with the key figures and the map controls on top. The `MapView` exists only while this page is the
 * one shown and the screen is on: the caller composes it only then.
 */
@Composable
fun MapPage(s: Snapshot, settings: Settings, track: List<Sample>, coaching: Coaching?, locked: Boolean) {
    val c = LocalColors.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val style = basemapStyle(settings, c.isDark)
    val path = coaching?.path
    val route = coaching?.route
    val follow = remember { CameraFollow() }
    var northUp by remember { mutableStateOf(false) }
    var whole by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var listening by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(Triple(track, path, route))

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context)
    }
    DisposableEffect(lifecycle, mapView) {
        var created = false
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_CREATE -> { mapView.onCreate(null); created = true }
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                Lifecycle.Event.ON_DESTROY -> { mapView.onDestroy(); created = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            if (created) mapView.onDestroy()
            ready = null
        }
    }
    DisposableEffect(mapView, style, c.isDark) {
        mapView.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            if (!listening) {
                listening = true
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        follow.moved(System.currentTimeMillis())
                        whole = false
                    }
                }
            }
            val builder = if (style != null) Style.Builder().fromUri(style) else Style.Builder().fromJson(emptyStyle(c.mapLand))
            map.setStyle(builder) { st ->
                addRunLayers(st, c, density)
                val (t, p, r) = latest
                updateRunLayers(st, t, p, r)
                map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(t.lastOrNull()?.let { LatLng(it.lat, it.lon) } ?: p?.let { LatLng(it.lat[0], it.lon[0]) } ?: LatLng(0.0, 0.0)).zoom(FOLLOW_ZOOM).build()))
                ready = map to st
            }
        }
        onDispose { }
    }

    // Once a second with the run: new trace, new position, the camera back on the runner.
    LaunchedEffect(ready, track.size, route, northUp, whole) {
        val (map, st) = ready ?: return@LaunchedEffect
        updateRunLayers(st, track, path, route)
        val now = System.currentTimeMillis()
        if (follow.tick(now)) whole = false
        val me = track.lastOrNull() ?: return@LaunchedEffect
        if (whole && path != null) {
            val b = LatLngBounds.Builder().apply { path.lat.indices.forEach { include(LatLng(path.lat[it], path.lon[it])) }; include(LatLng(me.lat, me.lon)) }.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, (48 * density).toInt()), 500)
        } else if (follow.following) {
            val bearing = if (northUp) 0.0 else heading(track.takeLast(30).map { it.ll() }) ?: map.cameraPosition.bearing
            map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(me.lat, me.lon)).bearing(bearing).zoom(map.cameraPosition.zoom).build()), 600)
        }
    }
    LaunchedEffect(ready, locked) { ready?.first?.uiSettings?.setAllGesturesEnabled(!locked) }

    Box(Modifier.fillMaxSize().clip(Radius.card)) {
        AndroidView({ mapView }, Modifier.fillMaxSize().background(c.mapLand).semantics { contentDescription = "Carte de la course" })

        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapFigure("Temps total", Format.duration(s.elapsedMs / 1000.0), Modifier.weight(1f))
                MapFigure("Distance", "${Format.distance(s.distance, settings.units)} ${Format.distanceUnit(settings.units)}", Modifier.weight(1f))
                MapFigure("Allure", s.pace?.let { Format.pace(it, settings.units) } ?: "–", Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapButton(if (northUp) "Nord en haut" else "Sens de la course en haut", text = if (northUp) "N" else null, icon = if (northUp) null else Icon.UP) { northUp = !northUp }
                MapButton("Zoomer", icon = Icon.PLUS) { ready?.first?.animateCamera(CameraUpdateFactory.zoomIn(), 300) }
                MapButton("Dézoomer", icon = Icon.MINUS) { ready?.first?.animateCamera(CameraUpdateFactory.zoomOut(), 300) }
                if (path != null) MapButton("Voir tout le parcours", icon = Icon.ROUTE, pressed = whole) { whole = !whole; if (!whole) follow.recentre() }
                MapButton("Revenir sur ma position", icon = Icon.PIN, pressed = !follow.following) { whole = false; follow.recentre() }
            }
        }

        Column(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                route?.offRoute == true -> MapBanner(Icon.ALERT, "Hors parcours · le parcours est à ${WorkoutText.distance(route.lateral, settings.units)}", c.rose)
                route?.arrived == true -> MapBanner(
                    Icon.FLAG,
                    "Arrivée · parcours terminé" + (coaching.pacer?.gapS?.let { " avec ${Format.duration(kotlin.math.abs(it))} ${if (it >= 0) "d'avance" else "de retard"}" } ?: ""),
                    c.mint,
                )
                style == null -> MapBanner(
                    Icon.ROUTE,
                    if (settings.mapStyleUrl.isEmpty()) "Pas de fond de carte configuré sur le serveur." else "Fond de carte désactivé : aucune demande au fournisseur.",
                    Pastel(c.surface, c.ink2),
                )
            }
            if (path != null && route != null) {
                Row(Modifier.clip(Radius.pill).background(c.surface).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Icon.ROUTE, c.sky.ink, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    if (route.joined) {
                        Text("Reste ", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.ink2)
                        Text("${Format.distance(route.remaining, settings.units)} ${Format.distanceUnit(settings.units)}", style = Type.h3, color = c.ink)
                    } else {
                        Text("Départ à ${WorkoutText.distance(route.lateral, settings.units)}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun MapFigure(label: String, value: String, modifier: Modifier) {
    val c = LocalColors.current
    Column(modifier.clip(Radius.row).background(c.surface).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label.uppercase(), style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.ink2, maxLines = 1)
        Text(value, style = Type.h3, color = c.ink, maxLines = 1)
    }
}

@Composable
private fun MapButton(description: String, icon: Icon? = null, text: String? = null, pressed: Boolean = false, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (pressed) c.accent else c.surface)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) AppIcon(icon, if (pressed) c.onAccent else c.ink, 20.dp)
        if (text != null) Text(text, style = Type.strong, color = if (pressed) c.onAccent else c.ink)
    }
}

@Composable
private fun MapBanner(icon: Icon, text: String, pastel: Pastel) {
    Row(Modifier.clip(Radius.row).background(pastel.fill).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, pastel.ink, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = pastel.ink)
    }
}

/** The page shown in place of the map while it is being swiped in or the screen is off. */
@Composable
fun MapPlaceholder() {
    Box(Modifier.fillMaxSize().clip(Radius.card).background(LocalColors.current.mapLand))
}
