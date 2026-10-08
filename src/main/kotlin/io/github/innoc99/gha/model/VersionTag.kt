package io.github.innoc99.gha.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * real 배포 워크플로우가 만드는 버전 Git 태그 (`{project 또는 module}-vYYYY.MM.DD.HHmm`, KST)
 */
data class VersionTag(
    val name: String,
    val version: String,
    val sha: String,
    val createdAt: Instant
) {
    companion object {
        private val TAG_PATTERN = Regex("""^(?:.+-)?(v\d{4}\.\d{2}\.\d{2}\.\d{4})$""")
        private val VERSION_FORMAT = DateTimeFormatter.ofPattern("'v'yyyy.MM.dd.HHmm")
        private val KST = ZoneId.of("Asia/Seoul")

        /** 버전 태그가 아니면 null */
        fun parse(name: String, sha: String): VersionTag? {
            val version = TAG_PATTERN.matchEntire(name)?.groupValues?.get(1) ?: return null
            val time = runCatching { LocalDateTime.parse(version, VERSION_FORMAT) }.getOrNull() ?: return null
            return VersionTag(name, version, sha, time.atZone(KST).toInstant())
        }
    }
}

/** 태그는 Run 시작 직후 setup job에서 만들어진다 — 큐 대기를 감안한 허용 범위 */
private val TAG_AFTER_RUN_WINDOW: Duration = Duration.ofMinutes(60)

/**
 * Run이 만든 버전 태그를 찾는다. 같은 커밋이면서 Run 시작 후(분 단위 절삭 감안 -1분) 60분 이내에 생성된 태그만 인정한다.
 * 같은 커밋으로 재배포(롤백)한 Run에 이전 배포의 태그가 붙지 않도록 시각까지 비교한다.
 */
fun findVersionTag(run: WorkflowRun, tags: List<VersionTag>): VersionTag? {
    val from = run.createdAt.minus(Duration.ofMinutes(1))
    val until = run.createdAt.plus(TAG_AFTER_RUN_WINDOW)
    return tags
        .filter { it.sha == run.headSha && it.createdAt >= from && it.createdAt <= until }
        .minByOrNull { it.createdAt }
}

private val REDEPLOY_TAG_PATTERN = Regex("""^(v\d{4}\.\d{2}\.\d{2}\.\d{4}|dev|stage|sandbox|qa|cbt|test)$""")

/**
 * `redeploy_tag` 입력 형식 검사 — 서버는 job 시작 후에야 거부하므로 Dispatch 전에 확인한다.
 * 빈 값은 "빌드 후 배포"를 뜻하므로 허용.
 */
fun isValidRedeployTag(value: String): Boolean = value.isEmpty() || REDEPLOY_TAG_PATTERN.matches(value)

/** 배포 워크플로우의 재배포(롤백) 입력 이름 */
const val REDEPLOY_TAG_INPUT = "redeploy_tag"
