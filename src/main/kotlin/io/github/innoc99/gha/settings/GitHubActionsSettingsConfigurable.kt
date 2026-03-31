package io.github.innoc99.gha.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import io.github.innoc99.gha.service.GitRemoteDetector
import io.github.innoc99.gha.service.GitRemoteInfo
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * GitHub Actions Tool 설정 UI
 */
class GitHubActionsSettingsConfigurable(private val project: Project) : Configurable {

    private lateinit var useGitHubAccountCheckBox: JBCheckBox
    private lateinit var tokenField: JBPasswordField
    private lateinit var autoRefreshCheckBox: JBCheckBox
    private lateinit var refreshIntervalField: JBTextField
    private lateinit var vcsInfoLabel: JLabel
    private lateinit var githubSettingsLink: JLabel
    private lateinit var tokenRow: Row

    private val settings = GitHubActionsSettings.getInstance(project)
    private val globalSettings = GitHubActionsGlobalSettings.getInstance()

    override fun getDisplayName(): String = "GitHub Actions Tool"

    override fun createComponent(): JComponent {
        val vcsInfo = GitRemoteDetector.detect(project)

        val component = panel {
            group("VCS 저장소 정보") {
                row {
                    vcsInfoLabel = label(formatVcsInfo(vcsInfo)).component
                }
                if (vcsInfo == null) {
                    row {
                        comment("Git remote를 감지할 수 없습니다. 프로젝트에 Git 저장소가 설정되어 있는지 확인하세요.")
                    }
                }
            }

            group("인증 설정 (글로벌)") {
                row {
                    useGitHubAccountCheckBox = checkBox("IntelliJ GitHub 계정 설정 사용")
                        .bindSelected(globalSettings.state::useGitHubAccountSettings)
                        .component
                    useGitHubAccountCheckBox.addActionListener { updateAuthUI() }
                }.rowComment("Version Control > GitHub에 등록된 계정의 토큰을 사용합니다")

                row {
                    githubSettingsLink = JLabel("<html><a href=''>GitHub 설정 열기 (Version Control > GitHub)</a></html>")
                    githubSettingsLink.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    githubSettingsLink.addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            ShowSettingsUtil.getInstance().showSettingsDialog(
                                project, "GitHub"
                            )
                        }
                    })
                    cell(githubSettingsLink)
                }

                tokenRow = row("Personal Access Token:") {
                    tokenField = JBPasswordField()
                    cell(tokenField)
                        .columns(COLUMNS_LARGE)
                        .bindText(
                            getter = { globalSettings.state.personalAccessToken },
                            setter = { globalSettings.state.personalAccessToken = it }
                        )
                }.rowComment("repo, workflow 권한이 있는 토큰 (모든 프로젝트에서 공유)")
            }

            group("모니터링 설정") {
                row {
                    autoRefreshCheckBox = checkBox("자동 새로고침 활성화")
                        .bindSelected(settings.state::autoRefreshEnabled)
                        .component
                }

                row("새로고침 주기 (초):") {
                    refreshIntervalField = textField()
                        .bindText(
                            getter = { settings.state.refreshIntervalSeconds.toString() },
                            setter = { settings.state.refreshIntervalSeconds = it.toIntOrNull() ?: 30 }
                        )
                        .columns(COLUMNS_SHORT)
                        .component
                }.rowComment("최소 10초")
            }
        }
        updateAuthUI()
        return component
    }

    private fun formatVcsInfo(info: GitRemoteInfo?): String {
        if (info == null) return "감지된 저장소 없음"
        return "${info.baseUrl} / ${info.owner} / ${info.repository}"
    }

    /**
     * 체크박스 상태에 따라 GitHub 설정 링크 / PAT 필드 활성화 토글
     */
    private fun updateAuthUI() {
        val useAccount = useGitHubAccountCheckBox.isSelected
        githubSettingsLink.isVisible = useAccount
        tokenRow.visible(useAccount.not())
    }

    override fun isModified(): Boolean {
        val state = settings.state
        val globalState = globalSettings.state
        return useGitHubAccountCheckBox.isSelected != globalState.useGitHubAccountSettings ||
                String(tokenField.password) != globalState.personalAccessToken ||
                autoRefreshCheckBox.isSelected != state.autoRefreshEnabled ||
                refreshIntervalField.text.toIntOrNull() != state.refreshIntervalSeconds
    }

    override fun apply() {
        val state = settings.state
        globalSettings.state.useGitHubAccountSettings = useGitHubAccountCheckBox.isSelected
        globalSettings.state.personalAccessToken = String(tokenField.password)
        state.autoRefreshEnabled = autoRefreshCheckBox.isSelected
        state.refreshIntervalSeconds = refreshIntervalField.text.toIntOrNull()?.coerceAtLeast(10) ?: 30
    }

    override fun reset() {
        val state = settings.state
        useGitHubAccountCheckBox.isSelected = globalSettings.state.useGitHubAccountSettings
        tokenField.text = globalSettings.state.personalAccessToken
        autoRefreshCheckBox.isSelected = state.autoRefreshEnabled
        refreshIntervalField.text = state.refreshIntervalSeconds.toString()
        updateAuthUI()
    }
}
