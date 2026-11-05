package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.designsystem.StatusChip
import dev.herdr.mobile.core.model.Pane

/**
 * Pane switcher for a tab that holds more than one Herdr pane.
 *
 * The tab rail only opens a tab's *active* pane, so without this a split tab
 * was reachable from Home but not from inside the terminal. Rendered only when
 * there is something to switch, so single-pane tabs look exactly as before.
 */
@Composable
internal fun PaneRail(
    panes: List<Pane>,
    activePaneId: String?,
    onSelectPane: (paneId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panes.size < 2) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        itemsIndexed(panes, key = { _, pane -> pane.id }) { index, pane ->
            // Position first: split panes often share one title ("user@host:
            // /tmp"), so the number is the only thing that tells them apart.
            val name = pane.label?.takeIf { it.isNotBlank() }
                ?: pane.agent?.takeIf { it.isNotBlank() }
            StatusChip(
                label = if (name != null) "${index + 1} · $name" else "${index + 1}",
                status = pane.status,
                selected = pane.id == activePaneId,
                onClick = { onSelectPane(pane.id) },
            )
        }
    }
}
