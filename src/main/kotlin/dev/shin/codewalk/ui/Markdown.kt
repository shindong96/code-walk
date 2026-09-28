package dev.shin.codewalk.ui

/**
 * Tiny Markdown → HTML for the step body. Covers what walk bodies use (headings, bold, inline
 * code, fenced code, bullet lists, paragraphs) without pulling in the Markdown plugin.
 */
object Markdown {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(s: String): String {
        var t = esc(s)
        t = t.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        t = t.replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")) { "<i>${it.groupValues[1]}</i>" }
        return t
    }

    fun toHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.lines()
        var i = 0
        var inList = false
        var para = StringBuilder()

        fun flushPara() {
            if (para.isNotEmpty()) { out.append("<p>").append(para).append("</p>"); para = StringBuilder() }
        }
        fun closeList() { if (inList) { out.append("</ul>"); inList = false } }

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    flushPara(); closeList()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) { code.append(esc(lines[i])).append('\n'); i++ }
                    out.append("<pre>").append(code).append("</pre>")
                }
                line.startsWith("#") -> {
                    flushPara(); closeList()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 4)
                    out.append("<h$level>").append(inline(line.drop(level).trim())).append("</h$level>")
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    flushPara()
                    if (!inList) { out.append("<ul>"); inList = true }
                    out.append("<li>").append(inline(line.trimStart().drop(2))).append("</li>")
                }
                line.isBlank() -> { flushPara(); closeList() }
                else -> {
                    closeList()
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(inline(line))
                }
            }
            i++
        }
        flushPara(); closeList()
        return out.toString()
    }
}
