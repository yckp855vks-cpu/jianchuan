package com.jianchuan.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 自动跳过开屏广告（李跳跳类）。
 *
 * ## 为什么规则收得这么紧（v1.13.7）
 *
 * v1.13.6 之前的写法是：递归扫整棵窗口树，只要文本 `contains` 到
 * 「跳过 / 关闭 / 我知道了 / 立即体验 / 稍后再说」就点一下。结果真实误伤：
 * 用户做**返回手势**、下拉通知栏、或进**系统设置**时，系统界面里的「关闭」
 * 被当成广告点了 —— 甚至点掉了自己的无障碍开关，于是功能"自己关了"。
 *
 * ## v1.13.9 的关键修复：为什么酷安开屏跳不掉
 *
 * 之前只听 TYPE_WINDOW_STATE_CHANGED。但开屏广告的「跳过」按钮是广告 SDK
 * **异步渲染**的：App 切前台那一瞬间，节点树里还没有这个按钮，等它出现
 * 只会再发一条 TYPE_WINDOW_CONTENT_CHANGED —— 我们不收，于是永远错过。
 * 三处一起改：
 * 1. 配置里加 `typeWindowContentChanged` + `flagIncludeNotImportantViews`
 *    （广告按钮常被标成 importantForAccessibility=no，不放在树里就能看见）
 * 2. 代码接收 content changed 事件，300ms 节流扫描（这类事件非常密集）
 * 3. 窗口期从 5 秒放宽到 10 秒（广告加载 + 倒计时常见 3~5 秒）
 * 4. 扫不到时遍历所有窗口（广告可能在独立 window，主窗口里没有）
 *
 * ## 真正让酷安跳不掉的原因（社区调研后确认）
 *
 * 翻 GKD 官方订阅仓库，酷安的开屏规则只有一行：
 *     '[id$=":id/tt_splash_skip_btn"]'
 * **它靠控件 id 认，不靠文字**。而本项目之前 100% 依赖文字匹配，所以只要
 * 按钮不写「跳过」两个字（纯数字倒计时、自绘、无文字）就永远匹配不上。
 * 这就是为什么改事件类型、加窗口、放宽时限都不管用 —— 方向错了。
 *
 * 现在：**控件 id 后缀匹配优先，文字匹配兜底**。id 表见 [AD_VIEW_IDS]。
 *
 * ## 误伤防护（一条都不能删）
 *
 * 1. **包名黑名单**：systemui（手势条/通知栏）、settings（含无障碍页）、
 *    权限控制器、输入法、自己——一律不碰，连计时都不更新。
 * 2. **关键词必须是整段广告语义**：「跳过」「跳过5」「跳过广告」「关闭广告」
 *    「skip」。**绝不 contains**，也**不含**通用词「关闭」「我知道了」「稍后再说」。
 * 3. **同一个 App 启动只点一次**，且只在切到前台后的 10 秒窗口内扫。
 *
 * 宁可漏跳，也不能乱点 —— 乱点用户会直接关掉整个功能，漏跳只是少跳一次广告。
 */
class AdSkipService : AccessibilityService() {

    companion object {
        /**
         * 绝对不碰的包。
         *
         * `com.android.settings` 最关键：无障碍开关就在这个包的应用里，
         * 以前它会被自己点掉，用户看到的就是"开了又自己关了"。
         * `com.android.systemui` 装着返回手势条和通知栏，同样不能碰。
         */
        private val BLOCKED_EXACT = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.coloros.safecenter"
        )

