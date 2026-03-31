package io.github.innoc99.gha.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class ConnectionState { ONLINE, OFFLINE }

interface ConnectionStateListener {
    fun onStateChanged(newState: ConnectionState)
}

/**
 * 네트워크 연결 상태 머신
 * ONLINE/OFFLINE 상태를 관리하고, 상태 전환 시 리스너에게 알림
 */
@Service(Service.Level.PROJECT)
class ConnectionStateManager(private val project: Project?) {

    private val logger by lazy { Logger.getInstance(ConnectionStateManager::class.java) }
    private val listeners = mutableListOf<ConnectionStateListener>()

    var state: ConnectionState = ConnectionState.ONLINE
        private set

    val isOnline: Boolean get() = state == ConnectionState.ONLINE

    /** 마지막 API 성공 시각 */
    var lastSuccessTime: Instant? = null
        private set

    /** 연속 실패 횟수 */
    private var consecutiveFailures = 0

    /** OFFLINE 전환 임계치 */
    private val failureThreshold = 2

    /** 백오프 단계 (0-based) */
    private var backoffStep = 0
    private val backoffIntervalsMs = longArrayOf(30_000, 60_000, 120_000, 300_000)

    private val healthCheckExecutor = Executors.newSingleThreadScheduledExecutor()
    private var healthCheckTask: ScheduledFuture<*>? = null

    /** health-check 수행자 (GitHubApiService.healthCheck를 주입) */
    private var healthChecker: (() -> Boolean)? = null

    /**
     * health-check 함수를 등록한다. GitHubApiService 초기화 후 호출.
     */
    fun setHealthChecker(checker: () -> Boolean) {
        healthChecker = checker
    }

    /**
     * API 호출 성공 기록
     */
    fun recordSuccess() {
        consecutiveFailures = 0
        lastSuccessTime = Instant.now()

        if (state == ConnectionState.OFFLINE) {
            if (project != null) logger.info("네트워크 복구 감지 — ONLINE 전환")
            state = ConnectionState.ONLINE
            resetBackoff()
            stopHealthCheckTimer()
            notifyListeners()
        }
    }

    /**
     * API 호출 실패 기록
     */
    fun recordFailure() {
        consecutiveFailures++

        if (state == ConnectionState.ONLINE && consecutiveFailures >= failureThreshold) {
            if (project != null) logger.info("연속 ${consecutiveFailures}회 실패 — OFFLINE 전환")
            state = ConnectionState.OFFLINE
            notifyListeners()
            startHealthCheckTimer()
        }
    }

    /**
     * 다음 health-check 백오프 간격 반환 (호출할 때마다 단계 증가)
     */
    fun nextBackoffMillis(): Long {
        val interval = backoffIntervalsMs[backoffStep.coerceAtMost(backoffIntervalsMs.size - 1)]
        if (backoffStep < backoffIntervalsMs.size - 1) backoffStep++
        return interval
    }

    fun resetBackoff() {
        backoffStep = 0
    }

    fun addListener(listener: ConnectionStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConnectionStateListener) {
        listeners.remove(listener)
    }

    /**
     * OFFLINE 전환 시 자동 health-check 타이머 시작
     */
    private fun startHealthCheckTimer() {
        stopHealthCheckTimer()
        scheduleNextHealthCheck()
    }

    private fun scheduleNextHealthCheck() {
        val delayMs = nextBackoffMillis()
        if (project != null) logger.info("다음 health-check: ${delayMs / 1000}초 후")
        healthCheckTask = healthCheckExecutor.schedule({
            val checker = healthChecker ?: return@schedule
            val success = checker()
            if (success) {
                recordSuccess()
            } else {
                // 아직 OFFLINE — 다음 health-check 예약
                if (state == ConnectionState.OFFLINE) {
                    scheduleNextHealthCheck()
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun stopHealthCheckTimer() {
        healthCheckTask?.cancel(false)
        healthCheckTask = null
    }

    fun dispose() {
        stopHealthCheckTimer()
        healthCheckExecutor.shutdown()
    }

    private fun notifyListeners() {
        val currentState = state
        listeners.forEach { it.onStateChanged(currentState) }
    }

    companion object {
        fun getInstance(project: Project): ConnectionStateManager {
            return project.getService(ConnectionStateManager::class.java)
        }

        /**
         * 테스트용 인스턴스 (IntelliJ Platform 없이 동작)
         */
        fun createForTest(): ConnectionStateManager {
            return ConnectionStateManager(null)
        }
    }
}
