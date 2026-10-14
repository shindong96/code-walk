package dev.shin.codewalk.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.shin.codewalk.WalkSession
import dev.shin.codewalk.model.Step
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Bottom tool window: walk picker + prev/next on top, step list on the left, explanation on the right.
 * All state lives in [WalkSession]; this panel only renders it and forwards clicks.
 */
class CodeWalkPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val session = WalkSession.get(project)

    private val picker = WalkPicker(project, session)
    private val counter = JBLabel()
    private val stepsModel = DefaultListModel<Step>()
    private val stepsList = JBList(stepsModel)
    private val body: BodyView = BodyView.create(parent, ::onLink)
    private var syncing = false

    init {
        border = JBUI.Borders.empty()

        // --- header: walk picker + navigation actions ---
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
            add(picker, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(counter); add(toolbar.component) }, BorderLayout.EAST)
        }

        // --- step list ---
        stepsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        stepsList.cellRenderer = object : ColoredListCellRenderer<Step>() {
            override fun customizeCellRenderer(list: JList<out Step>, value: Step, index: Int, selected: Boolean, focus: Boolean) {
                ipad = JBUI.insets(2, 6 + value.depth * 14, 2, 6)
                append("${displayNumber(index)}. ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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

        val splitter = OnePixelSplitter(false, 0.3f).apply {
            firstComponent = JBScrollPane(stepsList)
            secondComponent = body.component
        }
        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        session.addListener({ render() }, parent)
        render()
    }

    /**
     * A walk that opens with a text-only overview (no file) numbers it 0, so the code steps stay
     * 1…N: "0. 한눈에 보기, 1. 요청 진입, …". Without an overview, numbering starts at 1.
     */
    private fun hasOverview(): Boolean = session.current?.steps?.firstOrNull()?.file == null && (session.current?.steps?.size ?: 0) > 0
    private fun displayNumber(index: Int): Int = if (hasOverview()) index else index + 1
    private fun lastDisplayNumber(): Int = (session.current?.steps?.size ?: 0).let { if (hasOverview()) it - 1 else it }

    /** A link in the body was clicked: `walk:…` moves the editor, anything else opens the browser. */
    private fun onLink(href: String) {
        val ref = Markdown.parseLink(href)
        when {
            ref != null -> session.jumpTo(ref.first, ref.second, ref.third)
            href.startsWith("http://") || href.startsWith("https://") -> com.intellij.ide.BrowserUtil.browse(href)
        }
    }

    private fun render() {
        syncing = true
        try {
            val cur = session.current
            picker.refreshFace()

            val steps = cur?.steps ?: emptyList()
            if (stepsModel.size != steps.size || (0 until stepsModel.size).any { stepsModel[it] !== steps[it] }) {
                stepsModel.clear(); steps.forEach(stepsModel::addElement)
            }
            val idx = session.stepIndex
            if (idx in steps.indices) { stepsList.selectedIndex = idx; stepsList.ensureIndexIsVisible(idx) } else stepsList.clearSelection()
            counter.text = if (idx >= 0) "${displayNumber(idx)} / ${lastDisplayNumber()}" else ""

            body.setHtml(
                when {
                    cur == null && session.entries.isEmpty() -> "<p style='opacity:.6'>아직 walk 가 없어요.<br>Claude Code 에서 <code>/walk &lt;진입점&gt;</code> 을 실행하면 여기에 나타나요.</p>"
                    cur == null -> "<p style='opacity:.6'>위에서 walk 를 고르세요.</p>"
                    idx !in steps.indices -> "<p style='opacity:.6'>단계를 고르세요.</p>"
                    else -> "<h3>${Markdown.toHtml(steps[idx].title).removeSurrounding("<p>", "</p>")}</h3>" + Markdown.toHtml(steps[idx].body)
                },
            )
            stepsList.repaint()
        } finally {
            syncing = false
        }
    }
}