        /** 输入法也不能点（键盘上的「关闭」「跳过」是正常按键） */
        private val BLOCKED_PREFIX = listOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.sohu.inputmethod",
            "com.iflytek.inputmethod",
            "com.baidu.input",
            "com.tencent.qqpinyin",
            "com.google.android.inputmethod"
        )

        /**
         * **开屏广告按钮的控件 id（后缀匹配）** —— 这是 v1.13.9 之后最关键的一刀。
         *
         * 社区规则（GKD 官方订阅 / 李跳跳规则库）里，酷安的开屏规则就一行：
         *     '[id$=":id/tt_splash_skip_btn"]'
         * 也就是说**它压根不是靠文字认的**。广告 SDK 的跳过按钮文字经常是纯数字
         * 倒计时、或者干脆没文字（自绘），只有控件 id 稳定。
         *
         * 为什么用后缀匹配：穿山甲在不同版本里包前缀会变
         * （`com.byted.pangle:id/...` 与 `com.byted.pangle.m:id/...`），
         * 写死全名会在 SDK 升级后集体失效。
         *
         * 这里的 id 全部来自真实广告 SDK，不是猜的：
         * 穿山甲 Pangle / 腾讯优量汇 GDT / 快手联盟 KS / 通用开屏模板。
         */
        private val AD_VIEW_IDS = listOf(
            "tt_splash_skip_btn",          // 穿山甲（酷安、大量国内 App 都是这个）
            "tt_splash_skip_view",
            "tt_splash_skip",
            "gdt_ad_custom_count_down",    // 优量汇倒计时容器
            "gdt_ad_skip",
            "ksad_auto_close_btn",         // 快手联盟
            "ksad_skip_btn",
            "splash_ad_skip",              // 通用开屏模板
            "skip_view",
            "tv_skip",
            "skip_btn",
            "ad_skip_btn",
            "ad_skip_view"
        )

        /** 精确匹配的广告按钮文本（已经去空白、转小写） */
        private val EXACT_TEXTS = setOf(
            "跳过", "跳过广告", "关闭广告", "跳过视频", "skip"
        )

        /**
         * 「跳过」开头但**不是广告**的按钮，明确排除。
         * 放宽文字匹配后必须配这张表，否则「跳过教程」「跳过引导」会被误点。
         */
        private val EXCLUDED_TEXTS = setOf(
            "跳过教程", "跳过引导", "跳过设置", "跳过这一步", "跳过此步"
        )

        /** 「跳过5」「跳过5s」「跳过5秒」「跳过 5 秒」这类带倒计时的按钮 */
        private val COUNTDOWN_RE = Regex("^跳过\\d{1,2}[s秒]?$")

        /** 文本长度上限：广告按钮都很短，长句子一定不是 */
        private const val MAX_TEXT_LEN = 10

        /** 「跳过」开头的短文本上限（社区同款 `[text^="跳过"][len<=4]`，这里放到 5 兼容「跳过此广告」） */
        private const val PREFIX_MAX_LEN = 5

        /** 递归深度上限，避免在超长列表里空转 */
        private const val MAX_DEPTH = 32

        /**
         * 是否已授权无障碍。
         *
         * 千万不能拿字符串 contains(".AdSkipService") 去比：系统写进
         * ENABLED_ACCESSIBILITY_SERVICES 的是「包名/类全名」即
         * com.jianchuan.app/com.jianchuan.app.AdSkipService，
         * 而部分 ROM 又写成短名 com.jianchuan.app/.AdSkipService。
         * 只匹配其中一种 → 用户明明开了无障碍，开关还是把人往设置页赶。
         * 正确做法：按 ':' 拆开，逐条 unflatten 成 ComponentName 比包名+类名。
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AdSkipService::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            if (flat.isBlank()) return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            for (entry in splitter) {
                val cn = ComponentName.unflattenFromString(entry) ?: continue
                if (cn.packageName != expected.packageName) continue
                // 短名（.AdSkipService）补全成全名再比
                val cls = if (cn.className.startsWith(".")) cn.packageName + cn.className else cn.className
                if (cls == expected.className) return true
            }
            return false
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 上一次处理的目标包名 + 时间，只在 App 刚切到前台后的窗口内触发（开屏广告都在启动瞬间弹） */
    private var lastPackage = ""
    private var packageSince = 0L

    /** 已经跳过一次的包，同一个 App 启动只点一次，避免反复 */
    private var skippedPackage = ""

    /** 上次弹「已跳过广告」的时间，用于冷却去重 */
    private var lastToastAt = 0L

    /** 上次扫描时间，给 content changed 事件做节流（这类事件一秒能来几十条） */
    private var lastScanAt = 0L

    /**
     * 只在切到前台后的这段时间内扫。
     * 5 秒太短——广告 SDK 要加载素材，等「跳过」按钮出现常常已经 5 秒开外了。
     */
    private val WINDOW_MS = 10_000L

    /** content changed 事件的扫描节流间隔 */
    private val SCAN_THROTTLE_MS = 300L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        // 系统界面 / 设置 / 输入法 / 自己：不扫，也**不更新计时**。
        // 不更新是为了让「下拉通知栏再收起」不会被当成"切了个新 App"重新开窗口。
        if (isUnsafePackage(pkg)) return

        if (pkg != lastPackage) {
            lastPackage = pkg
            packageSince = System.currentTimeMillis()
            skippedPackage = ""   // 换了新 App，允许再跳一次
        }

        // 窗口期过了就别扫了，省电也防误伤
        val now = System.currentTimeMillis()
        if (now - packageSince > WINDOW_MS) return
        if (lastPackage == skippedPackage) return

        // content changed 太密集，节流；window state changed 必须立刻扫（首次机会）
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastScanAt < SCAN_THROTTLE_MS) return
        }
        lastScanAt = now

        if (!isSkipEnabled()) return

        val node = findInAllWindows() ?: return
        clickNode(node)
    }

    private fun isSkipEnabled(): Boolean =
        getSharedPreferences("jc", MODE_PRIVATE).getBoolean("ad_skip", false)

    /** 这个包是不是"点了会出事"的（系统界面/设置/输入法/自己） */
    private fun isUnsafePackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return true
        if (pkg == applicationContext.packageName) return true
        if (pkg in BLOCKED_EXACT) return true
        return BLOCKED_PREFIX.any { pkg.startsWith(it) }
    }

    /**
     * 在主窗口 + 所有交互窗口里找广告按钮。
     *
     * 为什么不能只看 rootInActiveWindow：广告 SDK 常把开屏放在独立 window 里
     * （尤其是自绘/WebView 型广告），此时 rootInActiveWindow 返回的是 App 主窗口，
     * 里面根本没有「跳过」。遍历 windows 才能扫到。
     */
    private fun findInAllWindows(): AccessibilityNodeInfo? {
        runCatching { rootInActiveWindow }.getOrNull()?.let { root ->
            val rootPkg = root.packageName?.toString().orEmpty()
            if (rootPkg.isEmpty() || !isUnsafePackage(rootPkg)) {
                findAdButton(root, 0)?.let { return it }
            }
        }
        // 需要 flagRetrieveInteractiveWindows 才有内容，没开就是空列表，不影响
        val wins = runCatching { windows }.getOrNull() ?: return null
        for (w in wins) {
            val root = runCatching { w?.root }.getOrNull() ?: continue
            val rootPkg = root.packageName?.toString().orEmpty()
            // 系统悬浮层（通知栏、权限弹窗）一律跳过，这是防误伤的最后一道闸
            if (rootPkg.isNotEmpty() && isUnsafePackage(rootPkg)) continue
            findAdButton(root, 0)?.let { return it }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        val done = if (node.isClickable) {
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        } else {
            clickParent(node)
        }
        if (done) {
            skippedPackage = lastPackage
            toastSkipped()
        }
    }

    /**
     * 按钮本身不可点时：先往上找最近的可点祖先（广告 SDK 常在外面套一层
     * clickable 容器，直接点文字节点是无效的），都不可点才按坐标兜底。
     */
    private fun clickParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) {
                val ok = runCatching {
                    cur!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (ok) return true
            }
            cur = cur.parent
            depth++
        }
        // 坐标点击兜底（需要 canPerformGestures，配置里已开）
        return runCatching {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.isEmpty) return@runCatching false
            val path = android.graphics.Path()
            path.moveTo(r.centerX().toFloat(), r.centerY().toFloat())
            val g = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(g, null, null)
        }.getOrDefault(false)
    }

    /**
     * 跳过了要说一声。不然用户只看到广告闪了一下就没了，不知道是自己的手机在干活
     * 还是 App 自己崩了。加 1.5 秒冷却，避免一次开屏弹好几个按钮时连着刷屏。
     */
    private fun toastSkipped() {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 1500) return
        lastToastAt = now
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "已跳过广告", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 递归找第一个广告跳过按钮。
     *
     * 判定顺序：**控件 id 优先，文字兜底**。
     * id 是广告 SDK 自己写的，语义明确、不会随文案改动失效；
     * 文字匹配之所以只能兜底，是因为有的按钮是纯数字倒计时、有的干脆没文字。
     */
    private fun findAdButton(root: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (root.isVisibleToUser) {
            if (matchesAdViewId(root.viewIdResourceName)) return root
            if (matchesAdText(root.text)) return root
            // 有些控件文字在 contentDescription 里
            if (matchesAdText(root.contentDescription)) return root
        }
        for (i in 0 until root.childCount) {
            val c = runCatching { root.getChild(i) }.getOrNull() ?: continue
            val hit = findAdButton(c, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * 控件 id 匹配（后缀）。viewIdResourceName 形如
     * `com.byted.pangle:id/tt_splash_skip_btn`，取 `:id/` 后面那段比。
     */
    private fun matchesAdViewId(raw: String?): Boolean {
        val v = raw ?: return false
        if (v.isEmpty()) return false
        val slash = v.lastIndexOf('/')
        if (slash < 0 || slash == v.length - 1) return false
        val name = v.substring(slash + 1)
        for (id in AD_VIEW_IDS) {
            if (name == id || name.endsWith(id)) return true
        }
        return false
    }

    /**
     * 广告按钮文字判定。
     *
     * 以前是「整段必须等于固定几种」，太死 —— 穿山甲/优量汇的按钮常写成
     * 「跳过广告 3 秒」「跳过此广告」，一个字都对不上。
     * 现在按社区同款思路放宽成「**以「跳过」开头 + 很短**」，但仍然：
     * 1. **绝不 contains**（那正是误点「关闭」的根源）
     * 2. 长度硬上限 5，长句子一律不算
     * 3. 「跳过教程 / 跳过引导 / 跳过设置」明确排除（放宽后必须配这张表）
     */
    private fun matchesAdText(raw: CharSequence?): Boolean {
        val s = raw?.toString()?.trim() ?: return false
        if (s.isEmpty()) return false
        val t = s.replace(Regex("\\s+"), "").lowercase()
        if (t.length > MAX_TEXT_LEN) return false
        if (t in EXCLUDED_TEXTS) return false
        if (t in EXACT_TEXTS) return true
        if (COUNTDOWN_RE.matches(t)) return true
        // 「跳过此广告」「跳过广告3秒」这类：前缀 + 短
        return t.startsWith("跳过") && t.length <= PREFIX_MAX_LEN
    }

    override fun onInterrupt() {}
}
