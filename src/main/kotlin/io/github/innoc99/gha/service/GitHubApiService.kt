package io.github.innoc99.gha.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.innoc99.gha.GhaBundle
import io.github.innoc99.gha.model.*
import io.github.innoc99.gha.settings.GitHubActionsGlobalSettings
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.time.Instant
import java.util.Base64

/** 사용자 액션 POST 결과. 실패 시 [errorMessage]에 서버 응답 사유(알림 HTML용으로 이스케이프됨)를 담는다. */
data class PostResult(val success: Boolean, val errorMessage: String? = null)

/**
 * GitHub / GitHub Enterprise API 연동 서비스
 */
@Service(Service.Level.PROJECT)
class GitHubApiService(private val project: Project) {

    private val logger = Logger.getInstance(GitHubApiService::class.java)
    private val gson = Gson()
    private val globalSettings = GitHubActionsGlobalSettings.getInstance()
    private val accountManager = service<GHAccountManager>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val healthCheckClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private val connectionState by lazy {
        ConnectionStateManager.getInstance(project).also {
            it.setHealthChecker { healthCheck() }
        }
    }

    private fun getRemoteInfo(): GitRemoteInfo? {
        return GitRemoteDetector.detect(project)
    }

    /**
     * 설정에 따라 토큰을 해석합니다.
     * - useGitHubAccountSettings == true → IntelliJ GitHub 계정에서 토큰 조회
     * - useGitHubAccountSettings == false → 직접 입력한 PAT 사용
     * 주의: suspend 함수를 runBlocking으로 호출하므로 background thread에서만 실행해야 합니다.
     */
    fun resolveToken(): String? {
        val state = globalSettings.state
        if (!state.useGitHubAccountSettings) {
            return globalSettings.getPersonalAccessToken()
        }

        val info = getRemoteInfo() ?: return null
        val host = java.net.URI(info.baseUrl).host

        val account = GHAccountsUtil.accounts.firstOrNull { it.server.host == host }
            ?: return null

        return try {
            runBlocking { accountManager.findCredentials(account) }
        } catch (e: Exception) {
            logger.warn("IntelliJ GitHub 계정에서 토큰 조회 실패", e)
            null
        }
    }

    /**
     * 유효한 토큰이 존재하는지 확인합니다 (EDT-safe, 동기).
     * - useGitHubAccountSettings == true → 매칭 계정 존재 여부만 확인
     * - useGitHubAccountSettings == false → PAT 비어있지 않은지만 확인
     */
    fun hasValidToken(): Boolean {
        val state = globalSettings.state
        if (!state.useGitHubAccountSettings) {
            return state.hasPersonalAccessToken
        }

        val info = getRemoteInfo() ?: return false
        val host = java.net.URI(info.baseUrl).host
        return GHAccountsUtil.accounts.any { it.server.host == host }
    }

    /**
     * 경량 health-check (GET {apiUrl}/rate_limit)
     * @return 네트워크 + 인증 정상이면 true
     */
    fun healthCheck(): Boolean {
        val token = resolveToken()
        val info = getRemoteInfo() ?: return false
        if (token.isNullOrBlank()) return false

        val url = "${info.apiUrl}/rate_limit"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "token $token")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        return try {
            healthCheckClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    private fun buildRequest(endpoint: String, postJson: String? = null): Request? {
        val token = resolveToken()
        val info = getRemoteInfo() ?: return null
        if (token.isNullOrBlank()) return null

        val url = "${info.apiUrl}/repos/${info.owner}/${info.repository}$endpoint"

        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "token $token")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .apply { if (postJson != null) post(postJson.toRequestBody(JSON_MEDIA_TYPE)) }
            .build()
    }

    /** @return 실패 시 null (빈 목록과 구분) */
    fun fetchWorkflows(): List<Workflow>? {
        val allWorkflows = mutableListOf<Workflow>()
        var page = 1

        while (true) {
            val request = buildRequest("/actions/workflows?per_page=100&page=$page") ?: return null
            val pageResult = executeRequest(request) { response ->
                val json = gson.fromJson(response, JsonObject::class.java)
                val workflows = json.getAsJsonArray("workflows")
                workflows.map { workflowJson ->
                    val obj = workflowJson.asJsonObject
                    Workflow(
                        id = obj.get("id").asLong,
                        name = obj.get("name").asString,
                        path = obj.get("path").asString,
                        state = obj.get("state").asString,
                        createdAt = Instant.parse(obj.get("created_at").asString),
                        updatedAt = Instant.parse(obj.get("updated_at").asString)
                    )
                }
            } ?: return null

            allWorkflows.addAll(pageResult)
            if (pageResult.size < 100) break
            page++
        }

        return allWorkflows
    }

