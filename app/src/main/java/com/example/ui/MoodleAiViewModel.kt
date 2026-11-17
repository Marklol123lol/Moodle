package com.example.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiAiService
import com.example.data.firestore.FirestoreHistoryRepository
import com.example.data.local.MoodleSessionVault
import com.example.data.model.AgentLogRecord
import com.example.data.model.AgentRunStatus
import com.example.data.model.AssignmentRecord
import com.example.data.model.AssignmentStatus
import com.example.data.model.ChatMessageRecord
import com.example.data.model.MoodleAttachment
import com.example.data.model.MoodleConnectionMode
import com.example.data.model.MoodleSessionCredentials
import com.example.data.model.SyncedCourse
import com.example.data.model.UiState
import com.example.data.model.UserWorkspace
import com.example.data.moodle.MoodleAuthExpiredException
import com.example.data.moodle.MoodleClient
import com.example.data.moodle.SubmissionOutcome
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SyncStatusUi {
    data object Idle : SyncStatusUi
    data class Syncing(val message: String) : SyncStatusUi
    data class Success(val message: String) : SyncStatusUi
    data class Error(val message: String) : SyncStatusUi
}

data class AgentExecutionUi(
    val isRunning: Boolean = false,
    val currentStepIndex: Int = 0,
    val steps: List<String> = emptyList(),
    val activeAssignmentId: String? = null,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val awaitingConfirmation: Boolean = false,
)

data class AssignmentAttachmentsUi(
    val isLoading: Boolean = false,
    val attachments: List<MoodleAttachment> = emptyList(),
    val error: String? = null,
)

