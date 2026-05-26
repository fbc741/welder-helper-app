package com.welder.helper

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * 题库管理 v2
 * 改进：相似度评分匹配 + 多策略搜索
 */
data class QuestionAnswer(
    val id: Int,
    val question: String,
    val options: String?,
    val answer: String,
    val explain: String?,
    val type: Int  // 0=判断 1=单选 2=多选
)

class QuestionBank(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "welder_questions.db"
        private const val DATABASE_VERSION = 2  // 升级版本号
        private const val TABLE_NAME = "questions"

        private const val COL_ID = "id"
        private const val COL_QUESTION = "question"
        private const val COL_OPTIONS = "options"
        private const val COL_ANSWER = "answer"
        private const val COL_EXPLAIN = "explain"
        private const val COL_TYPE = "type"
    }

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_QUESTION TEXT NOT NULL,
                $COL_OPTIONS TEXT,
                $COL_ANSWER TEXT NOT NULL,
                $COL_EXPLAIN TEXT,
                $COL_TYPE INTEGER DEFAULT 1
            )
        """)
        db.execSQL("CREATE INDEX idx_question ON $TABLE_NAME($COL_QUESTION)")
        importFromAssets(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    private fun importFromAssets(db: SQLiteDatabase) {
        try {
            val jsonStr = appContext.assets.open("question_bank/welder_questions.json")
                .bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val values = ContentValues().apply {
                    put(COL_QUESTION, obj.getString("question"))
                    put(COL_OPTIONS, obj.optString("options", ""))
                    put(COL_ANSWER, obj.getString("answer"))
                    put(COL_EXPLAIN, obj.optString("explain", ""))
                    put(COL_TYPE, obj.optInt("type", 1))
                }
                db.insert(TABLE_NAME, null, values)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 改进的识题匹配
     * 策略1: 直接包含匹配（最高优先级）
     * 策略2: 相似度评分（编辑距离+关键词）
     * 策略3: 模糊关键词匹配
     */
    fun findAnswer(questionText: String): QuestionAnswer? {
        val cleaned = cleanText(questionText)
        if (cleaned.length < 4) return null

        val db = readableDatabase

        // 策略1: 直接包含匹配
        val exactResult = directMatch(db, cleaned)
        if (exactResult != null) return exactResult

        // 策略2: 相似度评分匹配
        val similarResult = similarityMatch(db, cleaned)
        if (similarResult != null) return similarResult

        // 策略3: 关键词模糊匹配
        return fuzzyMatch(db, cleaned)
    }

    /**
     * 策略1: 直接包含匹配
     * OCR识别的文本包含题库题目或题库题目包含OCR文本
     */
    private fun directMatch(db: SQLiteDatabase, cleaned: String): QuestionAnswer? {
        // OCR文本包含题目
        var cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_QUESTION, COL_OPTIONS, COL_ANSWER, COL_EXPLAIN, COL_TYPE),
            "$COL_QUESTION LIKE ?",
            arrayOf("%$cleaned%"),
            null, null, null, "5"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return toQuestionAnswer(it)
            }
        }

        // 题目包含OCR文本（取前15个字作为特征）
        val feature = if (cleaned.length > 15) cleaned.substring(0, 15) else cleaned
        cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_QUESTION, COL_OPTIONS, COL_ANSWER, COL_EXPLAIN, COL_TYPE),
            "$COL_QUESTION LIKE ?",
            arrayOf("%$feature%"),
            null, null, "LENGTH($COL_QUESTION) ASC", "10"
        )
        cursor.use {
            var best: QuestionAnswer? = null
            while (it.moveToNext()) {
                val dbQ = it.getString(it.getColumnIndexOrThrow(COL_QUESTION))
                if (dbQ.contains(cleaned) || cleaned.contains(dbQ)) {
                    return toQuestionAnswer(it)
                }
                // 如果没有完全匹配，保留最接近的
                if (best == null && dbQ.contains(feature)) {
                    best = toQuestionAnswer(it)
                }
            }
            return best
        }
    }

    /**
     * 策略2: 相似度评分匹配
     * 使用编辑距离计算相似度
     */
    private fun similarityMatch(db: SQLiteDatabase, cleaned: String): QuestionAnswer? {
        val keywords = extractKeywords(cleaned)
        if (keywords.isEmpty()) return null

        // 用关键词召回候选题目
        val conditions = keywords.take(3).map { "$COL_QUESTION LIKE ?" }
        val selection = conditions.joinToString(" OR ")
        val selectionArgs = keywords.take(3).map { "%$it%" }.toTypedArray()

        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_QUESTION, COL_OPTIONS, COL_ANSWER, COL_EXPLAIN, COL_TYPE),
            selection, selectionArgs, null, null,
            "LENGTH($COL_QUESTION) ASC", "30"
        )

        var bestMatch: QuestionAnswer? = null
        var bestScore = 0.0

        cursor.use {
            while (it.moveToNext()) {
                val dbQ = it.getString(it.getColumnIndexOrThrow(COL_QUESTION))
                val dbCleaned = cleanText(dbQ)
                val score = calculateSimilarity(cleaned, dbCleaned)
                if (score > bestScore && score >= 0.6) {
                    bestScore = score
                    bestMatch = toQuestionAnswer(it)
                }
            }
        }
        return bestMatch
    }

    /**
     * 策略3: 模糊关键词匹配（兜底）
     */
    private fun fuzzyMatch(db: SQLiteDatabase, cleaned: String): QuestionAnswer? {
        val keywords = extractKeywords(cleaned).filter { it.length >= 2 }.take(5)
        if (keywords.isEmpty()) return null

        val conditions = keywords.map { "$COL_QUESTION LIKE ?" }
        val selection = conditions.joinToString(" OR ")
        val selectionArgs = keywords.map { "%$it%" }.toTypedArray()

        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_QUESTION, COL_OPTIONS, COL_ANSWER, COL_EXPLAIN, COL_TYPE),
            selection, selectionArgs, null, null,
            "LENGTH($COL_QUESTION) ASC", "20"
        )

        var bestMatch: QuestionAnswer? = null
        var bestScore = 0

        cursor.use {
            while (it.moveToNext()) {
                val dbQ = it.getString(it.getColumnIndexOrThrow(COL_QUESTION))
                var score = 0
                for (kw in keywords) {
                    if (dbQ.contains(kw)) score++
                }
                // 降低阈值到30%
                if (score > bestScore && score >= max(1, keywords.size * 0.3)) {
                    bestScore = score
                    bestMatch = toQuestionAnswer(it)
                }
            }
        }
        return bestMatch
    }

    /**
     * 清理文本：去除标点符号和多余空格
     */
    private fun cleanText(text: String): String {
        return text
            .replace(Regex("[，。！？、；：""''（）【】《》\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 提取关键词
     */
    private fun extractKeywords(text: String): List<String> {
        return text
            .replace(Regex("[\\\\p{Punct}\\\\s]+"), " ")
            .split(" ")
            .filter { it.length >= 2 }
            .distinct()
    }

    /**
     * 计算两个字符串的相似度（归一化编辑距离）
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val distance = levenshteinDistance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    /**
     * 编辑距离（Levenshtein Distance）
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = IntArray(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }

    private fun toQuestionAnswer(cursor: android.database.Cursor): QuestionAnswer {
        return QuestionAnswer(
            id = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ID)),
            question = cursor.getString(cursor.getColumnIndexOrThrow(COL_QUESTION)),
            options = cursor.getString(cursor.getColumnIndexOrThrow(COL_OPTIONS)),
            answer = cursor.getString(cursor.getColumnIndexOrThrow(COL_ANSWER)),
            explain = cursor.getString(cursor.getColumnIndexOrThrow(COL_EXPLAIN)),
            type = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TYPE))
        )
    }

    fun clearAll() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }

    fun getCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        cursor.use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }
}