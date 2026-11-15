package dev.wellbeing.phone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Stroke icons on a 24-unit grid, the same drawings as the web app. */
enum class Icon(val path: String) {
    PLAY("M7 4l13 8-13 8V4z"),
    PAUSE("M8 4v16M16 4v16"),
    STOP("M7.5 5h9a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5z"),
    FLAG("M5 22V4M5 4h13l-2.5 4L18 12H5"),
    LOCK("M6 11h12a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2zM8 11V7a4 4 0 0 1 8 0v4"),
    PIN("M12 22s7-6.2 7-12a7 7 0 0 0-14 0c0 5.8 7 12 7 12zM14.5 10a2.5 2.5 0 1 1 -5 0a2.5 2.5 0 1 1 5 0"),
    WATCH("M18 12a6 6 0 1 1 -12 0a6 6 0 1 1 12 0M9 6.6L9.6 3h4.8l.6 3.6M9 17.4l.6 3.6h4.8l.6-3.6M12 9.8V12l1.4 1"),
    CLOCK("M22 12a10 10 0 1 1 -20 0a10 10 0 1 1 20 0M12 6v6l4 2"),
    ROUTE("M9 19a3 3 0 1 1 -6 0a3 3 0 1 1 6 0M21 5a3 3 0 1 1 -6 0a3 3 0 1 1 6 0M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"),
    GAUGE("M12 14l4-4M3.34 19a10 10 0 1 1 17.32 0"),
    HEART("M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21.2l7.8-7.8 1-1a5.5 5.5 0 0 0 0-7.8z"),
    STEPS("M22 12h-4l-3 9L9 3l-3 9H2"),
    MOUNTAIN("M8 3l4 8 5-5 5 15H2L8 3z"),
    CHECK("M20 6L9 17l-5-5"),
    TRASH("M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"),
    EDIT("M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"),
    BACK("M19 12H5M12 19l-7-7 7-7"),
    LIST("M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"),
    SLIDERS("M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"),
    REFRESH("M23 4v6h-6M1 20v-6h6M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15"),
    SHARE("M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13"),
    ALERT("M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"),
    CLOUD("M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"),
    CHEVRON("M9 6l6 6-6 6"),
}

@Composable
fun AppIcon(icon: Icon, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val path = remember(icon) { PathParser().parsePathString(icon.path).toPath() }
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            drawPath(path, tint, style = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun Eyebrow(text: String, color: Color = LocalColors.current.ink3, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = Type.eyebrow, color = color, modifier = modifier)

/** A borderless tonal surface. */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color = LocalColors.current.surface, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(Radius.card).background(color).padding(horizontal = 18.dp, vertical = 16.dp)) { content() }
}

enum class ButtonKind { PRIMARY, TONAL, DANGER }

@Composable
fun AppButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.TONAL, icon: Icon? = null) {
    val c = LocalColors.current
    val (bg, fg) = when (kind) {
        ButtonKind.PRIMARY -> c.accent to c.onAccent
        ButtonKind.TONAL -> c.surface2 to c.ink
        ButtonKind.DANGER -> c.rose.fill to c.rose.ink
    }
    Row(
        modifier.clip(Radius.pill).background(bg).clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { AppIcon(icon, fg, 20.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.strong, color = fg)
    }
}

@Composable
fun IconButton(icon: Icon, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Box(
        modifier.size(44.dp).clip(CircleShape).background(c.surface).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { AppIcon(icon, c.ink2, 20.dp) }
}

@Composable
fun Tag(text: String, pastel: Pastel, icon: Icon? = null) {
    Row(
        Modifier.clip(Radius.pill).background(pastel.fill).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { AppIcon(icon, pastel.ink, 13.dp); Spacer(Modifier.width(5.dp)) }
        Text(text, style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = pastel.ink)
    }
}

/** A small titled figure on a pastel fill, used in summaries. */
@Composable
fun MiniTile(label: String, value: String, unit: String, pastel: Pastel, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Column(modifier.clip(Radius.tile).background(pastel.fill).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label.uppercase(), style = Type.eyebrow, color = pastel.ink)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Type.figure(26), color = c.ink, maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, style = Type.small, color = c.ink2, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
        }
    }
}

@Composable
fun Segmented(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Row(modifier.clip(Radius.pill).background(c.surface2).padding(4.dp)) {
        options.forEachIndexed { i, (label, selected) ->
            Text(
                label,
                style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = if (selected) c.onAccent else c.ink2,
                modifier = Modifier.clip(Radius.pill).background(if (selected) c.accent else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clip(Radius.row).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = c.ink)
            Text(subtitle, style = Type.small, color = c.ink3)
        }
        Box(Modifier.size(width = 44.dp, height = 26.dp).clip(Radius.pill).background(if (checked) c.accent else c.surface3).padding(3.dp)) {
            Box(Modifier.size(20.dp).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(c.surface))
        }
    }
}

/** How long a control must be held before it acts (stop, unlock). */
const val HOLD_MS = 1100

/**
 * Wraps a control that only acts when held: a brief touch does nothing. [content] receives the
 * progress of the hold, from 0 to 1, to draw it.
 */
@Composable
fun HoldToConfirm(onConfirmed: () -> Unit, description: String, modifier: Modifier = Modifier, content: @Composable (progress: Float) -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.semantics { contentDescription = description; role = Role.Button }.pointerInput(onConfirmed) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                    onConfirmed()
                }
                tryAwaitRelease()
                job.cancel()
                scope.launch { progress.snapTo(0f) }
            })
        },
        contentAlignment = Alignment.Center,
    ) { content(progress.value) }
}

/** Draws the progress of a hold as a ring around a round control. */
fun Modifier.holdRing(progress: Float, color: Color): Modifier = drawBehind {
    if (progress <= 0f) return@drawBehind
    val w = 4.dp.toPx()
    drawArc(color, -90f, 360f * progress, false, Offset(-w, -w), Size(size.width + 2 * w, size.height + 2 * w), style = Stroke(w, cap = StrokeCap.Round))
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))
