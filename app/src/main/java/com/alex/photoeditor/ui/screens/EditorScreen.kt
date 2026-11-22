package com.alex.photoeditor.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.photoeditor.R
import com.alex.photoeditor.ui.EditorTool
import com.alex.photoeditor.ui.EditorViewModel
import com.alex.photoeditor.ui.rememberSafeArea
import com.alex.photoeditor.ui.GalleryTab
import com.alex.photoeditor.ui.components.AdjustPanel
import com.alex.photoeditor.ui.components.ColorPanel
import com.alex.photoeditor.ui.components.CropStage
import com.alex.photoeditor.ui.components.DrawGestureStage
import com.alex.photoeditor.ui.components.DrawPanel
import com.alex.photoeditor.ui.components.ExpressiveButton
import com.alex.photoeditor.ui.components.ExpressiveIconToggle
import com.alex.photoeditor.ui.components.ExpressiveTonalButton
import com.alex.photoeditor.ui.components.pressAndHold
import com.alex.photoeditor.ui.components.ExportSheet
import com.alex.photoeditor.ui.components.FilterPanel
import com.alex.photoeditor.ui.components.InfoPanel
import com.alex.photoeditor.ui.components.LayerGestureStage
import com.alex.photoeditor.ui.components.LayersPanel
import com.alex.photoeditor.ui.components.NewProjectSheet
import com.alex.photoeditor.ui.components.NamedActionIconButton
import com.alex.photoeditor.ui.components.ProjectsSheet
import com.alex.photoeditor.ui.components.TapSelectPanel
import com.alex.photoeditor.ui.components.TextGestureStage
import com.alex.photoeditor.ui.components.TextPanel
import com.alex.photoeditor.ui.components.ToolRail
import com.alex.photoeditor.ui.components.TransformPanel

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onPickImage: () -> Unit,
    onPickLayer: () -> Unit = {}
) {
    val state by vm.preview.collectAsStateWithLifecycle()

    // The window is edge-to-edge, so the notch and the navigation bar overlap
    // the content unless the safe area is applied here.
    val safeArea = rememberSafeArea()

    // Status messages ("Saved to gallery", "Could not write the file", ...)
    // used to be assigned into ViewModel state that nothing ever displayed,
    // so every confirmation was silently swallowed. The snackbar is the
    // standard place for them.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm.statusMessage) {
        val message = vm.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.clearStatus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top inset stays on the content; the bottom inset is owned by
        // the NavigationBar below, so the gesture pill and the nav bar
        // never overlap either editor chrome or gallery content.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    top = safeArea.top,
                    start = safeArea.start,
                    end = safeArea.end
                )
        ) {
                if (vm.showGallery) {
                    // `vm.resumeViewerUri` survives the gallery's exit and
                    // re-entry — it marks the image to re-open on Back.
                    GalleryScreen(
                        onEditPhoto = { uri -> vm.openImage(uri, returnToGallery = true) },
                        resumeViewerUri = vm.resumeViewerUri,
                        onViewerResumeConsumed = { vm.clearResumeViewerUri() },
                        startTab = vm.galleryStartTab,
                        onStartTabConsumed = { vm.consumeGalleryStartTab() }
                    )
                } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        state.error != null -> ErrorState(state.error!!, onPickImage)
                        state.bitmap == null -> EmptyState(
                            onOpenGallery = { vm.openGalleryAt(GalleryTab.PHOTOS) },
                            onOpenProjects = vm::openProjects
                        )
                        else -> EditorContent(
                            vm = vm,
                            state = state,
                            onPickLayer = onPickLayer
                        )
                    }

                    if (state.isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Anchored top: the bottom of the screen is the tool rail and
            // panel, and a snackbar over the export button area would
            // flicker touches.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            )
        }

        AppBottomNavBar(
            gallerySelected = vm.showGallery,
            onSelectEditor = vm::closeGallery,
            onSelectGallery = vm::openGallery
        )
    }

    if (vm.showExportSheet) {
        ExportSheet(vm = vm)
    }

    if (vm.showProjects) {
        ProjectsSheet(vm = vm)
    }

    // Drawn after the projects sheet so the chooser sits on top of it.
    if (vm.showNewProject) {
        NewProjectSheet(vm = vm, onPickImage = onPickImage)
    }
}

