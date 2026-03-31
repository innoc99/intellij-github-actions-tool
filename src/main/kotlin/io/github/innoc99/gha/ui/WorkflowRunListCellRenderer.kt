package io.github.innoc99.gha.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import io.github.innoc99.gha.model.WorkflowRun
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * 워크플로우 실행 목록의 커스텀 셀 렌더러
 * 2줄 레이아웃: [상태아이콘] 워크플로우명 / #번호 by actor . 날짜 / 브랜치라벨
 */
class WorkflowRunListCellRenderer : ListCellRenderer<WorkflowRun> {

    private val formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

    override fun getListCellRendererComponent(
        list: JList<out WorkflowRun>,
        value: WorkflowRun,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            isOpaque = true
            background = if (isSelected) list.selectionBackground else list.background
        }

        // 좌측: 상태 아이콘
        val iconLabel = JLabel(getStatusIcon(value)).apply {
            border = JBUI.Borders.emptyRight(8)
            verticalAlignment = SwingConstants.TOP
        }
        panel.add(iconLabel, BorderLayout.WEST)

        // 중앙: 텍스트 정보 (2줄)
        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // 1줄: 워크플로우 이름
        val nameLabel = JLabel(value.name).apply {
            font = font.deriveFont(font.size2D + 1f)
            foreground = if (isSelected) list.selectionForeground else list.foreground
        }
        textPanel.add(nameLabel)

        // 2줄: #번호 by actor . 날짜
        val actorText = value.actor?.let { " by $it" } ?: ""
        val dateText = value.createdAt.atZone(ZoneId.systemDefault()).format(formatter)
        val subLabel = JLabel("#${value.runNumber}$actorText \u00b7 $dateText").apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = if (isSelected) list.selectionForeground else JBColor.GRAY
        }
        textPanel.add(subLabel)

        panel.add(textPanel, BorderLayout.CENTER)

        // 우측: 브랜치 라벨 + 이벤트
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
        }

        val branchLabel = JLabel(value.headBranch).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = if (isSelected) list.selectionForeground else JBColor(0x0366d6, 0x58a6ff)
            border = JBUI.Borders.empty(2, 6)
        }
        rightPanel.add(branchLabel)

        panel.add(rightPanel, BorderLayout.EAST)

        return panel
    }

    companion object {
        /**
         * 워크플로우 실행 상태에 따른 아이콘 반환
         */
        fun getStatusIcon(run: WorkflowRun): Icon {
            return when {
                run.isInProgress() -> AllIcons.Actions.Execute
                run.isSuccess() -> AllIcons.RunConfigurations.TestPassed
                run.isFailed() -> AllIcons.RunConfigurations.TestFailed
                run.isCancelled() -> AllIcons.RunConfigurations.TestSkipped
                else -> AllIcons.RunConfigurations.TestUnknown
            }
        }
    }
}
