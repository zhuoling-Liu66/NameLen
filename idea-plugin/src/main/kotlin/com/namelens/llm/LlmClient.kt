package com.namelens.llm

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.namelens.settings.NameLensSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 极薄的 OpenAI 兼容 Chat Completions 客户端（零第三方运行时依赖，Gson 为平台自带）。
 * 只允许在后台线程调用。
 */
class LlmClient(private val settings: NameLensSettings = NameLensSettings.getInstance()) {

    private val gson = Gson()
    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** @return assistant 文本内容 */
    fun chat(systemPrompt: String, userPrompt: String, temperature: Double = 0.2): String {
        val body = JsonObject().apply {
            addProperty("model", settings.model.trim())
            addProperty("temperature", temperature)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            )))
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(settings.chatCompletionsUrl))
            .timeout(Duration.ofSeconds(150))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        val response: HttpResponse<String> = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw LlmException("请求 LLM 失败（网络错误）: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            throw LlmException("LLM 服务返回 HTTP ${response.statusCode()}: ${response.body().take(400)}")
        }
        val root = JsonParser.parseString(response.body()).asJsonObject
        root.getAsJsonObject("error")?.let { err ->
            throw LlmException("LLM 返回错误: ${err.get("message")?.asString ?: err.toString()}")
        }
        val choices = root.getAsJsonArray("choices")
        val content = choices?.firstOrNull()
            ?.asJsonObject?.getAsJsonObject("message")?.get("content")
        return content?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw LlmException("LLM 响应缺少 choices[0].message.content: ${response.body().take(300)}")
    }

    companion object {
        /**
         * 从 LLM 输出中稳健提取 JSON 对象：容忍 ```json 围栏和前后多余文字。
         * 解析失败抛 LlmException。
         */
        fun extractJsonObject(text: String): JsonObject {
            var t = text.trim()
            if (t.startsWith("```")) {
                val firstLineEnd = t.indexOf('\n')
                if (firstLineEnd > 0) t = t.substring(firstLineEnd + 1) else t = t.removePrefix("```")
                if (t.endsWith("```")) t = t.removeSuffix("```")
                t = t.trim()
            }
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start < 0 || end <= start) {
                throw LlmException("LLM 未返回 JSON: ${text.take(300)}")
            }
            return try {
                JsonParser.parseString(t.substring(start, end + 1)).asJsonObject
            } catch (e: Exception) {
                throw LlmException("LLM 返回的 JSON 无法解析: ${text.take(300)}", e)
            }
        }
    }
}
