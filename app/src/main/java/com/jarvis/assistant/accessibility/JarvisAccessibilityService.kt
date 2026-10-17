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

    suspend fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(GestureDescription.StrokeDescription(p, 0, 60))
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

    /** Ön plandaki uygulamanın paket adı (Jarvis değilse). */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

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
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        /** Servis bağlanınca çalışacak kod (uyandırma servisini başlatmak için). */
        var onConnectedHook: ((JarvisAccessibilityService) -> Unit)? = null
    }
}
