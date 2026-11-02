package app.ceres.reader.data

import app.ceres.reader.source.WebBook
import app.ceres.reader.util.parseBookSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuiltinSourceTest {
    private val asset = listOf(
        File("src/main/assets/${SourceRepository.FANQIE_ASSET}"),
        File("app/src/main/assets/${SourceRepository.FANQIE_ASSET}"),
    ).first { it.isFile }

    @Test
    fun bundledFanqieSourceParses() {
        val sources = parseBookSources(asset.readText())
        assertEquals(1, sources.size)
        val src = sources.first()
        assertTrue(src.enabled)
        assertTrue("发现未开启", src.enabledExplore)
        assertTrue("段评规则缺失", src.hasReview())
        assertEquals("$.score", src.ruleBookInfo?.score)
        val kinds = WebBook.exploreKinds(src)
        assertEquals(
            listOf("热门榜", "飙升榜", "热搜榜", "潜力榜", "听书榜"),
            kinds.dropWhile { it.title != "榜单" }.drop(1).takeWhile { it.url != null }.map { it.title },
        )
    }

    @Test
    fun fanqieJsonShorthand() {
        assertTrue(SourceRepository.isBuiltinFanqie("fanqie://番茄.json"))
        assertTrue(SourceRepository.isBuiltinFanqie("  FANQIE://fanqie_novel_source.JSON "))
        assertFalse(SourceRepository.isBuiltinFanqie("fanqie://book/123"))
        assertFalse(SourceRepository.isBuiltinFanqie("fanqie://a/b.json"))
        assertFalse(SourceRepository.isBuiltinFanqie("https://x.com/fanqie.json"))
    }
}