@Composable
private fun EditorContent(
    vm: EditorViewModel,
    state: com.alex.photoeditor.core.PreviewState,
    onPickLayer: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        TopBar(vm = vm, state = state)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Pinned dark regardless of the system theme. The surrounding
                // chrome follows the user's wallpaper palette, but judging
                // exposure or colour against a light or strongly tinted stage
                // biases perception, which is not acceptable in a photo tool.
                .background(Color(0xFF101014)),
            contentAlignment = Alignment.Center
        ) {
            // Press-and-hold compares against the untouched original.
            val isCropMode = vm.activeTool == EditorTool.CROP
            val isLayerMode = vm.activeTool == EditorTool.LAYERS
            val isTextMode = vm.activeTool == EditorTool.TEXT
            val isDrawMode = vm.activeTool == EditorTool.DRAW

            // The layer stack is taken from the *collected* state, not from
            // vm.currentLayers(). Reading a StateFlow's value directly does not
            // register a snapshot read, so a composable that did so only ever
            // recomposed when some unrelated Compose state changed - which is
            // why the eye and delete controls appeared to need the tool
            // reopening before they showed anything.
            val activeLayer = state.layers.firstOrNull { it.id == vm.activeLayerId }
                ?: state.layers.lastOrNull()

            // Taken from the collected state for the same reason as the image
            // stack: reading the repository directly would not register a
            // snapshot read and the stage would never appear.
            val activeText = state.textLayers.firstOrNull { it.id == vm.activeTextId }
                ?: state.textLayers.lastOrNull()

            val shown = when {
                vm.isComparing -> state.original
                // Cropping draws on the uncropped geometry render so the crop
                // rect is not applied twice.
                isCropMode -> state.cropBase ?: state.bitmap
                else -> state.bitmap
            }
            shown?.let { bmp ->
                if (isCropMode) {
                    // Interactive crop: the overlay must sit on the exact
                    // rendered image, so CropStage handles the letterboxing.
                    CropStage(
                        bitmap = bmp,
                        initial = vm.activeCropSelection(),
                        onPreviewChange = vm::updateCrop,
                        onCommit = vm::commitCrop
                    )
                } else if (isLayerMode && activeLayer != null) {
                    // The stacked image is dragged and pinched directly on the
                    // photo, so the gesture stage takes over the preview.
                    LayerGestureStage(
                        bitmap = bmp,
                        layer = activeLayer,
                        vm = vm
                    )
                } else if (isTextMode && activeText != null) {
                    // Same gesture contract as an image layer, so moving
                    // between a caption and a sticker feels identical. The
                    // eyedropper borrows the same stage: with it on, a tap
                    // samples the flattened preview instead of moving.
                    TextGestureStage(
                        bitmap = bmp,
                        layer = activeText,
                        vm = vm,
                        eyedropperActive = vm.eyedropperActive
                    )
                } else if (isDrawMode) {
                    // Drawing borrows the stage the same way text does: the
                    // surface is the photo itself, so a stroke has to land
                    // where the finger is rather than on a separate canvas.
                    DrawGestureStage(
                        bitmap = bmp,
                        vm = vm
                    )
                } else {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Edited photo preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .semantics { contentDescription = "Photo preview" }
                    )
                }
            }

            if (state.isRendering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(18.dp)
                )
            }
        }

        ToolRail(
            active = vm.activeTool,
            onSelect = vm::selectTool,
            collapsed = vm.panelCollapsed,
            onToggleCollapsed = vm::togglePanelCollapsed,
            modifier = Modifier.fillMaxWidth()
        )

        // Collapsed means the panel is gone entirely, not merely shorter. The
        // rail's own arrow brings it back, so the preview gets the full
        // remaining height - which is the entire point of the control.
        if (vm.panelCollapsed) return@Column

        // The tool panel is height-bounded and scrollable rather than taking
        // its natural height.
        //
        // The preview above carries weight(1f), so it absorbs whatever the
        // panel does not claim. Text has more controls than Add image (a
        // field, fonts, styles, a colour picker), so its panel pushed the
        // preview smaller and the photo rendered at a different size depending
        // on which tool happened to be open - which is what made the two tools
        // feel like they were not the same screen. Capping the panel keeps the
        // preview identical across every tool and moves the overflow into a
        // scroll, which is the honest place for it.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val panelMaxHeight = maxHeight * 0.45f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                when (vm.activeTool) {
                    EditorTool.ADJUST -> AdjustPanel(vm)
                    EditorTool.COLOR -> ColorPanel(vm)
                    EditorTool.FILTERS -> FilterPanel(vm)
                    EditorTool.CROP -> TransformPanel(vm, cropMode = true)
                    EditorTool.TRANSFORM -> TransformPanel(vm, cropMode = false)
                    // The tap targets the fully rendered image, including any
                    // cutout, so the user sees the selection as they make it.
                    // Using the geometry-only render here meant the stage
                    // showed an uncut photo and the removal only appeared after
                    // switching tools.
                    EditorTool.TAP_SELECT -> TapSelectPanel(vm, state.bitmap)
                    EditorTool.LAYERS -> LayersPanel(
                        vm = vm,
                        layers = state.layers,
                        onPickLayer = onPickLayer
                    )
                    EditorTool.TEXT -> TextPanel(
                        vm = vm,
                        texts = state.textLayers,
                        eyedropperOn = vm.eyedropperActive,
                        onEyedropperChange = vm::setEyedropper,
                        onAddText = { vm.addText() }
                    )
                    EditorTool.INFO -> InfoPanel(state.info)
                    EditorTool.DRAW -> DrawPanel(vm = vm)
                }
            }
        }
    }
}

