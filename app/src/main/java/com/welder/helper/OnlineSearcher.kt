package com.welder.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 联网搜题 v2
 * 改进：使用多个搜题接口 + 搜索引擎抓取
 */
class OnlineSearcher {

    companion object {
        private const val TIMEOUT_SECONDS = 15L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun search(questionText: String): QuestionAnswer? {
        return withContext(Dispatchers.IO) {
            val keywords = extractKeywords(questionText)

            // 策略1: 百度搜索引擎抓取
            val baiduResult = searchBaidu(keywords, questionText)
            if (baiduResult != null) return@withContext baiduResult

            // 策略2: 搜狗搜索引擎抓取
            val sogouResult = searchSogou(keywords, questionText)
            if (sogouResult != null) return@withContext sogouResult

            // 策略3: 必应搜索引擎抓取
            searchBing(keywords, questionText)
        }
    }

    private fun searchBaidu(keywords: String, originalQuestion: String): QuestionAnswer? {
        return try {
            val encoded = URLEncoder.encode(keywords, "UTF-8")
            val request = Request.Builder()
                .url("https://www.baidu.com/s?wd=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Mobile Safari/537.36")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: return@try null
                parseSearchResult(html, originalQuestion)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun searchSogou(keywords: String, originalQuestion: String): QuestionAnswer? {
        return try {
            val encoded = URLEncoder.encode(keywords, "UTF-8")
            val request = Request.Builder()
                .url("https://www.sogou.com/web?query=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Mobile Safari/537.36")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: return@try null
                parseSearchResult(html, originalQuestion)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun searchBing(keywords: String, originalQuestion: String): QuestionAnswer? {
        return try {
            val encoded = URLEncoder.encode(keywords, "UTF-8")
            val request = Request.Builder()
                .url("https://cn.bing.com/search?q=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Mobile Safari/537.36")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: return@try null
                parseSearchResult(html, originalQuestion)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 解析搜索结果HTML
     * 从搜索结果中提取答案信息
     */
    private fun parseSearchResult(html: String, originalQuestion: String): QuestionAnswer? {
        if (html.isBlank()) return null

        // 从HTML中提取答案
        // 查找"答案"、"正确答案"、"参考答案"等关键词
        val answerPatterns = listOf(
            Regex("答案[：:]?\\s*([A-Ea-e])", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("正确答案[：:]?\\s*([A-Ea-e])", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("参考答案[：:]?\\s*([A-Ea-e])", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("答案[：:]?\\s*(正确|错误)", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("[正确错误]", setOf(RegexOption.DOT_MATCHES_ALL))
        )

        var answer = "未找到"
        for (pattern in answerPatterns) {
            val matchResult = pattern.find(html)
            if (matchResult != null) {
                answer = matchResult.groupValues[1]
                if (answer.isNotEmpty()) break
            }
        }

        // 查找解析
        val explainPatterns = listOf(
            Regex("解析[：:]?\\s*([^<\\n]+)", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("解释[：:]?\\s*([^<\\n]+)", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("说明[：:]?\\s*([^<\\n]+)", setOf(RegexOption.DOT_MATCHES_ALL))
        )

        var explain = ""
        for (pattern in explainPatterns) {
            val matchResult = pattern.find(html)
            if (matchResult != null) {
                explain = matchResult.groupValues[1].trim()
                if (explain.length > 5) break
            }
        }

        // 如果找到了答案，返回结果
        if (answer != "未找到") {
            return QuestionAnswer(
                id = 0,
                question = originalQuestion,
                options = "",
                answer = answer,
                explain = explain.ifEmpty { "联网搜索结果" },
                type = 1
            )
        }

        return null
    }

    private fun extractKeywords(text: String): String {
        val cleaned = text
            .replace(Regex("[\\\\p{Punct}\\\\s]+"), " ")
            .replace(Regex("(以下|下列|关于|应该|正确|错误|说法|选项|哪种|哪个|什么)"), "")
            .trim()

        // 取前20个字符作为关键词（更精确）
        return if (cleaned.length > 20) cleaned.substring(0, 20) else cleaned
    }
}