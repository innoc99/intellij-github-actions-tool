package io.github.innoc99.gha.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.innoc99.gha.GhaBundle
import io.github.innoc99.gha.model.WorkflowStep
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * GitHub Enterprise 스타일 Step 로그 패널
 * - Step별 collapsible 섹션
 * - ##[group] 중첩 collapse
 * - 라인 넘버
 * - 타임스탬프 호버 툴팁
 */
class StepLogPanel : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val scrollPane = JBScrollPane(contentPanel).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
    }

    // 헤더 패널 (Job 이름 + 검색)
    private val headerPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8, 12, 4, 12)
    }
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = GhaBundle.message("log.search.placeholder")
    }

    /** 로그 refresh 요청 콜백 */
    var onRefreshRequested: (() -> Unit)? = null

    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = GhaBundle.message("log.refresh.tooltip")
        isBorderPainted = false
        isContentAreaFilled = false
        isVisible = false
        addActionListener { onRefreshRequested?.invoke() }
    }

    // 현재 표시 중인 Step 섹션 목록 (검색 시 접힘/펼침 제어)
    private val stepSections = mutableListOf<StepSection>()

    init {
        val topPanel = JPanel(BorderLayout())
        val headerWrapper = JPanel(BorderLayout())
        headerWrapper.add(headerPanel, BorderLayout.CENTER)
        headerWrapper.add(refreshButton, BorderLayout.EAST)
        topPanel.add(headerWrapper, BorderLayout.NORTH)
        topPanel.add(searchField, BorderLayout.SOUTH)
        searchField.border = JBUI.Borders.empty(0, 12, 4, 12)

        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                applyLogSearch()
            }
        })

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Job의 Steps와 로그를 표시
     * @param jobName Job 표시 이름
     * @param steps Step 목록
     * @param fullLog Job 전체 로그 (Step별로 파싱)
     */
    fun showJobLog(jobName: String, steps: List<WorkflowStep>, fullLog: String) {
        // 헤더 설정
        headerPanel.removeAll()
        val nameLabel = JBLabel(jobName).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        }
        headerPanel.add(nameLabel, BorderLayout.WEST)
        headerPanel.revalidate()
        refreshButton.isVisible = true
        setRefreshing(false)

        // 기존 내용 제거
        contentPanel.removeAll()
        stepSections.clear()
        searchField.text = ""

        // 로그를 Step별로 파싱
        val stepLogs = parseStepLogs(fullLog)

        for (step in steps) {
            val logLines = stepLogs[step.number] ?: emptyList()
            val section = StepSection(step, logLines)
            stepSections.add(section)
            contentPanel.add(section)
        }

        // 하단 여백
        contentPanel.add(Box.createVerticalGlue())

        contentPanel.revalidate()
        contentPanel.repaint()

        // 스크롤 최상단
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = 0
        }
    }

    /**
     * 로딩 중 표시
     */
    fun showLoading(jobName: String) {
        headerPanel.removeAll()
        val nameLabel = JBLabel(jobName).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        }
        headerPanel.add(nameLabel, BorderLayout.WEST)
        headerPanel.revalidate()
        refreshButton.isVisible = true
        setRefreshing(true)

        contentPanel.removeAll()
        contentPanel.add(JBLabel(GhaBundle.message("log.loading")).apply {
            border = JBUI.Borders.empty(16)
        })
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    fun clear() {
        headerPanel.removeAll()
        headerPanel.revalidate()
        refreshButton.isVisible = false
        contentPanel.removeAll()
        stepSections.clear()
        searchField.text = ""
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    fun setRefreshing(refreshing: Boolean) {
        refreshButton.icon = if (refreshing) AnimatedIcon.Default() else AllIcons.Actions.Refresh
    }

    /**
     * 로그 검색 — 매칭 라인 하이라이트 + 해당 Step/Group 자동 펼침
     */
    private fun applyLogSearch() {
        val query = searchField.text.trim().lowercase()

        for (section in stepSections) {
            section.applySearch(query)
        }

        contentPanel.revalidate()
        contentPanel.repaint()

        // 첫 번째 매칭 위치로 스크롤
        if (query.isNotEmpty()) {
            SwingUtilities.invokeLater {
                for (section in stepSections) {
                    val firstMatch = section.getFirstMatchPanel()
                    if (firstMatch != null) {
                        val rect = SwingUtilities.convertRectangle(firstMatch, firstMatch.bounds, contentPanel)
                        contentPanel.scrollRectToVisible(rect)
                        break
                    }
                }
            }
        }
    }

    /**
     * Job 전체 로그를 Step별로 파싱
     * GitHub Actions 로그: ##[group]Step이름 으로 Step 시작
     */
    private fun parseStepLogs(fullLog: String): Map<Int, List<LogLine>> {
        val lines = fullLog.lines()
        val result = mutableMapOf<Int, MutableList<LogLine>>()
        var currentStep = 0
        var lineNumber = 0

        for (line in lines) {
            if (line.contains("##[group]")) {
                currentStep++
                lineNumber = 0
            }
            lineNumber++

            val parsed = parseLogLine(line, lineNumber)
            result.getOrPut(currentStep) { mutableListOf() }.add(parsed)
        }

        return result
    }

    /**
     * 로그 라인 파싱: 타임스탬프 분리 + ANSI 코드 제거 + 특수 마커 감지
     */
    private fun parseLogLine(rawLine: String, lineNumber: Int): LogLine {
        // 타임스탬프 패턴: 2026-02-11T14:23.203917Z 형식
        val timestampRegex = Regex("^(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z)\\s*(.*)")
        val match = timestampRegex.find(rawLine)

        val timestamp = match?.groupValues?.get(1)
        val contentRaw = match?.groupValues?.get(2) ?: rawLine

        // ANSI 이스케이프 코드 제거
        val content = contentRaw.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")

        val type = when {
            content.contains("##[group]") -> LogLineType.GROUP_START
            content.contains("##[endgroup]") -> LogLineType.GROUP_END
            content.contains("##[error]") -> LogLineType.ERROR
            content.contains("##[warning]") -> LogLineType.WARNING
            else -> LogLineType.NORMAL
        }

        // ##[group], ##[endgroup] 등 마커 제거
        val displayContent = content
            .replace("##[group]", "")
            .replace("##[endgroup]", "")
            .replace("##[error]", "")
            .replace("##[warning]", "")

        return LogLine(lineNumber, timestamp, displayContent, type, rawLine)
    }

    /**
     * Step 섹션 (collapsible) + 검색 지원
     */
    private inner class StepSection(
        private val step: WorkflowStep,
        private val logLines: List<LogLine>
    ) : JPanel() {

        private var expanded = false
        private val toggleIcon = JLabel(AllIcons.General.ArrowRight)
        private val logContentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
        }

        // 검색용: 모든 로그 라인 패널 + 소속 LogLine 데이터
        private val linePanels = mutableListOf<Pair<JPanel, LogLine>>()
        private val groupPanels = mutableListOf<CollapsibleGroupPanel>()

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT

            val header = createStepHeader()
            add(header)
            add(logContentPanel)

            buildLogContent()
        }

        private fun createStepHeader(): JPanel {
            val header = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6, 8, 6, 12)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                maximumSize = Dimension(Int.MAX_VALUE, 32)
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
            val statusIcon = JLabel(getStepStatusIcon(step))
            val nameLabel = JBLabel(step.name)

            leftPanel.add(toggleIcon)
            leftPanel.add(statusIcon)
            leftPanel.add(nameLabel)
            header.add(leftPanel, BorderLayout.WEST)

            val duration = getDuration(step)
            if (duration != null) {
                header.add(JBLabel(duration).apply { foreground = JBColor.GRAY }, BorderLayout.EAST)
            }

            header.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    setExpanded(!expanded)
                }
            })

            val separator = JSeparator().apply {
                maximumSize = Dimension(Int.MAX_VALUE, 1)
                foreground = JBColor.border()
            }

            val wrapper = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = LEFT_ALIGNMENT
                add(header)
                add(separator)
            }
            return wrapper
        }

        fun setExpanded(value: Boolean) {
            expanded = value
            logContentPanel.isVisible = expanded
            toggleIcon.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            revalidate()
            repaint()
        }

        private fun buildLogContent() {
            var insideGroup = false
            var groupPanel: CollapsibleGroupPanel? = null

            for (line in logLines) {
                when (line.type) {
                    LogLineType.GROUP_START -> {
                        if (groupPanel != null) {
                            groupPanels.add(groupPanel)
                            logContentPanel.add(groupPanel)
                        }
                        groupPanel = CollapsibleGroupPanel(line.displayContent.trim())
                        insideGroup = true
                    }
                    LogLineType.GROUP_END -> {
                        if (groupPanel != null) {
                            groupPanels.add(groupPanel)
                            logContentPanel.add(groupPanel)
                            groupPanel = null
                        }
                        insideGroup = false
                    }
                    else -> {
                        val linePanel = createLogLinePanel(line)
                        linePanels.add(linePanel to line)
                        if (insideGroup && groupPanel != null) {
                            groupPanel.addLogLine(linePanel, line)
                        } else {
                            logContentPanel.add(linePanel)
                        }
                    }
                }
            }

            if (groupPanel != null) {
                groupPanels.add(groupPanel)
                logContentPanel.add(groupPanel)
            }
        }

        /**
         * 검색 적용: 매칭 라인이 있으면 해당 Step과 Group 자동 펼침 + 하이라이트
         */
        fun applySearch(query: String) {
            var hasMatch = false

            // 모든 라인의 하이라이트 초기화 및 매칭 확인
            for ((panel, line) in linePanels) {
                val matches = query.isNotEmpty() && line.displayContent.lowercase().contains(query)
                panel.background = if (matches) JBColor(Color(60, 60, 0), Color(60, 60, 0)) else null
                panel.isOpaque = matches
                if (matches) hasMatch = true
            }

            // 그룹 내부 검색 → 매칭 시 그룹 자동 펼침
            for (gp in groupPanels) {
                gp.applySearch(query)
            }

            // 매칭 있으면 Step 자동 펼침
            if (hasMatch && !expanded) {
                setExpanded(true)
            }
        }

        fun getFirstMatchPanel(): JPanel? {
            for ((panel, line) in linePanels) {
                if (panel.isOpaque) return panel
            }
            for (gp in groupPanels) {
                val match = gp.getFirstMatchPanel()
                if (match != null) return match
            }
            return null
        }

        private fun createLogLinePanel(line: LogLine): JPanel {
            val panel = JPanel(BorderLayout()).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 20)
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 12, 0, 8)
            }

            val lineNumLabel = JBLabel(String.format("%4d", line.lineNumber)).apply {
                foreground = JBColor.GRAY
                font = Font("JetBrains Mono", Font.PLAIN, 12)
                border = JBUI.Borders.emptyRight(12)
                preferredSize = Dimension(48, 16)
            }
            panel.add(lineNumLabel, BorderLayout.WEST)

            val textLabel = JBLabel(line.displayContent).apply {
                font = Font("JetBrains Mono", Font.PLAIN, 12)
                if (line.type == LogLineType.ERROR) foreground = JBColor.RED
                else if (line.type == LogLineType.WARNING) foreground = JBColor.YELLOW
            }
            panel.add(textLabel, BorderLayout.CENTER)

            if (line.timestamp != null) {
                panel.toolTipText = formatTimestamp(line.timestamp)
            }

            return panel
        }
    }

    /**
     * ##[group] 중첩 collapsible 패널 + 검색 지원
     */
    private inner class CollapsibleGroupPanel(
        private val groupName: String
    ) : JPanel() {

        private var expanded = false
        private val toggleIcon = JLabel(AllIcons.General.ArrowRight).apply {
            preferredSize = Dimension(16, 16)
        }
        private val groupContentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
        }
        private val groupLinePanels = mutableListOf<Pair<JPanel, LogLine>>()

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT

            val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 20)
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 12, 0, 8)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            val nameLabel = JBLabel(groupName).apply {
                font = Font("JetBrains Mono", Font.PLAIN, 12)
                foreground = JBColor.GRAY
            }

            header.add(toggleIcon)
            header.add(nameLabel)

            header.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    setExpanded(!expanded)
                }
            })

            add(header)
            add(groupContentPanel)
        }

        fun setExpanded(value: Boolean) {
            expanded = value
            groupContentPanel.isVisible = expanded
            toggleIcon.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            revalidate()
            repaint()
        }

        fun addLogLine(linePanel: JPanel, line: LogLine) {
            groupLinePanels.add(linePanel to line)
            groupContentPanel.add(linePanel)
        }

        fun applySearch(query: String) {
            var hasMatch = false
            for ((panel, line) in groupLinePanels) {
                val matches = query.isNotEmpty() && line.displayContent.lowercase().contains(query)
                panel.background = if (matches) JBColor(Color(60, 60, 0), Color(60, 60, 0)) else null
                panel.isOpaque = matches
                if (matches) hasMatch = true
            }
            if (hasMatch && !expanded) {
                setExpanded(true)
            }
        }

        fun getFirstMatchPanel(): JPanel? {
            return groupLinePanels.firstOrNull { it.first.isOpaque }?.first
        }
    }

    private val kstFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Seoul"))

    /**
     * GMT 타임스탬프를 KST로 변환하여 포맷팅
     */
    private fun formatTimestamp(timestamp: String): String {
        return try {
            val instant = Instant.parse(timestamp)
            kstFormatter.format(instant)
        } catch (_: Exception) {
            timestamp
        }
    }

    private fun getStepStatusIcon(step: WorkflowStep): Icon {
        return when {
            step.status == "in_progress" || step.status == "queued" -> AllIcons.Actions.Execute
            step.conclusion == "success" -> AllIcons.RunConfigurations.TestPassed
            step.conclusion == "failure" -> AllIcons.RunConfigurations.TestFailed
            step.conclusion == "skipped" -> AllIcons.RunConfigurations.TestSkipped
            else -> AllIcons.RunConfigurations.TestUnknown
        }
    }

    private fun getDuration(step: WorkflowStep): String? {
        val start = step.startedAt ?: return null
        val end = step.completedAt ?: return null
        val seconds = Duration.between(start, end).seconds
        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
    }

    /** 파싱된 로그 라인 */
    data class LogLine(
        val lineNumber: Int,
        val timestamp: String?,
        val displayContent: String,
        val type: LogLineType,
        val rawLine: String
    )

    enum class LogLineType {
        NORMAL, GROUP_START, GROUP_END, ERROR, WARNING
    }
}
