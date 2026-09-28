package dev.shin.codewalk.model

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A walk written by Claude Code. Lives in `~/.code-walk/walks/<id>.walk.json`.
 *
 * The file format is the contract between the Claude skill and this plugin — keep it flat and
 * forgiving: unknown fields are ignored by Gson, and every field except `steps` has a default.
 */
data class Walk(
    val version: Int = 1,
    val id: String = "",
    val title: String = "",
    /** Absolute path of the project root the step paths are relative to. */
    val project: String = "",
    val createdAt: String = "",
    val steps: List<Step> = emptyList(),
)

data class Step(
    val id: String = "",
    val title: String = "",
    /** Path relative to [Walk.project]; may also be absolute. Null for a text-only step. */
    val file: String? = null,
    /** 1-based, inclusive. */
    val line: Int = 1,
    @SerializedName("endLine") val endLine: Int? = null,
    /** Nesting level for display (0 = top). Call depth in the walked flow. */
    val depth: Int = 0,
    /** Markdown body shown in the tool window. */
    val body: String = "",
)

/**
 * `~/.code-walk/state.json` — where the user is right now. Written by the plugin on every step
 * change; read by the Claude Code hook so questions carry the current location.
 */
data class WalkState(
    val walk: String,
    val project: String,
    val step: Int,
    val stepCount: Int,
    val stepId: String,
    val title: String,
    val file: String?,
    val line: Int,
    val endLine: Int?,
    val updatedAt: String,
)

object WalkStore {
    val root: Path = Path.of(System.getProperty("user.home"), ".code-walk")
    val walksDir: Path = root.resolve("walks")
    val stateFile: Path = root.resolve("state.json")

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun listWalkFiles(): List<Path> =
        if (Files.isDirectory(walksDir)) Files.list(walksDir).use { s ->
            s.filter { it.fileName.toString().endsWith(".walk.json") }.sorted().toList()
        } else emptyList()

    fun read(path: Path): Walk? = runCatching {
        Files.newBufferedReader(path).use { gson.fromJson(it, Walk::class.java) }
    }.getOrNull()?.takeIf { it.steps.isNotEmpty() }

    /**
     * `state.line` — the same position as one preformatted line, `<project>\t<message>`, so the
     * Claude Code hook can inject it with plain bash (no jq/node/python at prompt time).
     */
    val stateLine: Path = root.resolve("state.line")

    /** Atomic writes so the hook never reads a half-written file. */
    fun writeState(state: WalkState) {
        Files.createDirectories(root)
        atomicWrite(stateFile, gson.toJson(state))
        atomicWrite(stateLine, state.project + "\t" + state.toContextLine() + "\n")
    }

    fun clearState() {
        Files.deleteIfExists(stateFile)
        Files.deleteIfExists(stateLine)
    }

    private fun atomicWrite(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // AIDEV-NOTE: this line is the ONLY thing guaranteed to reach Claude on every prompt (the skill may not be
    // loaded), so it must be self-sufficient: absolute path + explicit "read it first" instruction.
    private fun WalkState.toContextLine(): String {
        if (file == null) {
            return "[code-walk] The user is viewing walk '$walk' step $step/$stepCount \"$title\" (text-only step) in the IDE."
        }
        val abs = if (Path.of(file).isAbsolute) file else Path.of(project, file).toString()
        val range = if (endLine != null && endLine != line) "$line-$endLine" else "$line"
        return "[code-walk] The user is viewing walk '$walk' step $step/$stepCount \"$title\" in the IDE: $abs lines $range. " +
            "Their question is about THIS code unless they clearly say otherwise — read those lines (Read $abs offset=$line) before answering, " +
            "and do not assume the question continues an earlier topic."
    }
}
