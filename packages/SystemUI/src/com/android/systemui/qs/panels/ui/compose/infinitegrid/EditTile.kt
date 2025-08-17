/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastMap
import com.android.compose.gesture.effect.rememberOffsetOverscrollEffectFactory
import com.android.compose.modifiers.height
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.common.ui.icons.MoreVert
import com.android.systemui.common.ui.icons.Undo
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.DragAndDropState
import com.android.systemui.qs.panels.ui.compose.DragType
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.EditTileListState.Companion.INVALID_INDEX
import com.android.systemui.qs.panels.ui.compose.dragAndDropRemoveZone
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileList
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileSource
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileArrangementPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ToggleTargetSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AUTO_SCROLL_DISTANCE
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AUTO_SCROLL_SPEED
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AVAILABLE_TILES_GRID_ALPHA
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AvailableTilesGridMinHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.CurrentTilesGridPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.GridBackgroundCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.TilePlacementSpec
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberTileShapeMode
import com.android.systemui.qs.panels.ui.compose.selection.InteractiveTileContainer
import com.android.systemui.qs.panels.ui.compose.selection.MutableSelectionState
import com.android.systemui.qs.panels.ui.compose.selection.ResizingState
import com.android.systemui.qs.panels.ui.compose.selection.ResizingState.ResizeOperation
import com.android.systemui.qs.panels.ui.compose.selection.ResizingState.ResizeOperation.FinalResizeOperation
import com.android.systemui.qs.panels.ui.compose.selection.ResizingState.ResizeOperation.TemporaryResizeOperation
import com.android.systemui.qs.panels.ui.compose.selection.StaticTileBadge
import com.android.systemui.qs.panels.ui.compose.selection.TileState
import com.android.systemui.qs.panels.ui.compose.selection.rememberResizingState
import com.android.systemui.qs.panels.ui.compose.selection.rememberSelectionState
import com.android.systemui.qs.panels.ui.compose.selection.selectableTile
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.SpacerGridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModelConstants.APP_ICON_INLINE_CONTENT_ID
import com.android.systemui.qs.panels.ui.viewmodel.EditTopBarActionViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridSnapshotViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.shared.model.groupAndSort
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

object TileType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditModeTopBar(
    onStopEditing: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val surfaceEffect2 = LocalAndroidColorScheme.current.surfaceEffect2
    TopAppBar(
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        title = {
            Text(
                text = stringResource(id = R.string.qs_edit_tiles),
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(start = 24.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onStopEditing,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = surfaceEffect2,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription =
                        stringResource(id = com.android.internal.R.string.action_bar_up_description),
                )
            }
        },
        actions = actions,
        modifier = modifier.padding(vertical = 8.dp),
        windowInsets = WindowInsets(0.dp),
    )
}

sealed interface EditAction {
    data class AddTile(val tileSpec: TileSpec) : EditAction

    data class InsertTile(val tileSpec: TileSpec, val position: Int) : EditAction

    data class RemoveTile(val tileSpec: TileSpec) : EditAction

    data class SetTiles(val tileSpecs: List<TileSpec>) : EditAction

    data class ResizeTile(val tileSpec: TileSpec, val toIcon: Boolean) : EditAction

    data object ResetGrid : EditAction
}

@Composable
private fun SingleTopBarAction(
    editTopBarActionViewModel: EditTopBarActionViewModel,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = { editTopBarActionViewModel.onClick() },
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        modifier = modifier,
    ) {
        Icon(
            editTopBarActionViewModel.icon,
            contentDescription = stringResource(id = editTopBarActionViewModel.labelId),
        )
    }
}

@Composable
private fun TopBarActionOverflow(
    actionsViewModel: SnapshotStateList<EditTopBarActionViewModel>,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        val density = LocalDensity.current
        val offset =
            with(density) {
                val safeContent = WindowInsets.safeDrawing
                val layoutDirection = LocalLayoutDirection.current
                DpOffset(
                    -safeContent.getLeft(this, layoutDirection).toDp(),
                    -safeContent.getTop(this).toDp(),
                )
            }
        IconButton(
            onClick = { showMenu = !showMenu },
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
        ) {
            Icon(
                MoreVert,
                contentDescription = stringResource(R.string.qs_edit_menu_content_description),
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.testTag(OPTIONS_DROP_DOWN_TEST_TAG).requiredWidthIn(min = 216.dp),
            containerColor = MaterialTheme.colorScheme.surfaceBright,
            offset = offset,
        ) {
            actionsViewModel.forEach { action ->
                key(action.labelId) {
                    DropdownMenuElement(action, dismissDropdown = { showMenu = false })
                }
            }
        }
    }
}

