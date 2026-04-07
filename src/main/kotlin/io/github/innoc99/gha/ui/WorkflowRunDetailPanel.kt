package io.github.innoc99.gha.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.github.innoc99.gha.GhaBundle
import io.github.innoc99.gha.model.WorkflowJob
import io.github.innoc99.gha.model.WorkflowRun
import io.github.innoc99.gha.service.GitHubApiService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 워크플로우 실행 상세 정보 패널 (헤더 + Jobs 그룹 트리)
 * Job 이름의 ' / ' 구분자로 그룹핑하여 트리 표시
 */
class WorkflowRunDetailPanel(
    private val project: Project
) : JPanel(BorderLayout()) {

    private val apiService = GitHubApiService.getInstance(project)
    private val headerPanel = JPanel(BorderLayout())
    private val rootNode = DefaultMutableTreeNode("Jobs")
    private val treeModel = DefaultTreeModel(rootNode)
    private val jobsTree = Tree(treeModel)

    // 현재 표시 중인 run (refresh용)
    private var currentRun: WorkflowRun? = null

    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = GhaBundle.message("tooltip.refreshJobs")
        isBorderPainted = false
        isContentAreaFilled = false
        addActionListener {
            currentRun?.let { refreshJobs(it) }
        }
    }

    /** Job(leaf 노드) 선택 시 콜백 (WorkflowJob) */
    var onJobSelected: ((WorkflowJob) -> Unit)? = null

    /** Job 선택 해제 시 콜백 */
    var onJobDeselected: (() -> Unit)? = null

    // 사일런트 갱신 중 선택 이벤트 무시 플래그
    private var suppressSelectionEvents = false

    private val emptyLabel = JBLabel(GhaBundle.message("detail.emptyState"), SwingConstants.CENTER)
    private val cardLayout = java.awt.CardLayout()
    private val contentCard = JPanel(cardLayout)

    /**
     * Job 그룹 노드 데이터 (접두사가 같은 Job들의 그룹)
     */
    data class JobGroup(
        val name: String,
        val jobs: List<WorkflowJob>
    ) {
        /** 그룹 상태: 자식 중 하나라도 failure면 failure, in_progress면 in_progress, 모두 success면 success */
        val status: String
            get() = when {
                jobs.any { it.conclusion == "failure" } -> "failure"
                jobs.any { it.status == "in_progress" || it.status == "queued" } -> "in_progress"
                jobs.all { it.conclusion == "success" } -> "success"
                jobs.any { it.conclusion == "cancelled" } -> "cancelled"
                jobs.any { it.conclusion == "skipped" } -> "skipped"
                else -> "unknown"
            }

        val conclusion: String? get() = if (status == "in_progress") null else status
    }

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_DETAIL = "detail"
    }

    init {
        setupUI()
    }

    private fun setupUI() {
        contentCard.add(emptyLabel, CARD_EMPTY)

        val detailPanel = JPanel(BorderLayout())

        // 헤더 + refresh 버튼
        headerPanel.border = JBUI.Borders.empty(8, 12)
        val headerWrapper = JPanel(BorderLayout())
        headerWrapper.add(headerPanel, BorderLayout.CENTER)
        headerWrapper.add(refreshButton, BorderLayout.EAST)
        detailPanel.add(headerWrapper, BorderLayout.NORTH)

        // Jobs 트리 설정
        jobsTree.isRootVisible = false
        jobsTree.showsRootHandles = true
        jobsTree.cellRenderer = JobTreeCellRenderer()

        // 트리 선택 시 콜백 호출
        jobsTree.addTreeSelectionListener { _ ->
            if (suppressSelectionEvents) return@addTreeSelectionListener
            val node = jobsTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            if (node == null) {
                onJobDeselected?.invoke()
                return@addTreeSelectionListener
            }
            when (val obj = node.userObject) {
                is WorkflowJob -> onJobSelected?.invoke(obj)
                else -> onJobDeselected?.invoke()
            }
        }

        detailPanel.add(JBScrollPane(jobsTree), BorderLayout.CENTER)
        contentCard.add(detailPanel, CARD_DETAIL)
        add(contentCard, BorderLayout.CENTER)

        cardLayout.show(contentCard, CARD_EMPTY)
    }

    /**
     * Job 목록을 ' / ' 구분자로 그룹핑하여 트리 구성
     */
    private fun buildJobTree(jobs: List<WorkflowJob>) {
        rootNode.removeAllChildren()

        // Job 이름에서 ' / '로 그룹핑
        val grouped = linkedMapOf<String, MutableList<WorkflowJob>>()
        val standalone = mutableListOf<WorkflowJob>()

        for (job in jobs) {
            val separatorIdx = job.name.indexOf(" / ")
            if (separatorIdx >= 0) {
                val prefix = job.name.substring(0, separatorIdx)
                grouped.getOrPut(prefix) { mutableListOf() }.add(job)
            } else {
                standalone.add(job)
            }
        }

        // 단독 Job과 그룹을 원래 순서 유지하며 추가
        // 순서: 첫 번째 등장 순서 기준
        val orderMap = linkedMapOf<String, Any>() // key -> WorkflowJob 또는 그룹명
        for (job in jobs) {
            val separatorIdx = job.name.indexOf(" / ")
            if (separatorIdx >= 0) {
                val prefix = job.name.substring(0, separatorIdx)
                if (prefix !in orderMap) {
                    orderMap[prefix] = prefix
                }
            } else {
                orderMap["__standalone_${job.id}"] = job
            }
        }

        for ((_, value) in orderMap) {
            when (value) {
                is WorkflowJob -> {
                    // 단독 Job
                    rootNode.add(DefaultMutableTreeNode(value))
                }
                is String -> {
                    // 그룹
                    val groupJobs = grouped[value] ?: continue
                    val group = JobGroup(value, groupJobs)
                    val groupNode = DefaultMutableTreeNode(group)
                    for (job in groupJobs) {
                        groupNode.add(DefaultMutableTreeNode(job))
                    }
                    rootNode.add(groupNode)
                }
            }
        }

        treeModel.reload()

        // 모든 그룹 펼치기
        for (i in 0 until jobsTree.rowCount) {
            jobsTree.expandRow(i)
        }
    }

    /**
     * 선택된 워크플로우 실행 정보를 표시
     */
    fun showRun(run: WorkflowRun) {
        currentRun = run
        updateHeader(run)
        cardLayout.show(contentCard, CARD_DETAIL)

        rootNode.removeAllChildren()
        treeModel.reload()

        // 백그라운드에서 Jobs 로딩
        setRefreshing(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val jobs = apiService.fetchWorkflowJobs(run.id)
            ApplicationManager.getApplication().invokeLater {
                buildJobTree(jobs)
                setRefreshing(false)
            }
        }
    }

    fun setRefreshing(refreshing: Boolean) {
        refreshButton.icon = if (refreshing) AnimatedIcon.Default() else AllIcons.Actions.Refresh
    }

    /**
     * 진행 중인 런의 Jobs를 사일런트 갱신 (선택/펼침 상태 보존)
     */
    fun refreshJobs(run: WorkflowRun) {
        // 현재 선택된 노드 정보 저장
        val selectedNode = jobsTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val selectedJobId = (selectedNode?.userObject as? WorkflowJob)?.id

        setRefreshing(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val jobs = apiService.fetchWorkflowJobs(run.id)
            ApplicationManager.getApplication().invokeLater {
                suppressSelectionEvents = true
                try {
                    // 펼침 상태 저장
                    val expandedRows = mutableSetOf<Int>()
                    for (i in 0 until jobsTree.rowCount) {
                        if (jobsTree.isExpanded(i)) expandedRows.add(i)
                    }

                    buildJobTree(jobs)

                    // 펼침 상태 복원
                    for (i in 0 until jobsTree.rowCount) {
                        if (i in expandedRows) jobsTree.expandRow(i)
                    }

                    // 선택 상태 복원
                    if (selectedJobId != null) {
                        restoreJobSelection(selectedJobId)
                    }
                } finally {
                    suppressSelectionEvents = false
                    setRefreshing(false)
                }
            }
        }
    }

    /**
     * jobId로 트리에서 해당 노드를 찾아 선택 복원
     */
    private fun restoreJobSelection(jobId: Long) {
        fun findJobNode(parent: DefaultMutableTreeNode): DefaultMutableTreeNode? {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                val obj = child.userObject
                if (obj is WorkflowJob && obj.id == jobId) return child
                // 그룹 노드 내부도 탐색
                val found = findJobNode(child)
                if (found != null) return found
            }
            return null
        }

        val node = findJobNode(rootNode)
        if (node != null) {
            jobsTree.selectionPath = TreePath(node.path)
        }
    }

    fun clear() {
        currentRun = null
        rootNode.removeAllChildren()
        treeModel.reload()
        headerPanel.removeAll()
        cardLayout.show(contentCard, CARD_EMPTY)
    }

    private fun updateHeader(run: WorkflowRun) {
        headerPanel.removeAll()

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val dateText = run.createdAt.atZone(ZoneId.systemDefault()).format(formatter)

        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JLabel(WorkflowRunListCellRenderer.getStatusIcon(run)))
            add(JBLabel(run.name).apply {
                font = font.deriveFont(java.awt.Font.BOLD, font.size2D + 2f)
            })
        }
        headerPanel.add(titlePanel, BorderLayout.NORTH)

        val actorText = run.actor?.let { " by $it" } ?: ""
        val conclusionText = run.conclusion ?: run.status
        val infoText = "#${run.runNumber}$actorText \u00b7 $conclusionText \u00b7 ${run.headBranch} \u00b7 $dateText"
        val infoLabel = JBLabel(infoText).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyTop(4)
        }
        headerPanel.add(infoLabel, BorderLayout.CENTER)

        headerPanel.revalidate()
        headerPanel.repaint()
    }

    /**
     * Jobs 트리 셀 렌더러 (Job + JobGroup 지원)
     */
    private class JobTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            backgroundNonSelectionColor = tree.background
            val node = value as? DefaultMutableTreeNode ?: return this

            when (val obj = node.userObject) {
                is WorkflowJob -> {
                    // leaf Job: 그룹 내 자식이면 짧은 이름, 단독이면 전체 이름
                    val displayName = if (node.parent?.let { (it as? DefaultMutableTreeNode)?.userObject } is JobGroup) {
                        val fullName = obj.name
                        val idx = fullName.indexOf(" / ")
                        if (idx >= 0) fullName.substring(idx + 3) else fullName
                    } else {
                        obj.name
                    }
                    val duration = getDuration(obj.startedAt, obj.completedAt)
                    text = if (duration != null) "$displayName  ($duration)" else displayName
                    icon = getStatusIcon(obj.status, obj.conclusion)
                }
                is JobGroup -> {
                    val duration = getGroupDuration(obj)
                    text = if (duration != null) "${obj.name}  ($duration)" else obj.name
                    icon = getStatusIcon(
                        if (obj.status == "in_progress") "in_progress" else "completed",
                        obj.conclusion
                    )
                }
            }

            border = JBUI.Borders.empty(1, 0)
            return this
        }

        private fun getStatusIcon(status: String, conclusion: String?): Icon {
            return when {
                status == "in_progress" || status == "queued" -> AllIcons.Actions.Execute
                conclusion == "success" -> AllIcons.RunConfigurations.TestPassed
                conclusion == "failure" -> AllIcons.RunConfigurations.TestFailed
                conclusion == "cancelled" || conclusion == "skipped" -> AllIcons.RunConfigurations.TestSkipped
                else -> AllIcons.RunConfigurations.TestUnknown
            }
        }

        private fun getDuration(start: java.time.Instant?, end: java.time.Instant?): String? {
            if (start == null || end == null) return null
            val seconds = Duration.between(start, end).seconds
            return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
        }

        private fun getGroupDuration(group: JobGroup): String? {
            val starts = group.jobs.mapNotNull { it.startedAt }
            val ends = group.jobs.mapNotNull { it.completedAt }
            if (starts.isEmpty() || ends.isEmpty()) return null
            val seconds = Duration.between(starts.min(), ends.max()).seconds
            return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
        }
    }
}
