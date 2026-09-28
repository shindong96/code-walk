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

class DeleteWalkAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.session()?.current != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val s = WalkSession.get(project)
        val walk = s.current ?: return
        val path = s.entries.firstOrNull { it.walk.id == walk.id }?.path ?: return
        val yes = com.intellij.openapi.ui.MessageDialogBuilder
            .yesNo("Walk 삭제", "'${walk.title.ifEmpty { walk.id }}' 을 삭제할까요?\n\n$path")
            .yesText("삭제").noText("취소")
            .ask(project)
        if (yes && !s.delete(walk)) {
            com.intellij.openapi.ui.Messages.showErrorDialog(project, "파일을 삭제하지 못했어요:\n$path", "Walk 삭제")
        }
    }
}

class ReloadAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) { e.session()?.refresh() }
}
