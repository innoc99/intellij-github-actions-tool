package io.github.innoc99.gha.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File

/**
 * 로컬 워크플로우 테스트 서비스
 * act 도구를 사용하여 GitHub Actions를 로컬에서 실행합니다.
 */
@Service(Service.Level.PROJECT)
class LocalWorkflowTestService(private val project: Project) {

    private val logger = Logger.getInstance(LocalWorkflowTestService::class.java)

    /**
     * act가 설치되어 있는지 확인
     */
    fun isActInstalled(): Boolean {
        return try {
            val commandLine = GeneralCommandLine("act", "--version")
            val process = commandLine.createProcess()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 워크플로우 파일을 로컬에서 테스트
     * @param workflowFile 워크플로우 파일 경로 (.github/workflows/ *.yml)
     * @param event 트리거할 이벤트 (예: push, pull_request)
     * @param outputHandler 출력 핸들러
     */
    fun testWorkflow(
        workflowFile: String,
        event: String = "push",
        outputHandler: (String) -> Unit
    ) {
        if (!isActInstalled()) {
            outputHandler("❌ act가 설치되어 있지 않습니다. https://github.com/nektos/act 에서 설치하세요.")
            return
        }

        val projectPath = project.basePath ?: return
        val workflowPath = File(projectPath, workflowFile)

        if (!workflowPath.exists()) {
            outputHandler("❌ 워크플로우 파일을 찾을 수 없습니다: $workflowFile")
            return
        }

        outputHandler("🚀 로컬 워크플로우 테스트 시작: $workflowFile")
        outputHandler("이벤트: $event")
        outputHandler("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        try {
            val commandLine = GeneralCommandLine(
                "act",
                event,
                "-W", workflowFile,
                "--verbose"
            ).apply {
                workDirectory = File(projectPath)
            }

            val processHandler = OSProcessHandler(commandLine)

            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    outputHandler(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    val exitCode = event.exitCode
                    if (exitCode == 0) {
                        outputHandler("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        outputHandler("✅ 워크플로우 테스트 성공")
                    } else {
                        outputHandler("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        outputHandler("❌ 워크플로우 테스트 실패 (exit code: $exitCode)")
                    }
                }
            })

            processHandler.startNotify()

        } catch (e: Exception) {
            logger.error("워크플로우 테스트 실패", e)
            outputHandler("❌ 오류: ${e.message}")
        }
    }

    /**
     * act 설치 가이드 출력
     */
    fun getInstallGuide(): String {
        return """
            |act 설치 방법:
            |
            |macOS (Homebrew):
            |  brew install act
            |
            |Linux:
            |  curl https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash
            |
            |Windows (Chocolatey):
            |  choco install act-cli
            |
            |자세한 정보: https://github.com/nektos/act
        """.trimMargin()
    }

    companion object {
        fun getInstance(project: Project): LocalWorkflowTestService {
            return project.getService(LocalWorkflowTestService::class.java)
        }
    }
}
