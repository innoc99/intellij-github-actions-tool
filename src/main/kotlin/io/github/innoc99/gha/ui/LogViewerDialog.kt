package io.github.innoc99.gha.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import io.github.innoc99.gha.service.GitHubApiService
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Job 로그 뷰어 다이얼로그
 */
class LogViewerDialog(
    private val project: Project,
    private val jobId: Long,
    private val jobName: String
) : DialogWrapper(project) {

    private val logTextArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
    }

    init {
        title = "Job 로그: $jobName"
        init()
        loadLogs()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(logTextArea)
        scrollPane.preferredSize = Dimension(800, 600)
        return scrollPane
    }

    private fun loadLogs() {
        val apiService = GitHubApiService.getInstance(project)
        val logs = apiService.fetchJobLogs(jobId)

        if (logs != null) {
            logTextArea.text = logs
        } else {
            logTextArea.text = "로그를 불러올 수 없습니다."
        }
    }
}
