package sa.akhbaraltareeq

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText

class MainActivity : Activity() {
    private lateinit var repo: NewsRepository
    @Volatile private var offlineTts: OfflineTts? = null
    @Volatile private var voiceReady = false
    @Volatile private var voiceInitializing = false
    @Volatile private var stopRequested = false
    @Volatile private var audioTrack: AudioTrack? = null
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
        this.text = text; textSize = 13f; isAllCaps = false; setOnClickListener { action() }
    }

    private fun render(section: String) {
        currentSection = section
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(14, 8, 14, 8); setBackgroundColor(paper)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("أخبار الطريق", 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(if (dark) "فاتح" else "داكن") { dark = !dark; render(section) })
        root.addView(header)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("الرئيسية", "السعودية", "العالم", "المناطق", "الإعدادات").forEach {
            nav.addView(button(it) { render(it) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("تحديث الأخبار") { refreshNews() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("إيقاف الصوت") { stopSpeech() }, LinearLayout.LayoutParams(0, -2, 1f))
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
                orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8); setBackgroundColor(panel)
            }
            card.addView(label(item.title, 16f, true))
            if (item.description.isNotBlank()) card.addView(label(item.description, 14f))
            card.addView(label(item.source + " • " + item.category, 13f))
            card.addView(button("استمع إلى الخبر") {
                speakNews(item.title + ". " + item.description)
            })
            content.addView(card)
            val gap = Space(this); content.addView(gap, LinearLayout.LayoutParams(1, 8))
        }
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

    // Offline Arabic male voice using Piper Kareem and sherpa-onnx.
    // Load the large speech model only when the user first requests playback.
    private fun initializeOfflineVoice(onReady: (() -> Unit)? = null) {
        if (voiceInitializing || voiceReady) {
            if (voiceReady) onReady?.invoke()
            return
        }
        voiceInitializing = true
        runOnUiThread { if (::status.isInitialized) status.text = "جارٍ تجهيز الصوت عند أول استخدام..." }
        thread(name = "voice-init") {
            try {
                piperPhonemizeInitialize(applicationContext)
                val config = getOfflineTtsConfig(
                    modelDir = "kareem",
                    modelName = "ar_JO-kareem-medium.onnx",
                    numThreads = 1
                )
                offlineTts = OfflineTts(assetManager = assets, config = config)
                voiceReady = true
                voiceInitializing = false
                runOnUiThread {
                    if (::status.isInitialized) status.text = "الصوت العربي الرجالي جاهز • يعمل دون إنترنت"
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                voiceReady = false
                voiceInitializing = false
                runOnUiThread {
                    if (::status.isInitialized) status.text = "تعذر تجهيز الصوت: ${e.message ?: "خطأ غير معروف"}"
                    Toast.makeText(this, "تعذر تجهيز الصوت: ${e.message ?: "خطأ غير معروف"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun speakNews(text: String) {
        val engine = offlineTts
        if (!voiceReady || engine == null) {
            if (!voiceInitializing) initializeOfflineVoice { speakNews(text) }
            else Toast.makeText(this, "جارٍ تجهيز الصوت، انتظر قليلاً ثم أعد الضغط.", Toast.LENGTH_LONG).show()
            return
        }
        stopSpeech()
        stopRequested = false
        val spokenText = text.replace(Regex("\\s+"), " ").take(600)
        thread(name = "news-speech") {
            var track: AudioTrack? = null
            try {
                val language = engine.lang().ifBlank { "ar-jo" }
                val phonemes = piperPhonemizeText(spokenText, language)
                    ?: throw IllegalStateException("تعذر تحويل النص العربي إلى صوت")
                val codes = try {
                    Array(phonemes.numSentences) { i -> phonemes.getPhonemes(i) }
                } finally {
                    phonemes.close()
                }
                for (sentence in codes) {
                    if (stopRequested) break
                    val generated = engine.generateWithConfig(
                        text = "",
                        config = GenerationConfig(
                            sid = 0, speed = 0.94f,
                            phonemeCodepoints = arrayOf(sentence)
                        )
                    )
                    if (stopRequested || generated.samples.isEmpty()) continue
                    if (track == null) {
                        val sampleRate = generated.sampleRate
                        val minBuffer = AudioTrack.getMinBufferSize(
                            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
                        ).coerceAtLeast(4096)
                        track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(minBuffer * 2)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        audioTrack = track
                        track.play()
                    }
                    var offset = 0
                    while (offset < generated.samples.size && !stopRequested) {
                        val count = minOf(2048, generated.samples.size - offset)
                        val written = track.write(
                            generated.samples, offset, count, AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        offset += written
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "تعذر تشغيل الصوت: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                track?.release()
                if (audioTrack === track) audioTrack = null
            }
        }
    }

    private fun stopSpeech() {
        stopRequested = true
        try { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.stop() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopSpeech()
        try { offlineTts?.release() } catch (_: Exception) {}
        offlineTts = null
        super.onDestroy()
    }
}
