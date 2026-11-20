package com.alex.photoeditor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alex.photoeditor.ui.EditorViewModel
import com.alex.photoeditor.ui.screens.EditorScreen
import com.alex.photoeditor.ui.screens.OnboardingPrefs
import com.alex.photoeditor.ui.screens.OnboardingScreen
import com.alex.photoeditor.ui.components.AllFilesGate
import com.alex.photoeditor.ui.theme.PhotoEditorTheme

class MainActivity : ComponentActivity() {

    /**
     * Set when the picker or another app hands us an image to edit.
     *
     * This must be Compose state, not a plain field: `LaunchedEffect` only
     * re-runs when its key is observed, so a non-observable field would leave
     * the editor stuck on the empty screen after a pick.
     */
    private var pendingUri by mutableStateOf<Uri?>(null)

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // OpenDocument grants a persistable read grant, so the image stays
            // readable across process death. PickVisualMedia does not, which is
            // why a picked photo could not be re-opened later.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingUri = uri
        }
    }

    /**
     * Picks an image to stack as a layer.
     *
     * Separate from the base-image picker so the two flows do not fight over
     * the same pending state.
     */
    private val pickLayer = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            currentViewModel?.addLayer(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The activity's own dispatcher, not a new instance: a throwaway
        // dispatcher is never consulted by the system.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        pendingUri = extractImageUri(intent)

        setContent {
            PhotoEditorTheme {
                val vm: EditorViewModel = viewModel()
                val context = LocalContext.current
                val shareUri by vm.shareRequest.collectAsStateWithLifecycle()

                // Expose the ViewModel to onBackPressed, which runs outside
                // the composition.
                currentViewModel = vm

                LaunchedEffect(pendingUri) {
                    pendingUri?.let {
                        vm.openImage(it)
                        // Clear before the load finishes so a re-pick of the
                        // same photo still registers as a change.
                        pendingUri = null
                    }
                }

                LaunchedEffect(shareUri) {
                    val uri = shareUri ?: return@LaunchedEffect
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, "Share edited photo"))
                    }
                    vm.consumeShareRequest()
                }

                // One-time onboarding asks for the two special permissions,
                // then the normal gate keeps the app honest if storage access
                // is revoked later. Onboarding itself never appears again.
                var onboardingDone by remember { mutableStateOf(OnboardingPrefs.isDone(context)) }
                if (!onboardingDone) {
                    OnboardingScreen(onFinished = { onboardingDone = true })
                } else {
                    AllFilesGate {
                        EditorScreen(
                            vm = vm,
                            onPickImage = { pickImage.launch(arrayOf("image/*")) },
                            onPickLayer = { pickLayer.launch(arrayOf("image/*")) }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractImageUri(intent)?.let { pendingUri = it }
    }

    /**
     * Routes the system Back gesture.
     *
     * Without this the activity was a leaf: Back exited the app outright even
     * with the export sheet open or a photo loaded, which read as "the back
     * button does not work". Back now unwinds the current tab's chrome first,
     * then steps from the secondary tab back to the primary gallery tab, and
     * only leaves the app from the gallery.
     *
     * Uses the modern callback rather than the overridden `onBackPressed`,
     * which Android 13+ ignores once an activity opts into predictive back.
     */
    private fun handleBack() {
        val vm = currentViewModel
        if (vm == null) {
            finish()
            return
        }
        when {
            // Sheets are modal layers: Back dismisses them rather than doing
            // anything to what is underneath.
            vm.showExportSheet -> vm.closeExportSheet()

            vm.showProjects -> vm.closeProjects()

            vm.showNewProject -> vm.closeNewProject()

            // An image is open on the editor tab: step back to the editor home.
            vm.hasImage -> vm.closeImage()

            // The gallery is the primary tab, so Back there leaves the app.
            // (The viewer and the folder drill-in inside GalleryScreen have
            // their own BackHandlers and consume Back first when open.)
            vm.showGallery -> finish()

            // The editor is the secondary tab: Back lands on the primary tab
            // rather than exiting.
            else -> vm.openGallery()
        }
    }

    /**
     * The ViewModel lives inside `setContent`, so it is captured here for the
     * back callback to reach. Null until the composition has run once.
     */
    private var currentViewModel: EditorViewModel? = null

    /** Pulls an image out of a SEND, EDIT or VIEW intent. */
    private fun extractImageUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_EDIT, Intent.ACTION_VIEW -> intent.data
            else -> null
        }?.takeIf { it.scheme != null } ?: return null

        // An externally shared image arrives with a one-shot grant, so claim
        // the persistable flag too when the sender offered it. Without this the
        // read can be revoked before we finish copying.
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        return uri
    }
}
