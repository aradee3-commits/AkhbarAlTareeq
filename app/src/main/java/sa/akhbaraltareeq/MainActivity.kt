package sa.akhbaraltareeq

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var dark = false
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val ink get() = if (dark) Color.WHITE else Color.rgb(28, 39, 52)
    private val paper get() = if (dark) Color.rgb(22, 29, 38) else Color.rgb(246, 248, 251)
    private val panel get() = if (dark) Color.rgb(36, 46, 59) else Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildScreen("الرئيسية")
    }

    private fun label(text: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            gravity = Gravity.RIGHT
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(8, 8, 8, 8)
        }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 17f
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun buildScreen(section: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(paper)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("أخبار الطريق", 28f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(if (dark) "الوضع الفاتح" else "الوضع الداكن") {
            dark = !dark
            buildScreen(section)
        })
        root.addView(header)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("الرئيسية", "السعودية", "العالم", "المناطق", "الإعدادات").forEach { item ->
            nav.addView(button(item) { buildScreen(item) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)

        status = label("نسخة تجريبية أولية • سيتم ربط مصادر الأخبار في المرحلة التالية", 14f)
        root.addView(status)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(label(section, 25f, true))
        content.addView(label("مرحباً بك في أخبار الطريق", 22f, true))
        content.addView(label(
            "هذه معاينة لواجهة التطبيق. الأخبار المعروضة لاحقاً ستُجلب من المصادر التي تختارها، مع حفظ آخر ثلاثة أيام للقراءة دون إنترنت.",
            18f
        ))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(panel)
        }
        card.addView(label("معاينة شكل الخبر", 19f, true))
        card.addView(label(
            "سيظهر هنا عنوان الخبر وملخصه واسم المصدر وتاريخ النشر. لا تعرض هذه النسخة أخباراً حقيقية بعد.",
            18f
        ))
        card.addView(button("استمع إلى رسالة ترحيبية") {
            val message = "أهلاً بك في أخبار الطريق. هذه نسخة تجريبية من التطبيق."
            if (tts?.isSpeaking == true) tts?.stop()
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "welcome")
            status.text = "جاري تشغيل القراءة الصوتية"
        })
        card.addView(button("إيقاف القراءة") {
            tts?.stop()
            status.text = "تم إيقاف القراءة"
        })
        content.addView(card)
        setContentView(root)
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale("ar", "SA"))
            runOnUiThread {
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    status.text = "محرك الصوت لا يوفر العربية حالياً؛ يمكن تثبيت بيانات صوت عربية على الشاشة."
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
