package dev.herdr.mobile.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.herdr.mobile.core.designsystem.HerdrMobileTheme
import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.ThemeMode
import dev.herdr.mobile.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Home workspace list as composed, with a fixed snapshot and no session.
 *
 * The daemon, the client and the view model are deliberately absent: what is
 * asserted here is the contract between the list and its callers — the labels
 * the user reads, and the ids each tap/long-press routes to.
 */
class HomeWorkspaceListUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun pane(workspaceId: String, tabId: String) =
        Pane(id = "$workspaceId:$tabId", tabId = tabId, workspaceId = workspaceId)

    private fun tab(workspaceId: String, id: String, label: String, status: AgentStatus) =
        Tab(
            id = id,
            workspaceId = workspaceId,
            label = label,
            status = status,
            panes = listOf(pane(workspaceId, id)),
        )

    /** Two workspaces, four tabs: enough for counts, ordering and chip labels. */
    private fun state() = HomeUiState(
        connection = ConnectionState.CONNECTED,
        snapshot = SessionSnapshot(
            revision = 1,
            seq = 1,
            workspaces = listOf(
                Workspace(
                    id = "w1",
                    label = "api",
                    path = "/srv/api",
                    activeTabId = "t1",
                    status = AgentStatus.WORKING,
                    tabs = listOf(
                        tab("w1", "t1", "server", AgentStatus.WORKING),
                        tab("w1", "t2", "tests", AgentStatus.IDLE),
                        Tab(id = "t3", workspaceId = "w1", number = 3),
                    ),
                ),
                Workspace(
                    id = "w2",
                    label = "docs",
                    activeTabId = "t4",
                    tabs = listOf(tab("w2", "t4", "site", AgentStatus.DONE)),
                ),
            ),
        ),
    )

    private fun render(
        state: HomeUiState = state(),
        onOpenWorkspace: (workspaceId: String, tabId: String?) -> Unit = { _, _ -> },
        onOpenTab: (workspaceId: String, tabId: String) -> Unit = { _, _ -> },
        onWsMenu: (HomeWsMenuTarget) -> Unit = {},
    ) {
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                Column(Modifier.fillMaxSize()) {
                    HomeHeader(
                        workspaceCount = state.workspaceCount,
                        tabCount = state.tabCount,
                        stale = state.stale,
                        connection = state.connection,
                        error = state.error,
                    )
                    Box(Modifier.weight(1f)) {
                        WorkspaceList(
                            state = state,
                            onMoveWorkspace = { _, _ -> },
                            onOpenWorkspace = onOpenWorkspace,
                            onOpenTab = onOpenTab,
                            screenOrigin = Offset.Zero,
                            onTabMenu = {},
                            onWsMenu = onWsMenu,
                        )
                    }
                }
            }
        }
    }

    /** The tappable card header: it is the only node with the label *and* a long-click. */
    private fun workspaceHeader(label: String) =
        compose.onNode(
            hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
        )

    @Test
    fun workspaceLabelTabCountAndChipsAreShown() {
        render()

        // Header totals are the "tab count" the user reads.
        compose.onNodeWithText("2 workspaces · 4 active tabs").assertIsDisplayed()

        workspaceHeader("api").assertIsDisplayed()
        compose.onNodeWithText("/srv/api").assertIsDisplayed()
        workspaceHeader("docs").assertIsDisplayed()

        // Every tab of the workspace is a chip; an unlabelled tab falls back to its number.
        compose.onNode(hasText("server") and hasStateDescription("Working")).assertIsDisplayed()
        compose.onNode(hasText("tests") and hasStateDescription("Idle")).assertIsDisplayed()
        compose.onNode(hasText("#3") and hasStateDescription("Status unknown")).assertIsDisplayed()
        compose.onNode(hasText("site") and hasStateDescription("Done, new results")).assertIsDisplayed()
    }

    @Test
    fun tappingAWorkspaceOpensItsActiveTab() {
        val opened = mutableListOf<Pair<String, String?>>()
        render(onOpenWorkspace = { workspaceId, tabId -> opened += workspaceId to tabId })

        workspaceHeader("api").performClick()

        assertEquals(listOf("w1" to "t1"), opened)
    }

    @Test
    fun tappingATabChipOpensThatTabOnly() {
        val opened = mutableListOf<Pair<String, String>>()
        render(onOpenTab = { workspaceId, tabId -> opened += workspaceId to tabId })

        compose.onNode(hasText("tests") and hasStateDescription("Idle")).performClick()

        assertEquals(listOf("w1" to "t2"), opened)
    }

    @Test
    fun longPressingAWorkspaceTargetsItsMenu() {
        val menus = mutableListOf<HomeWsMenuTarget>()
        render(onWsMenu = { menus += it })

        workspaceHeader("api")
            .performSemanticsAction(SemanticsActions.OnLongClick)

        assertEquals(1, menus.size)
        assertEquals("w1", menus.single().workspaceId)
        assertEquals("api", menus.single().label)
    }
}
