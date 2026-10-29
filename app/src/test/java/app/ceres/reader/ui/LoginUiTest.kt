package app.ceres.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginUiTest {
    @Test
    fun parsesLegadoRows() {
        val rows = parseLoginUi(
            """[{"name":"phone","viewName":"'手机号'","type":"text"},
                {"name":"密码","type":"password"},
                {"name":"线路","type":"select","chars":["主","备"],"default":"备"},
                {"name":"注册","type":"button","action":"https://example.com/reg"},
                {"type":"text"}]"""
        )
        assertEquals(listOf("手机号", "密码", "线路", "注册"), rows.map { it.label })
        assertEquals(listOf("text", "password", "select", "button"), rows.map { it.type })
        assertEquals(listOf("主", "备"), rows[2].chars)
        assertEquals("备", rows[2].default)
        assertEquals("https://example.com/reg", rows[3].action)
    }

    @Test
    fun ignoresNonArrays() {
        assertEquals(emptyList<LoginRow>(), parseLoginUi("登录后可用"))
        assertEquals(emptyList<LoginRow>(), parseLoginUi(null))
    }
}
