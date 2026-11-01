package sa.akhbaraltareeq

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var repo: NewsRepository
    private var tts: TextToSpeech? = null
    private var dark = false
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var currentSection = "الرئيسية"
    private val ink get() = if (dark) Color.WHITE else Color.rgb(28, 39, 52)
    private val paper get() = if (dark) Color.rgb(22, 29, 38) else Color.rgb(246, 248, 251)
    private val panel get() = if (dark) Color.rgb(36, 46, 59) else Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = NewsRepository(this)
        tts = TextToSpeech(this, this)
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

    private fun label(text: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(ink); gravity = Gravity.RIGHT
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_RTL; setPadding(10, 8, 10, 8)
        }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 16f; isAllCaps = false; setOnClickListener { action() }
    }

    private fun render(section: String) {
        currentSection = section
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(14, 8, 14, 8); setBackgroundColor(paper)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("أخبار الطريق", 27f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(if (dark) "فاتح" else "داكن") { dark = !dark; render(section) })
        root.addView(header)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("الرئيسية", "السعودية", "العالم", "المناطق", "الإعدادات").forEach {
            nav.addView(button(it) { render(it) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("تحديث الأخبار") { refreshNews() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("إيقاف الصوت") { tts?.stop() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        status = label("الأخبار المحفوظة متاحة دون إنترنت • الاحتفاظ بآخر 3 أيام", 14f)
        root.addView(status)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(2, 6, 2, 6) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (section == "الإعدادات") showSettings() else showNews(section)
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
        content.addView(label(section, 24f, true))
        val items = repo.readCache().filter {
            when (section) {
                "السعودية" -> it.category == "السعودية"
                "العالم" -> it.category == "العالم"
                "المناطق" -> it.category == "المناطق"
                else -> true
            }
        }
        if (items.isEmpty()) {
            content.addView(label("لا توجد أخبار محفوظة لهذا القسم حالياً. تحقق من اتصال الإنترنت ثم اضغط تحديث الأخبار.", 19f))
            return
        }
        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8); setBackgroundColor(panel)
            }
            card.addView(label(item.title, 19f, true))
            if (item.description.isNotBlank()) card.addView(label(item.description, 16f))
            card.addView(label(item.source + " • " + item.category, 13f))
            card.addView(button("استمع إلى الخبر") {
                tts?.speak(item.title + ". " + item.description, TextToSpeech.QUEUE_FLUSH, null, "news")
            })
            content.addView(card)
            val gap = Space(this); content.addView(gap, LinearLayout.LayoutParams(1, 8))
        }
    }

    private fun showSettings() {
        content.removeAllViews()
        content.addView(label("إعدادات الأخبار", 24f, true))
        content.addView(label("المناطق التي تريد متابعة أخبارها", 19f, true))
        listOf("المدينة المنورة", "تبوك", "مكة المكرمة", "الرياض", "جدة", "المنطقة الشرقية", "عسير").forEach { region ->
            val cb = CheckBox(this).apply {
                text = region; textSize = 17f; setTextColor(ink); isChecked = repo.selectedRegions().contains(region)
                setOnCheckedChangeListener { _, checked -> repo.setRegion(region, checked) }
            }
            content.addView(cb)
        }
        content.addView(label("مصادر الأخبار (RSS)", 19f, true))
        repo.sources().forEachIndexed { index, source ->
            val cb = CheckBox(this).apply {
                text = source.name; textSize = 16f; setTextColor(ink); isChecked = source.enabled
                setOnCheckedChangeListener { _, checked -> repo.toggleSource(index); status.text = "تم تحديث اختيار المصادر" }
            }
            content.addView(cb)
        }
        val name = EditText(this).apply { hint = "اسم المصدر الجديد"; textSize = 16f }
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
        content.addView(label("يتم التحديث دورياً عند توفر الشبكة، وقد يؤخر Android التنفيذ لتوفير البطارية. القراءة الصوتية تعتمد على الأصوات العربية المثبتة في الشاشة.", 15f))
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale("ar", "SA"))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                runOnUiThread { if (::status.isInitialized) status.text = "الصوت العربي غير متوفر في محرك الشاشة حالياً." }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); super.onDestroy()
    }
}
