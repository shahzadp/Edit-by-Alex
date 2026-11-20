package com.alex.photoeditor.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alex.photoeditor.R
import com.alex.photoeditor.ui.components.hasAllFilesAccess

/** One-time onboarding flag. Never shown again once marked done. */
object OnboardingPrefs {
    private const val PREFS = "edit_by_alex_onboarding"
    private const val KEY_DONE = "done_v1"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}

/** Android 8+ needs the "Install unknown apps" setting for APK installs. */
internal fun canInstallApks(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

/**
 * Shown on first launch only. Both permissions are opened in system settings
 * (special permissions, not runtime dialogs), so statuses are re-checked when
 * the app returns to the foreground.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var storageGranted by remember { mutableStateOf(hasAllFilesAccess(context)) }
    var installGranted by remember { mutableStateOf(canInstallApks(context)) }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> storageGranted = result }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                storageGranted = hasAllFilesAccess(context)
                installGranted = canInstallApks(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            runCatching { context.startActivity(intent) }
        } else {
            legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun openInstallSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { context.startActivity(intent) }
        }
    }

    BackHandler { (context as? Activity)?.finish() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_brand),
                contentDescription = null,
                modifier = Modifier.width(88.dp).aspectRatio(1f),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.onboarding_welcome),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Two system permissions let the app browse your files and install APKs you find. You'll only be asked once.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))

            PermissionCard(
                granted = storageGranted,
                icon = Icons.Filled.Storage,
                title = stringResource(R.string.onboarding_storage_title),
                description = "Needed to browse, organise and edit every file on this device.",
                buttonLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Enable in settings"
                } else {
                    "Grant access"
                },
                onClick = ::openStorageSettings
            )
            Spacer(Modifier.height(12.dp))
            PermissionCard(
                granted = installGranted,
                icon = Icons.Filled.GetApp,
                title = stringResource(R.string.onboarding_install_title),
                description = "Needed so APK files you tap can be installed with the system installer.",
                buttonLabel = "Allow in settings",
                onClick = ::openInstallSettings
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    OnboardingPrefs.markDone(context)
                    onFinished()
                },
                enabled = storageGranted && installGranted,
                modifier = Modifier.width(200.dp)
            ) {
                Text(stringResource(R.string.onboarding_start))
            }
            if (!storageGranted || !installGranted) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Grant both permissions above to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    granted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        if (granted) {
            androidx.compose.material3.AssistChip(
                onClick = {},
                label = { Text("Allowed") },
                leadingIcon = {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        } else {
            OutlinedButton(onClick = onClick) { Text(buttonLabel) }
        }
    }
}
