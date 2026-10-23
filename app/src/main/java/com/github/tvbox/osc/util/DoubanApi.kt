package com.github.tvbox.osc.util

import com.github.tvbox.osc.util.net.Http
import com.github.tvbox.osc.util.net.HttpRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 豆瓣短评抓取（需用户导入登录 Cookie）。
 * 豆瓣禁止匿名抓取：影片页 302、评论接口 403；Cookie 失效统一抛 [DoubanAuthException]。
 */
object DoubanApi {

    private const val SUBJECT_SUGGEST_URL = "https://movie.douban.com/j/subject_suggest"
    private const val COMMENTS_URL_TEMPLATE = "https://movie.douban.com/subject/%s/comments"
    private const val SUBJECT_URL_TEMPLATE = "https://movie.douban.com/subject/%s/"
    private const val PAGE_LIMIT = 20
    // 超时沿用 Http 共享 OkHttpClient（连接/读取 10s），暂不支持按请求覆盖

    private const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val REFERER = "https://movie.douban.com/"
    private const val LOGIN_HINT = "登录豆瓣"
    private const val ACCOUNTS_HOST = "accounts.douban.com"
    private const val AUTH_COOKIE_KEY = "dbcl2"

    private const val CACHE_PREFIX = "cache_douban_"
    private const val COMMENTS_CACHE_PREFIX = "cache_douban_comments_"
    private const val RESOLVE_CACHE_PREFIX = "cache_douban_resolve_"
    private const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val CACHE_ENVELOPE_V1 = "1|"

    private val suggestType: Type = object : TypeToken<List<SuggestItem>>() {}.type

    private val commentListType: Type = object : TypeToken<List<DoubanComment>>() {}.type

    private val gson = Gson()

    private data class SuggestItem(
        val id: String? = null,
        val title: String? = null,
        val year: String? = null,
        val type: String? = null,
    )

    data class DoubanComment(
        val nick: String,
        val stars: Int,
        val content: String,
        val votes: Int,
        val time: String,
    )

    data class DoubanCommentsResult(
        val comments: List<DoubanComment>,
        val hasMore: Boolean,
    )

    data class DoubanRating(
        val score: String,
        val count: String,
    )

    class DoubanAuthException : Exception("douban auth expired")

    /**
     * 从 Netscape cookies.txt 文本中提取 douban.com 的 Cookie 串。
     * 有效登录至少需要 dbcl2；无效返回 null。
     */
    fun extractCookie(netscapeText: String): String? {
        val pairs = ArrayList<String>()
        var hasAuth = false
        for (line in netscapeText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val cols = trimmed.split('\t')
            if (cols.size < 7) continue
            if (!cols[0].contains("douban.com")) continue
            val name = cols[5].trim()
            val value = cols[6].trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (name == AUTH_COOKIE_KEY) hasAuth = true
            pairs.add("$name=$value")
        }
        return if (hasAuth && pairs.isNotEmpty()) pairs.joinToString("; ") else null
    }

    fun hasCookie(): Boolean = readCookie().isNotBlank()

    fun currentCookie(): String = readCookie()

    fun maskedCookie(): String {
        val cookie = readCookie()
        if (cookie.isBlank()) return ""
        val masked = cookie.take(4) + "…" + cookie.takeLast(4)
        return masked
    }

    private fun readCookie(): String = try {
        KV.get(HawkConfig.DOUBAN_COOKIE, "")
    } catch (e: Exception) {
        LOG.e("DoubanApi", e)
        ""
    }

    private fun requestInit(cookie: String, extra: HttpRequest.() -> Unit = {}): HttpRequest.() -> Unit = {
        headers("User-Agent", DESKTOP_CHROME_UA)
        headers("Referer", REFERER)
        headers("Cookie", cookie)
        extra()
    }

    private fun detectAuth(code: Int, body: String, location: String?): Boolean {
        if (code == 302 || code == 403) return true
        if (location.orEmpty().contains(ACCOUNTS_HOST)) return true
        return body.contains(LOGIN_HINT)
    }

