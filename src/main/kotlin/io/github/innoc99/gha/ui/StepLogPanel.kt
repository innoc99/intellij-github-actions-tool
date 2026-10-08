package io.github.innoc99.gha.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.DocumentUtil
import com.intellij.util.ui.JBUI
import io.github.innoc99.gha.GhaBundle
import io.github.innoc99.gha.model.JobAnnotation
import io.github.innoc99.gha.model.WorkflowStep
import io.github.innoc99.gha.model.isActiveStatus
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.time.Duration
import javax.swing.*

/**
 * Job 로그 패널 — 읽기 전용 Editor 하나에 전체 로그를 표시
 * - Step·##[group] 단위 접기 (실패·진행 중 Step만 펼침)
 * - error/warning 줄 색상 + 스크롤바 마커
 * - 검색 하이라이트 + 해당 위치 자동 펼침, Enter로 다음 결과
 * 줄마다 Swing 컴포넌트를 만들던 방식은 수만 줄 로그에서 메모리·EDT 멈춤을 일으켜 교체했다.
 */
class StepLogPanel : JPanel(BorderLayout()), Disposable {

    private val document = EditorFactory.getInstance().createDocument("")
    private val editor = (EditorFactory.getInstance().createViewer(document) as EditorEx).apply {
        settings.isLineNumbersShown = true
        settings.isFoldingOutlineShown = true
        settings.isUseSoftWraps = false
        settings.isRightMarginShown = false
        settings.isCaretRowShown = false
        settings.additionalLinesCount = 0
        settings.additionalColumnsCount = 0
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

    // Job annotations (::error:: 등 실패 원인 요약)
    private val annotationsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(0, 12, 4, 12)
        isVisible = false
    }

    // 본문: 로그 Editor / 안내 메시지(로딩·실패)
    private val bodyLayout = CardLayout()
    private val bodyPanel = JPanel(bodyLayout)
    private val messageLabel = JBLabel().apply {
        border = JBUI.Borders.empty(16)
        verticalAlignment = SwingConstants.TOP
    }

    private val searchHighlighters = mutableListOf<RangeHighlighter>()
    private var searchMatches: List<Int> = emptyList()
    private var searchIndex = 0

    private companion object {
        const val MAX_ANNOTATIONS = 10
        const val MAX_SEARCH_MATCHES = 1000
        const val CARD_EDITOR = "editor"
        const val CARD_MESSAGE = "message"
    }

