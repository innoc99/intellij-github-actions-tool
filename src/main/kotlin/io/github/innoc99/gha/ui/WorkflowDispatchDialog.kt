package io.github.innoc99.gha.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.openapi.diagnostic.Logger
import io.github.innoc99.gha.model.DispatchInput
import io.github.innoc99.gha.model.Workflow
import io.github.innoc99.gha.model.WorkflowRun
import io.github.innoc99.gha.service.GitHubApiService
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * 워크플로우 Dispatch(수동 실행) 다이얼로그
 * workflow_dispatch inputs를 동적으로 표시
 */
class WorkflowDispatchDialog(
    private val project: Project,
    private val workflow: Workflow,
    private val dispatchInputs: List<DispatchInput> = emptyList(),
    branches: List<String> = emptyList(),
    defaultBranch: String = "main"
) : DialogWrapper(project) {

    private val branchComboBox = ComboBox<String>().apply {
        isEditable = true
        branches.forEach { addItem(it) }
        if (itemCount == 0) addItem("main")
        // 기본 브랜치가 목록에 없으면 맨 앞에 추가
        if (defaultBranch.isNotBlank() && !branches.contains(defaultBranch)) {
            insertItemAt(defaultBranch, 0)
        }
        selectedItem = defaultBranch
    }
    private val inputComponents = mutableMapOf<String, JComponent>()

    init {
        title = "Dispatch: ${workflow.name}"
        setOKButtonText("실행")
        setCancelButtonText("취소")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        // 브랜치
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("브랜치 (ref):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(branchComboBox, gbc)

        // 동적 입력 필드
        var row = 1
        for (input in dispatchInputs) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.gridwidth = 1
            val labelText = buildString {
                append(input.description.ifBlank { input.name })
                if (input.required) append(" *")
            }
            panel.add(JLabel(labelText), gbc)

            gbc.gridx = 1; gbc.weightx = 1.0
            val component = createInputComponent(input)
            inputComponents[input.name] = component
            panel.add(component, gbc)
            row++
        }

        // 안내 (inputs가 없을 때)
        if (dispatchInputs.isEmpty()) {
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
            gbc.insets = JBUI.insets(8, 4, 0, 4)
            panel.add(JLabel("<html><small>workflow_dispatch 이벤트가 정의된 워크플로우만 실행 가능합니다.</small></html>"), gbc)
        }

        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.NORTH)
        val height = 80 + (row * 36).coerceAtLeast(40)
        wrapper.preferredSize = java.awt.Dimension(450, height)
        return wrapper
    }

    private fun createInputComponent(input: DispatchInput): JComponent {
        return when (input.type) {
            DispatchInput.InputType.CHOICE -> {
                JComboBox(input.options.toTypedArray()).apply {
                    if (input.default != null) selectedItem = input.default
                }
            }
            DispatchInput.InputType.BOOLEAN -> {
                JBCheckBox().apply {
                    isSelected = input.default?.toBoolean() ?: false
                }
            }
            else -> {
                JBTextField(input.default ?: "").apply { columns = 25 }
            }
        }
    }

    private fun getInputValue(input: DispatchInput): String {
        val component = inputComponents[input.name] ?: return input.default ?: ""
        return when (component) {
            is JComboBox<*> -> component.selectedItem?.toString() ?: ""
            is JBCheckBox -> component.isSelected.toString()
            is JBTextField -> component.text.trim()
            else -> ""
        }
    }

    override fun doOKAction() {
        val ref = (branchComboBox.selectedItem as? String)?.trim() ?: ""
        if (ref.isEmpty()) {
            Messages.showWarningDialog(project, "브랜치를 입력해주세요.", "입력 오류")
            return
        }

        // 필수 입력 검증
        for (input in dispatchInputs) {
            if (input.required) {
                val value = getInputValue(input)
                if (value.isBlank()) {
                    Messages.showWarningDialog(
                        project,
                        "'${input.description.ifBlank { input.name }}'은(는) 필수 항목입니다.",
                        "입력 오류"
                    )
                    return
                }
            }
        }

        val inputs = dispatchInputs.associate { it.name to getInputValue(it) }
        val apiService = GitHubApiService.getInstance(project)
        val success = apiService.dispatchWorkflow(workflow.id, ref, inputs)

        if (success) {
            Messages.showInfoMessage(project, "${workflow.name} 워크플로우를 $ref 브랜치에서 실행했습니다.", "Dispatch 성공")
        } else {
            Messages.showErrorDialog(project, "Dispatch 실행에 실패했습니다.\nworkflow_dispatch 이벤트가 정의되어 있는지 확인해주세요.", "Dispatch 실패")
        }

        super.doOKAction()
    }

    companion object {
        private val logger = Logger.getInstance(WorkflowDispatchDialog::class.java)

        /**
         * inputs와 브랜치 목록을 백그라운드에서 로딩 후 다이얼로그 표시
         * @param cachedRuns 캐시된 runs 데이터 (있으면 runs API 호출 스킵)
         * @param onLoadingChanged 로딩 상태 콜백 (true=시작, false=완료)
         */
        fun showWithInputs(
            project: Project,
            workflow: Workflow,
            cachedRuns: List<WorkflowRun> = emptyList(),
            onLoadingChanged: ((Boolean) -> Unit)? = null
        ) {
            onLoadingChanged?.invoke(true)
            ApplicationManager.getApplication().executeOnPooledThread {
                val apiService = GitHubApiService.getInstance(project)
                val inputs = try {
                    apiService.fetchDispatchInputs(workflow.path)
                } catch (e: Exception) {
                    logger.warn("dispatch inputs 로딩 실패", e)
                    emptyList()
                }
                val runs = if (cachedRuns.isNotEmpty()) cachedRuns
                else try {
                    apiService.fetchWorkflowRuns(limit = 100)
                } catch (e: Exception) {
                    emptyList()
                }
                val branches = runs.map { it.headBranch }.distinct().sorted()

                // 기본 브랜치 우선순위: 해당 워크플로우 마지막 실행 브랜치 > git 현재 브랜치 > main > master
                val lastRunBranch = runs
                    .filter { it.workflowId == workflow.id }
                    .maxByOrNull { it.createdAt }
                    ?.headBranch
                val currentGitBranch = try {
                    GitRepositoryManager.getInstance(project)
                        .repositories.firstOrNull()
                        ?.currentBranch?.name
                } catch (_: Exception) { null }

                val defaultBranch = lastRunBranch
                    ?: currentGitBranch
                    ?: if (branches.contains("main")) "main"
                       else if (branches.contains("master")) "master"
                       else branches.firstOrNull() ?: "main"

                ApplicationManager.getApplication().invokeLater {
                    onLoadingChanged?.invoke(false)
                    WorkflowDispatchDialog(project, workflow, inputs, branches, defaultBranch).show()
                }
            }
        }
    }
}
