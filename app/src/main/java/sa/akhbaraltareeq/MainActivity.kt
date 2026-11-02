package sa.akhbaraltareeq

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import java.text.SimpleDateFormat
import java.util.Date
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

import android.speech.tts.TextToSpeech
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var repo: NewsRepository
    @Volatile private var speechEngine: TextToSpeech? = null
    @Volatile private var voiceInitializing = false
    @Volatile private var voiceReady = false
    private var dark = false
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var currentSection = "الرئيسية"
    private var newsTextSize = 17f
    private val prefs by lazy { getSharedPreferences("akhbar_settings", MODE_PRIVATE) }
    private val ink get() = if (dark) Color.WHITE else Color.rgb(28, 39, 52)
    private val paper get() = if (dark) Color.rgb(22, 29, 38) else Color.rgb(246, 248, 251)
    private val panel get() = if (dark) Color.rgb(36, 46, 59) else Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = NewsRepository(this)
        newsTextSize = prefs.getFloat("news_text_size", 17f).coerceIn(13f, 26f)
        dark = prefs.getBoolean("dark_theme", false)
        scheduleRefresh()
        render("الرئيسية")
        refreshNews()
    }

    private fun scheduleRefresh() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<NewsRefreshWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("akhbar-periodic-refresh",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun rounded(color: Int, radius: Float = 16f): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun label(text: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(ink); gravity = Gravity.RIGHT
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_RTL; setPadding(10, 8, 10, 8)
        }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 13f; isAllCaps = false; setOnClickListener { action() }
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) Color.rgb(35, 75, 100) else Color.rgb(220, 237, 246))
        setTextColor(ink)
    }

    private fun render(section: String) {
        currentSection = section
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(14, 8, 14, 8); setBackgroundColor(paper)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("📰 أخبار الطريق", 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = button("☰ القائمة") { }
        header.addView(menuButton)
        menuButton.setOnClickListener { showMainMenu(menuButton, section) }
        root.addView(header)
        status = label("", 12f)
        status.visibility = View.GONE
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(2, 6, 2, 6) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (section == "الإعدادات") showSettings() else showNews(section)
    }

    private fun showMainMenu(anchor: View, section: String) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add("الرئيسية").setOnMenuItemClickListener { render("الرئيسية"); true }
        menu.menu.add("أخبار السعودية").setOnMenuItemClickListener { render("السعودية"); true }
        menu.menu.add("أخبار العالم").setOnMenuItemClickListener { render("العالم"); true }
        menu.menu.add("أخبار المناطق").setOnMenuItemClickListener { render("المناطق"); true }
        menu.menu.add("الإعدادات").setOnMenuItemClickListener { render("الإعدادات"); true }
        menu.menu.add("تحديث الأخبار").setOnMenuItemClickListener { refreshNews(); true }
        menu.menu.add("إيقاف الصوت").setOnMenuItemClickListener { stopSpeech(); true }
        menu.menu.add("تكبير الخط  +").setOnMenuItemClickListener { changeTextSize(1f); true }
        menu.menu.add("تصغير الخط  −").setOnMenuItemClickListener { changeTextSize(-1f); true }
        menu.menu.add(if (dark) "تفعيل الوضع الفاتح" else "تفعيل الوضع الداكن")
            .setOnMenuItemClickListener {
                dark = !dark
                prefs.edit().putBoolean("dark_theme", dark).apply()
                render(section)
                true
            }
        menu.show()
    }

    private fun showNewsMenu(anchor: View, item: NewsItem) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add("موجز الخبر").setOnMenuItemClickListener { showSummary(item); true }
        menu.menu.add("تفاصيل الخبر").setOnMenuItemClickListener { showDetails(item); true }
        menu.menu.add("استمع إلى الخبر").setOnMenuItemClickListener {
            speakNews(item.title + ". " + item.description)
            true
        }
        menu.show()
    }

    private fun changeTextSize(delta: Float) {
        newsTextSize = (newsTextSize + delta).coerceIn(13f, 26f)
        prefs.edit().putFloat("news_text_size", newsTextSize).apply()
        render(currentSection)
    }

    private fun refreshNews() {
        status.text = "جارٍ تحديث الأخبار..."
        thread {
            val count = repo.refresh()
            runOnUiThread {
                status.text = if (count > 0) "تم جلب $count خبراً • تُحفظ الأخبار لثلاثة أيام" else
                    "لم يصل تحديث جديد؛ نعرض الأخبار المحفوظة إن وجدت"
                showNews(currentSection)
            }
        }
    }

    private fun showNews(section: String) {
        content.removeAllViews()
        content.addView(label(section, 20f, true))
        val items = repo.readCache().filter {
            when (section) {
                "السعودية" -> it.category == "السعودية"
                "العالم" -> it.category == "العالم"
                "المناطق" -> it.category == "المناطق"
                else -> true
            }
        }
        if (items.isEmpty()) {
            content.addView(label("لا توجد أخبار محفوظة لهذا القسم حالياً. تحقق من اتصال الإنترنت ثم اضغط تحديث الأخبار.", 15f))
            return
        }
        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8)
                background = rounded(panel, 18f); elevation = 3f
            }
            card.addView(label(item.title, newsTextSize, true))
            if (item.description.isNotBlank()) card.addView(label(item.description.take(160) + if (item.description.length > 160) "…" else "", newsTextSize - 2f))
            card.addView(label("◉ " + item.source + " • " + item.category, 12f))
            val tools = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            val microphone = button("🎙️") {
                speakNews(item.title + ". " + item.description)
            }.apply {
                textSize = 19f
                minWidth = 0
                minimumWidth = 0
                setPadding(8, 2, 8, 2)
                contentDescription = "استمع إلى الخبر"
            }
            tools.addView(microphone, LinearLayout.LayoutParams(-2, -2))
            card.addView(tools)
            content.addView(card)
            val gap = Space(this); content.addView(gap, LinearLayout.LayoutParams(1, 8))
        }
    }

    private fun showSummary(item: NewsItem) {
        val summary = item.description.ifBlank { "لا يتوفر ملخص من المصدر لهذا الخبر." }
        AlertDialog.Builder(this).setTitle("▤ موجز الخبر")
            .setMessage(item.title + "\\n\\n" + summary)
            .setPositiveButton("إغلاق", null)
            .setNeutralButton("استمع للموجز") { _, _ -> speakNews(item.title + ". " + summary) }.show()
    }

    private fun showDetails(item: NewsItem) {
        val details = item.title + "\\n\\n" + item.description + "\\n\\nالمصدر: " + item.source + "\\nالقسم: " + item.category +
            "\\nتاريخ النشر: " + SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("ar")).format(Date(item.published))
        val dialog = AlertDialog.Builder(this).setTitle("☷ تفاصيل الخبر").setMessage(details)
            .setPositiveButton("إغلاق", null)
        if (item.link.isNotBlank()) dialog.setNeutralButton("فتح الخبر الأصلي") { _, _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link))) } catch (_: Exception) {}
        }
        dialog.show()
    }

    private fun showSettings() {
        content.removeAllViews()
        content.addView(label("إعدادات الأخبار", 20f, true))
        content.addView(label("المناطق التي تريد متابعة أخبارها", 16f, true))
        listOf("المدينة المنورة", "تبوك", "مكة المكرمة", "الرياض", "جدة", "المنطقة الشرقية", "عسير").forEach { region ->
            val cb = CheckBox(this).apply {
                text = region; textSize = 15f; setTextColor(ink); isChecked = repo.selectedRegions().contains(region)
                setOnCheckedChangeListener { _, checked -> repo.setRegion(region, checked) }
            }
            content.addView(cb)
        }
        content.addView(label("مصادر الأخبار (RSS)", 16f, true))
        repo.sources().forEachIndexed { index, source ->
            val cb = CheckBox(this).apply {
                text = source.name; textSize = 14f; setTextColor(ink); isChecked = source.enabled
                setOnCheckedChangeListener { _, checked -> repo.toggleSource(index); status.text = "تم تحديث اختيار المصادر" }
            }
            content.addView(cb)
        }
        val name = EditText(this).apply { hint = "اسم المصدر الجديد"; textSize = 13f }
        val url = EditText(this).apply { hint = "رابط RSS يبدأ بـ https://"; textSize = 14f }
        content.addView(name); content.addView(url)
        content.addView(button("إضافة مصدر RSS") {
            try {
                repo.addSource(name.text.toString(), url.text.toString())
                Toast.makeText(this, "تمت إضافة المصدر", Toast.LENGTH_SHORT).show()
                render("الإعدادات")
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "تعذر إضافة المصدر", Toast.LENGTH_LONG).show()
            }
        })
        content.addView(label("يتم التحديث دورياً عند توفر الشبكة، وقد يؤخر Android التنفيذ لتوفير البطارية. الصوت عربي رجالي يعمل محلياً دون إنترنت.", 15f))
    }

    // Use Android's installed TTS engine to avoid native offline-model crashes.
    private fun initializeOfflineVoice(onReady: (() -> Unit)? = null) {
        if (voiceReady && speechEngine != null) {
            onReady?.invoke()
            return
        }
        if (voiceInitializing) return
        voiceInitializing = true
        status.text = "جارٍ تجهيز الصوت العربي..."
        try {
            speechEngine = TextToSpeech(applicationContext) { result ->
                voiceInitializing = false
                val engine = speechEngine
                if (result == TextToSpeech.SUCCESS && engine != null) {
                    val arabic = engine.setLanguage(Locale("ar", "SA"))
                    if (arabic == TextToSpeech.LANG_MISSING_DATA ||
                        arabic == TextToSpeech.LANG_NOT_SUPPORTED) {
                        voiceReady = false
                        status.text = "الصوت العربي غير مثبت في الجهاز. ثبّت صوتاً عربياً من إعدادات تحويل النص إلى كلام."
                        Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                    } else {
                        engine.setSpeechRate(0.94f)
                        voiceReady = true
                        status.text = "الصوت العربي جاهز من محرك أندرويد"
                        onReady?.invoke()
                    }
                } else {
                    voiceReady = false
                    status.text = "تعذر تشغيل محرك النطق في الجهاز"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            voiceInitializing = false
            voiceReady = false
            status.text = "تعذر تهيئة الصوت: ${e.message ?: "خطأ"}"
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun speakNews(text: String) {
        val engine = speechEngine
        if (!voiceReady || engine == null) {
            if (!voiceInitializing) initializeOfflineVoice { speakNews(text) }
            else Toast.makeText(this, "جارٍ تجهيز الصوت، انتظر لحظة.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val result = engine.speak(
                text.replace(Regex("\\s+"), " ").take(600),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "akhbar-news"
            )
            if (result == TextToSpeech.ERROR) {
                status.text = "تعذر بدء قراءة الخبر"
                Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            } else {
                status.text = "يتم الآن قراءة الخبر"
            }
        } catch (e: Exception) {
            status.text = "تعذر تشغيل الصوت: ${e.message ?: "خطأ"}"
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopSpeech() {
        try { speechEngine?.stop() } catch (_: Exception) {}
        status.text = "تم إيقاف الصوت"
    }

    override fun onDestroy() {
        stopSpeech()
        try { speechEngine?.shutdown() } catch (_: Exception) {}
        speechEngine = null
        super.onDestroy()
    }
}