    /**
     * @param workflowId 지정하면 해당 워크플로우의 Run만 조회 (전체 최근 N개에서 거르면 드물게 도는 워크플로우가 누락됨)
     * @param status 지정하면 해당 상태/결론(success 등)의 Run만 조회
     * @return 실패 시 null (빈 목록과 구분)
     */
    fun fetchWorkflowRuns(workflowId: Long? = null, limit: Int = 30, status: String? = null): List<WorkflowRun>? {
        val path = if (workflowId != null) "/actions/workflows/$workflowId/runs" else "/actions/runs"
        val statusQuery = status?.let { "&status=$it" } ?: ""
        val request = buildRequest("$path?per_page=$limit$statusQuery") ?: return null
        return executeRequest(request) { response ->
            gson.fromJson(response, JsonObject::class.java).getAsJsonArray("workflow_runs").map { parseRun(it.asJsonObject) }
        }
    }

    private fun parseRun(obj: JsonObject) = WorkflowRun(
        id = obj.get("id").asLong,
        name = obj.get("name").asString,
        workflowId = obj.get("workflow_id").asLong,
        status = obj.get("status").asString,
        conclusion = obj.get("conclusion")?.takeUnless { it.isJsonNull }?.asString,
        htmlUrl = obj.get("html_url").asString,
        createdAt = Instant.parse(obj.get("created_at").asString),
        updatedAt = Instant.parse(obj.get("updated_at").asString),
        headBranch = obj.get("head_branch")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        headSha = obj.get("head_sha").asString,
        event = obj.get("event").asString,
        runNumber = obj.get("run_number").asInt,
        runAttempt = obj.get("run_attempt")?.takeUnless { it.isJsonNull }?.asInt ?: 1,
        actor = obj.get("actor")?.takeUnless { it.isJsonNull }?.asJsonObject?.get("login")?.asString
    )

    /**
     * 최근 Git 태그 중 배포 버전 태그만 반환 (태그 → 커밋 sha 매핑)
     * @return 실패 시 null
     */
    fun fetchVersionTags(): List<VersionTag>? {
        val tags = mutableListOf<VersionTag>()
        // 모노레포는 모듈별 태그가 섞이므로 한 페이지로는 일부 모듈 버전이 빠질 수 있음
        // ponytail: 최대 300개 — 태그가 더 많으면 git/matching-refs 기반 prefix 조회로 전환
        for (page in 1..TAG_MAX_PAGES) {
            val request = buildRequest("/tags?per_page=100&page=$page") ?: return null
            val pageTags = executeRequest(request) { response ->
                gson.fromJson(response, JsonArray::class.java)
            } ?: return if (page == 1) null else tags
            pageTags.mapNotNullTo(tags) {
                val obj = it.asJsonObject
                VersionTag.parse(obj.get("name").asString, obj.getAsJsonObject("commit").get("sha").asString)
            }
            if (pageTags.size() < 100) break
        }
        return tags
    }