    /**
     * 标题 → doubanId（subject_suggest 兜底）。带 30 天缓存。
     */
    suspend fun resolveDoubanId(name: String, cookie: String): String? {
        val key = RESOLVE_CACHE_PREFIX + MD5.encode(name.trim())
        readCached(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = try {
            fetchResolve(name, cookie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DoubanAuthException) {
            throw e
        } catch (e: Exception) {
            LOG.e("DoubanApi", e)
            null
        } ?: return null
        writeCached(key, id)
        return id
    }

    private suspend fun fetchResolve(name: String, cookie: String): String? {
        val url = SUBJECT_SUGGEST_URL + "?q=" + URLEncoder.encode(name.trim(), "UTF-8")
        val response = Http.getRaw(url, requestInit(cookie))
        val text = String(response.body, Charsets.UTF_8)
        if (detectAuth(response.code, text, response.headers["Location"])) {
            throw DoubanAuthException()
        }
        if (response.code !in 200..299) return null
        val items = try {
            gson.fromJson<List<SuggestItem>>(text, suggestType).orEmpty()
        } catch (e: Exception) {
            LOG.e("DoubanApi", e)
            return null
        }
        return items.firstOrNull { !it.id.isNullOrBlank() }?.id
    }

    /**
     * 短评列表（20 条/页）。带 30 天缓存；页间由调用方 delay。
     */
    suspend fun comments(doubanId: String, cookie: String, start: Int): DoubanCommentsResult {
        val key = COMMENTS_CACHE_PREFIX + doubanId + "_" + start
        readCached(key)?.let { raw ->
            return decodeComments(raw) ?: DoubanCommentsResult(emptyList(), false)
        }
        val result = try {
            fetchComments(doubanId, cookie, start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DoubanAuthException) {
            throw e
        } catch (e: Exception) {
            LOG.e("DoubanApi", e)
            return DoubanCommentsResult(emptyList(), false)
        }
        if (result.comments.isNotEmpty()) {
            writeCached(key, gson.toJson(result.comments, commentListType))
        }
        return result
    }

    private suspend fun fetchComments(
        doubanId: String,
        cookie: String,
        start: Int,
    ): DoubanCommentsResult {
        val url = COMMENTS_URL_TEMPLATE.format(doubanId)
        val response = Http.getRaw(url, requestInit(cookie) {
            params("status", "P")
            params("limit", PAGE_LIMIT.toString())
            params("start", start.toString())
            params("sort", "new_score")
        })
        val text = String(response.body, Charsets.UTF_8)
        if (detectAuth(response.code, text, response.headers["Location"])) {
            throw DoubanAuthException()
        }
        if (response.code !in 200..299) return DoubanCommentsResult(emptyList(), false)
        return parseComments(text)
    }

    internal fun parseComments(html: String): DoubanCommentsResult {
        val doc = Jsoup.parse(html)
        val comments = ArrayList<DoubanComment>()
        for (item in doc.select("div.comment-item")) {
            val nick = item.selectFirst(".comment-info a")?.text()?.trim().orEmpty()
            val stars = parseStars(item.selectFirst(".comment-info .rating"))
            val content = item.selectFirst("p.comment-content .short")?.text()?.trim().orEmpty()
            val votes = item.selectFirst(".comment-vote .votes")?.text()?.trim()?.toIntOrNull() ?: 0
            val time = item.selectFirst(".comment-info .comment-time")?.text()?.trim().orEmpty()
            if (content.isEmpty() && nick.isEmpty()) continue
            comments.add(DoubanComment(nick, stars, content, votes, time))
        }
        val hasMore = doc.selectFirst("#paginator > a.next") != null
        return DoubanCommentsResult(comments, hasMore)
    }

    private fun parseStars(rating: Element?): Int {
        val cls = rating?.classNames().orEmpty()
        val starClass = cls.firstOrNull { it.startsWith("allstar") } ?: return 0
        return starClass.removePrefix("allstar").toIntOrNull()?.div(10) ?: 0
    }

    /**
     * 评分概览（可选）。失败返回 null，调用方不展示评分头。
     */
    suspend fun rating(doubanId: String, cookie: String): DoubanRating? {
        return try {
            fetchRating(doubanId, cookie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DoubanAuthException) {
            throw e
        } catch (e: Exception) {
            LOG.e("DoubanApi", e)
            null
        }
    }

    private suspend fun fetchRating(doubanId: String, cookie: String): DoubanRating? {
        val url = SUBJECT_URL_TEMPLATE.format(doubanId)
        val response = Http.getRaw(url, requestInit(cookie))
        val text = String(response.body, Charsets.UTF_8)
        if (detectAuth(response.code, text, response.headers["Location"])) {
            throw DoubanAuthException()
        }
        if (response.code !in 200..299) return null
        val doc = Jsoup.parse(text)
        val score = doc.selectFirst(".rating_num")?.text()?.trim().orEmpty()
        val countRaw = doc.selectFirst(".rating_sum span")?.text()?.trim().orEmpty()
        val count = Regex("\\d+").find(countRaw)?.value.orEmpty()
        if (score.isEmpty()) return null
        return DoubanRating(score, count)
    }

    private fun readCached(key: String): String? {
        val raw = try {
            KV.get<String>(key)
        } catch (e: Exception) {
            LOG.e("DoubanApi", e)
            null
        } ?: return null
        if (!raw.startsWith(CACHE_ENVELOPE_V1)) return raw
        val tsEnd = raw.indexOf('|', CACHE_ENVELOPE_V1.length)
        if (tsEnd < 0) return null
        val writtenAt = raw.substring(CACHE_ENVELOPE_V1.length, tsEnd).toLongOrNull() ?: return null
        if (System.currentTimeMillis() - writtenAt > POSITIVE_TTL_MS) {
            try {
                KV.delete(key)
            } catch (e: Exception) {
                LOG.e("DoubanApi", e)
            }
            return null
        }
        return raw.substring(tsEnd + 1)
    }

    private fun writeCached(key: String, value: String) {
        val wrapped = CACHE_ENVELOPE_V1 + System.currentTimeMillis() + "|" + value
        try {
            KV.put(key, wrapped)
        } catch (e: Exception) {
            LOG.e("DoubanApi", e)
        }
    }

    private fun decodeComments(raw: String): DoubanCommentsResult? {
        return try {
            val comments = gson.fromJson<List<DoubanComment>>(raw, commentListType).orEmpty()
            DoubanCommentsResult(comments, true)
        } catch (e: Exception) {
            LOG.e("DoubanApi", e)
            null
        }
    }

    /**
     * 连续翻页的礼貌间隔（≥1s），调用方在每次翻页前调用。
     */
    suspend fun pageDelay() {
        delay(TimeUnit.SECONDS.toMillis(1))
    }
}
