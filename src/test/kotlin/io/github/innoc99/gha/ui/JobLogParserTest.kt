package io.github.innoc99.gha.ui

import io.github.innoc99.gha.model.WorkflowStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class JobLogParserTest {

    private fun step(number: Int, name: String, startedAt: String?) = WorkflowStep(
        name = name, status = "completed", conclusion = "success", number = number,
        startedAt = startedAt?.let { Instant.parse(it) }, completedAt = null
    )

    private val steps = listOf(
        step(1, "Set up job", "2026-09-10T05:30:00Z"),
        step(2, "Build", "2026-09-10T05:30:05Z"),
        step(3, "Skipped", null)
    )

    private val log = """
        2026-09-10T05:30:00.1000000Z ##[group]Operating System
        2026-09-10T05:30:00.2000000Z Ubuntu
        2026-09-10T05:30:00.3000000Z ##[endgroup]
        2026-09-10T05:30:00.4000000Z ##[group]Runner Image
        2026-09-10T05:30:00.5000000Z Image: ubuntu-22.04
        2026-09-10T05:30:00.6000000Z ##[endgroup]
        2026-09-10T05:30:05.1000000Z [1;31mcompiling[0m
        2026-09-10T05:30:06.0000000Z ##[error]Process completed with exit code 1.
    """.trimIndent().replace("[1;31m", "\u001B[1;31m").replace("[0m", "\u001B[0m") + "\n"

    @Test
    @DisplayName("한 Step에 그룹이 여러 개여도 타임스탬프 기준으로 Step을 나눈다")
    fun splitsStepsByTimestamp() {
        val parsed = JobLogParser.parse(log, steps)
        val lines = parsed.text.lines()

        assertEquals(
            listOf(
                "Set up job", "Operating System", "Ubuntu", "Runner Image", "Image: ubuntu-22.04",
                "Build", "compiling", "Process completed with exit code 1.",
                "Skipped"
            ),
            lines
        )
        assertEquals(listOf(0 to 4, 5 to 7, 8 to 8), parsed.steps.map { it.headerLine to it.lastLine })
    }

    @Test
    @DisplayName("그룹 접기 범위와 error 줄 위치를 결과 텍스트 기준으로 계산한다")
    fun computesGroupsAndErrors() {
        val parsed = JobLogParser.parse(log, steps)
        assertEquals(listOf(1..2, 3..4), parsed.groups)
        assertEquals(listOf(7), parsed.errorLines)
    }

    @Test
    @DisplayName("Step 정보가 없으면 로그를 그대로 보여준다")
    fun keepsLogWithoutSteps() {
        assertEquals("a\nb", JobLogParser.parse("a\nb\n", emptyList()).text)
    }
}
