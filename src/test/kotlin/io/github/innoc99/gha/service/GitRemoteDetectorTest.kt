package io.github.innoc99.gha.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GitRemoteDetectorTest {

    private fun parse(url: String) = GitRemoteDetector.parseRemoteUrl(url)

    @Test
    @DisplayName("지원하는 remote URL 형식에서 서버·owner·저장소를 추출한다")
    fun parsesSupportedFormats() {
        val expected = GitRemoteInfo("https://ghe.example.com", "acme", "deploy-actions")
        assertEquals(expected, parse("https://ghe.example.com/acme/deploy-actions.git"))
        assertEquals(expected, parse("https://ghe.example.com/acme/deploy-actions"))
        assertEquals(expected, parse("https://user@ghe.example.com/acme/deploy-actions.git"))
        assertEquals(expected, parse("git@ghe.example.com:acme/deploy-actions.git"))
        assertEquals(expected, parse("ssh://git@ghe.example.com:22/acme/deploy-actions.git"))
    }

    @Test
    @DisplayName("저장소 이름에 점이 있어도 인식하고, http 포트는 유지한다")
    fun keepsDotsAndHttpPort() {
        assertEquals(GitRemoteInfo("https://github.com", "o", "my.repo"), parse("https://github.com/o/my.repo.git"))
        assertEquals(GitRemoteInfo("https://ghe.local:8443", "o", "r"), parse("https://ghe.local:8443/o/r.git"))
    }

    @Test
    @DisplayName("owner/repo 구조가 아니면 null을 반환한다")
    fun rejectsInvalidPaths() {
        assertNull(parse("https://github.com/only-owner"))
        assertNull(parse("https://gitlab.com/group/sub/repo.git"))
        assertNull(parse("not a url"))
    }

    @Test
    @DisplayName("github.com은 api.github.com, Enterprise는 /api/v3를 API 루트로 쓴다")
    fun resolvesApiUrl() {
        assertEquals("https://api.github.com", GitRemoteInfo("https://github.com", "o", "r").apiUrl)
        assertEquals("https://ghe.local/api/v3", GitRemoteInfo("https://ghe.local", "o", "r").apiUrl)
    }
}
