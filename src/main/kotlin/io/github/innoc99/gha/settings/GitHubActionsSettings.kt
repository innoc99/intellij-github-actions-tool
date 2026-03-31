package io.github.innoc99.gha.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * GitHub Actions Tool 프로젝트별 설정 저장소
 * PAT는 GitHubActionsGlobalSettings에서 관리합니다.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "GitHubActionsSettings",
    storages = [Storage("GitHubActionsSettings.xml")]
)
class GitHubActionsSettings : PersistentStateComponent<GitHubActionsSettings.State> {

    private var myState = State()

    data class State(
        var githubEnterpriseUrl: String = "",
        var repositoryOwner: String = "",
        var repositoryName: String = "",
        var autoRefreshEnabled: Boolean = true,
        var refreshIntervalSeconds: Int = 30
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): GitHubActionsSettings {
            return project.service()
        }
    }
}
