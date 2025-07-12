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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import com.android.compose.gesture.effect.rememberOffsetOverscrollEffectFactory
import com.android.compose.modifiers.height
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.ui.compose.load
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.DragAndDropState
import com.android.systemui.qs.panels.ui.compose.DragType
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.EditTileListState.Companion.INVALID_INDEX
import com.android.systemui.qs.panels.ui.compose.dragAndDropRemoveZone
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileList
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileSource
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileArrangementPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ToggleTargetSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AUTO_SCROLL_DISTANCE
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AUTO_SCROLL_SPEED
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.AvailableTilesGridMinHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.CurrentTilesGridPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.EditModeTileDefaults.GridBackgroundCornerRadius
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
import com.android.systemui.qs.panels.ui.model.AvailableTileGridCell
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.SpacerGridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.shared.model.groupAndSort
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object TileType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditModeTopBar(onStopEditing: () -> Unit, onReset: (() -> Unit)?) {
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
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
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onStopEditing,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    tint = MaterialTheme.colorScheme.onSurface,
                    contentDescription =
                        stringResource(id = com.android.internal.R.string.action_bar_up_description),
                )
            }
        },
        actions = {
            if (onReset != null) {
                TextButton(
                    onClick = onReset,
                    colors =
                        ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Text(
                        text = stringResource(id = com.android.internal.R.string.reset),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun DefaultEditTileGrid(
    listState: EditTileListState,
    otherTiles: List<SizedTile<EditTileViewModel>>,
    columns: Int,
    largeTilesSpan: Int,
    modifier: Modifier,
    onAddTile: (TileSpec, Int) -> Unit,
    onRemoveTile: (TileSpec) -> Unit,
    onSetTiles: (List<TileSpec>) -> Unit,
    onResize: (TileSpec, toIcon: Boolean) -> Unit,
    onStopEditing: () -> Unit,
    onReset: (() -> Unit)?,
) {
    val selectionState = rememberSelectionState()
    val reset: (() -> Unit)? =
        if (onReset != null) {
            {
                selectionState.unSelect()
                onReset()
            }
        } else {
            null
        }

    LaunchedEffect(selectionState.placementEvent) {
        selectionState.placementEvent?.let { event ->
            listState
                .targetIndexForPlacement(event)
                .takeIf { it != INVALID_INDEX }
                ?.let { onAddTile(event.movingSpec, it) }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { EditModeTopBar(onStopEditing = onStopEditing, onReset = reset) },
    ) { innerPadding ->
        CompositionLocalProvider(
            LocalOverscrollFactory provides rememberOffsetOverscrollEffectFactory()
        ) {
            val scrollState = rememberScrollState()

            AutoScrollGrid(listState, scrollState, innerPadding)

            LaunchedEffect(listState.dragType) {
                // Only scroll to the top when adding a new tile, not when reordering existing ones
                if (listState.dragInProgress && listState.dragType == DragType.Add) {
                    scrollState.animateScrollTo(0)
                }
            }

            Column(
                verticalArrangement =
                    spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
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
                                onRemoveTile(spec)
                            } else {
                                // Otherwise submit the new tile ordering
                                onSetTiles(listState.tileSpecs())
                                selectionState.select(spec)
                            }
                        },
            ) {
                CurrentTilesGridHeader(
                    listState,
                    selectionState,
                    onRemoveTile,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )

                CurrentTilesGrid(
                    listState,
                    selectionState,
                    columns,
                    largeTilesSpan,
                    onResize,
                    onRemoveTile,
                    onSetTiles,
                )

                // Sets a minimum height to be used when available tiles are hidden
                Box(
                    Modifier.fillMaxWidth()
                        .requiredHeightIn(AvailableTilesGridMinHeight)
                        .animateContentSize()
                ) {
                    // Using the fully qualified name here as a workaround for AnimatedVisibility
                    // not being available from a Box
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !listState.dragInProgress && !selectionState.placementEnabled,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        // Hide available tiles when dragging
                        Column(
                            verticalArrangement =
                                spacedBy(dimensionResource(id = R.dimen.qs_label_container_margin)),
                            modifier = modifier.fillMaxSize(),
                        ) {
                            val availableTiles = remember {
                                mutableStateListOf<AvailableTileGridCell>().apply {
                                    addAll(toAvailableTiles(listState.tiles, otherTiles))
                                }
                            }
                            LaunchedEffect(listState.tiles, otherTiles) {
                                availableTiles.apply {
                                    clear()
                                    addAll(toAvailableTiles(listState.tiles, otherTiles))
                                }
                            }
                            AvailableTileGrid(
                                availableTiles,
                                selectionState,
                                columns,
                                { onAddTile(it, listState.tileSpecs().size) }, // Add to the end
                                listState,
                            )
                        }
                    }
                }
            }
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
    Remove,
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
        val canRemove =
            listState.isDraggedCellRemovable ||
                selectionState.selection?.let { listState.isRemovable(it) } ?: false

        editGridHeaderState.value =
            when {
                selectionState.placementEnabled -> EditModeHeaderState.Place
                canRemove -> EditModeHeaderState.Remove
                else -> EditModeHeaderState.Idle
            }
    }

    return editGridHeaderState
}

@Composable
private fun CurrentTilesGridHeader(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    onRemoveTile: (TileSpec) -> Unit,
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
                EditModeHeaderState.Remove -> {
                    RemoveTileTarget {
                        selectionState.selection?.let {
                            selectionState.unSelect()
                            onRemoveTile(it)
                        }
                    }
                }
                EditModeHeaderState.Place -> {
                    EditGridCenteredText(text = stringResource(id = R.string.tap_to_position_tile))
                }
                EditModeHeaderState.Idle -> {
                    EditGridCenteredText(
                        text = stringResource(id = R.string.drag_to_rearrange_tiles)
                    )
                }
            }
        }
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
    Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = modifier)
}

