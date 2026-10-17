package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jarvis.assistant.core.Launcher
import com.jarvis.assistant.tools.normalizeName
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Erişilebilirlik servisi: Jarvis'in ekranı "görmesini" ve dokunmasını sağlar.
 * Ayrıca arka plandan uygulama açmak için de kullanılır.
 */
class JarvisAccessibilityService : AccessibilityService() {

    /** Son ekrani_oku sonucundaki öğeler; dokun(indeks) bunları kullanır. */
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Launcher.accessibilityContext = this
        onConnectedHook?.invoke(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideCurtain()
        if (instance === this) instance = null
        Launcher.accessibilityContext = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Ekranı okuma
    // ------------------------------------------------------------------

    private fun roots(): List<Pair<String, AccessibilityNodeInfo>> {
        val list = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        val ws = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .sortedByDescending { it.layer }
        for (w in ws) {
            val r = w.root ?: continue
            if (r.packageName == packageName) continue
            list += (w.title?.toString() ?: r.packageName?.toString() ?: "pencere") to r
        }
        if (list.isEmpty()) rootInActiveWindow?.let { list += (it.packageName?.toString() ?: "ekran") to it }
        return list
    }

    /** Görünen öğeleri numaralı, sade metin olarak döndürür. */
    fun readScreen(maxItems: Int = 120): String {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        for ((title, root) in roots()) {
            sb.append("== $title ==\n")
            collect(root, nodes, sb, maxItems)
        }
        lastNodes = nodes
        if (nodes.isEmpty()) return "Ekranda okunabilir öğe yok (ekran kilitli olabilir)."
        return sb.toString().trim()
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, sb: StringBuilder, max: Int, depth: Int = 0) {
        if (n == null || out.size >= max || depth > 60) return
        if (!n.isVisibleToUser) return
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val hint = n.hintText?.toString()?.trim().orEmpty()
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || n.isEditable || (n.isClickable && hint.isNotEmpty())
        if (interesting) {
            out += n
            val kind = when {
                n.isEditable -> "Yazı alanı"
                n.isCheckable -> if (n.isChecked) "Seçili kutu" else "Kutu"
                n.className?.contains("Button") == true -> "Buton"
                n.isClickable -> "Tıklanır"
                else -> "Metin"
            }
            val label = listOf(text, desc.takeIf { it != text }, hint.takeIf { n.isEditable && it.isNotEmpty() }?.let { "ipucu: $it" })
                .filter { !it.isNullOrEmpty() }.joinToString(" | ")
            sb.append("[${out.size - 1}] $kind: ${label.take(120)}")
            if (n.isScrollable) sb.append(" (kaydırılabilir)")
            sb.append('\n')
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, sb, max, depth + 1)
    }

    // ------------------------------------------------------------------
    // Etkileşim
    // ------------------------------------------------------------------

    /** Metne göre öğe bulur: önce tam eşleşme, sonra içeren. */
    fun findByText(query: String): AccessibilityNodeInfo? {
        val q = normalizeName(query)
        if (q.isEmpty()) return null
        val all = mutableListOf<AccessibilityNodeInfo>()
        for ((_, root) in roots()) gatherAll(root, all)
        fun label(n: AccessibilityNodeInfo) = normalizeName((n.text ?: n.contentDescription ?: "").toString())
        return all.firstOrNull { label(it) == q }
            ?: all.firstOrNull { label(it).startsWith(q) }
            ?: all.firstOrNull { label(it).contains(q) }
    }

    private fun gatherAll(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 60) return
        if (n.isVisibleToUser && (n.text != null || n.contentDescription != null)) out += n
        for (i in 0 until n.childCount) gatherAll(n.getChild(i), out, depth + 1)
    }

    fun nodeAt(index: Int): AccessibilityNodeInfo? = lastNodes.getOrNull(index)

