package com.jianchuan.app

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.annotation.SuppressLint
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import android.content.pm.PackageManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    /** 未连接时的一行状态条（原来是一整块「输入框 + 两个大按钮」顶在底部，还关不掉） */
    private lateinit var manualBox: LinearLayout
    private lateinit var stripDot: View
    private lateinit var stripText: TextView
    private lateinit var stripAction: TextView
    private lateinit var stripHint: TextView
    /** 点状态条才滑出来的连接抽屉 */
    private lateinit var connDrawer: LinearLayout
    private lateinit var connScrim: View
    private lateinit var drawerClose: android.widget.ImageButton
    private lateinit var drawerTitle: TextView
    /** 用户主动收起了抽屉：本次启动内不再自动弹，只有再点状态条才出现 */
    private var drawerDismissed = false
    private lateinit var addrInput: EditText
    private lateinit var netSwitch: CompoundButton
    private var syncingSwitch = false
    @Volatile private var heartbeatRunning = false
    private var serverUrl = ""
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var everConnected = false
    private var clientId = "app"
    // 从系统「分享」面板进来的文件（相册/文件管理器 → 分享 → 简传 Lite）。冷启动时可能还没连上电脑，
    // 先攒着，等连接就绪（loadServer 或 startDiscover 成功后）再逐个传出去。
    private val pendingSharedUris = mutableListOf<Uri>()
    // 最近一次分享的指纹（动作 + Uri 列表），用于去重：防止 singleTop 复用时 onCreate 和
    // onNewIntent 先后拿到同一份 intent 把文件传两遍。用指纹而不是一次性布尔，这样进程存活期间
    // 用户分享第二个文件也能正常处理。
    private var lastShareFingerprint: String? = null

    // 客户端上传（分享/选文件 → 传到电脑）期间持有的锁。上传大文件要几十秒，
    // 屏幕一灭进入 Doze，WiFi 转省电、TCP 被掐，apk/视频这种大文件必「connection abort」。
    // 与服务端模式（MobileServer 里的锁）相互独立：上传完就释放，不留长锁。
    private var uploadWakeLock: PowerManager.WakeLock? = null
    private var uploadWifiLock: WifiManager.WifiLock? = null
    // 并发上传计数：多个文件同时传时，锁要等最后一个传完才释放（否则先传完的线程把锁置空，
    // 还在传的大文件又会被息屏掐断）。
    @Volatile private var uploadLockRefs = 0

    /** 手动输入框弹出后的静默重连线程开关 */
    @Volatile private var silentRetryRunning = false
    /** 当前是不是公网模式（后台线程要读，不能碰 netSwitch 这种 View） */
    @Volatile private var publicMode = false
    /** 正在搜索电脑，防止心跳/换网/加载失败三路同时触发叠加出好几轮扫描 */
    @Volatile private var discovering = false
    /** 主页面加载失败过（显示的是错误页），心跳恢复后需要重载 */
    @Volatile private var pageBroken = false
    // 必须持有强引用，否则回调可能被 GC，换网后状态不再更新
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ---- 服务端模式 ----
    // Switch → CompoundButton：顶部已改成「图标胶囊」，实际控件是 CheckBox，
    // 两者共同父类是 CompoundButton，isChecked / setOnCheckedChangeListener 用法完全一样。
    private lateinit var serverSwitch: CompoundButton
    private lateinit var serverBox: ScrollView
    private lateinit var qrImage: ImageView
    private lateinit var serverUrlText: TextView
    private lateinit var serverStatsText: TextView
    // ---- 顶部栏 / 服务端面板新增控件 ----
    private lateinit var topBar: LinearLayout
    private lateinit var connDot: View
    private lateinit var clipDot: View
    private lateinit var srvDot: View
    private lateinit var srvTitle: TextView
    private lateinit var srvSub: TextView
    private lateinit var copyIpBtn: ImageButton
    private lateinit var addrPill: LinearLayout
    private lateinit var recvCard: LinearLayout
    private lateinit var qrCard: LinearLayout
    /** 服务端开启后收到的文件数（本次运行内累加） */
    private var receivedCount = 0
    // 注意：clipBridge / mobileServer / bleClip 三个对象必须在 onCreate 里创建，
    // 不能写成字段初始化器。原因：字段初始化器跑在 Activity 构造函数里，那时
    // getBaseContext() 还是 null，里面任何 getSystemService 都会抛
    // IllegalStateException("System services not available to Activities before onCreate()")
    // —— 表现就是点图标立刻闪退（v1.9.1 就是这么崩的）。
    // clipBridge 必须比 mobileServer 先建，MobileServer 构造时要用它。
    private lateinit var clipBridge: ClipBridge
    private lateinit var mobileServer: MobileServer

    // ---- 剪贴板同步（没有开关了：默认自动同步，只显示状态）----
    private lateinit var clipStatus: TextView
    /** 当前配色：首次晨雾，之后用上次选的那套 */
    private var pal: Palette = PALETTES[0]
    private lateinit var bleClip: BleClipServer
    @Volatile private var clipWatchRunning = false
    private var clipThread: Thread? = null
    /** 最近一次发出去的内容指纹，用于抑制「收到→又发回去」的回环 */
    @Volatile private var lastSentHash = 0
    @Volatile private var clipPeerConnected = false

    // ---- 本机地址候选（热点/多网卡场景下只有一个是对的，可点选切换）----
    private lateinit var ipList: LinearLayout
    private lateinit var refreshIpBtn: TextView
    private lateinit var openDlBtn: TextView
    private lateinit var diagBtn: TextView
    private lateinit var peerText: TextView
    private var ipCandidates: List<IpCandidate> = emptyList()
    private var selectedIp: String = ""

    // ---- 下载进度浮层（叠在 WebView 底部的卡片列表）----
    private lateinit var dlPanel: LinearLayout
    private val dlItems = mutableListOf<DlItem>()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null && result.contents.isNotBlank()) {
            val url = result.contents.trim()
            loadServer(ensureHttp(url))
        }
    }

    // 选文件夹 → 打包 zip → 发送
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { packFolderAndSend(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次启动：先走引导页（自启动/电池优化/通知），完成或跳过后才进主界面
        if (!getSharedPreferences("jc", MODE_PRIVATE).getBoolean("onboarded", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // 到这里系统服务才可用，剪贴板桥 / 服务端 / 蓝牙外设都必须在这个时刻之后创建
        clipBridge = ClipBridge(this)
        mobileServer = MobileServer(this, clipBridge)
        bleClip = BleClipServer(this)

        webView = findViewById(R.id.webview)
        status = findViewById(R.id.status)
        manualBox = findViewById(R.id.manual_box)
        stripDot = findViewById(R.id.strip_dot)
        stripText = findViewById(R.id.strip_text)
        stripAction = findViewById(R.id.strip_action)
        stripHint = findViewById(R.id.strip_hint)
        connDrawer = findViewById(R.id.conn_drawer)
        connScrim = findViewById(R.id.conn_scrim)
        drawerClose = findViewById(R.id.drawer_close)
        drawerTitle = findViewById(R.id.drawer_title)
        // 状态条整行可点 → 滑出抽屉；✕ 和遮罩都能收起
        manualBox.setOnClickListener { openDrawer() }
        drawerClose.setOnClickListener { closeDrawer(true) }
        connScrim.setOnClickListener { closeDrawer(true) }
        addrInput = findViewById(R.id.addr_input)
        topBar = findViewById(R.id.top_bar)
        connDot = findViewById(R.id.conn_dot)
        clipDot = findViewById(R.id.clip_dot)
        netSwitch = findViewById(R.id.net_switch)
        serverSwitch = findViewById(R.id.server_switch)
        serverBox = findViewById(R.id.server_box)
        qrImage = findViewById(R.id.qr_image)
        qrCard = findViewById(R.id.qr_card)
        serverUrlText = findViewById(R.id.server_url)
        serverStatsText = findViewById(R.id.server_stats)
        srvDot = findViewById(R.id.srv_dot)
        srvTitle = findViewById(R.id.srv_title)
        srvSub = findViewById(R.id.srv_sub)
        addrPill = findViewById(R.id.addr_pill)
        copyIpBtn = findViewById(R.id.copy_ip_btn)
        recvCard = findViewById(R.id.recv_card)
        ipList = findViewById(R.id.ip_list)
        refreshIpBtn = findViewById(R.id.refresh_ip_btn)
        refreshIpBtn.setOnClickListener { refreshServerIps() }
        openDlBtn = findViewById(R.id.open_dl_btn)
        // 点「打开」直接给一份列表：系统「文件」App 只认网格大图，同系列文件
        // 名字全被截断，找文件得靠猜。列表里一行一个完整名字，点一行就打开。
        // 想用系统的还可以点列表底部那行「在文件管理器中打开」。
        openDlBtn.setOnClickListener { showReceivedFilesDialog() }
        diagBtn = findViewById(R.id.diag_btn)
        diagBtn.setOnClickListener { runNetworkDiagnosis() }
        peerText = findViewById(R.id.peer_text)
        clipStatus = findViewById(R.id.clip_status)
        copyIpBtn.setOnClickListener { copyServerAddress() }
        // 主题：和电脑端一样「点一下直接换下一套」，不弹框；
        // 想一步到某套就长按，出色卡自己挑。
        findViewById<ImageButton>(R.id.theme_btn).apply {
            setOnClickListener {
                val idx = PALETTES.indexOfFirst { it.id == pal.id }.coerceAtLeast(0)
                applyTheme(PALETTES[(idx + 1) % PALETTES.size])
                pillPop(this)
            }
            setOnLongClickListener {
                showThemePicker()
                true
            }
        }
        findViewById<ImageButton>(R.id.more_btn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 主题要在任何界面动作之前铺好，否则会先闪一下晨雾再变
        pal = currentPalette()
        applyPalette()
        dlPanel = findViewById(R.id.dl_panel)

        setupWebView()

        findViewById<Button>(R.id.go_btn).setOnClickListener {
            val s = addrInput.text.toString().trim()
            if (s.isNotEmpty()) loadServer(ensureHttp(s))
        }
        findViewById<Button>(R.id.scan_btn).setOnClickListener { startScan() }

        // 公网/局域网：图标胶囊（CheckBox 做的外壳，行为等同开关）
        netSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            stylePill(netSwitch, pal, if (checked) "连接中" else "公网")
            pillPop(netSwitch)
            publicMode = checked
            if (checked) {
                // 切公网：不能直接拿上次记的地址连。cloudflare 快速隧道每次开启
                // 都换一个随机域名，电脑一重启，手机存的那个就是死链。
                resolvePublicUrl()
            } else {
                stylePill(netSwitch, pal)
                // 切局域网：重新自动发现
                startDiscover()
            }
        }

        // 服务端模式：打开后手机变「东道主」，别人扫码/输网址传文件
        serverSwitch.setOnCheckedChangeListener { _, checked ->
            stylePill(serverSwitch, pal, if (checked) "接收中" else "关闭中…")
            pillPop(serverSwitch)
            if (checked) startServerMode() else stopServerMode()
        }

        // 剪贴板双向同步：不再有开关，默认就开（用户定的刚需）。
        // 蓝牙权限没给会在下面自己弹系统框，给完继续开，拒绝则自动落到局域网通道。
        enableClipSync()

        // 上次连过的局域网地址还在，就直接连（不用重新扫码）；失效了心跳会自动重新发现
        val savedLan = getSharedPreferences("jc", MODE_PRIVATE).getString("lan_url", "") ?: ""
        if (savedLan.isNotBlank()) {
            loadServer(ensureHttp(savedLan))
        } else {
            startDiscover()
        }
        watchNetwork()
        uiHandler.postDelayed(peerTick, 1500)
        showLastCrashIfAny()

        // 后台保活：启动前台服务，进程不被系统回收（保活是通知推送/跳广告的命根子）
        KeepAliveService.start(this)

        // 从系统「分享」进来的文件：接到就走上传。此时上面的连接逻辑已跑完，
        // 若已连上就直接传；没连上则攒着等 onNewIntent/连接就绪后补传。
        handleShareIntent(intent)

        // 顶部栏入场：淡入 + 从上方落 8dp。首屏唯一一处主动动画，不拖慢启动
        topBar.alpha = 0f
        topBar.translationY = -dp(8).toFloat()
        topBar.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    /**
     * 上次崩溃过就在启动时把堆栈弹出来（可长按选中复制），看完删掉，只打扰一次。
     * 有了这个，闪退就不用再靠「打开就闪退」一句话去猜原因。
     */
    private fun showLastCrashIfAny() {
        val file = listOfNotNull(getExternalFilesDir(null), filesDir)
            .map { File(it, App.CRASH_FILE) }
            .firstOrNull { it.exists() } ?: return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        runCatching { file.delete() }

        val body = TextView(this).apply {
            this.text = text
            textSize = 10f
            setPadding(36, 24, 36, 8)
            setTextIsSelectable(true)
        }
        val box = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle("上次异常退出，把这段发我")
            .setView(box)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------------------------------------------------------------- 主题
    private fun currentPalette(): Palette {
        val id = getSharedPreferences("jc", MODE_PRIVATE).getString("theme_id", "light") ?: "light"
        return PALETTES.firstOrNull { it.id == id } ?: PALETTES[0]
    }

    private fun tintOf(c: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    /** 抽屉底色：只有上两角圆，贴住屏幕底边 */
    private fun drawerBg(p: Palette): GradientDrawable = GradientDrawable().apply {
        setColor(p.card)
        val r = dp(18).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    // ---------------------------------------------------------------- 连接抽屉
    //
    // 原来的做法是把「输入框 + 连接 + 扫码连接 + 三行说明」常驻在底部，一旦出现就没有收回的途径，
    // 输入框被整块挤掉。现在底部只留一行可点的状态条，点开才是抽屉，三种方式都能收起。
    private fun openDrawer() {
        if (connDrawer.visibility == View.VISIBLE) return
        connScrim.visibility = View.VISIBLE
        connDrawer.visibility = View.VISIBLE
        connScrim.alpha = 0f
        connScrim.animate().alpha(1f).setDuration(160).start()
        connDrawer.alpha = 0f
        connDrawer.translationY = dp(28).toFloat()
        connDrawer.animate().alpha(1f).translationY(0f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    /**
     * 收起抽屉。userDismiss 为 true 表示是用户主动关的 —— 记下来，
     * 后台的「找不到电脑」探测就不要再把它弹出来。
     */
    private fun closeDrawer(userDismiss: Boolean) {
        drawerDismissed = drawerDismissed || userDismiss
        if (connDrawer.visibility != View.VISIBLE) return
        hideKeyboard()
        connScrim.animate().alpha(0f).setDuration(140).start()
        connDrawer.animate().alpha(0f).translationY(dp(24).toFloat())
            .setDuration(160).withEndAction {
                connDrawer.visibility = View.GONE
                connScrim.visibility = View.GONE
                connDrawer.translationY = 0f
                connScrim.alpha = 1f
            }.start()
    }

    private fun hideKeyboard() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(addrInput.windowToken, 0)
            addrInput.clearFocus()
        }
    }

    /** 图标胶囊：选中填品牌色（服务端用绿），未选中只留一圈描边。
     *  文字必须跟着状态一起变 —— 只有图标用户不知道这是干什么的，
     *  只有固定文字又看不出开没开。 */
    private fun stylePill(cb: CompoundButton, p: Palette, label: String? = null) {
        val on = if (cb.id == R.id.server_switch) p.ok else p.brand
        cb.background =
            if (cb.isChecked) rounded(on, Color.TRANSPARENT, 13)
            else rounded(Color.TRANSPARENT, p.line, 13)
        cb.setCompoundDrawableTintList(
            ColorStateList.valueOf(if (cb.isChecked) Color.WHITE else p.text3)
        )
        cb.setTextColor(if (cb.isChecked) Color.WHITE else p.text2)
        cb.text = label ?: defaultPillLabel(cb)
    }

    private fun defaultPillLabel(cb: CompoundButton): String = when (cb.id) {
        R.id.server_switch -> if (cb.isChecked) "接收中" else "服务端"
        else -> if (cb.isChecked) "公网已开" else "公网"
    }

    /**
     * 把当前配色刷到所有原生控件上。切主题时再调一次即可。
     * 不用 setTheme + recreate：那会把 WebView、服务端、蓝牙连接全重建，
     * 换个颜色不该付这个代价。
     */
    private fun applyPalette() {
        val p = pal
        findViewById<View>(R.id.root).setBackgroundColor(p.topbar)
        topBar.setBackgroundColor(p.topbar)
        window.statusBarColor = p.topbar
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (p.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        findViewById<TextView>(R.id.brand_name).setTextColor(p.text)
        status.setTextColor(p.text2)
        clipStatus.setTextColor(p.text3)

        listOf(R.id.theme_btn, R.id.more_btn).forEach {
            findViewById<ImageButton>(it).background = rounded(Color.TRANSPARENT, p.line, 15)
        }
        stylePill(netSwitch, p)
        stylePill(serverSwitch, p)

        // 未连接状态条（一行）+ 连接抽屉。状态条不刷底色，保留 XML 里的水波纹反馈
        stripText.setTextColor(p.text2)
        stripAction.setTextColor(p.brand)
        stripHint.setTextColor(p.text3)
        stripDot.background?.setTint(p.text3)
        connDrawer.background = drawerBg(p)
        findViewById<View>(R.id.drawer_handle).background?.setTint(p.line)
        drawerTitle.setTextColor(p.text)
        drawerClose.background = rounded(tintOf(p.text3, 0.12f), Color.TRANSPARENT, 14)
        drawerClose.imageTintList = ColorStateList.valueOf(p.text2)
        addrInput.setTextColor(p.text)
        addrInput.setHintTextColor(p.text3)
        addrInput.background = rounded(p.input, p.line, 10)

        // 服务端面板
        serverBox.setBackgroundColor(p.topbar)
        srvTitle.setTextColor(p.ok)
        srvSub.setTextColor(p.text3)
        qrCard.background = rounded(p.card, p.line, 16)
        addrPill.background = rounded(tintOf(p.brand, 0.10f), tintOf(p.brand, 0.45f), 12)
        serverUrlText.setTextColor(p.brand)
        refreshIpBtn.setTextColor(p.text2)
        refreshIpBtn.background = rounded(Color.TRANSPARENT, p.line, 9)
        diagBtn.setTextColor(p.text3)
        peerText.setTextColor(p.text3)
        recvCard.background = rounded(p.card2, Color.TRANSPARENT, 12)
        serverStatsText.setTextColor(p.text)
        openDlBtn.setTextColor(p.brand)
        copyIpBtn.imageTintList = ColorStateList.valueOf(p.brand)
        clipDot.background?.setTint(p.ok)
        srvDot.background?.setTint(p.ok)

        syncWebTheme(p)
    }

    /** 网页那五套主题靠 data-theme 上的 CSS 变量，改属性就能换，不用重新加载 */
    private fun syncWebTheme(p: Palette) {
        val js = "(function(){try{" +
            "document.documentElement.setAttribute('data-theme','${p.id}');" +
            "document.documentElement.classList.add('theme-fade');" +
            "setTimeout(function(){document.documentElement.classList.remove('theme-fade')},320);" +
            "localStorage.setItem('jc.theme.v2','${p.id}');" +
            "}catch(e){}})()"
        try {
            webView.evaluateJavascript(js, null)
        } catch (_: Throwable) {
        }
    }

    /** 图标胶囊按下时轻轻弹一下，让人确认"点到了" */
    private fun pillPop(v: View) {
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
        }.start()
    }

    /**
     * 主题弹窗：晨雾玻璃拟物色卡，**点一下立即切换**（没有确认/取消这一步，和电脑端一致）。
     *
     * 两个坑记一下：
     * 1) Dialog 不 setLayout 的话宽度会塌成内容宽，五张卡挤成一条，点都点不准；
     *    必须在 show() 之后再设窗口尺寸。
     * 2) 背景设透明但内容 root 不给底，会变成「字浮在半空」看不见的鬼弹窗。
     */
    private fun showThemePicker() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.card, pal.line, 20)
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }
        root.addView(TextView(this).apply {
            text = "选择主题"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(pal.text)
        })
        root.addView(TextView(this).apply {
            text = "点一下立即切换"
            textSize = 12f
            setTextColor(pal.text3)
            setPadding(0, dp(3), 0, dp(14))
        })

        val dialog = android.app.Dialog(this)
        for (p in PALETTES) {
            val selected = p.id == pal.id

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(
                    if (selected) tintOf(pal.brand, 0.10f) else Color.TRANSPARENT,
                    if (selected) pal.brand else pal.line,
                    14
                )
                setPadding(dp(12), dp(11), dp(12), dp(11))
            }
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            // 预览块：用该主题的顶栏色→品牌色做一个小渐变，比三个死板色块更像"这套主题的样子"
            val preview = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(p.topbar, p.brand)
                ).apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(1, p.line)
                }
            }
            preview.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))

            val name = TextView(this).apply {
                text = p.name
                textSize = 15f
                setTextColor(pal.text)
                setPadding(dp(12), 0, 0, 0)
            }

            val mark = TextView(this).apply {
                text = if (selected) "当前" else ""
                textSize = 12f
                setTextColor(pal.brand)
                gravity = Gravity.CENTER
            }

            row.addView(preview)
            row.addView(name)
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            row.addView(mark)

            row.setOnClickListener {
                // 点当前主题不用重刷，直接关掉；点别的立刻生效
                if (!selected) applyTheme(p)
                dialog.dismiss()
            }
            root.addView(row)
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            // 关键：不设宽度就会塌成内容宽，卡片挤成一坨
            val w = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 弹出动效：淡入 + 轻轻放大
            val dv = decorView
            dv.alpha = 0f
            dv.scaleX = 0.94f
            dv.scaleY = 0.94f
            dv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        }
    }

    private fun applyTheme(p: Palette) {
        if (p.id == pal.id) return
        pal = p
        getSharedPreferences("jc", MODE_PRIVATE).edit().putString("theme_id", p.id).apply()
        applyPalette()
        // 顶栏淡一下，让"换了套颜色"这件事看得出来。
        // 不再弹 Toast：颜色自己就是最直接的反馈，再念一遍主题名是废话。
        topBar.animate().alpha(0.4f).setDuration(110).withEndAction {
            topBar.animate().alpha(1f).setDuration(170).start()
        }.start()
    }

    /** 复制服务端地址：以前这行字只能看不能点，想手输得自己一个字符一个字符抄 */
    private fun copyServerAddress() {
        val raw = serverUrlText.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            Toast.makeText(this, "服务端还没开启", Toast.LENGTH_SHORT).show()
            return
        }
        val text = if (raw.startsWith("http")) raw else "http://$raw"
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("简传地址", text))
            flashCopied()
            Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "复制失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 复制成功后图标变对勾 600ms，再淡回复制图标 */
    private fun flashCopied() {
        copyIpBtn.setImageResource(R.drawable.ic_check)
        copyIpBtn.imageTintList = ColorStateList.valueOf(pal.ok)
        copyIpBtn.animate().scaleX(1.2f).scaleY(1.2f).setDuration(110).withEndAction {
            copyIpBtn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()
        uiHandler.postDelayed({
            copyIpBtn.setImageResource(R.drawable.ic_copy)
            copyIpBtn.imageTintList = ColorStateList.valueOf(pal.brand)
        }, 700)
    }

    // ---------------------------------------------------------------- 剪贴板同步
    /**
     * 打开剪贴板同步。两条通道并行：
     *   1) 蓝牙：手机当 BLE 外设广播，电脑连上来 —— 没网也能用
     *   2) 局域网：走电脑的 8787 / 手机 8788 HTTP —— 蓝牙不可用时的兜底
     */
    private fun enableClipSync() {
        // Android 12+ 蓝牙权限拆成「连接」和「广播」两项，两个都得要
        if (Build.VERSION.SDK_INT >= 31) {
            val need = mutableListOf<String>()
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) need.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!granted(Manifest.permission.BLUETOOTH_ADVERTISE)) need.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (need.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_BT)
                return
            }
        }

        // 回调必须先挂上再 start，否则第一个连接事件会漏掉
        bleClip.onText = { text -> onClipFromPeer(text, "蓝牙") }
        bleClip.onState = { connected, _ ->
            clipPeerConnected = connected
            runOnUiThread { updateClipStatus() }
        }
        val bleOk = if (bleClip.isSupported()) bleClip.start() else false

        startClipWatcher()
        updateClipStatus(
            if (bleOk) null
            else "蓝牙没开或本机不支持，已改用局域网同步（需和电脑在同一 WiFi/热点）"
        )
    }

    private fun disableClipSync() {
        clipWatchRunning = false
        clipThread?.interrupt()
        clipThread = null
        bleClip.stop()
        clipPeerConnected = false
        clipStatus.visibility = View.GONE
    }

    /** 顶部那行剪贴板状态。只有一行，放不下长句，所以只说结论 */
    private fun updateClipStatus(extra: String? = null) {
        val btAlive = bleClip.running || clipPeerConnected
        clipStatus.visibility = View.VISIBLE
        clipStatus.text = if (btAlive) "剪贴板自动同步中" else "剪贴板 · 仅局域网"
        clipDot.background?.setTint(if (btAlive || serverUrl.isNotBlank()) pal.ok else pal.text3)
        if (extra != null) {
            // 具体原因不占那一行：长按看
            clipStatus.contentDescription = extra
            clipStatus.setOnLongClickListener {
                Toast.makeText(this, extra, Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    /**
     * 剪贴板监听线程。
     * Android 10+ 只有应用位于前台才能读剪贴板，所以只在 onResume~onPause 之间跑；
     * 用户去别的 App 复制完切回来，onResume 会补读一次，不会漏掉。
     */
    private fun startClipWatcher() {
        if (clipWatchRunning) return
        clipWatchRunning = true
        clipThread = thread(name = "clip-watch") {
            // 初值取当前内容，避免一开开关就把剪贴板里的陈年老内容发出去
            var last = clipBridge.readText() ?: ""
            while (clipWatchRunning) {
                try {
                    val t = clipBridge.readText()
                    if (!t.isNullOrEmpty() && t != last) {
                        last = t
                        if (!clipBridge.isEcho(t) && t.hashCode() != lastSentHash) pushClip(t)
                    }
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(600)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /** 发本机新复制的内容：优先蓝牙，蓝牙没连上就走局域网 */
    private fun pushClip(text: String) {
        if (text.isBlank()) return
        lastSentHash = text.hashCode()
        val byBle = bleClip.sendText(text)
        if (!byBle) thread { postClipToPc(text) }
        runOnUiThread { updateClipStatus(if (byBle) "已通过蓝牙发送 ${text.length} 字" else "已发送 ${text.length} 字") }
    }

    /** 局域网通道：POST 到电脑的 /api/clip */
    private fun postClipToPc(text: String): Boolean {
        val base = serverUrl
        if (base.isBlank()) return false
        return try {
            val conn = URL("$base/api/clip").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            val bytes = text.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            CookieManager.getInstance().getCookie(base)?.let { conn.setRequestProperty("Cookie", it) }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /** 收到对端（蓝牙 / 网页）发来的内容 → 写进手机剪贴板 */
    private fun onClipFromPeer(text: String, via: String) {
        if (text.isBlank()) return
        // 记成"自己发过的"，剪贴板监听线程读到同内容就不会再发回去（否则来回死循环）
        lastSentHash = text.hashCode()
        clipBridge.writeText(text)
        runOnUiThread { updateClipStatus("已从$via 收到 ${text.length} 字，已放进剪贴板") }
    }

    // ---------------------------------------------------------------- 服务端模式
    private fun startServerMode() {
        // 先确保有读存储权限，授权后才真正扫描手机文件（否则只能扫到「应用」）
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }
        startServerModeInternal()
    }

    private fun startServerModeInternal() {
        // Android 11+ 文档/压缩包需要「所有文件访问」，没开就提示引导（媒体文件不受影响）
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            showAllFilesPermissionDialog()
        }
        val port = 8788   // 手机服务端用 8788，与电脑服务端 8787 区分开
        try {
            mobileServer.start(port)
        } catch (e: Exception) {
            Toast.makeText(this, "服务端启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            serverSwitch.isChecked = false
            return
        }
        refreshServerStats()
        webView.visibility = View.GONE
        manualBox.visibility = View.GONE
        closeDrawer(false)
        serverBox.visibility = View.VISIBLE
        // 面板入场：整体淡入上移 8dp，二维码晚 60ms 从 0.92 放大到 1。
        // 必须等 visibility 变 VISIBLE 之后再启动，否则动画在不可见时已经跑完了。
        serverBox.alpha = 0f
        serverBox.translationY = dp(8).toFloat()
        serverBox.animate().alpha(1f).translationY(0f).setDuration(200).start()
        qrCard.alpha = 0f
        qrCard.scaleX = 0.92f
        qrCard.scaleY = 0.92f
        qrCard.translationY = dp(6).toFloat()
        qrCard.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(60).setDuration(260).start()
        status.text = "服务端 · 等别人连我"
        mobileServer.uploadListener = { name, _ ->
            receivedCount++
            runOnUiThread {
                refreshServerStats()
                Toast.makeText(this, "收到「$name」", Toast.LENGTH_SHORT).show()
            }
        }
        mobileServer.clipListener = {
            runOnUiThread {
                Toast.makeText(this, "已把电脑的内容放进手机剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
        // 枚举全部网卡候选（热点/以太网/USB 全覆盖），不再只信 WifiManager
        refreshServerIps()
    }

    private fun refreshServerStats() {
        val c = mobileServer.fileCounts()
        val total = c.values.sum()
        val sub = findViewById<TextView>(R.id.recv_sub)
        // 主行说「这次收到几个」，副行说「手机里能分享几个」，两件事分开讲
        serverStatsText.text = "已接收 $receivedCount 个文件"
        sub.text = if (total <= 0) {
            "还没有可分享的文件"
        } else {
            "可分享 $total 个 · 图片 ${c["image"] ?: 0} · 视频 ${c["video"] ?: 0} · 文档 ${c["doc"] ?: 0}"
        }
    }

    // ---------------------------------------------------------------- 连不上时自检
    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * 每 1.5 秒刷一次「最近访问」。
     * 排查热点场景时，这条信息能一刀切开两种完全不同的故障：
     *   有请求 → 网络是通的，问题在浏览器/页面
     *   没请求 → 请求压根没到手机，问题在热点隔离/代理/防火墙
     */
    private val peerTick = object : Runnable {
        override fun run() {
            if (::peerText.isInitialized && ::serverBox.isInitialized &&
                ::mobileServer.isInitialized && serverBox.visibility == View.VISIBLE
            ) {
                peerText.text = buildPeerText()
            }
            uiHandler.postDelayed(this, 1500)
        }
    }

    private fun buildPeerText(): String {
        if (!mobileServer.isRunning) return "服务端未运行"
        val ip = mobileServer.lastClientIp
        if (ip.isBlank()) return "还没有人访问过 · 端口 8788"
        val ago = ((System.currentTimeMillis() - mobileServer.lastClientAt) / 1000).coerceAtLeast(0)
        val whenText = if (ago < 2) "刚刚" else "${ago} 秒前"
        return "最近访问：$ip  $whenText · 共 ${mobileServer.requestCount} 个请求"
    }

    private fun runNetworkDiagnosis() {
        val port = 8788
        val cands = localIpCandidates()
        if (cands.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("网络自检")
                .setMessage("没有找到可用的局域网地址。\n请先连上 WiFi 或打开手机热点。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        Toast.makeText(this, "自检中…", Toast.LENGTH_SHORT).show()
        thread {
            val sb = StringBuilder()
            sb.append("① 手机自己能不能连上服务端口 ").append(port).append("\n")
            var anyOk = false
            for (c in cands) {
                val ok = tryConnect(c.ip, port)
                if (ok) anyOk = true
                sb.append(if (ok) "✅ 通 " else "❌ 不通 ")
                    .append(c.ip)
                    .append("（").append(c.iface).append(" · ").append(c.tag).append("）\n")
            }
            sb.append('\n')

            val peer = mobileServer.lastClientIp
            if (peer.isBlank()) {
                sb.append("② 还没有任何外部设备访问过\n\n")
                sb.append("电脑打不开，说明请求根本没到手机，问题在网络这一段：\n")
                sb.append("· 手机热点可能开了「设备隔离 / AP 隔离」\n")
                sb.append("· 电脑开着代理软件，把 192.168 开头的地址也代理走了\n")
                sb.append("· 电脑防火墙拦了浏览器访问局域网\n")
                sb.append("· 手机开着 VPN，VPN 会接管热点网卡的流量\n")
                sb.append("· 电脑连的是别的 WiFi，没连到这台手机的热点\n")
            } else {
                sb.append("② 最近访问来自 ").append(peer)
                sb.append("（").append(mobileServer.lastClientPath).append("）\n")
                sb.append("累计 ").append(mobileServer.requestCount).append(" 个请求\n\n")
                sb.append("请求已经到手机了，说明网络是通的。\n")
                sb.append("电脑上页面打不开就按 Ctrl+F5 强制刷新一下。\n")
            }
            if (!anyOk) {
                sb.append("\n⚠️ 手机自己都连不上自己，服务端可能没起来。\n")
                sb.append("把「服务端」开关关掉再打开一次试试。")
            }
            val msg = sb.toString()
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("网络自检")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun tryConnect(ip: String, port: Int, timeoutMs: Int = 800): Boolean {
        return try {
            Socket().use {
                it.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 是否有「至少一项」媒体读取权限。
     * 注意：Android 13+ 的权限弹窗是分组的（照片和视频 / 音乐和音频），
     * 用户只勾其中一组是极常见的操作。这里不能用「全部已授权」来判定，
     * 否则只勾一项就会被判失败、服务端永远起不来。
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_AUDIO) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT >= 23) {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            true  // Android 6.0 之前安装即授权
        }
    }

    private fun granted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() {
        val perms = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> null
        }
        if (perms == null) { startServerModeInternal(); return }
        ActivityCompat.requestPermissions(this, perms, REQ_STORAGE)
    }

    private fun showAllFilesPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("开启「所有文件访问」")
            .setMessage("要扫描文档、压缩包等文件给别人下载，需要开启「所有文件访问」权限（图片/音乐/视频不受影响）。")
            .setPositiveButton("去开启") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                    }
                }
            }
            .setNegativeButton("跳过", null)
            .show()
    }

    private fun stopServerMode() {
        runCatching { mobileServer.stop() }
        // 收起服务端面板并复位入场动画（showWebViewOnly 里统一做，避免两处不一致）
        serverBox.visibility = View.GONE
        serverBox.alpha = 1f
        serverBox.translationY = 0f

        // 关掉服务端 ≠ 和电脑失联。以前这里无条件 startDiscover()，
        // 探测不到电脑就把「输入框 + 两个大按钮」整块甩出来，还没有关闭入口 ——
        // 就是用户截图里那个关不掉的弹窗。现在回到关闭前的客户端地址，失败也只留一行状态条。
        val back = serverUrl.ifBlank {
            getSharedPreferences("jc", MODE_PRIVATE).getString("lan_url", "") ?: ""
        }
        if (back.isNotBlank()) {
            showWebViewOnly()
            closeDrawer(false)
            manualBox.visibility = View.GONE
            loadServer(back)
        } else {
            showWebViewOnly()
            status.text = "服务端已关闭 · 本机文件"
            markDisconnected("未连接", "本机文件仍可查看")
            // 只有「本来就没连过电脑」时才去找，而且找到也不打扰
            startDiscover()
        }
        stylePill(serverSwitch, pal)
    }

    /** 把底部状态条点亮成「未连接 · 点这里连接」，不主动弹抽屉 */
    private fun markDisconnected(main: String, hint: String = "") {
        manualBox.visibility = View.VISIBLE
        stripText.text = main
        stripAction.text = "· 点这里连接"
        stripHint.text = hint
        stripDot.background?.setTint(pal.text3)
        connDot.background?.setTint(pal.text3)
    }

    private fun generateQr(content: String, size: Int): Bitmap {
        val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val w = matrix.width
        val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    // ---------------------------------------------------------------- 本机 IP 枚举
    //
    // Android 12+ 的 WifiManager.connectionInfo.ipAddress 对普通 App 返回 0；
    // 手机自己开热点时更是必然为 0（connectionInfo 记录的是「我作为客户端连了谁」）。
    // 这里改用 java.net.NetworkInterface 遍历全部网卡：零运行时权限，热点/以太网/USB 全覆盖。
    data class IpCandidate(
        val ip: String,          // "192.168.43.1"
        val iface: String,       // "wlan0"
        val prefixLen: Int,      // 24
        val broadcast: String?,  // "192.168.43.255"，可为 null
        val score: Int,
        val tag: String          // "热点"/"WiFi"/"以太网"/"USB"/"其他"
    )

    // 这些是蜂窝/VPN/虚拟接口，对端连不上，直接丢掉
    private val ipFilterPrefixes = listOf(
        "rmnet", "ccmni", "dummy", "v4-rmnet", "clat",
        "tun", "tap", "ppp", "ip6tnl", "sit", "gre", "lo"
    )

    private fun ifaceTag(name: String): String {
        val n = name.lowercase()
        return when {
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") -> "热点"
            n.startsWith("wlan") -> "WiFi"
            n.startsWith("eth") -> "以太网"
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> "USB"
            else -> "其他"
        }
    }

    private fun ifaceScore(name: String): Int {
        val n = name.lowercase()
        return when {
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") -> 60
            n.startsWith("wlan") || n.startsWith("eth") -> 40
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> 30
            else -> 0
        }
    }

    /** 当前活动网络的接口名（拿不到按 0 分，不影响其他加分项） */
    private fun activeIfaceName(): String = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getLinkProperties(cm.activeNetwork)?.interfaceName ?: ""
    } catch (_: Exception) { "" }

    private fun localIpCandidates(): List<IpCandidate> {
        val result = mutableListOf<IpCandidate>()
        val activeIface = activeIfaceName()
        try {
            val enums = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in enums) {
                try {
                    if (!nif.isUp || nif.isLoopback) continue
                    val name = nif.name ?: continue
                    val lower = name.lowercase()
                    if (ipFilterPrefixes.any { lower.startsWith(it) }) continue
                    for (addr in nif.interfaceAddresses) {
                        val inet = addr.address as? Inet4Address ?: continue
                        if (inet.isLoopbackAddress || inet.isLinkLocalAddress) continue
                        val ip = inet.hostAddress ?: continue
                        // 网段分：192.168/16 → 300；10/8 → 200；172.16-31/12 → 100；其他 → 50
                        var score = when {
                            ip.startsWith("192.168.") -> 300
                            ip.startsWith("10.") -> 200
                            ip.startsWith("172.") -> {
                                val second = ip.substringAfter("172.").substringBefore(".").toIntOrNull()
                                if (second != null && second in 16..31) 100 else 50
                            }
                            else -> 50
                        }
                        // 网关特征分（热点关键信号）：主机位 .1 + 落在安卓热点默认 DHCP 池
                        val host = ip.substringAfterLast('.').toIntOrNull() ?: -1
                        if (host == 1) score += 80
                        if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")) score += 80
                        score += ifaceScore(name)
                        if (name == activeIface) score += 100
                        result.add(
                            IpCandidate(
                                ip = ip,
                                iface = name,
                                prefixLen = addr.networkPrefixLength.toInt(),
                                broadcast = addr.broadcast?.hostAddress,
                                score = score,
                                tag = ifaceTag(name)
                            )
                        )
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return result.sortedWith(
            compareByDescending<IpCandidate> { it.score }
                .thenBy { it.iface }
                .thenBy { it.ip }
        ).distinctBy { it.ip }
        // 按 IP 去重：开热点时 wlan0 和 p2p0 会报同一个地址，
        // 界面上并排出现两条一模一样的 http://... 只会让人以为出问题了。
        // 排序在前，distinctBy 保留的就是分数最高那条。
    }

    private fun refreshServerIps() {
        ipCandidates = localIpCandidates()
        selectedIp = ipCandidates.firstOrNull { it.ip == selectedIp }?.ip
            ?: ipCandidates.firstOrNull()?.ip ?: "127.0.0.1"
        renderIpList()
        updateServerQr()
        if (ipCandidates.isEmpty()) {
            Toast.makeText(this, "没找到局域网 IP，可能当前没有网络", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderIpList() {
        ipList.removeAllViews()
        if (ipCandidates.isEmpty()) {
            ipList.addView(TextView(this).apply {
                text = "没找到可用的局域网地址"
                textSize = 12f
                setTextColor(Color.parseColor("#9cb1c3"))
            })
            return
        }
        for (c in ipCandidates) {
            val on = c.ip == selectedIp
            val row = TextView(this).apply {
                text = "http://${c.ip}:8788  · ${c.tag} · ${c.iface}"
                textSize = 12.5f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(if (on) Color.WHITE else Color.parseColor("#9cb1c3"))
                setBackgroundColor(if (on) Color.parseColor("#2b5c86") else Color.TRANSPARENT)
                setOnClickListener {
                    selectedIp = c.ip
                    renderIpList()
                    updateServerQr()
                }
            }
            ipList.addView(row)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun updateServerQr() {
        val url = "http://$selectedIp:8788"
        serverUrlText.text = url
        qrImage.setImageBitmap(generateQr(url, 440))
    }

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("对准电脑上的二维码")
        options.setBeepEnabled(true)
        options.setOrientationLocked(true)   // 锁定竖屏，竖着拿手机就能扫
        scanLauncher.launch(options)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan()
            } else {
                Toast.makeText(this, "需要相机权限才能扫码，请到设置里开启", Toast.LENGTH_SHORT).show()
            }
        }
        if (requestCode == REQ_BT) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED } &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                // 权限到位，继续把剪贴板同步开起来
                enableClipSync()
            } else {
                Toast.makeText(
                    this,
                    "没有蓝牙权限，只能用局域网同步（需连同一个 WiFi/热点）",
                    Toast.LENGTH_LONG
                ).show()
                // 开关保持打开：蓝牙通道用不了，但局域网通道仍然有效
                updateClipStatus(null)
            }
        }
        if (requestCode == REQ_STORAGE) {
            // 只要授到「任意一项」媒体权限就能开始扫描对应类别，不能要求全部已授权
            val anyGranted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            if (anyGranted) {
                val missing = permissions.withIndex()
                    .filter { grantResults[it.index] != PackageManager.PERMISSION_GRANTED }
                    .map { it.value }
                if (missing.isNotEmpty()) {
                    Toast.makeText(
                        this,
                        "已授权部分类型，未授权的类别扫不到（可稍后在系统设置里补充）",
                        Toast.LENGTH_LONG
                    ).show()
                }
                startServerModeInternal()
            } else {
                Toast.makeText(this, "需要存储权限才能扫描手机文件，请到设置里开启", Toast.LENGTH_LONG).show()
                serverSwitch.isChecked = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「所有文件访问」设置页回来，权限可能已授权，重新扫描补上文档/压缩包
        if (serverSwitch.isChecked && mobileServer.isRunning) {
            mobileServer.rescan()
            refreshServerStats()
        }
        // 剪贴板只有前台能读，回到前台就恢复监听，并立刻补读一次
        // （用户去别的 App 复制完再切回来，靠这一次补读把内容送出去）
        startClipWatcher()
        thread(name = "clip-resume") {
            val t = clipBridge.readText()
            if (!t.isNullOrEmpty() && !clipBridge.isEcho(t) && t.hashCode() != lastSentHash) {
                pushClip(t)
            }
        }

    }

    override fun onPause() {
        // 记下当前页面，App 被切掉再打开能回到原来那一页（否则永远是默认频道）
        runCatching { rememberUrl(webView.url) }
        // 后台读不到剪贴板，停掉省电；蓝牙广播保留，收到电脑发来的内容仍能写进剪贴板
        clipWatchRunning = false
        clipThread?.interrupt()
        clipThread = null
        super.onPause()
    }

    // ---------------------------------------------------------------- WebView
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 标记必须是纯 ASCII：HTTP 头只认 latin-1，塞中文进去这个头会被底层丢掉，
            // 电脑端就没法区分「手机 App 的 WebView」和「电脑浏览器」了。
            userAgentString = "$userAgentString JCApp/1.9"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 加载失败时 WebView 会先回调 onReceivedError 再回调这里（错误页也算「finished」），
                // 所以 pageBroken 为真就什么都别改 —— 否则会出现「状态栏写着已连接、页面是报错页」。
                if (pageBroken) return
                status.text = "已连接 · ${currentModeLabel()}"
                everConnected = true
                // 页面真正打开了就说明连上了 —— 手动输入框必须收起来。
                // 之前只在 loadServer() 里收，但心跳/换网反复触发 startDiscover 时
                // 会先弹框再加载，用户看到的就是「明明连上了还挂着输入框」。
                manualBox.visibility = View.GONE
                silentRetryRunning = false
                rememberUrl(url ?: view?.url)
                applyAppLayout()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // 只有主页面加载失败才提示，页面内部的轮询失败不算
                if (request?.isForMainFrame == true) {
                    pageBroken = true
                    status.text = "连接中断，正在重连…"
                    thread { Thread.sleep(1500); runOnUiThread { startDiscover() } }
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    startActivityForResult(intent, REQ_FILE)
                } catch (e: Exception) {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = null
                    Toast.makeText(this@MainActivity, "打不开文件选择器", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = parseFilename(contentDisposition, url)
            saveFile(url, name, mimeType ?: "")
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
    }

    /**
     * 手机 App 里的版式收窄。
     * index.html 是给电脑浏览器写的三栏布局，窄屏时右栏（公共频道访问地址 /
     * 在线设备 / 下载安卓版 App）会整块堆到聊天下面 —— App 里「下载安卓版」
     * 是给已经装了的人看的，还占掉半屏。这里注入一段 CSS 把右栏收掉，聊天区独占一屏。
     * 用注入而不是改 index.html：不用重打 exe，电脑浏览器也不受影响。
     */
    private fun applyAppLayout() {
        val js = """
            (function(){
              if (document.getElementById('jc-app-css')) return;
              document.documentElement.classList.add('jc-app');
              var s = document.createElement('style');
              s.id = 'jc-app-css';
              s.textContent = '.jc-app .side{display:none!important}'
                + '.jc-app .app{grid-template-columns:minmax(0,1fr)!important}';
              document.head.appendChild(s);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 只显示 WebView（客户端界面），把服务端面板收掉。
     *
     * webView 和 server_box 是同一个 LinearLayout 里的兄弟节点，**两个都是
     * layout_height=0dp + weight=1**。只要同时 VISIBLE 就会平分高度 ——
     * 用户实测「开了服务端，换个网络环境就变成上下两半」，就是换网时后台
     * 重连走了 loadServer()，它只把 WebView 点亮、忘了收服务端面板。
     * 所以以后凡是「切到客户端」的地方都过这个函数，不要再单独写 visibility。
     */
    private fun showWebViewOnly() {
        serverBox.visibility = View.GONE
        // 复位入场动画残留，否则下次开服务端会从半透明/位移状态开始
        serverBox.alpha = 1f
        serverBox.translationY = 0f
        webView.visibility = View.VISIBLE
    }

    private fun loadServer(url: String) {
        serverUrl = url.trimEnd('/')
        manualBox.visibility = View.GONE
        // 连上了就把抽屉收掉，并把「用户主动关过」的标记清掉，下次断开还能正常提示
        drawerDismissed = false
        closeDrawer(false)
        silentRetryRunning = false
        pageBroken = false        // 主动重新加载，错误页标记清零
        showWebViewOnly()
        val isLan = isLanUrl(serverUrl)
        publicMode = !isLan
        syncingSwitch = true
        netSwitch.isChecked = !isLan
        syncingSwitch = false
        // 静默改 isChecked 不会走监听器，胶囊外观要自己补一次
        stylePill(netSwitch, pal)
        status.text = "连接中… · ${if (isLan) "局域网" else "公网"}"
        // 地址记下来：局域网/公网各存一份，下次启动或拨开关直接连，不用重新扫码
        val sp = getSharedPreferences("jc", MODE_PRIVATE).edit()
        if (isLan) sp.putString("lan_url", serverUrl) else sp.putString("pub_url", serverUrl)
        sp.apply()
        // 重连时回到上次停留的页面（同一个服务器才恢复），否则永远是「局域网」默认频道
        webView.loadUrl(targetUrl())
        startHeartbeat()
        // 从分享面板进来时若还没连上，这里连上了就把攒着的文件补传出去
        flushPendingShared()
    }

    // ---------------------------------------------------------------- 上次页面记忆
    /**
     * 记住当前页面的完整地址（含 ?cat= / ?ch= 这类频道参数）。
     * 只记「根路径 + 查询串」：文件预览之类会失效的子页面不记，免得重开就是白屏。
     */
    private fun rememberUrl(u: String?) {
        if (u.isNullOrBlank() || serverUrl.isBlank()) return
        if (!sameOrigin(u, serverUrl)) return
        val path = runCatching { Uri.parse(u).path ?: "" }.getOrDefault("")
        if (path != "/" && path.isNotEmpty()) return
        getSharedPreferences("jc", MODE_PRIVATE).edit().putString(LAST_URL, u).apply()
    }

    private fun sameOrigin(a: String, b: String): Boolean {
        return try {
            val ua = Uri.parse(a)
            val ub = Uri.parse(b)
            ua.host != null && ua.host == ub.host && ua.port == ub.port
        } catch (_: Exception) {
            false
        }
    }

    private fun targetUrl(): String {
        val last = getSharedPreferences("jc", MODE_PRIVATE).getString(LAST_URL, "") ?: ""
        if (last.isNotBlank() && sameOrigin(last, serverUrl)) {
            val path = runCatching { Uri.parse(last).path ?: "" }.getOrDefault("")
            if (path == "/" || path.isEmpty()) return last
        }
        return "$serverUrl/"
    }

    /** 连通性探测：3 秒超时，只认 2xx */
    private fun probeServer(base: String): Boolean {
        if (base.isBlank()) return false
        return try {
            val conn = URL("$base/api/info").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 切公网时先问电脑「你现在的公网地址是多少」。
     *
     * cloudflare 快速隧道每次开启都换随机域名，手机里记的 pub_url 电脑一重启就失效，
     * 而以前这里是直接拿它去连 —— 换地方、重启电脑之后必然连不上。
     * 顺序：上次的局域网地址（同一 WiFi 下可达）→ 缓存的公网地址（电脑没重启时还有效）
     * → 都探不通才让用户手输。
     */
    private fun resolvePublicUrl() {
        markDisconnected("正在找公网地址…", "电脑上能看到当前地址")
        thread {
            val sp = getSharedPreferences("jc", MODE_PRIVATE)
            val lanCands = listOf(serverUrl, sp.getString("lan_url", "") ?: "")
                .filter { it.isNotBlank() && !it.contains("trycloudflare") }
                .distinct()

            for (base in lanCands) {
                val got = fetchTunnelUrl(base)
                if (got != null) {
                    sp.edit().putString("pub_url", got).apply()
                    runOnUiThread {
                        // 不弹 Toast：底部状态条本来就会显示当前地址，再来一条是重复噪音
                        loadServer(got)
                    }
                    return@thread
                }
            }

            val saved = sp.getString("pub_url", "") ?: ""
            if (saved.isNotBlank() && probeServer(ensureHttp(saved))) {
                runOnUiThread { loadServer(ensureHttp(saved)) }
                return@thread
            }

            runOnUiThread { showManualPub() }
        }
    }

    /** 从某台电脑的 /api/tunnel 里取当前公网地址；隧道没开就返回 null */
    private fun fetchTunnelUrl(base: String): String? {
        if (base.isBlank()) return null
        return try {
            val conn = URL("$base/api/tunnel").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            // 只能问 /api/tunnel。/api/info 里的 tunnel 字段是个布尔值
            // （"这次请求是不是从公网隧道进来的"），不是状态对象，取不到 url。
            val json = JSONObject(body)
            val url = json.optString("url", "")
            if (json.optBoolean("running", false) && url.startsWith("http")) url.trimEnd('/') else null
        } catch (_: Exception) {
            null
        }
    }

    /** 探不到就落回手输：抽屉打开，并说清楚为什么要手输 */
    private fun showManualPub() {
        stylePill(netSwitch, pal, "公网")
        markDisconnected("公网未连接", "地址每次开公网都会变")
        addrInput.setText("")
        addrInput.hint = "输入公网网址（trycloudflare 地址）"
        // 这是用户自己拨的开关，属于「他想要」的弹窗，可以直接展开
        drawerDismissed = false
        openDrawer()
        // 抽屉里已经写好「公网未连接 · 地址每次开公网都会变」，不再叠一条 Toast
    }

    // 心跳：定期探测电脑是否还在，断了就更新状态栏（不然电脑关了 App 还显示「已连接」）
    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        thread {
            var fail = 0
            while (heartbeatRunning) {
                try {
                    Thread.sleep(3000)
                    val base = serverUrl
                    if (base.isBlank()) continue
                    val ok = try {
                        val conn = URL("$base/api/info").openConnection() as HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.responseCode in 200..299
                    } catch (e: Exception) { false }
                    if (ok) {
                        if (fail > 0) {
                            fail = 0
                            runOnUiThread {
                                status.text = "已连接 · ${currentModeLabel()}"
                                // 电脑（exe）重启后地址没变，WebView 里那张报错页不会自己好，
                                // 心跳恢复就顺手重新加载一次，用户什么都不用点。
                                if (pageBroken) {
                                    pageBroken = false
                                    webView.loadUrl(targetUrl())
                                }
                            }
                        }
                    } else {
                        fail++
                        when (fail) {
                            1 -> runOnUiThread { status.text = "连接断开，正在重连…" }
                            // 连续 3 次（约 9 秒）连不上，说明电脑关了或换了网络，重新扫描
                            3 -> runOnUiThread {
                                status.text = "网络已切换，重新搜索电脑…"
                                startDiscover()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun ensureHttp(s: String) = if (s.startsWith("http")) s else "http://$s"

    private fun currentModeLabel() = if (netSwitch.isChecked) "公网" else "局域网"

    // 判断是不是局域网地址（私有 IP 段），其余都算公网
    private fun isLanUrl(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host ?: return false
            if (host == "localhost" || host == "127.0.0.1") return true
            if (host.startsWith("10.") || host.startsWith("192.168.")) return true
            if (host.startsWith("172.")) {
                val second = host.substringAfter("172.").substringBefore(".").toIntOrNull()
                return second != null && second in 16..31
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- 网络变化监听
    private fun watchNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        // 服务端模式下只刷新地址列表（热点开关/切 WiFi 会让 IP 变），
                        // 不做客户端重发现——现在我是服务端，不该去搜别人。
                        //
                        // 判断条件只看开关，**不能再看 mobileServer.isRunning**：
                        // 换网瞬间服务端可能刚好正在重启（isRunning=false），旧写法会掉进
                        // else 分支去 startDiscover()，找到电脑后走 loadServer() 把 WebView
                        // 点亮、服务端面板又没收起 → 界面变成上下两半（用户实测的那个 bug）。
                        if (serverSwitch.isChecked) {
                            refreshServerIps()
                        } else if (everConnected) {
                            status.text = "网络已切换，重新搜索电脑…"
                            startDiscover()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    runOnUiThread {
                        if (serverSwitch.isChecked) {
                            refreshServerIps()
                        } else if (everConnected) {
                            status.text = "网络断开"
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 文件下载（自实现，绕开系统下载管理器的明文限制）
    private fun saveFile(url: String, name: String, mime: String) {
        // DownloadListener 本来就在 UI 线程，但 AndroidBridge 的 @JavascriptInterface
        // 跑在 JavaBridge 线程，统一挪到主线程再碰 View 最稳
        runOnUiThread { startDownload(url, name, mime) }
    }

    /**
     * 「打开」：本地已有一份就直接交给系统应用（相册/播放器/WPS 都行）；
     * 没有才现下，下完自动打开。
     *
     * 为什么不是每次都下载：手机收到文件时 App 已经在后台静默存好了，用户看到的
     * 已经是本地文件，再下一遍纯属浪费流量和时间（外网时尤其明显）。
     */
    private fun openFileOnPhone(url: String, name: String, mime: String) {
        val local = findDownloadedFile(name)
        if (local != null) {
            if (!viewLocalFile(local, name)) {
                Toast.makeText(
                    this,
                    "没有能打开「$name」的应用，文件在 下载/简传Lite",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        // 本地确实没有才下。这里**故意不弹「正在下载…」**：
        // 底部那张下载卡本来就顶在最上面显示进度，再浮一个 Toast 正好把它盖住
        // （用户截图里就是白色气泡压在 1.1MB/1.8MB 上）。有卡片就够了。
        startDownload(url, name, mime, openAfter = true)
    }

    private fun startDownload(
        url: String,
        name: String,
        mime: String,
        openAfter: Boolean = false
    ) {
        val item = addDlItem(name.ifBlank { "文件" })
        item.setTotal(-1L)
        thread {
            var conn: HttpURLConnection? = null
            try {
                conn = openDownloadConn(url)
                // 关键：网络流直接透传给写盘，全程只在内存里留一个 256KB 缓冲。
                // 之前是 downloadBytes() 里的 readBytes() —— 整个文件先攒进 byte[]，
                // 下几百 MB 的文件夹压缩包时 Array.copyOf 要一次申请 256MB，
                // 手机堆给不出来就直接 OutOfMemoryError 闪退（v1.11.1 实测崩溃）。
                item.setTotal(conn.contentLengthLong)
                val saved = conn.inputStream.use { input ->
                    writeToDownloads(input, name, mime) { got -> item.postProgress(got) }
                }
                item.postDone(saved)
                // 下载是为了打开：落盘后立刻把本地那份交给系统应用
                if (openAfter) {
                    runOnUiThread {
                        if (!openDownloadedFile(saved)) {
                            Toast.makeText(
                                this,
                                "已存进 下载/简传Lite，但这台手机没有能打开它的应用",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                item.postFail("内存不足，文件太大")
            } catch (e: Exception) {
                item.postFail(e.message ?: "下载中断")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    /** 往浮层里加一条下载卡片，返回它的控制器 */
    private fun addDlItem(name: String): DlItem {
        val view = layoutInflater.inflate(R.layout.item_download, dlPanel, false)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (dlPanel.childCount > 0) lp.topMargin = dp(8)
        view.layoutParams = lp
        dlPanel.addView(view)

        val item = DlItem(view, name)
        dlItems.add(item)

        if (dlPanel.visibility != View.VISIBLE) {
            dlPanel.visibility = View.VISIBLE
            dlPanel.alpha = 0f
            dlPanel.translationY = dp(20).toFloat()
            dlPanel.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        return item
    }

    /**
     * 一条下载任务的卡片控制器。
     *
     * 线程约定：进度来自网络线程，但**所有 View 操作只在主线程**做，
     * 网络线程只负责把「已接收字节数」post 过来。
     */
    private inner class DlItem(val view: View, private val fileName: String) {
        private val nameText = view.findViewById<TextView>(R.id.dl_name)
        private val stateText = view.findViewById<TextView>(R.id.dl_state)
        private val bar = view.findViewById<ProgressBar>(R.id.dl_bar)
        private val sizeText = view.findViewById<TextView>(R.id.dl_size)
        private val speedText = view.findViewById<TextView>(R.id.dl_speed)

        @Volatile private var finished = false
        private var total = -1L
        private var received = 0L
        private var lastBytes = 0L
        private var lastMs = 0L
        private var speed = 0.0   // B/s，做过平滑

        init {
            nameText.text = fileName
            // 传完点一下直接去文件夹看，省得自己翻
            view.setOnClickListener {
                if (finished) openFolderOnPhone(nameText.text.toString())
            }
        }

        /** 网络线程调用 */
        fun setTotal(t: Long) {
            view.post {
                total = t
                if (finished) return@post
                if (t <= 0) {
                    // 服务端没给 Content-Length（chunked），只能显示不确定进度
                    bar.isIndeterminate = true
                    stateText.text = ""
                }
            }
        }

        /** 网络线程调用 */
        fun postProgress(got: Long) {
            if (finished) return
            view.post { render(got) }
        }

        private fun render(got: Long) {
            if (finished) return
            received = got
            val now = SystemClock.uptimeMillis()
            if (lastMs == 0L) {
                lastMs = now
                lastBytes = got
            } else {
                val dt = (now - lastMs) / 1000.0
                if (dt >= 0.25) {
                    val inst = (got - lastBytes) / dt
                    speed = if (speed <= 0.0) inst else speed * 0.7 + inst * 0.3
                    lastBytes = got
                    lastMs = now
                }
            }
            if (total > 0) {
                val permille = ((got * 1000) / total).toInt().coerceIn(0, 1000)
                bar.isIndeterminate = false
                bar.progress = permille
                stateText.text = "${permille / 10}%"
                sizeText.text = "${fmtSize(got)} / ${fmtSize(total)}"
            } else {
                bar.isIndeterminate = true
                stateText.text = ""
                sizeText.text = fmtSize(got)
            }
            speedText.text = if (speed > 0.5) "${fmtSize(speed.toLong())}/s" else ""
        }

        fun postDone(savedName: String?) {
            view.post {
                if (finished) return@post
                finished = true
                bar.isIndeterminate = false
                bar.progress = 1000
                stateText.text = "完成"
                stateText.setTextColor(Color.parseColor("#3DDC97"))
                speedText.text = ""
                nameText.text = savedName ?: fileName
                sizeText.text = if (received > 0) "已保存 · ${fmtSize(received)}" else "已保存"
                dismissLater(2200)
            }
        }

        fun postFail(reason: String) {
            view.post {
                if (finished) return@post
                finished = true
                bar.isIndeterminate = false
                stateText.text = "失败"
                stateText.setTextColor(Color.parseColor("#FF7A7A"))
                speedText.text = ""
                sizeText.text = reason.replace("\n", " ").take(46)
                dismissLater(4500)
            }
        }

        private fun dismissLater(delay: Long) {
            view.postDelayed({
                view.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(200)
                    .withEndAction {
                        dlPanel.removeView(view)
                        dlItems.remove(this)
                        if (dlPanel.childCount == 0) dlPanel.visibility = View.GONE
                    }.start()
            }, delay)
        }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun openDownloadConn(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        // readTimeout 是「两次读之间最长间隔」，不是总时长，大文件慢慢下不会被它掐断
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        val cookie = CookieManager.getInstance().getCookie(url)
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        return conn
    }

    /**
     * 边读边写，返回转录字节数。绝不把内容整块放进内存。
     *
     * onProgress 会被频繁调用（每 256KB 一次），这里按 120ms 节流：
     * 千兆局域网下 256KB 只够跑十几毫秒，不节流会把主线程刷爆。
     */
    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        var lastTick = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            if (onProgress != null) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTick >= 120) {
                    lastTick = now
                    onProgress(total)
                }
            }
        }
        output.flush()
        onProgress?.invoke(total)
        return total
    }

    /**
     * 把网络流写进 下载/简传Lite，返回最终落盘的文件名；失败抛异常由上层提示。
     *
     * 全程流式：网络 → 256KB 缓冲 → 文件描述符，占用内存与文件大小无关。
     *
     * 为什么自己算重名：Android 10+ 的 MediaStore 理论上会给同名文件自动加 (1)，
     * 但在 IS_PENDING=1 阶段这个唯一化并不可靠（pending 文件对其他应用不可见），
     * 实测会出现两条同名的 pending 记录最后都落盘成功 —— 用户看到的就是「发两次没改名」。
     * Android 9 及以下更是直接覆盖。所以两边都先算出唯一名再写。
     */
    private fun writeToDownloads(
        input: InputStream,
        name: String,
        mime: String,
        onProgress: ((Long) -> Unit)? = null
    ): String {
        val safeName = name.ifBlank { "file" }
        if (Build.VERSION.SDK_INT >= 29) {
            val finalName = uniqueDownloadName(safeName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    if (mime.isBlank()) "application/octet-stream" else mime
                )
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/简传Lite"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("下载目录不可写")
            var done = false
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    copyStream(input, out, onProgress)
                } ?: throw IOException("打不开写入通道")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                done = true
                // insert 拿到的 Uri 是这条记录最权威的身份证，当场记下来。
                // 丢掉它、事后再按文件名去反查，就是「明明有文件却说没有」的来源。
                rememberLocalFile(safeName, finalName, uri)
            } finally {
                // 中途断了别留个半截 pending 文件在下载目录里（对其他应用不可见、清不掉）
                if (!done) runCatching { resolver.delete(uri, null, null) }
            }
            return finalName
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "简传Lite"
        )
        if (!dir.exists()) dir.mkdirs()
        var finalName = safeName
        var f = File(dir, finalName)
        var i = 1
        while (f.exists() && i < 1000) {
            finalName = numberedName(safeName, i)
            f = File(dir, finalName)
            i++
        }
        var done = false
        try {
            FileOutputStream(f).use { out -> copyStream(input, out, onProgress) }
            done = true
        } finally {
            if (!done) runCatching { f.delete() }
        }
        return finalName
    }

    /** 在「下载/简传Lite」里没被占用的名字，被占就依次试 名字 (1).ext、(2).ext… */
    private fun uniqueDownloadName(name: String): String {
        if (!downloadNameTaken(name)) return name
        var i = 1
        while (i < 1000) {
            val cand = numberedName(name, i)
            if (!downloadNameTaken(cand)) return cand
            i++
        }
        return numberedName(name, System.currentTimeMillis().toInt() and 0xFFFF)
    }

    private fun numberedName(name: String, i: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + " ($i)" + name.substring(dot) else "$name ($i)"
    }

    /** 该文件名是否已经存在于 下载/简传Lite（按 MediaStore 查，不依赖目录可读性） */
    private fun downloadNameTaken(name: String): Boolean {
        return try {
            val proj = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                proj,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(name),
                null
            )?.use { c ->
                val pi = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val rel = (if (pi >= 0) c.getString(pi) else "") ?: ""
                    if (rel.replace('\\', '/').trimEnd('/').endsWith("Download/简传Lite")) return true
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- 打开文件所在文件夹（手机端）
    // Android 没有「定位到某个具体文件」的公开 API，能做到的上限是打开它所在的目录。
    // 分三级降级：目录 → 直接打开这个文件 → 告诉用户手动去哪儿找。
    // ---------------------------------------------------------------- 已接收文件列表

    /** 一条已接收文件的摘要 */
    private data class RecvEntry(
        val name: String,
        val size: Long,
        val time: Long,
        val mime: String,
        val uri: Uri
    )

    /**
     * 列出「下载/简传Lite」里已经在手机上的文件，一行一个完整文件名。
     *
     * 为什么不直接调系统「文件」App：
     *  - 它是网格大图，一屏只放两排，同系列文件名一律被截成「哲风壁纸…」，
     *    十几张看起来完全一样（用户截图里的原话：找文件很困难）；
     *  - Android 上「打开某个文件夹」只有系统文件管理器实现这个协议，
     *    第三方（ES / MT）不接，所以弹「用哪个应用打开」也只有它一个选项——
     *    ES 收到不认识的目录地址会直接落到自己的「中转站」。
     * 与其求人，不如自己列：一行一个完整文件名，点一行用对应 App 打开。
     */
    private fun showReceivedFilesDialog() {
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }
        val files = listReceivedFiles()
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.card, pal.line, 20)
            setPadding(dp(15), dp(15), dp(15), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "已接收文件"
            textSize = 16.5f
            setTextColor(pal.text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = if (files.isEmpty()) "还没有收到文件" else "下载/简传Lite · 共 ${files.size} 个"
            textSize = 12f
            setTextColor(pal.text3)
            setPadding(0, dp(3), 0, dp(10))
        })

        if (files.isNotEmpty()) {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (e in files) list.addView(recvRow(e) { dialog.dismiss() })
            // 高度上限取半屏多一点：行数少时贴合内容，行数多时自己滚，
            // 否则文件一多弹窗会长到屏幕外，按钮就点不到了
            val cap = (resources.displayMetrics.heightPixels * 0.56f).toInt()
            val want = dp(58) * files.size
            root.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                addView(list)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (want > cap) cap else ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        root.addView(TextView(this).apply {
            text = "在文件管理器中打开"
            textSize = 12.5f
            setTextColor(pal.text2)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                openFolderOnPhone("")
            }
        })

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val dv = decorView
            dv.alpha = 0f
            dv.scaleX = 0.95f
            dv.scaleY = 0.95f
            dv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
        }
    }

    /** 一行：类型块 + 文件名 + 大小/时间 */
    private fun recvRow(e: RecvEntry, onOpened: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(9), dp(2), dp(9))
            isClickable = true
            isFocusable = true
        }
        // 用扩展名当图标：比一堆一样的灰线框更容易一眼分辨，也不用额外图片资源
        val ext = e.name.substringAfterLast('.', "").uppercase().take(4)
        row.addView(TextView(this).apply {
            text = ext.ifEmpty { "?" }
            textSize = if (ext.length >= 4) 9.5f else 11f
            setTextColor(pal.brand)
            gravity = Gravity.CENTER
            maxLines = 1
            background = rounded(tintOf(pal.brand, 0.12f), Color.TRANSPARENT, 10)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = e.name
            textSize = 13.5f
            setTextColor(pal.text)
            maxLines = 1
            // 掐中间而不是掐尾巴：前缀能看出是哪个系列，后缀能看出是什么格式
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = fmtSize(e.size) + " · " + fmtRecvTime(e.time)
            textSize = 11.5f
            setTextColor(pal.text3)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)

        row.setOnClickListener {
            if (viewLocalFile(e.uri to e.mime, e.name)) {
                onOpened()
            } else {
                Toast.makeText(
                    this, "没有能打开「${e.name}」的应用", Toast.LENGTH_SHORT
                ).show()
            }
        }
        return row
    }

    /** 今天只显示时:分，今年显示月/日，更早的带年份 —— 三种密度，列表不容易看乱 */
    private fun fmtRecvTime(ms: Long): String {
        if (ms <= 0) return ""
        val d = java.util.Date(ms)
        val now = java.util.Calendar.getInstance()
        val c = java.util.Calendar.getInstance().apply { time = d }
        val sameDay = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
        val fmt = when {
            sameDay -> "HH:mm"
            now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) -> "M月d日"
            else -> "yyyy年M月d日"
        }
        return java.text.SimpleDateFormat(fmt, java.util.Locale.CHINA).format(d)
    }

    /**
     * 扫「下载/简传Lite」。Android 10+ 走 MediaStore（分区存储下直接读目录常常读不到），
     * 更低的版本才用 File 兜底。
     */
    private fun listReceivedFiles(): List<RecvEntry> {
        val out = ArrayList<RecvEntry>()
        if (Build.VERSION.SDK_INT >= 29) {
            val cols = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED,
                MediaStore.Downloads.MIME_TYPE
            )
            try {
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols,
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf("%简传Lite%"),
                    "${MediaStore.Downloads.DATE_ADDED} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        out.add(
                            RecvEntry(
                                name,
                                c.getLong(2),
                                c.getLong(3) * 1000L,
                                c.getString(4) ?: guessMimeFromName(name),
                                ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                                )
                            )
                        )
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (out.isEmpty()) {
            // 兜底：老系统，或 MediaStore 还没登记（刚写完立刻来查）。
            // 这里只读不写，读不到就是空列表，不会误伤文件。
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    MobileServer.UPLOAD_DIR_NAME
                )
                val arr = dir.listFiles() ?: emptyArray()
                for (f in arr.filter { it.isFile }.sortedByDescending { it.lastModified() }) {
                    out.add(
                        RecvEntry(
                            f.name, f.length(), f.lastModified(),
                            guessMimeFromName(f.name), Uri.fromFile(f)
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    private fun openFolderOnPhone(fileName: String) {
        if (openDownloadDir()) return
        if (openDownloadedFile(fileName)) return
        Toast.makeText(
            this,
            "这台手机没有可用的文件管理器，请手动打开「文件管理 → 下载 → 简传Lite」",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 用系统文档界面打开 下载/简传Lite 目录。不同 ROM 的 authority 不一样，挨个试。 */
    private fun openDownloadDir(): Boolean {
        val docId = "primary:Download/简传Lite"
        for (authority in arrayOf(
            "com.android.externalstorage.documents",
            "com.google.android.documentsui",
            "com.android.documentsui"
        )) {
            try {
                val uri = DocumentsContract.buildDocumentUri(authority, docId)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                return true
            } catch (_: Exception) {
                // 这个 authority 在当前机器上不认，换下一个
            }
        }
        return false
    }

    /**
     * 按文件名找本地那份文件。返回 null 表示**本地没有**，和「有但打不开」是两件事。
     *
     * 三档，从最可靠往下退：
     *  1. 落盘记录表（`jc` 偏好里的 `dl:<文件名>`）。文件是我们自己 insert 进
     *     MediaStore 的，Uri 当场就拿到了，存下来比事后反查准得多 ——
     *     **这是修「明明有文件还说没有」的关键**：反查依赖 MediaStore 索引的
     *     DISPLAY_NAME 精确相等，一旦落盘时因为重名被改成「xxx (1).jpg」、
     *     或者中文名被系统做了 NFC/NFD 归一，就一条都匹配不上。
     *  2. MediaStore 精确名。先限死 下载/简传Lite（别打开别的 App 的同名文件），
     *     再放宽到整个下载目录（v1.11.x 之前没有子目录）。
     *  3. 模糊名：「基名%.扩展名」，把 `(1)` `(2)` 这类重名后缀也收进来。
     */
    private fun findDownloadedFile(name: String): Pair<Uri, String>? {
        if (name.isBlank()) return null
        recallLocalFile(name)?.let { return it }
        if (Build.VERSION.SDK_INT < 29) return null

        val cols = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.MIME_TYPE)
        val exact = "${MediaStore.Downloads.DISPLAY_NAME}=?"
        val inDir = "$exact AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val scans: List<Pair<String, Array<String>>> = buildList {
            add(inDir to arrayOf(name, "%简传Lite%"))
            add(exact to arrayOf(name))
            likePattern(name)?.let { pat ->
                val like = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
                add("$like AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?" to arrayOf(pat, "%简传Lite%"))
                add(like to arrayOf(pat))
            }
        }
        for ((sel, args) in scans) {
            try {
                val cursor = contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cols, sel, args,
                    "${MediaStore.Downloads.DATE_ADDED} DESC"
                ) ?: continue
                cursor.use { c ->
                    if (!c.moveToFirst()) return@use
                    val id = c.getLong(0)
                    val mime = c.getString(1) ?: guessMimeFromName(name)
                    return ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    ) to mime
                }
            } catch (_: Throwable) {
                // 这一档查询失败就试下一档
            }
        }
        return null
    }

    /** `a.jpg` → `a%.jpg`，用来匹配 `a (1).jpg`。名字里有 LIKE 通配符时直接放弃这一档 */
    private fun likePattern(name: String): String? {
        if (name.contains('%') || name.contains('_')) return null
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            name.substring(0, dot) + "%" + name.substring(dot)
        } else {
            "$name%"
        }
    }

    /** `photo.heic` → `image/heic`；猜不出就交给系统（*／*） */
    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "*/*"
        return try {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        } catch (_: Throwable) {
            "*/*"
        }
    }

    /**
     * 记下落盘结果：原始文件名、实际落盘名**两个 key 都指向同一个 Uri**。
     * 打开时用哪个名字来查都能命中（下载完自动打开走的是实际名，
     * 网页按钮传过来的是原始名）。
     */
    private fun rememberLocalFile(original: String, actual: String, uri: Uri) {
        try {
            val sp = getSharedPreferences("jc", MODE_PRIVATE).edit()
            val v = "$actual\u0001$uri"
            sp.putString("dl:$original", v)
            if (actual != original) sp.putString("dl:$actual", v)
            sp.apply()
        } catch (_: Throwable) {
            // 记不下来不影响主流程，还有 MediaStore 那一档兜着
        }
    }

    /** 查落盘记录表。返回 null 除了「没记录」，也包括「记录里的文件已经被删了」 */
    private fun recallLocalFile(name: String): Pair<Uri, String>? {
        return try {
            val raw = getSharedPreferences("jc", MODE_PRIVATE).getString("dl:$name", null)
                ?: return null
            val parts = raw.split('\u0001', limit = 2)
            if (parts.size < 2) return null
            val actual = parts[0]
            val uri = Uri.parse(parts[1])
            // 文件可能已经被用户在文件管理器里删了 —— 开一下探针确认还在，
            // 不然 ACTION_VIEW 会抛 FileNotFoundException，用户看到的却是「打不开」。
            val alive = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (_: Throwable) {
                false
            }
            if (!alive) return null
            val mime = try {
                contentResolver.getType(uri) ?: guessMimeFromName(actual)
            } catch (_: Throwable) {
                guessMimeFromName(actual)
            }
            uri to mime
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把本地那份交给系统，由用户选应用（相册 / 播放器 / WPS / 图片编辑器…）。
     *
     * 先按真实 mime 走，这样系统选择器里列出来的都是真能处理这个类型的应用；
     * 匹配不到任何应用时（典型是服务器没给 Content-Type，落成了 octet-stream）
     * 放开成通配 mime 再试一次 —— **这一步是把「点了没反应」变成「弹出应用列表」的关键**。
     */
    private fun viewLocalFile(file: Pair<Uri, String>, nameHint: String = ""): Boolean {
        val uri = file.first
        val mime = file.second.ifBlank { "*/*" }
        // apk 和「打开图片/视频」根本不是一个机制，见 installApk
        if (isApk(mime, nameHint)) return installApk(uri)
        if (tryView(uri, mime)) return true
        if (mime != "*/*") {
            return tryView(uri, "*/*")
        }
        return false
    }

    private fun isApk(mime: String, name: String): Boolean =
        mime == "application/vnd.android.package-archive" ||
            name.endsWith(".apk", ignoreCase = true)

    /**
     * 安装 apk。
     *
     * Android 8.0 起，「点开一个 apk」受两层管控，**两层不满足时系统是静默丢弃 Intent**：
     * `startActivity` 不抛异常、界面上一点反应都没有，调用方还会以为成功了。
     *   1. manifest 要有 REQUEST_INSTALL_PACKAGES（已声明）；
     *   2. 用户得在系统设置里给本应用开「安装未知应用」——这条替不了，
     *      只能把他领到那个开关前面，回来再点一次。
     *
     * 返回 true 表示「已经处理了」，调用方不要再退回去重新下载。
     */
    private fun installApk(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                this,
                "需要先允许「简传 Lite」安装应用，开完回来再点一次",
                Toast.LENGTH_LONG
            ).show()
            val jumped = runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }.isSuccess
            if (!jumped) {
                Toast.makeText(
                    this,
                    "系统没有提供授权入口，请到「设置 → 应用 → 特殊权限」里手动允许",
                    Toast.LENGTH_LONG
                ).show()
            }
            return true
        }
        return tryView(uri, "application/vnd.android.package-archive") ||
            tryView(uri, "*/*")
    }

    private fun tryView(uri: Uri, mime: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        true
    } catch (_: Throwable) {
        // ActivityNotFoundException（没有应用能处理）或权限异常，都算「打不开」
        false
    }

    /** 目录打不开时退一步：把文件本身用系统默认应用打开（收到的 PDF / 图片照样能直接看） */
    private fun openDownloadedFile(name: String): Boolean {
        val f = findDownloadedFile(name) ?: return false
        return viewLocalFile(f, name)
    }

    private fun parseFilename(cd: String?, url: String): String {
        if (cd != null) {
            Regex("filename\\*=UTF-8''([^;]+)").find(cd)?.let {
                return Uri.decode(it.groupValues[1])
            }
            Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
                return it.groupValues[1].trim()
            }
        }
        return URLUtil.guessFileName(url, null, null).ifBlank { "file" }
    }

    // ---------------------------------------------------------------- 文件选择 + 上传
    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "打不开相册", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    // 分享入口：App 已在后台时，用户再分享一个文件，系统会把新 intent 交给已有实例
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleShareIntent(intent)
        }
    }

    /**
     * 处理「系统分享面板 → 简传 Lite」进来的文件（SEND / SEND_MULTIPLE）。
     * 取 EXTRA_STREAM（单文件）或 clipData（多文件）里的 Uri，交给 uploadUri 传到电脑。
     * 若当前还没连上电脑，先攒进 pendingSharedUris，等连接就绪再补传。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        // 用 LinkedHashSet 按 Uri 字符串去重：很多 App（相册/音乐）分享时会把同一个文件
        // 同时塞进 EXTRA_STREAM 和 clipData，直接两个来源都 add 会传两份相同的文件。
        val seen = LinkedHashSet<String>()
        val uris = mutableListOf<Uri>()
        fun add(u: Uri?) {
            if (u == null) return
            if (seen.add(u.toString())) uris.add(u)
        }
        // 单文件：EXTRA_STREAM 里是 Uri（SEND 也可能塞 clipData）
        @Suppress("DEPRECATION")
        add(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        // 多文件：clipData 里逐个 item
        intent.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) add(cd.getItemAt(i).uri)
        }
        if (uris.isEmpty()) return

        // 按「动作 + Uri 列表」做指纹去重：onCreate 和 onNewIntent 可能先后拿到同一个 intent
        // （singleTop 复用时），指纹相同就跳过，避免一份文件传两次；指纹不同（用户又分享了别的）
        // 则正常处理。不能用一次性 shareHandled 布尔，否则进程存活期间第二次分享会被永久吞掉。
        val fingerprint = action + "|" + uris.joinToString(",") { it.toString() }
        if (fingerprint == lastShareFingerprint) return
        lastShareFingerprint = fingerprint

        if (serverUrl.isNotBlank()) {
            // 已连电脑，直接传
            uris.forEach { uploadUri(it) }
        } else {
            // 还没连上：攒着，等 loadServer / startDiscover 成功后补传
            pendingSharedUris.addAll(uris)
            // 主动触发一次发现，尽量快点连上
            startDiscover()
        }
    }

    /** 连接就绪后，把攒着的分享文件补传出去 */
    private fun flushPendingShared() {
        if (pendingSharedUris.isEmpty() || serverUrl.isBlank()) return
        val toSend = pendingSharedUris.toList()
        pendingSharedUris.clear()
        toSend.forEach { uploadUri(it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            // 网页端 <input type=file> 触发的文件选择
            if (resultCode == RESULT_OK && data != null) {
                collectUris(data).forEach { uploadUri(it) }
            }
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            return
        }
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            collectUris(data).forEach { uploadUri(it) }
        }
    }

    private fun collectUris(data: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        }
        return uris
    }

    private fun querySize(uri: Uri): Long? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !it.isNull(idx)) it.getLong(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    /** 读超时随大小自适应：300s 基准 + 按 256KB/s 最悲观速率追加，封顶 30 分钟；大小未知→0（无限） */
    private fun adaptiveReadTimeout(bytes: Long): Int =
        if (bytes <= 0) 0
        else (300_000L + bytes / (256 * 1024) * 1000L).coerceAtMost(30L * 60 * 1000).toInt()

    /** 把原生上传进度推给网页（网页有 JC_UPLOAD_HOOK 才走，没加载则短路） */
    private fun pushUploadEventToWeb(
        ev: String,
        name: String,
        done: Long,
        total: Long,
        text: String? = null,
        ok: Boolean? = null
    ) {
        val sb = StringBuilder("""{"ev":"$ev","name":${JSONObject.quote(name)},"done":$done,"total":$total""")
        if (text != null) sb.append(""","text":${JSONObject.quote(text)}""")
        if (ok != null) sb.append(""","ok":$ok""")
        sb.append("}")
        val json = sb.toString()
        runOnUiThread {
            webView.evaluateJavascript("window.JC_UPLOAD_HOOK && window.JC_UPLOAD_HOOK($json);", null)
        }
    }

    /** 只改底部卡片上的那行字（统计/打包阶段的文案） */
    private fun pushUploadText(text: String) {
        val json = """{"ev":"text","text":${JSONObject.quote(text)}}"""
        runOnUiThread {
            webView.evaluateJavascript("window.JC_UPLOAD_HOOK && window.JC_UPLOAD_HOOK($json);", null)
        }
    }

    private fun uploadUri(uri: Uri) {
        val ui = TransferUi(this)
        acquireUploadLocks()
        thread {
            try {
                val name = sanitizeFileName(queryName(uri) ?: "file_${System.currentTimeMillis()}")
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val size = querySize(uri) ?: -1L
                ui.show(name, size)

                val url = "$serverUrl/api/upload?name=" + URLEncoder.encode(name, "UTF-8") +
                        "&mime=" + URLEncoder.encode(mime, "UTF-8") +
                        "&client=" + URLEncoder.encode(clientId, "UTF-8")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = adaptiveReadTimeout(size)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                // 关键：setFixedLengthStreamingMode 禁用 HttpURLConnection 内部缓冲，
                // 否则整个 body 会先攒在内存里 → 大文件 OOM
                if (size > 0) conn.setFixedLengthStreamingMode(size)
                else conn.setChunkedStreamingMode(65536)
                val cookie = CookieManager.getInstance().getCookie(serverUrl)
                if (cookie != null) conn.setRequestProperty("Cookie", cookie)

                var sent = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    conn.outputStream.use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            if (ui.cancelled) {
                                conn.disconnect()
                                throw CancellationException()
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            ui.progress(sent, if (size > 0) size else sent)
                        }
                    }
                }
                val code = conn.responseCode
                val ok = code in 200..299
                ui.finish(ok, if (ok) "「$name」已发送到电脑" else "发送失败($code)")
            } catch (e: CancellationException) {
                ui.finish(false, "已取消")
            } catch (e: Exception) {
                ui.finish(false, "发送失败：${e.message}")
            } finally {
                releaseUploadLocks()
            }
        }
    }

    /**
     * 上传期间持锁，防止息屏 Doze 掐断大文件长传。
     * 复用服务端模式同款策略（PARTIAL_WAKE_LOCK + WifiLock），但用独立的标签和实例，
     * 上传结束立即释放，避免和服务端锁互相干扰。
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireUploadLocks() {
        synchronized(this) {
            uploadLockRefs++
            if (uploadWakeLock != null) return
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                uploadWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jianchuan:upload").apply {
                    setReferenceCounted(false)
                    acquire(60L * 60 * 1000)
                }
            } catch (_: Throwable) {
            }
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                uploadWifiLock = wm.createWifiLock(mode, "jianchuan:upload").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun releaseUploadLocks() {
        synchronized(this) {
            uploadLockRefs--
            if (uploadLockRefs > 0) return
            uploadLockRefs = 0
            try { uploadWakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
            uploadWakeLock = null
            try { uploadWifiLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
            uploadWifiLock = null
        }
    }

    private fun queryName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0)
        }
        return name
    }

    // 选文件夹 → 打包 zip → 发送（三阶段进度：统计 → 打包 → 上传）
    private fun packFolderAndSend(treeUri: Uri) {
        val ui = TransferUi(this)
        thread {
            try {
                ui.show("", -1, "正在统计文件…")
                val doc = DocumentFile.fromTreeUri(this, treeUri) ?: return@thread
                val folderName = sanitizeFileName(doc.name ?: "folder")

                val (count, totalSize) = countTree(doc) { n, s ->
                    ui.phase("正在统计…已发现 $n 个文件 / ${fmtBytes(s)}")
                    if (ui.cancelled) throw CancellationException()
                }
                if (count == 0) {
                    ui.finish(false, "「$folderName」是空文件夹")
                    return@thread
                }
                if (count > MAX_ZIP_FILE_COUNT) {
                    ui.finish(false, "「$folderName」有 $count 个文件，超过上限 $MAX_ZIP_FILE_COUNT，请分小一点")
                    return@thread
                }
                if (totalSize > MAX_ZIP_TOTAL_BYTES) {
                    ui.finish(false, "「$folderName」共 ${fmtBytes(totalSize)}，超过上限 ${fmtBytes(MAX_ZIP_TOTAL_BYTES)}，请分小一点")
                    return@thread
                }
                val avail = StatFs(cacheDir.absolutePath).availableBytes
                if (avail < totalSize + CACHE_MARGIN_BYTES) {
                    ui.finish(false, "手机存储空间不足：打包约需 ${fmtBytes(totalSize)}，缓存可用仅 ${fmtBytes(avail)}，请清理后重试")
                    return@thread
                }

                ui.show("$folderName.zip", totalSize)
                ui.phase("正在打包「$folderName.zip」")
                val zipFile = File(cacheDir, "${sanitizeFileName(folderName)}.zip")
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile), 1 shl 16)).use { zos ->
                    packTree(doc, zos, "", totalSize) { done ->
                        ui.progress(done, totalSize)
                        if (ui.cancelled) throw CancellationException()
                    }
                }
                if (ui.cancelled) {
                    zipFile.delete()
                    ui.finish(false, "已取消")
                    return@thread
                }
                ui.phase("正在上传（0 / ${fmtBytes(zipFile.length())}）")
                uploadLocalFile(zipFile, "${folderName}.zip", "application/zip", ui)
            } catch (e: CancellationException) {
                runOnUiThread { Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show() }
                ui.finish(false, "已取消")
            } catch (e: Exception) {
                ui.finish(false, "失败：${e.message}")
            }
        }
    }

    // 递归统计文件夹里文件数和总大小（用栈避免深层递归栈溢出），带进度回调
    private fun countTree(doc: DocumentFile, onProgress: (Int, Long) -> Unit): Pair<Int, Long> {
        var count = 0
        var size = 0L
        val stack = ArrayDeque<DocumentFile>()
        stack.add(doc)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (child in d.listFiles()) {
                if (child.isDirectory) stack.add(child)
                else if (child.isFile) {
                    count++
                    size += child.length()
                    onProgress(count, size)
                }
            }
            if (count > 20001) break   // 扫描安全上限，不是业务限制（业务限制是 MAX_ZIP_FILE_COUNT）
        }
        return count to size
    }

    private fun fmtBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1048576 -> "%.1f KB".format(n / 1024.0)
        n < 1073741824 -> "%.1f MB".format(n / 1048576.0)
        else -> "%.2f GB".format(n / 1073741824.0)
    }

    private fun packTree(
        doc: DocumentFile?,
        zos: ZipOutputStream,
        base: String,
        totalSize: Long,
        onBytes: (Long) -> Unit
    ): Long {
        doc ?: return 0L
        var copied = 0L
        for (child in doc.listFiles()) {
            if (child.isDirectory) {
                copied += packTree(child, zos, "$base${child.name}/", totalSize, onBytes)
            } else if (child.isFile) {
                try {
                    zos.putNextEntry(ZipEntry("$base${child.name}"))
                    val n = contentResolver.openInputStream(child.uri)?.use { it.copyTo(zos, 65536) } ?: 0L
                    copied += n
                    zos.closeEntry()
                    onBytes(copied)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Exception) {
                }
            }
        }
        return copied
    }

    private fun uploadLocalFile(file: File, name: String, mime: String, ui: TransferUi? = null) {
        acquireUploadLocks()
        thread {
            try {
                val fileSize = file.length()
                ui?.show(name, fileSize)
                if (ui == null) pushUploadEventToWeb("start", name, 0L, fileSize)

                val url = "$serverUrl/api/upload?name=" + URLEncoder.encode(name, "UTF-8") +
                        "&mime=" + URLEncoder.encode(mime, "UTF-8") +
                        "&client=" + URLEncoder.encode(clientId, "UTF-8")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = adaptiveReadTimeout(fileSize)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                // 流式上传：不把整个 zip 读进内存，避免大文件夹 OOM 闪退
                conn.setFixedLengthStreamingMode(fileSize)
                val cookie = CookieManager.getInstance().getCookie(serverUrl)
                if (cookie != null) conn.setRequestProperty("Cookie", cookie)

                var sent = 0L
                FileInputStream(file).use { input ->
                    conn.outputStream.use { output ->
                        val buf = ByteArray(65536)
                        while (true) {
                            if (ui?.cancelled == true) {
                                conn.disconnect()
                                throw CancellationException()
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            sent += n
                            if (ui != null) ui.progress(sent, fileSize)
                            else pushUploadEventToWeb("progress", name, sent, fileSize)
                        }
                    }
                }
                val code = conn.responseCode
                val ok = code in 200..299
                if (ui != null) {
                    ui.finish(ok, if (ok) "「$name」已发送到电脑" else "发送失败($code)")
                } else {
                    pushUploadEventToWeb(
                        "end", name, sent, fileSize,
                        text = if (ok) "「$name」已发送到电脑" else "「$name」发送失败($code)",
                        ok = ok
                    )
                }
            } catch (e: CancellationException) {
                ui?.finish(false, "已取消")
            } catch (e: Exception) {
                if (ui != null) {
                    ui.finish(false, "发送失败：${e.message}")
                } else {
                    runOnUiThread { Toast.makeText(this, "发送失败：${e.message}", Toast.LENGTH_SHORT).show() }
                }
            } finally {
                // 无论成败都清掉临时 zip，防 cacheDir 越堆越多
                try { file.delete() } catch (_: Exception) {}
                releaseUploadLocks()
            }
        }
    }

    // ---------------------------------------------------------------- 设备发现
    private fun startDiscover() {
        // 心跳失败、换网回调、页面加载失败三路都会调到这里，
        // 不加闸门会同时跑好几轮「UDP 4 秒 + TCP 扫 254 个 IP 6 秒」，
        // 不但互相抢带宽，还会各自弹一次输入框。
        if (discovering) return
        discovering = true
        runOnUiThread { status.text = "正在搜索同一 WiFi 下的电脑…" }
        thread {
            val targets = buildBroadcastAddresses()
            var found = false
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 300
                    val msg = "JC_DISCOVER".toByteArray()
                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + 4000
                    var lastSend = 0L
                    while (System.currentTimeMillis() < deadline && !found) {
                        val now = System.currentTimeMillis()
                        if (now - lastSend >= 700) {
                            for (t in targets) {
                                try {
                                    socket.send(DatagramPacket(msg, msg.size,
                                        InetAddress.getByName(t), 8788))
                                } catch (_: Exception) {
                                }
                            }
                            lastSend = now
                        }
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            socket.receive(pkt)
                            val j = JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                            if (j.has("port") || j.optString("app").contains("简传")) {
                                val port = j.optInt("port", 8787)
                                val ip = j.optString("ip").ifBlank { pkt.address.hostAddress ?: "" }
                                runOnUiThread { loadServer("http://$ip:$port") }
                                found = true
                            }
                        } catch (_: SocketTimeoutException) {
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (!found) {
                // UDP 广播没找到（很多路由器会丢广播包），改用 TCP 扫描兜底：
                // 收发文件正常说明手机和电脑的 TCP 是通的，逐 IP 扫 8787 端口一定能找到
                runOnUiThread { status.text = "正在扫描局域网…" }
                tcpScanLan()
            }
            discovering = false
        }
    }

    // 取打分最高的 ≤2 个候选的 /24 前缀，用于 TCP 兜底扫描
    private fun lanScanPrefixes(): List<String> {
        return localIpCandidates()
            .take(2)
            .map { c -> c.ip.split(".").take(3).joinToString(".") }
            .filter { it.isNotBlank() }
            .distinct()
    }

    // TCP 扫描当前网段，找监听 8787 端口的电脑（UDP 广播失败后的兜底）
    private fun tcpScanLan() {
        val prefixes = lanScanPrefixes()
        if (prefixes.isEmpty()) {
            showNotFound()
            return
        }
        val found = AtomicBoolean(false)
        thread {
            for (prefix in prefixes) {
                if (found.get()) break
                scanPrefix(prefix, found)
            }
            if (!found.get()) showNotFound()
        }
    }

    private fun scanPrefix(prefix: String, found: AtomicBoolean) {
        val pool = Executors.newFixedThreadPool(32)
        val latch = CountDownLatch(254)
        for (i in 1..254) {
            pool.execute {
                if (!found.get()) {
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress("$prefix.$i", 8787), 300)
                            if (found.compareAndSet(false, true)) {
                                val url = "http://$prefix.$i:8787"
                                runOnUiThread { loadServer(url) }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                latch.countDown()
            }
        }
        pool.shutdown()
        try { latch.await(6, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    /**
     * 搜索失败时的处理。
     *
     * 关键：**不能无条件弹手动输入框**。UDP 广播被路由器丢掉、TCP 扫描 254 个地址超时，
     * 都会走到这里，但电脑往往还开着 —— 一弹框用户就以为必须重新输地址
     * （用户实测：「和电脑连着呢也弹」）。
     * 所以先探一次当前地址，通就当已连接；真不通才弹框，并挂一个后台静默重试，
     * 电脑重新打开后自动连回去、自动收框。
     */
    private fun showNotFound() {
        thread(name = "notfound-probe") {
            val cur = serverUrl
            if (cur.isNotBlank() && probeServer(cur)) {
                runOnUiThread {
                    manualBox.visibility = View.GONE
                    status.text = "已连接 · ${currentModeLabel()}"
                    if (pageBroken) {
                        pageBroken = false
                        webView.loadUrl(targetUrl())
                    }
                }
                return@thread
            }
            runOnUiThread {
                // 找不到电脑只是「这次没连上」，不该拿一整块面板压住界面。
                // 一行状态条点一下就够，抽屉等用户自己点。
                status.text = "没找到电脑"
                markDisconnected("没找到电脑")
                startSilentReconnect()
            }
        }
    }

    /**
     * 手动输入框弹出后，后台每 3 秒静默试一次上次连过的地址。
     * 电脑（exe）重新打开后自动连回去并收起输入框，用户不用再点「连接」。
     */
    private fun startSilentReconnect() {
        if (silentRetryRunning) return
        silentRetryRunning = true
        thread(name = "silent-reconnect") {
            while (silentRetryRunning) {
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!silentRetryRunning) break
                val sp = getSharedPreferences("jc", MODE_PRIVATE)
                val cands = if (publicMode) {
                    listOf(sp.getString("pub_url", "") ?: "")
                } else {
                    listOf(serverUrl, sp.getString("lan_url", "") ?: "")
                }.filter { it.isNotBlank() }.distinct()
                val hit = cands.firstOrNull { probeServer(it) }
                if (hit != null) {
                    silentRetryRunning = false
                    runOnUiThread {
                        manualBox.visibility = View.GONE
                        loadServer(hit)
                    }
                    break
                }
            }
        }
    }

    private fun buildBroadcastAddresses(): List<String> {
        val set = LinkedHashSet<String>()
        set.add("255.255.255.255")
        for (c in localIpCandidates()) {
            // 接口自己的真实广播地址（最准）
            c.broadcast?.let { set.add(it) }
            // /24 与 /16 定向广播兜底（prefixLen 不可信时也覆盖到）
            val p = c.ip.split(".")
            if (p.size == 4) {
                set.add("${p[0]}.${p[1]}.${p[2]}.255")
                set.add("${p[0]}.${p[1]}.255.255")
            }
        }
        return set.toList()
    }

    // ---------------------------------------------------------------- 网页桥
    inner class AndroidBridge {
        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("简传Lite", text))
                Toast.makeText(this@MainActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun setClipboard(text: String) {
            // 网页轮询到电脑发来的剪贴板内容时调这里（局域网通道的落地端）
            this@MainActivity.onClipFromPeer(text, "网页")
        }

        /**
         * 网页点视频时调这里，跳到原生播放器（libVLC）。
         *
         * listJson 是当前目录的视频列表（绝对地址），原生端靠它做选集和自动下一集；
         * index 是点中的那个。传字符串而不是拆成多个参数，是因为播放列表可能几十条。
         */
        @JavascriptInterface
        fun openPlayer(listJson: String, index: Int) {
            runOnUiThread { PlayerActivity.start(this@MainActivity, listJson, index) }
        }

        @JavascriptInterface
        fun setClientId(cid: String) {
            if (cid.isNotBlank()) clientId = cid
        }

        @JavascriptInterface
        fun pickFiles() {
            // 必须用 this@MainActivity 显式调用外层方法，
            // 否则 Kotlin 在内部类作用域里解析到自己 → 无限递归 → StackOverflowError 闪退
            runOnUiThread { this@MainActivity.pickFiles() }
        }

        @JavascriptInterface
        fun pickImage() {
            runOnUiThread { this@MainActivity.openGallery() }
        }

        @JavascriptInterface
        fun pickFolder() {
            runOnUiThread { this@MainActivity.folderLauncher.launch(null) }
        }

        @JavascriptInterface
        fun saveFile(url: String, name: String, mime: String) {
            this@MainActivity.saveFile(url, name, mime)
        }

        @JavascriptInterface
        fun openFile(url: String, name: String, mime: String) {
            // 文件气泡右侧那个按钮：手机上应该是「打开」而不是「下载」。
            // 收到的文件 App 早就在后台存进 下载/简传Lite 了，本地有就直接开，
            // 没有（历史消息 / 用户清过下载目录）才现下，下完自动打开。
            runOnUiThread { this@MainActivity.openFileOnPhone(url, name, mime) }
        }

        @JavascriptInterface
        fun revealFile(name: String) {
            // 手机上点文件旁的文件夹图标：打开本机的 下载/简传Lite 目录，
            // 而不是去调电脑端的接口（那样会在电脑桌面弹窗，很奇怪）
            runOnUiThread { this@MainActivity.openFolderOnPhone(name) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 返回键先收抽屉，再退网页历史 —— 否则用户按返回是想关面板，结果页面倒退了
        if (::connDrawer.isInitialized && connDrawer.visibility == View.VISIBLE) {
            closeDrawer(true)
            return
        }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        heartbeatRunning = false
        clipWatchRunning = false
        clipThread?.interrupt()
        clipThread = null
        uiHandler.removeCallbacksAndMessages(null)
        // onCreate 中途出异常时这两个可能还没建起来，别在退出路径上再崩一次
        if (::bleClip.isInitialized) runCatching { bleClip.stop() }
        if (::mobileServer.isInitialized) runCatching { mobileServer.stop() }
        super.onDestroy()
    }

    /**
     * 传输进度：不再弹原生对话框。
     * App 里的页面底部本来就有一条进度卡（网页 #nativeProg，靠 JC_UPLOAD_HOOK 推），
     * 再叠一个盖住整页的 AlertDialog 既挡视野又重复播报，用户点不着下面任何东西。
     * 这个类现在只干三件事：把阶段文案/进度推给网页、保持屏幕常亮、失败时补一句 Toast。
     * 选文件夹的「统计 → 打包 → 上传」三段共用一个实例，文案随阶段走。
     */
    private class TransferUi(private val activity: MainActivity) {
        private var name = ""
        private var total = -1L
        private val lastProg = java.util.concurrent.atomic.AtomicLong(0)
        private val lastText = java.util.concurrent.atomic.AtomicLong(0)
        private var keepOn = false

        @Volatile
        var cancelled = false
            private set

        /**
         * 开一个阶段。startText 非空时只改文案（统计阶段没有文件名，也没有百分比），
         * 否则发 start 事件，把卡片标题换成「正在发送「name」」并把进度归零。
         */
        fun show(uploadName: String, totalBytes: Long, startText: String? = null) {
            name = uploadName
            total = totalBytes
            activity.runOnUiThread {
                if (!keepOn) {
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    keepOn = true
                }
            }
            if (startText != null) activity.pushUploadText(startText)
            else activity.pushUploadEventToWeb("start", uploadName, 0L, totalBytes)
        }

        /** 阶段文案。统计时每发现一个文件回调一次，不节流会把 UI 线程打爆 */
        fun phase(text: String) {
            val now = System.currentTimeMillis()
            val last = lastText.get()
            if (now - last < TEXT_MIN_INTERVAL_MS) return
            if (!lastText.compareAndSet(last, now)) return
            activity.pushUploadText(text)
        }

        fun progress(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            val last = lastProg.get()
            if (now - last < PROGRESS_MIN_INTERVAL_MS) return
            if (!lastProg.compareAndSet(last, now)) return
            activity.pushUploadEventToWeb("progress", name, done, total)
        }

        /** 收尾：关掉常亮、让卡片自己淡出。ok=false 才补 Toast（成功时页面里已经看得见） */
        fun finish(ok: Boolean, text: String) {
            activity.runOnUiThread {
                if (keepOn) {
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    keepOn = false
                }
            }
            activity.pushUploadEventToWeb("end", name, 0L, total, text = text, ok = ok)
            if (!ok) {
                activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** 文件名消毒：剥路径分隔符/控制字符/首部点号，截 180。与 MobileServer.safeFileName 同规则。 */
    private fun sanitizeFileName(raw: String): String {
        var s = raw.replace('\\', '/').substringAfterLast('/')
        s = s.filter { !it.isISOControl() }.trim().trimStart('.')
        if (s.isBlank()) s = "file"
        return s.take(180)
    }

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_FILE = 1002
        private const val REQ_CAMERA = 1003
        private const val REQ_STORAGE = 1004
        private const val REQ_BT = 1005

        // 打包上限：970MB 照片文件夹常超 500 个文件，放到 2000；总大小 1.5GB 保持不变
        private const val MAX_ZIP_FILE_COUNT = 2000
        private const val MAX_ZIP_TOTAL_BYTES = 1536L * 1024 * 1024
        private const val PROGRESS_MIN_INTERVAL_MS = 200L
        // 统计阶段每发现一个文件就回调一次，文案（文件数/大小）要跟着涨但不能每文件都过一遍 JS
        private const val TEXT_MIN_INTERVAL_MS = 180L
        private const val CACHE_MARGIN_BYTES = 64L * 1024 * 1024

        /** SharedPreferences 里记「上次停留的页面地址」，App 重开时恢复 */
        private const val LAST_URL = "last_url"
    }
}

// ============================================================ 主题配色
/**
 * 手机原生界面的五套配色，和网页里那五套一一对应（晨雾 / 薄荷 / 橘霞 / 夜雨 / 暮紫）。
 * 网页靠 CSS 变量，原生这边没有这套机制，所以把颜色收成数据对象，
 * 切主题时逐个 View 刷一遍 —— 比 setTheme + recreate 温和得多，
 * 不会把 WebView、服务端、蓝牙连接全重建一次。
 */
data class Palette(
    val id: String,
    val name: String,
    val topbar: Int,
    val card: Int,
    val card2: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val brand: Int,
    val line: Int,
    val ok: Int,
    val input: Int,
    val dark: Boolean
)

val PALETTES = listOf(
    Palette(
        "light", "晨雾",
        0xFFEBF2F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFF1F6FB.toInt(),
        0xFF15222E.toInt(), 0xFF51667A.toInt(), 0xFF8296A6.toInt(),
        0xFF2F7FB8.toInt(), 0xFFD5E2ED.toInt(), 0xFF12B886.toInt(),
        0xFFF4F8FC.toInt(), false
    ),
    Palette(
        "mint", "薄荷",
        0xFFE4F4EE.toInt(), 0xFFFFFFFF.toInt(), 0xFFEFF9F5.toInt(),
        0xFF0F3129.toInt(), 0xFF476B61.toInt(), 0xFF7B9A91.toInt(),
        0xFF0F9E8E.toInt(), 0xFFC6EADE.toInt(), 0xFF0EA472.toInt(),
        0xFFF1FAF6.toInt(), false
    ),
    Palette(
        "sunset", "橘霞",
        0xFFFCEBE3.toInt(), 0xFFFFFDFC.toInt(), 0xFFFDF2EC.toInt(),
        0xFF43241A.toInt(), 0xFF6F4A3C.toInt(), 0xFFA3796A.toInt(),
        0xFFEF6A3D.toInt(), 0xFFFFDED1.toInt(), 0xFF12B886.toInt(),
        0xFFFDF5F1.toInt(), false
    ),
    Palette(
        "dark", "夜雨",
        0xFF0E1A25.toInt(), 0xFF16242F.toInt(), 0xFF1B2C3A.toInt(),
        0xFFE4EEF7.toInt(), 0xFF9CB1C3.toInt(), 0xFF6D8398.toInt(),
        0xFF63B3EA.toInt(), 0xFF2A4157.toInt(), 0xFF3DDC97.toInt(),
        0xFF16242F.toInt(), true
    ),
    Palette(
        "dusk", "暮紫",
        0xFF17112A.toInt(), 0xFF221838.toInt(), 0xFF2A1F42.toInt(),
        0xFFEEE6FA.toInt(), 0xFFB3A4C9.toInt(), 0xFF84769B.toInt(),
        0xFFA78BFA.toInt(), 0xFF3A2D57.toInt(), 0xFF5FD98F.toInt(),
        0xFF221838.toInt(), true
    )
)
