package com.nx.android.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The endpoint probe: the addresses it builds, the lists it reads, the failures it names.
 */
class AgentModelProbeTest {

    @Test
    fun theModelsAddressIsTheEndpointPlusModels() {
        assertEquals(
            "https://api.deepseek.com/models",
            AgentModelProbe.modelsUrl("https://api.deepseek.com"),
        )
    }

    @Test
    fun aTrailingSlashDoesNotDoubleUp() {
        assertEquals(
            "https://api.moonshot.cn/v1/models",
            AgentModelProbe.modelsUrl("https://api.moonshot.cn/v1/"),
        )
    }

    @Test
    fun thePathTheEndpointAlreadyHasIsKept() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            AgentModelProbe.modelsUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun theChatAddressIsTheEndpointPlusChatCompletions() {
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            AgentModelProbe.chatUrl("https://ark.cn-beijing.volces.com/api/v3"),
        )
    }

    @Test
    fun theDataArrayIsTheList() {
        val text = """{"object":"list","data":[{"id":"glm-4.6"},{"id":"glm-4.5"}]}"""

        assertEquals(listOf("glm-4.6", "glm-4.5"), AgentModelProbe.parseModels(text))
    }

    @Test
    fun theModelsArrayIsTheListToo() {
        val text = """{"models":[{"id":"a"},{"id":"b"}]}"""

        assertEquals(listOf("a", "b"), AgentModelProbe.parseModels(text))
    }

    @Test
    fun anEntryWithoutAnIdFallsBackToItsName() {
        val text = """{"data":[{"name":"only-a-name"}]}"""

        assertEquals(listOf("only-a-name"), AgentModelProbe.parseModels(text))
    }

    @Test
    fun theSameModelTwiceIsOneModel() {
        val text = """{"data":[{"id":"a"},{"id":"a"},{"id":"b"}]}"""

        assertEquals(listOf("a", "b"), AgentModelProbe.parseModels(text))
    }

    @Test
    fun aLongListIsCut() {
        val text = (1..500).joinToString(",", """{"data":[""") { """{"id":"m$it"}""" } + "]}"

        val ids = AgentModelProbe.parseModels(text)

        assertEquals(200, ids?.size)
        assertEquals("m1", ids?.first())
    }

    @Test
    fun anEmptyListIsAListWithNothingInIt() {
        assertEquals(emptyList<String>(), AgentModelProbe.parseModels("""{"data":[]}"""))
    }

    @Test
    fun somethingThatIsNotAListIsNotRead() {
        assertNull(AgentModelProbe.parseModels("""{"error":"nope"}"""))
        assertNull(AgentModelProbe.parseModels("not json at all"))
    }

    @Test
    fun theCodesAreNamed() {
        assertEquals(AgentModelProbe.Why.BadKey, AgentModelProbe.whyFor(401))
        assertEquals(AgentModelProbe.Why.BadKey, AgentModelProbe.whyFor(403))
        assertEquals(AgentModelProbe.Why.NoSuchApi, AgentModelProbe.whyFor(404))
        assertEquals(AgentModelProbe.Why.NotAccepted, AgentModelProbe.whyFor(400))
        assertEquals(AgentModelProbe.Why.ServerSide, AgentModelProbe.whyFor(500))
        assertEquals(AgentModelProbe.Why.ServerSide, AgentModelProbe.whyFor(503))
    }

    @Test
    fun anEndpointThatCannotBeReachedIsANetworkProblem() {
        // 端口上没有东西听：连不上，不是端点不认
        val outcome = AgentModelProbe.models("http://127.0.0.1:1/v1", "sk-test")

        assertTrue(outcome is AgentModelProbe.Outcome.Failed)
        assertEquals(AgentModelProbe.Why.Network, (outcome as AgentModelProbe.Outcome.Failed).why)
    }

    @Test
    fun theCleartextRefusalIsItsOwnThing() {
        // 系统拦下明文 http 时的说法：不是网络不通，是端点得用 https
        val blocked = java.io.IOException("Cleartext HTTP traffic to 127.0.0.1 not permitted")

        assertTrue(blocked.message!!.contains("Cleartext", ignoreCase = true))
    }
}
