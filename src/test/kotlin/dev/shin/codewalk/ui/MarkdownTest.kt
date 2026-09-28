package dev.shin.codewalk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownTest {
    @Test
    fun `line reference at sentence end becomes a walk link`() {
        val html = Markdown.toHtml("잠금 중이면 바로 거절해요 (line 89).")
        assertEquals("<p>잠금 중이면 바로 거절해요 (<a href=\"walk:line:89:\">line 89</a>).</p>", html)
    }

    @Test
    fun `line range and file reference`() {
        assertEquals(
            "<p>a (<a href=\"walk:line:1441:1460\">lines 1441–1460</a>) b (<a href=\"walk:file:Services/Foo.cs:120:\">Services/Foo.cs:120</a>)</p>",
            Markdown.toHtml("a (lines 1441-1460) b (Services/Foo.cs:120)"),
        )
    }

    @Test
    fun `parseLink round-trips`() {
        assertEquals(Triple(null, 89, null), Markdown.parseLink("walk:line:89:"))
        assertEquals(Triple(null, 1441, 1460), Markdown.parseLink("walk:line:1441:1460"))
        assertEquals(Triple("Services/Foo.cs", 120, null), Markdown.parseLink("walk:file:Services/Foo.cs:120:"))
        assertNull(Markdown.parseLink("https://example.com"))
    }

    @Test
    fun `inline code and bullets still render`() {
        assertEquals(
            "<p><code>RegionHint</code> 를 붙여요</p><ul><li>잠겨 있으면: 거절</li></ul>",
            Markdown.toHtml("`RegionHint` 를 붙여요\n\n- 잠겨 있으면: 거절"),
        )
    }
}
