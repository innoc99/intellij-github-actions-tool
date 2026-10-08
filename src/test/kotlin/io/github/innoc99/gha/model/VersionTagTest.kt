package io.github.innoc99.gha.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class VersionTagTest {

    private fun run(sha: String, createdAt: String) = WorkflowRun(
        id = 1, name = "3) | Real Build & Deploy", workflowId = 1, status = "completed", conclusion = "success",
        htmlUrl = "", createdAt = Instant.parse(createdAt), updatedAt = Instant.parse(createdAt),
        headBranch = "main", headSha = sha, event = "workflow_dispatch", runNumber = 1, runAttempt = 1
    )

    @Test
    @DisplayName("버전 태그 이름에서 버전과 KST 생성 시각을 추출하고, 버전 태그가 아니면 null")
    fun parsesVersionTags() {
        val tag = VersionTag.parse("sample-api-v2026.09.10.1430", "abc")!!
        assertEquals("v2026.09.10.1430", tag.version)
        assertEquals(Instant.parse("2026-09-10T05:30:00Z"), tag.createdAt)
        assertEquals("v2026.09.10.1430", VersionTag.parse("v2026.09.10.1430", "abc")!!.version)
        assertNull(VersionTag.parse("release-1.0", "abc"))
        assertNull(VersionTag.parse("api-v2026.13.40.9999", "abc"))
    }

    @Test
    @DisplayName("같은 커밋이고 Run 시작 직후 생성된 태그만 Run에 연결한다")
    fun matchesTagCreatedByRun() {
        // Run 시작 05:29:40Z(14:29:40 KST) → setup이 14:30에 태그 생성
        val created = VersionTag.parse("api-v2026.09.10.1430", "abc")!!
        val older = VersionTag.parse("api-v2026.09.01.1000", "abc")!!
        val other = VersionTag.parse("api-v2026.09.10.1431", "def")!!
        val tags = listOf(older, created, other)

        assertEquals(created, findVersionTag(run("abc", "2026-09-10T05:29:40Z"), tags))
        // 같은 커밋으로 나중에 롤백한 Run에는 이전 태그를 붙이지 않는다
        assertNull(findVersionTag(run("abc", "2026-09-20T01:00:00Z"), tags))
    }

    @Test
    @DisplayName("redeploy_tag는 빈 값, 버전 태그, phase 이름만 허용한다")
    fun validatesRedeployTag() {
        assertTrue(isValidRedeployTag(""))
        assertTrue(isValidRedeployTag("v2026.09.10.1430"))
        assertTrue(isValidRedeployTag("stage"))
        assertFalse(isValidRedeployTag("api-v2026.09.10.1430"))
        assertFalse(isValidRedeployTag("real"))
        assertFalse(isValidRedeployTag("v2026.09.10.143000"))
    }
}