    /** Öğeye tıklar: kendisi tıklanamıyorsa tıklanabilir ebeveynini dener, olmazsa dokunma hareketi yapar. */
    suspend fun click(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = TAP_MS): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(GestureDescription.StrokeDescription(p, 0, durationMs.coerceIn(1, 400)))
    }

    /** Öğenin ekrandaki orta noktası. */
    fun centerOf(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return null
        return r.exactCenterX() to r.exactCenterY()
    }

    /** Ekran yüzdesini (0-100) piksele çevirir. */
    fun percentToPx(xPct: Double, yPct: Double): Pair<Float, Float> {
        val dm = resources.displayMetrics
        return (dm.widthPixels * xPct.coerceIn(0.0, 100.0) / 100).toFloat() to (dm.heightPixels * yPct.coerceIn(0.0, 100.0) / 100).toFloat()
    }

    /**
     * Telefonun "basılı tutma" süresi (Erişilebilirlik > Dokunma ve basılı tutma gecikmesi ayarına göre değişir).
     * Basılı tutma hareketi bundan biraz uzun yapılır ki her uygulamada algılansın.
     */
    fun autoLongPressMs(): Long = (android.view.ViewConfiguration.getLongPressTimeout() * 1.6).toLong().coerceIn(600, 3000)

    /** Öğeye basılı tutar: önce uygulamanın kendi uzun basma işlemi, olmazsa parmak hareketi. */
    suspend fun longPress(node: AccessibilityNodeInfo, durationMs: Long?): Boolean {
        if (durationMs == null) {
            var cur: AccessibilityNodeInfo? = node
            var depth = 0
            while (cur != null && depth < 6) {
                if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
                cur = cur.parent
                depth++
            }
        }
        val (x, y) = centerOf(node) ?: return false
        return longPressAt(x, y, durationMs)
    }

    suspend fun longPressAt(x: Float, y: Float, durationMs: Long?): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(GestureDescription.StrokeDescription(p, 0, (durationMs ?: autoLongPressMs()).coerceIn(300, 10_000)))
    }

    /**
     * Sürükler. holdFirst: önce basılı tutup "tutma" moduna geçer (simge/öğe taşıma için);
     * false ise doğrudan çeker (kaydırıcı, çizim). Süre verilmezse mesafeye göre kendisi ayarlar.
     */
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, holdFirst: Boolean, moveMs: Long?): Boolean {
        val density = resources.displayMetrics.density
        val distDp = kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()) / density
        // Yaklaşık insan hızı: kısa mesafe hızlı, uzun mesafe daha uzun sürer.
        val move = (moveMs ?: (250 + distDp * 2.2).toLong()).coerceIn(150, 5000)
        val line = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        if (!holdFirst) return gesture(GestureDescription.StrokeDescription(line, 0, move))
        // 1) Basılı tut  2) taşı  3) bırakmadan önce kısa bekle (hedef "yakalasın").
        val hold = GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1) }, 0, autoLongPressMs(), true)
        if (!gesture(hold)) return false
        val moving = hold.continueStroke(line, 0, move, true)
        if (!gesture(moving)) return false
        val drop = moving.continueStroke(Path().apply { moveTo(x2, y2) }, 0, 350, false)
        return gesture(drop)
    }

    /** Ekranın kaba bir "parmak izi": dokunmadan sonra bir şey değişti mi anlamak için. */
    fun screenSignature(): Int {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || sb.length > 4000) return
            if (n.isVisibleToUser) {
                n.text?.let { sb.append(it).append('|') }
                if (n.isChecked) sb.append('✓')
                if (n.isFocused) sb.append('f')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        roots().forEach { walk(it.second, 0) }
        return sb.toString().hashCode()
    }

    /**
     * Ekran görüntüsünü alıp küçültülmüş JPEG olarak döndürür (yapay zekanın ekrana "bakması" için).
     * Perde açıksa görüntüye girmesin diye kısa süre kaldırılır.
     */
    suspend fun screenshotJpeg(maxSide: Int = 1280): ByteArray? {
        val hadCurtain = curtain != null
        if (hadCurtain) { hideCurtain(); kotlinx.coroutines.delay(250) }
        try {
            val bmp = suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val hw = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            result.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(soft)
                        }
                        override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    },
                )
            } ?: return null
            return com.jarvis.assistant.ai.Images.toJpeg(bmp, maxSide)
        } finally {
            if (hadCurtain) showCurtain()
        }
    }

    /** Ekranı kaydırır. yon: asagi, yukari, sol, sag (içeriğin gideceği yön değil, parmağın tersi). */
    suspend fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (direction) {
            "yukari" -> { p.moveTo(w / 2, h * 0.3f); p.lineTo(w / 2, h * 0.75f) }
            "sol" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.85f, h / 2) }
            "sag" -> { p.moveTo(w * 0.85f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            else -> { p.moveTo(w / 2, h * 0.75f); p.lineTo(w / 2, h * 0.3f) }
        }
        return gesture(GestureDescription.StrokeDescription(p, 0, 350))
    }

    private suspend fun gesture(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    /** Yazı alanına metin yazar. Hedef verilmezse odaktaki ya da ilk yazı alanı kullanılır. */
    fun typeText(text: String, target: AccessibilityNodeInfo?): Boolean {
        val node = target?.takeIf { it.isEditable }
            ?: roots().firstNotNullOfOrNull { it.second.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }?.takeIf { it.isEditable }
            ?: roots().firstNotNullOfOrNull { firstEditable(it.second) }
            ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun firstEditable(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 60) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstEditable(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    // ------------------------------------------------------------------
    // Perde: ekranda iş yaparken üstte "Jarvis çalışıyor" paneli
    // ------------------------------------------------------------------

    private var curtain: android.view.View? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Ekranı Jarvis'in paneliyle örter. Erişilebilirlik katmanı dokunuşları geçirir
     * (FLAG_NOT_TOUCHABLE), bu yüzden alttaki uygulamada dokunma ve yazma çalışmaya devam eder.
     */
    fun showCurtain(message: String = "Jarvis çalışıyor…") = mainHandler.post {
        if (curtain != null) {
            (curtain?.findViewWithTag<android.widget.TextView>("msg"))?.text = message
            return@post
        }
        val wm = getSystemService(android.view.WindowManager::class.java)
        val cyan = 0xFF00E5FF.toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xF2000000.toInt())
            addView(android.widget.ProgressBar(this@JarvisAccessibilityService).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(cyan)
            })
            addView(android.widget.TextView(this@JarvisAccessibilityService).apply {
                tag = "msg"
                text = message
                setTextColor(cyan)
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                setPadding(48, 36, 48, 0)
            })
        }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        runCatching {
            wm.addView(box, lp)
            curtain = box
        }
    }

    fun hideCurtain() = mainHandler.post {
        curtain?.let { v -> runCatching { getSystemService(android.view.WindowManager::class.java).removeView(v) } }
        curtain = null
    }

    /** Ön plandaki uygulamanın paket adı (Jarvis değilse). */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * Uzun mesajın sonundaki "Devamını okuyun / Read more" bağlantısına basar.
     * Önce metindeki tıklanabilir bağlantıyı doğrudan çalıştırır, olmazsa son satıra dokunur.
     */
    suspend fun expandReadMore(node: AccessibilityNodeInfo): Boolean {
        val text = node.text ?: return false
        val str = text.toString()
        val marker = READ_MORE.firstOrNull { str.contains(it, ignoreCase = true) } ?: return false
        val spanned = text as? android.text.Spanned
        val span = spanned?.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)
            ?.firstOrNull { sp ->
                val start = spanned.getSpanStart(sp)
                val end = spanned.getSpanEnd(sp)
                start >= 0 && end <= str.length && str.substring(start, end).contains(marker.take(6), ignoreCase = true)
            }
        if (span != null) {
            val ok = runCatching { span.onClick(android.view.View(this)) }.isSuccess
            if (ok) return true
        }
        // Yedek: bağlantı son satırın sonundadır; o bölgeye dokun.
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val line = 20f * resources.displayMetrics.density
        return tap(r.left + r.width() * 0.45f, r.bottom - line * 0.6f)
    }

    /** Verilen paketin penceresinde, kimliği (viewId) bu olan görünür öğeler; ekrandaki sırasıyla. */
    fun nodesById(viewId: String): List<AccessibilityNodeInfo> =
        roots().flatMap { it.second.findAccessibilityNodeInfosByViewId(viewId) }.filter { it.isVisibleToUser }

    /** Ekrandaki ilk kaydırılabilir listeyi geriye (yukarı) kaydırır. Kaydırdıysa true. */
    fun scrollListBackward(pkg: String): Boolean {
        val root = roots().firstOrNull { it.second.packageName?.toString() == pkg }?.second ?: return false
        val list = firstScrollable(root) ?: return false
        return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun firstScrollable(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstScrollable(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    /** Görünen pencerelerde kimliği (viewId) verilen ilk öğe. */
    fun findById(viewId: String): AccessibilityNodeInfo? =
        roots().firstNotNullOfOrNull { it.second.findAccessibilityNodeInfosByViewId(viewId).firstOrNull { n -> n.isVisibleToUser } }

    companion object {
        /** Kesik mesajların sonundaki "devamını oku" yazıları. */
        val READ_MORE = listOf("Devamını okuyun", "Devamını oku", "Read more")

        /** Normal dokunma süresi (ms). */
        const val TAP_MS = 60L

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        /** Servis bağlanınca çalışacak kod (uyandırma servisini başlatmak için). */
        var onConnectedHook: ((JarvisAccessibilityService) -> Unit)? = null
    }
}
