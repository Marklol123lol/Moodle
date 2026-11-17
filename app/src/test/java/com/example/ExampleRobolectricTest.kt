package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.example.data.model.AgentRunStatus
import com.example.data.model.AssignmentStatus
import com.example.data.model.MoodleConnectionMode
import com.example.data.model.MoodleSessionCredentials
import com.example.data.model.UiState
import com.example.data.moodle.MoodleClient
import com.example.ui.AgentExecutionUi
import com.example.ui.SyncStatusUi
import com.example.ui.auth.AuthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.MoodleAiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Moodle AI", appName)
    }

    @Test
    fun `main activity launches without crashing`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `auth screen renders sign in button and branding`() {
        composeRule.setContent {
            MoodleAiTheme {
                AuthScreen(onAuthSuccess = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("google_sign_in_button")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `dashboard screen renders connect moodle action when disconnected`() {
        composeRule.setContent {
            MoodleAiTheme {
                DashboardScreen(
                    workspaceState = UiState.Success(null),
                    coursesState = UiState.Success(emptyList()),
                    assignmentsState = UiState.Success(emptyList()),
                    session = MoodleSessionCredentials(),
                    syncStatus = SyncStatusUi.Idle,
                    agentExecution = AgentExecutionUi(),
                    onOpenMoodleConnect = {},
                    onSyncNow = {},
                    onRunBatchAgent = {},
                    onOpenAssistant = {},
                    onOpenAssignment = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_connect_moodle_button").assertIsDisplayed()
    }

    @Test
    fun `moodle client extracts sesskey from json config and hidden input`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = MoodleClient(context)

        val jsonHtml = """<script>M.cfg = {"wwwroot":"https://moodle.1skola.lv","sesskey":"AbCd1234Ef"};</script>"""
        assertEquals("AbCd1234Ef", client.extractSesskey(jsonHtml))

        val inputHtml = """<form><input type="hidden" name="sesskey" value="XyZ987654" /></form>"""
        assertEquals("XyZ987654", client.extractSesskey(inputHtml))
    }

    @Test
    fun `safe enum parsers handle unknown values without throwing`() {
        assertEquals(AssignmentStatus.PENDING, AssignmentStatus.safeFrom("UNKNOWN_STATE"))
        assertEquals(MoodleConnectionMode.DISCONNECTED, MoodleConnectionMode.safeFrom("OLD_MODE"))
        assertEquals(AgentRunStatus.RUNNING, AgentRunStatus.safeFrom("BAD"))
    }
}
