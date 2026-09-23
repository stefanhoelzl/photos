package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.stho.photos.app.Notice

/**
 * §1's "never an opaque error", as a toast rather than a banner.
 *
 * It never blocks and never reflows the list: the catalog and every thumbnail are already on
 * disk, so a failed sync must not stop you browsing. Colour follows the kind, which is the same
 * split that decides the duration — a failure needing a person is an error and waits for one; a
 * condition that clears itself is informational and says so quietly.
 */
@Composable
public fun Toast(notice: Notice, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val error = notice.kind == Notice.Kind.Error
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (error) scheme.errorContainer else scheme.surfaceContainerHigh)
            .border(
                width = if (error) 0.dp else 0.5.dp,
                color = if (error) scheme.error else scheme.outlineVariant,
                shape = RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onDismiss)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Text(
            notice.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (error) scheme.error else scheme.onSurface,
        )
        Text(
            notice.detail,
            fontSize = 12.sp,
            color = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant,
        )
        // Only an error carries an action: an informational toast names a condition there is
        // nothing to do about, and an action that leads nowhere is worse than none.
        if (error) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Settings",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(top = 6.dp),
                )
            }
        }
    }
}
