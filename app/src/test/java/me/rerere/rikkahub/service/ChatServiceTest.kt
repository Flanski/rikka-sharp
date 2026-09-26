package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)

        assertFalse(shouldUseExternalWebSearch(assistant))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)

        assertTrue(shouldUseExternalWebSearch(assistant))
    }

    /**
     * 回归测试：内置搜索声明**不得**再抑制本地搜索工具。
     *
     * `BuiltInTools.Search` 只是声明，服务端是否真的执行取决于 provider 的 API 路径
     * （OpenAI 兼容网关走 /chat/completions、DeepSeek 的 /responses 都会忽略它）。
     * 旧实现「声明存在即抑制本地」会导致模型手里一个搜索工具都没有。
     */
    @Test
    fun `built-in search declaration no longer suppresses external web search`() {
        val assistant = Assistant(enableWebSearch = true)

        assertTrue(shouldUseExternalWebSearch(assistant))
    }

    @Test
    fun `built-in search declaration without preference stays off`() {
        val assistant = Assistant(enableWebSearch = false)

        assertFalse(shouldUseExternalWebSearch(assistant))
    }
}
