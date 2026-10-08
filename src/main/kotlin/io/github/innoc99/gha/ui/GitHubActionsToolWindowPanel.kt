package io.github.innoc99.gha.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import java.time.Instant
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.ListSpeedSearch
import io.github.innoc99.gha.GhaBundle
import io.github.innoc99.gha.model.JobAnnotation
import io.github.innoc99.gha.model.REDEPLOY_TAG_INPUT
import io.github.innoc99.gha.model.VersionTag
import io.github.innoc99.gha.model.findVersionTag
import io.github.innoc99.gha.model.isActiveStatus
import io.github.innoc99.gha.model.Workflow
import io.github.innoc99.gha.model.WorkflowJob
import io.github.innoc99.gha.model.WorkflowRun
import io.github.innoc99.gha.service.ConnectionState
import io.github.innoc99.gha.service.ConnectionStateListener
import io.github.innoc99.gha.service.ConnectionStateManager
import io.github.innoc99.gha.service.GitHubApiService
import io.github.innoc99.gha.service.GitRemoteDetector
import io.github.innoc99.gha.service.PostResult
import io.github.innoc99.gha.settings.GitHubActionsSettings
import io.github.innoc99.gha.settings.GitHubActionsSettingsConfigurable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * GitHub Actions Tool Window 메인 패널
 * 1번: 워크플로우 트리 / 2번: 런 목록 / 3번: Jobs 트리 / 4번: Step 로그 뷰
 */
class GitHubActionsToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val apiService = GitHubApiService.getInstance(project)
    private val cardLayout = CardLayout()
    private val contentPanel = JPanel(cardLayout)

    // 1번: 워크플로우 트리
    private val treePanel = WorkflowTreePanel()

    // 2번: 런 목록
    private val allRuns = mutableListOf<WorkflowRun>()
    private val listModel = DefaultListModel<WorkflowRun>()
    private val runList = JBList(listModel)

    // 3번: Jobs 상세 패널
    private val detailPanel = WorkflowRunDetailPanel(project)

    // 상태/브랜치 필터
    private val statusFilter = JComboBox<String>()
    private val branchFilter = JComboBox<String>()

    // Dispatch 버튼 + 웹 이동 버튼
    private val dispatchButton = JButton("Dispatch", AllIcons.Actions.Execute)
    private val webButton = JButton("Web", AllIcons.General.Web)

    // 4번: Step 로그 패널
    private val stepLogPanel = StepLogPanel()
    private val logCache = mutableMapOf<Long, JobLogEntry>()

    private data class JobLogEntry(val log: ParsedJobLog, val annotations: List<JobAnnotation>)

    // Step 로그 패널의 Editor 해제를 패널 수명에 연결
    init {
        Disposer.register(this, stepLogPanel)
    }

    // 3분할 메인 스플릿 (로그 패널 표시/숨김 제어용)
    private lateinit var outerSplit: JSplitPane

    // Runs refresh 버튼 (필터 오른쪽)
    private val runsRefreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = GhaBundle.message("tooltip.refreshRuns")
        isBorderPainted = false
        isContentAreaFilled = false
        addActionListener { refreshFromUser(::refreshRunsSilently) }
    }

    // 배포 버전 태그 (Run ↔ 배포 버전 연결)
    private var versionTags: List<VersionTag> = emptyList()

    // 워크플로우별 마지막 성공 Run (트리 요약 = phase별 현재 배포 현황)
    private val lastSuccessRuns = mutableMapOf<Long, WorkflowRun>()

    private val shortTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

    // 현재 선택된 워크플로우
    private var selectedWorkflow: Workflow? = null

    // 현재 선택된 Job (로그 refresh용)
    private var currentJob: WorkflowJob? = null

    // 사일런트 갱신 중 선택 이벤트 무시 플래그
    private var suppressRunSelection = false

    private val settings = GitHubActionsSettings.getInstance(project)

    // 자동 갱신 타이머 — 자동 갱신 꺼짐·IDE 백그라운드·Tool Window 숨김이면 건너뜀
    private val listRefreshTimer = javax.swing.Timer(listRefreshDelayMs()) {
        listRefreshTimerDelaySync()
        if (!canAutoPoll()) return@Timer
        // 첫 전체 로딩이 실패했으면 Run만 갱신하지 말고 전체 로딩 재시도 (안내 카드에 고착 방지)
        if (initialLoadDone) refreshRunsSilently() else loadData()
    }
    private val detailRefreshTimer = javax.swing.Timer(5_000) { if (canAutoPoll()) refreshDetailIfInProgress() }

    // 요청 진행 중 여부 (EDT 전용) — 응답 지연 시 타이머 요청이 쌓이지 않도록
    private var runsRefreshInFlight = false
    private var loadInFlight = false

    // 전체 로딩(워크플로우 목록)이 한 번이라도 성공했는지
    private var initialLoadDone = false

    // dispose 이후 대기 중이던 EDT 콜백이 release된 Editor·정지된 타이머를 건드리지 않도록
    @Volatile
    private var disposed = false
    private val expired = Condition<Any?> { disposed || project.isDisposed }

    // 태그를 마지막으로 조회한 시각 — 이후 성공한 Run이 있을 때만 다시 조회
    @Volatile
    private var tagsFetchedAt: Instant = Instant.EPOCH

    // 오프라인 배너
    private val offlineBanner = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        background = JBColor(Color(255, 243, 205), Color(77, 66, 30))
        border = JBUI.Borders.customLine(JBColor(Color(255, 193, 7), Color(150, 120, 30)), 0, 0, 1, 0)
        isVisible = false
    }
    private val offlineBannerLabel = JLabel().apply {
        icon = AllIcons.General.Warning
    }
    private val connectionState by lazy { ConnectionStateManager.getInstance(project) }

    private val connectionListener = object : ConnectionStateListener {
        override fun onStateChanged(newState: ConnectionState) {
            onEdt {
                when (newState) {
                    ConnectionState.OFFLINE -> onGoOffline()
                    ConnectionState.ONLINE -> onGoOnline()
                }
            }
        }
    }

    companion object {
        private val logger = Logger.getInstance(GitHubActionsToolWindowPanel::class.java)
        private const val CARD_GUIDE = "guide"
        private const val CARD_MAIN = "main"
        private const val CARD_NO_ACTIONS = "no_actions"
    }

    init {
        setupUI()
        // VCS 초기화 완료 후 로딩
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(1500)
            onEdt { refreshView() }
        }
    }

    private fun setupUI() {
        val topWrapper = JPanel(BorderLayout())
        topWrapper.add(createToolbar(), BorderLayout.NORTH)

        offlineBanner.add(offlineBannerLabel)
        offlineBanner.isVisible = false
        topWrapper.add(offlineBanner, BorderLayout.SOUTH)

        add(topWrapper, BorderLayout.NORTH)
        contentPanel.add(createGuidePanel(), CARD_GUIDE)
        contentPanel.add(createNoActionsPanel(), CARD_NO_ACTIONS)
        contentPanel.add(createMainPanel(), CARD_MAIN)
        add(contentPanel, BorderLayout.CENTER)

        // 상태 전환 리스너 등록 (dispose에서 해제)
        connectionState.addListener(connectionListener)
    }

    /** Tool Window 제거·프로젝트 종료 시 호출 — 타이머와 리스너 해제 */
    override fun dispose() {
        disposed = true
        listRefreshTimer.stop()
        detailRefreshTimer.stop()
        connectionState.removeListener(connectionListener)
    }

    /** 패널이 살아 있을 때만 EDT에서 실행 */
    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ action() }, expired)
    }

    /** 자동 polling 허용 여부: 자동 갱신 켜짐 + IDE 활성 + Tool Window 표시 중 */
    private fun canAutoPoll(): Boolean =
        settings.state.autoRefreshEnabled && ApplicationManager.getApplication().isActive && isShowing

    private fun listRefreshDelayMs(): Int = settings.state.refreshIntervalSeconds.coerceAtLeast(10) * 1000

    /** 설정 화면에서 갱신 주기를 바꾸면 다음 tick부터 반영 */
    private fun listRefreshTimerDelaySync() {
        val delay = listRefreshDelayMs()
        if (listRefreshTimer.delay != delay) listRefreshTimer.delay = delay
    }

    /**
     * 사용자 새로고침 (툴바·Tools 메뉴·Runs 버튼). OFFLINE이면 health-check로 복구를 먼저 시도한다.
     * 복구되면 ONLINE 전환 리스너가 전체 리로드를 수행한다.
     */
    fun refreshFromUser(onlineAction: () -> Unit = ::refreshView) {
        if (connectionState.isOnline) {
            onlineAction()
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (apiService.healthCheck()) {
                connectionState.recordSuccess()
            } else {
                onEdt { offlineBannerLabel.text = GhaBundle.message("banner.offline.checkNetwork") }
            }
        }
    }

    private fun createMainPanel(): JPanel {
        val mainPanel = JPanel(BorderLayout())

        // --- 1번: 워크플로우 트리 ---
        treePanel.onWorkflowSelected = { workflow ->
            selectedWorkflow = workflow
            dispatchButton.isEnabled = workflow != null
            webButton.isEnabled = workflow != null
            applyFilters()
            detailPanel.clear()
            hideLogPanel()
            // 전체 최근 Run에는 드물게 도는 워크플로우가 없을 수 있으므로 해당 워크플로우 Run을 따로 조회
            refreshRunsSilently()
        }
        treePanel.onDispatchRequested = { workflow ->
            showDispatchDialog(workflow)
        }
        treePanel.workflowSummary = { workflow -> workflowSummary(workflow) }
        treePanel.getWorkflowWebUrl = { workflow ->
            getWorkflowWebUrl(workflow)
        }
        treePanel.minimumSize = Dimension(300, 0)
        treePanel.preferredSize = Dimension(380, 0)

        // --- 2번+3번: 런 목록 + Jobs 상세 ---
        val centerPanel = JPanel(BorderLayout())

        val topPanel = JPanel(BorderLayout())
        topPanel.add(createFilterPanel(), BorderLayout.NORTH)

        runList.cellRenderer = WorkflowRunListCellRenderer { run -> versionOf(run) }
        runList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        ListSpeedSearch.installOn(runList) { it.name }

        // 런 선택 시 상세 패널 업데이트
        runList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !suppressRunSelection) {
                val selected = runList.selectedValue
                if (selected != null) {
                    logCache.clear()
                    hideLogPanel()
                    detailPanel.showRun(selected)
                }
            }
        }

        // 더블클릭 시 브라우저에서 열기 + 우클릭 컨텍스트 메뉴
        runList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = runList.selectedValue ?: return
                    BrowserUtil.browse(selected.htmlUrl)
                }
            }

            override fun mousePressed(e: MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)

            private fun handlePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val index = runList.locationToIndex(e.point)
                if (index < 0) return
                runList.selectedIndex = index
                val selected = runList.selectedValue ?: return

                buildRunPopup(selected).show(runList, e.x, e.y)
            }
        })

        topPanel.add(JBScrollPane(runList), BorderLayout.CENTER)

        val centerSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, detailPanel).apply {
            resizeWeight = 0.4
            dividerSize = 1
            border = null
            setUI(createThinDividerUI())
        }
        centerPanel.add(centerSplit, BorderLayout.CENTER)

        // Job 선택 콜백 설정
        detailPanel.onJobSelected = { job ->
            currentJob = job
            showLogPanel()
            loadJobLog(job)
        }
        detailPanel.onJobUpdated = { job -> if (currentJob?.id == job.id) currentJob = job }
        detailPanel.onJobDeselected = {
            currentJob = null
            hideLogPanel()
        }

        // 로그 패널 refresh 콜백
        stepLogPanel.onRefreshRequested = {
            currentJob?.let { job ->
                logCache.remove(job.id)
                loadJobLog(job)
            }
        }

        // 1번 트리 + 2번+3번 패널
        val leftSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, centerPanel).apply {
            dividerLocation = 320
            dividerSize = 1
            border = null
            setUI(createThinDividerUI())
        }

        // (1번+2번+3번) + 4번 로그
        outerSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, stepLogPanel).apply {
            resizeWeight = 0.0
            dividerSize = 1
            border = null
            setUI(createThinDividerUI())
        }

        // 초기 상태: 로그 패널 숨김
        stepLogPanel.isVisible = false
        outerSplit.dividerSize = 0

        mainPanel.add(outerSplit, BorderLayout.CENTER)
        return mainPanel
    }

    private fun createThinDividerUI(): javax.swing.plaf.basic.BasicSplitPaneUI {
        return object : javax.swing.plaf.basic.BasicSplitPaneUI() {
            override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider {
                return object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    override fun paint(g: Graphics) {
                        g.color = JBColor.border()
                        g.fillRect(0, 0, width, height)
                    }
                }
            }
        }
    }

    private fun showLogPanel() {
        if (stepLogPanel.isVisible) return
        stepLogPanel.isVisible = true
        outerSplit.dividerSize = 1
        outerSplit.dividerLocation = 1000
        outerSplit.revalidate()
    }

    private fun hideLogPanel() {
        if (!stepLogPanel.isVisible) return
        stepLogPanel.isVisible = false
        outerSplit.dividerSize = 0
        stepLogPanel.clear()
        outerSplit.revalidate()
    }

    /**
     * Job 선택 시 해당 Job의 전체 Steps 로그 로딩
     */
    private fun loadJobLog(job: WorkflowJob) {
        // Job 표시명 (그룹 내 자식이면 짧은 이름)
        val displayName = job.name

        val cached = logCache[job.id]
        if (cached != null) {
            stepLogPanel.showJobLog(displayName, cached.log, cached.annotations)
            return
        }

        stepLogPanel.showLoading(displayName)
        stepLogPanel.setRefreshing(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val logs = apiService.fetchJobLogs(job.id)
            val annotations = apiService.fetchJobAnnotations(job.id).orEmpty()
            // 수십 MB 로그도 있으므로 파싱은 EDT 밖에서
            val parsed = logs?.let { JobLogParser.parse(it, job.steps) }
            onEdt {
                // 실패는 캐시하지 않음 — 다시 선택하거나 새로고침하면 재조회
                if (parsed != null) logCache[job.id] = JobLogEntry(parsed, annotations)
                // 그사이 다른 Job을 골랐거나 로그 패널을 닫았으면 표시하지 않음
                if (currentJob?.id != job.id || !stepLogPanel.isVisible) return@onEdt
                // 진행 중 Job은 완료 전까지 로그 API가 응답하지 않을 수 있음
                val failure = GhaBundle.message(if (isActiveStatus(job.status)) "log.notReady" else "log.loadFailed")
                stepLogPanel.showJobLog(displayName, parsed, annotations, failure)
            }
        }
    }

    private fun createFilterPanel(): JPanel {
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))

        filterPanel.add(JLabel(GhaBundle.message("filter.status")))
        statusFilter.addItem(GhaBundle.message("filter.all"))
        statusFilter.addItem("success")
        statusFilter.addItem("failure")
        statusFilter.addItem("cancelled")
        statusFilter.addItem("in_progress")
        filterPanel.add(statusFilter)

        filterPanel.add(JLabel(GhaBundle.message("filter.branch")))
        branchFilter.addItem(GhaBundle.message("filter.all"))
        filterPanel.add(branchFilter)

        // Dispatch 버튼
        dispatchButton.isEnabled = false
        dispatchButton.addActionListener {
            val wf = selectedWorkflow ?: return@addActionListener
            showDispatchDialog(wf)
        }
        filterPanel.add(dispatchButton)

        // 웹으로 이동 버튼
        webButton.isEnabled = false
        webButton.addActionListener {
            val wf = selectedWorkflow ?: return@addActionListener
            val url = getWorkflowWebUrl(wf) ?: return@addActionListener
            BrowserUtil.browse(url)
        }
        filterPanel.add(webButton)

        val filterAction = { _: java.awt.event.ActionEvent? -> applyFilters() }
        statusFilter.addActionListener(filterAction)
        branchFilter.addActionListener(filterAction)

        // Refresh 버튼 (오른쪽 배치)
        val wrapper = JPanel(BorderLayout())
        wrapper.add(filterPanel, BorderLayout.CENTER)
        wrapper.add(runsRefreshButton, BorderLayout.EAST)
        return wrapper
    }

    /**
     * 워크플로우의 GitHub 웹 URL 생성
     */
    private fun getWorkflowWebUrl(workflow: Workflow): String? {
        val info = GitRemoteDetector.detect(project) ?: return null
        val baseUrl = info.baseUrl.trimEnd('/')
        return "$baseUrl/${info.owner}/${info.repository}/actions/workflows/${workflow.path.substringAfterLast('/')}"
    }

    private fun createToolbar(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction(GhaBundle.message("toolbar.refresh"), GhaBundle.message("toolbar.refresh.description"), AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshFromUser()
            })
            add(object : AnAction(GhaBundle.message("toolbar.settings"), GhaBundle.message("toolbar.settings.description"), AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project, GitHubActionsSettingsConfigurable::class.java
                    )
                }
            })
        }

        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("GitHubActionsToolbar", actionGroup, true)
        actionToolbar.targetComponent = this

        val toolbarPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        toolbarPanel.add(actionToolbar.component)
        return toolbarPanel
    }

    private fun createGuidePanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            anchor = GridBagConstraints.CENTER
            fill = GridBagConstraints.NONE
        }

        val iconLabel = JLabel(AllIcons.General.Information)
        iconLabel.horizontalAlignment = SwingConstants.CENTER
        panel.add(iconLabel, gbc)

        gbc.insets = Insets(10, 0, 5, 0)
        val titleLabel = JLabel(GhaBundle.message("guide.title"))
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(titleLabel, gbc)

        gbc.insets = Insets(5, 0, 15, 0)
        val descLabel = JLabel(GhaBundle.message("guide.description"))
        descLabel.horizontalAlignment = SwingConstants.CENTER
        panel.add(descLabel, gbc)

        gbc.insets = Insets(0, 0, 0, 0)
        val linkLabel = JLabel("<html><a href=''>Settings > Tools > GitHub Actions Tool</a></html>")
        linkLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        linkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project, GitHubActionsSettingsConfigurable::class.java
                )
            }
        })
        panel.add(linkLabel, gbc)

        return panel
    }

    private fun createNoActionsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            anchor = GridBagConstraints.CENTER
            fill = GridBagConstraints.NONE
        }

        val iconLabel = JLabel(AllIcons.General.Information)
        iconLabel.horizontalAlignment = SwingConstants.CENTER
        panel.add(iconLabel, gbc)

        gbc.insets = Insets(10, 0, 5, 0)
        val titleLabel = JLabel(GhaBundle.message("noActions.title"))
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(titleLabel, gbc)

        gbc.insets = Insets(5, 0, 15, 0)
        val descLabel = JLabel(GhaBundle.message("noActions.description"))
        descLabel.horizontalAlignment = SwingConstants.CENTER
        panel.add(descLabel, gbc)

        return panel
    }

    fun refreshView() {
        if (!isSettingsConfigured()) {
            cardLayout.show(contentPanel, CARD_GUIDE)
            listRefreshTimer.stop()
            detailRefreshTimer.stop()
            return
        }

        loadData()
        listRefreshTimer.restart()
        detailRefreshTimer.restart()
    }

    private fun isSettingsConfigured(): Boolean {
        val hasToken = apiService.hasValidToken()
        val hasVcs = GitRemoteDetector.detect(project) != null
        return hasToken && hasVcs
    }

    private fun loadData() {
        if (loadInFlight) return
        loadInFlight = true
        detailPanel.clear()
        logCache.clear()
        hideLogPanel()

        val workflowId = selectedWorkflow?.id
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val workflows = apiService.fetchWorkflows()
                val runs = workflows?.let { apiService.fetchWorkflowRuns(workflowId, limit = 50) }
                val tags = runs?.let { apiService.fetchVersionTags() }
                // 워크플로우 수만큼 호출 — 전체 로딩 시에만 하고, 이후는 Run 갱신 결과로 보정
                val lastSuccess = if (runs == null) emptyList() else workflows
                    .mapNotNull { wf -> apiService.fetchWorkflowRuns(wf.id, limit = 1, status = "success")?.firstOrNull() }
                LoadResult(workflows, runs, tags, lastSuccess)
            }.onFailure { logger.warn("전체 로딩 실패", it) }.getOrNull()
            if (result?.tags != null) tagsFetchedAt = Instant.now()

            onEdt {
                loadInFlight = false
                val workflows = result?.workflows
                val runs = result?.runs
                // 조회 실패 → 기존 화면 유지, 다음 tick에 전체 로딩 재시도
                if (workflows == null || runs == null) return@onEdt
                initialLoadDone = true
                if (workflows.isEmpty()) {
                    // Actions 미설정 → 안내 카드 표시, 타이머 정지
                    cardLayout.show(contentPanel, CARD_NO_ACTIONS)
                    listRefreshTimer.stop()
                    detailRefreshTimer.stop()
                    return@onEdt
                }
                cardLayout.show(contentPanel, CARD_MAIN)
                result.tags?.let { versionTags = it }
                lastSuccessRuns.clear()
                result.lastSuccess.forEach { lastSuccessRuns[it.workflowId] = it }
                treePanel.setWorkflows(workflows)
                allRuns.clear()
                allRuns.addAll(runs)
                updateBranchFilter()
                applyFilters()
                // 로딩 중 워크플로우 선택이 바뀌었으면 해당 워크플로우 Run으로 다시 조회
                if (workflowId != selectedWorkflow?.id) refreshRunsSilently()
            }
        }
    }

    private data class LoadResult(
        val workflows: List<Workflow>?,
        val runs: List<WorkflowRun>?,
        val tags: List<VersionTag>?,
        val lastSuccess: List<WorkflowRun>
    )

    private fun updateBranchFilter() {
        val selectedBranch = branchFilter.selectedItem as? String
        branchFilter.removeAllItems()
        branchFilter.addItem(GhaBundle.message("filter.all"))
        allRuns.map { it.headBranch }.distinct().sorted().forEach { branchFilter.addItem(it) }
        if (selectedBranch != null && branchFilter.getItemCount() > 0) {
            branchFilter.selectedItem = selectedBranch
        }
    }

    private fun applyFilters() {
        val previouslySelectedRunId = runList.selectedValue?.id
        val allLabel = GhaBundle.message("filter.all")
        val selectedStatus = statusFilter.selectedItem as? String ?: allLabel
        val selectedBranch = branchFilter.selectedItem as? String ?: allLabel

        val filtered = allRuns.filter { run ->
            val matchWorkflow = selectedWorkflow == null || run.workflowId == selectedWorkflow?.id
            val matchStatus = selectedStatus == allLabel || when (selectedStatus) {
                "success" -> run.conclusion == "success"
                "failure" -> run.conclusion == "failure"
                "cancelled" -> run.conclusion == "cancelled"
                "in_progress" -> run.isInProgress()
                else -> true
            }
            val matchBranch = selectedBranch == allLabel || run.headBranch == selectedBranch
            matchWorkflow && matchStatus && matchBranch
        }

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

        if (previouslySelectedRunId != null) {
            for (i in 0 until listModel.size()) {
                if (listModel.getElementAt(i).id == previouslySelectedRunId) {
                    runList.selectedIndex = i
                    break
                }
            }
        }
    }

    private fun refreshRunsSilently() {
        if (runsRefreshInFlight || !isSettingsConfigured()) return

        runsRefreshInFlight = true
        runsRefreshButton.icon = AnimatedIcon.Default()
        val workflowId = selectedWorkflow?.id
        ApplicationManager.getApplication().executeOnPooledThread {
            // 예외·Error가 나도 플래그가 풀리도록 결과와 무관하게 EDT 복귀
            val runs = runCatching { apiService.fetchWorkflowRuns(workflowId, limit = 50) }
                .onFailure { logger.warn("Run 목록 갱신 실패", it) }.getOrNull()
            // 태그는 마지막 조회 이후 성공한 Run(새 real 배포 가능성)이 있을 때만 다시 조회
            val fetchedAt = tagsFetchedAt
            val tags = if (runs != null && runs.any { it.isSuccess() && it.updatedAt.isAfter(fetchedAt) }) {
                runCatching { apiService.fetchVersionTags() }.getOrNull()?.also { tagsFetchedAt = Instant.now() }
            } else null

            onEdt {
                runsRefreshInFlight = false
                runsRefreshButton.icon = AllIcons.Actions.Refresh
                // 요청 중 워크플로우 선택이 바뀌었으면 결과와 무관하게 다시 조회
                if (workflowId != selectedWorkflow?.id) {
                    refreshRunsSilently()
                    return@onEdt
                }
                if (tags != null) versionTags = tags
                // 실패 시 기존 목록 유지
                if (runs == null) {
                    if (!connectionState.isOnline) offlineBannerLabel.text = GhaBundle.message("banner.offline.checkNetwork")
                    return@onEdt
                }
                suppressRunSelection = true
                try {
                    allRuns.clear()
                    allRuns.addAll(runs)
                    updateLastSuccessRuns(runs)
                    updateBranchFilter()
                    applyFilters()
                } finally {
                    suppressRunSelection = false
                }
            }
        }
    }

    /**
     * Dispatch 다이얼로그 표시 (캐시된 runs 활용 + 로딩 애니메이션)
     */
    private fun showDispatchDialog(workflow: Workflow) {
        WorkflowDispatchDialog.showWithInputs(
            project, workflow,
            cachedRuns = allRuns.toList(),
            onDispatched = ::refreshRunsSilently,
            versionSuggestions = versionSuggestions()
        )
    }

    /**
     * 선택한 Run의 버전으로 재배포(롤백) — redeploy_tag 입력을 미리 채운 Dispatch
     */
    private fun showRedeployDialog(run: WorkflowRun, version: String) {
        val workflow = treePanel.findWorkflow(run.workflowId) ?: return
        WorkflowDispatchDialog.showWithInputs(
            project, workflow,
            cachedRuns = allRuns.toList(),
            onDispatched = ::refreshRunsSilently,
            presetInputs = mapOf(REDEPLOY_TAG_INPUT to version),
            versionSuggestions = versionSuggestions(),
            branchOverride = run.headBranch
        )
    }

    private fun versionOf(run: WorkflowRun): String? = findVersionTag(run, versionTags)?.version

    /** 최근 버전 태그 (최신순, 중복 제거) — redeploy_tag 선택지 */
    private fun versionSuggestions(): List<String> =
        versionTags.sortedByDescending { it.createdAt }.map { it.version }.distinct().take(20)

    /** 트리 요약: ✓ 버전(없으면 #번호) · 시각 */
    private fun workflowSummary(workflow: Workflow): String? {
        val run = lastSuccessRuns[workflow.id] ?: return null
        val label = versionOf(run) ?: "#${run.runNumber}"
        val time = run.updatedAt.atZone(ZoneId.systemDefault()).format(shortTimeFormatter)
        return "\u2713 $label \u00b7 $time"
    }

    /** 갱신된 Run 중 더 최근 성공이 있으면 트리 요약 보정 (추가 API 호출 없음) */
    private fun updateLastSuccessRuns(runs: List<WorkflowRun>) {
        var changed = false
        runs.filter { it.isSuccess() }.groupBy { it.workflowId }.forEach { (id, list) ->
            val latest = list.maxBy { it.updatedAt }
            val current = lastSuccessRuns[id]
            if (current == null || latest.updatedAt > current.updatedAt) {
                lastSuccessRuns[id] = latest
                changed = true
            }
        }
        if (changed) treePanel.tree.repaint()
    }

    /**
     * Run 우클릭 메뉴: 웹 열기 / 다시 실행 / 실패 Job만 다시 실행 / 취소
     */
    private fun buildRunPopup(run: WorkflowRun): JPopupMenu {
        val popup = JPopupMenu()
        popup.add(JMenuItem(GhaBundle.message("contextMenu.openWeb"), AllIcons.General.Web).apply {
            addActionListener { BrowserUtil.browse(run.htmlUrl) }
        })
        popup.addSeparator()
        popup.add(JMenuItem(GhaBundle.message("run.rerun"), AllIcons.Actions.Restart).apply {
            isEnabled = run.isCompleted()
            addActionListener { runAction(GhaBundle.message("run.rerun"), run) { apiService.rerunWorkflowRun(it.id) } }
        })
        popup.add(JMenuItem(GhaBundle.message("run.rerunFailed"), AllIcons.Actions.Restart).apply {
            isEnabled = run.isCompleted() && (run.isFailed() || run.isCancelled())
            addActionListener { runAction(GhaBundle.message("run.rerunFailed"), run) { apiService.rerunFailedJobs(it.id) } }
        })
        val version = versionOf(run)
        popup.add(JMenuItem(GhaBundle.message("run.redeploy", version ?: "-"), AllIcons.Actions.Rollback).apply {
            isEnabled = version != null
            toolTipText = GhaBundle.message("run.redeploy.tooltip")
            addActionListener { version?.let { showRedeployDialog(run, it) } }
        })
        popup.add(JMenuItem(GhaBundle.message("run.cancel"), AllIcons.Actions.Suspend).apply {
            isEnabled = run.isInProgress()
            addActionListener {
                val confirmed = Messages.showYesNoDialog(
                    project, GhaBundle.message("run.cancel.confirm", run.name, run.runNumber),
                    GhaBundle.message("run.cancel"), null
                ) == Messages.YES
                if (confirmed) runAction(GhaBundle.message("run.cancel"), run) { apiService.cancelWorkflowRun(it.id) }
            }
        })
        return popup
    }

    /** Run 대상 POST 액션을 백그라운드에서 실행하고 결과를 알림으로 표시 */
    private fun runAction(title: String, run: WorkflowRun, call: (WorkflowRun) -> PostResult) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = call(run)
            onEdt {
                val target = "${run.name} #${run.runNumber}"
                if (result.success) {
                    GhaNotifications.info(project, GhaBundle.message("run.action.success", title, target))
                    refreshRunsSilently()
                } else {
                    GhaNotifications.error(project, GhaBundle.message("run.action.failure", title, target, result.errorMessage.orEmpty()))
                }
            }
        }
    }

    /**
     * OFFLINE 전환 시: 타이머 정지, 배너 표시
     */
    private fun onGoOffline() {
        listRefreshTimer.stop()
        detailRefreshTimer.stop()

        val timeText = connectionState.lastSuccessTime?.let {
            it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        } ?: "-"
        offlineBannerLabel.text = GhaBundle.message("banner.offline.lastUpdate", timeText)
        offlineBanner.isVisible = true
        offlineBanner.parent?.revalidate()
    }

    /**
     * ONLINE 복귀 시: 배너 숨김, 타이머 재시작, 데이터 리로드
     */
    private fun onGoOnline() {
        offlineBanner.isVisible = false
        offlineBanner.parent?.revalidate()

        if (isSettingsConfigured()) {
            loadData()
            listRefreshTimer.restart()
            detailRefreshTimer.restart()
        }
    }

    private fun refreshDetailIfInProgress() {
        val selectedRun = runList.selectedValue ?: return
        if (!selectedRun.isInProgress()) return

        logCache.clear()
        detailPanel.refreshJobs(selectedRun)
    }
}
