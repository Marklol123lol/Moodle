package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable
data object DashboardRoute

@Serializable
data class CoursesRoute(val filterCourseId: String? = null)

@Serializable
data class AssignmentsRoute(val courseId: String? = null)

@Serializable
data object AssistantRoute

@Serializable
data object HistoryRoute

@Serializable
data object SettingsRoute

@Serializable
data object MoodleConnectRoute

@Serializable
data class AssignmentDetailRoute(val assignmentId: String)

enum class TopLevelDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
) {
    DASHBOARD(
        route = DashboardRoute,
        label = "Dashboard",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        testTag = "nav_dashboard",
    ),
    COURSES(
        route = CoursesRoute(),
        label = "Мои курсы",
        selectedIcon = Icons.Filled.MenuBook,
        unselectedIcon = Icons.Outlined.MenuBook,
        testTag = "nav_courses",
    ),
    ASSIGNMENTS(
        route = AssignmentsRoute(),
        label = "Все задания",
        selectedIcon = Icons.Filled.Assignment,
        unselectedIcon = Icons.Outlined.Assignment,
        testTag = "nav_assignments",
    ),
    ASSISTANT(
        route = AssistantRoute,
        label = "AI Assistant",
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        testTag = "nav_assistant",
    ),
    HISTORY(
        route = HistoryRoute,
        label = "История работы",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_history",
    ),
    SETTINGS(
        route = SettingsRoute,
        label = "Настройки",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings",
    ),
}
