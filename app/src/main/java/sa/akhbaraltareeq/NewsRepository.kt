package sa.akhbaraltareeq

import android.content.Context
import android.util.Xml
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class NewsItem(val title: String, val description: String, val link: String, val source: String, val category: String, val published: Long, val articleText: String = "")
data class FeedSource(val name: String, val category: String, val url: String, val enabled: Boolean = true)

class NewsRepository(private val context: Context) {
    private fun cleanFeedText(raw: String): String {
        var value = raw.replace(Regex("<[^>]*>"), " ")
        repeat(3) {
            val decoded = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
            if (decoded != value) value = decoded
        }
        return value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private val prefs = context.getSharedPreferences("akhbar_settings", Context.MODE_PRIVATE)
    private val cachePrefs = context.getSharedPreferences("akhbar_cache", Context.MODE_PRIVATE)
    private fun googleNews(q: String) = "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=ar&gl=SA&ceid=SA:ar"

    fun sources(): List<FeedSource> {
        val regionalNames = listOf("المدينة المنورة", "تبوك", "مكة المكرمة", "الرياض", "جدة", "المنطقة الشرقية", "عسير")
        val defaults = listOf(
            FeedSource("أخبار السعودية", "السعودية", googleNews("السعودية")),
            FeedSource("أخبار العالم", "العالم", googleNews("أخبار العالم")),
            FeedSource("أخبار كرة القدم", "الرياضة", googleNews("(كرة القدم OR دوري روشن OR الدوري السعودي OR المنتخب السعودي OR دوري أبطال أوروبا OR الدوري الإنجليزي OR الدوري الإسباني OR الدوري الإيطالي OR الدوري الألماني OR الدوري الفرنسي OR كأس العالم OR انتقالات اللاعبين) -كرة السلة -التنس -الفورمولا -الطائرة")),
            FeedSource("أخبار التكنولوجيا", "التكنولوجيا", googleNews("التكنولوجيا والذكاء الاصطناعي"))
        ) + regionalNames.map { FeedSource(it, "المناطق", googleNews(it)) }
        val saved = prefs.getString("sources", null)
        if (saved != null) {
            val a = JSONArray(saved)
            val existing = (0 until a.length()).map { i -> a.getJSONObject(i).let {
                FeedSource(it.optString("name"), it.optString("category"), it.optString("url"), it.optBoolean("enabled", true))
            } }
            val completed = existing.map { current ->
                val updatedDefault = defaults.firstOrNull { it.category == current.category && (it.name == current.name || (current.category == "الرياضة" && current.name == "أخبار الرياضة")) }
                if (updatedDefault != null) updatedDefault.copy(enabled = current.enabled) else current
            } + defaults.filter { d -> existing.none { it.category == d.category && (it.name == d.name || (d.category == "الرياضة" && it.name == "أخبار الرياضة")) } }
            if (completed != existing) saveSources(completed)
            return completed
        }
        saveSources(defaults)
        return defaults
    }
    private fun saveSources(list: List<FeedSource>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("category", it.category).put("url", it.url).put("enabled", it.enabled)) }
        prefs.edit().putString("sources", a.toString()).apply()
    }
    fun addSource(name: String, url: String) {
        require(url.startsWith("https://")) { "يجب أن يبدأ رابط المصدر بـ https://" }
        saveSources(sources() + FeedSource(name.ifBlank { "مصدر جديد" }, "مخصص", url))
    }
    fun toggleSource(index: Int) { val all = sources().toMutableList(); if (index in all.indices) { all[index] = all[index].copy(enabled = !all[index].enabled); saveSources(all) } }
    fun selectedRegions(): Set<String> = prefs.getStringSet("regions", setOf("المدينة المنورة", "تبوك"))?.toSet() ?: emptySet()
    fun setRegion(name: String, enabled: Boolean) {
        val set = selectedRegions().toMutableSet()
        if (enabled) set.add(name) else set.remove(name)
        prefs.edit().putStringSet("regions", set).apply()
    }
    fun refresh(): Int {
        val regions = selectedRegions()
        val incoming = mutableListOf<NewsItem>()
        sources().filter { it.enabled }.filter { it.category != "المناطق" || regions.any { r -> it.name.contains(r) } }.forEach {
            try { incoming.addAll(fetch(it)) } catch (_: Exception) { }
        }
        val recent = (incoming + readCache())
            .filter { System.currentTimeMillis() - it.published <= TimeUnit.DAYS.toMillis(3) }
            .sortedByDescending { it.published }
        val merged = deduplicateNews(recent).take(250)
        writeCache(merged)
        return incoming.size
    }
    // Normalize Arabic headlines so publisher names, prefixes and small wording changes
    // do not create multiple cards for the same story.
    private val headlineNoise = setOf(
        "في", "من", "على", "عن", "بعد", "قبل", "مع", "الى", "إلى", "هذا", "هذه",
        "ذلك", "التي", "الذي", "كما", "حيث", "ضمن", "حول", "أمام", "لدى", "بشأن",
        "تقرير", "تفاصيل", "صور", "فيديو", "عاجل", "ثقافي", "ثقافية", "رياضي",
        "اقتصادي", "محلي", "اخبار", "أخبار"
    )

    private fun normalizedHeadline(value: String): String {
        var text = value.lowercase(Locale.ROOT)
            .replace(Regex("\\s+[|–—-]\\s+.*$"), "")
            .replace(Regex("^(ثقافي|ثقافية|رياضي|اقتصادي|محلي|عاجل)\\s*[/:-]\\s*"), "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun headlineTokens(value: String): Set<String> =
        normalizedHeadline(value).split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in headlineNoise }
            .toSet()

    private fun deduplicateNews(items: List<NewsItem>): List<NewsItem> {
        val kept = mutableListOf<NewsItem>()
        val normalized = mutableListOf<String>()
        val tokenSets = mutableListOf<Set<String>>()
        for (item in items) {
            val key = normalizedHeadline(item.title)
            val tokens = headlineTokens(item.title)
            var duplicate = -1
            for (i in kept.indices) {
                val otherKey = normalized[i]
                val other = tokenSets[i]
                if (key.isNotBlank() && key == otherKey) {
                    duplicate = i
                    break
                }
                if (tokens.size < 5 || other.size < 5) continue
                val overlap = tokens.intersect(other).size
                val smaller = minOf(tokens.size, other.size).coerceAtLeast(1)
                val containment = overlap.toDouble() / smaller
                val unionSimilarity = overlap.toDouble() / tokens.union(other).size.coerceAtLeast(1)
                // A headline can add context (city, timing, or "report about") while
                // still describing the same event. Require several meaningful shared terms.
                if (overlap >= 5 && containment >= 0.78 && unionSimilarity >= 0.48) {
                    duplicate = i
                    break
                }
            }
            if (duplicate < 0) {
                kept.add(item)
                normalized.add(key)
                tokenSets.add(tokens)
            } else {
                val previous = kept[duplicate]
                val previousQuality = previous.articleText.length * 3 + previous.description.length
                val currentQuality = item.articleText.length * 3 + item.description.length
                if (currentQuality > previousQuality) {
                    kept[duplicate] = item
                    normalized[duplicate] = key
                    tokenSets[duplicate] = tokens
                }
            }
        }
        return kept.sortedByDescending { it.published }
    }

    private fun fetch(source: FeedSource): List<NewsItem> {
        val c = URL(source.url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "AkhbarAlTareeq/0.2")
        try {
            c.inputStream.use { input ->
                val p = Xml.newPullParser(); p.setInput(input, "UTF-8")
                val out = mutableListOf<NewsItem>()
                var event = p.eventType; var inside = false
                var title = ""; var desc = ""; var link = ""; var date = ""
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        val tag = p.name.lowercase(Locale.ROOT)
                        if (tag == "item" || tag == "entry") { inside = true; title = ""; desc = ""; link = ""; date = "" }
                        else if (inside) when (tag) {
                            "title" -> title = cleanFeedText(p.nextText())
                            "description", "summary", "content" -> desc = cleanFeedText(p.nextText())
                            "link" -> link = (p.getAttributeValue(null, "href") ?: p.nextText()).trim()
                            "pubdate", "published", "updated" -> date = p.nextText().trim()
                        }
                    } else if (event == XmlPullParser.END_TAG && (p.name.equals("item", true) || p.name.equals("entry", true))) {
                        val parsed = try {
                            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                            f.parse(date)?.time ?: System.currentTimeMillis()
                        } catch (_: Exception) { System.currentTimeMillis() }
                        if (title.isNotBlank()) out.add(NewsItem(title, desc.take(5000), link, source.name, source.category, parsed))
                        inside = false
                    }
                    event = p.next()
                }
                return out
            }
        } finally { c.disconnect() }
    }

    fun saveArticleText(item: NewsItem, text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        val updated = readCache().map {
            if (it.link == item.link && item.link.isNotBlank()) it.copy(articleText = cleaned)
            else if (it.title == item.title && it.source == item.source) it.copy(articleText = cleaned)
            else it
        }
        writeCache(updated)
    }

    fun fetchFullArticle(item: NewsItem): String {
        if (item.link.isBlank() || !(item.link.startsWith("https://") || item.link.startsWith("http://"))) return ""
        val connection = Jsoup.connect(item.link)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .timeout(18000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
        val doc = connection.get()
        doc.select("script,style,nav,header,footer,aside,form,button,svg,iframe,noscript,.advert,.ads,.social,.share,.related,.comments,.breadcrumb").remove()
        val selectors = listOf(
            "[itemprop=articleBody]", "article", "main", ".article-body", ".article-content",
            ".story-body", ".story-content", ".entry-content", ".post-content",
            ".content-body", ".body-content", ".news-details", ".article__body"
        )
        var best = ""
        for (selector in selectors) {
            val candidate = doc.select(selector).maxByOrNull { it.text().length } ?: continue
            val paragraphs = candidate.select("p").map { it.text().trim() }.filter { it.length >= 35 }
            val text = if (paragraphs.isNotEmpty()) paragraphs.joinToString("\\n\\n") else candidate.text()
            if (text.length > best.length) best = text
        }
        if (best.length < 180) {
            val paragraphs = doc.select("body p").map { it.text().trim() }
                .filter { it.length >= 45 && !it.contains("اشترك") && !it.contains("تابعنا") }
            val fallback = paragraphs.joinToString("\\n\\n")
            if (fallback.length > best.length) best = fallback
        }
        return cleanFeedText(best).take(30000)
    }

    fun readCache(): List<NewsItem> {
        val a = JSONArray(cachePrefs.getString("items", "[]") ?: "[]")
        return (0 until a.length()).map { i -> a.getJSONObject(i).let {
            NewsItem(cleanFeedText(it.optString("title")), cleanFeedText(it.optString("description")), it.optString("link"), it.optString("source"), it.optString("category"), it.optLong("published"), cleanFeedText(it.optString("articleText")))
        }}.filter { System.currentTimeMillis() - it.published <= TimeUnit.DAYS.toMillis(3) }
    }
    private fun writeCache(items: List<NewsItem>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("title", it.title).put("description", it.description).put("link", it.link).put("source", it.source).put("category", it.category).put("published", it.published).put("articleText", it.articleText)) }
        cachePrefs.edit().putString("items", a.toString()).apply()
    }
}