@Composable
private fun DropdownMenuElement(
    action: EditTopBarActionViewModel,
    dismissDropdown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        onClick = {
            action.onClick()
            dismissDropdown()
        },
        text = {
            Box(modifier = Modifier.padding(start = 6.dp)) {
                Text(
                    text = stringResource(action.labelId),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.wrapContentHeight(Alignment.CenterVertically),
                )
            }
        },
        leadingIcon = {
            Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        colors = menuItemColors(),
        contentPadding = PaddingValues(16.dp),
        modifier = modifier.heightIn(min = 52.dp),
    )
}

@ReadOnlyComposable
@Composable
private fun menuItemColors() =
    MenuItemColors(
        textColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        trailingIconColor = Color.Transparent,
        disabledTextColor = Color.Transparent,
        disabledLeadingIconColor = Color.Transparent,
        disabledTrailingIconColor = Color.Transparent,
    )

@Composable
fun DefaultEditTileGrid(
    listState: EditTileListState,
    allTiles: List<EditTileViewModel>,
    snapshotViewModel: InfiniteGridSnapshotViewModel,
    topBarActions: SnapshotStateList<EditTopBarActionViewModel>,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    onStopEditing: () -> Unit = {},
    onEditAction: (EditAction) -> Unit = {},
) {
    val selectionState = rememberSelectionState()

    LaunchedEffect(selectionState.placementEvent) {
        selectionState.placementEvent?.let { event ->
            listState
                .targetIndexForPlacement(event)
                .takeIf { it != INVALID_INDEX }
                ?.let { onEditAction(EditAction.InsertTile(event.movingSpec, it)) }
        }
    }

    Scaffold(
        modifier =
            modifier
                .consumeWindowInsets(WindowInsets.displayCutout)
                .sysuiResTag(EDIT_MODE_ROOT_TEST_TAG),
        containerColor = Color.Transparent,
        topBar = {
            EditModeTopBar(onStopEditing = onStopEditing, modifier = Modifier.statusBarsPadding()) {
                AnimatedVisibility(snapshotViewModel.canUndo, enter = fadeIn(), exit = fadeOut()) {
                    IconButton(
                        enabled = snapshotViewModel.canUndo,
                        onClick = {
                            selectionState.unSelect()
                            snapshotViewModel.undo()
                        },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    ) {
                        Icon(
                            Undo,
                            contentDescription =
                                stringResource(id = com.android.internal.R.string.undo),
                        )
                    }
                }
                if (topBarActions.size == 1) {
                    SingleTopBarAction(topBarActions.single())
                } else if (topBarActions.size > 1) {
                    TopBarActionOverflow(topBarActions)
                }
            }
        },
    ) { innerPadding ->
        CompositionLocalProvider(
            LocalOverscrollFactory provides rememberOffsetOverscrollEffectFactory()
        ) {
            AutoScrollGrid(listState, scrollState, innerPadding)

            LaunchedEffect(listState.dragType) {
                // Only scroll to the top when adding a new tile, not when reordering existing ones
                if (listState.dragInProgress && listState.dragType == DragType.Add) {
                    scrollState.animateScrollTo(0)
                }
            }

            EditModeScrollableColumn(
                listState = listState,
                selectionState = selectionState,
                innerPadding = innerPadding,
                scrollState = scrollState,
                onEditAction = onEditAction,
            ) {
                CurrentTilesGridHeader(
                    listState,
                    selectionState,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )

                CurrentTilesGrid(
                    listState = listState,
                    selectionState = selectionState,
                    onEditAction = onEditAction,
                )

                // Only show available tiles when a drag or placement isn't in progress, OR the
                // drag is within the current tiles grid
                AnimatedAvailableTilesGrid(
                    allTiles = allTiles,
                    listState = listState,
                    selectionState = selectionState,
                    onEditAction = onEditAction,
                    canLayoutTile = true,
                    showAvailableTiles =
                        !(listState.dragInProgress || selectionState.placementEnabled) ||
                            listState.dragType == DragType.Move,
                )
            }
        }
    }
}

