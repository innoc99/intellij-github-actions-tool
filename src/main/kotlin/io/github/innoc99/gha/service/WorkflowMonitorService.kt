package io.github.innoc99.gha.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.innoc99.gha.model.WorkflowRun
import io.github.innoc99.gha.settings.GitHubActionsSettings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 워크플로우 모니터링 서비스
 * 주기적으로 워크플로우 실행 상태를 체크합니다.
 * OFFLINE 시 polling을 정지하고 ONLINE 복귀 시 재시작합니다.
 */
@Service(Service.Level.PROJECT)
class WorkflowMonitorService(private val project: Project) : ConnectionStateListener {

    private val apiService = GitHubApiService.getInstance(project)
    private val settings = GitHubActionsSettings.getInstance(project)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val listeners = mutableListOf<WorkflowStatusListener>()
    private var monitoringTask: ScheduledFuture<*>? = null

    fun startMonitoring() {
        ConnectionStateManager.getInstance(project).addListener(this)
        scheduleMonitoring()
    }

    fun stopMonitoring() {
        monitoringTask?.cancel(false)
        executor.shutdown()
    }

    private fun scheduleMonitoring() {
        monitoringTask?.cancel(false)
        val interval = settings.state.refreshIntervalSeconds.toLong()
        monitoringTask = executor.scheduleAtFixedRate({
            checkWorkflowStatus()
        }, 0, interval, TimeUnit.SECONDS)
    }

    fun addListener(listener: WorkflowStatusListener) {
        listeners.add(listener)
    }

    private fun checkWorkflowStatus() {
        if (!settings.state.autoRefreshEnabled) return

        val runs = apiService.fetchWorkflowRuns(limit = 20)
        listeners.forEach { it.onWorkflowsUpdated(runs) }
    }

    override fun onStateChanged(newState: ConnectionState) {
        when (newState) {
            ConnectionState.OFFLINE -> {
                monitoringTask?.cancel(false)
                monitoringTask = null
            }
            ConnectionState.ONLINE -> {
                scheduleMonitoring()
            }
        }
    }

    interface WorkflowStatusListener {
        fun onWorkflowsUpdated(runs: List<WorkflowRun>)
    }

    companion object {
        fun getInstance(project: Project): WorkflowMonitorService {
            return project.getService(WorkflowMonitorService::class.java)
        }
    }
}
