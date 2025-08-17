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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.zIndex
import com.android.compose.gesture.effect.rememberOffsetOverscrollEffectFactory
import com.android.compose.modifiers.height
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.load
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
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileArrangementPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ToggleTargetSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AUTO_SCROLL_DISTANCE
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AUTO_SCROLL_SPEED
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AUTO_SELECT_DEBOUNCE_MILLIS
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AVAILABLE_TILES_GRID_ALPHA
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AvailableTilesGridMinHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.CurrentTilesGridPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.GridBackgroundCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.TilePlacementSpec
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberTileShapeMode
import com.android.systemui.qs.panels.ui.compose.selection.InteractiveTileContainer
import com.android.systemui.qs.panels.ui.compose.selection.MutableSelectionState
import com.android.systemui.qs.panels.ui.compose.selection.QSDragAnchorsData
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

object TileType

sealed interface EditAction {
    data class AddTile(val tileSpec: TileSpec) : EditAction

    data class InsertTile(val tileSpec: TileSpec, val position: Int) : EditAction

    data class RemoveTile(val tileSpec: TileSpec) : EditAction

    data class SetTiles(val tileSpecs: List<TileSpec>) : EditAction

    data class ResizeTile(val tileSpec: TileSpec, val toIcon: Boolean) : EditAction

    data object ResetGrid : EditAction
}

