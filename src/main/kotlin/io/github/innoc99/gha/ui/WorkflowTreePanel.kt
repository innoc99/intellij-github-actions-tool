package io.github.innoc99.gha.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ide.BrowserUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.github.innoc99.gha.GhaBundle
import io.github.innoc99.gha.model.Workflow
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import javax.swing.tree.*

/**
 * 좌측 워크플로우 트리 패널
 * - 상단 고정 필터 텍스트박스
 * - "전체 히스토리" 특수 노드 + 워크플로우 노드
 * - 우클릭 컨텍스트 메뉴 (Dispatch 실행)
 */
class WorkflowTreePanel : JPanel(BorderLayout()) {

    /** 트리에서 선택 변경 시 콜백 (null이면 전체 히스토리, Workflow면 특정 워크플로우) */
    var onWorkflowSelected: ((Workflow?) -> Unit)? = null

    /** Dispatch 요청 콜백 */
    var onDispatchRequested: ((Workflow) -> Unit)? = null

    /** 워크플로우 옆에 표시할 요약 (마지막 성공 배포 버전·시각) */
    var workflowSummary: ((Workflow) -> String?)? = null

    /** 워크플로우 웹 URL 생성 콜백 */
    var getWorkflowWebUrl: ((Workflow) -> String?)? = null

    private val rootNode = DefaultMutableTreeNode("root")
    private val allHistoryNode = DefaultMutableTreeNode(ALL_HISTORY_MARKER)
    private val treeModel = DefaultTreeModel(rootNode)
    val tree = Tree(treeModel)

    private val filterField = SearchTextField(false)
    private var workflows: List<Workflow> = emptyList()

    /** 마지막으로 알린 선택 (워크플로우 id 또는 전체 히스토리 마커) */
    private var lastSelectionKey: Any? = null

    companion object {
        const val ALL_HISTORY_MARKER = "__ALL_HISTORY__"
    }

    init {
        setupUI()
    }

    private fun setupUI() {
        // 상단 필터 텍스트박스
        filterField.textEditor.emptyText.text = GhaBundle.message("tree.filter.placeholder")
        filterField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                applyFilter()
            }
        })
        filterField.border = JBUI.Borders.empty(4, 4, 4, 4)
        add(filterField, BorderLayout.NORTH)

        // 트리 설정
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = WorkflowTreeCellRenderer()

        rootNode.add(allHistoryNode)
        treeModel.reload()

        // 선택 이벤트
        tree.addTreeSelectionListener { e ->
            // reload로 선택이 풀렸다가 같은 항목으로 복원되는 경우는 알리지 않음 (상세·로그가 매번 지워지던 문제)
            if (!e.isAddedPath) return@addTreeSelectionListener
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val key = when (val obj = node.userObject) {
                ALL_HISTORY_MARKER -> ALL_HISTORY_MARKER
                is Workflow -> obj.id
                else -> return@addTreeSelectionListener
            }
            if (key == lastSelectionKey) return@addTreeSelectionListener
            lastSelectionKey = key
            onWorkflowSelected?.invoke(node.userObject as? Workflow)
        }

        // 우클릭 컨텍스트 메뉴
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = handlePopup(e)

            private fun handlePopup(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val workflow = node.userObject as? Workflow ?: return

                val popup = JPopupMenu()
                val dispatchItem = JMenuItem(GhaBundle.message("contextMenu.dispatch"), AllIcons.Actions.Execute)
                dispatchItem.addActionListener { onDispatchRequested?.invoke(workflow) }
                popup.add(dispatchItem)

                val webUrl = getWorkflowWebUrl?.invoke(workflow)
                if (webUrl != null) {
                    val webItem = JMenuItem(GhaBundle.message("contextMenu.openWeb"), AllIcons.General.Web)
                    webItem.addActionListener { BrowserUtil.browse(webUrl) }
                    popup.add(webItem)
                }
                popup.show(tree, e.x, e.y)
            }
        })

        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    /**
     * 필터 텍스트에 따라 트리 노드 필터링
     */
    private fun applyFilter() {
        val query = filterField.text.trim().lowercase()
        val previousSelection = getSelectedWorkflow()

        rootNode.removeAllChildren()
        rootNode.add(allHistoryNode)

        val filtered = if (query.isEmpty()) {
            workflows.sortedBy { it.name }
        } else {
            workflows.filter { it.name.lowercase().contains(query) }.sortedBy { it.name }
        }

        filtered.forEach { wf ->
            rootNode.add(DefaultMutableTreeNode(wf))
        }
        treeModel.reload()

        // 이전 선택 복원 시도
        if (previousSelection != null) {
            for (i in 0 until rootNode.childCount) {
                val node = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                val wf = node.userObject as? Workflow ?: continue
                if (wf.id == previousSelection.id) {
                    tree.selectionPath = TreePath(arrayOf(rootNode, node))
                    return
                }
            }
        }
    }

    /**
     * 워크플로우 목록을 트리에 반영
     */
    fun setWorkflows(newWorkflows: List<Workflow>) {
        workflows = newWorkflows
        applyFilter()

        // 이전 선택을 복원하지 못했고 필터가 비어있으면 전체 히스토리 선택
        if (tree.selectionPath == null && filterField.text.isBlank()) {
            tree.selectionPath = TreePath(arrayOf(rootNode, allHistoryNode))
        }
    }

    fun findWorkflow(id: Long): Workflow? = workflows.firstOrNull { it.id == id }

    /**
     * 현재 선택된 워크플로우 반환 (전체 히스토리면 null)
     */
    fun getSelectedWorkflow(): Workflow? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? Workflow
    }

    /**
     * 트리 셀 렌더러 — 워크플로우 이름 + 마지막 성공 요약(회색)
     */
    private inner class WorkflowTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                ALL_HISTORY_MARKER -> {
                    append(GhaBundle.message("tree.allHistory"))
                    icon = AllIcons.Vcs.History
                }
                is Workflow -> {
                    append(obj.name)
                    workflowSummary?.invoke(obj)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    icon = AllIcons.Actions.Execute
                }
            }
            border = JBUI.Borders.empty(2, 0)
        }
    }
}
