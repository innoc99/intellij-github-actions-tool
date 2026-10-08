package io.github.innoc99.gha.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.openapi.diagnostic.Logger
import io.github.innoc99.gha.GhaBundle
import io.github.innoc99.gha.model.DispatchInput
import io.github.innoc99.gha.model.Workflow
import io.github.innoc99.gha.model.REDEPLOY_TAG_INPUT
import io.github.innoc99.gha.model.WorkflowRun
import io.github.innoc99.gha.model.isValidRedeployTag
import io.github.innoc99.gha.service.GitHubApiService
import io.github.innoc99.gha.service.PostResult
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.Instant
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
    defaultBranch: String = "main",
    /** Dispatch 성공 시 호출 (EDT) */
    private val onDispatched: (() -> Unit)? = null,
    /** 입력 기본값 덮어쓰기 (롤백 시 redeploy_tag 등) */
    private val presetInputs: Map<String, String> = emptyMap(),
    /** redeploy_tag 선택지 (최근 버전 태그) */
    private val versionSuggestions: List<String> = emptyList(),
    /** 같은 real 워크플로우가 1분 내 실행됨 — 같은 분 재실행은 버전 태그 충돌로 실패 */
    private val recentRealRun: Boolean = false
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
        title = GhaBundle.message("dispatch.title", workflow.name)
        setOKButtonText(GhaBundle.message("dispatch.ok"))
        setCancelButtonText(GhaBundle.message("dispatch.cancel"))
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
        panel.add(JLabel(GhaBundle.message("dispatch.branchLabel")), gbc)
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
            panel.add(JLabel(GhaBundle.message("dispatch.hint")), gbc)
        }

        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.NORTH)
        val height = 80 + (row * 36).coerceAtLeast(40)
        wrapper.preferredSize = java.awt.Dimension(450, height)
        return wrapper
    }

    private fun createInputComponent(input: DispatchInput): JComponent {
        val initial = presetInputs[input.name] ?: input.default
        if (input.name == REDEPLOY_TAG_INPUT && input.type == DispatchInput.InputType.STRING) {
            // 빈 값 = 빌드 후 배포, 버전 선택 = 해당 이미지로 재배포(롤백)
            return ComboBox((listOf("") + versionSuggestions).toTypedArray()).apply {
                isEditable = true
                selectedItem = initial.orEmpty()
            }
        }
        return when (input.type) {
            DispatchInput.InputType.CHOICE -> {
                JComboBox(input.options.toTypedArray()).apply {
                    if (initial != null) selectedItem = initial
                }
            }
            DispatchInput.InputType.BOOLEAN -> {
                JBCheckBox().apply {
                    isSelected = initial?.toBoolean() ?: false
                }
            }
            else -> {
                JBTextField(initial ?: "").apply { columns = 25 }
            }
        }
    }

    private fun getInputValue(input: DispatchInput): String {
        val component = inputComponents[input.name] ?: return input.default ?: ""
        return when (component) {
            // 편집 가능한 콤보는 입력 중인 텍스트가 selectedItem에 아직 반영되지 않았을 수 있음
            is JComboBox<*> -> (if (component.isEditable) component.editor.item else component.selectedItem)?.toString()?.trim().orEmpty()
            is JBCheckBox -> component.isSelected.toString()
            is JBTextField -> component.text.trim()
            else -> ""
        }
    }

    override fun doOKAction() {
        val ref = (branchComboBox.selectedItem as? String)?.trim() ?: ""
        if (ref.isEmpty()) {
            Messages.showWarningDialog(project, GhaBundle.message("dispatch.branchRequired"), GhaBundle.message("dispatch.inputError"))
            return
        }

        // 필수 입력 검증
        for (input in dispatchInputs) {
            if (input.required) {
                val value = getInputValue(input)
                if (value.isBlank()) {
                    Messages.showWarningDialog(
                        project,
                        GhaBundle.message("dispatch.requiredField", input.description.ifBlank { input.name }),
                        GhaBundle.message("dispatch.inputError")
                    )
                    return
                }
            }
        }

        val inputs = dispatchInputs.associate { it.name to getInputValue(it) }

        // redeploy_tag 형식은 서버가 job 시작 후에야 거부하므로 미리 확인
        inputs[REDEPLOY_TAG_INPUT]?.let { tag ->
            if (!isValidRedeployTag(tag)) {
                Messages.showWarningDialog(project, GhaBundle.message("dispatch.invalidRedeployTag", tag), GhaBundle.message("dispatch.inputError"))
                return
            }
        }
        if (recentRealRun && Messages.showYesNoDialog(
                project, GhaBundle.message("dispatch.recentRealRun"), GhaBundle.message("dispatch.recentRealRun.title"), null
            ) != Messages.YES
        ) return

        super.doOKAction()

        // 네트워크·Keychain 접근이 있으므로 EDT를 막지 않도록 백그라운드에서 실행
        object : Task.Backgroundable(project, GhaBundle.message("dispatch.progress", workflow.name), false) {
            private var result: PostResult? = null

            override fun run(indicator: ProgressIndicator) {
                result = GitHubApiService.getInstance(project).dispatchWorkflow(workflow.id, ref, inputs)
            }

            override fun onSuccess() {
                val r = result ?: return
                if (r.success) {
                    GhaNotifications.info(project, GhaBundle.message("dispatch.success.message", workflow.name, ref))
                    onDispatched?.invoke()
                } else {
                    val detail = r.errorMessage?.let { "<br>$it" } ?: ""  // executePost에서 이스케이프됨
                    GhaNotifications.error(project, GhaBundle.message("dispatch.failure.message") + detail)
                }
            }
        }.queue()
    }

    companion object {
        private val logger = Logger.getInstance(WorkflowDispatchDialog::class.java)

        private val REAL_NAME_PATTERN = Regex("(?i)\\breal\\b")

        /** 워크플로우 이름(`3) | Real ...`) 또는 파일명(`real-build-deploy.yml`)으로 real 배포 워크플로우 판단 */
        private fun isRealWorkflow(workflow: Workflow): Boolean =
            REAL_NAME_PATTERN.containsMatchIn(workflow.name) || workflow.path.substringAfterLast('/').startsWith("real-")

        /**
         * inputs와 브랜치 목록을 백그라운드에서 로딩 후 다이얼로그 표시
         * @param cachedRuns 캐시된 runs 데이터 (있으면 runs API 호출 스킵)
         * @param onLoadingChanged 로딩 상태 콜백 (true=시작, false=완료)
         */
        fun showWithInputs(
            project: Project,
            workflow: Workflow,
            cachedRuns: List<WorkflowRun> = emptyList(),
            onLoadingChanged: ((Boolean) -> Unit)? = null,
            onDispatched: (() -> Unit)? = null,
            presetInputs: Map<String, String> = emptyMap(),
            versionSuggestions: List<String> = emptyList(),
            branchOverride: String? = null
        ) {
            onLoadingChanged?.invoke(true)
            ApplicationManager.getApplication().executeOnPooledThread {
                val apiService = GitHubApiService.getInstance(project)
                val inputs = try {
                    apiService.fetchDispatchInputs(workflow.path)
                } catch (e: Exception) {
                    logger.warn("dispatch inputs 로딩 실패", e)
                    null
                }
                // 직전 실행 판단(기본 브랜치, real 재실행 경고)은 이 워크플로우의 최신 Run으로 — 화면 목록은 다른 워크플로우 것일 수 있음
                val workflowRuns = apiService.fetchWorkflowRuns(workflow.id, limit = 20)
                    ?: cachedRuns.filter { it.workflowId == workflow.id }
                val branches = (cachedRuns + workflowRuns).map { it.headBranch }.filter { it.isNotBlank() }.distinct().sorted()

                // 기본 브랜치 우선순위: 해당 워크플로우 마지막 실행 브랜치 > git 현재 브랜치 > main > master
                val lastRunBranch = workflowRuns.maxByOrNull { it.createdAt }?.headBranch
                val currentGitBranch = try {
                    GitRepositoryManager.getInstance(project)
                        .repositories.firstOrNull()
                        ?.currentBranch?.name
                } catch (_: Exception) { null }

                val defaultBranch = branchOverride
                    ?: lastRunBranch
                    ?: currentGitBranch
                    ?: if (branches.contains("main")) "main"
                       else if (branches.contains("master")) "master"
                       else branches.firstOrNull() ?: "main"

                // 롤백 등으로 미리 채울 입력이 워크플로우에 없으면 일반 배포가 돌지 않도록 중단
                val missing = presetInputs.keys - inputs.orEmpty().map { it.name }.toSet()
                val recentRealRun = isRealWorkflow(workflow) && workflowRuns.any {
                    it.createdAt.isAfter(Instant.now().minusSeconds(60))
                }

                ApplicationManager.getApplication().invokeLater({
                    onLoadingChanged?.invoke(false)
                    if (inputs == null) {
                        GhaNotifications.error(project, GhaBundle.message("dispatch.inputsLoadFailed", workflow.name))
                        return@invokeLater
                    }
                    if (missing.isNotEmpty()) {
                        GhaNotifications.error(project, GhaBundle.message("dispatch.missingInput", workflow.name, missing.joinToString()))
                        return@invokeLater
                    }
                    WorkflowDispatchDialog(
                        project, workflow, inputs, branches, defaultBranch, onDispatched,
                        presetInputs, versionSuggestions, recentRealRun
                    ).show()
                }, project.disposed)
            }
        }
    }
}
