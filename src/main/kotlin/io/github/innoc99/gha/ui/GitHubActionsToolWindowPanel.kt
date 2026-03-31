package io.github.innoc99.gha.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.ListSpeedSearch
import io.github.innoc99.gha.model.Workflow
import io.github.innoc99.gha.model.WorkflowJob
import io.github.innoc99.gha.model.WorkflowRun
import io.github.innoc99.gha.service.ConnectionState
import io.github.innoc99.gha.service.ConnectionStateListener
import io.github.innoc99.gha.service.ConnectionStateManager
import io.github.innoc99.gha.service.GitHubApiService
import io.github.innoc99.gha.service.GitRemoteDetector
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
class GitHubActionsToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

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
    private val logCache = mutableMapOf<Long, String>()

    // 3분할 메인 스플릿 (로그 패널 표시/숨김 제어용)
    private lateinit var outerSplit: JSplitPane

    // Runs refresh 버튼 (필터 오른쪽)
    private val runsRefreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Runs 새로고침"
        isBorderPainted = false
        isContentAreaFilled = false
        addActionListener {
            if (!connectionState.isOnline) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val recovered = apiService.healthCheck()
                    if (recovered) {
                        connectionState.recordSuccess()
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            offlineBannerLabel.text = "오프라인 — 네트워크 연결을 확인해주세요"
                        }
                    }
                }
            } else {
                refreshRunsSilently()
            }
        }
    }

    // 현재 선택된 워크플로우
    private var selectedWorkflow: Workflow? = null

    // 현재 선택된 Job (로그 refresh용)
    private var currentJob: WorkflowJob? = null

    // 사일런트 갱신 중 선택 이벤트 무시 플래그
    private var suppressRunSelection = false

    // 자동 갱신 타이머
    private val listRefreshTimer = javax.swing.Timer(20_000) { refreshRunsSilently() }
    private val detailRefreshTimer = javax.swing.Timer(5_000) { refreshDetailIfInProgress() }

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

    companion object {
        private const val CARD_GUIDE = "guide"
        private const val CARD_MAIN = "main"
    }

    init {
        setupUI()
        // VCS 초기화 완료 후 로딩
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(1500)
            ApplicationManager.getApplication().invokeLater {
                refreshView()
            }
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
        contentPanel.add(createMainPanel(), CARD_MAIN)
        add(contentPanel, BorderLayout.CENTER)

        // 상태 전환 리스너 등록
        connectionState.addListener(object : ConnectionStateListener {
            override fun onStateChanged(newState: ConnectionState) {
                ApplicationManager.getApplication().invokeLater {
                    when (newState) {
                        ConnectionState.OFFLINE -> onGoOffline()
                        ConnectionState.ONLINE -> onGoOnline()
                    }
                }
            }
        })
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
        }
        treePanel.onDispatchRequested = { workflow ->
            showDispatchDialog(workflow)
        }
        treePanel.getWorkflowWebUrl = { workflow ->
            getWorkflowWebUrl(workflow)
        }
        treePanel.minimumSize = Dimension(300, 0)
        treePanel.preferredSize = Dimension(380, 0)

        // --- 2번+3번: 런 목록 + Jobs 상세 ---
        val centerPanel = JPanel(BorderLayout())

        val topPanel = JPanel(BorderLayout())
        topPanel.add(createFilterPanel(), BorderLayout.NORTH)

        runList.cellRenderer = WorkflowRunListCellRenderer()
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
                val index = runList.locationToIndex(e.point) ?: return
                runList.selectedIndex = index
                val selected = runList.selectedValue ?: return

                val popup = JPopupMenu()
                val webItem = JMenuItem("웹으로 이동", AllIcons.General.Web)
                webItem.addActionListener { BrowserUtil.browse(selected.htmlUrl) }
                popup.add(webItem)
                popup.show(runList, e.x, e.y)
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
            stepLogPanel.showJobLog(displayName, job.steps, cached)
            return
        }

        stepLogPanel.showLoading(displayName)
        stepLogPanel.setRefreshing(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val logs = apiService.fetchJobLogs(job.id)
            ApplicationManager.getApplication().invokeLater {
                val logContent = logs ?: "로그를 불러올 수 없습니다."
                logCache[job.id] = logContent
                stepLogPanel.showJobLog(displayName, job.steps, logContent)
                stepLogPanel.setRefreshing(false)
            }
        }
    }

    private fun createFilterPanel(): JPanel {
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))

        filterPanel.add(JLabel("상태:"))
        statusFilter.addItem("전체")
        statusFilter.addItem("success")
        statusFilter.addItem("failure")
        statusFilter.addItem("cancelled")
        statusFilter.addItem("in_progress")
        filterPanel.add(statusFilter)

        filterPanel.add(JLabel("브랜치:"))
        branchFilter.addItem("전체")
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
            add(object : AnAction("새로고침", "워크플로우 목록을 새로고침합니다", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    if (!connectionState.isOnline) {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val recovered = apiService.healthCheck()
                            if (recovered) {
                                connectionState.recordSuccess()
                            } else {
                                ApplicationManager.getApplication().invokeLater {
                                    offlineBannerLabel.text = "오프라인 — 네트워크 연결을 확인해주세요"
                                }
                            }
                        }
                    } else {
                        refreshView()
                    }
                }
            })
            add(object : AnAction("설정", "GitHub Actions Tool 설정을 엽니다", AllIcons.General.Settings) {
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
        val titleLabel = JLabel("GitHub Actions Tool 설정이 필요합니다")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(titleLabel, gbc)

        gbc.insets = Insets(5, 0, 15, 0)
        val descLabel = JLabel("<html><center>GitHub 인증이 필요합니다.<br>Version Control > GitHub에 계정을 등록하거나,<br>아래 설정에서 Personal Access Token을 입력해주세요.</center></html>")
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

    fun refreshView() {
        if (!isSettingsConfigured()) {
            cardLayout.show(contentPanel, CARD_GUIDE)
            listRefreshTimer.stop()
            detailRefreshTimer.stop()
            return
        }

        cardLayout.show(contentPanel, CARD_MAIN)
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
        detailPanel.clear()
        logCache.clear()
        hideLogPanel()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val workflows = apiService.fetchWorkflows()
                val runs = apiService.fetchWorkflowRuns(limit = 50)

                ApplicationManager.getApplication().invokeLater {
                    treePanel.setWorkflows(workflows)
                    allRuns.clear()
                    allRuns.addAll(runs)
                    updateBranchFilter()
                    applyFilters()
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    // 오프라인이면 기존 데이터 유지, 온라인 실패만 비움
                    if (connectionState.isOnline) {
                        allRuns.clear()
                        listModel.clear()
                    }
                }
            }
        }
    }

    private fun updateBranchFilter() {
        val selectedBranch = branchFilter.selectedItem as? String
        branchFilter.removeAllItems()
        branchFilter.addItem("전체")
        allRuns.map { it.headBranch }.distinct().sorted().forEach { branchFilter.addItem(it) }
        if (selectedBranch != null && branchFilter.getItemCount() > 0) {
            branchFilter.selectedItem = selectedBranch
        }
    }

    private fun applyFilters() {
        val previouslySelectedRunId = runList.selectedValue?.id
        val selectedStatus = statusFilter.selectedItem as? String ?: "전체"
        val selectedBranch = branchFilter.selectedItem as? String ?: "전체"

        val filtered = allRuns.filter { run ->
            val matchWorkflow = selectedWorkflow == null || run.workflowId == selectedWorkflow?.id
            val matchStatus = selectedStatus == "전체" || when (selectedStatus) {
                "success" -> run.conclusion == "success"
                "failure" -> run.conclusion == "failure"
                "cancelled" -> run.conclusion == "cancelled"
                "in_progress" -> run.isInProgress()
                else -> true
            }
            val matchBranch = selectedBranch == "전체" || run.headBranch == selectedBranch
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
        if (!isSettingsConfigured()) return

        runsRefreshButton.icon = AnimatedIcon.Default()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val runs = apiService.fetchWorkflowRuns(limit = 50)
                ApplicationManager.getApplication().invokeLater {
                    suppressRunSelection = true
                    try {
                        allRuns.clear()
                        allRuns.addAll(runs)
                        updateBranchFilter()
                        applyFilters()
                    } finally {
                        suppressRunSelection = false
                        runsRefreshButton.icon = AllIcons.Actions.Refresh
                    }
                }
            } catch (_: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    runsRefreshButton.icon = AllIcons.Actions.Refresh
                    if (!connectionState.isOnline) {
                        offlineBannerLabel.text = "오프라인 — 네트워크 연결을 확인해주세요"
                    }
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
            cachedRuns = allRuns.toList()
        )
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
        offlineBannerLabel.text = "오프라인 — 마지막 갱신: $timeText"
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