@OptIn(ExperimentalMaterial3Api::class)
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

    AutoSelectTiles(listState, selectionState)

    LaunchedEffect(selectionState.placementEvent) {
        selectionState.placementEvent?.let { event ->
            listState
                .targetIndexForPlacement(event)
                .takeIf { it != INVALID_INDEX }
                ?.let { onEditAction(EditAction.InsertTile(event.movingSpec, it)) }
        }
    }

    val undoAction: @Composable RowScope.() -> Unit = {
        AnimatedVisibility(snapshotViewModel.canUndo, enter = fadeIn(), exit = fadeOut()) {
            IconButton(
                enabled = snapshotViewModel.canUndo,
                onClick = snapshotViewModel::undo,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = LocalAndroidColorScheme.current.surfaceEffect1,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) {
                Icon(
                    Undo,
                    contentDescription = stringResource(id = com.android.internal.R.string.undo),
                )
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // Using current and available tiles to avoid the remove button's state rapidly toggling off and
    // on when adding a new tile
    val isTileRemovable: (TileSpec) -> Boolean by rememberUpdatedState { spec ->
        allTiles.find { it.tileSpec == spec }?.isRemovable ?: false
    }
    Scaffold(
        modifier =
            modifier
                .consumeWindowInsets(WindowInsets.displayCutout)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .sysuiResTag(EDIT_MODE_ROOT_TEST_TAG),
        containerColor = Color.Transparent,
        topBar = {
            EditModeExpandableTopBar(
                onStopEditing = onStopEditing,
                subtitle = { expanded: Boolean ->
                    if (expanded) {
                        TopBarSubtitle(
                            listState,
                            selectionState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                modifier = Modifier.statusBarsPadding(),
                scrollBehavior = scrollBehavior,
                collapsibleActions = topBarActions,
                actions = {
                    undoAction()

                    RemoveButton(
                        enabled = selectionState.selection?.let { isTileRemovable(it) } ?: false
                    ) {
                        selectionState.selection?.let { currentSelection ->
                            // Auto-select an adjacent tile when removing the selection,
                            // prioritizing the next tile and falling back to the previous tile
                            listState
                                .findNeighboringTile(currentSelection)
                                ?.let(selectionState::select)
                            onEditAction(EditAction.RemoveTile(currentSelection))
                        }
                    }
                },
            )
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

            NavBarInsetScrollZone {
                EditModeScrollableColumn(
                    listState = listState,
                    selectionState = selectionState,
                    innerPadding = innerPadding,
                    scrollState = scrollState,
                    onEditAction = onEditAction,
                ) {
                    Spacer(Modifier.height(16.dp))

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
private fun NavBarInsetScrollZone(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth()) {
        content()

        // Adding a scrollable box the size of the navigation bar at the bottom of the screen and
        // with a higher zIndex than the scrollable list of tiles.
        // This box intercepts scroll gestures in the navigation bar area without consuming them to
        // allow STL to handle them.
        Box(
            Modifier.align(Alignment.BottomCenter)
                .zIndex(2f)
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .scrollable(rememberScrollableState { 0f }, orientation = Orientation.Vertical)
        )
    }
}

/**
 * Auto-selects the first tile when needed
 *
 * This automatically selects the first tile if there's no current selections OR the current
 * selection was removed from the grid.
 */
@Composable
private fun AutoSelectTiles(listState: EditTileListState, selectionState: MutableSelectionState) {
    val specs = listState.tileSpecs()
    val selection = selectionState.selection

    LaunchedEffect(listState.dragInProgress, selection, specs) {
        // Avoid changing the selection while a drag is in progress
        if (listState.dragInProgress) return@LaunchedEffect

        // Select the first tile if:
        // - Nothing is selected
        // - The selection was removed
        val selectFirstTile = selection == null || selection !in specs

        if (selectFirstTile) {
            // Debounce specs updates
            // We're delaying for a short moment to make sure we're selecting the first tile when
            // the grid is idle, in case we received multiple updates in rapid succession.
            // This would ideally be tied to the animation speed for correctness.
            delay(AUTO_SELECT_DEBOUNCE_MILLIS)
            specs.firstOrNull()?.let { firstSpec -> selectionState.select(firstSpec) }
        }
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
private fun TopBarSubtitle(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    modifier: Modifier = Modifier,
) {
    val editGridHeaderState by rememberEditModeState(listState, selectionState)

    AnimatedContent(
        targetState = editGridHeaderState,
        label = "EditModeTopBarSubtitle",
        modifier = modifier,
    ) { state ->
        val text =
            when (state) {
                EditModeHeaderState.Place -> stringResource(id = R.string.tap_to_position_tile)
                EditModeHeaderState.Idle -> stringResource(id = R.string.select_to_rearrange_tiles)
            }
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RemoveButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val enabledTransition = updateTransition(enabled, "QSEditModeRemoveButton")
    val backgroundColor by
        enabledTransition.animateColor(transitionSpec = { tween() }) {
            if (it) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = .1f)
            }
        }
    val contentColor by
        enabledTransition.animateColor(transitionSpec = { tween() }) {
            if (it) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        }

    // Using a Box instead of a Button to animate the colors
    Box(
        modifier
            .drawBehind {
                drawRoundRect(
                    color = backgroundColor,
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp)
    ) {
        BasicText(
            text = stringResource(R.string.qs_customize_remove),
            color = { contentColor },
            style = MaterialTheme.typography.labelLarge,
        )
    }
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
private fun TileLazyGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        columns = columns,
        verticalArrangement = spacedBy(TileArrangementPadding),
        horizontalArrangement = spacedBy(TileArrangementPadding),
        contentPadding = contentPadding,
        userScrollEnabled = false,
        modifier = modifier,
        content = content,
    )
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
                    onClick = { onEditAction(EditAction.ResetGrid) },
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
                            shape = RoundedCornerShape(InactiveTileCornerRadius),
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
    val tileState = remember { mutableStateOf(TileState.New) }

    LaunchedEffect(selectionState.selection, selectionState.placementEnabled) {
        tileState.value =
            selectionState.tileStateFor(tile.tileSpec, tileState.value, canShowRemovalBadge = false)
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

    if (tileState == TileState.Selected) {
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
    var anchorsData: QSDragAnchorsData? by remember { mutableStateOf(null) }

    val scope = rememberCoroutineScope()
    LaunchedEffect(resizingState.isDragActive, anchorsData, scope) {
        anchorsData?.let {
            // Avoid changing anchors during drag gestures as this will snap the drag handle to the
            // closest anchor
            if (!resizingState.isDragActive) {
                resizingState.updateAnchors(it, largeTilesSpan, tilePadding)

                // Launching the animation in a separate scope to avoid cancelling ongoing
                // animations
                scope.launch { resizingState.updateCurrentValue(it.isIcon) }
            }
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .map { layoutInfo ->
                layoutInfo.visibleItemsInfo
                    .find { it.key == cell.key }
                    ?.let { QSDragAnchorsData(it.span == 1, it.size.width) }
            }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { anchorsData = it }
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
            TileState.New,
            TileState.None,
            TileState.Placeable,
            TileState.GreyedOut -> null
        }

    // TODO(b/412357793) Remove this workaround when animateItem is fixed for RTL grids
    // Don't apply the placementSpec to the selected tile as it interferes with
    // the resizing animation. The selection can't change positions without selecting a
    // different tile, so this isn't needed regardless.
    val placementSpec = TilePlacementSpec.takeIf { resizingState.isIdle }
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
            ) {
                selectionState.select(cell.tile.tileSpec)
            }

        val toggleSelectionLabel = stringResource(R.string.accessibility_qs_edit_toggle_selection)
        val placeTileLabel = stringResource(R.string.accessibility_qs_edit_place_tile_action)
        val containerAlpha by animateFloatAsState(if (tileState == TileState.GreyedOut) .4f else 1f)
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
                    CornerSize(InactiveTileCornerRadius),
                )
                .thenIf(isSelectable) { draggableModifier }
                .tileBackground(
                    cornerRadius = InactiveTileCornerRadius,
                    alpha = { containerAlpha },
                    color = { colors.background },
                )
                .keyboardShortcuts(cell.tile.tileSpec, selectionState) {
                    onResize(FinalResizeOperation(cell.tile.tileSpec, !cell.isIcon))
                }
                .thenIf(isSelectable) { selectableModifier }
        ) {
            EditTile(tile = cell.tile, state = resizingState, progress = resizingState::progress)
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
        verticalArrangement = spacedBy(CommonTileDefaults.StartPadding, Alignment.Top),
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
                        selectionState.select(cell.tileSpec)
                    }
                }
            Box(
                Modifier.then(draggableModifier)
                    .fillMaxSize()
                    .borderOnFocus(
                        MaterialTheme.colorScheme.secondary,
                        CornerSize(InactiveTileCornerRadius),
                    )
                    .tileBackground(cornerRadius = InactiveTileCornerRadius) { colors.background }
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
    state: ResizingState,
    progress: () -> Float,
    colors: TileColors = EditModeTileDefaults.editTileColors(),
) {
    val defaultStartPadding = CommonTileDefaults.StartPadding
    val iconSizeDiff = CommonTileDefaults.SmallTileIconSize - CommonTileDefaults.LargeTileIconSize
    val toggleTargetSize = ToggleTargetSize
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
                            iconHorizontalCenter(
                                padding = defaultStartPadding,
                                containerSize = constraints.maxWidth,
                                toggleTargetSize = toggleTargetSize,
                            )
                        } else {
                            // Find the center of the minimum width to hold the same position as the
                            // tile is resized.
                            val basePadding =
                                min?.let {
                                    iconHorizontalCenter(
                                        padding = defaultStartPadding,
                                        containerSize = it.roundToInt(),
                                        toggleTargetSize = toggleTargetSize,
                                    )
                                } ?: 0f
                            // Large tiles, represented with a progress of 1f, have a 0.dp padding
                            basePadding * (1f - currentProgress)
                        }

                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeRelative(startPadding.roundToInt(), 0)
                    }
                }
                .largeTilePadding(),
    ) {
        // Icon
        Box(
            Modifier.size(ToggleTargetSize)
                .let {
                    if (tile.isDualTarget) {
                        it.tileBackground(
                            cornerRadius = InactiveTileCornerRadius,
                            alpha = { progress() },
                            color = { colors.iconBackground },
                        )
                    } else {
                        it
                    }
                }
        ) {
            SmallTileContent(
                iconProvider = { tile.icon },
                color = colors.icon,
                animateToEnd = true,
                size = { CommonTileDefaults.SmallTileIconSize - iconSizeDiff * progress() },
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

private fun MeasureScope.iconHorizontalCenter(
    padding: Dp,
    containerSize: Int,
    toggleTargetSize: Dp,
): Float {
    return (containerSize - toggleTargetSize.roundToPx()) / 2f - padding.toPx()
}

@Composable
private fun editTileShape(cornerRadius: Dp): RoundedCornerShape {
    val shapeMode = rememberTileShapeMode()
    val radius = when (shapeMode) {
        1 -> InactiveTileCornerRadius /* Circle */
        2 -> ActiveTileCornerRadius /* Rounded Square */
        3 -> 0.dp /* Square */
        else -> cornerRadius
    }
    return RoundedCornerShape(radius)
}

@Composable
private fun Modifier.tileBackground(
    cornerRadius: Dp,
    alpha: () -> Float = { 1f },
    color: () -> Color,
): Modifier {
    // Clip tile contents from overflowing past the tile
    return clip(editTileShape(cornerRadius)).drawBehind { drawRect(color(), alpha = alpha()) }
}

private fun Modifier.keyboardShortcuts(
    tileSpec: TileSpec,
    selectionState: MutableSelectionState,
    onResize: () -> Unit,
): Modifier {
    return focusable().onKeyEvent {
        if (it.type != KeyEventType.KeyUp) {
            false
        } else {
            when (it.key) {
                Key.Enter -> {
                    selectionState.onTap(tileSpec)
                    true
                }
                Key.M -> { // Move
                    selectionState.enterPlacementMode(tileSpec)
                    true
                }
                Key.R -> { // Resize
                    onResize()
                    true
                }
                else -> false
            }
        }
    }
}

private object EditModeTileDefaults {
    const val PLACEHOLDER_ALPHA = .3f
    const val AUTO_SCROLL_DISTANCE = 100
    const val AUTO_SCROLL_SPEED = 2 // 2ms per pixel
    const val AVAILABLE_TILES_GRID_ALPHA = .32f
    const val AUTO_SELECT_DEBOUNCE_MILLIS = 100L
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
private const val AVAILABLE_TILE_TEST_TAG = "AvailableTileTestTag"
