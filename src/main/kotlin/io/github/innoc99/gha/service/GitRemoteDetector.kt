package io.github.innoc99.gha.service

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import java.net.URI

/**
 * Git remote URL에서 GitHub 정보를 자동 감지
 */
data class GitRemoteInfo(
    val baseUrl: String,
    val owner: String,
    val repository: String
) {
    /** REST API 루트 — github.com은 api.github.com, Enterprise는 {host}/api/v3 */
    val apiUrl: String
        get() = if (baseUrl == "https://github.com") "https://api.github.com" else "$baseUrl/api/v3"
}

object GitRemoteDetector {

    // scp 형식: [user@]host:owner/repo.git
    private val SCP_PATTERN = Regex("""(?:[^@/]+@)?([^:/]+):(.+)""")

    /**
     * 프로젝트의 Git remote에서 GitHub 정보를 감지
     * 프로젝트 루트의 저장소를 우선하고, 없으면 첫 번째 저장소를 사용한다.
     */
    fun detect(project: Project): GitRemoteInfo? {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val repo = repositories.firstOrNull { it.root.path == project.basePath }
            ?: repositories.firstOrNull()
            ?: return null

        val originRemote = repo.remotes.firstOrNull { it.name == "origin" }
            ?: repo.remotes.firstOrNull()
            ?: return null

        val remoteUrl = originRemote.firstUrl ?: return null
        return parseRemoteUrl(remoteUrl)
    }

    /**
     * 지원 형식: https://[user@]host[:port]/owner/repo[.git], ssh://[user@]host[:port]/owner/repo[.git],
     * [user@]host:owner/repo[.git]. 저장소 이름의 '.'은 허용한다.
     */
    fun parseRemoteUrl(url: String): GitRemoteInfo? {
        val trimmed = url.trim().removeSuffix("/").removeSuffix(".git")
        val (host, path) = if ("://" in trimmed) {
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val hostName = uri.host ?: return null
            // ssh 포트는 웹 주소와 무관하므로 http(s)일 때만 유지
            val withPort = if (uri.scheme.startsWith("http") && uri.port != -1) "$hostName:${uri.port}" else hostName
            withPort to uri.path.trim('/')
        } else {
            val match = SCP_PATTERN.matchEntire(trimmed) ?: return null
            match.groupValues[1] to match.groupValues[2].trim('/')
        }

        val parts = path.split('/')
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        return GitRemoteInfo(baseUrl = "https://$host", owner = parts[0], repository = parts[1])
    }
}
