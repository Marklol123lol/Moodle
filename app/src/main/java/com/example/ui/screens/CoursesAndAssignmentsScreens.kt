package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AssignmentRecord
import com.example.data.model.AssignmentStatus
import com.example.data.model.MoodleSessionCredentials
import com.example.data.model.SyncedCourse
import com.example.data.model.UiState
import com.example.ui.AgentExecutionUi
import com.example.ui.SyncStatusUi
import com.example.ui.components.AgentStepperCard
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusIndicatorPill
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusPending

@Composable
fun CoursesScreen(
    coursesState: UiState<List<SyncedCourse>>,
    session: MoodleSessionCredentials,
    syncStatus: SyncStatusUi,
    onSyncNow: () -> Unit,
    onOpenMoodleConnect: () -> Unit,
    onOpenCourseAssignments: (String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val courses = (coursesState as? UiState.Success)?.data ?: emptyList()
    val filtered = remember(courses, searchQuery) {
        if (searchQuery.isBlank()) courses
        else courses.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.shortName.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Мои курсы",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Синхронизировано с ${session.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (session.isConnected) {
                    Button(
                        onClick = onSyncNow,
                        enabled = syncStatus !is SyncStatusUi.Syncing,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("courses_sync_button")
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Обновить")
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск по курсам...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить поиск")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("courses_search_input")
            )
        }

        if (coursesState is UiState.Error) {
            item {
                Text(
                    text = coursesState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.MenuBook,
                    title = if (session.isConnected) "Курсы не найдены" else "Moodle не подключён",
                    description = if (session.isConnected) {
                        "Попробуйте обновить список курсов или измените поисковый запрос."
                    } else {
                        "Войдите в свой аккаунт на moodle.1skola.lv, чтобы загрузить ваши реальные школьные курсы."
                    },
                    actionLabel = if (session.isConnected) "Синхронизировать" else "Подключить Moodle",
                    onAction = if (session.isConnected) onSyncNow else onOpenMoodleConnect
                )
            }
        } else {
            items(filtered, key = { it.id }) { course ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCourseAssignments(course.moodleCourseId) }
                        .testTag("course_card_${course.moodleCourseId}")
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MenuBook,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = course.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (course.shortName.isNotBlank() || course.category.isNotBlank()) {
                                        Text(
                                            text = listOf(course.shortName, course.category)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Icon(Icons.Rounded.ChevronRight, contentDescription = "Открыть задания курса")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatusIndicatorPill(
                                label = "Заданий: ${course.assignmentCount}",
                                isPositive = course.assignmentCount > 0,
                                isWarning = course.assignmentCount == 0L
                            )
                            Text(
                                text = "Прогресс: ${course.progressPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (course.progressPercent > 0L) {
                            LinearProgressIndicator(
                                progress = { (course.progressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AssignmentsScreen(
    assignmentsState: UiState<List<AssignmentRecord>>,
    initialCourseId: String?,
    session: MoodleSessionCredentials,
    agentExecution: AgentExecutionUi,
    onSyncNow: () -> Unit,
    onOpenMoodleConnect: () -> Unit,
    onRunBatchAgent: () -> Unit,
    onOpenAssignment: (String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf<AssignmentStatus?>(null) }
    var activeCourseFilter by remember(initialCourseId) { mutableStateOf(initialCourseId) }

    val assignments = (assignmentsState as? UiState.Success)?.data ?: emptyList()
    val filtered = remember(assignments, searchQuery, selectedStatus, activeCourseFilter) {
        assignments.filter { item ->
            val matchesCourse = activeCourseFilter == null || item.moodleCourseId == activeCourseFilter
            val matchesStatus = selectedStatus == null || item.status == selectedStatus?.name
            val matchesQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.courseTitle.contains(searchQuery, ignoreCase = true) ||
                item.subjectCategory.contains(searchQuery, ignoreCase = true)
            matchesCourse && matchesStatus && matchesQuery
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Все задания",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Всего: ${assignments.size} • Показано: ${filtered.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onRunBatchAgent,
                    enabled = !agentExecution.isRunning && assignments.any {
                        it.status == AssignmentStatus.PENDING.name || it.status == AssignmentStatus.OVERDUE.name
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("assignments_batch_ai_button")
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Решить AI")
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск заданий или предметов...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("assignments_search_input")
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeCourseFilter != null) {
                    FilterChip(
                        selected = true,
                        onClick = { activeCourseFilter = null },
                        label = { Text("Фильтр по курсу ✕") }
                    )
                }
                FilterChip(
                    selected = selectedStatus == null,
                    onClick = { selectedStatus = null },
                    label = { Text("Все") }
                )
                AssignmentStatus.entries.forEach { st ->
                    FilterChip(
                        selected = selectedStatus == st,
                        onClick = { selectedStatus = if (selectedStatus == st) null else st },
                        label = { Text(st.labelRu) }
                    )
                }
            }
        }

        item {
            AgentStepperCard(state = agentExecution)
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.Assignment,
                    title = if (session.isConnected) "Задания не найдены" else "Нет синхронизированных заданий",
                    description = if (session.isConnected) {
                        "В выбранных курсах пока нет заданий под текущим фильтром."
                    } else {
                        "Подключите Moodle (moodle.1skola.lv), чтобы загрузить задания, материалы и дедлайны."
                    },
                    actionLabel = if (session.isConnected) "Обновить из Moodle" else "Подключить Moodle",
                    onAction = if (session.isConnected) onSyncNow else onOpenMoodleConnect
                )
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                val statusEnum = AssignmentStatus.safeFrom(item.status)
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAssignment(item.id) }
                        .testTag("assignment_card_${item.id}")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.courseTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            StatusIndicatorPill(
                                label = statusEnum.labelRu,
                                isPositive = statusEnum == AssignmentStatus.SUBMITTED || statusEnum == AssignmentStatus.DRAFT_READY,
                                isWarning = statusEnum == AssignmentStatus.PENDING
                            )
                        }

                        if (item.description.isNotBlank()) {
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Срок: ${item.dueDateText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (statusEnum == AssignmentStatus.OVERDUE) StatusDanger else StatusConnected
                            )
                            if (item.subjectCategory.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = item.subjectCategory,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
