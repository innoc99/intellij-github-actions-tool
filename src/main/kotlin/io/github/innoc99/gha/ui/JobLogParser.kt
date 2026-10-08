package io.github.innoc99.gha.ui

import io.github.innoc99.gha.model.WorkflowStep
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Job 로그를 Editor 표시용 텍스트로 변환한 결과. 모든 위치는 결과 텍스트의 0-based 줄 번호.
 */
data class ParsedJobLog(
    val text: String,
    val steps: List<StepBlock>,
    /** ##[group] 접기 범위 (헤더 줄 → 그룹 마지막 줄) */
    val groups: List<IntRange>,
    val errorLines: List<Int>,
    val warningLines: List<Int>
)

/** Step 헤더 줄과 그 Step 로그의 마지막 줄 (로그가 없으면 lastLine == headerLine) */
data class StepBlock(val step: WorkflowStep, val headerLine: Int, val lastLine: Int)

/**
 * GitHub Actions Job 로그 파서
 * - Step 구분: 각 줄 타임스탬프를 Step 시작 시각(초 단위)과 비교. `##[group]` 개수로 세면 한 Step에 그룹이 여러 개일 때 밀린다.
 *   ponytail: 같은 초에 시작한 Step이 여럿이면 앞 Step의 줄이 마지막 Step으로 몰린다 — 필요해지면 Step별 로그 API로 전환
 * - 타임스탬프·ANSI 코드·`##[...]` 마커 제거, `##[endgroup]` 줄은 생략
 */
object JobLogParser {

    private val TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z) ?(.*)$""")
    private val ANSI = Regex("\u001B\\[[0-9;]*[a-zA-Z]")

    private class RawLine(val time: Instant?, val content: String)

    fun parse(log: String, steps: List<WorkflowStep>): ParsedJobLog {
        val ordered = steps.sortedBy { it.number }
        val linesByStep = List(ordered.size.coerceAtLeast(1)) { mutableListOf<String>() }

        var current = 0
        // 로그 끝 개행이 빈 줄로 남지 않도록 제거
        for (raw in log.trimEnd('\n', '\r').lines().map(::splitTimestamp)) {
            if (raw.time != null) {
                val second = raw.time.truncatedTo(ChronoUnit.SECONDS)
                // 로그는 시간순이므로 뒤로 가지 않는다
                val index = ordered.indexOfLast { it.startedAt != null && !it.startedAt.isAfter(second) }
                if (index > current) current = index
            }
            linesByStep[current].add(raw.content)
        }

        val out = mutableListOf<String>()
        val blocks = mutableListOf<StepBlock>()
        val groups = mutableListOf<IntRange>()
        val errors = mutableListOf<Int>()
        val warnings = mutableListOf<Int>()

        ordered.forEachIndexed { i, step ->
            val header = out.size
            out.add(step.name)
            val openGroups = ArrayDeque<Int>()
            for (line in linesByStep[i]) {
                when {
                    line.startsWith("##[endgroup]") -> openGroups.removeLastOrNull()?.let { start ->
                        if (out.lastIndex > start) groups.add(start..out.lastIndex)
                    }
                    line.startsWith("##[group]") -> {
                        openGroups.addLast(out.size)
                        out.add(line.removePrefix("##[group]"))
                    }
                    line.startsWith("##[error]") -> {
                        errors.add(out.size)
                        out.add(line.removePrefix("##[error]"))
                    }
                    line.startsWith("##[warning]") -> {
                        warnings.add(out.size)
                        out.add(line.removePrefix("##[warning]"))
                    }
                    else -> out.add(line)
                }
            }
            // 닫히지 않은 그룹은 Step 끝에서 닫는다
            while (openGroups.isNotEmpty()) {
                val start = openGroups.removeLast()
                if (out.lastIndex > start) groups.add(start..out.lastIndex)
            }
            blocks.add(StepBlock(step, header, out.lastIndex))
        }
        // Step 정보가 없으면 로그만 그대로
        if (ordered.isEmpty()) out.addAll(linesByStep[0])

        return ParsedJobLog(out.joinToString("\n"), blocks, groups, errors, warnings)
    }

    private fun splitTimestamp(line: String): RawLine {
        val clean = ANSI.replace(line.removePrefix("\uFEFF"), "")  // 첫 줄 BOM
        val match = TIMESTAMP.matchEntire(clean) ?: return RawLine(null, clean)
        val time = runCatching { Instant.parse(match.groupValues[1]) }.getOrNull()
        return RawLine(time, match.groupValues[2])
    }
}
