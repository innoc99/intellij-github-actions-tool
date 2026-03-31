package io.github.innoc99.gha.service

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Git remote URL에서 GitHub 정보를 자동 감지
 */
data class GitRemoteInfo(
    val baseUrl: String,
    val owner: String,
    val repository: String
)

object GitRemoteDetector {

    // HTTPS: https://github.example.com/owner/repo.git
    private val HTTPS_PATTERN = Regex("""https?://([^/]+)/([^/]+)/([^/.]+?)(?:\.git)?$""")

    // SSH: git@github.example.com:owner/repo.git
    private val SSH_PATTERN = Regex("""git@([^:]+):([^/]+)/([^/.]+?)(?:\.git)?$""")

    /**
     * 프로젝트의 Git remote에서 GitHub 정보를 감지
     */
    fun detect(project: Project): GitRemoteInfo? {
        val repositoryManager = GitRepositoryManager.getInstance(project)
        val repositories = repositoryManager.repositories
        if (repositories.isEmpty()) return null

        // 첫 번째 repository의 origin remote 사용
        val repo = repositories.first()
        val originRemote = repo.remotes.firstOrNull { it.name == "origin" }
            ?: repo.remotes.firstOrNull()
            ?: return null

        val remoteUrl = originRemote.firstUrl ?: return null
        return parseRemoteUrl(remoteUrl)
    }

    fun parseRemoteUrl(url: String): GitRemoteInfo? {
        HTTPS_PATTERN.matchEntire(url)?.let { match ->
            val host = match.groupValues[1]
            return GitRemoteInfo(
                baseUrl = "https://$host",
                owner = match.groupValues[2],
                repository = match.groupValues[3]
            )
        }

        SSH_PATTERN.matchEntire(url)?.let { match ->
            val host = match.groupValues[1]
            return GitRemoteInfo(
                baseUrl = "https://$host",
                owner = match.groupValues[2],
                repository = match.groupValues[3]
            )
        }

        return null
    }
}
