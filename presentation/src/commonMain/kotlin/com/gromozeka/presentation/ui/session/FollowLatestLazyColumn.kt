package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
internal fun <T> FollowLatestLazyColumn(
    items: List<T>,
    itemKey: (T) -> Any,
    unreadKey: (T) -> Any = itemKey,
    contentRevision: Any?,
    unreadLabel: (Int) -> String,
    focusKey: Any? = null,
    focusItemKey: (T) -> Any? = itemKey,
    onFocusConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var followingLatest by remember { mutableStateOf(true) }
    var hasUnreadContent by remember { mutableStateOf(false) }
    var unreadKeys by remember { mutableStateOf(emptySet<Any>()) }
    var previousItemKeys by remember { mutableStateOf<List<Any>?>(null) }
    var previousUnreadKeys by remember { mutableStateOf<Set<Any>?>(null) }
    var previousContentRevision by remember { mutableStateOf<Any?>(null) }
    val isAtBottom by remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount == 0 || !listState.canScrollForward
        }
    }

    LaunchedEffect(listState) {
        var previousPosition = listState.position()
        snapshotFlow {
            ScrollObservation(
                position = listState.position(),
                isAtBottom = listState.layoutInfo.totalItemsCount == 0 || !listState.canScrollForward,
                isScrolling = listState.isScrollInProgress,
            )
        }.collect { observation ->
            if (observation.isAtBottom) {
                followingLatest = true
                hasUnreadContent = false
                unreadKeys = emptySet()
            } else if (observation.isScrolling && observation.position < previousPosition) {
                followingLatest = false
            }
            previousPosition = observation.position
        }
    }

    val itemKeys = items.map(itemKey)
    val currentUnreadKeys = items.mapTo(linkedSetOf(), unreadKey)
    LaunchedEffect(itemKeys, contentRevision) {
        val previousItems = previousItemKeys
        val previousUnread = previousUnreadKeys
        val contentChanged = previousItems != null && (
            previousItems != itemKeys || previousContentRevision != contentRevision
        )
        val addedUnreadKeys = if (previousUnread == null) emptySet() else currentUnreadKeys - previousUnread

        previousItemKeys = itemKeys
        previousUnreadKeys = currentUnreadKeys
        previousContentRevision = contentRevision

        when {
            items.isEmpty() -> {
                followingLatest = true
                hasUnreadContent = false
                unreadKeys = emptySet()
            }

            followingLatest -> listState.scrollToLatest(items.size)
            contentChanged -> {
                hasUnreadContent = true
                unreadKeys = unreadKeys + addedUnreadKeys
            }
        }
    }

    LaunchedEffect(focusKey, itemKeys) {
        if (focusKey == null) return@LaunchedEffect
        val targetIndex = items.indexOfFirst { focusItemKey(it) == focusKey }
        if (targetIndex < 0) return@LaunchedEffect

        followingLatest = false
        hasUnreadContent = false
        unreadKeys = emptySet()
        listState.scrollToItem(targetIndex)
        onFocusConsumed()
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastIndex = layoutInfo.totalItemsCount - 1
            LayoutRevision(
                viewportWidth = layoutInfo.viewportSize.width,
                viewportHeight = layoutInfo.viewportSize.height,
                totalItemsCount = layoutInfo.totalItemsCount,
                lastItemSize = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex }?.size,
            )
        }
            .distinctUntilChanged()
            .drop(1)
            .collect { revision ->
                if (followingLatest && revision.totalItemsCount > 0) {
                    listState.scrollToLatest(revision.totalItemsCount)
                }
            }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiTestTag.MessageList.value),
            state = listState,
            contentPadding = contentPadding,
        ) {
            items(items = items, key = itemKey, itemContent = itemContent)
        }

        if (!isAtBottom) {
            ScrollToLatestButton(
                unreadCount = unreadKeys.size.takeIf { hasUnreadContent },
                label = unreadLabel(unreadKeys.size).takeIf { hasUnreadContent },
                onClick = {
                    followingLatest = true
                    hasUnreadContent = false
                    unreadKeys = emptySet()
                    coroutineScope.launch {
                        listState.scrollToLatest(items.size)
                    }
                },
            )
        }
    }
}

@Composable
private fun BoxScope.ScrollToLatestButton(
    unreadCount: Int?,
    label: String?,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(12.dp)
        .testTag(UiTestTag.UnreadMessagesButton.value)
    if (label == null) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to latest")
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to latest")
                Spacer(Modifier.width(6.dp))
                Text(if (requireNotNull(unreadCount) > 0) "$unreadCount $label" else label)
            }
        }
    }
}

private suspend fun LazyListState.scrollToLatest(itemCount: Int) {
    if (itemCount <= 0) return
    scrollToItem(itemCount - 1)
    if (canScrollForward) {
        scrollBy(Int.MAX_VALUE.toFloat())
    }
}

private fun LazyListState.position(): ScrollPosition = ScrollPosition(
    itemIndex = firstVisibleItemIndex,
    itemOffset = firstVisibleItemScrollOffset,
)

private data class ScrollPosition(
    val itemIndex: Int,
    val itemOffset: Int,
) : Comparable<ScrollPosition> {
    override fun compareTo(other: ScrollPosition): Int =
        compareValuesBy(this, other, ScrollPosition::itemIndex, ScrollPosition::itemOffset)
}

private data class ScrollObservation(
    val position: ScrollPosition,
    val isAtBottom: Boolean,
    val isScrolling: Boolean,
)

private data class LayoutRevision(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val totalItemsCount: Int,
    val lastItemSize: Int?,
)
