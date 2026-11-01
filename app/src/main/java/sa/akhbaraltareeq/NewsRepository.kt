package sa.akhbaraltareeq

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class NewsItem(val title: String, val description: String, val link: String, val source: String, val category: String, val published: Long)
data class FeedSource(val name: String, val category: String, val url: String, val enabled: Boolean = true)

class NewsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("akhbar_settings", Context.MODE_PRIVATE)
    private val cachePrefs = context.getSharedPreferences("akhbar_cache", Context.MODE_PRIVATE)
    private fun googleNews(q: String) = "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=ar&gl=SA&ceid=SA:ar"

    fun sources(): List<FeedSource> {
        val saved = prefs.getString("sources", null)
        if (saved != null) {
            val a = JSONArray(saved)
            return (0 until a.length()).map { i -> a.getJSONObject(i).let { FeedSource(it.optString("name"), it.optString("category"), it.optString("url"), it.optBoolean("enabled", true)) } }
        }
        return listOf(
            FeedSource("أخبار السعودية", "السعودية", googleNews("السعودية")),
            FeedSource("أخبار العالم", "العالم", googleNews("أخبار العالم")),
            FeedSource("المدينة المنورة", "المناطق", googleNews("المدينة المنورة")),
            FeedSource("تبوك", "المناطق", googleNews("تبوك"))
        )
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
        val merged = (incoming + readCache()).distinctBy { if (it.link.isNotBlank()) it.link else it.title }
            .filter { System.currentTimeMillis() - it.published <= TimeUnit.DAYS.toMillis(3) }
            .sortedByDescending { it.published }.take(250)
        writeCache(merged)
        return incoming.size
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
                            "title" -> title = p.nextText().trim()
                            "description", "summary", "content" -> desc = p.nextText().replace(Regex("<[^>]*>"), " ").trim()
                            "link" -> link = (p.getAttributeValue(null, "href") ?: p.nextText()).trim()
                            "pubdate", "published", "updated" -> date = p.nextText().trim()
                        }
                    } else if (event == XmlPullParser.END_TAG && (p.name.equals("item", true) || p.name.equals("entry", true))) {
                        val parsed = try {
                            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                            f.parse(date)?.time ?: System.currentTimeMillis()
                        } catch (_: Exception) { System.currentTimeMillis() }
                        if (title.isNotBlank()) out.add(NewsItem(title, desc.take(450), link, source.name, source.category, parsed))
                        inside = false
                    }
                    event = p.next()
                }
                return out
            }
        } finally { c.disconnect() }
    }
    fun readCache(): List<NewsItem> {
        val a = JSONArray(cachePrefs.getString("items", "[]") ?: "[]")
        return (0 until a.length()).map { i -> a.getJSONObject(i).let {
            NewsItem(it.optString("title"), it.optString("description"), it.optString("link"), it.optString("source"), it.optString("category"), it.optLong("published"))
        }}.filter { System.currentTimeMillis() - it.published <= TimeUnit.DAYS.toMillis(3) }
    }
    private fun writeCache(items: List<NewsItem>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("title", it.title).put("description", it.description).put("link", it.link).put("source", it.source).put("category", it.category).put("published", it.published)) }
        cachePrefs.edit().putString("items", a.toString()).apply()
    }
}
