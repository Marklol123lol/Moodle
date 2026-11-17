package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.AiSolutionResult
import com.example.data.model.AssignmentRecord
import com.example.data.model.MoodleAttachment
import com.example.data.model.SyncedCourse
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class GeminiAiService {

    private companion object {
        const val TAG = "GeminiAiService"
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val TIMEOUT_MS = 60_000L
    }

    private val solutionSchema = Schema.obj(
        properties = mapOf(
            "subjectCategory" to Schema.string(description = "Detected school subject name in Russian"),
            "summary" to Schema.string(description = "Brief analysis of assignment requirements"),
            "solution" to Schema.string(description = "Complete formatted answer ready for Moodle"),
            "explanationSimple" to Schema.string(description = "Clear step-by-step explanation in simple language"),
            "recommendedFormat" to Schema.string(description = "Recommended Moodle submission format (text, essay, steps, etc.)"),
            "verificationNotes" to Schema.string(description = "Self-check verification notes confirming accuracy and completeness")
        )
    )

    private fun structuredModel(modelName: String) =
        Firebase.ai.generativeModel(
            modelName = modelName.ifBlank { DEFAULT_MODEL },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = solutionSchema
            }
        )

    private fun chatModel(modelName: String) =
        Firebase.ai.generativeModel(
            modelName = modelName.ifBlank { DEFAULT_MODEL }
        )

    suspend fun solveAssignment(
        assignment: AssignmentRecord,
        attachments: List<MoodleAttachment>,
        extraImages: List<Bitmap>,
        preferredModel: String,
        answerLanguage: String,
    ): Result<AiSolutionResult> {
        return try {
            val activeModel = structuredModel(preferredModel)
            val attachmentTexts = attachments
                .filter { it.extractedText.isNotBlank() }
                .joinToString("\n\n") { "Файл ${it.fileName}:\n${it.extractedText}" }

            val allBitmaps = (attachments.flatMap { it.images } + extraImages).take(6)

            val promptText = buildString {
                appendLine("Ты — профессиональный школьный AI-ассистент Moodle AI.")
                appendLine("Проанализируй школьное задание, изучи приложенные материалы/изображения, реши его точно и проверь правильность.")
                appendLine("Язык объяснения: $answerLanguage. Если задание на английском или латышском языке — сам ответ подготовь на языке задания, а объяснение — на $answerLanguage.")
                appendLine()
                appendLine("Курс: ${assignment.courseTitle}")
                appendLine("Название задания: ${assignment.title}")
                appendLine("Срок сдачи: ${assignment.dueDateText}")
                appendLine("Описание задания:")
                appendLine(assignment.description.ifBlank { "Описание отсутствует в HTML; опирайся на название и приложенные материалы." })
                if (attachmentTexts.isNotBlank()) {
                    appendLine()
                    appendLine("Содержимое прикреплённых файлов:")
                    appendLine(attachmentTexts.take(12000))
                }
                appendLine()
                appendLine("Требования к результату:")
                appendLine("1. В поле solution дай готовый, аккуратно оформленный ответ для вставки в Moodle.")
                appendLine("2. В поле explanationSimple объясни решение простым и понятным ученику языком.")
                appendLine("3. В поле verificationNotes выполни самопроверку: проверь расчёты, грамматику и соответствие всем требованиям задания.")
            }

            val response = withTimeout(TIMEOUT_MS) {
                activeModel.generateContent(
                    content {
                        text(promptText)
                        allBitmaps.forEach { bmp -> image(bmp) }
                    }
                )
            }

            val jsonText = response.text ?: throw IllegalStateException("Gemini вернул пустой ответ.")
            val obj = JSONObject(jsonText)
            Result.success(
                AiSolutionResult(
                    subjectCategory = obj.optString("subjectCategory", assignment.subjectCategory),
                    summary = obj.optString("summary", ""),
                    solution = obj.optString("solution", ""),
                    explanationSimple = obj.optString("explanationSimple", ""),
                    recommendedFormat = obj.optString("recommendedFormat", "Текстовый ответ"),
                    verificationNotes = obj.optString("verificationNotes", ""),
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini solveAssignment timed out", e)
            Result.failure(IllegalStateException("Превышено время ожидания Gemini (60 сек). Попробуйте ещё раз."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini solveAssignment failed", e)
            Result.failure(e)
        }
    }

    suspend fun verifyAnswer(
        assignment: AssignmentRecord,
        draftAnswer: String,
        preferredModel: String,
    ): Result<String> {
        return try {
            val model = chatModel(preferredModel)
            val prompt = """
                Проверь ответ ученика на школьное задание из Moodle.
                Курс: ${assignment.courseTitle}
                Задание: ${assignment.title}
                Условие: ${assignment.description.take(4000)}

                Подготовленный ответ:
                $draftAnswer

                Кратко (3-5 пунктов) укажи: всё ли верно, нет ли ошибок в математике/латышском/английском/фактах и готов ли ответ к отправке.
            """.trimIndent()

            val response = withTimeout(TIMEOUT_MS) {
                model.generateContent(prompt)
            }
            val text = response.text?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Модель вернула пустой ответ при проверке.")
            Result.success(text)
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Проверка ответа превысила 60 секунд."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini verifyAnswer failed", e)
            Result.failure(e)
        }
    }

    fun streamAssistantResponse(
        userMessage: String,
        courses: List<SyncedCourse>,
        assignments: List<AssignmentRecord>,
        chatHistory: List<Pair<String, String>>,
        preferredModel: String,
    ): Flow<String> = flow {
        val model = chatModel(preferredModel)

        val contextBlock = buildString {
            appendLine("Контекст реальных данных Moodle (moodle.1skola.lv):")
            if (courses.isEmpty()) {
                appendLine("- Курсы ещё не синхронизированы. Если пользователь спрашивает о своих курсах/заданиях, честно скажи, что нужно сначала подключиться к moodle.1skola.lv и синхронизировать курсы.")
            } else {
                appendLine("Курсы (${courses.size}):")
                courses.take(20).forEach { c ->
                    appendLine("- ${c.title} (ID: ${c.moodleCourseId}, заданий: ${c.assignmentCount})")
                }
            }
            if (assignments.isNotEmpty()) {
                appendLine()
                appendLine("Задания (${assignments.size}):")
                assignments.take(30).forEach { a ->
                    appendLine("- [${a.status}] Курс: ${a.courseTitle} | Задание: ${a.title} | Срок: ${a.dueDateText} | Описание: ${a.description.take(240)}")
                }
            }
            appendLine()
            appendLine("Правила: Никогда не выдумывай несуществующие курсы или задания. Опирайся только на реальный список выше. Помогай с математикой, английским, латышским, историей, биологией, географией и сочинениями. Никогда не отправляй ничего автоматически без подтверждения пользователя.")
        }

        val fullPrompt = buildString {
            appendLine(contextBlock)
            if (chatHistory.isNotEmpty()) {
                appendLine("\nНедавний диалог:")
                chatHistory.takeLast(6).forEach { (role, text) ->
                    appendLine("$role: ${text.take(600)}")
                }
            }
            appendLine("\nЗапрос ученика: $userMessage")
        }

        val accumulated = StringBuilder()
        try {
            withTimeout(TIMEOUT_MS) {
                model.generateContentStream(fullPrompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(accumulated.toString())
                    }
                }
            }
            if (accumulated.isBlank()) {
                throw IllegalStateException("Gemini вернул пустой ответ.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Streaming chat timed out", e)
            throw IllegalStateException("Превышено время ожидания ответа от Gemini (60 сек).")
        } catch (e: CancellationException) {
            throw e
        }
    }
}