@Composable
private fun EditModeScrollableColumn(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    innerPadding: PaddingValues,
    scrollState: ScrollState,
    onEditAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
        modifier =
            modifier
                .fillMaxSize()
                // Apply top padding before the scroll so the scrollable doesn't show under
                // the top bar
                .padding(top = innerPadding.calculateTopPadding())
                .clipScrollableContainer(Orientation.Vertical)
                .verticalScroll(scrollState)
                .dragAndDropRemoveZone(listState) { spec, removalEnabled ->
                    if (removalEnabled) {
                        // If removal is enabled, remove the tile
                        onEditAction(EditAction.RemoveTile(spec))
                    } else {
                        // Otherwise submit the new tile ordering
                        onEditAction(EditAction.SetTiles(listState.tileSpecs()))
                        selectionState.select(spec)
                    }
                },
    ) {
        content()

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
    }
}

@Composable
private fun AutoScrollGrid(
    listState: EditTileListState,
    scrollState: ScrollState,
    padding: PaddingValues,
) {
    val density = LocalDensity.current
    val (top, bottom) =
        remember(density) {
            with(density) {
                padding.calculateTopPadding().roundToPx() to
                    padding.calculateBottomPadding().roundToPx()
            }
        }
    val scrollTarget by
        remember(listState, scrollState, top, bottom) {
            derivedStateOf {
                val position = listState.draggedPosition
                if (position.isSpecified) {
                    // Return the scroll target needed based on the position of the drag movement,
                    // or null if we don't need to scroll
                    val y = position.y.roundToInt()
                    when {
                        y < AUTO_SCROLL_DISTANCE + top -> 0
                        y > scrollState.viewportSize - bottom - AUTO_SCROLL_DISTANCE ->
                            scrollState.maxValue
                        else -> null
                    }
                } else {
                    null
                }
            }
        }
    LaunchedEffect(scrollTarget) {
        scrollTarget?.let {
            // Change the duration of the animation based on the distance to maintain the
            // same scrolling speed
            val distance = abs(it - scrollState.value)
            scrollState.animateScrollTo(
                it,
                animationSpec =
                    tween(durationMillis = distance * AUTO_SCROLL_SPEED, easing = LinearEasing),
            )
        }
    }
}

private enum class EditModeHeaderState {
    Place,
    Idle,
}

@Composable
private fun rememberEditModeState(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
): State<EditModeHeaderState> {
    val editGridHeaderState = remember { mutableStateOf(EditModeHeaderState.Idle) }
    LaunchedEffect(
        listState.dragInProgress,
        selectionState.selected,
        selectionState.placementEnabled,
    ) {
        editGridHeaderState.value =
            when {
                selectionState.placementEnabled -> EditModeHeaderState.Place
                else -> EditModeHeaderState.Idle
            }
    }

    return editGridHeaderState
}