/**
 * The app's permanent two-destination bar.
 *
 * The gallery used to be a one-off button on the home screen, which made it
 * invisible anywhere else and reachable only when no photo was open. A bottom
 * nav bar makes it a peer of the editor: one tap, always, from either place.
 *
 * Stays visible under the fullscreen viewer as well — it doubles as a quick
 * way back to the editor without hunting for the back arrow.
 */
@Composable
private fun AppBottomNavBar(
    gallerySelected: Boolean,
    onSelectEditor: () -> Unit,
    onSelectGallery: () -> Unit
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Gallery is the primary first tab and the landing destination when
        // the app starts; the editor sits second.
        NavigationBarItem(
            selected = gallerySelected,
            onClick = onSelectGallery,
            icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
            label = { Text(stringResource(R.string.title_gallery)) },
            alwaysShowLabel = true,
            modifier = Modifier.semantics { contentDescription = "Gallery" },
            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
            )
        )
        NavigationBarItem(
            selected = !gallerySelected,
            onClick = onSelectEditor,
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            label = { Text(stringResource(R.string.title_editor)) },
            alwaysShowLabel = true,
            modifier = Modifier.semantics { contentDescription = "Editor" },
            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
            )
        )
    }
}

@Composable
private fun TopBar(
    vm: EditorViewModel,
    state: com.alex.photoeditor.core.PreviewState
) {
    val comparing = vm.isComparing
    var saveMenuOpen by remember { mutableStateOf(false) }

    // The toolbar packs seven controls. On a narrow phone the old fixed-size
    // row overflowed and clipped the trailing Save button into the corner, so
    // we shrink the navigation cluster and drop the dimension label when the
    // screen is too narrow to afford it.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val roomy = maxWidth >= 400.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            NamedActionIconButton(
                name = "Back",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = { vm.closeImage() },
                contentDescription = "Back",
                size = 40.dp
            )

            NamedActionIconButton(
                name = "Undo",
                icon = Icons.AutoMirrored.Filled.Undo,
                onClick = vm::undo,
                enabled = state.canUndo,
                contentDescription = "Undo",
                size = 40.dp
            )

            NamedActionIconButton(
                name = "Redo",
                icon = Icons.AutoMirrored.Filled.Redo,
                onClick = vm::redo,
                enabled = state.canRedo,
                contentDescription = "Redo",
                size = 40.dp
            )

            // Hold to see the original. A filled tonal state while held makes
            // the "you are looking at the unedited photo" moment unambiguous.
            ExpressiveIconToggle(
                icon = Icons.Filled.Compare,
                contentDescription = "Hold to compare with original",
                onClick = {},
                selected = comparing,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(40.dp)
                    .pressAndHold(
                        onDown = { vm.toggleComparing(true) },
                        onUp = { vm.toggleComparing(false) }
                    )
            )

            Spacer(Modifier.weight(1f))

            if (roomy) {
                state.info?.let {
                    Text(
                        text = "${it.widthPx}x${it.heightPx}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                }
            }

            NamedActionIconButton(
                name = "Share",
                icon = Icons.Filled.Share,
                onClick = { vm.share() },
                contentDescription = "Share",
                size = 44.dp
            )

            // Full-size tonal button with a chevron badge so it reads as the
            // equal of Share and clearly opens a choice rather than saving.
            Box {
                FilledTonalIconButton(
                    onClick = { saveMenuOpen = true },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Save options" }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(13.dp)
                                .align(Alignment.BottomEnd)
                        )
                    }
                }
                DropdownMenu(
                    expanded = saveMenuOpen,
                    onDismissRequest = { saveMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Save Over") },
                        onClick = {
                            saveMenuOpen = false
                            vm.saveOver()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Save as Copy") },
                        onClick = {
                            saveMenuOpen = false
                            vm.openExportSheet()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    onOpenGallery: () -> Unit,
    onOpenProjects: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // The real app mark rather than a generic sliders glyph, so the first
        // screen is already recognisably the app. This is a flattened vector
        // rather than R.mipmap.ic_launcher: the launcher icon is an adaptive-
        // icon XML, which painterResource cannot load.
        Image(
            painter = painterResource(id = R.drawable.ic_brand),
            contentDescription = null,
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Edit by Alex",
            style = MaterialTheme.typography.titleLarge,
            // Explicitly on-surface rather than the default: the title sits on
            // the near-black background, where the inherited colour from the
            // picker surface read as grey and looked disabled.
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose a photo to start editing. Everything stays on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        ExpressiveButton(
            text = "Open photo",
            icon = Icons.Filled.PhotoLibrary,
            onClick = onOpenGallery
        )
        Spacer(Modifier.height(10.dp))
        ExpressiveTonalButton(
            text = "Projects",
            icon = Icons.Filled.Folder,
            onClick = onOpenProjects
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Edits save automatically, so nothing is lost if you close the app.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.OutlinedButton(onClick = onRetry) {
            Text("Try another photo")
        }
    }
}