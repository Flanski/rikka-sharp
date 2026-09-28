package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import me.rerere.ai.core.ReasoningTrace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ResponseAPI"

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default()
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            // ★把失败的请求**与它的 input 结构一起**记下来。
            //   之前的日志只记请求、不记结果，于是报错发生时报不出「是哪一次请求、当时的 input 长什么样」，
            //   只能事后翻全部请求去猜 —— 这次就是因为这个卡住的。
            val errBody = response.body.string()
            ReasoningTrace.logLazy {
                "RESP FAILED code=${response.code}\n" +
                    "    body=${errBody.take(2000)}"
            }
            throw Exception("Failed to get response: ${response.code} $errBody")
        }

        val bodyStr = response.body?.string() ?: ""
        Log.i(TAG, "generateText: $bodyStr")
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val output = parseResponseOutput(bodyJson)

        return output
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        val decoder = ResponseApiStreamDecoder()

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: $id/$type $data")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        println(bodyElement)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                sendChunks(decoder.onClosed())
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            put("store", false)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put(
                    "instructions",
                    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }

            // messages
            val inputArray = buildMessages(messages)
            // ★请求级总览：这一步能回答「这次请求到底发出去了几条 reasoning、有没有残缺的」。
            //
            // 其中 `missingText` 是最关键的指标 —— 它数的是
            // 「带了 encrypted_content、却**没有** reasoning_text 明文」的 item。
            // DeepSeek 的报错（reasoning_text must be passed back）指向的正是这种残缺 item；
            // 一旦报错复现，只要在日志里看到 missingText>0，就直接锁定了成因。
            ReasoningTrace.logLazy {
                val inputObjects = inputArray.filterIsInstance<JsonObject>()
                val reasoningItems = inputObjects
                    .filter { it["type"]?.jsonPrimitiveOrNull?.contentOrNull == "reasoning" }
                val missingText = reasoningItems.count {
                    it.containsKey("encrypted_content") &&
                        ((it["content"] as? JsonArray)?.isEmpty() != false)
                }
                val noId = reasoningItems.count { !it.containsKey("id") }

                // ── ★「漏发」检测 ──
                // 报错说的是 `reasoning_text ... must be passed back`，即**必须回传**。
                // 此前我一直在找「发了但格式不对」，但从没检查过「**根本没发**」这种可能 ——
                // 而 buildMessages 确实有过滤条件，某些消息会被整条丢弃，其 reasoning 也随之漏掉。
                //
                // 这里把 messages 里的 reasoning 总数与实际发出的对比：
                // 不相等就说明有漏发，且差值就是漏掉的数量。
                val inMessages = messages.sumOf { m ->
                    m.parts.count { it is me.rerere.ai.ui.UIMessagePart.Reasoning }
                }
                val leaked = inMessages - reasoningItems.size

                // ── 结构序列 ──
                // 把 input 的 item 类型压成一个短串，用于判断 reasoning 的**位置**是否合理
                // （例如是否出现了孤立的 reasoning、或某个 assistant 轮次少了 reasoning）。
                // 编码：u=user / a=assistant message / r=reasoning / f=function_call / o=function_call_output
                val structure = buildString {
                    inputObjects.take(300).forEach { item ->
                        val t = item["type"]?.jsonPrimitiveOrNull?.contentOrNull
                        append(
                            when {
                                t == "reasoning" -> 'r'
                                t == "message" -> when (item["role"]?.jsonPrimitiveOrNull?.contentOrNull) {
                                    "assistant" -> 'a'
                                    "user" -> 'u'
                                    "system", "developer" -> 's'
                                    else -> 'm'
                                }
                                t == "function_call" -> 'f'
                                t == "function_call_output" -> 'o'
                                t != null -> '?'
                                // 没有 type 字段的是普通 role 消息
                                else -> when (item["role"]?.jsonPrimitiveOrNull?.contentOrNull) {
                                    "user" -> 'u'
                                    "assistant" -> 'a'
                                    "system", "developer" -> 's'
                                    else -> 'x'
                                }
                            }
                        )
                    }
                    if (inputObjects.size > 300) append("...")
                }

                "REQ input items=${inputArray.size} reasoning=${reasoningItems.size}" +
                    " missingText=$missingText noId=$noId" +
                    // ★inMessages/leaked 是这次新加的关键指标
                    " inMessages=$inMessages leaked=$leaked" +
                    " model=${params.model.modelId} tools=${params.tools.size}" +
                    "\n    struct=$structure"
            }
            put("input", inputArray)

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    if (level != ReasoningLevel.AUTO) {
                        put("effort", level.effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

            // tools
            // Response API 的 tools 是扁平数组, 函数工具和内置工具可以共存, 必须写在同一个 key 下,
            // 否则后写入的会覆盖前者
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            if (useFunctionTools || params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (useFunctionTools) {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.apiParameters()
                                    )
                                )
                            })
                        }
                    }
                    // built-in tools
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    internal fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        // ★这里有一条会**整条丢弃消息**的过滤，是「reasoning 漏发」的唯一入口。
        //
        // 报错 `The reasoning_text in the thinking mode must be passed back to the API`
        // 说的是「必须回传」—— 我一直只在查「发了但格式不对」，
        // 却忽略了「**根本没发**」这种可能。而这段 filter 正是那种可能发生的地方：
        // 一条 assistant 消息若 `isValidToUpload()` 为 false，
        // 且它的 reasoning 都没有 encryptedContent，就会被**整条丢掉**，
        // 其中的 reasoning 也跟着消失。
        //
        // 关键在于：**被丢掉的东西不会出现在任何日志里**（日志记的是"实际发出"的），
        // 所以此前完全看不见。这里把丢弃行为显式记下来。
        val (kept, dropped) = messages.partition { message ->
            message.role != MessageRole.SYSTEM && (
                message.isValidToUpload() || message.parts.any { part ->
                    part is UIMessagePart.Reasoning &&
                        part.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent != null
                }
            )
        }

        ReasoningTrace.logLazy {
            if (dropped.isEmpty()) {
                "SEND kept=${kept.size} dropped=0 reasoningInKept=" +
                    kept.sumOf { m -> m.parts.count { it is UIMessagePart.Reasoning } }
            } else {
                // 逐条说明「丢了什么、为什么」—— 特别是里面有没有 reasoning
                val detail = dropped.joinToString(" | ") { m ->
                    val reasonings = m.parts.count { it is UIMessagePart.Reasoning }
                    val blankReasonings = m.parts.filterIsInstance<UIMessagePart.Reasoning>()
                        .count { it.reasoning.isBlank() }
                    val withEnc = m.parts.filterIsInstance<UIMessagePart.Reasoning>()
                        .count { it.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent != null }
                    "${m.role} parts=${m.parts.size} reasoning=$reasonings(blank=$blankReasonings,enc=$withEnc)"
                }
                "SEND ★DROPPED ${dropped.size} messages（其中的 reasoning 会一并漏发）\n" +
                    "    $detail\n" +
                    "    kept=${kept.size} reasoningInKept=" +
                    kept.sumOf { m -> m.parts.count { it is UIMessagePart.Reasoning } }
            }
        }

        kept.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantItems(message)
            } else {
                addUserItems(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantItems(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    val emittedReasoningIds = mutableSetOf<String>()
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                val reasoningId = reasoningMetadata?.reasoningId
                                if (reasoningId != null && !emittedReasoningIds.add(reasoningId)) {
                                    return@forEach
                                }
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                val reasoningParts = if (reasoningId == null) {
                                    listOf(part)
                                } else {
                                    group.parts.filterIsInstance<UIMessagePart.Reasoning>().filter {
                                        it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId == reasoningId
                                    }
                                }
                                // 先构造、再记录、最后 add ——
                                // 这样日志里能看到**实际发出的那个对象**，而不是"应该发出什么"。
                                val emittedReasoning = buildJsonObject {
                                    put("type", "reasoning")
                                    reasoningId?.let { put("id", it) }
                                    put("summary", buildJsonArray {
                                        reasoningParts
                                            .filter { it.reasoningType == ReasoningType.SUMMARY_TEXT }
                                            .filter { it.reasoning.isNotEmpty() }
                                            .forEach {
                                                add(buildJsonObject {
                                                    put("type", "summary_text")
                                                    put("text", it.reasoning)
                                                })
                                            }
                                    })
                                    val content = reasoningParts
                                        .filter { it.reasoningType == ReasoningType.REASONING_TEXT }
                                        .filter { it.reasoning.isNotEmpty() }
                                    if (content.isNotEmpty()) {
                                        put("content", buildJsonArray {
                                            content.forEach {
                                                add(buildJsonObject {
                                                    put("type", "reasoning_text")
                                                    put("text", it.reasoning)
                                                })
                                            }
                                        })
                                    }
                                    reasoningMetadata?.encryptedContent?.let {
                                        put("encrypted_content", it)
                                    }
                                }
                                ReasoningTrace.logLazy {
                                    val perPart = reasoningParts.joinToString(" ; ") { p ->
                                        val m = p.metadataAs<OpenAIReasoningMetadata>()
                                        "type=${p.reasoningType},len=${p.reasoning.length}," +
                                            "id=${m?.reasoningId ?: "-"},enc=${if (m?.encryptedContent != null) "Y" else "N"}"
                                    }
                                    val textCount = (emittedReasoning["content"] as? JsonArray)?.size ?: 0
                                    buildString {
                                        append("REQ reasoning itemId=").append(reasoningId ?: "(null)")
                                        append(" parts=").append(reasoningParts.size)
                                        append(" → textParts=").append(textCount)
                                        append(" hasEncrypted=").append(emittedReasoning.containsKey("encrypted_content"))
                                        append("\n    parts: ").append(perPart)
                                        append("\n    emitted: ").append(emittedReasoning.toString().take(3000))
                                    }
                                }
                                add(emittedReasoning)
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part))
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            is UIMessagePart.ServerTool -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addServerToolItem(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // 同一批并发工具调用需先输出全部 function_call，再输出对应结果。
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                    }
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            val hasImage = tool.output.any { it is UIMessagePart.Image }
                            if (hasImage) {
                                putJsonArray("output") {
                                    tool.output.forEach { part ->
                                        when (part) {
                                            is UIMessagePart.Image -> add(buildJsonObject {
                                                part.encodeBase64().onSuccess { encoded ->
                                                    put("type", "input_image")
                                                    put("image_url", encoded.base64)
                                                }.onFailure {
                                                    it.printStackTrace()
                                                    put("type", "input_text")
                                                    put("text", "Error: Failed to encode image to base64")
                                                }
                                            })
                                            is UIMessagePart.Text -> add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                            else -> {}
                                        }
                                    }
                                }
                            } else {
                                put(
                                    "output",
                                    tool.output.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                )
                            }
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addServerToolItem(tool: UIMessagePart.ServerTool) {
        val metadata = tool.metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.OPENAI_RESPONSES) return

        val rawCall = metadata?.call.takeIf { protocol == ServerToolProtocol.OPENAI_RESPONSES }
        if (rawCall != null) {
            add(rawCall)
            return
        }

        add(buildJsonObject {
            put("type", "${tool.toolName.removeSuffix("_call")}_call")
            put("id", tool.toolCallId)
            put("status", tool.status.toOpenAIStatus())
            tool.input?.let { input ->
                if (tool.toolName.removeSuffix("_call") == "web_search") put("action", input)
                else if (input is JsonObject) input.forEach { (key, value) -> put(key, value) }
                else put("input", input)
            }
            tool.output?.let { put("output", it) }
        })
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "input_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    internal fun parseResponseOutput(jsonObject: JsonObject): TextGenerationResult {
        println(jsonObject)
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val reasoningMetadata = OpenAIReasoningMetadata(
                        reasoningId = output["id"]?.jsonPrimitive?.contentOrNull,
                        encryptedContent = output["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata()
                    val reasoningPartStart = parts.size
                    output["summary"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now(),
                                        metadata = reasoningMetadata,
                                        reasoningType = ReasoningType.SUMMARY_TEXT,
                                    )
                                )
                            }
                        }
                    }
                    output["content"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        if (part["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text") {
                            parts.add(
                                UIMessagePart.Reasoning(
                                    reasoning = part["text"]?.jsonPrimitive?.content ?: error("text not found"),
                                    createdAt = Clock.System.now(),
                                    finishedAt = Clock.System.now(),
                                    metadata = reasoningMetadata,
                                    reasoningType = ReasoningType.REASONING_TEXT,
                                )
                            )
                        }
                    }
                    if (parts.size == reasoningPartStart) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                createdAt = Clock.System.now(),
                                finishedAt = Clock.System.now(),
                                metadata = reasoningMetadata,
                                reasoningType = ReasoningType.REASONING_TEXT,
                            )
                        )
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }

                else -> if (isOpenAIServerToolCall(type)) {
                    parts.add(output.toOpenAIServerTool())
                }
            }
        }

        return TextGenerationResult(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = parts,
            ),
            finishReason = jsonObject["status"]?.jsonPrimitive?.contentOrNull,
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObject)
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

