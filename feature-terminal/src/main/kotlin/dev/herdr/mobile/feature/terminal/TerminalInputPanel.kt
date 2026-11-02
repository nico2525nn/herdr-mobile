package dev.herdr.mobile.feature.terminal

import android.util.Log
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.StatusChip
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.terminal.view.ImeTrigger
import dev.herdr.mobile.terminal.view.imeCommand
import dev.herdr.mobile.core.model.InputPanelPage
import com.termux.view.TerminalView
import dev.herdr.mobile.terminal.view.BackendState
import dev.herdr.mobile.terminal.view.RemoteTermuxSession
import dev.herdr.mobile.terminal.view.TermuxSchemes
import dev.herdr.mobile.terminal.view.TermuxTerminalHost
import dev.herdr.mobile.terminal.view.TermuxViewClient
import dev.herdr.mobile.terminal.view.installBoxDrawingOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
internal fun BottomInputPanel(
    viewModel: TerminalViewModel,
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onExtraKey: (dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Key) -> Unit,
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
