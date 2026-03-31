package io.github.innoc99.gha.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import io.github.innoc99.gha.service.LocalWorkflowTestService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 로컬 워크플로우 테스트 다이얼로그
 */
class LocalTestDialog(private val project: Project) : DialogWrapper(project) {

    private val workflowFileField = JBTextField(".github/workflows/")
    private val eventComboBox = ComboBox(arrayOf("push", "pull_request", "workflow_dispatch", "schedule"))
    private val outputArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val testService = LocalWorkflowTestService.getInstance(project)

    init {
        title = "로컬 워크플로우 테스트"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(800, 600)

        // 상단 설정 패널
        val configPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = java.awt.Insets(5, 5, 5, 5)

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        configPanel.add(JBLabel("워크플로우 파일:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        configPanel.add(workflowFileField, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        configPanel.add(JBLabel("이벤트:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        configPanel.add(eventComboBox, gbc)

        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 2
        val testButton = JButton("테스트 실행").apply {
            addActionListener { runTest() }
        }
        configPanel.add(testButton, gbc)

        panel.add(configPanel, BorderLayout.NORTH)

        // 출력 패널
        val outputScrollPane = JBScrollPane(outputArea)
        panel.add(outputScrollPane, BorderLayout.CENTER)

        // act 설치 확인
        if (!testService.isActInstalled()) {
            outputArea.text = testService.getInstallGuide()
        }

        return panel
    }

    private fun runTest() {
        val workflowFile = workflowFileField.text
        val event = eventComboBox.selectedItem as String

        outputArea.text = ""

        testService.testWorkflow(workflowFile, event) { output ->
            outputArea.append(output)
            outputArea.caretPosition = outputArea.document.length
        }
    }

    override fun createActions() = emptyArray<javax.swing.Action>()
}
