package io.github.innoc99.gha.model

import java.time.Instant

/**
 * GitHub Actions 워크플로우 실행 정보
 */
data class WorkflowRun(
    val id: Long,
    val name: String,
    val workflowId: Long,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val headBranch: String,
    val headSha: String,
    val event: String,
    val runNumber: Int,
    val runAttempt: Int,
    val actor: String? = null
) {
    fun isInProgress(): Boolean = status == "in_progress" || status == "queued"
    fun isCompleted(): Boolean = status == "completed"
    fun isSuccess(): Boolean = conclusion == "success"
    fun isFailed(): Boolean = conclusion == "failure"
    fun isCancelled(): Boolean = conclusion == "cancelled"
}

/**
 * GitHub Actions 워크플로우 정의
 */
data class Workflow(
    val id: Long,
    val name: String,
    val path: String,
    val state: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * GitHub Actions Job 정보
 */
data class WorkflowJob(
    val id: Long,
    val runId: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val steps: List<WorkflowStep>
)

/**
 * GitHub Actions Step 정보
 */
data class WorkflowStep(
    val name: String,
    val status: String,
    val conclusion: String?,
    val number: Int,
    val startedAt: Instant?,
    val completedAt: Instant?
)

/**
 * workflow_dispatch 입력 필드 정의
 */
data class DispatchInput(
    val name: String,
    val description: String,
    val required: Boolean,
    val default: String?,
    val type: InputType,
    val options: List<String>
) {
    enum class InputType { STRING, CHOICE, BOOLEAN, ENVIRONMENT }
}
