package io.github.innoc99.gha.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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

/**
 * GitHub Enterprise API 연동 서비스
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
            return state.personalAccessToken.ifBlank { null }
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
            return state.personalAccessToken.isNotBlank()
        }

        val info = getRemoteInfo() ?: return false
        val host = java.net.URI(info.baseUrl).host
        return GHAccountsUtil.accounts.any { it.server.host == host }
    }

    /**
     * 경량 health-check (GET /api/v3/rate_limit)
     * @return 네트워크 + 인증 정상이면 true
     */
    fun healthCheck(): Boolean {
        val token = resolveToken()
        val info = getRemoteInfo() ?: return false
        if (token.isNullOrBlank()) return false

        val baseUrl = info.baseUrl.trimEnd('/')
        val url = "$baseUrl/api/v3/rate_limit"
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

    private fun buildRequest(endpoint: String): Request? {
        val token = resolveToken()
        val info = getRemoteInfo() ?: return null
        if (token.isNullOrBlank()) return null

        val baseUrl = info.baseUrl.trimEnd('/')
        val url = "$baseUrl/api/v3/repos/${info.owner}/${info.repository}$endpoint"

        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "token $token")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()
    }

    fun fetchWorkflows(): List<Workflow> {
        val allWorkflows = mutableListOf<Workflow>()
        var page = 1

        while (true) {
            val request = buildRequest("/actions/workflows?per_page=100&page=$page") ?: return allWorkflows
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
            } ?: break

            allWorkflows.addAll(pageResult)
            if (pageResult.size < 100) break
            page++
        }

        return allWorkflows
    }

    fun fetchWorkflowRuns(limit: Int = 30): List<WorkflowRun> {
        val request = buildRequest("/actions/runs?per_page=$limit") ?: return emptyList()
        return executeRequest(request) { response ->
            val json = gson.fromJson(response, JsonObject::class.java)
            val runs = json.getAsJsonArray("workflow_runs")
            runs.map { runJson ->
                val obj = runJson.asJsonObject
                WorkflowRun(
                    id = obj.get("id").asLong,
                    name = obj.get("name").asString,
                    workflowId = obj.get("workflow_id").asLong,
                    status = obj.get("status").asString,
                    conclusion = obj.get("conclusion")?.let {
                        if (it.isJsonNull) null else it.asString
                    },
                    htmlUrl = obj.get("html_url").asString,
                    createdAt = Instant.parse(obj.get("created_at").asString),
                    updatedAt = Instant.parse(obj.get("updated_at").asString),
                    headBranch = obj.get("head_branch").asString,
                    headSha = obj.get("head_sha").asString,
                    event = obj.get("event").asString,
                    runNumber = obj.get("run_number").asInt,
                    runAttempt = obj.get("run_attempt").asInt,
                    actor = obj.get("actor")?.let { actorEl ->
                        if (actorEl.isJsonNull) null
                        else actorEl.asJsonObject.get("login")?.asString
                    }
                )
            }
        } ?: emptyList()
    }

    fun fetchWorkflowJobs(runId: Long): List<WorkflowJob> {
        val request = buildRequest("/actions/runs/$runId/jobs") ?: return emptyList()
        return executeRequest(request) { response ->
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
        } ?: emptyList()
    }

    /**
     * 워크플로우 YAML에서 workflow_dispatch inputs 파싱
     */
    fun fetchDispatchInputs(workflowPath: String): List<DispatchInput> {
        if (workflowPath.isBlank()) return emptyList()
        val request = buildRequest("/contents/$workflowPath") ?: return emptyList()
        val yamlContent = executeRequest(request) { response ->
            val json = gson.fromJson(response, JsonObject::class.java)
            val content = json.get("content")?.asString ?: return@executeRequest null
            String(Base64.getMimeDecoder().decode(content))
        }
        if (yamlContent == null) {
            logger.warn("fetchDispatchInputs: YAML 콘텐츠 조회 실패 (path=$workflowPath)")
            return emptyList()
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

    fun fetchJobLogs(jobId: Long): String? {
        val request = buildRequest("/actions/jobs/$jobId/logs") ?: return null
        return executeRequest(request) { it }
    }

    /**
     * 워크플로우 수동 실행 (workflow_dispatch)
     * @return 성공 여부
     */
    fun dispatchWorkflow(workflowId: Long, ref: String, inputs: Map<String, String> = emptyMap()): Boolean {
        // dispatch는 항상 사용자 액션
        if (!connectionState.isOnline) {
            if (!healthCheck()) return false
            connectionState.recordSuccess()
        }

        val token = resolveToken()
        val info = getRemoteInfo() ?: return false
        if (token.isNullOrBlank()) return false

        val baseUrl = info.baseUrl.trimEnd('/')
        val url = "$baseUrl/api/v3/repos/${info.owner}/${info.repository}/actions/workflows/$workflowId/dispatches"

        val body = buildString {
            append("{\"ref\":\"$ref\"")
            if (inputs.isNotEmpty()) {
                append(",\"inputs\":{")
                append(inputs.entries.joinToString(",") { (k, v) ->
                    "\"$k\":\"$v\""
                })
                append("}")
            }
            append("}")
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "token $token")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn("Dispatch 실패: ${response.code} - ${response.body?.string()}")
                    connectionState.recordFailure()
                }
                val success = response.isSuccessful || response.code == 204
                if (success) connectionState.recordSuccess()
                success
            }
        } catch (e: IOException) {
            logger.debug("Dispatch 요청 오류", e)
            connectionState.recordFailure()
            false
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
                    connectionState.recordFailure()
                    return null
                }
                connectionState.recordSuccess()
                val body = response.body?.string() ?: return null
                parser(body)
            }
        } catch (e: IOException) {
            logger.debug("API 요청 오류", e)
            connectionState.recordFailure()
            null
        }
    }

    companion object {
        fun getInstance(project: Project): GitHubApiService = project.getService(GitHubApiService::class.java)
    }
}
