package io.github.innoc99.gha.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * GitHub Actions Tool 글로벌 설정 (애플리케이션 레벨)
 * PAT 본문은 PasswordSafe(OS Keychain)에 저장하고, XML에는 존재 여부만 남긴다.
 */
@Service(Service.Level.APP)
@State(
    name = "GitHubActionsGlobalSettings",
    storages = [Storage("GitHubActionsGlobalSettings.xml")]
)
class GitHubActionsGlobalSettings : PersistentStateComponent<GitHubActionsGlobalSettings.State> {

    private var myState = State()

    data class State(
        /** 1.1.x 이하 평문 저장값 — 첫 조회 시 PasswordSafe로 이전하고, 저장이 끝난 뒤에 비운다 */
        var personalAccessToken: String = "",
        var useGitHubAccountSettings: Boolean = true,
        /** EDT에서 Keychain 접근 없이 토큰 유무를 판단하기 위한 플래그 */
        var hasPersonalAccessToken: Boolean = false
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        if (state.personalAccessToken.isNotBlank()) state.hasPersonalAccessToken = true
    }

    /** PasswordSafe 조회 — Keychain 접근이므로 background thread에서 호출 */
    @Synchronized
    fun getPersonalAccessToken(): String? {
        val legacyToken = myState.personalAccessToken
        if (legacyToken.isNotBlank()) {
            // 다시 읽어 저장이 확인될 때만 평문을 지운다 (secret service 없음·KeePass 잠김 등에서 유실 방지)
            savePersonalAccessToken(legacyToken)
            if (PasswordSafe.instance.getPassword(PAT_ATTRIBUTES) == legacyToken) myState.personalAccessToken = ""
            return legacyToken
        }
        return PasswordSafe.instance.getPassword(PAT_ATTRIBUTES)?.ifBlank { null }
    }

    @Synchronized
    fun savePersonalAccessToken(token: String) {
        PasswordSafe.instance.setPassword(PAT_ATTRIBUTES, token.ifBlank { null })
        myState.hasPersonalAccessToken = token.isNotBlank()
    }

    companion object {
        private val PAT_ATTRIBUTES = CredentialAttributes(generateServiceName("GitHub Actions Tool", "PersonalAccessToken"))

        fun getInstance(): GitHubActionsGlobalSettings {
            return ApplicationManager.getApplication().getService(GitHubActionsGlobalSettings::class.java)
        }
    }
}
