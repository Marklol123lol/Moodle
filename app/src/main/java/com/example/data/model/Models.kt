package com.example.data.model

import android.graphics.Bitmap
import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class MoodleConnectionMode(val labelRu: String) {
    DISCONNECTED("Не подключено"),
    BROWSER_SESSION("Сессия браузера"),
    WEB_SERVICE_API("Web Services API");

    companion object {
        fun safeFrom(raw: String): MoodleConnectionMode =
            entries.firstOrNull { it.name == raw } ?: DISCONNECTED
    }
}

enum class AssignmentStatus(val labelRu: String) {
    PENDING("Не выполнено"),
    DRAFT_READY("Черновик AI готов"),
    CONFIRMED("Подтверждено"),
    SUBMITTED("Отправлено в Moodle"),
    OVERDUE("Просрочено");

    companion object {
        fun safeFrom(raw: String): AssignmentStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class AgentRunStatus(val labelRu: String) {
    RUNNING("Выполняется"),
    AWAITING_CONFIRMATION("Ждёт подтверждения"),
    COMPLETED("Завершено"),
    SUBMITTED("Отправлено"),
    FAILED("Ошибка");

    companion object {
        fun safeFrom(raw: String): AgentRunStatus =
            entries.firstOrNull { it.name == raw } ?: RUNNING
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@Keep
@IgnoreExtraProperties
data class UserWorkspace(
    @DocumentId val id: String = "",
    val userId: String = "",
    val moodleUrl: String = "https://moodle.1skola.lv",
    val connectionMode: String = MoodleConnectionMode.DISCONNECTED.name,
    val moodleDisplayName: String = "",
    val preferredModel: String = "gemini-3.8-flash",
    val answerLanguage: String = "Русский",
    val courseCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class SyncedCourse(
    @DocumentId val id: String = "",
    val userId: String = "",
    val moodleCourseId: String = "",
    val title: String = "",
    val shortName: String = "",
    val category: String = "",
    val courseUrl: String = "",
    val progressPercent: Long = 0L,
    val assignmentCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class AssignmentRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val moodleAssignId: String = "",
    val moodleCourseId: String = "",
    val courseTitle: String = "",
    val title: String = "",
    val description: String = "",
    val dueDateText: String = "",
    val dueEpochMillis: Long = 0L,
    val status: String = AssignmentStatus.PENDING.name,
    val subjectCategory: String = "",
    val assignmentUrl: String = "",
    val attachmentsSummary: String = "",
    val aiSolution: String = "",
    val aiExplanation: String = "",
    val userEditedAnswer: String = "",
    val recommendedFormat: String = "",
    val verificationNotes: String = "",
    val submittedByUser: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class AgentLogRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val actionType: String = "",
    val assignmentId: String = "",
    val assignmentTitle: String = "",
    val courseTitle: String = "",
    val summary: String = "",
    val status: String = AgentRunStatus.RUNNING.name,
    val steps: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class ChatMessageRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "user",
    val text: String = "",
    val relatedCourseTitle: String = "",
    val relatedAssignmentTitle: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

data class MoodleAttachment(
    val fileName: String,
    val url: String,
    val extension: String,
    val extractedText: String = "",
    val images: List<Bitmap> = emptyList(),
)

data class AiSolutionResult(
    val subjectCategory: String,
    val summary: String,
    val solution: String,
    val explanationSimple: String,
    val recommendedFormat: String,
    val verificationNotes: String,
)

data class MoodleSessionCredentials(
    val baseUrl: String = "https://moodle.1skola.lv",
    val mode: MoodleConnectionMode = MoodleConnectionMode.DISCONNECTED,
    val cookieHeader: String = "",
    val sesskey: String = "",
    val wsToken: String = "",
    val moodleUserId: String = "",
    val moodleDisplayName: String = "",
) {
    val isConnected: Boolean
        get() = when (mode) {
            MoodleConnectionMode.DISCONNECTED -> false
            MoodleConnectionMode.BROWSER_SESSION -> cookieHeader.contains("MoodleSession")
            MoodleConnectionMode.WEB_SERVICE_API -> wsToken.isNotBlank()
        }
}