    /** @return 실패 시 null (빈 목록과 구분) */
    /** @param userAction Run 클릭 등 사용자 동작이면 OFFLINE이어도 health-check 후 시도 */
    fun fetchWorkflowJobs(runId: Long, userAction: Boolean = false): List<WorkflowJob>? {
        val request = buildRequest("/actions/runs/$runId/jobs") ?: return null
        return executeRequest(request, isUserAction = userAction) { response ->
            val json = gson.fromJson(response, JsonObject::class.java)
            val jobs = json.getAsJsonArray("jobs")
            jobs.map { jobJson ->
                val obj = jobJson.asJsonObject
                val steps = obj.getAsJsonArray("steps")?.map { stepJson ->
                    val stepObj = stepJson.asJsonObject
                    WorkflowStep(
                        name = stepObj.get("name").asString,
                        status = stepObj.get("status").asString,
                        conclusion = stepObj.get("conclusion")?.let {
                            if (it.isJsonNull) null else it.asString
                        },
                        number = stepObj.get("number").asInt,
                        startedAt = stepObj.get("started_at")?.let {
                            if (it.isJsonNull) null else Instant.parse(it.asString)
                        },
                        completedAt = stepObj.get("completed_at")?.let {
                            if (it.isJsonNull) null else Instant.parse(it.asString)
                        }
                    )
                } ?: emptyList()

                WorkflowJob(
                    id = obj.get("id").asLong,
                    runId = obj.get("run_id").asLong,
                    name = obj.get("name").asString,
                    status = obj.get("status").asString,
                    conclusion = obj.get("conclusion")?.let {
                        if (it.isJsonNull) null else it.asString
                    },
                    startedAt = obj.get("started_at")?.let {
                        if (it.isJsonNull) null else Instant.parse(it.asString)
                    },
                    completedAt = obj.get("completed_at")?.let {
                        if (it.isJsonNull) null else Instant.parse(it.asString)
                    },
                    steps = steps
                )
            }
        }
    }

    /**
     * 워크플로우 YAML에서 workflow_dispatch inputs 파싱
     */
    /**
     * 사용자 액션(Dispatch 다이얼로그) — OFFLINE이어도 health-check 후 시도
     * @return 조회 실패 시 null. 빈 목록으로 돌려주면 inputs 없이 Dispatch가 나가 배포 사고가 될 수 있다.
     */
    fun fetchDispatchInputs(workflowPath: String): List<DispatchInput>? {
        if (workflowPath.isBlank()) return null
        val request = buildRequest("/contents/$workflowPath") ?: return null
        val yamlContent = executeRequest(request, isUserAction = true) { response ->
            val json = gson.fromJson(response, JsonObject::class.java)
            val content = json.get("content")?.asString ?: return@executeRequest null
            String(Base64.getMimeDecoder().decode(content))
        }
        if (yamlContent == null) {
            logger.warn("fetchDispatchInputs: YAML 콘텐츠 조회 실패 (path=$workflowPath)")
            return null
        }
        return parseDispatchInputs(yamlContent)
    }

    /**
     * YAML 문자열에서 workflow_dispatch inputs 추출
     */
    private fun parseDispatchInputs(yamlContent: String): List<DispatchInput> {
        try {
            val yaml = Yaml()
            @Suppress("UNCHECKED_CAST")
            val doc = yaml.load(yamlContent) as? Map<Any, Any> ?: return emptyList()

            // on 키 추출 (SnakeYAML은 on을 boolean true로 파싱)
            val onSection = (doc["on"] ?: doc[true] ?: doc["true"]) as? Map<*, *> ?: return emptyList()
            val dispatchSection = onSection["workflow_dispatch"] as? Map<*, *> ?: return emptyList()
            val inputsSection = dispatchSection["inputs"] as? Map<*, *> ?: return emptyList()

            return inputsSection.map { (key, value) ->
                val inputMap = value as? Map<*, *> ?: emptyMap<String, Any>()
                val typeStr = inputMap["type"]?.toString() ?: "string"
                val options = (inputMap["options"] as? List<*>)?.map { it.toString() } ?: emptyList()

                DispatchInput(
                    name = key.toString(),
                    description = inputMap["description"]?.toString() ?: "",
                    required = inputMap["required"] as? Boolean ?: false,
                    default = inputMap["default"]?.toString(),
                    type = when (typeStr) {
                        "choice" -> DispatchInput.InputType.CHOICE
                        "boolean" -> DispatchInput.InputType.BOOLEAN
                        "environment" -> DispatchInput.InputType.ENVIRONMENT
                        else -> DispatchInput.InputType.STRING
                    },
                    options = options
                )
            }
        } catch (e: Exception) {
            logger.warn("workflow_dispatch inputs 파싱 실패", e)
            return emptyList()
        }
    }

    /**
     * Job의 annotations (`::error::`, `::warning::` 등) — Job id는 check run id와 같다
     * @return 실패 시 null
     */
    fun fetchJobAnnotations(jobId: Long): List<JobAnnotation>? {
        val request = buildRequest("/check-runs/$jobId/annotations") ?: return null
        return executeRequest(request, isUserAction = true) { response ->
            gson.fromJson(response, JsonArray::class.java).map {
                val obj = it.asJsonObject
                JobAnnotation(
                    level = obj.get("annotation_level")?.takeUnless { el -> el.isJsonNull }?.asString ?: "notice",
                    title = obj.get("title")?.takeUnless { el -> el.isJsonNull }?.asString.orEmpty(),
                    message = obj.get("message")?.takeUnless { el -> el.isJsonNull }?.asString.orEmpty()
                )
            }
        }
    }

