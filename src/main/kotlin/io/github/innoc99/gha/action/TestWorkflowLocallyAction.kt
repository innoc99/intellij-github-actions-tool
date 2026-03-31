package io.github.innoc99.gha.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import io.github.innoc99.gha.ui.LocalTestDialog

/**
 * 로컬 워크플로우 테스트 액션
 */
class TestWorkflowLocallyAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LocalTestDialog(project).show()
    }
}
