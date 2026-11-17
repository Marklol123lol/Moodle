package com.example.data.moodle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.model.AssignmentRecord
import com.example.data.model.AssignmentStatus
import com.example.data.model.MoodleAttachment
import com.example.data.model.MoodleConnectionMode
import com.example.data.model.MoodleSessionCredentials
import com.example.data.model.SyncedCourse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed interface SubmissionOutcome {
    data class Submitted(val message: String) : SubmissionOutcome
    data class RequiresBrowserForm(val editUrl: String, val prefillAnswer: String, val reason: String) : SubmissionOutcome
}

data class MoodleSyncSnapshot(
    val displayName: String,
    val moodleUserId: String,
    val sesskey: String,
    val courses: List<SyncedCourse>,
    val assignments: List<AssignmentRecord>,
)

data class AssignmentDeepDetails(
    val description: String,
    val dueDateText: String,
    val dueEpochMillis: Long,
    val isAlreadySubmitted: Boolean,
    val attachments: List<MoodleAttachment>,
)

class MoodleAuthExpiredException(message: String) : IOException(message)

class MoodleClient(private val context: Context) {

    private companion object {
        const val TAG = "MoodleClient"
        const val MAX_RETRIES = 3
        const val MAX_ATTACHMENT_BYTES = 12 * 1024 * 1024
        const val USER_AGENT = "MoodleAIAssistant-Android/1.0"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun obtainWebServiceToken(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val body = FormBody.Builder()
                .add("username", username.trim())
                .add("password", password)
                .add("service", "moodle_mobile_app")
                .build()
            val req = Request.Builder()
                .url("$cleanBase/login/token.php")
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()

            val responseText = executeWithRetry(req).getOrThrow()
            val json = JSONObject(responseText)
            val token = json.optString("token", "")
            val error = json.optString("error", "")
            when {
                token.isNotBlank() -> Result.success(token)
                error.isNotBlank() -> Result.failure(IllegalStateException(error))
                else -> Result.failure(IllegalStateException("Moodle не вернул Web Services токен."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain WS token", e)
            Result.failure(e)
        }
    }

    suspend fun verifyWebServiceToken(
        baseUrl: String,
        wsToken: String,
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val json = callWebService(baseUrl, wsToken, "core_webservice_get_site_info", emptyMap())
            val obj = JSONObject(json)
            if (obj.has("exception")) {
                return@withContext Result.failure(
                    IllegalStateException(obj.optString("message", "Ошибка Moodle Web Services"))
                )
            }
            val fullName = obj.optString("fullname", "Студент Moodle")
            val userId = obj.optLong("userid", 0L).toString()
            Result.success(userId to fullName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyBrowserSession(
        baseUrl: String,
        cookieHeader: String,
    ): Result<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trimEnd('/')
            var targetUrl = "$cleanBase/my/courses.php"
            var req = Request.Builder()
                .url(targetUrl)
                .header("Cookie", cookieHeader)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            var pair = executeForUrlAndBody(req)
            if (pair.isFailure) {
                targetUrl = "$cleanBase/my/"
                req = Request.Builder()
                    .url(targetUrl)
                    .header("Cookie", cookieHeader)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                pair = executeForUrlAndBody(req)
            }
            val (finalUrl, html) = pair.getOrThrow()
            if (isLoginPage(finalUrl, html)) {
                return@withContext Result.failure(
                    MoodleAuthExpiredException("Сессия Moodle не активна или истекла. Войдите через браузер.")
                )
            }
            val sesskey = extractSesskey(html)
            val doc = Jsoup.parse(html, cleanBase)
            val displayName = doc.selectFirst(".usertext, .usermenu .userbutton, [data-userid]")
                ?.text()?.trim()?.ifBlank { null }
                ?: "Студент Moodle"
            val userId = doc.selectFirst("[data-userid]")?.attr("data-userid").orEmpty()
            Result.success(Triple(sesskey, userId, displayName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncCoursesAndAssignments(
        userId: String,
        session: MoodleSessionCredentials,
    ): Result<MoodleSyncSnapshot> = withContext(Dispatchers.IO) {
        try {
            if (!session.isConnected) {
                return@withContext Result.failure(
                    IllegalStateException("Moodle не подключён. Выполните авторизацию.")
                )
            }
            when (session.mode) {
                MoodleConnectionMode.WEB_SERVICE_API -> syncViaWebService(userId, session)
                MoodleConnectionMode.BROWSER_SESSION -> syncViaBrowserSession(userId, session)
                MoodleConnectionMode.DISCONNECTED -> Result.failure(
                    IllegalStateException("Moodle отключён.")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Moodle sync failed", e)
            Result.failure(e)
        }
    }

    private suspend fun syncViaWebService(
        userId: String,
        session: MoodleSessionCredentials,
    ): Result<MoodleSyncSnapshot> {
        val siteInfo = JSONObject(
            callWebService(session.baseUrl, session.wsToken, "core_webservice_get_site_info")
        )
        if (siteInfo.has("exception")) {
            return Result.failure(IllegalStateException(siteInfo.optString("message", "Ошибка WS API")))
        }
        val moodleUid = siteInfo.optLong("userid", 0L).toString()
        val displayName = siteInfo.optString("fullname", session.moodleDisplayName.ifBlank { "Студент" })

        val coursesRaw = callWebService(
            session.baseUrl,
            session.wsToken,
            "core_enrol_get_users_courses",
            mapOf("userid" to moodleUid)
        )
        val coursesArr = JSONArray(coursesRaw)
        val courses = mutableListOf<SyncedCourse>()
        val courseIds = mutableListOf<String>()
        val courseTitles = mutableMapOf<String, String>()

        for (i in 0 until coursesArr.length()) {
            val c = coursesArr.getJSONObject(i)
            val cid = c.optLong("id").toString()
            val title = Jsoup.parse(c.optString("fullname", "Курс $cid")).text()
            val shortName = c.optString("shortname", "")
            val progress = c.optDouble("progress", 0.0).toLong().coerceIn(0L, 100L)
            courseIds.add(cid)
            courseTitles[cid] = title
            courses.add(
                SyncedCourse(
                    id = "course_$cid",
                    userId = userId,
                    moodleCourseId = cid,
                    title = title,
                    shortName = shortName,
                    category = c.optString("coursecategory", ""),
                    courseUrl = "${session.baseUrl}/course/view.php?id=$cid",
                    progressPercent = progress,
                    assignmentCount = 0L,
                )
            )
        }

        val assignments = mutableListOf<AssignmentRecord>()
        val assignCountPerCourse = mutableMapOf<String, Long>()

        if (courseIds.isNotEmpty()) {
            val assignParams = mutableMapOf<String, String>()
            courseIds.forEachIndexed { idx, cid -> assignParams["courseids[$idx]"] = cid }

            val assignJsonStr = callWebService(
                session.baseUrl,
                session.wsToken,
                "mod_assign_get_assignments",
                assignParams
            )
            val assignObj = JSONObject(assignJsonStr)

            if (assignObj.has("courses")) {
                val cArr = assignObj.getJSONArray("courses")
                for (i in 0 until cArr.length()) {
                    val courseBlock = cArr.getJSONObject(i)
                    val cid = courseBlock.optLong("id").toString()
                    val cTitle = courseTitles[cid] ?: courseBlock.optString("fullname", "Курс $cid")
                    val items = courseBlock.optJSONArray("assignments") ?: JSONArray()
                    assignCountPerCourse[cid] = items.length().toLong()
                    for (j in 0 until items.length()) {
                        val a = items.getJSONObject(j)
                        val aid = a.optLong("id").toString()
                        val cmid = a.optLong("cmid", a.optLong("id")).toString()
                        val dueSec = a.optLong("duedate", 0L)
                        val dueMillis = if (dueSec > 0L) dueSec * 1000L else 0L
                        val dueText = if (dueMillis > 0L) formatEpoch(dueMillis) else "Без срока"
                        val intro = Jsoup.parse(a.optString("intro", "")).text()
                        val attachList = mutableListOf<String>()
                        val introFiles = a.optJSONArray("introattachments")
                        if (introFiles != null) {
                            for (k in 0 until introFiles.length()) {
                                val f = introFiles.getJSONObject(k)
                                attachList.add("${f.optString("filename")}|${f.optString("fileurl")}?token=${session.wsToken}")
                            }
                        }
                        val isSubmittedWs = runCatching {
                            val stRaw = callWebService(
                                session.baseUrl,
                                session.wsToken,
                                "mod_assign_get_submission_status",
                                mapOf("assignid" to aid)
                            )
                            val stObj = JSONObject(stRaw)
                            val subStatus = stObj.optJSONObject("lastattempt")
                                ?.optJSONObject("submission")
                                ?.optString("status", "")
                                .orEmpty()
                            subStatus.equals("submitted", ignoreCase = true)
                        }.getOrDefault(false)

                        val status = when {
                            isSubmittedWs -> AssignmentStatus.SUBMITTED.name
                            dueMillis in 1 until System.currentTimeMillis() -> AssignmentStatus.OVERDUE.name
                            else -> AssignmentStatus.PENDING.name
                        }
                        assignments.add(
                            AssignmentRecord(
                                id = "assign_$aid",
                                userId = userId,
                                moodleAssignId = aid,
                                moodleCourseId = cid,
                                courseTitle = cTitle.take(290),
                                title = a.optString("name", "Задание $aid").take(390),
                                description = intro.take(19500),
                                dueDateText = dueText,
                                dueEpochMillis = dueMillis,
                                status = status,
                                subjectCategory = inferSubject(cTitle),
                                assignmentUrl = "${session.baseUrl}/mod/assign/view.php?id=$cmid",
                                attachmentsSummary = attachList.joinToString("\n").take(3900),
                                submittedByUser = isSubmittedWs,
                            )
                        )
                    }
                }
            }
        }

        val updatedCourses = courses.map {
            it.copy(assignmentCount = assignCountPerCourse[it.moodleCourseId] ?: 0L)
        }
        return Result.success(
            MoodleSyncSnapshot(
                displayName = displayName,
                moodleUserId = moodleUid,
                sesskey = "",
                courses = updatedCourses,
                assignments = assignments,
            )
        )
    }

    private suspend fun syncViaBrowserSession(
        userId: String,
        session: MoodleSessionCredentials,
    ): Result<MoodleSyncSnapshot> {
        val cleanBase = session.baseUrl.trimEnd('/')
        val coursesReq = Request.Builder()
            .url("$cleanBase/my/courses.php")
            .header("Cookie", session.cookieHeader)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val (finalUrl, html) = executeForUrlAndBody(coursesReq).getOrThrow()
        if (isLoginPage(finalUrl, html)) {
            return Result.failure(
                MoodleAuthExpiredException("Сессия Moodle истекла. Пожалуйста, войдите снова через браузер.")
            )
        }

        val sesskey = extractSesskey(html).ifBlank { session.sesskey }
        val doc = Jsoup.parse(html, cleanBase)
        val displayName = doc.selectFirst(".usertext, .usermenu .userbutton")?.text()?.trim()
            ?.ifBlank { null } ?: session.moodleDisplayName.ifBlank { "Студент Moodle" }
        val moodleUid = doc.selectFirst("[data-userid]")?.attr("data-userid").orEmpty()

        val parsedCourses = mutableMapOf<String, SyncedCourse>()

        // 1. Try Moodle 4.x core AJAX endpoint if sesskey is available
        if (sesskey.isNotBlank()) {
            val ajaxCourses = fetchCoursesViaMoodleAjax(cleanBase, session.cookieHeader, sesskey, userId)
            ajaxCourses.forEach { parsedCourses[it.moodleCourseId] = it }
        }

        // 2. Also parse HTML links from /my/courses.php and /my/
        extractCoursesFromHtml(doc, cleanBase, userId).forEach {
            parsedCourses.putIfAbsent(it.moodleCourseId, it)
        }
        if (parsedCourses.isEmpty()) {
            val myReq = Request.Builder()
                .url("$cleanBase/my/")
                .header("Cookie", session.cookieHeader)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val (_, myHtml) = executeForUrlAndBody(myReq).getOrElse { "" to "" }
            if (myHtml.isNotBlank()) {
                extractCoursesFromHtml(Jsoup.parse(myHtml, cleanBase), cleanBase, userId).forEach {
                    parsedCourses.putIfAbsent(it.moodleCourseId, it)
                }
            }
        }

        val assignments = mutableMapOf<String, AssignmentRecord>()
        val courseList = parsedCourses.values.toList().take(20)
        val countByCourse = mutableMapOf<String, Long>()

        // 3. Scan each course page for assignments & scan assignment details
        for (course in courseList) {
            try {
                val cReq = Request.Builder()
                    .url(course.courseUrl)
                    .header("Cookie", session.cookieHeader)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                val (_, courseHtml) = executeForUrlAndBody(cReq).getOrNull() ?: continue
                val cDoc = Jsoup.parse(courseHtml, cleanBase)
                val assignLinks = cDoc.select("a[href*=\"/mod/assign/view.php?id=\"]")
                var courseAssignCount = 0L
                val seenAssignIds = mutableSetOf<String>()

                for (link in assignLinks) {
                    val href = link.absUrl("href").ifBlank { link.attr("href") }
                    val cmid = extractQueryParam(href, "id") ?: continue
                    if (!seenAssignIds.add(cmid)) continue
                    courseAssignCount++

                    val rawTitle = link.selectFirst(".instancename")?.ownText()?.trim()
                        ?: link.text().trim()
                    val title = rawTitle.ifBlank { "Задание #$cmid" }

                    val deep = fetchAssignmentDetailsInternal(cleanBase, session.cookieHeader, href)
                    val dueMillis = deep?.dueEpochMillis ?: 0L
                    val isSubmitted = deep?.isAlreadySubmitted == true
                    val status = when {
                        isSubmitted -> AssignmentStatus.SUBMITTED.name
                        dueMillis in 1 until System.currentTimeMillis() -> AssignmentStatus.OVERDUE.name
                        else -> AssignmentStatus.PENDING.name
                    }
                    val attachSummary = deep?.attachments.orEmpty()
                        .joinToString("\n") { "${it.fileName}|${it.url}" }

                    assignments["assign_$cmid"] = AssignmentRecord(
                        id = "assign_$cmid",
                        userId = userId,
                        moodleAssignId = cmid,
                        moodleCourseId = course.moodleCourseId,
                        courseTitle = course.title.take(290),
                        title = title.take(390),
                        description = (deep?.description ?: "").take(19500),
                        dueDateText = deep?.dueDateText ?: "Уточняется в Moodle",
                        dueEpochMillis = dueMillis,
                        status = status,
                        subjectCategory = inferSubject(course.title),
                        assignmentUrl = href,
                        attachmentsSummary = attachSummary.take(3900),
                        submittedByUser = isSubmitted,
                    )
                }
                countByCourse[course.moodleCourseId] = courseAssignCount
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error scanning course ${course.moodleCourseId}: ${e.message}")
            }
        }

        val updatedCourses = courseList.map {
            it.copy(assignmentCount = countByCourse[it.moodleCourseId] ?: 0L)
        }

        return Result.success(
            MoodleSyncSnapshot(
                displayName = displayName,
                moodleUserId = moodleUid,
                sesskey = sesskey,
                courses = updatedCourses,
                assignments = assignments.values.toList(),
            )
        )
    }

    suspend fun fetchAssignmentDetailsAndAttachments(
        session: MoodleSessionCredentials,
        assignmentUrl: String,
        attachmentsSummary: String,
    ): Result<AssignmentDeepDetails> = withContext(Dispatchers.IO) {
        try {
            val deep = if (session.mode == MoodleConnectionMode.BROWSER_SESSION) {
                fetchAssignmentDetailsInternal(
                    session.baseUrl.trimEnd('/'),
                    session.cookieHeader,
                    assignmentUrl
                )
            } else {
                null
            }

            val baseAttachments = deep?.attachments ?: parseAttachmentsSummary(attachmentsSummary)
            val enrichedAttachments = baseAttachments.take(4).map { att ->
                downloadAndExtractAttachment(session, att)
            }

            Result.success(
                AssignmentDeepDetails(
                    description = deep?.description.orEmpty(),
                    dueDateText = deep?.dueDateText ?: "Срок в Moodle",
                    dueEpochMillis = deep?.dueEpochMillis ?: 0L,
                    isAlreadySubmitted = deep?.isAlreadySubmitted ?: false,
                    attachments = enrichedAttachments,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitAssignmentOnlineText(
        session: MoodleSessionCredentials,
        assignment: AssignmentRecord,
        confirmedAnswer: String,
    ): Result<SubmissionOutcome> = withContext(Dispatchers.IO) {
        try {
            if (session.mode == MoodleConnectionMode.WEB_SERVICE_API) {
                val resp = callWebService(
                    session.baseUrl,
                    session.wsToken,
                    "mod_assign_save_submission",
                    mapOf(
                        "assignmentid" to assignment.moodleAssignId,
                        "plugindata[onlinetext_editor][text]" to confirmedAnswer.replace("\n", "<br/>"),
                        "plugindata[onlinetext_editor][format]" to "1",
                        "plugindata[onlinetext_editor][itemid]" to "0",
                    )
                )
                val trimmed = resp.trim()
                if (trimmed.startsWith("{") && JSONObject(trimmed).has("exception")) {
                    val msg = JSONObject(trimmed).optString("message", "Ошибка отправки через WS API")
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                return@withContext Result.success(
                    SubmissionOutcome.Submitted("Черновик ответа успешно сохранён в Moodle через Web Services API.")
                )
            }

            val editUrl = if (assignment.assignmentUrl.contains("?")) {
                "${assignment.assignmentUrl}&action=editsubmission"
            } else {
                "${assignment.assignmentUrl}?action=editsubmission"
            }

            val getReq = Request.Builder()
                .url(editUrl)
                .header("Cookie", session.cookieHeader)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val (finalUrl, html) = executeForUrlAndBody(getReq).getOrThrow()
            if (isLoginPage(finalUrl, html)) {
                return@withContext Result.failure(
                    MoodleAuthExpiredException("Сессия Moodle истекла. Авторизуйтесь заново.")
                )
            }

            val doc = Jsoup.parse(html, session.baseUrl.trimEnd('/'))
            val form = doc.select("form").firstOrNull {
                it.select("textarea[name*=onlinetext_editor]").isNotEmpty()
            }

            if (form == null) {
                return@withContext Result.success(
                    SubmissionOutcome.RequiresBrowserForm(
                        editUrl = editUrl,
                        prefillAnswer = confirmedAnswer,
                        reason = "Это задание использует загрузку файла или специальную форму Moodle. Откроем форму с вашим ответом."
                    )
                )
            }

            val actionUrl = form.absUrl("action").ifBlank { editUrl }
            val formBuilder = FormBody.Builder()
            for (input in form.select("input")) {
                val name = input.attr("name")
                if (name.isBlank()) continue
                if (name.startsWith("onlinetext_editor")) continue
                val type = input.attr("type").lowercase()
                if (type == "button" || type == "reset") continue
                if (type == "submit" && name != "submitbutton") continue
                if ((type == "checkbox" || type == "radio") && !input.hasAttr("checked")) continue
                formBuilder.add(name, input.attr("value"))
            }
            for (select in form.select("select")) {
                val name = select.attr("name")
                if (name.isBlank() || name.startsWith("onlinetext_editor")) continue
                val selected = select.selectFirst("option[selected]") ?: select.selectFirst("option")
                if (selected != null) {
                    formBuilder.add(name, selected.attr("value"))
                }
            }
            formBuilder.add("onlinetext_editor[text]", "<p>${confirmedAnswer.replace("\n", "<br/>")}</p>")
            formBuilder.add("onlinetext_editor[format]", "1")

            val postReq = Request.Builder()
                .url(actionUrl)
                .header("Cookie", session.cookieHeader)
                .header("User-Agent", USER_AGENT)
                .post(formBuilder.build())
                .build()

            val (postUrl, postHtml) = executeForUrlAndBody(postReq).getOrThrow()
            val postDoc = Jsoup.parse(postHtml)
            val statusText = postDoc.select(".submissionstatustable").text()
            if (postUrl.contains("mod/assign/view.php") &&
                (statusText.contains("Submitted", ignoreCase = true) ||
                    statusText.contains("Iesniegts", ignoreCase = true) ||
                    statusText.contains("Отправлено", ignoreCase = true) ||
                    statusText.contains("Draft", ignoreCase = true))
            ) {
                Result.success(
                    SubmissionOutcome.Submitted("Ответ сохранён и отправлен в Moodle!")
                )
            } else {
                Result.success(
                    SubmissionOutcome.RequiresBrowserForm(
                        editUrl = editUrl,
                        prefillAnswer = confirmedAnswer,
                        reason = "Moodle требует дополнительного подтверждения на странице задания."
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Submission failed", e)
            Result.failure(e)
        }
    }

    private fun fetchCoursesViaMoodleAjax(
        baseUrl: String,
        cookieHeader: String,
        sesskey: String,
        userId: String,
    ): List<SyncedCourse> {
        return try {
            val payload = JSONArray().put(
                JSONObject().apply {
                    put("index", 0)
                    put("methodname", "core_course_get_enrolled_courses_by_timeline_classification")
                    put("args", JSONObject().apply {
                        put("offset", 0)
                        put("limit", 50)
                        put("classification", "all")
                        put("sort", "fullname")
                    })
                }
            ).toString()

            val req = Request.Builder()
                .url("$baseUrl/lib/ajax/service.php?sesskey=$sesskey&info=core_course_get_enrolled_courses_by_timeline_classification")
                .header("Cookie", cookieHeader)
                .header("User-Agent", USER_AGENT)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string().orEmpty()
                val arr = JSONArray(text)
                if (arr.length() == 0) return emptyList()
                val first = arr.getJSONObject(0)
                if (first.optBoolean("error", true)) return emptyList()
                val coursesArr = first.getJSONObject("data").optJSONArray("courses") ?: return emptyList()
                val out = mutableListOf<SyncedCourse>()
                for (i in 0 until coursesArr.length()) {
                    val c = coursesArr.getJSONObject(i)
                    val cid = c.optLong("id").toString()
                    out.add(
                        SyncedCourse(
                            id = "course_$cid",
                            userId = userId,
                            moodleCourseId = cid,
                            title = Jsoup.parse(c.optString("fullname", "Курс $cid")).text().take(290),
                            shortName = c.optString("shortname", "").take(110),
                            category = c.optString("coursecategory", "").take(140),
                            courseUrl = c.optString("viewurl", "$baseUrl/course/view.php?id=$cid"),
                            progressPercent = c.optLong("progress", 0L).coerceIn(0L, 100L),
                            assignmentCount = 0L,
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "AJAX courses fetch fallback triggered: ${e.message}")
            emptyList()
        }
    }

    private fun extractCoursesFromHtml(
        doc: org.jsoup.nodes.Document,
        baseUrl: String,
        userId: String,
    ): List<SyncedCourse> {
        val map = linkedMapOf<String, SyncedCourse>()
        for (el in doc.select("a[href*=\"/course/view.php?id=\"]")) {
            val href = el.absUrl("href").ifBlank { el.attr("href") }
            val cid = extractQueryParam(href, "id") ?: continue
            if (cid == "1") continue // Skip front page site course
            val title = el.text().trim()
            if (title.length < 2) continue
            map.putIfAbsent(
                cid,
                SyncedCourse(
                    id = "course_$cid",
                    userId = userId,
                    moodleCourseId = cid,
                    title = title.take(290),
                    shortName = "",
                    category = "",
                    courseUrl = "$baseUrl/course/view.php?id=$cid",
                    progressPercent = 0L,
                    assignmentCount = 0L,
                )
            )
        }
        return map.values.toList()
    }

    private fun fetchAssignmentDetailsInternal(
        baseUrl: String,
        cookieHeader: String,
        url: String,
    ): AssignmentDeepDetails? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(html, baseUrl)
                val desc = doc.selectFirst("#intro, .activity-description, .assign-intro, .generalbox")
                    ?.text()?.trim().orEmpty()

                var dueDateText = ""
                var isSubmitted = false
                for (row in doc.select(".submissionstatustable tr, .activity-dates div, [data-region=activity-dates] div")) {
                    val rowText = row.text().trim()
                    if (rowText.contains("Due", ignoreCase = true) ||
                        rowText.contains("Termiņš", ignoreCase = true) ||
                        rowText.contains("Līdz", ignoreCase = true) ||
                        rowText.contains("Срок", ignoreCase = true)
                    ) {
                        dueDateText = row.select("td").lastOrNull()?.text()?.trim()
                            ?: rowText
                    }
                    if (rowText.contains("Submitted for grading", ignoreCase = true) ||
                        rowText.contains("Iesniegts vērtēšanai", ignoreCase = true) ||
                        rowText.contains("Отправлено для оценивания", ignoreCase = true)
                    ) {
                        isSubmitted = true
                    }
                }

                val attachments = doc.select("a[href*=\"pluginfile.php\"]").mapNotNull { a ->
                    val fileUrl = a.absUrl("href").ifBlank { a.attr("href") }
                    val name = a.text().trim().ifBlank { fileUrl.substringAfterLast('/').substringBefore('?') }
                    if (name.isBlank()) null
                    else MoodleAttachment(
                        fileName = name,
                        url = fileUrl,
                        extension = name.substringAfterLast('.', "").lowercase()
                    )
                }.distinctBy { it.url }

                AssignmentDeepDetails(
                    description = desc,
                    dueDateText = dueDateText.ifBlank { "Срок не указан" },
                    dueEpochMillis = parseDueEpoch(dueDateText),
                    isAlreadySubmitted = isSubmitted,
                    attachments = attachments,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect assignment $url: ${e.message}")
            null
        }
    }

    private fun downloadAndExtractAttachment(
        session: MoodleSessionCredentials,
        attachment: MoodleAttachment,
    ): MoodleAttachment {
        return try {
            val reqBuilder = Request.Builder()
                .url(attachment.url)
                .header("User-Agent", USER_AGENT)
            if (session.cookieHeader.isNotBlank()) {
                reqBuilder.header("Cookie", session.cookieHeader)
            }
            val bytes = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return attachment
                val length = resp.body?.contentLength() ?: 0L
                if (length > MAX_ATTACHMENT_BYTES) return attachment
                resp.body?.bytes() ?: return attachment
            }
            if (bytes.size > MAX_ATTACHMENT_BYTES) return attachment

            when (attachment.extension) {
                "docx" -> {
                    val extracted = extractDocxText(bytes)
                    attachment.copy(extractedText = extracted)
                }
                "txt", "csv", "md", "html", "htm", "py", "java", "kt", "js" -> {
                    attachment.copy(extractedText = String(bytes, Charsets.UTF_8).take(15000))
                }
                "png", "jpg", "jpeg", "webp" -> {
                    val bmp = decodeScaledBitmap(bytes, 1280)
                    attachment.copy(images = listOfNotNull(bmp))
                }
                "pdf" -> {
                    val pages = renderPdfPages(bytes, maxPages = 4)
                    attachment.copy(
                        extractedText = "[PDF-файл: отрендерено ${pages.size} стр. для анализа Gemini]",
                        images = pages
                    )
                }
                else -> attachment
            }
        } catch (e: Exception) {
            Log.w(TAG, "Attachment extraction failed for ${attachment.fileName}: ${e.message}")
            attachment
        }
    }

    private fun extractDocxText(bytes: ByteArray): String {
        return try {
            val sb = StringBuilder()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = zis.readBytes().toString(Charsets.UTF_8)
                        sb.append(Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser()).text())
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            sb.toString().take(15000)
        } catch (_: Exception) {
            ""
        }
    }

    private fun renderPdfPages(bytes: ByteArray, maxPages: Int): List<Bitmap> {
        val tempFile = File.createTempFile("moodle_att_", ".pdf", context.cacheDir)
        return try {
            tempFile.writeBytes(bytes)
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = minOf(renderer.pageCount, maxPages)
                    val list = mutableListOf<Bitmap>()
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            val scale = minOf(1.5f, 1100f / maxOf(1, page.width))
                            val w = maxOf(1, (page.width * scale).toInt())
                            val h = maxOf(1, (page.height * scale).toInt())
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            list.add(bitmap)
                        }
                    }
                    list
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            tempFile.delete()
        }
    }

    private fun decodeScaledBitmap(bytes: ByteArray, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    private suspend fun callWebService(
        baseUrl: String,
        wsToken: String,
        wsFunction: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val form = FormBody.Builder()
            .add("wstoken", wsToken)
            .add("wsfunction", wsFunction)
            .add("moodlewsrestformat", "json")
        params.forEach { (k, v) -> form.add(k, v) }

        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/webservice/rest/server.php")
            .header("User-Agent", USER_AGENT)
            .post(form.build())
            .build()
        return executeWithRetry(req).getOrThrow()
    }

    private suspend fun executeWithRetry(request: Request): Result<String> {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        return Result.success(body)
                    }
                    if (resp.code in 400..499) {
                        return Result.failure(IOException("HTTP ${resp.code}: ${resp.message}"))
                    }
                    lastError = IOException("HTTP ${resp.code}: ${resp.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            delay(500L * (attempt + 1))
        }
        return Result.failure(lastError ?: IOException("Сетевая ошибка при обращении к Moodle."))
    }

    private suspend fun executeForUrlAndBody(request: Request): Result<Pair<String, String>> {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                httpClient.newCall(request).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        return Result.success(finalUrl to body)
                    }
                    if (resp.code in 400..499) {
                        return Result.failure(IOException("HTTP ${resp.code}: ${resp.message}"))
                    }
                    lastError = IOException("HTTP ${resp.code}: ${resp.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            delay(500L * (attempt + 1))
        }
        return Result.failure(lastError ?: IOException("Не удалось загрузить страницу Moodle."))
    }

    private fun isLoginPage(url: String, html: String): Boolean {
        return url.contains("/login/index.php") ||
            (html.contains("id=\"login\"") && html.contains("name=\"password\""))
    }

    internal fun extractSesskey(html: String): String {
        val regex = Regex("\"sesskey\"\\s*:\\s*\"([a-zA-Z0-9]+)\"")
        return regex.find(html)?.groupValues?.getOrNull(1)
            ?: Regex("name=\"sesskey\"\\s+value=\"([a-zA-Z0-9]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("sesskey=([a-zA-Z0-9]+)").find(html)?.groupValues?.getOrNull(1)
            ?: ""
    }

    private fun extractQueryParam(url: String, param: String): String? {
        return Regex("[?&]$param=([0-9a-zA-Z_-]+)").find(url)?.groupValues?.getOrNull(1)
    }

    private fun inferSubject(courseTitle: String): String {
        val lower = courseTitle.lowercase()
        return when {
            lower.contains("matem") || lower.contains("матем") || lower.contains("algebr") -> "Математика"
            lower.contains("angl") || lower.contains("english") || lower.contains("англ") -> "Английский язык"
            lower.contains("latv") || lower.contains("латыш") -> "Латышский язык"
            lower.contains("vēst") || lower.contains("hist") || lower.contains("истор") -> "История"
            lower.contains("ģeogr") || lower.contains("geogr") || lower.contains("геогр") -> "География"
            lower.contains("biolo") || lower.contains("биол") -> "Биология"
            lower.contains("fizik") || lower.contains("phys") || lower.contains("физик") -> "Физика"
            lower.contains("ķīm") || lower.contains("chem") || lower.contains("хим") -> "Химия"
            else -> "Учебный предмет"
        }
    }

    private fun parseAttachmentsSummary(summary: String): List<MoodleAttachment> {
        if (summary.isBlank()) return emptyList()
        return summary.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) {
                val name = parts[0].trim()
                MoodleAttachment(
                    fileName = name,
                    url = parts[1].trim(),
                    extension = name.substringAfterLast('.', "").lowercase()
                )
            } else null
        }
    }

    private fun formatEpoch(millis: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return sdf.format(millis)
    }

    private fun parseDueEpoch(text: String): Long {
        if (text.isBlank()) return 0L
        val patterns = listOf(
            "EEEE, d MMMM yyyy, h:mm a",
            "d MMMM yyyy, HH:mm",
            "dd.MM.yyyy HH:mm",
            "yyyy-MM-dd HH:mm",
            "d. MMMM yyyy, HH:mm",
        )
        for (pattern in patterns) {
            for (locale in listOf(Locale.ENGLISH, Locale("lv", "LV"), Locale("ru", "RU"))) {
                try {
                    val parsed = SimpleDateFormat(pattern, locale).parse(text.trim())
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {
                }
            }
        }
        return 0L
    }
}
