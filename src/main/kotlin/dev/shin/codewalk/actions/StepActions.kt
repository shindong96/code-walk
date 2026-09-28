package dev.shin.codewalk.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.shin.codewalk.WalkSession

private fun AnActionEvent.session(): WalkSession? = project?.let { WalkSession.get(it) }

class NextStepAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val s = e.session()
        e.presentation.isEnabled = s?.current != null && s.stepIndex < (s.current?.steps?.size ?: 0) - 1
    }
    override fun actionPerformed(e: AnActionEvent) { e.session()?.next() }
}

class PrevStepAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val s = e.session()
        e.presentation.isEnabled = s?.current != null && s.stepIndex > 0
    }
    override fun actionPerformed(e: AnActionEvent) { e.session()?.prev() }
}

class ReloadAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) { e.session()?.refresh() }
}
