package dev.shin.codewalk.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.shin.codewalk.WalkSession
import dev.shin.codewalk.model.Step
import dev.shin.codewalk.model.Walk
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.HyperlinkEvent

/**
 * Bottom tool window: walk picker + prev/next on top, step list on the left, explanation on the right.
 * All state lives in [WalkSession]; this panel only renders it and forwards clicks.
 */
class CodeWalkPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val session = WalkSession.get(project)

    /** Combo rows: a non-selectable date header, or a walk. */
    private sealed interface Row
    private data class DateHeader(val label: String) : Row
    private data class WalkRow(val entry: WalkSession.Entry) : Row

    private val walkBox = JComboBox<Row>()
    private val counter = JBLabel()
    private val stepsModel = DefaultListModel<Step>()
    private val stepsList = JBList(stepsModel)
    private val body = JEditorPane("text/html", "")
    private var syncing = false
    private var lastWalkRow: WalkRow? = null

    init {
        border = JBUI.Borders.empty()

        // --- header: walk picker + navigation actions ---
        walkBox.renderer = object : ColoredListCellRenderer<Row>() {
            override fun customizeCellRenderer(list: JList<out Row>, value: Row?, index: Int, selected: Boolean, focus: Boolean) {
                when (value) {
                    null -> append("(walk 없음 — Claude Code 에서 /walk 를 실행하세요)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    is DateHeader -> {
                        // index == -1 is the closed combo's own face; headers never show there.
                        append(value.label, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                        background = UIUtil.getPanelBackground()
                    }
                    is WalkRow -> {
                        val w = value.entry.walk
                        if (index >= 0) ipad = JBUI.insets(2, 14, 2, 6)   // indent under the date header in the popup
                        append(w.title.ifEmpty { w.id })
                        append("  ${w.steps.size} steps · ${timeFmt.format(java.time.Instant.ofEpochMilli(value.entry.createdAt))}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
        walkBox.addActionListener {
            if (syncing) return@addActionListener
            when (val sel = walkBox.selectedItem) {
                is DateHeader -> { syncing = true; try { walkBox.selectedItem = lastWalkRow } finally { syncing = false } }
                is WalkRow -> { lastWalkRow = sel; if (sel.entry.walk.id != session.current?.id) session.open(sel.entry.walk) }
            }
        }
        val actions = DefaultActionGroup().apply {
            add(ActionManager.getInstance().getAction("CodeWalk.Prev"))
            add(ActionManager.getInstance().getAction("CodeWalk.Next"))
            add(ActionManager.getInstance().getAction("CodeWalk.Reload"))
            add(ActionManager.getInstance().getAction("CodeWalk.Delete"))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 6)
            add(walkBox, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(counter); add(toolbar.component) }, BorderLayout.EAST)
        }

        // --- step list ---
        stepsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        stepsList.cellRenderer = object : ColoredListCellRenderer<Step>() {
            override fun customizeCellRenderer(list: JList<out Step>, value: Step, index: Int, selected: Boolean, focus: Boolean) {
                ipad = JBUI.insets(2, 6 + value.depth * 14, 2, 6)
                append("${index + 1}. ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.title, if (index == session.stepIndex) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                value.file?.let { f ->
                    val name = f.substringAfterLast('/')
                    append("  $name:${value.line}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
        stepsList.addListSelectionListener {
            if (syncing || it.valueIsAdjusting) return@addListSelectionListener
            val idx = stepsList.selectedIndex
            if (idx >= 0 && idx != session.stepIndex) session.goTo(idx)
        }

        // --- body ---
        body.isEditable = false
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        body.border = JBUI.Borders.empty(8, 12)
        body.background = UIUtil.getPanelBackground()
        body.addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val href = e.description ?: ""
            val ref = Markdown.parseLink(href)
            when {
                ref != null -> session.jumpTo(ref.first, ref.second, ref.third)
                e.url != null -> com.intellij.ide.BrowserUtil.browse(e.url)
            }
        }

        val splitter = OnePixelSplitter(false, 0.3f).apply {
            firstComponent = JBScrollPane(stepsList)
            secondComponent = JBScrollPane(body)
        }
        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        session.addListener({ render() }, parent)
        render()
    }

    private fun render() {
        syncing = true
        try {
            val entries = session.entries
            val walks = session.walks
            val cur = session.current

            // Newest first, grouped under date headers: 오늘 / 어제 / 9월 26일 / 2025. 12. 3.
            val rows = ArrayList<Row>()
            var lastDay: java.time.LocalDate? = null
            for (e in entries) {
                val day = java.time.Instant.ofEpochMilli(e.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                if (day != lastDay) { rows.add(DateHeader(dayLabel(day))); lastDay = day }
                rows.add(WalkRow(e))
            }
            walkBox.model = DefaultComboBoxModel(rows.toTypedArray())
            val selRow = rows.filterIsInstance<WalkRow>().let { wr -> wr.firstOrNull { it.entry.walk.id == cur?.id } ?: wr.firstOrNull() }
            walkBox.selectedItem = selRow
            lastWalkRow = selRow
            walkBox.isEnabled = walks.isNotEmpty()

            val steps = cur?.steps ?: emptyList()
            if (stepsModel.size != steps.size || (0 until stepsModel.size).any { stepsModel[it] !== steps[it] }) {
                stepsModel.clear(); steps.forEach(stepsModel::addElement)
            }
            val idx = session.stepIndex
            if (idx in steps.indices) { stepsList.selectedIndex = idx; stepsList.ensureIndexIsVisible(idx) } else stepsList.clearSelection()
            counter.text = if (idx >= 0) "${idx + 1} / ${steps.size}" else ""

            body.text = when {
                cur == null && walks.isEmpty() -> css() + "<p style='color:gray'>아직 walk 가 없어요.<br>Claude Code 에서 <code>/walk &lt;진입점&gt;</code> 을 실행하면 여기에 나타나요.</p>"
                cur == null -> css() + "<p style='color:gray'>위에서 walk 를 고르세요.</p>"
                idx !in steps.indices -> css() + "<p style='color:gray'>단계를 고르세요.</p>"
                else -> css() + "<h3>${Markdown.toHtml(steps[idx].title).removeSurrounding("<p>", "</p>")}</h3>" + Markdown.toHtml(steps[idx].body)
            }
            body.caretPosition = 0
            stepsList.repaint()
        } finally {
            syncing = false
        }
    }

    private val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())

    private fun dayLabel(day: java.time.LocalDate): String {
        val today = java.time.LocalDate.now()
        return when {
            day == today -> "오늘"
            day == today.minusDays(1) -> "어제"
            day.year == today.year -> "${day.monthValue}월 ${day.dayOfMonth}일"
            else -> "${day.year}. ${day.monthValue}. ${day.dayOfMonth}."
        }
    }

    private fun css(): String {
        val font = UIUtil.getLabelFont()
        val fg = UIUtil.getLabelForeground()
        val code = JBColor(0xF0F0F0, 0x2B2D30)
        fun hex(c: java.awt.Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
        val link = com.intellij.ui.JBColor.namedColor("Link.activeForeground", JBColor(0x2470B3, 0x589DF6))
        return "<style>" +
            "body{font-family:'${font.family}';font-size:${font.size}pt;color:${hex(fg)};}" +
            "a{color:${hex(link)};text-decoration:none;}" +
            "h3{margin:0 0 8px 0;} p{margin:0 0 8px 0;} ul{margin:0 0 8px 16px;} li{margin:2px 0;}" +
            "code{font-family:'${UIUtil.getFontWithFallback("JetBrains Mono", 0, font.size).family}';background:${hex(code)};}" +
            "pre{background:${hex(code)};padding:6px;margin:0 0 8px 0;}" +
            "</style>"
    }
}
