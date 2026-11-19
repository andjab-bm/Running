package dev.wellbeing.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.wellbeing.phone.R
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.SettingsStore
import dev.wellbeing.phone.sync.ApiException
import dev.wellbeing.phone.sync.ServerApi
import dev.wellbeing.phone.sync.ServerProfile
import dev.wellbeing.phone.sync.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Link {
    data object Idle : Link
    data object Checking : Link
    data class Connected(val version: String) : Link
    data class Failed(val kind: ApiException.Kind) : Link
}

/** Avatar colours of the web app, in the order profiles are created. */
private val avatarColors = listOf(0xFFB9AFFF, 0xFFF7AFC3, 0xFF8FE0BB, 0xFFFFC39C, 0xFF9CCBF7, 0xFFEFD877).map(::Color)

/** One line in the settings: the server and profile in use, or that none is set; opens [ServerSheet]. */
@Composable
fun ServerRow(settings: Settings, onOpen: () -> Unit) {
    val c = LocalColors.current
    val host = settings.serverUrl.removePrefix("http://").removePrefix("https://")
    val detail = when {
        settings.serverUrl.isBlank() -> "Non configuré"
        settings.profileId == null -> "$host · aucun profil choisi"
        else -> "$host · ${settings.profileName}"
    }
    Row(
        Modifier.fillMaxWidth().clip(Radius.tile).background(c.surface).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.server_title), style = Type.h3, color = c.ink)
            Text(detail, style = Type.small, color = if (settings.serverUrl.isBlank() || settings.profileId == null) c.rose.ink else c.ink2)
        }
        AppIcon(Icon.CHEVRON, c.ink3, 18.dp)
    }
}

/**
 * Pairing with the home server and choice of the profile runs go to, in a sheet. A new address is
 * only kept once it has answered as this product's server. While a server answers with profiles
 * and none of them is chosen, the sheet cannot be closed.
 */
@Composable
fun ServerSheet(settings: Settings, store: SettingsStore, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var link by remember { mutableStateOf<Link>(Link.Idle) }
    var profiles by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }

    fun connect(url: String, keep: Boolean) {
        link = Link.Checking
        scope.launch {
            link = try {
                val (version, list) = withContext(Dispatchers.IO) { ServerApi(url).let { it.health() to it.profiles() } }
                profiles = list
                if (keep) store.update { it.copy(serverUrl = ServerApi.normalise(url).toString().trimEnd('/')) }
                Link.Connected(version)
            } catch (e: ApiException) {
                Link.Failed(e.kind)
            }
        }
    }
    // Shows the state of the saved server when the screen opens.
    LaunchedEffect(Unit) { if (settings.serverUrl.isNotBlank()) connect(settings.serverUrl, keep = false) }

    val mustChoose = link is Link.Connected && profiles.isNotEmpty() && profiles.none { it.id == settings.profileId }
    val close = { if (!mustChoose) onDismiss() }
    BackHandler(onBack = close)

    BottomSheet(close) {
      Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.server_title), style = Type.h3, color = c.ink, modifier = Modifier.weight(1f))
                when (val l = link) {
                    is Link.Connected -> Tag(stringResource(R.string.server_connected, l.version), c.mint, Icon.CHECK)
                    is Link.Failed -> Tag(stringResource(R.string.server_unreachable), Pastel(c.surface2, c.ink2))
                    Link.Checking -> Tag(stringResource(R.string.server_checking), c.butter)
                    Link.Idle -> Unit
                }
            }
            TextInput(stringResource(R.string.server_address), address, { address = it }, "http://192.168.1.20:8080")
            (link as? Link.Failed)?.let {
                Text(
                    stringResource(if (it.kind == ApiException.Kind.NOT_OUR_SERVER) R.string.server_not_ours else R.string.server_no_answer),
                    style = Type.small, color = c.rose.ink,
                )
            }
            AppButton(stringResource(R.string.server_connect), { connect(address, keep = true) }, kind = ButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.server_privacy), style = Type.small, color = c.ink2)
        }

        if (profiles.isNotEmpty() || settings.profileId != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.profile_title), style = Type.h3, color = c.ink)
            if (profiles.isEmpty()) Text(settings.profileName, style = Type.strong, color = c.ink)
            profiles.forEachIndexed { i, p ->
                val on = settings.profileId == p.id
                Row(
                    Modifier.fillMaxWidth().clip(Radius.row).background(if (on) c.accentSoft else Color.Transparent)
                        .clickable(role = Role.RadioButton) {
                            scope.launch {
                                store.update { it.copy(profileId = p.id, profileName = p.name, units = p.units, zones = p.zones) }
                                Sync.schedule(context)
                            }
                        }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(avatarColors[i % avatarColors.size]), contentAlignment = Alignment.Center) {
                        Text(p.name.take(1).uppercase(), style = Type.h3, color = c.zoneInk)
                    }
                    Text(p.name, style = Type.strong, color = if (on) c.accent else c.ink, modifier = Modifier.weight(1f))
                    if (on) AppIcon(Icon.CHECK, c.accent, 18.dp)
                }
            }
            if (profiles.isNotEmpty() && profiles.none { it.id == settings.profileId } && settings.profileId != null) {
                Text(stringResource(R.string.profile_missing), style = Type.small, color = c.rose.ink)
            }
            Text(stringResource(R.string.profile_hint), style = Type.small, color = c.ink2)
        }
      }
      if (mustChoose) Text("Choisis un profil pour terminer.", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.rose.ink)
      else AppButton("Terminé", close, modifier = Modifier.fillMaxWidth())
    }
}
