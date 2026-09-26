package me.rerere.ai.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [supportsBuiltInSearch] 的回归测试。
 *
 * 背景：原实现在 UI 层写死了白名单
 * `provider is Google || (provider is OpenAI && provider.useResponseApi)`，
 * **漏掉了 Claude 类型** —— 而 ClaudeProvider 明确会发送 `web_search_20250305`，
 * 导致「provider 有能力、界面上却找不到开关」。
 */
class ProviderSettingBuiltInSearchTest {

    @Test
    fun `google supports built-in search`() {
        assertTrue(ProviderSetting.Google().supportsBuiltInSearch)
    }

    /** 本次修复的核心：Claude 类型必须被识别为支持。 */
    @Test
    fun `claude supports built-in search`() {
        assertTrue(ProviderSetting.Claude().supportsBuiltInSearch)
    }

    @Test
    fun `openai with responses api supports built-in search`() {
        assertTrue(ProviderSetting.OpenAI(useResponseApi = true).supportsBuiltInSearch)
    }

    /** chat/completions 路径完全不处理内置工具，声明会被静默丢弃。 */
    @Test
    fun `openai with chat completions does not support built-in search`() {
        assertFalse(ProviderSetting.OpenAI(useResponseApi = false).supportsBuiltInSearch)
    }
}
