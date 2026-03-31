package io.github.innoc99.gha.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

/**
 * GitHub Actions Tool 글로벌 설정 (애플리케이션 레벨)
 * PAT 등 프로젝트 공통 인증 정보를 저장합니다.
 */
@Service(Service.Level.APP)
@State(
    name = "GitHubActionsGlobalSettings",
    storages = [Storage("GitHubActionsGlobalSettings.xml")]
)
class GitHubActionsGlobalSettings : PersistentStateComponent<GitHubActionsGlobalSettings.State> {

    private var myState = State()

    data class State(
        var personalAccessToken: String = "",
        var useGitHubAccountSettings: Boolean = true
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): GitHubActionsGlobalSettings {
            return ApplicationManager.getApplication().getService(GitHubActionsGlobalSettings::class.java)
        }
    }
}
