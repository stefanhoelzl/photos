package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.stho.photos.app.SaveOutcome

/**
 * §1's first-run screen: two fields, entered exactly once.
 *
 * **One URL carries everything** — the host is the endpoint, its `<region>-s3` prefix is the
 * signing region, and the first path segment is the zone, which on bunny.net *is* the access key
 * ID. So there is no separate endpoint field, no zone field and no key field, and the thing a
 * person is asked for is the thing they can copy out of their storage dashboard.
 *
 * **Nothing is validated against the network.** §1 accepts that a mistyped password is stored
 * happily and surfaces on the first sync, and pays for it by requiring that sync failures name
 * the status and the cause. What *is* checked is the URL, because that is a parse rather than a
 * question for the zone: an unparseable one could never reach a zone to be wrong about.
 *
 * There is no "skip" and no way back. §1 allows exactly two states — set up, or not — and a
 * third one reached by dismissing this screen is the ambiguity the rule exists to prevent.
 */
@Composable
public fun SetupScreen(onSave: (storageUrl: String, password: String) -> SaveOutcome) {
    var storageUrl by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rejection by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val outcome = onSave(storageUrl, password)
        rejection = (outcome as? SaveOutcome.Rejected)?.why
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Photos",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The storage URL and password for your zone. Both are stored on this device and " +
                "entered only once.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = storageUrl,
            onValueChange = { storageUrl = it; rejection = null },
            label = { Text("Storage URL") },
            placeholder = { Text("https://de-s3.storage.bunnycdn.com/my-photos") },
            singleLine = true,
            // No autocorrect and no capitalisation: this is a URL, and a phone keyboard that
            // helpfully capitalises the host produces a value that fails to parse for a reason
            // nobody can see.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; rejection = null },
            label = { Text("Password") },
            supportingText = { Text("The zone's Secret Access Key.") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        rejection?.let { why ->
            Spacer(Modifier.height(12.dp))
            Text(why, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = ::submit,
            // Disabled rather than rejecting on submit: an empty field is not a mistake worth
            // an error message, it is a form that is not finished.
            enabled = storageUrl.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

/**
 * What §1's third state looks like: the credential store could not be asked.
 *
 * Not the setup screen, deliberately. Sending someone here to retype a password they have
 * already stored would not unlock a locked keyring, and would quietly overwrite a working
 * install with whatever they typed the second time.
 */
@Composable
public fun BlockedScreen(reason: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Cannot reach the credential store",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(reason, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
