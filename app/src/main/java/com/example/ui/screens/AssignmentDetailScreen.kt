package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AssignmentRecord
import com.example.data.model.AssignmentStatus
import com.example.ui.AgentExecutionUi
import com.example.ui.AssignmentAttachmentsUi
import com.example.ui.components.AgentStepperCard
import com.example.ui.components.StatusIndicatorPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentDetailScreen(
    assignment: AssignmentRecord?,
    attachmentsUi: AssignmentAttachmentsUi,
    agentExecution: AgentExecutionUi,
    onLoadAttachments: (AssignmentRecord) -> Unit,
    onRunAgent: (AssignmentRecord, List<Bitmap>) -> Unit,
    onSaveDraft: (String, String) -> Unit,
    onConfirmAndSubmit: (AssignmentRecord, String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var copiedNotice by remember { mutableStateOf(false) }
    val extraBitmaps = remember { mutableStateListOf<Bitmap>() }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var userConfirmedCheckbox by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bmp ->
                        extraBitmaps.add(bmp)
                    }
                }
            }
        }
    }

    LaunchedEffect(assignment?.id) {
        if (assignment != null) {
            onLoadAttachments(assignment)
        }
    }

    var editableAnswer by remember(assignment?.id, assignment?.userEditedAnswer, assignment?.aiSolution) {
        mutableStateOf(
            assignment?.userEditedAnswer?.ifBlank { assignment.aiSolution }.orEmpty()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(assignment?.courseTitle ?: "Задание", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (assignment == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Задание не найдено.")
            }
            return@Scaffold
        }

        val statusEnum = AssignmentStatus.safeFrom(assignment.status)
        val isSubmitted = statusEnum == AssignmentStatus.SUBMITTED

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusIndicatorPill(
                                label = statusEnum.labelRu,
                                isPositive = statusEnum == AssignmentStatus.SUBMITTED || statusEnum == AssignmentStatus.DRAFT_READY,
                                isWarning = statusEnum == AssignmentStatus.PENDING
                            )
                            Text(
                                text = "Срок: ${assignment.dueDateText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = assignment.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = assignment.description.ifBlank {
                                "Подробное текстовое описание не найдено на странице Moodle. Нажмите «Решить с AI-агентом» для проверки прикреплённых файлов или добавьте фото задания."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Attachments & Photo Picker
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Материалы (PDF, DOCX, фото)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("attach_extra_photo_button")
                            ) {
                                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Фото")
                            }
                        }

                        if (attachmentsUi.isLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Чтение вложений из Moodle...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (attachmentsUi.attachments.isEmpty() && extraBitmaps.isEmpty()) {
                            Text(
                                text = "Вложений в Moodle не обнаружено. Вы можете прикрепить фото тетради или учебника кнопкой выше.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            attachmentsUi.attachments.forEach { att ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "${att.fileName} (${att.extension.uppercase()})",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        if (att.extractedText.isNotBlank()) {
                                            Text(
                                                text = att.extractedText.take(240),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val previewBitmaps = attachmentsUi.attachments.flatMap { it.images } + extraBitmaps
                        if (previewBitmaps.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(previewBitmaps) { bmp ->
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Страница или фото материала",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(96.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // AI Agent Action Button & Live Stepper
            item {
                Button(
                    onClick = { onRunAgent(assignment, extraBitmaps.toList()) },
                    enabled = !agentExecution.isRunning && !isSubmitted,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("solve_with_agent_button")
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isSubmitted) "Задание уже отправлено в Moodle"
                        else "Запустить AI-агента (анализ и решение)"
                    )
                }
            }

            item {
                AgentStepperCard(state = agentExecution)
            }

            // Explanation & Verification Cards
            if (assignment.aiExplanation.isNotBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Lightbulb,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Простое объяснение решения",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = assignment.aiExplanation,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            if (assignment.verificationNotes.isNotBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Verified,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Самопроверка агента",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Text(
                                text = assignment.verificationNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Editable Answer + Mandatory Confirmation Preview
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Ответ для вставки в Moodle",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (assignment.recommendedFormat.isNotBlank()) {
                            Text(
                                text = "Формат: ${assignment.recommendedFormat}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        OutlinedTextField(
                            value = editableAnswer,
                            onValueChange = { editableAnswer = it },
                            enabled = !isSubmitted,
                            placeholder = { Text("Здесь появится сгенерированный ответ. Вы можете отредактировать его перед отправкой...") },
                            minLines = 6,
                            maxLines = 16,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("assignment_answer_editor")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(editableAnswer))
                                    copiedNotice = true
                                },
                                enabled = editableAnswer.isNotBlank(),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("copy_answer_button")
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Копировать ответ", modifier = Modifier.size(18.dp))
                            }
                            OutlinedButton(
                                onClick = { onSaveDraft(assignment.id, editableAnswer) },
                                enabled = !isSubmitted && editableAnswer.isNotBlank(),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("save_draft_button")
                            ) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Черновик")
                            }
                            Button(
                                onClick = {
                                    userConfirmedCheckbox = false
                                    showPreviewDialog = true
                                },
                                enabled = !isSubmitted && editableAnswer.isNotBlank(),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(48.dp)
                                    .testTag("preview_submit_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Отправить")
                            }
                        }
                        if (copiedNotice) {
                            Text(
                                text = "Ответ скопирован в буфер обмена.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPreviewDialog && assignment != null) {
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            icon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
            title = { Text("Предварительный просмотр отправки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Курс: ${assignment.courseTitle}\nЗадание: ${assignment.title}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = editableAnswer.take(1500),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = userConfirmedCheckbox,
                            onCheckedChange = { userConfirmedCheckbox = it },
                            modifier = Modifier.testTag("confirm_submission_checkbox")
                        )
                        Text(
                            text = "Я проверил ответ и подтверждаю отправку в Moodle.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPreviewDialog = false
                        onConfirmAndSubmit(assignment, editableAnswer)
                    },
                    enabled = userConfirmedCheckbox,
                    modifier = Modifier.testTag("confirm_and_submit_button")
                ) {
                    Text("Подтвердить и отправить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPreviewDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