class MoodleAiViewModel(
    private val currentUserId: String,
    private val sessionVault: MoodleSessionVault,
    private val moodleClient: MoodleClient,
    private val geminiAiService: GeminiAiService,
    private val historyRepo: FirestoreHistoryRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "MoodleAiViewModel"
        const val STOP_TIMEOUT_MS = 5000L
    }

    val workspaceState: StateFlow<UiState<UserWorkspace?>> =
        historyRepo.observeWorkspace(currentUserId)
            .map<UserWorkspace?, UiState<UserWorkspace?>> { UiState.Success(it) }
            .catch { emit(UiState.Error(unwrapFirestoreMessage(it, "Не удалось загрузить профиль"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val coursesState: StateFlow<UiState<List<SyncedCourse>>> =
        historyRepo.observeCourses(currentUserId)
            .map<List<SyncedCourse>, UiState<List<SyncedCourse>>> { UiState.Success(it) }
            .catch { emit(UiState.Error(unwrapFirestoreMessage(it, "Не удалось загрузить курсы"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val assignmentsState: StateFlow<UiState<List<AssignmentRecord>>> =
        historyRepo.observeAssignments(currentUserId)
            .map<List<AssignmentRecord>, UiState<List<AssignmentRecord>>> { UiState.Success(it) }
            .catch { emit(UiState.Error(unwrapFirestoreMessage(it, "Не удалось загрузить задания"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val agentLogsState: StateFlow<UiState<List<AgentLogRecord>>> =
        historyRepo.observeAgentLogs(currentUserId)
            .map<List<AgentLogRecord>, UiState<List<AgentLogRecord>>> { UiState.Success(it) }
            .catch { emit(UiState.Error(unwrapFirestoreMessage(it, "Не удалось загрузить журнал агента"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val chatMessagesState: StateFlow<UiState<List<ChatMessageRecord>>> =
        historyRepo.observeChatMessages(currentUserId)
            .map<List<ChatMessageRecord>, UiState<List<ChatMessageRecord>>> { UiState.Success(it) }
            .catch { emit(UiState.Error(unwrapFirestoreMessage(it, "Не удалось загрузить чат"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val moodleSession: StateFlow<MoodleSessionCredentials> =
        sessionVault.observeSession(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MoodleSessionCredentials())

    private val _syncStatus = MutableStateFlow<SyncStatusUi>(SyncStatusUi.Idle)
    val syncStatus: StateFlow<SyncStatusUi> = _syncStatus.asStateFlow()

    private val _agentExecution = MutableStateFlow(AgentExecutionUi())
    val agentExecution: StateFlow<AgentExecutionUi> = _agentExecution.asStateFlow()

    private val _attachmentsUi = MutableStateFlow(AssignmentAttachmentsUi())
    val attachmentsUi: StateFlow<AssignmentAttachmentsUi> = _attachmentsUi.asStateFlow()

    private val _streamingAssistantText = MutableStateFlow<String?>(null)
    val streamingAssistantText: StateFlow<String?> = _streamingAssistantText.asStateFlow()

    private val _submissionBrowserFallback = MutableStateFlow<SubmissionOutcome.RequiresBrowserForm?>(null)
    val submissionBrowserFallback: StateFlow<SubmissionOutcome.RequiresBrowserForm?> = _submissionBrowserFallback.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    init {
        viewModelScope.launch {
            historyRepo.ensureWorkspaceInitialized(currentUserId)
        }
    }

    private fun unwrapFirestoreMessage(error: Throwable, fallback: String): String {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: error
        Log.w(TAG, fallback, root)
        return root.message ?: fallback
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun clearSubmissionBrowserFallback() {
        _submissionBrowserFallback.value = null
    }

    fun connectViaBrowserSession(
        baseUrl: String,
        cookieHeader: String,
        extractedSesskey: String,
        extractedName: String,
    ) {
        viewModelScope.launch {
            if (!cookieHeader.contains("MoodleSession")) {
                _syncStatus.value = SyncStatusUi.Error(
                    "Cookie MoodleSession не найдена. Пожалуйста, войдите на moodle.1skola.lv в окне браузера ниже."
                )
                return@launch
            }
            _syncStatus.value = SyncStatusUi.Syncing("Проверка сессии Moodle...")
            val verifyResult = moodleClient.verifyBrowserSession(baseUrl, cookieHeader)
            verifyResult.onSuccess { (sesskey, moodleUid, displayName) ->
                val finalSesskey = sesskey.ifBlank { extractedSesskey }
                val finalName = extractedName.ifBlank { displayName }
                sessionVault.saveBrowserSession(
                    userId = currentUserId,
                    baseUrl = baseUrl,
                    cookieHeader = cookieHeader,
                    sesskey = finalSesskey,
                    moodleUserId = moodleUid,
                    moodleDisplayName = finalName,
                )
                historyRepo.updateMoodleConnectionState(
                    userId = currentUserId,
                    moodleUrl = baseUrl,
                    mode = MoodleConnectionMode.BROWSER_SESSION,
                    displayName = finalName,
                    courseCount = currentCourses().size.toLong(),
                )
                syncMoodleCoursesAndAssignments()
            }.onFailure { err ->
                _syncStatus.value = SyncStatusUi.Error(
                    err.localizedMessage ?: "Не удалось подтвердить сессию Moodle."
                )
            }
        }
    }

    fun connectViaWebServiceCredentials(
        baseUrl: String,
        username: String,
        password: String,
    ) {
        viewModelScope.launch {
            if (username.isBlank() || password.isBlank()) {
                _syncStatus.value = SyncStatusUi.Error("Введите логин и пароль Moodle.")
                return@launch
            }
            _syncStatus.value = SyncStatusUi.Syncing("Получение токена Moodle Web Services...")
            val tokenResult = moodleClient.obtainWebServiceToken(baseUrl, username, password)
            tokenResult.onSuccess { token ->
                connectViaWebServiceTokenInternal(baseUrl, token)
            }.onFailure { err ->
                _syncStatus.value = SyncStatusUi.Error(
                    "Web Services API: ${err.localizedMessage ?: "недоступен"}. Используйте вкладку «Вход через браузер»."
                )
            }
        }
    }

    fun connectViaWebServiceToken(baseUrl: String, token: String) {
        viewModelScope.launch {
            if (token.isBlank()) {
                _syncStatus.value = SyncStatusUi.Error("Введите токен Moodle Web Services.")
                return@launch
            }
            connectViaWebServiceTokenInternal(baseUrl, token.trim())
        }
    }

    private suspend fun connectViaWebServiceTokenInternal(baseUrl: String, token: String) {
        _syncStatus.value = SyncStatusUi.Syncing("Проверка токена Moodle API...")
        val verify = moodleClient.verifyWebServiceToken(baseUrl, token)
        verify.onSuccess { (moodleUid, fullName) ->
            sessionVault.saveWebServiceSession(
                userId = currentUserId,
                baseUrl = baseUrl,
                wsToken = token,
                moodleUserId = moodleUid,
                moodleDisplayName = fullName,
            )
            historyRepo.updateMoodleConnectionState(
                userId = currentUserId,
                moodleUrl = baseUrl,
                mode = MoodleConnectionMode.WEB_SERVICE_API,
                displayName = fullName,
                courseCount = currentCourses().size.toLong(),
            )
            syncMoodleCoursesAndAssignments()
        }.onFailure { err ->
            _syncStatus.value = SyncStatusUi.Error(
                err.localizedMessage ?: "Токен Moodle отклонён сервером."
            )
        }
    }

    fun disconnectMoodle() {
        viewModelScope.launch {
            sessionVault.clearSession(currentUserId)
            historyRepo.updateMoodleConnectionState(
                userId = currentUserId,
                moodleUrl = "https://moodle.1skola.lv",
                mode = MoodleConnectionMode.DISCONNECTED,
                displayName = "",
                courseCount = currentCourses().size.toLong(),
            )
            _syncStatus.value = SyncStatusUi.Idle
            _bannerMessage.value = "Сессия Moodle безопасно удалена с устройства."
        }
    }

    fun syncMoodleCoursesAndAssignments() {
        viewModelScope.launch {
            val session = sessionVault.getSession(currentUserId)
            if (!session.isConnected) {
                _syncStatus.value = SyncStatusUi.Error("Moodle не подключён. Подключите аккаунт moodle.1skola.lv.")
                return@launch
            }
            val steps = mutableListOf("1. Подключение к ${session.baseUrl} (${session.mode.labelRu})")
            _syncStatus.value = SyncStatusUi.Syncing("Загрузка курсов и заданий из Moodle...")

            val logId = historyRepo.createAgentLog(
                userId = currentUserId,
                actionType = "MOODLE_SYNC",
                assignmentId = "",
                assignmentTitle = "Синхронизация курсов",
                courseTitle = "moodle.1skola.lv",
                summary = "Подключение к Moodle и получение списка курсов",
                steps = steps,
                status = AgentRunStatus.RUNNING,
            ).getOrNull()

            val result = moodleClient.syncCoursesAndAssignments(currentUserId, session)
            result.onSuccess { snapshot ->
                steps.add("2. Найдено курсов: ${snapshot.courses.size}")
                steps.add("3. Обнаружено заданий: ${snapshot.assignments.size}")
                if (snapshot.sesskey.isNotBlank() && session.mode == MoodleConnectionMode.BROWSER_SESSION) {
                    sessionVault.saveBrowserSession(
                        userId = currentUserId,
                        baseUrl = session.baseUrl,
                        cookieHeader = session.cookieHeader,
                        sesskey = snapshot.sesskey,
                        moodleUserId = snapshot.moodleUserId,
                        moodleDisplayName = snapshot.displayName,
                    )
                }
                val saveRes = historyRepo.upsertSyncedData(
                    userId = currentUserId,
                    courses = snapshot.courses,
                    assignments = snapshot.assignments,
                )
                historyRepo.updateMoodleConnectionState(
                    userId = currentUserId,
                    moodleUrl = session.baseUrl,
                    mode = session.mode,
                    displayName = snapshot.displayName,
                    courseCount = snapshot.courses.size.toLong(),
                )
                if (saveRes.isSuccess) {
                    steps.add("4. Данные сохранены в рабочее пространство")
                    val msg = "Синхронизировано курсов: ${snapshot.courses.size}, заданий: ${snapshot.assignments.size}."
                    _syncStatus.value = SyncStatusUi.Success(msg)
                    if (logId != null) {
                        historyRepo.updateAgentLog(
                            userId = currentUserId,
                            logId = logId,
                            assignmentId = "",
                            assignmentTitle = "Синхронизация Moodle",
                            courseTitle = "moodle.1skola.lv",
                            summary = msg,
                            steps = steps,
                            status = AgentRunStatus.COMPLETED,
                        )
                    }
                } else {
                    val err = saveRes.exceptionOrNull()?.localizedMessage ?: "Ошибка сохранения"
                    _syncStatus.value = SyncStatusUi.Error(err)
                }
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Ошибка синхронизации с Moodle."
                steps.add("Ошибка: $msg")
                if (err is MoodleAuthExpiredException) {
                    sessionVault.clearSession(currentUserId)
                    historyRepo.updateMoodleConnectionState(
                        userId = currentUserId,
                        moodleUrl = session.baseUrl,
                        mode = MoodleConnectionMode.DISCONNECTED,
                        displayName = "",
                        courseCount = currentCourses().size.toLong(),
                    )
                }
                _syncStatus.value = SyncStatusUi.Error(msg)
                if (logId != null) {
                    historyRepo.updateAgentLog(
                        userId = currentUserId,
                        logId = logId,
                        assignmentId = "",
                        assignmentTitle = "Синхронизация Moodle",
                        courseTitle = "moodle.1skola.lv",
                        summary = msg,
                        steps = steps,
                        status = AgentRunStatus.FAILED,
                    )
                }
            }
        }
    }

    fun loadAssignmentAttachments(assignment: AssignmentRecord) {
        viewModelScope.launch {
            _attachmentsUi.value = AssignmentAttachmentsUi(isLoading = true)
            val session = sessionVault.getSession(currentUserId)
            val result = moodleClient.fetchAssignmentDetailsAndAttachments(
                session = session,
                assignmentUrl = assignment.assignmentUrl,
                attachmentsSummary = assignment.attachmentsSummary,
            )
            result.onSuccess { deep ->
                _attachmentsUi.value = AssignmentAttachmentsUi(
                    isLoading = false,
                    attachments = deep.attachments,
                )
            }.onFailure { err ->
                _attachmentsUi.value = AssignmentAttachmentsUi(
                    isLoading = false,
                    error = err.localizedMessage ?: "Не удалось загрузить материалы задания."
                )
            }
        }
    }

    fun runAgentForAssignment(
        assignment: AssignmentRecord,
        extraImages: List<Bitmap> = emptyList(),
    ) {
        viewModelScope.launch {
            val steps = mutableListOf<String>()
            fun pushStep(stepText: String, idx: Int) {
                steps.add(stepText)
                _agentExecution.value = AgentExecutionUi(
                    isRunning = true,
                    currentStepIndex = idx,
                    steps = steps.toList(),
                    activeAssignmentId = assignment.id,
                    statusMessage = stepText,
                )
            }

            pushStep("1. Подключение к Moodle (${moodleSession.value.mode.labelRu})", 1)
            val logId = historyRepo.createAgentLog(
                userId = currentUserId,
                actionType = "AI_AGENT_SOLVE",
                assignmentId = assignment.id,
                assignmentTitle = assignment.title,
                courseTitle = assignment.courseTitle,
                summary = "Агент анализирует задание и готовит решение",
                steps = steps,
                status = AgentRunStatus.RUNNING,
            ).getOrNull()

            pushStep("2. Открытие курса «${assignment.courseTitle}»", 2)
            pushStep("3. Проверка срока сдачи: ${assignment.dueDateText}", 3)
            pushStep("4. Чтение описания задания «${assignment.title}»", 4)
            pushStep("5. Изучение прикреплённых материалов (PDF, DOCX, фото)", 5)

            val session = sessionVault.getSession(currentUserId)
            val deepAttachments = if (session.isConnected) {
                moodleClient.fetchAssignmentDetailsAndAttachments(
                    session = session,
                    assignmentUrl = assignment.assignmentUrl,
                    attachmentsSummary = assignment.attachmentsSummary,
                ).getOrNull()?.attachments ?: _attachmentsUi.value.attachments
            } else {
                _attachmentsUi.value.attachments
            }
            _attachmentsUi.value = AssignmentAttachmentsUi(isLoading = false, attachments = deepAttachments)

            val ws = (workspaceState.value as? UiState.Success)?.data
            val modelName = ws?.preferredModel ?: "gemini-3.8-flash"
            val answerLang = ws?.answerLanguage ?: "Русский"

            pushStep("6. Анализ условий и генерация решения ($modelName)", 6)
            val solveResult = geminiAiService.solveAssignment(
                assignment = assignment,
                attachments = deepAttachments,
                extraImages = extraImages,
                preferredModel = modelName,
                answerLanguage = answerLang,
            )

            solveResult.onSuccess { solution ->
                pushStep("7. Проверка правильности и формата ответа", 7)
                val verificationNote = solution.verificationNotes.ifBlank {
                    geminiAiService.verifyAnswer(assignment, solution.solution, modelName)
                        .getOrElse { "Самопроверка завершена." }
                }

                historyRepo.saveAiSolutionForAssignment(
                    userId = currentUserId,
                    assignmentId = assignment.id,
                    subjectCategory = solution.subjectCategory.ifBlank { assignment.subjectCategory },
                    aiSolution = solution.solution,
                    aiExplanation = solution.explanationSimple,
                    recommendedFormat = solution.recommendedFormat,
                    verificationNotes = verificationNote,
                )

                pushStep("8. Черновик решения и объяснение готовы", 8)
                pushStep("9. Ожидание проверки и подтверждения пользователя", 9)

                _agentExecution.value = AgentExecutionUi(
                    isRunning = false,
                    currentStepIndex = 9,
                    steps = steps.toList(),
                    activeAssignmentId = assignment.id,
                    statusMessage = "Решение готово. Проверьте, при необходимости отредактируйте и подтвердите отправку.",
                    awaitingConfirmation = true,
                )

                if (logId != null) {
                    historyRepo.updateAgentLog(
                        userId = currentUserId,
                        logId = logId,
                        assignmentId = assignment.id,
                        assignmentTitle = assignment.title,
                        courseTitle = assignment.courseTitle,
                        summary = "Черновик подготовлен, ожидает подтверждения пользователя",
                        steps = steps,
                        status = AgentRunStatus.AWAITING_CONFIRMATION,
                    )
                }
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Ошибка Gemini AI при решении задания."
                steps.add("Ошибка: $msg")
                _agentExecution.value = AgentExecutionUi(
                    isRunning = false,
                    currentStepIndex = 6,
                    steps = steps.toList(),
                    activeAssignmentId = assignment.id,
                    statusMessage = "Выполнение агента остановлено",
                    errorMessage = msg,
                )
                if (logId != null) {
                    historyRepo.updateAgentLog(
                        userId = currentUserId,
                        logId = logId,
                        assignmentId = assignment.id,
                        assignmentTitle = assignment.title,
                        courseTitle = assignment.courseTitle,
                        summary = msg,
                        steps = steps,
                        status = AgentRunStatus.FAILED,
                    )
                }
            }
        }
    }

    fun runBatchAgentForAllPending() {
        viewModelScope.launch {
            val pending = currentAssignments().filter {
                it.status == AssignmentStatus.PENDING.name || it.status == AssignmentStatus.OVERDUE.name
            }.take(5)
            if (pending.isEmpty()) {
                _bannerMessage.value = "Нет невыполненных заданий для пакетной подготовки."
                return@launch
            }
            val steps = mutableListOf(
                "1. Подключение к Moodle и поиск невыполненных заданий",
                "2. Найдено заданий к подготовке: ${pending.size}"
            )
            _agentExecution.value = AgentExecutionUi(
                isRunning = true,
                currentStepIndex = 2,
                steps = steps.toList(),
                statusMessage = "Пакетный AI-агент обрабатывает ${pending.size} заданий...",
            )
            val logId = historyRepo.createAgentLog(
                userId = currentUserId,
                actionType = "BATCH_PREPARE",
                assignmentId = "",
                assignmentTitle = "Пакетная подготовка (${pending.size} заданий)",
                courseTitle = "Все курсы",
                summary = "Подготовка черновиков без автоматической отправки",
                steps = steps,
                status = AgentRunStatus.RUNNING,
            ).getOrNull()

            val session = sessionVault.getSession(currentUserId)
            val ws = (workspaceState.value as? UiState.Success)?.data
            val modelName = ws?.preferredModel ?: "gemini-3.8-flash"
            val answerLang = ws?.answerLanguage ?: "Русский"
            var preparedCount = 0

            for (assign in pending) {
                steps.add("Анализ: ${assign.courseTitle} — ${assign.title}")
                _agentExecution.value = _agentExecution.value.copy(
                    currentStepIndex = 6,
                    steps = steps.toList(),
                    activeAssignmentId = assign.id,
                    statusMessage = "Решаю: ${assign.title}"
                )
                val attachments = if (session.isConnected) {
                    moodleClient.fetchAssignmentDetailsAndAttachments(
                        session, assign.assignmentUrl, assign.attachmentsSummary
                    ).getOrNull()?.attachments ?: emptyList()
                } else emptyList()

                val res = geminiAiService.solveAssignment(
                    assignment = assign,
                    attachments = attachments,
                    extraImages = emptyList(),
                    preferredModel = modelName,
                    answerLanguage = answerLang,
                )
                if (res.isSuccess) {
                    val sol = res.getOrThrow()
                    historyRepo.saveAiSolutionForAssignment(
                        userId = currentUserId,
                        assignmentId = assign.id,
                        subjectCategory = sol.subjectCategory.ifBlank { assign.subjectCategory },
                        aiSolution = sol.solution,
                        aiExplanation = sol.explanationSimple,
                        recommendedFormat = sol.recommendedFormat,
                        verificationNotes = sol.verificationNotes,
                    )
                    preparedCount++
                }
            }

            steps.add("9. Подготовлено $preparedCount из ${pending.size}. Ждёт подтверждения пользователя!")
            _agentExecution.value = AgentExecutionUi(
                isRunning = false,
                currentStepIndex = 9,
                steps = steps.toList(),
                statusMessage = "Готово! Подготовлено черновиков: $preparedCount. Откройте любое задание для проверки и подтверждения.",
                awaitingConfirmation = true,
            )
            if (logId != null) {
                historyRepo.updateAgentLog(
                    userId = currentUserId,
                    logId = logId,
                    assignmentId = "",
                    assignmentTitle = "Пакетная подготовка",
                    courseTitle = "Все курсы",
                    summary = "Подготовлено черновиков: $preparedCount/${pending.size}. Ожидает ручного подтверждения.",
                    steps = steps,
                    status = AgentRunStatus.AWAITING_CONFIRMATION,
                )
            }
        }
    }

    fun saveEditedAnswer(assignmentId: String, editedAnswer: String) {
        viewModelScope.launch {
            val res = historyRepo.saveEditedDraftAnswer(currentUserId, assignmentId, editedAnswer)
            _bannerMessage.value = if (res.isSuccess) {
                "Изменения черновика сохранены."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Не удалось сохранить черновик."
            }
        }
    }

    fun confirmAndSubmitAssignment(assignment: AssignmentRecord, finalAnswer: String) {
        viewModelScope.launch {
            if (finalAnswer.isBlank()) {
                _bannerMessage.value = "Ответ не может быть пустым."
                return@launch
            }
            val session = sessionVault.getSession(currentUserId)
            if (!session.isConnected) {
                _bannerMessage.value = "Подключите Moodle (moodle.1skola.lv) перед отправкой ответа."
                return@launch
            }

            val steps = mutableListOf(
                "9. Пользователь подтвердил отправку после предпросмотра",
                "10. Подготовка и отправка ответа в Moodle..."
            )
            _agentExecution.value = AgentExecutionUi(
                isRunning = true,
                currentStepIndex = 10,
                steps = steps,
                activeAssignmentId = assignment.id,
                statusMessage = "Отправка подтверждённого ответа в Moodle...",
            )

            val outcome = moodleClient.submitAssignmentOnlineText(session, assignment, finalAnswer)
            outcome.onSuccess { sub ->
                when (sub) {
                    is SubmissionOutcome.Submitted -> {
                        historyRepo.markAssignmentConfirmedAndSubmitted(
                            userId = currentUserId,
                            assignmentId = assignment.id,
                            finalAnswer = finalAnswer,
                            submitted = true,
                        )
                        steps.add("Готово: ${sub.message}")
                        historyRepo.createAgentLog(
                            userId = currentUserId,
                            actionType = "SUBMIT_CONFIRMED",
                            assignmentId = assignment.id,
                            assignmentTitle = assignment.title,
                            courseTitle = assignment.courseTitle,
                            summary = sub.message,
                            steps = steps,
                            status = AgentRunStatus.SUBMITTED,
                        )
                        _agentExecution.value = AgentExecutionUi(
                            isRunning = false,
                            currentStepIndex = 10,
                            steps = steps,
                            activeAssignmentId = assignment.id,
                            statusMessage = sub.message,
                        )
                        _bannerMessage.value = sub.message
                    }
                    is SubmissionOutcome.RequiresBrowserForm -> {
                        historyRepo.markAssignmentConfirmedAndSubmitted(
                            userId = currentUserId,
                            assignmentId = assignment.id,
                            finalAnswer = finalAnswer,
                            submitted = false,
                        )
                        _submissionBrowserFallback.value = sub
                        _agentExecution.value = AgentExecutionUi(
                            isRunning = false,
                            currentStepIndex = 10,
                            steps = steps + sub.reason,
                            activeAssignmentId = assignment.id,
                            statusMessage = sub.reason,
                        )
                    }
                }
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Ошибка отправки ответа в Moodle."
                _agentExecution.value = AgentExecutionUi(
                    isRunning = false,
                    currentStepIndex = 10,
                    steps = steps + "Ошибка: $msg",
                    activeAssignmentId = assignment.id,
                    statusMessage = msg,
                    errorMessage = msg,
                )
                _bannerMessage.value = msg
            }
        }
    }

    fun sendAssistantMessage(userText: String) {
        val cleanText = userText.trim()
        if (cleanText.isBlank()) return

        viewModelScope.launch {
            historyRepo.appendChatMessage(
                userId = currentUserId,
                role = "user",
                text = cleanText,
            )

            val courses = currentCourses()
            val assignments = currentAssignments()
            val history = ((chatMessagesState.value as? UiState.Success)?.data ?: emptyList())
                .map { it.role to it.text }
            val ws = (workspaceState.value as? UiState.Success)?.data
            val modelName = ws?.preferredModel ?: "gemini-3.8-flash"

            // Detect explicit batch or subject-specific agent commands
            val lower = cleanText.lowercase()
            if (lower.contains("подготовь ответы на все") || lower.contains("выполни все доступные")) {
                if (assignments.any { it.status == AssignmentStatus.PENDING.name || it.status == AssignmentStatus.OVERDUE.name }) {
                    runBatchAgentForAllPending()
                }
            } else if (lower.contains("выполни") || lower.contains("реши") || lower.contains("подготовь ответ")) {
                val pendingList = assignments.filter {
                    it.status == AssignmentStatus.PENDING.name || it.status == AssignmentStatus.OVERDUE.name
                }
                val subjectHint = when {
                    lower.contains("англ") || lower.contains("english") -> "англ"
                    lower.contains("матем") || lower.contains("алгебр") || lower.contains("геом") -> "матем"
                    lower.contains("лат") || lower.contains("latv") -> "лат"
                    lower.contains("истор") || lower.contains("vēst") -> "истор"
                    lower.contains("биол") -> "биол"
                    lower.contains("геогр") -> "геогр"
                    lower.contains("физик") || lower.contains("fizik") -> "физик"
                    lower.contains("хим") || lower.contains("ķīm") -> "хим"
                    else -> null
                }
                val targetAssign = if (subjectHint != null) {
                    pendingList.firstOrNull {
                        it.courseTitle.lowercase().contains(subjectHint) ||
                            it.subjectCategory.lowercase().contains(subjectHint) ||
                            it.title.lowercase().contains(subjectHint)
                    }
                } else {
                    pendingList.firstOrNull { a ->
                        cleanText.split(" ").any { word ->
                            word.length >= 4 && (
                                a.title.contains(word, ignoreCase = true) ||
                                    a.courseTitle.contains(word, ignoreCase = true)
                                )
                        }
                    }
                }
                if (targetAssign != null) {
                    runAgentForAssignment(targetAssign)
                }
            }

            _streamingAssistantText.value = ""
            val finalBuilder = StringBuilder()
            try {
                geminiAiService.streamAssistantResponse(
                    userMessage = cleanText,
                    courses = courses,
                    assignments = assignments,
                    chatHistory = history,
                    preferredModel = modelName,
                ).collect { chunk ->
                    finalBuilder.clear()
                    finalBuilder.append(chunk)
                    _streamingAssistantText.value = chunk
                }

                val finalReply = finalBuilder.toString().ifBlank { "Не удалось сформировать ответ." }
                _streamingAssistantText.value = null
                historyRepo.appendChatMessage(
                    userId = currentUserId,
                    role = "assistant",
                    text = finalReply,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _streamingAssistantText.value = null
                val errorText = "Ошибка Gemini API: ${e.localizedMessage ?: "Не удалось получить ответ."}"
                historyRepo.appendChatMessage(
                    userId = currentUserId,
                    role = "assistant",
                    text = errorText,
                )
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            historyRepo.clearChatMessages(currentUserId)
        }
    }

    fun updateAiSettings(preferredModel: String, answerLanguage: String) {
        viewModelScope.launch {
            val res = historyRepo.updateAiPreferences(currentUserId, preferredModel, answerLanguage)
            _bannerMessage.value = if (res.isSuccess) {
                "Настройки AI обновлены ($preferredModel, $answerLanguage)."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Не удалось обновить настройки."
            }
        }
    }

    private fun currentCourses(): List<SyncedCourse> =
        (coursesState.value as? UiState.Success)?.data ?: emptyList()

    private fun currentAssignments(): List<AssignmentRecord> =
        (assignmentsState.value as? UiState.Success)?.data ?: emptyList()
}
