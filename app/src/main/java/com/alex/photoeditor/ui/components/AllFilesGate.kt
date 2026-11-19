package com.alex.photoeditor.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alex.photoeditor.R

/**
 * The app does not run without all files access — full stop.
 *
 * The whole product is a file manager for media: browsing, organising,
 * folder creation, moves, copies and deletes. A half-open permission would
 * leave the user filing bug reports about files vanishing or refusing to
 * move, so the gate blocks every path until storage is granted.
 *
 * Android 11+ grants it through a system settings page ("All files access"),
 * which robots every start can't resume silently — that's why we re-check on
 * ON_START rather than once at composition time.
 *
 * On Android 10 and below, the same access arrives as the legacy
 * WRITE_EXTERNAL_STORAGE runtime permission.
 */
@Composable
fun AllFilesGate(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAllFilesAccess(context)) }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
    }

    // Re-check when the app returns to the foreground: the user grants the
    // special permission in Settings, not in our process.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                granted = hasAllFilesAccess(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (granted) {
            content()
        } else {
            AllFilesRequiredScreen(
                onEnableAccess = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        runCatching { context.startActivity(intent) }
                    } else {
                        legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                onExit = { (context as? Activity)?.finish() }
            )
            // Back must leave the app, not land on a half-live editor.
            BackHandler { (context as? Activity)?.finish() }
        }
    }
}

/** True when the app may freely read, move and create files on shared storage. */
fun hasAllFilesAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun AllFilesRequiredScreen(
    onEnableAccess: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_brand),
            contentDescription = null,
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(1f),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.padding(top = 24.dp))
        Text(
            "Full file access is required",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.padding(top = 8.dp))
        Text(
            "Edit by Alex browses, plays and organises your photos, videos and audio directly. It needs full file access to do that — there is no version of the app that works without it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.padding(top = 24.dp))
        Button(onClick = onEnableAccess) {
            Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Enable in settings" else "Grant access")
        }
        Spacer(Modifier.padding(top = 10.dp))
        OutlinedButton(onClick = onExit) {
            Text("Quit")
        }
    }
}
