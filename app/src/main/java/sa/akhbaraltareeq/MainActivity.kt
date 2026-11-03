package sa.akhbaraltareeq

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
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
import android.speech.tts.UtteranceProgressListener
import android.view.MotionEvent
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
    private var drivingItems: List<NewsItem> = emptyList()
    private var drivingIndex = 0
    private var drivingPaused = false
    private var drivingMode = false
    private val prefs by lazy { getSharedPreferences("akhbar_settings", MODE_PRIVATE) }
    private val ink get() = if (dark) Color.WHITE else Color.rgb(28, 39, 52)
    private val paper get() = if (dark) Color.rgb(22, 29, 38) else Color.rgb(246, 248, 251)
    private val panel get() = if (dark) Color.argb(158, 20, 31, 43) else Color.argb(150, 246, 249, 252)

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
        background = rounded(if (dark) Color.rgb(35, 75, 100) else Color.rgb(220, 237, 246), 14f)
        setTextColor(ink)
        stateListAnimator = null
        elevation = 2f
    }

    private fun render(section: String) {
        currentSection = section
        val screen = FrameLayout(this)
        val backgroundImage = ImageView(this).apply {
            setImageResource(R.drawable.world_background)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
        }
        screen.addView(backgroundImage, FrameLayout.LayoutParams(-1, -1))
        val shade = View(this).apply { setBackgroundColor(Color.argb(92, 0, 0, 0)) }
        screen.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(14, 8, 14, 8); setBackgroundColor(Color.TRANSPARENT)
        }
        screen.addView(root, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("📰 أخبار الطريق", 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = button("☰ القائمة") { }
        menuButton.background = rounded(if (dark) Color.rgb(35, 75, 100) else Color.rgb(220, 237, 246), 18f)
        header.addView(menuButton)
        menuButton.setOnClickListener { showMainMenu(menuButton, section) }
        root.addView(header)
        status = label("", 12f)
        status.visibility = View.GONE
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(2, 6, 2, 6) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screen)
        if (section == "الإعدادات") showSettings() else showNews(section)
    }

    private fun showMainMenu(anchor: View, section: String) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add("الرئيسية").setOnMenuItemClickListener { render("الرئيسية"); true }
        menu.menu.add("أخبار السعودية").setOnMenuItemClickListener { render("السعودية"); true }
        menu.menu.add("أخبار العالم").setOnMenuItemClickListener { render("العالم"); true }
        menu.menu.add("أخبار المناطق").setOnMenuItemClickListener { render("المناطق"); true }
        menu.menu.add("الإعدادات").setOnMenuItemClickListener { render("الإعدادات"); true }
        menu.menu.add("🚗 وضع القيادة - قراءة تلقائية").setOnMenuItemClickListener { startDrivingMode(); true }
        menu.menu.add("تحديث الأخبار").setOnMenuItemClickListener { refreshNews(); true }
        menu.menu.add("إيقاف الصوت").setOnMenuItemClickListener { stopSpeech(); true }
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


    private fun startDrivingMode() {
        drivingItems = repo.readCache().filter {
            when (currentSection) {
                "السعودية" -> it.category == "السعودية"
                "العالم" -> it.category == "العالم"
                "المناطق" -> it.category == "المناطق" && repo.selectedRegions().any { region -> it.source.contains(region) }
                else -> true
            }
        }
        if (drivingItems.isEmpty()) {
            Toast.makeText(this, "لا توجد أخبار لقراءتها حالياً", Toast.LENGTH_LONG).show()
            return
        }
        drivingIndex = 0
        drivingPaused = false
        drivingMode = true
        showDrivingNews()
        initializeOfflineVoice { if (drivingMode && !drivingPaused) speakDrivingItem() }
    }


    private fun storyText(item: NewsItem): String {
        var body = item.description.trim().replace(Regex("\\s+"), " ")
        val title = item.title.trim().replace(Regex("\\s+"), " ")
        if (body.isBlank() || title.isBlank()) return body
        if (body.equals(title, ignoreCase = true)) return ""
        if (body.startsWith(title, ignoreCase = true)) {
            body = body.substring(title.length).trimStart(' ', '-', ':', '،', '.', '؛', '|')
        }
        return body.trim()
    }

    private fun showDrivingNews() {
        if (!drivingMode || drivingItems.isEmpty()) return
        val item = drivingItems[drivingIndex]
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(22, 18, 22, 18)
            setBackgroundColor(if (dark) Color.rgb(12, 20, 29) else Color.rgb(239, 245, 249))
        }
        root.addView(TextView(this).apply {
            text = "وضع القيادة  •  ${drivingIndex + 1} / ${drivingItems.size}"
            textSize = 20f; setTextColor(ink); gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = item.title; textSize = 40f; setTextColor(ink)
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTypeface(null, Typeface.BOLD); textDirection = View.TEXT_DIRECTION_RTL
            setPadding(8, 20, 8, 16)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val fullBody = storyText(item).ifBlank {
            "المصدر لم يرسل تفاصيل إضافية لهذا الخبر. يمكنك فتح الخبر الأصلي من زر التفاصيل."
        }
        val bodyScroll = ScrollView(this)
        bodyScroll.addView(TextView(this).apply {
            text = fullBody; textSize = 27f; setTextColor(ink)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; textAlignment = View.TEXT_ALIGNMENT_CENTER
            textDirection = View.TEXT_DIRECTION_RTL; setPadding(10, 12, 10, 20)
            setLineSpacing(5f, 1.12f)
        })
        root.addView(bodyScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = item.source; textSize = 17f; setTextColor(ink); gravity = Gravity.CENTER
        })
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("السابق") { moveDriving(-1) })
        controls.addView(button(if (drivingPaused) "▶ استئناف" else "Ⅱ إيقاف") {
            if (drivingPaused) { drivingPaused = false; speakDrivingItem() }
            else { drivingPaused = true; stopSpeech() }
            showDrivingNews()
        })
        controls.addView(button("التالي") { moveDriving(1) })
        controls.addView(button("خروج") {
            drivingMode = false; drivingPaused = true; stopSpeech(); render(currentSection)
        })
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        var downY = 0f
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downY = event.y; true }
                MotionEvent.ACTION_UP -> {
                    val delta = downY - event.y
                    if (kotlin.math.abs(delta) > 70) moveDriving(if (delta > 0) 1 else -1)
                    true
                }
                else -> true
            }
        }
        setContentView(root)
    }

    private fun moveDriving(step: Int) {
        if (!drivingMode || drivingItems.isEmpty()) return
        drivingIndex = (drivingIndex + step).coerceIn(0, drivingItems.lastIndex)
        drivingPaused = false
        stopSpeech()
        showDrivingNews()
        speakDrivingItem()
    }

    private fun speakDrivingItem() {
        if (!drivingMode || drivingPaused || drivingItems.isEmpty()) return
        val item = drivingItems[drivingIndex]
        val engine = speechEngine
        if (!voiceReady || engine == null) {
            initializeOfflineVoice { speakDrivingItem() }
            return
        }
        engine.speak((item.title + ". " + storyText(item)).replace(Regex("\\s+"), " ").take(1800),
            TextToSpeech.QUEUE_FLUSH, null, "driving-${drivingIndex}")
        status.text = "قراءة أخبار القيادة تلقائياً"
    }

    private fun showNews(section: String) {
        content.removeAllViews()
        content.addView(label(section, 20f, true))
        val items = repo.readCache().filter {
            when (section) {
                "السعودية" -> it.category == "السعودية"
                "العالم" -> it.category == "العالم"
                "المناطق" -> it.category == "المناطق" && repo.selectedRegions().any { region -> it.source.contains(region) }
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
                background = rounded(panel, 22f); elevation = 5f
                clipToOutline = true
            }
            card.addView(label(item.title, newsTextSize, true))
            val body = storyText(item)
            if (body.isNotBlank()) {
                val fullText = label(body, newsTextSize - 1f)
                fullText.setLineSpacing(3f, 1.08f)
                card.addView(fullText)
            } else {
                card.addView(label("المصدر لم يرسل تفاصيل إضافية لهذا الخبر.", newsTextSize - 1f))
            }
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
            val enlarge = button("+") { changeTextSize(1f) }.apply {
                textSize = 17f; minWidth = 0; minimumWidth = 0
                setPadding(12, 0, 12, 0); contentDescription = "تكبير الخط"
            }
            tools.addView(enlarge, LinearLayout.LayoutParams(-2, -2))
            val reduce = button("−") { changeTextSize(-1f) }.apply {
                textSize = 17f; minWidth = 0; minimumWidth = 0
                setPadding(12, 0, 12, 0); contentDescription = "تصغير الخط"
            }
            tools.addView(reduce, LinearLayout.LayoutParams(-2, -2))
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
        content.addView(label("اختر المناطق ثم اضغط حفظ الاختيارات لتحديث الأخبار وفقاً لها.", 13f))
        listOf("المدينة المنورة", "تبوك", "مكة المكرمة", "الرياض", "جدة", "المنطقة الشرقية", "عسير").forEach { region ->
            val cb = CheckBox(this).apply {
                text = region; textSize = 15f; setTextColor(ink); isChecked = repo.selectedRegions().contains(region)
                setOnCheckedChangeListener { _, checked -> repo.setRegion(region, checked) }
            }
            content.addView(cb)
        }
        content.addView(button("✓ حفظ اختيارات المناطق") {
            Toast.makeText(this, "تم حفظ المناطق المختارة، جارٍ تحديث أخبارها", Toast.LENGTH_SHORT).show()
            refreshNews()
        })
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

                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onError(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                if (utteranceId?.startsWith("driving-") == true) runOnUiThread {
                                    if (drivingMode && !drivingPaused && drivingIndex < drivingItems.lastIndex) {
                                        drivingIndex++
                                        showDrivingNews()
                                        speakDrivingItem()
                                    } else if (drivingMode && drivingIndex == drivingItems.lastIndex) {
                                        drivingPaused = true
                                        showDrivingNews()
                                        Toast.makeText(this@MainActivity, "انتهت الأخبار المتاحة", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        })
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
