package com.welder.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 联网搜题
 * 调用公开题库接口
 */
class OnlineSearcher {

    companion object {
        // 公开搜题接口（示例，可替换为实际接口）
        private const val SEARCH_API = "https://tk.enncy.cn/query"
        private const val TIMEOUT_SECONDS = 10L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 联网搜题
     */
    suspend fun search(questionText: String): QuestionAnswer? {
        return withContext(Dispatchers.IO) {
            try {
                // 提取核心关键词
                val keywords = extractKeywords(questionText)
                val encodedKeywords = URLEncoder.encode(keywords, "UTF-8")

                val request = Request.Builder()
                    .url("$SEARCH_API?keyword=$encodedKeywords")
                    .header("User-Agent", "WelderHelper/1.0")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    parseResponse(body, questionText)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * 提取关键词
     */
    private fun extractKeywords(text: String): String {
        // 去除标点符号、空格、常见干扰词
        val cleaned = text
            .replace(Regex("[\\p{Punct}\\s]+"), " ")
            .replace(Regex("(以下|下列|关于|应该|正确|错误|说法|选项)"), "")
            .trim()

        // 取前30个字符作为关键词
        return if (cleaned.length > 30) cleaned.substring(0, 30) else cleaned
    }

    /**
     * 解析搜题接口返回
     */
    private fun parseResponse(jsonStr: String?, originalQuestion: String): QuestionAnswer? {
        if (jsonStr.isNullOrBlank()) return null

        return try {
            val json = JSONObject(jsonStr)
            val code = json.optInt("code", -1)

            if (code == 1 || code == 200) {
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val first = data.getJSONObject(0)
                    QuestionAnswer(
                        id = 0,
                        question = first.optString("question", originalQuestion),
                        options = first.optString("options", ""),
                        answer = first.optString("answer", "未找到"),
                        explain = first.optString("explain", ""),
                        type = 1
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
