package dev.shin.codewalk.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.shin.codewalk.WalkSession
import dev.shin.codewalk.model.Walk
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * The walk picker at the top of the tool window: a dropdown-looking button that opens a popup
 * list of walks, newest first, grouped under date headers. Each row carries a trash icon;
 * clicking it (or pressing Delete/Backspace on a selected row) deletes that walk after a
 * confirmation, and the list refreshes in place.
 */
class WalkPicker(private val project: Project, private val session: WalkSession) : JButton() {

    sealed interface Row
    data class DateHeader(val label: String) : Row
    data class WalkRow(val entry: WalkSession.Entry) : Row

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private var popup: JBPopup? = null

    init {
        horizontalAlignment = SwingConstants.LEFT
        icon = AllIcons.General.ArrowDown
        horizontalTextPosition = SwingConstants.LEFT
        iconTextGap = JBUI.scale(8)
        addActionListener { if (popup?.isVisible == true) popup?.cancel() else showPopup() }
        refreshFace()
    }

    /** Button face: the current walk, or a hint. Called by the panel on every session change. */
    fun refreshFace() {
        val cur = session.current
        text = when {
            cur != null -> "${cur.title.ifEmpty { cur.id }}  ·  ${cur.steps.size} steps"
            session.entries.isEmpty() -> "walk 없음 — Claude Code 에서 /walk 를 실행하세요"
            else -> "walk 를 고르세요"
        }
        isEnabled = session.entries.isNotEmpty()
    }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>()
        var lastDay: LocalDate? = null
        for (e in session.entries) {
            val day = Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
            if (day != lastDay) { out.add(DateHeader(dayLabel(day))); lastDay = day }
            out.add(WalkRow(e))
        }
        return out
    }

    private fun showPopup() {
        val model = DefaultListModel<Row>()
        rows().forEach(model::addElement)
        val list = JBList(model)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = RowRenderer()
        list.setEmptyText("walk 없음")
        // Preselect the open walk.
        (0 until model.size).firstOrNull { (model[it] as? WalkRow)?.entry?.walk?.id == session.current?.id }?.let { list.selectedIndex = it }

        // Headers are not selectable: bounce to the nearest walk row.
        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val i = list.selectedIndex
            if (i >= 0 && model[i] is DateHeader) {
                val next = (i + 1 until model.size).firstOrNull { model[it] is WalkRow } ?: (i - 1 downTo 0).firstOrNull { model[it] is WalkRow }
                if (next != null) list.selectedIndex = next else list.clearSelection()
            }
        }

        val scroll = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, list)
            .setTitle("Walks")
            .setMovable(false)
            .setResizable(true)
            .setRequestFocus(true)
            .setMinSize(java.awt.Dimension(width.coerceAtLeast(JBUI.scale(360)), JBUI.scale(120)))
            .createPopup()
        this.popup = popup

        fun choose(row: WalkRow) {
            if (row.entry.walk.id != session.current?.id) session.open(row.entry.walk)
            popup.cancel()
        }

        // Click: trash zone (rightmost TRASH_ZONE px) deletes, anywhere else on a walk row chooses it.
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val i = list.locationToIndex(e.point)
                if (i < 0) return
                val row = model[i] as? WalkRow ?: return
                val b = list.getCellBounds(i, i) ?: return
                if (!b.contains(e.point)) return
                e.consume()
                list.selectedIndex = i
                if (e.x >= b.x + b.width - JBUI.scale(TRASH_ZONE)) deleteRow(row, model) else choose(row)
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val row = list.selectedValue as? WalkRow ?: return
                when (e.keyCode) {
                    KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> { e.consume(); deleteRow(row, model) }
                    KeyEvent.VK_ENTER -> { e.consume(); choose(row) }
                }
            }
        })

        // Walk files can appear/disappear while the popup is open (Claude writing a new one): rebuild.
        val disposable = Disposer.newDisposable("walk-picker-popup")
        Disposer.register(popup, disposable)
        session.addListener({
            if (!popup.isVisible) return@addListener
            val selectedId = (list.selectedValue as? WalkRow)?.entry?.walk?.id
            model.clear(); rows().forEach(model::addElement)
            (0 until model.size).firstOrNull { (model[it] as? WalkRow)?.entry?.walk?.id == selectedId }?.let { list.selectedIndex = it }
        }, disposable)

        popup.showUnderneathOf(this)
    }

    private fun deleteRow(row: WalkRow, model: DefaultListModel<Row>) {
        val walk = row.entry.walk
        if (!confirmDelete(project, walk, row.entry.path.toString())) return
        if (!session.delete(walk)) {
            Messages.showErrorDialog(project, "파일을 삭제하지 못했어요:\n${row.entry.path}", "Walk 삭제")
            return
        }
        // Remove right away (the session listener will rebuild anyway once the poll catches up).
        val i = model.indexOf(row)
        if (i >= 0) {
            model.remove(i)
            // Drop a date header left with no walks under it.
            val headerIdx = i - 1
            if (headerIdx >= 0 && model[headerIdx] is DateHeader && (headerIdx + 1 >= model.size || model[headerIdx + 1] is DateHeader)) model.remove(headerIdx)
        }
    }

    private inner class RowRenderer : ListCellRenderer<Row> {
        private val panel = JPanel(BorderLayout())
        private val text = SimpleColoredComponent()
        private val trash = JLabel(AllIcons.Actions.GC).apply { border = JBUI.Borders.empty(0, 6, 0, 8); toolTipText = "이 walk 삭제 (Delete)" }

        init { panel.add(text, BorderLayout.CENTER); panel.add(trash, BorderLayout.EAST) }

        override fun getListCellRendererComponent(list: JList<out Row>, value: Row, index: Int, selected: Boolean, focus: Boolean): Component {
            text.clear()
            text.isOpaque = false
            when (value) {
                is DateHeader -> {
                    text.ipad = JBUI.insets(4, 8, 2, 8)
                    text.append(value.label, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    trash.isVisible = false
                    panel.background = UIUtil.getListBackground()
                }
                is WalkRow -> {
                    val w = value.entry.walk
                    text.ipad = JBUI.insets(3, 20, 3, 8)
                    text.append(w.title.ifEmpty { w.id }, if (w.id == session.current?.id) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    text.append("  ${w.steps.size} steps · ${timeFmt.format(Instant.ofEpochMilli(value.entry.createdAt))}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    trash.isVisible = true
                    panel.background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
                    text.foreground = if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
                }
            }
            return panel
        }
    }

    private fun dayLabel(day: LocalDate): String {
        val today = LocalDate.now()
        return when {
            day == today -> "오늘"
            day == today.minusDays(1) -> "어제"
            day.year == today.year -> "${day.monthValue}월 ${day.dayOfMonth}일"
            else -> "${day.year}. ${day.monthValue}. ${day.dayOfMonth}."
        }
    }

    companion object {
        private const val TRASH_ZONE = 32

        /** Shared with the toolbar's delete action. */
        fun confirmDelete(project: Project, walk: Walk, path: String): Boolean =
            MessageDialogBuilder.yesNo("Walk 삭제", "'${walk.title.ifEmpty { walk.id }}' 을 삭제할까요?\n\n$path")
                .yesText("삭제").noText("취소")
                .ask(project)
    }
}
