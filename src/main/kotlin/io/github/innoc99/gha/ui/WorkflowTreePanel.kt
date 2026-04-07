package io.github.innoc99.gha.ui

import com.intellij.icons.AllIcons
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

    /** 워크플로우 웹 URL 생성 콜백 */
    var getWorkflowWebUrl: ((Workflow) -> String?)? = null

    private val rootNode = DefaultMutableTreeNode("root")
    private val allHistoryNode = DefaultMutableTreeNode(ALL_HISTORY_MARKER)
    private val treeModel = DefaultTreeModel(rootNode)
    val tree = Tree(treeModel)

    private val filterField = SearchTextField(false)
    private var workflows: List<Workflow> = emptyList()

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
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val obj = node.userObject) {
                ALL_HISTORY_MARKER -> onWorkflowSelected?.invoke(null)
                is Workflow -> onWorkflowSelected?.invoke(obj)
            }
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

        // 필터가 비어있으면 전체 히스토리 선택
        if (filterField.text.isBlank()) {
            tree.selectionPath = TreePath(arrayOf(rootNode, allHistoryNode))
        }
    }

    /**
     * 현재 선택된 워크플로우 반환 (전체 히스토리면 null)
     */
    fun getSelectedWorkflow(): Workflow? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? Workflow
    }

    /**
     * 트리 셀 렌더러
     */
    private class WorkflowTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            backgroundNonSelectionColor = tree.background
            val node = value as? DefaultMutableTreeNode ?: return this

            when (val obj = node.userObject) {
                ALL_HISTORY_MARKER -> {
                    text = GhaBundle.message("tree.allHistory")
                    icon = AllIcons.Vcs.History
                }
                is Workflow -> {
                    text = obj.name
                    icon = AllIcons.Actions.Execute
                }
            }

            border = JBUI.Borders.empty(2, 0)
            return this
        }
    }
}
