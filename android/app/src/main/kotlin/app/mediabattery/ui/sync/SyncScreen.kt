package app.mediabattery.ui.sync

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mediabattery.AppGraph
import app.mediabattery.R
import app.mediabattery.ui.theme.SbColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.dirt23.battery.core.sync.LinkResult
import site.dirt23.battery.core.sync.SyncCrypto
import site.dirt23.battery.core.sync.SyncState
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sync setup, a port of the extension's sync page.
 *
 * Four things: what sync is doing right now, the code to carry to the other devices, a box
 * to paste one in, and the server for whoever runs their own. Not friction gated, here or
 * in the extension: nothing on this screen loosens a limit, and joining a shared battery is
 * more likely to lower the charge than raise it, since lower always wins.
 *
 * The two destructive moves ask first, in the same words the extension uses.
 */
@Composable
fun SyncScreen(graph: AppGraph, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val status by graph.syncStatus.collectAsStateWithLifecycle()
    val record by graph.syncStore.record.collectAsStateWithLifecycle()

    var joinInput by remember { mutableStateOf("") }
    var serverInput by remember(record.serverUrl) { mutableStateOf(record.serverUrl ?: "") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var confirmJoin by remember { mutableStateOf(false) }

    // The status card says how long ago the last round trip landed, so it has to keep
    // counting on its own.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            now = System.currentTimeMillis()
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    val errCode = stringResource(R.string.sync_err_code)
    val errProfile = stringResource(R.string.sync_err_profile)
    val errServer = stringResource(R.string.sync_err_server)

    if (confirmOff) {
        Confirm(
            text = stringResource(R.string.sync_off_confirm),
            onCancel = { confirmOff = false },
            onConfirm = {
                confirmOff = false
                message = null
                // Turning off shuts the engine down, so restart it straight away: without
                // that a code minted later in the same session never starts following.
                graph.onSync {
                    unlink()
                    start()
                }
            },
        )
    }

    if (confirmJoin) {
        Confirm(
            text = stringResource(R.string.sync_join_confirm),
            onCancel = { confirmJoin = false },
            onConfirm = {
                confirmJoin = false
                busy = true
                // On the sync engine's own scope, not the composition's: leaving this screen
                // mid join would cancel it, and a cancelled join never rolls back.
                graph.onSync {
                    val result = link(joinInput)
                    message = when (result) {
                        LinkResult.OK -> null
                        LinkResult.BAD_CODE -> errCode
                        LinkResult.NO_PROFILE -> errProfile
                        LinkResult.OFFLINE -> errServer
                    }
                    if (result == LinkResult.OK) joinInput = ""
                    busy = false
                }
            },
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .background(SbColors.Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Header(onBack)

        StatusCard(
            state = if (status.on) status.state else SyncState.OFF,
            lastSyncTs = status.lastSyncTs,
            now = now,
            onTurnOff = { confirmOff = true },
        )

        Card {
            Label(stringResource(R.string.sync_code_label), stringResource(R.string.sync_code_why))
            Spacer(Modifier.height(10.dp))
            val code = record.syncCode
            if (code == null) {
                Action(stringResource(R.string.sync_get_code), enabled = !busy) {
                    busy = true
                    message = null
                    graph.onSync {
                        enable()
                        busy = false
                    }
                }
            } else {
                val shown = SyncCrypto.formatCode(code)
                SelectionContainer {
                    Text(
                        text = shown,
                        color = SbColors.Ink,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SbColors.Bg)
                            .border(2.dp, SbColors.Line, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                val clipboard = LocalClipboard.current
                Action(
                    stringResource(if (copied) R.string.sync_copied else R.string.sync_copy)
                ) {
                    graph.scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", shown)))
                        copied = true
                    }
                }
            }
        }

        Card {
            Label(stringResource(R.string.sync_join_label), stringResource(R.string.sync_join_why))
            Spacer(Modifier.height(10.dp))
            Field(
                value = joinInput,
                hint = stringResource(R.string.sync_join_hint),
                monospace = true,
                onChange = { joinInput = it },
            )
            Spacer(Modifier.height(10.dp))
            Action(stringResource(R.string.sync_join), enabled = !busy && joinInput.isNotBlank()) {
                message = null
                confirmJoin = true
            }
        }

        Card {
            var open by remember { mutableStateOf(false) }
            Text(
                text = stringResource(R.string.sync_advanced),
                color = SbColors.Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { open = !open }
                    .padding(vertical = 4.dp),
            )
            if (open) {
                Spacer(Modifier.height(12.dp))
                Label(
                    stringResource(R.string.sync_server_label),
                    stringResource(R.string.sync_server_why),
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    value = serverInput,
                    hint = graph.sync.defaultServer(),
                    monospace = false,
                    onChange = { serverInput = it },
                )
                Spacer(Modifier.height(10.dp))
                Action(stringResource(R.string.sync_save), enabled = !busy) {
                    busy = true
                    message = null
                    graph.onSync {
                        setServer(serverInput)
                        busy = false
                    }
                }
            }
        }

        message?.let {
            Text(
                text = it,
                color = SbColors.Red,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sync_title),
            color = SbColors.Ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.sync_back),
            color = SbColors.Muted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** The live state in its own card: a dot, a word, a line, and the off switch. */
@Composable
private fun StatusCard(
    state: SyncState,
    lastSyncTs: Long,
    now: Long,
    onTurnOff: () -> Unit,
) {
    val label = stringResource(
        when (state) {
            SyncState.OFF -> R.string.sync_state_off
            SyncState.SYNCING -> R.string.sync_state_syncing
            SyncState.SYNCED -> R.string.sync_state_synced
            SyncState.OFFLINE -> R.string.sync_state_offline
        }
    )
    val detail = when (state) {
        SyncState.OFF -> stringResource(R.string.sync_state_off_detail)
        SyncState.OFFLINE -> stringResource(R.string.sync_state_offline_detail)
        SyncState.SYNCING -> ""
        SyncState.SYNCED -> if (lastSyncTs > 0) relAge(max(0L, now - lastSyncTs)) else ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SbColors.Surface)
            .border(1.dp, SbColors.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(syncColor(state)),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = SbColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (detail.isNotEmpty()) {
                Text(detail, color = SbColors.Muted, fontSize = 13.sp)
            }
        }
        if (state != SyncState.OFF) {
            Text(
                text = stringResource(R.string.sync_off),
                color = SbColors.Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(SbColors.SurfaceRaised)
                    .clickable(onClick = onTurnOff)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Dim when off, green when it is working, red when it cannot reach the server. */
fun syncColor(state: SyncState): Color = when (state) {
    SyncState.OFF -> SbColors.Muted
    SyncState.OFFLINE -> SbColors.Red
    SyncState.SYNCING, SyncState.SYNCED -> SbColors.Green
}

@Composable
private fun relAge(ms: Long): String = when {
    ms < 45_000 -> stringResource(R.string.sync_ago_now)
    ms < 3_600_000 -> stringResource(R.string.sync_ago_min, (ms / 60_000.0).roundToInt())
    else -> stringResource(R.string.sync_ago_hour, (ms / 3_600_000.0).roundToInt())
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SbColors.Surface)
            .border(1.dp, SbColors.Line, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun Label(title: String, why: String) {
    Text(title, color = SbColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(why, color = SbColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun Field(
    value: String,
    hint: String,
    monospace: Boolean,
    onChange: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SbColors.Bg)
            .border(2.dp, SbColors.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, color = SbColors.Muted, fontSize = 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(
                color = SbColors.Ink,
                fontSize = 15.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(SbColors.Green),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (enabled) SbColors.Ink else SbColors.Muted,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(SbColors.SurfaceRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun Confirm(text: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        text = { Text(text, color = SbColors.Ink, fontSize = 15.sp, lineHeight = 21.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.sync_confirm), color = SbColors.Green)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.sync_cancel), color = SbColors.Muted)
            }
        },
        containerColor = SbColors.Surface,
    )
}
