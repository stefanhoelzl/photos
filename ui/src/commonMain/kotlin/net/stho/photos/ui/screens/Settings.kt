package net.stho.photos.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.stho.photos.ui.state.SyncStatus
import net.stho.photos.ui.state.Totals

/**
 * Settings in E.1: account, sync, log out — and **no storage handling at all**.
 *
 * There is nothing to manage yet: caching is plain browse-to-cache, and the totals, the
 * per-album rows and both of their buttons arrive with E.2, the milestone that gives them
 * something to do.
 *
 * The sync row is load-bearing rather than decorative: a toast is transient, so this is the
 * durable record of a failure, and it is what makes a missed toast harmless.
 */
@Composable
public fun SettingsScreen(sync: SyncStatus, totals: Totals) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Library")
        Cell("Albums", "${totals.albums} · ${totals.photos} photos")
        SectionHeader("Sync")
        when (sync) {
            is SyncStatus.Never -> Cell("Last sync", "never")
            is SyncStatus.Running -> Cell("Last sync", "running…")
            is SyncStatus.Succeeded -> Cell("Last sync", sync.at.toString().substringBefore('.'))
            is SyncStatus.Failed -> {
                Cell("Last sync", sync.notice.title, error = true)
                Cell("", sync.notice.detail)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Cell(label: String, value: String, error: Boolean = false) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal, modifier = Modifier.fillMaxWidth(0.5f))
            Text(
                value,
                fontSize = 12.5.sp,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}
