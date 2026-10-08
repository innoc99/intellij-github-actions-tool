package io.github.innoc99.gha.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import io.github.innoc99.gha.ui.GitHubActionsToolWindowFactory
import io.github.innoc99.gha.ui.GitHubActionsToolWindowPanel

/**
 * 워크플로우 새로고침 액션 (Tools 메뉴) — Tool Window를 열고 새로고침한다
 */
class RefreshWorkflowsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(GitHubActionsToolWindowFactory.TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? GitHubActionsToolWindowPanel
            panel?.refreshFromUser()
        }
    }
}
