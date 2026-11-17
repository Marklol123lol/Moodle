package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.configureAppCheck
import com.example.data.ai.GeminiAiService
import com.example.data.firestore.FirestoreHistoryRepository
import com.example.data.local.MoodleSessionVault
import com.example.data.model.UiState
import com.example.data.moodle.MoodleClient
import com.example.ui.MoodleAiViewModel
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.auth.signOut
import com.example.ui.components.MoodleBrowserSubmissionDialog
import com.example.ui.components.MoodleLogoBadge
import com.example.ui.navigation.AssignmentDetailRoute
import com.example.ui.navigation.AssignmentsRoute
import com.example.ui.navigation.AssistantRoute
import com.example.ui.navigation.CoursesRoute
import com.example.ui.navigation.DashboardRoute
import com.example.ui.navigation.HistoryRoute
import com.example.ui.navigation.MoodleConnectRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.navigation.TopLevelDestination
import com.example.ui.screens.AssignmentDetailScreen
import com.example.ui.screens.AssignmentsScreen
import com.example.ui.screens.AssistantScreen
import com.example.ui.screens.CoursesScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MoodleConnectScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MoodleAiTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory Step 1: Configure App Check with launch intent before setContent/ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            MoodleAiTheme {
                AppRoot()
            }
        }
    }
}

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun AppRoot(auth: FirebaseAuth = Firebase.auth) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            delay(300)
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            MoodleAiAuthenticatedShell(
                currentUserId = user.uid,
                userEmail = user.email,
                onSignOut = {
                    signOut(credentialManager, scope)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleAiAuthenticatedShell(
    currentUserId: String,
    userEmail: String?,
    onSignOut: () -> Unit,
) {
    val viewModel: MoodleAiViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val vault = MoodleSessionVault(app)
                val client = MoodleClient(app)
                val ai = GeminiAiService()
                val repo = FirestoreHistoryRepository(app)
                MoodleAiViewModel(currentUserId, vault, client, ai, repo)
            }
        }
    )

    val workspaceState by viewModel.workspaceState.collectAsStateWithLifecycle()
    val coursesState by viewModel.coursesState.collectAsStateWithLifecycle()
    val assignmentsState by viewModel.assignmentsState.collectAsStateWithLifecycle()
    val agentLogsState by viewModel.agentLogsState.collectAsStateWithLifecycle()
    val chatMessagesState by viewModel.chatMessagesState.collectAsStateWithLifecycle()
    val moodleSession by viewModel.moodleSession.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val agentExecution by viewModel.agentExecution.collectAsStateWithLifecycle()
    val attachmentsUi by viewModel.attachmentsUi.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingAssistantText.collectAsStateWithLifecycle()
    val browserFallback by viewModel.submissionBrowserFallback.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    if (browserFallback != null) {
        MoodleBrowserSubmissionDialog(
            fallback = browserFallback!!,
            cookieHeader = moodleSession.cookieHeader,
            onDismiss = { viewModel.clearSubmissionBrowserFallback() }
        )
    }

    fun navigateTopLevel(dest: TopLevelDestination) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun isDestSelected(dest: TopLevelDestination): Boolean {
        val current = navBackStackEntry?.destination ?: return false
        return when (dest) {
            TopLevelDestination.DASHBOARD -> current.hasRoute<DashboardRoute>()
            TopLevelDestination.COURSES -> current.hasRoute<CoursesRoute>()
            TopLevelDestination.ASSIGNMENTS -> current.hasRoute<AssignmentsRoute>()
            TopLevelDestination.ASSISTANT -> current.hasRoute<AssistantRoute>()
            TopLevelDestination.HISTORY -> current.hasRoute<HistoryRoute>()
            TopLevelDestination.SETTINGS -> current.hasRoute<SettingsRoute>()
        }
    }

    val isFullScreenSubRoute = navBackStackEntry?.destination?.let {
        it.hasRoute<MoodleConnectRoute>() || it.hasRoute<AssignmentDetailRoute>()
    } == true

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 840.dp
        val isMediumWidth = maxWidth >= 600.dp && maxWidth < 840.dp

        val mainContent: @Composable (Modifier) -> Unit = { contentModifier ->
            Scaffold(
                modifier = contentModifier,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    if (!isFullScreenSubRoute && !isExpandedWidth) {
                        TopAppBar(
                            title = { MoodleLogoBadge() },
                            navigationIcon = {
                                if (!isMediumWidth) {
                                    IconButton(
                                        onClick = { scope.launch { drawerState.open() } },
                                        modifier = Modifier.testTag("open_drawer_button")
                                    ) {
                                        Icon(Icons.Rounded.Menu, contentDescription = "Открыть меню")
                                    }
                                }
                            },
                            actions = {
                                if (moodleSession.isConnected) {
                                    IconButton(
                                        onClick = { viewModel.syncMoodleCoursesAndAssignments() },
                                        modifier = Modifier.testTag("topbar_sync_button")
                                    ) {
                                        Icon(Icons.Rounded.Sync, contentDescription = "Синхронизировать Moodle")
                                    }
                                }
                                IconButton(
                                    onClick = { navigateTopLevel(TopLevelDestination.SETTINGS) },
                                    modifier = Modifier.testTag("topbar_settings_button")
                                ) {
                                    Icon(Icons.Rounded.Settings, contentDescription = "Настройки")
                                }
                            }
                        )
                    }
                },
                bottomBar = {
                    if (!isFullScreenSubRoute && !isMediumWidth && !isExpandedWidth) {
                        NavigationBar {
                            listOf(
                                TopLevelDestination.DASHBOARD,
                                TopLevelDestination.COURSES,
                                TopLevelDestination.ASSIGNMENTS,
                                TopLevelDestination.ASSISTANT,
                            ).forEach { dest ->
                                val selected = isDestSelected(dest)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label, maxLines = 1) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                MoodleNavHost(
                    navController = navController,
                    modifier = Modifier.padding(innerPadding),
                    viewModel = viewModel,
                    userEmail = userEmail,
                    workspaceState = workspaceState,
                    coursesState = coursesState,
                    assignmentsState = assignmentsState,
                    agentLogsState = agentLogsState,
                    chatMessagesState = chatMessagesState,
                    moodleSession = moodleSession,
                    syncStatus = syncStatus,
                    agentExecution = agentExecution,
                    attachmentsUi = attachmentsUi,
                    streamingText = streamingText,
                    onSignOut = onSignOut,
                )
            }
        }

        if (isExpandedWidth && !isFullScreenSubRoute) {
            PermanentNavigationDrawer(
                drawerContent = {
                    PermanentDrawerSheet(modifier = Modifier.width(260.dp)) {
                        Spacer(Modifier.height(16.dp))
                        MoodleLogoBadge(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Spacer(Modifier.height(12.dp))
                        TopLevelDestination.entries.forEach { dest ->
                            val selected = isDestSelected(dest)
                            NavigationDrawerItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .testTag("drawer_${dest.testTag}")
                            )
                        }
                    }
                }
            ) {
                mainContent(Modifier.fillMaxSize())
            }
        } else if (isMediumWidth && !isFullScreenSubRoute) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    Spacer(Modifier.height(12.dp))
                    TopLevelDestination.entries.forEach { dest ->
                        val selected = isDestSelected(dest)
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(dest) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label
                                )
                            },
                            label = { Text(dest.label, maxLines = 1) },
                            modifier = Modifier.testTag("rail_${dest.testTag}")
                        )
                    }
                }
                mainContent(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = !isFullScreenSubRoute,
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            MoodleLogoBadge()
                        }
                        TopLevelDestination.entries.forEach { dest ->
                            val selected = isDestSelected(dest)
                            NavigationDrawerItem(
                                selected = selected,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    navigateTopLevel(dest)
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .testTag("drawer_${dest.testTag}")
                            )
                        }
                    }
                }
            ) {
                mainContent(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun MoodleNavHost(
    navController: NavHostController,
    modifier: Modifier,
    viewModel: MoodleAiViewModel,
    userEmail: String?,
    workspaceState: UiState<com.example.data.model.UserWorkspace?>,
    coursesState: UiState<List<com.example.data.model.SyncedCourse>>,
    assignmentsState: UiState<List<com.example.data.model.AssignmentRecord>>,
    agentLogsState: UiState<List<com.example.data.model.AgentLogRecord>>,
    chatMessagesState: UiState<List<com.example.data.model.ChatMessageRecord>>,
    moodleSession: com.example.data.model.MoodleSessionCredentials,
    syncStatus: com.example.ui.SyncStatusUi,
    agentExecution: com.example.ui.AgentExecutionUi,
    attachmentsUi: com.example.ui.AssignmentAttachmentsUi,
    streamingText: String?,
    onSignOut: () -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = DashboardRoute,
        modifier = modifier
    ) {
        composable<DashboardRoute> {
            DashboardScreen(
                workspaceState = workspaceState,
                coursesState = coursesState,
                assignmentsState = assignmentsState,
                session = moodleSession,
                syncStatus = syncStatus,
                agentExecution = agentExecution,
                onOpenMoodleConnect = { navController.navigate(MoodleConnectRoute) },
                onSyncNow = { viewModel.syncMoodleCoursesAndAssignments() },
                onRunBatchAgent = { viewModel.runBatchAgentForAllPending() },
                onOpenAssistant = { navController.navigate(AssistantRoute) },
                onOpenAssignment = { assignId ->
                    navController.navigate(AssignmentDetailRoute(assignId))
                }
            )
        }

        composable<CoursesRoute> {
            CoursesScreen(
                coursesState = coursesState,
                session = moodleSession,
                syncStatus = syncStatus,
                onSyncNow = { viewModel.syncMoodleCoursesAndAssignments() },
                onOpenMoodleConnect = { navController.navigate(MoodleConnectRoute) },
                onOpenCourseAssignments = { courseId ->
                    navController.navigate(AssignmentsRoute(courseId = courseId))
                }
            )
        }

        composable<AssignmentsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AssignmentsRoute>()
            AssignmentsScreen(
                assignmentsState = assignmentsState,
                initialCourseId = route.courseId,
                session = moodleSession,
                agentExecution = agentExecution,
                onSyncNow = { viewModel.syncMoodleCoursesAndAssignments() },
                onOpenMoodleConnect = { navController.navigate(MoodleConnectRoute) },
                onRunBatchAgent = { viewModel.runBatchAgentForAllPending() },
                onOpenAssignment = { assignId ->
                    navController.navigate(AssignmentDetailRoute(assignId))
                }
            )
        }

        composable<AssistantRoute> {
            AssistantScreen(
                chatMessagesState = chatMessagesState,
                streamingText = streamingText,
                agentExecution = agentExecution,
                onSendMessage = { viewModel.sendAssistantMessage(it) },
                onClearChat = { viewModel.clearChat() }
            )
        }

        composable<HistoryRoute> {
            HistoryScreen(
                agentLogsState = agentLogsState,
                onOpenAssignment = { assignId ->
                    navController.navigate(AssignmentDetailRoute(assignId))
                }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                userEmail = userEmail,
                workspaceState = workspaceState,
                session = moodleSession,
                onOpenMoodleConnect = { navController.navigate(MoodleConnectRoute) },
                onDisconnectMoodle = { viewModel.disconnectMoodle() },
                onUpdateAiPreferences = { model, lang -> viewModel.updateAiSettings(model, lang) },
                onSignOut = onSignOut
            )
        }

        composable<MoodleConnectRoute> {
            MoodleConnectScreen(
                session = moodleSession,
                syncStatus = syncStatus,
                onConnectBrowserSession = { baseUrl, cookie, sesskey, name ->
                    viewModel.connectViaBrowserSession(baseUrl, cookie, sesskey, name)
                },
                onConnectWsCredentials = { baseUrl, user, pass ->
                    viewModel.connectViaWebServiceCredentials(baseUrl, user, pass)
                },
                onConnectWsToken = { baseUrl, token ->
                    viewModel.connectViaWebServiceToken(baseUrl, token)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<AssignmentDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AssignmentDetailRoute>()
            val allAssignments = (assignmentsState as? UiState.Success)?.data ?: emptyList()
            val assignment = allAssignments.firstOrNull { it.id == route.assignmentId }
            AssignmentDetailScreen(
                assignment = assignment,
                attachmentsUi = attachmentsUi,
                agentExecution = agentExecution,
                onLoadAttachments = { viewModel.loadAssignmentAttachments(it) },
                onRunAgent = { assign, imgs -> viewModel.runAgentForAssignment(assign, imgs) },
                onSaveDraft = { id, text -> viewModel.saveEditedAnswer(id, text) },
                onConfirmAndSubmit = { assign, finalAnswer ->
                    viewModel.confirmAndSubmitAssignment(assign, finalAnswer)
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
