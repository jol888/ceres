package app.ceres.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalImporterTest {
    @Test
    fun splitsChaptersAndPreface() {
        val txt = "\uFEFF书名简介\r\n\r\n　　第一章 开始\r\n　　段一\r\n段二  \r\n第二章 继续\r\n\u3000\u3000段三\r\n第三卷\r\n第3章 很长很长的一行正文不算标题因为它太长了太长了太长了太长了太长了太长了太长了太长了太长了\r\n"
        val p = LocalImporter.parseTxt(txt.toByteArray(), "《测试》作者：某人.txt")
        assertEquals("测试", p.title)
        assertEquals(listOf("前言", "第一章 开始", "第二章 继续", "第三卷"), p.chapters.map { it.first })
        assertEquals(listOf("书名简介"), p.chapters[0].second)
        assertEquals(listOf("段一", "段二"), p.chapters[1].second)
        assertEquals(listOf("段三"), p.chapters[2].second)
        assertEquals(1, p.chapters[3].second.size)
    }

    @Test
    fun chunksWhenNoHeadings() {
        val txt = (1..400).joinToString("\n") { "第${it}段" + "字".repeat(40) }
        val p = LocalImporter.parseTxt(txt.toByteArray(Charsets.UTF_8), "a.txt")
        assertEquals(400, p.chapters.sumOf { it.second.size })
        assertEquals("第 1 部分", p.chapters.first().first)
    }

    @Test
    fun gbkFallback() {
        val txt = "第一章 甲\n内容甲\n第二章 乙\n内容乙"
        val p = LocalImporter.parseTxt(txt.toByteArray(charset("GB18030")), "b.txt")
        assertEquals(listOf("第一章 甲", "第二章 乙"), p.chapters.map { it.first })
    }
}