@Composable
private fun CurrentTilesGridHeader(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    modifier: Modifier = Modifier,
) {
    val editGridHeaderState by rememberEditModeState(listState, selectionState)

    AnimatedContent(
        targetState = editGridHeaderState,
        label = "QSEditHeader",
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) { state ->
        EditGridHeader {
            when (state) {
                EditModeHeaderState.Place -> {
                    EditGridCenteredText(text = stringResource(id = R.string.tap_to_position_tile))
                }
                EditModeHeaderState.Idle -> {
                    EditGridCenteredText(
                        text = stringResource(id = R.string.select_to_rearrange_tiles)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabGridHeader(@StringRes headerResId: Int, modifier: Modifier = Modifier) {
    Crossfade(targetState = headerResId, label = "QSEditHeader", modifier = modifier) {
        EditGridHeader { EditGridCenteredText(text = stringResource(id = it)) }
    }
}

@Composable
private fun EditGridHeader(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun EditGridCenteredText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun CurrentTilesGrid(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    onEditAction: (EditAction) -> Unit,
) {
    val currentListState by rememberUpdatedState(listState)
    val totalRows = listState.tiles.lastOrNull()?.row ?: 0
    val totalHeight by
        animateDpAsState(
            gridHeight(totalRows + 1, TileHeight, TileArrangementPadding, CurrentTilesGridPadding),
            label = "QSEditCurrentTilesGridHeight",
        )
    val gridState = rememberLazyGridState()
    var gridContentOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    val coroutineScope = rememberCoroutineScope()

    val primaryColor = MaterialTheme.colorScheme.primary
    TileLazyGrid(
        state = gridState,
        columns = GridCells.Fixed(listState.columns),
        contentPadding = PaddingValues(CurrentTilesGridPadding),
        modifier =
            Modifier.fillMaxWidth()
                .height { totalHeight.roundToPx() }
                .border(
                    width = 2.dp,
                    color = primaryColor,
                    shape = RoundedCornerShape(GridBackgroundCornerRadius),
                )
                .dragAndDropTileList(gridState, { gridContentOffset }, listState) { spec ->
                    onEditAction(EditAction.SetTiles(currentListState.tileSpecs()))
                    selectionState.select(spec)
                }
                .onGloballyPositioned { coordinates ->
                    gridContentOffset = coordinates.positionInRoot()
                }
                .drawBehind {
                    drawRoundRect(
                        primaryColor,
                        cornerRadius = CornerRadius(GridBackgroundCornerRadius.toPx()),
                        alpha = .15f,
                    )
                }
                .sysuiResTag(CURRENT_TILES_GRID_TEST_TAG),
    ) {
        EditTiles(
            listState = listState,
            selectionState = selectionState,
            gridState = gridState,
            coroutineScope = coroutineScope,
            onRemoveTile = { onEditAction(EditAction.RemoveTile(it)) },
        ) { resizingOperation ->
            when (resizingOperation) {
                is TemporaryResizeOperation -> {
                    currentListState.resizeTile(resizingOperation.spec, resizingOperation.toIcon)
                }
                is FinalResizeOperation -> {
                    with(resizingOperation) {
                        // Commit the new size of the tile IF the size changed. Do this check before
                        // a snapshot is taken to avoid saving an unnecessary snapshot
                        val isIcon = spec !in listState.largeTilesSpecs
                        if (isIcon != toIcon) {
                            onEditAction(EditAction.ResizeTile(spec, toIcon))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedAvailableTilesGrid(
    allTiles: List<EditTileViewModel>,
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    showAvailableTiles: Boolean,
    canLayoutTile: Boolean,
    onEditAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Sets a minimum height to be used when available tiles are hidden
    Box(
        Modifier.fillMaxWidth().requiredHeightIn(AvailableTilesGridMinHeight).animateContentSize()
    ) {

        // Using the fully qualified name here as a workaround for AnimatedVisibility not being
        // available from a Box
        androidx.compose.animation.AnimatedVisibility(
            visible = showAvailableTiles,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            // Hide available tiles when dragging
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement =
                    spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
                modifier = modifier.fillMaxSize(),
            ) {
                AvailableTileGrid(
                    allTiles,
                    selectionState,
                    listState.columns,
                    canLayoutTile = canLayoutTile,
                    { onEditAction(EditAction.AddTile(it)) }, // Add to the end
                    listState,
                )

                TextButton(
                    onClick = {
                        selectionState.unSelect()
                        onEditAction(EditAction.ResetGrid)
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    modifier = Modifier.padding(vertical = 16.dp),
                ) {
                    Text(
                        text = stringResource(id = com.android.internal.R.string.reset),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailableTileGrid(
    tiles: List<EditTileViewModel>,
    selectionState: MutableSelectionState,
    columns: Int,
    canLayoutTile: Boolean,
    onAddTile: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState,
) {
    // Group and sort to get the proper order tiles should be displayed in
    val groupedTileSpecs =
        remember(tiles.fastMap { it.category }, tiles.fastMap { it.label }) {
            groupAndSort(tiles).mapValues { tiles -> tiles.value.map { it.tileSpec } }
        }
    // Map of TileSpec to EditTileViewModel to use with the grouped tilespecs
    val viewModelsMap = remember(tiles) { tiles.associateBy { it.tileSpec } }

    // Available tiles
    Column(
        verticalArrangement = spacedBy(2.dp),
        horizontalAlignment = Alignment.Start,
        modifier =
            Modifier.fillMaxWidth().wrapContentHeight().sysuiResTag(AVAILABLE_TILES_GRID_TEST_TAG),
    ) {
        groupedTileSpecs.entries.forEachIndexed { index, (category, tileSpecs) ->
            key(category) {
                val shape =
                    when (index) {
                        0 ->
                            RoundedCornerShape(
                                topStart = GridBackgroundCornerRadius,
                                topEnd = GridBackgroundCornerRadius,
                            )
                        groupedTileSpecs.size - 1 ->
                            RoundedCornerShape(
                                bottomStart = GridBackgroundCornerRadius,
                                bottomEnd = GridBackgroundCornerRadius,
                            )
                        else -> RectangleShape
                    }
                Column(
                    verticalArrangement = spacedBy(16.dp),
                    modifier =
                        Modifier.background(
                                brush = SolidColor(MaterialTheme.colorScheme.surface),
                                shape = shape,
                                alpha = AVAILABLE_TILES_GRID_ALPHA,
                            )
                            .padding(16.dp),
                ) {
                    CategoryHeader(
                        category,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    )
                    tileSpecs.chunked(columns).forEach { row ->
                        Row(
                            horizontalArrangement = spacedBy(TileArrangementPadding),
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        ) {
                            for (tileSpec in row) {
                                val viewModel = viewModelsMap[tileSpec] ?: continue
                                key(tileSpec) {
                                    AvailableTileGridCell(
                                        cell = viewModel,
                                        dragAndDropState = dragAndDropState,
                                        selectionState = selectionState,
                                        canLayoutTile = canLayoutTile,
                                        onAddTile = onAddTile,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }

                            // Spacers for incomplete rows
                            repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

private fun gridHeight(rows: Int, tileHeight: Dp, tilePadding: Dp, gridPadding: Dp): Dp {
    return ((tileHeight + tilePadding) * rows) + gridPadding * 2
}

private fun GridCell.key(index: Int): Any {
    return if (this is TileGridCell) key else index
}

/**
 * Adds a list of [GridCell] to the lazy grid
 *
 * @param listState the [EditTileListState] for this grid
 * @param selectionState the [MutableSelectionState] for this grid
 * @param gridState the [LazyGridState] for this grid
 * @param coroutineScope the [CoroutineScope] to be used for the tiles
 * @param onRemoveTile the callback when a tile is removed from this grid
 * @param onResize the callback when a tile has a new [ResizeOperation]
 */
fun LazyGridScope.EditTiles(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    gridState: LazyGridState,
    coroutineScope: CoroutineScope,
    onRemoveTile: (TileSpec) -> Unit,
    onResize: (operation: ResizeOperation) -> Unit,
) {
    itemsIndexed(
        items = listState.tiles,
        key = { index, item -> item.key(index) },
        span = { _, item -> item.span },
        contentType = { _, _ -> TileType },
    ) { index, cell ->
        when (cell) {
            is TileGridCell ->
                if (listState.isMoving(cell.tile.tileSpec)) {
                    // If the tile is being moved, replace it with a visible spacer
                    SpacerGridCell(
                        Modifier.background(
                            color =
                                MaterialTheme.colorScheme.secondary.copy(
                                    alpha = EditModeTileDefaults.PLACEHOLDER_ALPHA
                                ),
                            shape = RoundedCornerShape(InactiveCornerRadius),
                        )
                    )
                } else {
                    TileGridCell(
                        cell = cell,
                        index = index,
                        dragAndDropState = listState,
                        selectionState = selectionState,
                        gridState = gridState,
                        onResize = onResize,
                        onRemoveTile = onRemoveTile,
                        coroutineScope = coroutineScope,
                        largeTilesSpan = listState.largeTilesSpan,
                    )
                }
            is SpacerGridCell ->
                SpacerGridCell(
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { selectionState.onTap(index) })
                    }
                )
        }
    }
}

@Composable
private fun rememberTileState(
    tile: EditTileViewModel,
    selectionState: MutableSelectionState,
): State<TileState> {
    val tileState = remember { mutableStateOf(TileState.None) }

    LaunchedEffect(selectionState.selection, selectionState.placementEnabled, tile.isRemovable) {
        tileState.value =
            selectionState.tileStateFor(tile.tileSpec, tileState.value, tile.isRemovable)
    }

    return tileState
}

@Composable
private fun LazyGridItemScope.TileGridCell(
    cell: TileGridCell,
    index: Int,
    dragAndDropState: DragAndDropState,
    selectionState: MutableSelectionState,
    gridState: LazyGridState,
    onResize: (operation: ResizeOperation) -> Unit,
    onRemoveTile: (TileSpec) -> Unit,
    coroutineScope: CoroutineScope,
    largeTilesSpan: Int,
    modifier: Modifier = Modifier,
) {
    val stateDescription = stringResource(id = R.string.accessibility_qs_edit_position, index + 1)
    val tileState by rememberTileState(cell.tile, selectionState)
    val resizingState = rememberResizingState(cell.tile.tileSpec, cell.isIcon)
    val progress: () -> Float = {
        if (tileState == TileState.Selected) {
            resizingState.progress()
        } else {
            if (cell.isIcon) 0f else 1f
        }
    }

    if (tileState != TileState.Selected) {
        // Update the draggable anchor state when the tile's size is not manually toggled
        LaunchedEffect(cell.isIcon) { resizingState.updateCurrentValue(cell.isIcon) }
    } else {
        // If the tile is selected, listen to new target values from the draggable anchor to toggle
        // the tile's size
        LaunchedEffect(resizingState) {
            snapshotFlow { resizingState.temporaryResizeOperation }
                .onEach { onResize(it) }
                .launchIn(this)
            snapshotFlow { resizingState.finalResizeOperation }
                .onEach { onResize(it) }
                .launchIn(this)
        }
    }

    val tilePadding = with(LocalDensity.current) { TileArrangementPadding.roundToPx() }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .map { layoutInfo ->
                layoutInfo.visibleItemsInfo
                    .find { it.key == cell.key }
                    ?.let { (it.span == 1) to it.size.width }
            }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (isIcon, width) ->
                resizingState.updateAnchors(isIcon, width, largeTilesSpan, tilePadding)
            }
    }

    val colors = EditModeTileDefaults.editTileColors()
    val toggleSizeLabel = stringResource(R.string.accessibility_qs_edit_toggle_tile_size_action)
    val togglePlacementModeLabel =
        stringResource(R.string.accessibility_qs_edit_toggle_placement_mode)
    val decorationClickLabel =
        when (tileState) {
            TileState.Removable ->
                stringResource(id = R.string.accessibility_qs_edit_remove_tile_action)
            TileState.Selected -> toggleSizeLabel
            TileState.None,
            TileState.Placeable,
            TileState.GreyedOut -> null
        }

    // TODO(b/412357793) Remove this workaround when animateItem is fixed for RTL grids
    // Don't apply the placementSpec to the selected tile as it interferes with
    // the resizing animation. The selection can't change positions without selecting a
    // different tile, so this isn't needed regardless.
    val placementSpec = if (tileState != TileState.Selected) TilePlacementSpec else null
    val removeTile = {
        selectionState.unSelect()
        onRemoveTile(cell.tile.tileSpec)
    }
    InteractiveTileContainer(
        tileState = tileState,
        resizingState = resizingState,
        modifier =
            modifier
                .height(TileHeight)
                .fillMaxWidth()
                .animateItem(placementSpec = placementSpec)
                .tileTestTag(cell.isIcon),
        onClick = {
            if (tileState == TileState.Removable) {
                removeTile()
            } else if (tileState == TileState.Selected) {
                coroutineScope.launch { resizingState.toggleCurrentValue() }
            }
        },
        contentDescription = decorationClickLabel,
    ) {
        val placeableColor = MaterialTheme.colorScheme.primary.copy(alpha = .4f)
        val backgroundColor by
            animateColorAsState(
                if (tileState == TileState.Placeable) placeableColor else colors.background
            )

        // Rapidly composing elements with the draggable modifier can cause visual jank. This
        // usually happens when resizing a tile multiple times. We can fix this by applying the
        // draggable modifier after the first frame
        var isSelectable by remember { mutableStateOf(false) }
        LaunchedEffect(dragAndDropState.dragInProgress) {
            isSelectable = !dragAndDropState.dragInProgress
        }
        val selectableModifier = Modifier.selectableTile(cell.tile.tileSpec, selectionState)
        val draggableModifier =
            Modifier.dragAndDropTileSource(
                SizedTileImpl(cell.tile, cell.width),
                dragAndDropState,
                DragType.Move,
                selectionState::unSelect,
            )

        val toggleSelectionLabel = stringResource(R.string.accessibility_qs_edit_toggle_selection)
        val placeTileLabel = stringResource(R.string.accessibility_qs_edit_place_tile_action)
        Box(
            Modifier.fillMaxSize()
                .clearAndSetSemantics {
                    this.stateDescription = stateDescription
                    contentDescription = cell.tile.label.text

                    if (isSelectable) {
                        val actions =
                            mutableListOf(
                                CustomAccessibilityAction(togglePlacementModeLabel) {
                                    selectionState.togglePlacementMode(cell.tile.tileSpec)
                                    true
                                }
                            )

                        if (selectionState.placementEnabled) {
                            actions.add(
                                CustomAccessibilityAction(placeTileLabel) {
                                    selectionState.placeTileAt(cell.tile.tileSpec)
                                    true
                                }
                            )
                        } else {
                            // Don't allow for resizing during placement mode
                            actions.add(
                                CustomAccessibilityAction(toggleSizeLabel) {
                                    onResize(FinalResizeOperation(cell.tile.tileSpec, !cell.isIcon))
                                    true
                                }
                            )
                            actions.add(
                                CustomAccessibilityAction(toggleSelectionLabel) {
                                    selectionState.toggleSelection(cell.tile.tileSpec)
                                    true
                                }
                            )
                        }

                        customActions = actions
                    }
                }
                .borderOnFocus(
                    MaterialTheme.colorScheme.secondary,
                    CornerSize(InactiveCornerRadius),
                )
                .thenIf(isSelectable) { draggableModifier }
                .tileBackground { backgroundColor }
                .clickable { selectionState.onTap(cell.tile.tileSpec) }
                .thenIf(isSelectable) { selectableModifier }
        ) {
            EditTile(
                tile = cell.tile,
                tileState = tileState,
                state = resizingState,
                progress = progress,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryHeader(category: TileCategory, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(category.iconId),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = category.label.load() ?: "",
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AvailableTileGridCell(
    cell: EditTileViewModel,
    dragAndDropState: DragAndDropState,
    selectionState: MutableSelectionState,
    canLayoutTile: Boolean,
    onAddTile: (TileSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateDescription: String? =
        if (cell.isCurrent) stringResource(R.string.accessibility_qs_edit_tile_already_added)
        else null

    val alpha by animateFloatAsState(if (cell.isCurrent) .38f else 1f)
    val colors = EditModeTileDefaults.editTileColors()
    val onClick: () -> Unit = {
        onAddTile(cell.tileSpec)
        if (canLayoutTile) {
            selectionState.select(cell.tileSpec)
        }
    }
    val clickLabel =
        stringResource(id = R.string.accessibility_qs_edit_named_tile_add_action, cell.label.text)

    // Displays the tile as an icon tile with the label underneath
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(CommonTileDefaults.TileStartPadding, Alignment.Top),
        modifier =
            modifier
                .graphicsLayer { this.alpha = alpha }
                .semantics(mergeDescendants = true) {
                    if (stateDescription != null) {
                        this.stateDescription = stateDescription
                    } else {
                        // This is needed due to b/418803616. When a clickable element that
                        // doesn't have semantics is slightly out of bounds of a scrollable
                        // container, it will be found by talkback. Because the text is off screen,
                        // it will say "Unlabelled". Instead, give it a role (that is also
                        // meaningful when on screen), and it will be skipped when not visible.
                        this.role = Role.Button
                    }
                }
                .sysuiResTag(AVAILABLE_TILE_TEST_TAG),
    ) {
        Box(Modifier.fillMaxWidth().height(TileHeight)) {
            val draggableModifier =
                if (cell.isCurrent || !canLayoutTile) {
                    Modifier
                } else {
                    Modifier.dragAndDropTileSource(
                        SizedTileImpl(cell, 1), // Available tiles are fixed at a width of 1
                        dragAndDropState,
                        DragType.Add,
                    ) {
                        selectionState.unSelect()
                    }
                }
            Box(
                Modifier.then(draggableModifier)
                    .fillMaxSize()
                    .borderOnFocus(
                        MaterialTheme.colorScheme.secondary,
                        CornerSize(InactiveCornerRadius),
                    )
                    .tileBackground { colors.background }
                    .clickable(
                        enabled = !cell.isCurrent,
                        onClick = onClick,
                        onClickLabel = clickLabel,
                    )
            ) {
                // Icon
                SmallTileContent(
                    iconProvider = { cell.icon },
                    color = colors.icon,
                    animateToEnd = true,
                    modifier = Modifier.align(Alignment.Center).clearAndSetSemantics {},
                )
            }

            StaticTileBadge(
                icon = Icons.Default.Add,
                contentDescription = clickLabel,
                enabled = !cell.isCurrent,
                onClick = onClick,
                modifier = Modifier.focusProperties { canFocus = false },
            )
        }
        Box(Modifier.fillMaxSize()) {
            val inlinedLabel = cell.inlinedLabel
            val icon = cell.appIcon
            if (inlinedLabel != null && icon != null && icon is Icon.Loaded) {
                AppIconText(icon, inlinedLabel, colors.label)
            } else {
                Text(
                    cell.label.text,
                    maxLines = 2,
                    color = colors.label,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto),
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.AppIconText(
    icon: Icon.Loaded,
    label: AnnotatedString,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val iconSize: TextUnit = dimensionResource(R.dimen.qs_edit_mode_app_icon).value.sp
    val inlineContent =
        remember(icon) {
            mapOf(
                Pair(
                    APP_ICON_INLINE_CONTENT_ID,
                    InlineTextContent(
                        Placeholder(
                            width = iconSize,
                            height = iconSize,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        )
                    ) {
                        Image(
                            rememberDrawablePainter(icon.drawable),
                            contentDescription = null,
                            Modifier.fillMaxSize(),
                        )
                    },
                )
            )
        }
    Text(
        label,
        maxLines = 2,
        color = color,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto),
        inlineContent = inlineContent,
        modifier = modifier.align(Alignment.TopCenter),
    )
}

@Composable
private fun SpacerGridCell(modifier: Modifier = Modifier) {
    // By default, spacers are invisible and exist purely to catch drag movements
    Box(modifier.height(TileHeight).fillMaxWidth())
}

@Composable
fun EditTile(
    tile: EditTileViewModel,
    tileState: TileState,
    state: ResizingState,
    progress: () -> Float,
    colors: TileColors = EditModeTileDefaults.editTileColors(),
) {
    val iconSizeDiff = CommonTileDefaults.IconSize - CommonTileDefaults.LargeTileIconSize
    val containerAlpha by animateFloatAsState(if (tileState == TileState.GreyedOut) .4f else 1f)
    Row(
        horizontalArrangement = spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.layout { measurable, constraints ->
                    val (min, max) = state.bounds
                    val currentProgress = progress()
                    // Always display the tile using the large size and trust the parent composable
                    // to clip the content as needed. This stop the labels from being truncated.
                    val width =
                        max?.roundToInt()?.takeIf { it > constraints.maxWidth }
                            ?: constraints.maxWidth
                    val placeable =
                        measurable.measure(constraints.copy(minWidth = width, maxWidth = width))

                    val startPadding =
                        if (currentProgress == 0f) {
                            // Find the center of the max width when the tile is icon only
                            iconHorizontalCenter(constraints.maxWidth)
                        } else {
                            // Find the center of the minimum width to hold the same position as the
                            // tile is resized.
                            val basePadding =
                                min?.let { iconHorizontalCenter(it.roundToInt()) } ?: 0f
                            // Large tiles, represented with a progress of 1f, have a 0.dp padding
                            basePadding * (1f - currentProgress)
                        }

                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeRelative(startPadding.roundToInt(), 0)
                    }
                }
                .largeTilePadding()
                .graphicsLayer { this.alpha = containerAlpha },
    ) {
        // Icon
        Box(
            Modifier.size(ToggleTargetSize).thenIf(tile.isDualTarget) {
                Modifier.drawBehind { drawCircle(colors.iconBackground, alpha = progress()) }
            }
        ) {
            SmallTileContent(
                iconProvider = { tile.icon },
                color = colors.icon,
                animateToEnd = true,
                size = { CommonTileDefaults.IconSize - iconSizeDiff * progress() },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Labels, positioned after the icon
        LargeTileLabels(
            label = tile.label.text,
            secondaryLabel = tile.appName?.text,
            colors = colors,
            modifier = Modifier.weight(1f).graphicsLayer { this.alpha = progress() },
        )
    }
}

private fun MeasureScope.iconHorizontalCenter(containerSize: Int): Float {
    return (containerSize - ToggleTargetSize.roundToPx()) / 2f -
        CommonTileDefaults.TileStartPadding.toPx()
}

@Composable
private fun editTileShape(shapeMode: Int): RoundedCornerShape {
    val radius = when (shapeMode) {
        1 -> InactiveCornerRadius // circle-ish
        2 -> ActiveTileCornerRadius // rounded square
        3 -> 0.dp // square
        else -> InactiveCornerRadius
    }
    return RoundedCornerShape(radius)
}

@Composable
private fun Modifier.tileBackground(color: () -> Color): Modifier {
    val shapeMode = rememberTileShapeMode()
    return clip(editTileShape(shapeMode)).drawBehind { drawRect(color()) }
}

private object EditModeTileDefaults {
    const val PLACEHOLDER_ALPHA = .3f
    const val AUTO_SCROLL_DISTANCE = 100
    const val AUTO_SCROLL_SPEED = 2 // 2ms per pixel
    const val AVAILABLE_TILES_GRID_ALPHA = .32f
    val CurrentTilesGridPadding = 10.dp
    val AvailableTilesGridMinHeight = 200.dp
    val GridBackgroundCornerRadius = 28.dp
    val TilePlacementSpec =
        spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioLowBouncy,
            visibilityThreshold = IntOffset.VisibilityThreshold,
        )

    @Composable
    fun editTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = LocalAndroidColorScheme.current.surfaceEffect2,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )
}

private const val EDIT_MODE_ROOT_TEST_TAG = "EditModeRoot"
private const val CURRENT_TILES_GRID_TEST_TAG = "CurrentTilesGrid"
private const val AVAILABLE_TILES_GRID_TEST_TAG = "AvailableTilesGrid"
private const val OPTIONS_DROP_DOWN_TEST_TAG = "OptionsDropdown"
private const val AVAILABLE_TILE_TEST_TAG = "AvailableTileTestTag"
