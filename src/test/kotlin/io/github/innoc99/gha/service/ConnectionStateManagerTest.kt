package io.github.innoc99.gha.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConnectionStateManagerTest {

    private lateinit var manager: ConnectionStateManager
    private val stateChanges = mutableListOf<ConnectionState>()

    @BeforeEach
    fun setUp() {
        manager = ConnectionStateManager.createForTest()
        stateChanges.clear()
        manager.addListener(object : ConnectionStateListener {
            override fun onStateChanged(newState: ConnectionState) {
                stateChanges.add(newState)
            }
        })
    }

    @Test
    fun `초기 상태는 ONLINE이다`() {
        assertEquals(ConnectionState.ONLINE, manager.state)
    }

    @Test
    fun `1회 실패로는 OFFLINE 전환되지 않는다`() {
        manager.recordFailure()
        assertEquals(ConnectionState.ONLINE, manager.state)
        assertTrue(stateChanges.isEmpty())
    }

    @Test
    fun `연속 2회 실패 시 OFFLINE으로 전환된다`() {
        manager.recordFailure()
        manager.recordFailure()
        assertEquals(ConnectionState.OFFLINE, manager.state)
        assertEquals(listOf(ConnectionState.OFFLINE), stateChanges)
    }

    @Test
    fun `성공 후 실패 카운터가 초기화된다`() {
        manager.recordFailure()
        manager.recordSuccess()
        manager.recordFailure()
        assertEquals(ConnectionState.ONLINE, manager.state)
        assertTrue(stateChanges.isEmpty())
    }

    @Test
    fun `OFFLINE에서 성공 시 ONLINE으로 복귀한다`() {
        manager.recordFailure()
        manager.recordFailure()
        stateChanges.clear()

        manager.recordSuccess()
        assertEquals(ConnectionState.ONLINE, manager.state)
        assertEquals(listOf(ConnectionState.ONLINE), stateChanges)
    }

    @Test
    fun `OFFLINE 상태에서 추가 실패해도 리스너가 중복 호출되지 않는다`() {
        manager.recordFailure()
        manager.recordFailure()
        stateChanges.clear()

        manager.recordFailure()
        manager.recordFailure()
        assertTrue(stateChanges.isEmpty())
    }

    @Test
    fun `isOnline은 현재 상태를 반환한다`() {
        assertTrue(manager.isOnline)
        manager.recordFailure()
        manager.recordFailure()
        assertFalse(manager.isOnline)
    }

    @Test
    fun `백오프 간격이 올바르게 증가한다`() {
        assertEquals(30_000L, manager.nextBackoffMillis())  // 30s
        assertEquals(60_000L, manager.nextBackoffMillis())  // 60s
        assertEquals(120_000L, manager.nextBackoffMillis()) // 120s
        assertEquals(300_000L, manager.nextBackoffMillis()) // 5분 (최대)
        assertEquals(300_000L, manager.nextBackoffMillis()) // 최대 유지
    }

    @Test
    fun `ONLINE 복귀 시 백오프 카운터가 초기화된다`() {
        manager.nextBackoffMillis() // 30s
        manager.nextBackoffMillis() // 60s
        manager.resetBackoff()
        assertEquals(30_000L, manager.nextBackoffMillis()) // 다시 30s
    }

    @Test
    fun `마지막 성공 시각이 기록된다`() {
        assertNull(manager.lastSuccessTime)
        manager.recordSuccess()
        assertNotNull(manager.lastSuccessTime)
    }
}
