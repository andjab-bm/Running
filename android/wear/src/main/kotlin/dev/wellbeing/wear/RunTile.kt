package dev.wellbeing.wear

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.wellbeing.running.Tokens
import dev.wellbeing.running.WatchTile

/**
 * The tile: during a run the watch has joined, its state, moving time, distance and heart rate with
 * their age; otherwise a start control. Touching it opens the watch app, which then joins or starts
 * the run as when opened by hand. It reads what the app already holds and is refreshed by the
 * heart-rate service at most every 30 s; without the tile added, nothing here runs.
 */
class RunTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val content = WatchTile.of(
            WatchSession.state.value, WatchSession.stateAt.value, WatchSession.heartRate.value,
            WatchSession.measuring.value, System.currentTimeMillis(),
        )
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            // During a run the age line is kept roughly right; otherwise only the service's requests refresh it.
            .setFreshnessIntervalMillis(if (content is WatchTile.Run) REFRESH_MS else 0)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(this, content)))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile); "tile" }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { it.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build()); "resources" }

    companion object {
        private const val RESOURCES = "1"
        const val REFRESH_MS = 30_000L

        private fun color(v: Int) = argb((0xFF000000 or v.toLong()).toInt())

        private fun text(value: String, size: Float, color: Int, bold: Boolean = true) = LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(2)
            .setFontStyle(
                FontStyle.Builder().setSize(sp(size)).setColor(color(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build(),
            )
            .build()

        private fun space(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

        private fun open(context: Context, id: String) = ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder().setPackageName(context.packageName).setClassName(MainActivity::class.java.name).build(),
                ).build(),
            )
            .build()

        private fun pill(context: Context, label: String, id: String) = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(open(context, id))
                    .setBackground(ModifiersBuilders.Background.Builder().setColor(color(Tokens.Watch.PRIMARY)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(24f)).build()).build())
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(18f)).setEnd(dp(18f)).setTop(dp(8f)).setBottom(dp(8f)).build())
                    .build(),
            )
            .addContent(text(label, 15f, Tokens.Watch.ON_PRIMARY))
            .build()

        fun layout(context: Context, content: WatchTile): LayoutElementBuilders.LayoutElement {
            val column = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            when (content) {
                is WatchTile.Run -> {
                    column.addContent(text("● ${content.status}", 13f, Tokens.Watch.TIME))
                    column.addContent(space(2f))
                    column.addContent(text(content.time, 34f, Tokens.Watch.TIME))
                    column.addContent(text("${content.distance} ${content.unit} · ${content.heartRate} bpm", 17f, Tokens.Watch.DISTANCE))
                    column.addContent(text(WatchTile.age(content.ageS), 12f, Tokens.Watch.MUTED, bold = false))
                    column.addContent(space(8f))
                    column.addContent(pill(context, "Ouvrir", "open"))
                }
                WatchTile.Idle -> {
                    column.addContent(text("Aucune course sur la montre", 14f, Tokens.Watch.MUTED, bold = false))
                    column.addContent(space(10f))
                    column.addContent(pill(context, "▶ Démarrer", "start"))
                    column.addContent(space(6f))
                    column.addContent(text("ou rejoindre celle du téléphone", 12f, Tokens.Watch.MUTED, bold = false))
                }
            }
            return LayoutElementBuilders.Box.Builder()
                .setWidth(expand()).setHeight(expand())
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open(context, "tile")).build())
                .addContent(column.build())
                .build()
        }
    }
}
