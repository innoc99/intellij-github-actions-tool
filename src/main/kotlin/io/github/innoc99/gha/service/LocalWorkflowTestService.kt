package io.github.innoc99.gha.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import io.github.innoc99.gha.GhaBundle
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
            outputHandler(GhaBundle.message("localTest.actNotInstalled"))
            return
        }

        val projectPath = project.basePath ?: return
        val workflowPath = File(projectPath, workflowFile)

        if (!workflowPath.exists()) {
            outputHandler(GhaBundle.message("localTest.fileNotFound", workflowFile))
            return
        }

        outputHandler(GhaBundle.message("localTest.starting", workflowFile))
        outputHandler(GhaBundle.message("localTest.eventInfo", event))
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
                        outputHandler(GhaBundle.message("localTest.success"))
                    } else {
                        outputHandler("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        outputHandler(GhaBundle.message("localTest.failure", exitCode))
                    }
                }
            })

            processHandler.startNotify()

        } catch (e: Exception) {
            logger.error("워크플로우 테스트 실패", e)
            outputHandler(GhaBundle.message("localTest.error", e.message ?: ""))
        }
    }

    /**
     * act 설치 가이드 출력
     */
    fun getInstallGuide(): String {
        return GhaBundle.message("localTest.installGuide")
    }

    companion object {
        fun getInstance(project: Project): LocalWorkflowTestService {
            return project.getService(LocalWorkflowTestService::class.java)
        }
    }
}
