package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.model.InputPanelPage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun BottomInputPanel(
    viewModel: TerminalViewModel,
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onExtraKey: (TerminalKeyEncoder.Key) -> Unit,
    onPageChange: (InputPanelPage) -> Unit,
    cjkFocus: androidx.compose.ui.focus.FocusRequester,
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
            if (pages.size > 1) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { index ->
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
                            )
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
