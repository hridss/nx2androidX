package com.nx.android.feature.faq

import com.nx.android.data.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 常见问题的内容：两份 JSON（中文与英文）。
 *
 * 两份文件的结构必须一样（组、组里的顺序、条数），问答都不能是空的——
 * 内容文件是数据，错了不会在编译期被发现，只能靠这里盯着。
 */
class FaqContentTest {

    @Test
    fun theGroupsAreInTheSameOrderInBothLanguages() {
        val zh = FaqContent.parse(zhJson())
        val en = FaqContent.parse(enJson())
        assertTrue(zh.isNotEmpty())
        // the keys are stable, so the same key must not appear twice
        assertEquals(zh.size, zh.map { it.key }.distinct().size)
        // the groups come and go with the content: what must hold is that both files agree on them
        assertEquals(zh.map { it.key }, en.map { it.key })
    }

    @Test
    fun everyGroupHasQuestions() {
        // the count comes and goes with the content: what must not happen is a group without a question
        for (text in listOf(zhJson(), enJson())) {
            for (group in FaqContent.parse(text)) {
                assertTrue("group ${group.key} has no question", group.entries.isNotEmpty())
            }
        }
    }

    @Test
    fun bothLanguagesHaveTheSameShape() {
        val zh = FaqContent.parse(zhJson())
        val en = FaqContent.parse(enJson())
        assertEquals(zh.map { it.key }, en.map { it.key })
        assertEquals(zh.map { it.entries.size }, en.map { it.entries.size })
    }

    @Test
    fun everyQuestionAndAnswerIsThere() {
        for (text in listOf(zhJson(), enJson())) {
            for (group in FaqContent.parse(text)) {
                assertTrue("group ${group.key} has no title", group.title.isNotBlank())
                assertTrue("group ${group.key} has no question", group.entries.isNotEmpty())
                for (entry in group.entries) {
                    assertTrue("a question is empty", entry.question.isNotBlank())
                    assertTrue("an answer is empty", entry.answer.isNotBlank())
                }
            }
        }
    }

    @Test
    fun aBrokenFileGivesNothingInsteadOfCrashing() {
        assertEquals(emptyList<FaqGroup>(), FaqContent.parse("{ not json"))
        assertEquals(emptyList<FaqGroup>(), FaqContent.parse(""))
        assertEquals(emptyList<FaqGroup>(), FaqContent.parse("[]"))
    }

    @Test
    fun aHalfAnswerIsSkipped() {
        val text = """[{"group":"g","title":"T","items":[{"q":"问题","a":""},{"q":"问题二","a":"答案"}]}]"""
        val groups = FaqContent.parse(text)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].entries.size)
        assertEquals("问题二", groups[0].entries[0].question)
    }

    @Test
    fun theFileFollowsTheAppsLanguage() {
        assertEquals("nx2a/agent/workspace/knowledge/faq_zh.json", FaqContent.fileFor(AppLocale.Mode.ZH, "en"))
        assertEquals("nx2a/agent/workspace/knowledge/faq_en.json", FaqContent.fileFor(AppLocale.Mode.EN, "zh"))
        assertEquals("nx2a/agent/workspace/knowledge/faq_zh.json", FaqContent.fileFor(AppLocale.Mode.SYSTEM, "zh"))
        assertEquals("nx2a/agent/workspace/knowledge/faq_en.json", FaqContent.fileFor(AppLocale.Mode.SYSTEM, "en"))
        assertEquals("nx2a/agent/workspace/knowledge/faq_en.json", FaqContent.fileFor(AppLocale.Mode.SYSTEM, "fr"))
    }

    private fun zhJson(): String = asset("nx2a/agent/workspace/knowledge/faq_zh.json")
    private fun enJson(): String = asset("nx2a/agent/workspace/knowledge/faq_en.json")

    /** 内容文件的位置：模块目录（android/app）下就是它，往上两级也能找到 */
    private fun asset(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "src/main/assets/$relative"),
                File(dir, "app/src/main/assets/$relative"),
            )) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot find $relative from ${File(".").absolutePath}")
    }
}
