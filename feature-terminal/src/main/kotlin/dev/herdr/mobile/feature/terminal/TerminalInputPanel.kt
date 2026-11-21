package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.model.InputPanelPage
import dev.herdr.mobile.core.model.SwipeBehavior
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun BottomInputPanel(
    viewModel: TerminalViewModel,
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onExtraKey: (TerminalKeyEncoder.Key) -> Unit,
    onPageChange: (InputPanelPage) -> Unit,
    cjkFocus: androidx.compose.ui.focus.FocusRequester,
    onCjkFocusChanged: (Boolean) -> Unit = {},
) {
    val settings = state.settings
    val pages = buildList {
        if (settings.extraKeysEnabled) add(InputPanelPage.EXTRA_KEYS)
        if (settings.cjkInputEnabled) add(InputPanelPage.CJK_INPUT)
    }
    if (pages.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val pagerScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = pages.indexOf(state.inputPage).coerceAtLeast(0),
        pageCount = { pages.size },
    )
    // No page indicator: the spec allows one but the user finds the dots
    // noise (current page is obvious from the panel itself). Pager keeps
    // working via horizontal swipes in both directions.
    // When a panel is disabled in Settings, pages shrinks: clamp the pager or
    // pages[currentPage] throws IndexOutOfBounds on a stale page 1.
    LaunchedEffect(pages.size) {
        if (pagerState.currentPage >= pages.size) {
            pagerState.scrollToPage(0)
        }
    }
    // External page changes (the keyboard-button fallback switches the CJK page
    // to the shortcut panel) must move the pager too; it otherwise stayed on a
    // page the state no longer describes.
    LaunchedEffect(state.inputPage, pages.size) {
        val index = pages.indexOf(state.inputPage)
        if (index >= 0 && pagerState.currentPage != index) {
            pagerState.scrollToPage(index)
        }
    }
    // Switching pages never touches keyboard visibility: no auto-focus of the
    // CJK field (only the keyboard button summons the IME) and no dismiss.
    LaunchedEffect(pagerState.currentPage, pages.size) {
        val page = pages.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        onPageChange(page)
    }
    Surface(
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (pages.size > 1 && settings.swipeBehavior != SwipeBehavior.PAGE) {
                // With swipe disabled there is no other page switch. Keep the
                // control scoped to that case so the normal PAGE flow stays
                // swipe-only and the panel keeps its uncluttered height.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    pages.forEach { page ->
                        val selected = pagerState.currentPage == pages.indexOf(page)
                        TextButton(
                            onClick = {
                                pagerScope.launch {
                                    pagerState.animateScrollToPage(pages.indexOf(page))
                                }
                            },
                            modifier = Modifier.semantics {
                                // Colour alone does not convey the active page
                                // to TalkBack; expose the selection state.
                                this.selected = selected
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                        ) {
                            Text(
                                text = when (page) {
                                    InputPanelPage.EXTRA_KEYS -> "Keys"
                                    InputPanelPage.CJK_INPUT -> "Input"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            if (pages.size > 1) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    userScrollEnabled = settings.swipeBehavior == SwipeBehavior.PAGE,
                ) { index ->
                    // The keys are Buttons: their touch targets swallow
                    // horizontal drags, so a swipe that starts on a key never
                    // reaches the pager. Claim horizontal drags in the Initial
                    // pass (taps have no slop and still reach the buttons) and
                    // drive the pager ourselves. This keeps panel switching
                    // swipe-only, with no extra on-screen control.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(pages.size, settings.swipeBehavior) {
                                if (settings.swipeBehavior != SwipeBehavior.PAGE) {
                                    return@pointerInput
                                }
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    var totalX = 0f
                                    var totalY = 0f
                                    var claimed = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes
                                            .firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        if (change.isConsumed) {
                                            // A vertical key detector already
                                            // owns this gesture.
                                            return@awaitEachGesture
                                        }
                                        val dx = change.position.x - change.previousPosition.x
                                        val dy = change.position.y - change.previousPosition.y
                                        if (!claimed) {
                                            totalX += dx
                                            totalY += dy
                                            // Wait for real travel (2x slop)
                                            // before deciding: a one-pixel
                                            // horizontal lead at slop used to
                                            // lock the pager through a later
                                            // deep downward flick.
                                            when (resolveDragAxis(
                                                totalX,
                                                totalY,
                                                viewConfiguration.touchSlop * 2f,
                                            )) {
                                                DragAxis.HORIZONTAL -> claimed = true
                                                DragAxis.VERTICAL ->
                                                    // Vertical gesture belongs to
                                                    // the extra-key flick.
                                                    return@awaitEachGesture
                                                DragAxis.NONE -> Unit
                                            }
                                        }
                                        if (claimed) change.consume()
                                    }
                                    if (claimed) {
                                        val target = (pagerState.currentPage +
                                            if (totalX < 0) 1 else -1)
                                            .coerceIn(0, pagerState.pageCount - 1)
                                        pagerScope.launch {
                                            pagerState.animateScrollToPage(target)
                                        }
                                    }
                                }
                            },
                    ) {
                    when (pages[index]) {
                        InputPanelPage.EXTRA_KEYS -> ExtraKeysPanel(
                            onKey = onExtraKey,
                            armed = state.stickyModifiers,
                            haptic = settings.hapticFeedback,
                        )

                        InputPanelPage.CJK_INPUT -> {
                            val tabId = state.target?.tabId
                            val draft by viewModel.cjkDraft(tabId).collectAsStateWithLifecycle()
                            CjkInputPanel(
                                text = draft,
                                onTextChange = { viewModel.setCjkDraft(tabId, it) },
                                onSend = {
                                    onSendText(it)
                                    viewModel.clearCjkDraft(tabId)
                                },
                                onBackspace = { onSendBytes(byteArrayOf(0x7F)) },
                                focusRequester = cjkFocus,
                                onFocusChanged = onCjkFocusChanged,
                            )
                        }
                    }
                    }
                }
            } else {
                when (pages.first()) {
                    InputPanelPage.EXTRA_KEYS -> ExtraKeysPanel(
                        onKey = onExtraKey,
                        armed = state.stickyModifiers,
                        haptic = settings.hapticFeedback,
                    )

                    InputPanelPage.CJK_INPUT -> {
                        val tabId = state.target?.tabId
                        val draft by viewModel.cjkDraft(tabId).collectAsStateWithLifecycle()
                        CjkInputPanel(
                            text = draft,
                            onTextChange = { viewModel.setCjkDraft(tabId, it) },
                            onSend = {
                                onSendText(it)
                                viewModel.clearCjkDraft(tabId)
                            },
                            onBackspace = { onSendBytes(byteArrayOf(0x7F)) },
                            focusRequester = cjkFocus,
                            onFocusChanged = onCjkFocusChanged,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Grid for a view size + text size, computed EXACTLY the way Termux's own
 * updateSize does (monospace "X" advance, ceiled font spacing): the attach
 * geometry (and the daemon's prelude blank-fill) must match the view's grid
 * or history banking falls short. Same 20x5 clamp as the old view.
 */
internal fun gridFor(
    textSizePx: Int,
    widthPx: Int,
    heightPx: Int,
    familyName: String = "monospace",
): Pair<Int, Int> {
    if (textSizePx <= 0 || widthPx <= 0 || heightPx <= 0) return 80 to 24
    val paint = android.graphics.Paint().apply {
        textSize = textSizePx.toFloat()
        typeface = android.graphics.Typeface.create(familyName, android.graphics.Typeface.NORMAL)
    }
    val cellWidth = paint.measureText("X").toInt().coerceAtLeast(1)
    val cellHeight = kotlin.math.ceil(paint.fontSpacing).toInt().coerceAtLeast(1)
    return maxOf(20, widthPx / cellWidth) to
        maxOf(5, heightPx / cellHeight)
}
