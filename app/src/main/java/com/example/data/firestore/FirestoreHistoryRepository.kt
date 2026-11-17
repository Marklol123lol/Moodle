package com.example.data.firestore

import android.content.Context
import com.example.R
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.AgentLogRecord
import com.example.data.model.AgentRunStatus
import com.example.data.model.AssignmentRecord
import com.example.data.model.AssignmentStatus
import com.example.data.model.ChatMessageRecord
import com.example.data.model.MoodleConnectionMode
import com.example.data.model.SyncedCourse
import com.example.data.model.UserWorkspace
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirestoreHistoryRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Требуется вход через Google для работы с хранилищем.")
    }

    private fun safeDocId(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return cleaned.take(100).ifBlank { UUID.randomUUID().toString() }
    }

    fun observeWorkspace(userId: String): Flow<UserWorkspace?> = flow {
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserWorkspace::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error -> handleStreamError(error, OperationType.GET, path, userId) }
        )
    }

    fun observeCourses(userId: String): Flow<List<SyncedCourse>> = flow {
        val path = "users/$userId/courses"
        emitAll(
            db.collection("users").document(userId).collection("courses")
                .snapshots()
                .map { snap ->
                    snap.toObjects(SyncedCourse::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error -> handleStreamError(error, OperationType.LIST, path, userId) }
        )
    }

    fun observeAssignments(userId: String): Flow<List<AssignmentRecord>> = flow {
        val path = "users/$userId/assignments"
        emitAll(
            db.collection("users").document(userId).collection("assignments")
                .snapshots()
                .map { snap ->
                    snap.toObjects(AssignmentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(compareBy<AssignmentRecord> {
                            if (it.dueEpochMillis == 0L) Long.MAX_VALUE else it.dueEpochMillis
                        })
                }
                .catch { error -> handleStreamError(error, OperationType.LIST, path, userId) }
        )
    }

    fun observeAgentLogs(userId: String): Flow<List<AgentLogRecord>> = flow {
        val path = "users/$userId/agentLogs"
        emitAll(
            db.collection("users").document(userId).collection("agentLogs")
                .snapshots()
                .map { snap ->
                    snap.toObjects(AgentLogRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }
                }
                .catch { error -> handleStreamError(error, OperationType.LIST, path, userId) }
        )
    }

    fun observeChatMessages(userId: String): Flow<List<ChatMessageRecord>> = flow {
        val path = "users/$userId/chatMessages"
        emitAll(
            db.collection("users").document(userId).collection("chatMessages")
                .snapshots()
                .map { snap ->
                    snap.toObjects(ChatMessageRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }
                }
                .catch { error -> handleStreamError(error, OperationType.LIST, path, userId) }
        )
    }

    private fun handleStreamError(
        error: Throwable,
        opType: OperationType,
        path: String,
        userId: String,
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, opType, path)
        }
        throw error
    }

    suspend fun ensureWorkspaceInitialized(
        userId: String,
        moodleUrl: String = "https://moodle.1skola.lv",
    ): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "UID mismatch" }
            val ref = db.collection("users").document(uid)
            val snap = ref.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "moodleUrl" to moodleUrl.take(250),
                    "connectionMode" to MoodleConnectionMode.DISCONNECTED.name,
                    "moodleDisplayName" to "",
                    "preferredModel" to "gemini-3.8-flash",
                    "answerLanguage" to "Русский",
                    "courseCount" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                ref.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateMoodleConnectionState(
        userId: String,
        moodleUrl: String,
        mode: MoodleConnectionMode,
        displayName: String,
        courseCount: Long,
    ): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            ensureWorkspaceInitialized(uid, moodleUrl)
            db.collection("users").document(uid).update(
                mapOf(
                    "moodleUrl" to moodleUrl.take(250),
                    "connectionMode" to mode.name,
                    "moodleDisplayName" to displayName.take(120),
                    "courseCount" to courseCount.coerceIn(0L, 500L),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateAiPreferences(
        userId: String,
        preferredModel: String,
        answerLanguage: String,
    ): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val safeModel = if (preferredModel in listOf("gemini-3.8-flash", "gemini-4-argon", "gemini-3.5-flash-lite")) {
                preferredModel
            } else "gemini-3.8-flash"
            db.collection("users").document(uid).update(
                mapOf(
                    "preferredModel" to safeModel,
                    "answerLanguage" to answerLanguage.take(40).ifBlank { "Русский" },
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun upsertSyncedData(
        userId: String,
        courses: List<SyncedCourse>,
        assignments: List<AssignmentRecord>,
    ): Result<Unit> {
        val basePath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)

            val coursesRef = db.collection("users").document(uid).collection("courses")
            for (course in courses) {
                val docId = safeDocId(course.id.ifBlank { "course_${course.moodleCourseId}" })
                val docRef = coursesRef.document(docId)
                val existing = docRef.get().await()
                if (existing.exists()) {
                    docRef.update(
                        mapOf(
                            "title" to course.title.take(290).ifBlank { "Курс" },
                            "shortName" to course.shortName.take(115),
                            "category" to course.category.take(145),
                            "courseUrl" to course.courseUrl.take(495),
                            "progressPercent" to course.progressPercent.coerceIn(0L, 100L),
                            "assignmentCount" to course.assignmentCount.coerceIn(0L, 500L),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                    ).await()
                } else {
                    docRef.set(
                        mapOf(
                            "userId" to uid,
                            "moodleCourseId" to course.moodleCourseId.take(60),
                            "title" to course.title.take(290).ifBlank { "Курс" },
                            "shortName" to course.shortName.take(115),
                            "category" to course.category.take(145),
                            "courseUrl" to course.courseUrl.take(495),
                            "progressPercent" to course.progressPercent.coerceIn(0L, 100L),
                            "assignmentCount" to course.assignmentCount.coerceIn(0L, 500L),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                    ).await()
                }
            }

            val assignRef = db.collection("users").document(uid).collection("assignments")
            for (assign in assignments) {
                val docId = safeDocId(assign.id.ifBlank { "assign_${assign.moodleAssignId}" })
                val docRef = assignRef.document(docId)
                val existing = docRef.get().await()
                if (existing.exists()) {
                    val existingStatus = existing.getString("status") ?: AssignmentStatus.PENDING.name
                    val nextStatus = if (existingStatus in listOf(
                            AssignmentStatus.DRAFT_READY.name,
                            AssignmentStatus.CONFIRMED.name,
                            AssignmentStatus.SUBMITTED.name
                        )
                    ) existingStatus else assign.status

                    docRef.update(
                        mapOf(
                            "courseTitle" to assign.courseTitle.take(290).ifBlank { "Курс" },
                            "title" to assign.title.take(390).ifBlank { "Задание" },
                            "description" to assign.description.take(19500),
                            "dueDateText" to assign.dueDateText.take(115),
                            "dueEpochMillis" to assign.dueEpochMillis.coerceAtLeast(0L),
                            "assignmentUrl" to assign.assignmentUrl.take(495),
                            "attachmentsSummary" to assign.attachmentsSummary.take(3900),
                            "status" to nextStatus,
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                    ).await()
                } else {
                    docRef.set(
                        mapOf(
                            "userId" to uid,
                            "moodleAssignId" to assign.moodleAssignId.take(60),
                            "moodleCourseId" to assign.moodleCourseId.take(60),
                            "courseTitle" to assign.courseTitle.take(290).ifBlank { "Курс" },
                            "title" to assign.title.take(390).ifBlank { "Задание" },
                            "description" to assign.description.take(19500),
                            "dueDateText" to assign.dueDateText.take(115),
                            "dueEpochMillis" to assign.dueEpochMillis.coerceAtLeast(0L),
                            "status" to assign.status,
                            "subjectCategory" to assign.subjectCategory.take(95),
                            "assignmentUrl" to assign.assignmentUrl.take(495),
                            "attachmentsSummary" to assign.attachmentsSummary.take(3900),
                            "aiSolution" to "",
                            "aiExplanation" to "",
                            "userEditedAnswer" to "",
                            "recommendedFormat" to "",
                            "verificationNotes" to "",
                            "submittedByUser" to assign.submittedByUser,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, basePath)
            Result.failure(e)
        }
    }

    suspend fun saveAiSolutionForAssignment(
        userId: String,
        assignmentId: String,
        subjectCategory: String,
        aiSolution: String,
        aiExplanation: String,
        recommendedFormat: String,
        verificationNotes: String,
    ): Result<Unit> {
        val docId = safeDocId(assignmentId)
        val docPath = "users/$userId/assignments/$docId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(uid).collection("assignments").document(docId).update(
                mapOf(
                    "subjectCategory" to subjectCategory.take(95),
                    "aiSolution" to aiSolution.take(29500),
                    "aiExplanation" to aiExplanation.take(14500),
                    "userEditedAnswer" to aiSolution.take(29500),
                    "recommendedFormat" to recommendedFormat.take(490),
                    "verificationNotes" to verificationNotes.take(4900),
                    "status" to AssignmentStatus.DRAFT_READY.name,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveEditedDraftAnswer(
        userId: String,
        assignmentId: String,
        editedAnswer: String,
    ): Result<Unit> {
        val docId = safeDocId(assignmentId)
        val docPath = "users/$userId/assignments/$docId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(uid).collection("assignments").document(docId).update(
                mapOf(
                    "userEditedAnswer" to editedAnswer.take(29500),
                    "status" to AssignmentStatus.DRAFT_READY.name,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun markAssignmentConfirmedAndSubmitted(
        userId: String,
        assignmentId: String,
        finalAnswer: String,
        submitted: Boolean,
    ): Result<Unit> {
        val docId = safeDocId(assignmentId)
        val docPath = "users/$userId/assignments/$docId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val status = if (submitted) AssignmentStatus.SUBMITTED.name else AssignmentStatus.CONFIRMED.name
            db.collection("users").document(uid).collection("assignments").document(docId).update(
                mapOf(
                    "userEditedAnswer" to finalAnswer.take(29500),
                    "status" to status,
                    "submittedByUser" to true,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createAgentLog(
        userId: String,
        actionType: String,
        assignmentId: String,
        assignmentTitle: String,
        courseTitle: String,
        summary: String,
        steps: List<String>,
        status: AgentRunStatus = AgentRunStatus.RUNNING,
    ): Result<String> {
        val logId = safeDocId("log_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}")
        val docPath = "users/$userId/agentLogs/$logId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(uid).collection("agentLogs").document(logId).set(
                mapOf(
                    "userId" to uid,
                    "actionType" to actionType.take(95).ifBlank { "AGENT_TASK" },
                    "assignmentId" to assignmentId.take(120),
                    "assignmentTitle" to assignmentTitle.take(390),
                    "courseTitle" to courseTitle.take(290),
                    "summary" to summary.take(3900).ifBlank { "Запуск агента" },
                    "status" to status.name,
                    "steps" to steps.take(20),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(logId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateAgentLog(
        userId: String,
        logId: String,
        assignmentId: String,
        assignmentTitle: String,
        courseTitle: String,
        summary: String,
        steps: List<String>,
        status: AgentRunStatus,
    ): Result<Unit> {
        val safeId = safeDocId(logId)
        val docPath = "users/$userId/agentLogs/$safeId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(uid).collection("agentLogs").document(safeId).update(
                mapOf(
                    "assignmentId" to assignmentId.take(120),
                    "assignmentTitle" to assignmentTitle.take(390),
                    "courseTitle" to courseTitle.take(290),
                    "summary" to summary.take(3900).ifBlank { "Обновление шага" },
                    "status" to status.name,
                    "steps" to steps.take(20),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun appendChatMessage(
        userId: String,
        role: String,
        text: String,
        relatedCourseTitle: String = "",
        relatedAssignmentTitle: String = "",
    ): Result<Unit> {
        val msgId = safeDocId("msg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}")
        val docPath = "users/$userId/chatMessages/$msgId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val safeRole = if (role == "assistant") "assistant" else "user"
            db.collection("users").document(uid).collection("chatMessages").document(msgId).set(
                mapOf(
                    "userId" to uid,
                    "role" to safeRole,
                    "text" to text.take(29500).ifBlank { "..." },
                    "relatedCourseTitle" to relatedCourseTitle.take(290),
                    "relatedAssignmentTitle" to relatedAssignmentTitle.take(390),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun clearChatMessages(userId: String): Result<Unit> {
        val path = "users/$userId/chatMessages"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val snaps = db.collection("users").document(uid).collection("chatMessages").get().await()
            for (doc in snaps.documents) {
                doc.reference.delete().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }
}
