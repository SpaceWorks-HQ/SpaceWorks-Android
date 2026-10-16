package org.spaceworks.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
class ShellTest {
    @get:Rule val compose=createComposeRule()
    @Test fun connectAndNavigateEmptyShell() {
        val holder=ConnectionStateHolder("",false,true,{}, {})
        compose.setContent {SpaceWorksApp(holder)}
        compose.onNodeWithText("Connect to your Space Works server").assertIsDisplayed()
        compose.onNodeWithTag("serverUrl").performTextInput("https://example.org")
        compose.onNodeWithText("Connect",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Sign-in will be available in the next phase.").assertIsDisplayed()
        listOf("Inventory","Activity","Inbox","Profile").forEach {tab ->
            compose.onNodeWithText(tab).performClick()
            compose.onNodeWithText("Nothing here yet.").assertIsDisplayed()
        }
        compose.onNodeWithText("Staff").assertDoesNotExist()
        compose.onNodeWithText("Dark mode").performClick()
        compose.onNodeWithText("Light mode").assertIsDisplayed()
    }
}
