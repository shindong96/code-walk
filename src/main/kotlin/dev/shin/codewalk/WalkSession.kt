package dev.shin.codewalk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import dev.shin.codewalk.model.Walk
import dev.shin.codewalk.model.WalkState
import dev.shin.codewalk.model.WalkStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Per-project state: which walks are available for this project, which one is open, and where
 * the user is in it. Polls `~/.code-walk/walks` once a second so a walk Claude just wrote shows up
 * without any server, port or IDE-side listener.
 */
@Service(Service.Level.PROJECT)
class WalkSession(private val project: Project) : Disposable {

    fun interface Listener {
        fun changed()
    }

    private data class Loaded(val path: Path, val mtime: Long, val walk: Walk)

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val loaded = LinkedHashMap<Path, Loaded>()
    private var highlighter: RangeHighlighter? = null
    private var highlightedEditor: Editor? = null
    private val poll: ScheduledFuture<*>

    @Volatile var walks: List<Walk> = emptyList(); private set
    @Volatile var current: Walk? = null; private set
    @Volatile var stepIndex: Int = -1; private set

    init {
        poll = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::refresh, 0, 1, TimeUnit.SECONDS)
    }

    fun addListener(l: Listener, parent: Disposable) {
        listeners.add(l)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(l) }
    }

    private fun fire() = ApplicationManager.getApplication().invokeLater({ listeners.forEach { it.changed() } }, project.disposed)

    /** Re-read walk files whose mtime changed. Runs on a pooled thread. */
    fun refresh() {
        val files = runCatching { WalkStore.listWalkFiles() }.getOrDefault(emptyList())
        var changed = false
        synchronized(loaded) {
            val gone = loaded.keys.filter { it !in files }
            gone.forEach { loaded.remove(it); changed = true }
            for (f in files) {
                val mtime = runCatching { Files.getLastModifiedTime(f).toMillis() }.getOrDefault(0L)
                val prev = loaded[f]
                if (prev != null && prev.mtime == mtime) continue
                val walk = WalkStore.read(f) ?: continue
                if (!belongsHere(walk)) { loaded.remove(f); continue }
                loaded[f] = Loaded(f, mtime, walk)
                changed = true
            }
            if (!changed) return
            walks = loaded.values.map { it.walk }
            // Keep the open walk on its latest content; drop it if its file disappeared.
            val cur = current
            if (cur != null) {
                val fresh = walks.firstOrNull { it.id == cur.id }
                if (fresh == null) { current = null; stepIndex = -1 }
                else if (fresh !== cur) { current = fresh; stepIndex = stepIndex.coerceIn(0, fresh.steps.size - 1) }
            }
        }
        fire()
    }

    /** A walk belongs to this IDE window when its project root and ours contain each other. */
    private fun belongsHere(walk: Walk): Boolean {
        val base = project.basePath?.trimEnd('/') ?: return false
        val wp = walk.project.trimEnd('/')
        if (wp.isEmpty()) return true
        return base == wp || base.startsWith("$wp/") || wp.startsWith("$base/")
    }

    fun open(walk: Walk, index: Int = 0) {
        current = walk
        goTo(index)
    }

    fun close() {
        current = null
        stepIndex = -1
        clearHighlight()
        runCatching { WalkStore.clearState() }
        fire()
    }

    fun next() = goTo(stepIndex + 1)
    fun prev() = goTo(stepIndex - 1)

    fun goTo(index: Int) {
        val walk = current ?: return
        if (index !in walk.steps.indices) return
        stepIndex = index
        val step = walk.steps[index]
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            reveal(walk, step.file, step.line, step.endLine)
        }, project.disposed)
        writeState(walk, index)
        fire()
    }

    private fun resolve(walk: Walk, file: String): Path {
        val p = Path.of(file)
        if (p.isAbsolute) return p
        val root = walk.project.ifEmpty { project.basePath ?: "" }
        return Path.of(root, file)
    }

    private fun reveal(walk: Walk, file: String?, line: Int, endLine: Int?) {
        clearHighlight()
        if (file == null) return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolve(walk, file)) ?: return
        val descriptor = OpenFileDescriptor(project, vf, line - 1, 0)
        val fileEditor = FileEditorManager.getInstance(project).openEditor(descriptor, true).firstOrNull() as? TextEditor ?: return
        val editor = fileEditor.editor
        val doc = editor.document
        val startLine = (line - 1).coerceIn(0, doc.lineCount - 1)
        val lastLine = ((endLine ?: line) - 1).coerceIn(startLine, doc.lineCount - 1)
        val attrs = editor.colorsScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
        highlighter = editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(startLine), doc.getLineEndOffset(lastLine),
            HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.LINES_IN_RANGE,
        )
        highlightedEditor = editor
    }

    private fun clearHighlight() {
        val h = highlighter ?: return
        val e = highlightedEditor
        if (e != null && !e.isDisposed) e.markupModel.removeHighlighter(h)
        highlighter = null
        highlightedEditor = null
    }

    private fun writeState(walk: Walk, index: Int) {
        val step = walk.steps[index]
        val state = WalkState(
            walk = walk.id, project = walk.project.ifEmpty { project.basePath ?: "" },
            step = index + 1, stepCount = walk.steps.size, stepId = step.id, title = step.title,
            file = step.file, line = step.line, endLine = step.endLine,
            updatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
        AppExecutorUtil.getAppExecutorService().execute { runCatching { WalkStore.writeState(state) } }
    }

    override fun dispose() {
        poll.cancel(false)
        clearHighlight()
    }

    companion object {
        fun get(project: Project): WalkSession = project.getService(WalkSession::class.java)
    }
}
