package com.example.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AgentLogRecord
import com.example.data.model.AgentRunStatus
import com.example.data.model.ChatMessageRecord
import com.example.data.model.MoodleSessionCredentials
import com.example.data.model.UiState
import com.example.data.model.UserWorkspace
import com.example.ui.AgentExecutionUi
import com.example.ui.components.AgentStepperCard
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusIndicatorPill
import java.text.SimpleDateFormat
import java.util.Locale

private val QUICK_ASSISTANT_PROMPTS = listOf(
    "Найди все мои невыполненные задания",
    "Покажи задания с ближайшим дедлайном",
    "Выполни задание по английскому",
    "Посмотри, что нужно сделать по математике",
    "Объясни мне эту тему простым языком",
    "Подготовь ответы на все доступные упражнения",
)

@Composable
fun AssistantScreen(
    chatMessagesState: UiState<List<ChatMessageRecord>>,
    streamingText: String?,
    agentExecution: AgentExecutionUi,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val messages = (chatMessagesState as? UiState.Success)?.data ?: emptyList()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AI Assistant",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Gemini работает с вашими курсами moodle.1skola.lv",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = onClearChat, modifier = Modifier.testTag("clear_chat_button")) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Очистить чат")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QUICK_ASSISTANT_PROMPTS.forEach { prompt ->
                AssistChip(
                    onClick = { onSendMessage(prompt) },
                    label = { Text(prompt, maxLines = 1) }
                )
            }
        }

        AgentStepperCard(
            state = agentExecution,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty() && streamingText == null) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "Спросите AI-помощника",
                        description = "Задайте вопрос по любому предмету (математика, английский, латышский, история, биология) или выберите команду выше — ИИ найдёт нужное задание и поможет с решением."
                    )
                }
            }

            items(messages, key = { it.id }) { msg ->
                ChatBubble(isUser = msg.role == "user", text = msg.text)
            }

            if (streamingText != null) {
                item {
                    ChatBubble(
                        isUser = false,
                        text = streamingText.ifBlank { "Думаю над вашим запросом..." }
                    )
                }
            }
        }

        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Напишите команду или вопрос...") },
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("assistant_chat_input")
                )
                Button(
                    onClick = {
                        val toSend = inputText
                        inputText = ""
                        onSendMessage(toSend)
                    },
                    enabled = inputText.isNotBlank() && streamingText == null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("assistant_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить сообщение")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(isUser: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isUser) 0.dp else 2.dp,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(14.dp)
            )
        }
    }
}

@Composable
fun HistoryScreen(
    agentLogsState: UiState<List<AgentLogRecord>>,
    onOpenAssignment: (String) -> Unit,
) {
    val logs = (agentLogsState as? UiState.Success)?.data ?: emptyList()
    val dateFmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "История работы",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Журнал всех запусков AI-агента, подготовленных черновиков и отправок",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (logs.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.History,
                    title = "История пока пуста",
                    description = "Здесь будут сохраняться все действия AI-агента, синхронизации Moodle и подтверждённые отправки."
                )
            }
        } else {
            items(logs, key = { it.id }) { log ->
                val statusEnum = AgentRunStatus.safeFrom(log.status)
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    onClick = {
                        if (log.assignmentId.isNotBlank()) onOpenAssignment(log.assignmentId)
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = log.assignmentTitle.ifBlank { log.actionType },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            StatusIndicatorPill(
                                label = statusEnum.labelRu,
                                isPositive = statusEnum == AgentRunStatus.SUBMITTED || statusEnum == AgentRunStatus.COMPLETED,
                                isWarning = statusEnum == AgentRunStatus.AWAITING_CONFIRMATION || statusEnum == AgentRunStatus.RUNNING
                            )
                        }
                        if (log.courseTitle.isNotBlank()) {
                            Text(
                                text = log.courseTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = log.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (log.steps.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                log.steps.forEach { step ->
                                    Text(
                                        text = "• $step",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        val timeText = log.updatedAt?.toDate()?.let { dateFmt.format(it) }.orEmpty()
                        if (timeText.isNotBlank()) {
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    userEmail: String?,
    workspaceState: UiState<UserWorkspace?>,
    session: MoodleSessionCredentials,
    onOpenMoodleConnect: () -> Unit,
    onDisconnectMoodle: () -> Unit,
    onUpdateAiPreferences: (model: String, language: String) -> Unit,
    onSignOut: () -> Unit,
) {
    val ws = (workspaceState as? UiState.Success)?.data
    var selectedModel by remember(ws?.preferredModel) {
        mutableStateOf(ws?.preferredModel ?: "gemini-3.8-flash")
    }
    var selectedLang by remember(ws?.answerLanguage) {
        mutableStateOf(ws?.answerLanguage ?: "Русский")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Google Account
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Person, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Аккаунт Google", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = userEmail ?: "Авторизованный пользователь",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onSignOut,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Выйти из аккаунта")
                    }
                }
            }
        }

        // Moodle Connection
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Подключение Moodle", style = MaterialTheme.typography.titleMedium)
                        StatusIndicatorPill(
                            label = session.mode.labelRu,
                            isPositive = session.isConnected
                        )
                    }
                    Text(
                        text = "Сервер: ${session.baseUrl}\nПользователь: ${session.moodleDisplayName.ifBlank { "Не авторизован" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onOpenMoodleConnect,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("settings_connect_moodle_button")
                        ) {
                            Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (session.isConnected) "Переподключить" else "Подключить")
                        }
                        if (session.isConnected) {
                            OutlinedButton(
                                onClick = onDisconnectMoodle,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("settings_disconnect_moodle_button")
                            ) {
                                Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Отключить")
                            }
                        }
                    }
                }
            }
        }

        // Gemini AI Model Preferences
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Настройки Gemini AI", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Модель по умолчанию:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon").forEach { model ->
                            FilterChip(
                                selected = selectedModel == model,
                                onClick = { selectedModel = model },
                                label = { Text(model) }
                            )
                        }
                    }

                    Text(
                        text = "Язык объяснений:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Русский", "Latviešu", "English").forEach { lang ->
                            FilterChip(
                                selected = selectedLang == lang,
                                onClick = { selectedLang = lang },
                                label = { Text(lang) }
                            )
                        }
                    }

                    Button(
                        onClick = { onUpdateAiPreferences(selectedModel, selectedLang) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("save_ai_settings_button")
                    ) {
                        Text("Сохранить параметры AI")
                    }
                }
            }
        }
    }
}
