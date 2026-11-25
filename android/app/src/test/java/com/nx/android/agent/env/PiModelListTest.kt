package com.nx.android.agent.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's model list: the table it prints, read back.
 */
class PiModelListTest {

    /** What `pi --list-models moonshotai-cn` prints: a header and a row per model. */
    private val table = """
        provider     model                    context  max-out  thinking  images
        moonshotai-cn  kimi-latest              256K     32K      yes      no
        moonshotai-cn  kimi-k2-turbo-preview   256K     32K      no       no
        moonshotai    moonshot-v1-8k           128K     8K       no       no
    """.trimIndent()

    @Test
    fun theRowsOfThatProviderAreTaken() {
        val models = PiModelList.parse(table, "moonshotai-cn")

        assertEquals(listOf("kimi-latest", "kimi-k2-turbo-preview"), models.map { it.id })
    }

    @Test
    fun theHeaderIsNotAModel() {
        val models = PiModelList.parse(table, "provider")

        assertTrue(models.isEmpty())
    }

    @Test
    fun theContextIsRead() {
        val models = PiModelList.parse(table, "moonshotai-cn")

        assertEquals(256_000L, models[0].contextWindow)
    }

    @Test
    fun thinkingIsRead() {
        val models = PiModelList.parse(table, "moonshotai-cn")

        assertTrue(models[0].reasoning)
        assertEquals(false, models[1].reasoning)
    }

    @Test
    fun anotherProvidersRowsAreLeftAlone() {
        // 模糊匹配会把国际版那家也带出来：只留自己要的那一家
        val models = PiModelList.parse(table, "moonshotai-cn")

        assertEquals(false, models.any { it.id == "moonshot-v1-8k" })
    }

    @Test
    fun noiseDoesNotBreakTheReading() {
        val noisy = """
            Warning: errors loading models.json:
            some detail

            $table
        """.trimIndent()

        val models = PiModelList.parse(noisy, "moonshotai-cn")

        assertEquals(2, models.size)
    }

    @Test
    fun aTableThatIsNotThereGivesNothing() {
        assertTrue(PiModelList.parse("No models matching \"kimi\"", "moonshotai-cn").isEmpty())
        assertTrue(PiModelList.parse("", "moonshotai-cn").isEmpty())
    }

    @Test
    fun theTokenCountsAreRead() {
        assertEquals(200_000L, PiModelList.parseTokens("200K"))
        assertEquals(1_500_000L, PiModelList.parseTokens("1.5M"))
        assertEquals(1_000_000L, PiModelList.parseTokens("1M"))
        assertEquals(131_072L, PiModelList.parseTokens("131072"))
        assertEquals(0L, PiModelList.parseTokens(""))
    }
}
