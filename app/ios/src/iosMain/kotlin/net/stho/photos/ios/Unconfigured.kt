package net.stho.photos.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.stho.photos.ui.screens.PhotosTheme

/**
 * What a build with no credentials shows, which on a phone is the only way to say it.
 *
 * The desktop root prints to stderr and exits 3; a UIKit entry point has neither option, and an
 * empty album list would look like a working app whose library happened to be empty — the
 * precise ambiguity §1 forbids.
 *
 * **This screen is a stopgap and goes away with the setup screen**, which is what will actually
 * let someone configure a TestFlight build. It exists so bring-up on the simulator has an
 * honest failure rather than a silent one.
 */
@Composable
internal fun Unconfigured() {
    PhotosTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Not set up",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "This build reads PHOTOS_ENDPOINT and PHOTOS_PASSWORD from the " +
                    "environment. Launch it with Scripts/ios-sim.sh, which passes them through.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
