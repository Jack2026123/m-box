package com.github.tvbox.osc.miniapp.engine

import androidx.test.core.app.ApplicationProvider
import com.github.tvbox.osc.reading.NoBootApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = NoBootApplication::class)
class MiniAppPreludeTest {

    @Test
    fun prelude_hasNoStrayBackslashN() {
        val host = MiniAppHost(ApplicationProvider.getApplicationContext())
        val prelude = host.prelude()
        assertTrue(prelude.contains("var com = "))
        assertTrue(prelude.contains("\n"))
        assertFalse(prelude.contains("\\n"))
    }

    // logtest50: 洪荒影院 home JS calls registerTask (海阔页面绑定延时任务);
    // the prelude must define it (and its unRegisterTask pair) or the
    // run dies with ReferenceError before setHomeResult.
    @Test
    fun prelude_definesRegisterTaskPair() {
        val host = MiniAppHost(ApplicationProvider.getApplicationContext())
        val prelude = host.prelude()
        assertTrue(prelude.contains("function registerTask("))
        assertTrue(prelude.contains("__host_registerTask"))
        assertTrue(prelude.contains("function unRegisterTask("))
        assertTrue(prelude.contains("__host_unRegisterTask"))
    }

    // logtest终极版第二批: 海阔 JS API 缺口补齐; 规则一调就
    // ReferenceError 的那批必须在 prelude 里有定义.
    @Test
    fun prelude_definesUltimateBatch2() {
        val host = MiniAppHost(ApplicationProvider.getApplicationContext())
        val prelude = host.prelude()
        for (name in listOf(
            "getMyUrl", "getPageTitle", "back", "refreshPage", "toast",
            "getCookie", "evalLazyRule", "setLastChapterRule", "setPageParams",
            "setPagePicUrl", "setPageReverse", "xpath", "xpathArray",
        )) {
            assertTrue("missing prelude fn: $name", prelude.contains("function $name("))
        }
    }
}