internal fun isOpenAIServerToolCall(type: String): Boolean =
    type.endsWith("_call") && type !in setOf(
        "function_call",
        "custom_tool_call",
        "computer_call",
        "local_shell_call",
        "shell_call",
        "image_generation_call",
    )

internal fun JsonObject.toOpenAIServerTool(): UIMessagePart.ServerTool {
    val type = get("type")?.jsonPrimitive?.contentOrNull ?: "server_tool_call"
    val protocolFields = setOf("type", "id", "status", "result", "output")
    val input = get("action") ?: JsonObject(filterKeys { it !in protocolFields })
        .takeUnless { it.isEmpty() }
    return UIMessagePart.ServerTool(
        toolCallId = get("id")?.jsonPrimitive?.contentOrNull ?: "",
        toolName = type.removeSuffix("_call"),
        input = input,
        output = get("output") ?: get("result"),
        status = get("status")?.jsonPrimitive?.contentOrNull.toServerToolStatus(),
        metadata = ServerToolMetadata(
            protocol = ServerToolProtocol.OPENAI_RESPONSES,
            call = this,
        ).toMetadata(),
    )
}

internal fun String?.toServerToolStatus(): ServerToolStatus = when (this) {
    "completed" -> ServerToolStatus.COMPLETED
    "failed", "incomplete", "cancelled" -> ServerToolStatus.FAILED
    else -> ServerToolStatus.IN_PROGRESS
}

private fun ServerToolStatus.toOpenAIStatus(): String = when (this) {
    ServerToolStatus.IN_PROGRESS -> "in_progress"
    ServerToolStatus.COMPLETED -> "completed"
    ServerToolStatus.FAILED -> "failed"
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        else -> ResponseProviderCapabilities()
    }
}