@Composable
private fun RemoveTileTarget(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = tileHorizontalArrangement(),
        modifier =
            Modifier.wrapContentSize()
                .clickable(onClick = onClick)
                .border(1.dp, LocalContentColor.current, shape = CircleShape)
                .padding(10.dp),
    ) {
        Icon(imageVector = Icons.Default.Clear, contentDescription = null)
        Text(text = stringResource(id = R.string.qs_customize_remove))
    }
}

@Composable
private fun CurrentTilesGrid(
    listState: EditTileListState,
    selectionState: MutableSelectionState,
    columns: Int,
    largeTilesSpan: Int,
    onResize: (TileSpec, toIcon: Boolean) -> Unit,
    onRemoveTile: (TileSpec) -> Unit,
    onSetTiles: (List<TileSpec>) -> Unit,
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

    val cells = listState.tiles
    val primaryColor = MaterialTheme.colorScheme.primary
    TileLazyGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
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
                    onSetTiles(currentListState.tileSpecs())
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
                .testTag(CURRENT_TILES_GRID_TEST_TAG),
    ) {
        EditTiles(
            cells = cells,
            dragAndDropState = listState,
            selectionState = selectionState,
            coroutineScope = coroutineScope,
            largeTilesSpan = largeTilesSpan,
            onRemoveTile = onRemoveTile,
        ) { resizingOperation ->
            when (resizingOperation) {
                is TemporaryResizeOperation -> {
                    currentListState.resizeTile(resizingOperation.spec, resizingOperation.toIcon)
                }
                is FinalResizeOperation -> {
                    // Commit the new size of the tile
                    onResize(resizingOperation.spec, resizingOperation.toIcon)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AvailableTileGrid(
    tiles: List<AvailableTileGridCell>,
    selectionState: MutableSelectionState,
    columns: Int,
    onAddTile: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState,
) {
    // Available tiles aren't visible during drag and drop, so the row/col isn't needed
    val groupedTiles =
        remember(tiles.fastMap { it.tile.category }, tiles.fastMap { it.tile.label }) {
            groupAndSort(tiles)
        }

    // Available tiles
    Column(
        verticalArrangement = spacedBy(TileArrangementPadding),
        horizontalAlignment = Alignment.Start,
        modifier =
            Modifier.fillMaxWidth().wrapContentHeight().testTag(AVAILABLE_TILES_GRID_TEST_TAG),
    ) {
        groupedTiles.forEach { (category, tiles) ->
            key(category) {
                val surfaceColor = MaterialTheme.colorScheme.surface
                Column(
                    verticalArrangement = spacedBy(16.dp),
                    modifier =
                        Modifier.drawBehind {
                                drawRoundRect(
                                    surfaceColor,
                                    cornerRadius = CornerRadius(GridBackgroundCornerRadius.toPx()),
                                    alpha = .32f,
                                )
                            }
                            .padding(16.dp),
                ) {
                    CategoryHeader(
                        category,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    )
                    tiles.chunked(columns).forEach { row ->
                        Row(
                            horizontalArrangement = spacedBy(TileArrangementPadding),
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        ) {
                            row.forEach { tileGridCell ->
                                key(tileGridCell.key) {
                                    AvailableTileGridCell(
                                        cell = tileGridCell,
                                        dragAndDropState = dragAndDropState,
                                        selectionState = selectionState,
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

fun gridHeight(rows: Int, tileHeight: Dp, tilePadding: Dp, gridPadding: Dp): Dp {
    return ((tileHeight + tilePadding) * rows) + gridPadding * 2
}

private fun GridCell.key(index: Int): Any {
    return if (this is TileGridCell) key else index
}

/**
 * Adds a list of [GridCell] to the lazy grid
 *
 * @param cells the list of [GridCell]
 * @param dragAndDropState the [DragAndDropState] for this grid
 * @param selectionState the [MutableSelectionState] for this grid
 * @param coroutineScope the [CoroutineScope] to be used for the tiles
 * @param largeTilesSpan the width used for large tiles
 * @param onRemoveTile the callback when a tile is removed from this grid
 * @param onResize the callback when a tile has a new [ResizeOperation]
 */
fun LazyGridScope.EditTiles(
    cells: List<GridCell>,
    dragAndDropState: DragAndDropState,
    selectionState: MutableSelectionState,
    coroutineScope: CoroutineScope,
    largeTilesSpan: Int,
    onRemoveTile: (TileSpec) -> Unit,
    onResize: (operation: ResizeOperation) -> Unit,
) {
    items(
        count = cells.size,
        key = { cells[it].key(it) },
        span = { cells[it].span },
        contentType = { TileType },
    ) { index ->
        when (val cell = cells[index]) {
            is TileGridCell ->
                if (dragAndDropState.isMoving(cell.tile.tileSpec)) {
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
                        dragAndDropState = dragAndDropState,
                        selectionState = selectionState,
                        onResize = onResize,
                        onRemoveTile = onRemoveTile,
                        coroutineScope = coroutineScope,
                        largeTilesSpan = largeTilesSpan,
                        modifier =
                            Modifier.animateItem(
                                placementSpec =
                                    spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    )
                            ),
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
    val canShowRemovalBadge = tile.isRemovable

    LaunchedEffect(selectionState.selection, selectionState.placementEnabled, canShowRemovalBadge) {
        tileState.value =
            selectionState.tileStateFor(tile.tileSpec, tileState.value, canShowRemovalBadge)
    }

    return tileState
}

@Composable
private fun TileGridCell(
    cell: TileGridCell,
    index: Int,
    dragAndDropState: DragAndDropState,
    selectionState: MutableSelectionState,
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
        LaunchedEffect(resizingState.temporaryResizeOperation) {
            onResize(resizingState.temporaryResizeOperation)
        }
        LaunchedEffect(resizingState.finalResizeOperation) {
            onResize(resizingState.finalResizeOperation)
        }
    }

    val totalPadding =
        with(LocalDensity.current) { (largeTilesSpan - 1) * TileArrangementPadding.roundToPx() }
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
    InteractiveTileContainer(
        tileState = tileState,
        resizingState = resizingState,
        modifier =
            modifier.height(TileHeight).fillMaxWidth().onSizeChanged {
                // Calculate the min/max width from the idle size
                val min = if (cell.isIcon) it.width else (it.width - totalPadding) / largeTilesSpan
                val max = if (cell.isIcon) (it.width * largeTilesSpan) + totalPadding else it.width
                resizingState.updateAnchors(min.toFloat(), max.toFloat())
            },
        onClick = {
            if (tileState == TileState.Removable) {
                onRemoveTile(cell.tile.tileSpec)
            } else if (tileState == TileState.Selected) {
                coroutineScope.launch { resizingState.toggleCurrentValue() }
            }
        },
        onClickLabel = decorationClickLabel,
    ) {
        val placeableColor = MaterialTheme.colorScheme.primary.copy(alpha = .4f)
        val backgroundColor by
            animateColorAsState(
                if (tileState == TileState.Placeable) placeableColor else colors.background
            )
        Box(
            modifier
                .fillMaxSize()
                .semantics(mergeDescendants = true) {
                    this.stateDescription = stateDescription
                    contentDescription = cell.tile.label.text
                    customActions =
                        listOf(
                            // TODO(b/367748260): Add final accessibility actions
                            CustomAccessibilityAction(toggleSizeLabel) {
                                onResize(FinalResizeOperation(cell.tile.tileSpec, !cell.isIcon))
                                true
                            },
                            CustomAccessibilityAction(togglePlacementModeLabel) {
                                selectionState.togglePlacementMode(cell.tile.tileSpec)
                                true
                            },
                        )
                }
                .selectableTile(cell.tile.tileSpec, selectionState)
                .dragAndDropTileSource(
                    SizedTileImpl(cell.tile, cell.width),
                    dragAndDropState,
                    DragType.Move,
                    selectionState::unSelect,
                )
                .tileBackground { backgroundColor }
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
    cell: AvailableTileGridCell,
    dragAndDropState: DragAndDropState,
    selectionState: MutableSelectionState,
    onAddTile: (TileSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateDescription: String? =
        if (cell.isAvailable) null
        else stringResource(R.string.accessibility_qs_edit_tile_already_added)

    val alpha by animateFloatAsState(if (cell.isAvailable) 1f else .38f)
    val colors = EditModeTileDefaults.editTileColors()

    // Displays the tile as an icon tile with the label underneath
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(CommonTileDefaults.TileStartPadding, Alignment.Top),
        modifier =
            modifier
                .graphicsLayer { this.alpha = alpha }
                .semantics(mergeDescendants = true) {
                    stateDescription?.let { this.stateDescription = it }
                },
    ) {
        Box(Modifier.fillMaxWidth().height(TileHeight)) {
            val draggableModifier =
                if (cell.isAvailable) {
                    Modifier.dragAndDropTileSource(
                        SizedTileImpl(cell.tile, cell.width),
                        dragAndDropState,
                        DragType.Add,
                    ) {
                        selectionState.unSelect()
                    }
                } else {
                    Modifier
                }
            Box(draggableModifier.fillMaxSize().tileBackground { colors.background }) {
                // Icon
                SmallTileContent(
                    iconProvider = { cell.tile.icon },
                    color = colors.icon,
                    animateToEnd = true,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            StaticTileBadge(
                icon = Icons.Default.Add,
                contentDescription =
                    stringResource(id = R.string.accessibility_qs_edit_tile_add_action),
                enabled = cell.isAvailable,
            ) {
                onAddTile(cell.tile.tileSpec)
                selectionState.select(cell.tile.tileSpec)
            }
        }
        Box(Modifier.fillMaxSize()) {
            Text(
                cell.tile.label.text,
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
    val alpha by animateFloatAsState(if (tileState == TileState.GreyedOut) .4f else 1f)
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
                        placeable.place(startPadding.roundToInt(), 0)
                    }
                }
                .largeTilePadding()
                .graphicsLayer { this.alpha = alpha },
    ) {
        // Icon
        Box(Modifier.size(ToggleTargetSize)) {
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

private fun toAvailableTiles(
    currentTiles: List<GridCell>,
    otherTiles: List<SizedTile<EditTileViewModel>>,
): List<AvailableTileGridCell> {
    return currentTiles.filterIsInstance<TileGridCell>().fastMap {
        AvailableTileGridCell(it.tile, isAvailable = false)
    } + otherTiles.fastMap { AvailableTileGridCell(it.tile) }
}

private fun MeasureScope.iconHorizontalCenter(containerSize: Int): Float {
    return (containerSize - ToggleTargetSize.roundToPx()) / 2f -
        CommonTileDefaults.TileStartPadding.toPx()
}

private fun Modifier.tileBackground(color: () -> Color): Modifier {
    // Clip tile contents from overflowing past the tile
    return clip(RoundedCornerShape(InactiveCornerRadius)).drawBehind { drawRect(color()) }
}

private object EditModeTileDefaults {
    const val PLACEHOLDER_ALPHA = .3f
    const val AUTO_SCROLL_DISTANCE = 100
    const val AUTO_SCROLL_SPEED = 2 // 2ms per pixel
    val CurrentTilesGridPadding = 10.dp
    val AvailableTilesGridMinHeight = 200.dp
    val GridBackgroundCornerRadius = 42.dp

    @Composable
    fun editTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect2,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )
}

private const val CURRENT_TILES_GRID_TEST_TAG = "CurrentTilesGrid"
private const val AVAILABLE_TILES_GRID_TEST_TAG = "AvailableTilesGrid"
