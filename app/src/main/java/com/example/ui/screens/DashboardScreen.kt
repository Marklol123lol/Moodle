package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AssignmentTurnedIn
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AssignmentRecord
import com.example.data.model.AssignmentStatus
import com.example.data.model.MoodleSessionCredentials
import com.example.data.model.SyncedCourse
import com.example.data.model.UiState
import com.example.data.model.UserWorkspace
import com.example.ui.AgentExecutionUi
import com.example.ui.SyncStatusUi
import com.example.ui.components.AgentStepperCard
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.MoodleLogoBadge
import com.example.ui.components.StatMetricCard
import com.example.ui.components.StatusIndicatorPill
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusPending

@Composable
fun DashboardScreen(
    workspaceState: UiState<UserWorkspace?>,
    coursesState: UiState<List<SyncedCourse>>,
    assignmentsState: UiState<List<AssignmentRecord>>,
    session: MoodleSessionCredentials,
    syncStatus: SyncStatusUi,
    agentExecution: AgentExecutionUi,
    onOpenMoodleConnect: () -> Unit,
    onSyncNow: () -> Unit,
    onRunBatchAgent: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenAssignment: (String) -> Unit,
) {
    val workspace = (workspaceState as? UiState.Success)?.data
    val courses = (coursesState as? UiState.Success)?.data ?: emptyList()
    val assignments = (assignmentsState as? UiState.Success)?.data ?: emptyList()

    val activeAssignments = assignments.filter {
        it.status != AssignmentStatus.SUBMITTED.name && it.status != AssignmentStatus.CONFIRMED.name
    }
    val completedAssignments = assignments.filter {
        it.status == AssignmentStatus.SUBMITTED.name || it.status == AssignmentStatus.CONFIRMED.name
    }
    val upcomingDeadlines = activeAssignments.take(5)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_moodle_hero),
                    contentDescription = "Фон панели управления Moodle AI",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xE60B1220), Color(0x991E3A8A))
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MoodleLogoBadge()
                        StatusIndicatorPill(
                            label = if (session.isConnected) "Moodle: Подключено" else "Moodle: Не подключено",
                            isPositive = session.isConnected
                        )
                    }

                    Text(
                        text = if (session.isConnected) {
                            "Здравствуйте, ${session.moodleDisplayName.ifBlank { "ученик" }}!"
                        } else {
                            "Подключите ваш аккаунт moodle.1skola.lv"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!session.isConnected) {
                            Button(
                                onClick = onOpenMoodleConnect,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("dashboard_connect_moodle_button")
                            ) {
                                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Подключить Moodle")
                            }
                        } else {
                            Button(
                                onClick = onSyncNow,
                                enabled = syncStatus !is SyncStatusUi.Syncing,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("dashboard_sync_button")
                            ) {
                                if (syncStatus is SyncStatusUi.Syncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Обновить курсы")
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenAssistant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("dashboard_open_assistant_button")
                        ) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("AI-чат", color = Color.White)
                        }
                    }
                }
            }
        }

        // System Integration Status Cards (Moodle + Gemini API)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenMoodleConnect() }
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Статус Moodle",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StatusIndicatorPill(
                            label = session.mode.labelRu,
                            isPositive = session.isConnected
                        )
                        Text(
                            text = session.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Gemini API",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StatusIndicatorPill(
                            label = "Firebase AI Logic",
                            isPositive = true
                        )
                        Text(
                            text = workspace?.preferredModel ?: "gemini-3.8-flash",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (syncStatus is SyncStatusUi.Error) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = syncStatus.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        // KPI Metrics Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatMetricCard(
                    title = "Курсы",
                    value = courses.size.toString(),
                    subtitle = "Синхронизировано",
                    icon = Icons.Rounded.MenuBook,
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                StatMetricCard(
                    title = "Активные",
                    value = activeAssignments.size.toString(),
                    subtitle = "Ждут решения",
                    icon = Icons.Rounded.PendingActions,
                    accentColor = StatusPending,
                    modifier = Modifier.weight(1f)
                )
                StatMetricCard(
                    title = "Готово",
                    value = completedAssignments.size.toString(),
                    subtitle = "Сдано вами",
                    icon = Icons.Rounded.AssignmentTurnedIn,
                    accentColor = StatusConnected,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Autonomous Batch Agent Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI-агент подготовки заданий",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Найдёт новые задания, изучит PDF/DOCX/фото, подготовит ответы и остановится для вашего подтверждения перед отправкой.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onRunBatchAgent,
                        enabled = !agentExecution.isRunning && activeAssignments.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("dashboard_run_batch_agent_button")
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Запуск")
                    }
                }
            }
        }

        item {
            AgentStepperCard(state = agentExecution)
        }

        // Upcoming Deadlines Section
        item {
            Text(
                text = "Ближайшие дедлайны",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (upcomingDeadlines.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.CalendarMonth,
                    title = if (session.isConnected) "Активных дедлайнов нет" else "Нет данных из Moodle",
                    description = if (session.isConnected) {
                        "Нажмите «Обновить курсы», чтобы проверить новые задания на moodle.1skola.lv."
                    } else {
                        "Фиктивные данные не используются. Подключите ваш профиль moodle.1skola.lv, чтобы увидеть реальные курсы и сроки сдачи."
                    },
                    actionLabel = if (session.isConnected) "Синхронизировать" else "Подключить Moodle",
                    onAction = if (session.isConnected) onSyncNow else onOpenMoodleConnect
                )
            }
        } else {
            items(upcomingDeadlines, key = { it.id }) { assignment ->
                val statusEnum = AssignmentStatus.safeFrom(assignment.status)
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAssignment(assignment.id) }
                        .testTag("deadline_card_${assignment.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (statusEnum == AssignmentStatus.OVERDUE) StatusDanger.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CalendarMonth,
                                contentDescription = null,
                                tint = if (statusEnum == AssignmentStatus.OVERDUE) StatusDanger
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = assignment.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = assignment.courseTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Срок: ${assignment.dueDateText}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (statusEnum == AssignmentStatus.OVERDUE) StatusDanger
                                    else MaterialTheme.colorScheme.primary
                                )
                                StatusIndicatorPill(
                                    label = statusEnum.labelRu,
                                    isPositive = statusEnum == AssignmentStatus.DRAFT_READY,
                                    isWarning = statusEnum == AssignmentStatus.PENDING
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "Открыть задание"
                        )
                    }
                }
            }
        }
    }
}
