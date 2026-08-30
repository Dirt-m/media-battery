package app.mediabattery.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.mediabattery.AppGraph
import app.mediabattery.R
import app.mediabattery.data.AppLabels
import app.mediabattery.ui.common.SegmentOption
import app.mediabattery.ui.common.Segmented
import app.mediabattery.ui.friction.GateRequest
import app.mediabattery.ui.theme.SbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import site.dirt23.battery.core.identity.AppCatalog
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.rules.RulesDiff

/**
 * The apps section of the options page: what the battery does about each app.
 *
 * The rows are the tracked apps; a phone holds a couple of hundred and listing them all
 * would bury the rest of the page. Typing in the search field brings the untracked ones in.
 *
 * Loosening is gated, tightening is free. Block to On, On to Off, and dropping an app from
 * tracking all go through the friction gate first; a stricter mode happens on the tap. The
 * cost is flat: friction that grows is friction people turn off.
 */
fun LazyListScope.appsSection(
    graph: AppGraph,
    rows: List<AppRow>,
    /** The modes as they are right now, so a tap shows on the control it was made on. */
    modes: Map<String, SiteMode>,
    query: String,
    onQuery: (String) -> Unit,
    onGate: (GateRequest) -> Unit,
) {
    item { AppSearch(query, onQuery) }

    val q = query.trim().lowercase()
    val visible = if (q.isEmpty()) {
        rows.filter { it.tracked }
    } else {
        rows.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
    }

    items(visible, key = { it.packageName }) { row ->
        val mode = modes[row.id] ?: SiteMode.ON
        AppRowView(
            row = row,
            mode = mode,
            labels = graph.labels,
            onTrack = { graph.setTracked(row.packageName, true) },
            onUntrack = { onGate(GateRequest { graph.setTracked(row.packageName, false) }) },
            onMode = { next ->
                if (looser(next, mode)) {
                    onGate(GateRequest { graph.setMode(row.id, next) })
                } else {
                    graph.setMode(row.id, next)
                }
            },
        )
    }

    if (visible.isEmpty()) {
        item {
            Text(
                text = stringResource(if (q.isEmpty()) R.string.apps_hint else R.string.apps_empty),
                color = SbColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AppSearch(query: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SbColors.Bg)
            .border(2.dp, SbColors.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (query.isEmpty()) {
            Text(stringResource(R.string.apps_search), color = SbColors.Muted, fontSize = 15.sp)
        }
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = SbColors.Ink, fontSize = 15.sp),
            cursorBrush = SolidColor(SbColors.Green),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppRowView(
    row: AppRow,
    mode: SiteMode,
    labels: AppLabels,
    onTrack: () -> Unit,
    onUntrack: () -> Unit,
    onMode: (SiteMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (row.tracked) SbColors.Surface else SbColors.Bg)
            .border(1.dp, if (row.tracked) SbColors.Line else SbColors.Bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.packageName, labels)
            Spacer(Modifier.width(12.dp))
            Text(
                text = row.label,
                color = SbColors.Ink,
                fontSize = 16.sp,
                fontWeight = if (row.tracked) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (row.tracked) {
                Text(
                    text = stringResource(R.string.apps_untrack),
                    color = SbColors.Muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onUntrack)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.apps_track),
                    color = SbColors.Green,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onTrack)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (row.tracked) {
            Spacer(Modifier.height(12.dp))
            ModePicker(mode, onMode)
        }
    }
}

/** Decoding a launcher icon is disk work, so it happens off the main thread and lands late. */
@Composable
private fun AppIcon(packageName: String, labels: AppLabels) {
    val bitmap by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.Default) {
            runCatching { labels.icon(packageName)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
        }
    }
    val image = bitmap
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(SbColors.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun ModePicker(mode: SiteMode, onMode: (SiteMode) -> Unit) {
    val modes = listOf(SiteMode.ON, SiteMode.OFF, SiteMode.BLOCK)
    Segmented(
        options = listOf(
            SegmentOption(stringResource(R.string.mode_on)),
            SegmentOption(stringResource(R.string.mode_off), SbColors.Line),
            SegmentOption(stringResource(R.string.mode_block), SbColors.Red),
        ),
        selected = modes.indexOf(mode),
        onSelect = { onMode(modes[it]) },
    )
}

/** One app as the list shows it. The mode is read live off the settings instead. */
data class AppRow(
    val packageName: String,
    val id: String,
    val label: String,
    val tracked: Boolean,
)

/** Block is strictest, off loosest. Moving down that order is what costs; the order is the core's. */
private fun looser(next: SiteMode, current: SiteMode): Boolean =
    RulesDiff.rank(next) < RulesDiff.rank(current)

/**
 * The list, built off the main thread and rebuilt only when the picks move. Tracked first,
 * then everything else by name.
 *
 * The modes are deliberately not in here. Enumerating the launchable apps and sorting them
 * is the expensive part, and rebuilding it on every mode tap left the segmented control
 * showing the old pick until the walk came back.
 */
@Composable
fun rememberAppRows(graph: AppGraph): List<AppRow> {
    val state = produceState(initialValue = emptyList<AppRow>(), graph) {
        graph.tracked.packages.collectLatest { picked ->
            value = withContext(Dispatchers.Default) {
                graph.neverTrack.refresh()
                val blocked = graph.neverTrack.all()
                graph.labels.launchable()
                    .map { it.packageName }
                    .filterNot { it in blocked }
                    .map { pkg ->
                        AppRow(
                            packageName = pkg,
                            id = AppCatalog.idFor(pkg),
                            label = graph.labels.label(pkg),
                            tracked = pkg in picked,
                        )
                    }
                    .sortedWith(compareByDescending<AppRow> { it.tracked }.thenBy { it.label.lowercase() })
            }
        }
    }
    return state.value
}