    init {
        val topPanel = JPanel(BorderLayout())
        val headerWrapper = JPanel(BorderLayout())
        headerWrapper.add(headerPanel, BorderLayout.CENTER)
        headerWrapper.add(refreshButton, BorderLayout.EAST)
        topPanel.add(headerWrapper, BorderLayout.NORTH)
        topPanel.add(annotationsPanel, BorderLayout.CENTER)
        topPanel.add(searchField, BorderLayout.SOUTH)
        searchField.border = JBUI.Borders.empty(0, 12, 4, 12)

        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = applyLogSearch()
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) moveToMatch(searchIndex + if (e.isShiftDown) -1 else 1)
            }
        })

        bodyPanel.add(editor.component, CARD_EDITOR)
        bodyPanel.add(JScrollPane(messageLabel).apply { border = null }, CARD_MESSAGE)

        add(topPanel, BorderLayout.NORTH)
        add(bodyPanel, BorderLayout.CENTER)
    }

    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(editor)
    }

    /**
     * 파싱된 Job 로그를 표시 (파싱은 호출 측이 EDT 밖에서 수행)
     * @param parsed null이면 [failureMessage] 표시
     */
    fun showJobLog(
        jobName: String,
        parsed: ParsedJobLog?,
        annotations: List<JobAnnotation> = emptyList(),
        failureMessage: String = GhaBundle.message("log.loadFailed")
    ) {
        setHeader(jobName)
        setRefreshing(false)
        showAnnotations(annotations)
        searchField.text = ""

        if (parsed == null) {
            showMessage(failureMessage)
            return
        }

        DocumentUtil.writeInRunUndoTransparentAction { document.setText(parsed.text) }
        applyMarkup(parsed)
        applyFolding(parsed)
        bodyLayout.show(bodyPanel, CARD_EDITOR)

        // 첫 error 줄(없으면 맨 위)로 이동
        val target = parsed.errorLines.firstOrNull()?.let { document.getLineStartOffset(it) } ?: 0
        editor.caretModel.moveToOffset(target)
        editor.scrollingModel.scrollToCaret(if (target == 0) ScrollType.MAKE_VISIBLE else ScrollType.CENTER)
    }

    /**
     * 로딩 중 표시
     */
    fun showLoading(jobName: String) {
        setHeader(jobName)
        setRefreshing(true)
        showMessage(GhaBundle.message("log.loading"))
    }

    fun clear() {
        showAnnotations(emptyList())
        headerPanel.removeAll()
        headerPanel.revalidate()
        refreshButton.isVisible = false
        searchField.text = ""
        // 큰 로그를 숨김 상태로 메모리에 두지 않음
        DocumentUtil.writeInRunUndoTransparentAction { document.setText("") }
        editor.markupModel.removeAllHighlighters()
    }

    fun setRefreshing(refreshing: Boolean) {
        refreshButton.icon = if (refreshing) AnimatedIcon.Default() else AllIcons.Actions.Refresh
    }

    private fun setHeader(jobName: String) {
        headerPanel.removeAll()
        headerPanel.add(JBLabel(jobName).apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }, BorderLayout.WEST)
        headerPanel.revalidate()
        refreshButton.isVisible = true
    }

    private fun showMessage(text: String) {
        messageLabel.text = text
        bodyLayout.show(bodyPanel, CARD_MESSAGE)
    }

    /** Step 헤더(굵게 + 상태 아이콘·소요 시간), error/warning 줄 색상과 스크롤바 마커 */
    private fun applyMarkup(parsed: ParsedJobLog) {
        val markup = editor.markupModel
        markup.removeAllHighlighters()
        searchHighlighters.clear()

        val headerAttributes = TextAttributes(null, null, null, null, Font.BOLD)
        for (block in parsed.steps) {
            markup.addLineHighlighter(block.headerLine, HighlighterLayer.ADDITIONAL_SYNTAX, headerAttributes).apply {
                gutterIconRenderer = StepStatusGutter(block.step)
            }
        }
        val errorAttributes = TextAttributes(JBColor.RED, null, null, null, Font.PLAIN).apply { errorStripeColor = JBColor.RED }
        parsed.errorLines.forEach { markup.addLineHighlighter(it, HighlighterLayer.ADDITIONAL_SYNTAX, errorAttributes) }
        val warningAttributes = TextAttributes(JBColor.ORANGE, null, null, null, Font.PLAIN).apply { errorStripeColor = JBColor.ORANGE }
        parsed.warningLines.forEach { markup.addLineHighlighter(it, HighlighterLayer.ADDITIONAL_SYNTAX, warningAttributes) }
    }

    /** Step·그룹 접기 — 실패·진행 중 Step만 펼치고 그룹은 모두 접는다 (GitHub 웹과 동일) */
    private fun applyFolding(parsed: ParsedJobLog) {
        val folding = editor.foldingModel
        folding.runBatchFoldingOperation {
            folding.clearFoldRegions()
            for (block in parsed.steps) {
                if (block.lastLine <= block.headerLine) continue
                val step = block.step
                val region = folding.addFoldRegion(
                    document.getLineEndOffset(block.headerLine),
                    document.getLineEndOffset(block.lastLine),
                    "  ${stepSummary(step)}"
                )
                region?.isExpanded = step.conclusion == "failure" || isActiveStatus(step.status)
            }
            for (group in parsed.groups) {
                folding.addFoldRegion(
                    document.getLineEndOffset(group.first),
                    document.getLineEndOffset(group.last),
                    " …"
                )?.isExpanded = false
            }
        }
    }

    /** 접힌 Step 헤더 옆 표시: 줄 수가 아니라 소요 시간 */
    private fun stepSummary(step: WorkflowStep): String = getDuration(step) ?: "…"

    /**
     * 로그 검색 — 매칭 하이라이트, 매칭이 있는 접힌 영역 펼침, 첫 결과로 이동
     */
    private fun applyLogSearch() {
        val markup = editor.markupModel
        searchHighlighters.forEach { markup.removeHighlighter(it) }
        searchHighlighters.clear()
        searchMatches = emptyList()

        val query = searchField.text.trim()
        if (query.isEmpty()) return

        val text = document.charsSequence
        val matches = mutableListOf<Int>()
        var index = StringUtil.indexOfIgnoreCase(text, query, 0)
        while (index >= 0 && matches.size < MAX_SEARCH_MATCHES) {
            matches.add(index)
            index = StringUtil.indexOfIgnoreCase(text, query, index + query.length)
        }

        val attributes = editor.colorsScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
        val folding = editor.foldingModel
        folding.runBatchFoldingOperation {
            for (start in matches) {
                searchHighlighters.add(
                    markup.addRangeHighlighter(
                        start, start + query.length, HighlighterLayer.SELECTION - 1, attributes, HighlighterTargetArea.EXACT_RANGE
                    )
                )
                // 바깥 영역부터 펼쳐질 때까지 반복
                var collapsed = folding.getCollapsedRegionAtOffset(start)
                while (collapsed != null) {
                    collapsed.isExpanded = true
                    collapsed = folding.getCollapsedRegionAtOffset(start)
                }
            }
        }
        searchMatches = matches
        moveToMatch(0)
    }

    private fun moveToMatch(index: Int) {
        if (searchMatches.isEmpty()) return
        searchIndex = Math.floorMod(index, searchMatches.size)
        editor.caretModel.moveToOffset(searchMatches[searchIndex])
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    /** 실패·경고 annotation을 로그 위에 요약 표시 (최대 10개) */
    private fun showAnnotations(annotations: List<JobAnnotation>) {
        annotationsPanel.removeAll()
        annotations.sortedByDescending { it.isFailure }.take(MAX_ANNOTATIONS).forEach { annotation ->
            val icon = when (annotation.level) {
                "failure" -> AllIcons.General.Error
                "warning" -> AllIcons.General.Warning
                else -> AllIcons.General.Information
            }
            val text = listOf(annotation.title, annotation.message).filter { it.isNotBlank() }.joinToString(" — ")
            annotationsPanel.add(JBLabel(StringUtil.shortenTextWithEllipsis(text.replace('\n', ' '), 200, 0), icon, SwingConstants.LEFT).apply {
                toolTipText = "<html><pre>${StringUtil.escapeXmlEntities(text)}</pre></html>"
                border = JBUI.Borders.empty(2, 0)
                if (annotation.isFailure) foreground = JBColor.RED
            })
        }
        annotationsPanel.isVisible = annotationsPanel.componentCount > 0
        annotationsPanel.revalidate()
    }

    private fun getDuration(step: WorkflowStep): String? {
        val start = step.startedAt ?: return null
        val end = step.completedAt ?: return null
        val seconds = Duration.between(start, end).seconds
        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
    }

    /** Step 헤더 줄의 상태 아이콘 */
    private inner class StepStatusGutter(private val step: WorkflowStep) : GutterIconRenderer() {
        override fun getIcon(): Icon = when {
            isActiveStatus(step.status) -> AllIcons.Actions.Execute
            step.conclusion == "success" -> AllIcons.RunConfigurations.TestPassed
            step.conclusion == "failure" -> AllIcons.RunConfigurations.TestFailed
            step.conclusion == "skipped" -> AllIcons.RunConfigurations.TestSkipped
            else -> AllIcons.RunConfigurations.TestUnknown
        }

        override fun getTooltipText(): String = listOfNotNull(step.name, getDuration(step)).joinToString(" · ")

        override fun equals(other: Any?): Boolean = other is StepStatusGutter && other.step == step

        override fun hashCode(): Int = step.hashCode()
    }
}
