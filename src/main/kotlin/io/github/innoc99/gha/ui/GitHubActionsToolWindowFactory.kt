package io.github.innoc99.gha.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * GitHub Actions Tool Window Factory
 */
class GitHubActionsToolWindowFactory : ToolWindowFactory {

    companion object {
        /** plugin.xml의 toolWindow id와 동일 */
        const val TOOL_WINDOW_ID = "GitHub Actions"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GitHubActionsToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // content 해제(Tool Window 제거·프로젝트 종료) 시 패널의 타이머·리스너도 함께 해제
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}
