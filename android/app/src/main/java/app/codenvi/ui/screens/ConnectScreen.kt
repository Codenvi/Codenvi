package app.codenvi.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.MainActivity
import app.codenvi.R
import app.codenvi.data.ConnectionString
import app.codenvi.data.ServerAddress
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.ConfirmDialog
import app.codenvi.ui.theme.Tokens

/** First run (docs/design.md §10.3 screen 1): disclaimer, then the server's host name and password. */
@Composable
fun ConnectScreen(vm: AppViewModel, activity: MainActivity, initialLink: String?, onDone: () -> Unit) {
    val secure by vm.repo.secure.collectAsStateWithLifecycle()
    // A codenvi:// link (opened from a message, or pasted into the host field) still works; otherwise: host + password.
    var host by remember { mutableStateOf(initialLink ?: "") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Build.MODEL ?: "Phone") }
    var busy by remember { mutableStateOf(false) }
    var confirmHost by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { activity.setSecure(true); onDispose { activity.setSecure(false) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Codenvi", style = MaterialTheme.typography.titleLarge, color = Tokens.Primary)
        Text("Drive Claude Code on your PCs from this phone.", color = Tokens.OnSurfaceMuted)

        if (!secure.disclaimerAccepted) {
            Card(colors = CardDefaults.cardColors(containerColor = Tokens.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Before you start", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.disclaimer), color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { vm.repo.acceptDisclaimer() }) { Text("I understand") }
                }
            }
            return@Column
        }

        Text("Connect to server", style = MaterialTheme.typography.titleMedium)
        Text("Enter your server's host name and its password (the CODENVI_ACCESS_KEY from the GitHub secret ENV_VARS). " +
            "Use the same two values on every PC and on this phone.", color = Tokens.OnSurfaceMuted)
        OutlinedTextField(
            value = host, onValueChange = { host = it }, label = { Text("Host name") },
            placeholder = { Text("codenvi.example.com") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        )
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name for this phone") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !busy && host.isNotBlank() && name.isNotBlank(),
            onClick = {
                if (host.trim().startsWith("codenvi://", ignoreCase = true)) {
                    val cs = runCatching { ConnectionString.parse(host) }.getOrElse { vm.say(it.message ?: "Invalid string"); return@Button }
                    if (cs.host == null) { vm.say("That's a key-only string. Paste a Connect a phone string."); return@Button }
                    confirmHost = cs.host
                    return@Button
                }
                val normalized = ServerAddress.normalize(host)
                if (normalized == null) { vm.say("Enter the server's host name, for example codenvi.example.com."); return@Button }
                if (password.isBlank()) { vm.say("Enter the server's password."); return@Button }
                confirmHost = normalized
            },
        ) { Text("Connect") }
        if (busy) CircularProgressIndicator(color = Tokens.Primary)
        Spacer(Modifier.height(24.dp))
    }

    confirmHost?.let { confirmedHost ->
        ConfirmDialog(
            title = "Confirm server",
            text = "Connect this phone to\n\n$confirmedHost\n\nOnly continue if this is your own Codenvi server.",
            confirm = "Connect",
            onConfirm = {
                busy = true
                vm.run(onOk = { busy = false; onDone() }) {
                    try {
                        if (host.trim().startsWith("codenvi://", ignoreCase = true)) vm.repo.pair(host, name)
                        else vm.repo.pairWithPassword(confirmedHost, password, name)
                    } finally { busy = false }
                }
            },
            onDismiss = { confirmHost = null },
        )
    }
}
