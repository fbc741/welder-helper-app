package com.welder.helper

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * 题库管理
 * 本地SQLite存储 + 从assets导入
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
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "questions"

        // 列名
        private const val COL_ID = "id"
        private const val COL_QUESTION = "question"
        private const val COL_OPTIONS = "options"
        private const val COL_ANSWER = "answer"
        private const val COL_EXPLAIN = "explain"
        private const val COL_TYPE = "type"
    }

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        // 创建题库表
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
        // 创建全文搜索索引
        db.execSQL("CREATE INDEX idx_question ON $TABLE_NAME($COL_QUESTION)")

        // 导入本地题库
        importFromAssets(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    /**
     * 从assets导入题库JSON
     */
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
     * 本地题库匹配
     * 关键词模糊匹配
     */
    fun findAnswer(questionText: String): QuestionAnswer? {
        val db = readableDatabase

        // 提取关键词（去除标点符号和空格）
        val keywords = questionText
            .replace(Regex("[\\p{Punct}\\s]+"), " ")
            .split(" ")
            .filter { it.length >= 2 }
            .take(5)

        if (keywords.isEmpty()) return null

        // 构建查询条件
        val conditions = keywords.map { "$COL_QUESTION LIKE ?" }
        val selection = conditions.joinToString(" OR ")
        val selectionArgs = keywords.map { "%$it%" }.toTypedArray()

        // 按匹配度排序（匹配关键词越多越靠前）
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_QUESTION, COL_OPTIONS, COL_ANSWER, COL_EXPLAIN, COL_TYPE),
            selection,
            selectionArgs,
            null, null,
            "LENGTH($COL_QUESTION) ASC",  // 短题目优先
            "10"  // 最多返回10条
        )

        var bestMatch: QuestionAnswer? = null
        var bestScore = 0

        cursor.use {
            while (it.moveToNext()) {
                val dbQuestion = it.getString(it.getColumnIndexOrThrow(COL_QUESTION))

                // 计算匹配分数
                var score = 0
                for (keyword in keywords) {
                    if (dbQuestion.contains(keyword)) {
                        score++
                    }
                }

                // 如果匹配度超过阈值（至少50%关键词命中）
                if (score > bestScore && score >= keywords.size * 0.5) {
                    bestScore = score
                    bestMatch = QuestionAnswer(
                        id = it.getInt(it.getColumnIndexOrThrow(COL_ID)),
                        question = dbQuestion,
                        options = it.getString(it.getColumnIndexOrThrow(COL_OPTIONS)),
                        answer = it.getString(it.getColumnIndexOrThrow(COL_ANSWER)),
                        explain = it.getString(it.getColumnIndexOrThrow(COL_EXPLAIN)),
                        type = it.getInt(it.getColumnIndexOrThrow(COL_TYPE))
                    )
                }
            }
        }

        return bestMatch
    }

    /**
     * 清空题库
     */
    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_NAME, null, null)
    }

    /**
     * 获取题目数量
     */
    fun getCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }
}