    fun rerunWorkflowRun(runId: Long): PostResult = executePost("/actions/runs/$runId/rerun")

    fun rerunFailedJobs(runId: Long): PostResult = executePost("/actions/runs/$runId/rerun-failed-jobs")

    fun cancelWorkflowRun(runId: Long): PostResult = executePost("/actions/runs/$runId/cancel")

    /** 사용자 액션(Job 선택) — OFFLINE이어도 health-check 후 시도 */
    fun fetchJobLogs(jobId: Long): String? {
        val request = buildRequest("/actions/jobs/$jobId/logs") ?: return null
        return executeRequest(request, isUserAction = true) { it }
    }

    /**
     * 워크플로우 수동 실행 (workflow_dispatch)
     */
    fun dispatchWorkflow(workflowId: Long, ref: String, inputs: Map<String, String> = emptyMap()): PostResult {
        val payload = JsonObject().apply {
            addProperty("ref", ref)
            if (inputs.isNotEmpty()) {
                add("inputs", JsonObject().apply { inputs.forEach { (k, v) -> addProperty(k, v) } })
            }
        }
        return executePost("/actions/workflows/$workflowId/dispatches", gson.toJson(payload))
    }

    /**
     * 사용자 액션 POST. OFFLINE이면 health-check 후 시도한다.
     * 4xx는 서버가 응답한 것이므로 네트워크 실패로 기록하지 않는다.
     */
    private fun executePost(endpoint: String, jsonBody: String = "{}"): PostResult {
        if (!connectionState.isOnline) {
            if (!healthCheck()) return PostResult(false, GhaBundle.message("api.offline"))
            connectionState.recordSuccess()
        }
        val request = buildRequest(endpoint, jsonBody) ?: return PostResult(false, GhaBundle.message("api.notConfigured"))

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code >= 500) connectionState.recordFailure() else connectionState.recordSuccess()
                if (response.isSuccessful) return PostResult(true)

                val body = response.body?.string().orEmpty()
                logger.warn("POST $endpoint 실패: ${response.code} - $body")
                val message = runCatching { gson.fromJson(body, JsonObject::class.java).get("message").asString }.getOrNull()
                PostResult(false, StringUtil.escapeXmlEntities("HTTP ${response.code}" + (message?.let { ": $it" } ?: "")))
            }
        } catch (e: IOException) {
            logger.debug("POST 요청 오류: $endpoint", e)
            connectionState.recordFailure()
            PostResult(false, e.message?.let(StringUtil::escapeXmlEntities))
        }
    }

    private fun <T> executeRequest(request: Request, isUserAction: Boolean = false, parser: (String) -> T): T? {
        // OFFLINE 상태의 자동 호출은 네트워크를 아예 안 건드림
        if (!connectionState.isOnline && !isUserAction) {
            return null
        }

        // OFFLINE + 사용자 액션: health-check 먼저 수행
        if (!connectionState.isOnline && isUserAction) {
            if (!healthCheck()) {
                return null
            }
            connectionState.recordSuccess()
        }

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.debug("API 요청 실패: ${response.code}")
                    if (response.code == 404) {
                        // 404 = 서버는 응답함, Actions 미설정 등 리소스 없음
                        connectionState.recordSuccess()
                    } else {
                        connectionState.recordFailure()
                    }
                    return null
                }
                val body = response.body?.string() ?: return null
                // VPN/프록시 단절 시 200 + HTML 로그인 페이지가 올 수 있음 → 파싱 실패도 실패로 기록
                parser(body).also { connectionState.recordSuccess() }
            }
        } catch (e: IOException) {
            logger.debug("API 요청 오류", e)
            connectionState.recordFailure()
            null
        } catch (e: RuntimeException) {
            logger.debug("API 응답 파싱 실패", e)
            connectionState.recordFailure()
            null
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()
        private const val TAG_MAX_PAGES = 3

        fun getInstance(project: Project): GitHubApiService = project.getService(GitHubApiService::class.java)
    }
}
