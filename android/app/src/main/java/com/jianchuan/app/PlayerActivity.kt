package com.jianchuan.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 原生播放器。
 *
 * 为什么要有它：网页里的 `<video>` 放不了 MKV，更没法切音轨、渲染 ASS 字幕。
 * 用户要的是「和自己那个媒体播放器一样」，所以这里直接用 libVLC —— 和投影仪
 * 上那个播放器同一个内核，行为完全一致（含 ASS 特效字幕的原样渲染）。
 *
 * 数据从网页传进来（`AndroidBridge.openPlayer`），是一个 JSON 数组：
 * `[{name, url, path}, ...]`。url 是绝对地址，path 是手机或电脑上的原始路径，
 * 用来做播放进度记忆的 key。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LIST = "play_list"
        const val EXTRA_INDEX = "play_index"
        private const val PREF = "play"

        /** libvlc_media_slave_type_subtitle：addSlave 的第一个参数是裸 int，库里没有常量类 */
        private const val SLAVE_SUBTITLE = 0

        /** 从网页调起播放器。listJson 见类注释 */
        fun start(ctx: Context, listJson: String, index: Int) {
            try {
                ctx.startActivity(
                    Intent(ctx, PlayerActivity::class.java)
                        .putExtra(EXTRA_LIST, listJson)
                        .putExtra(EXTRA_INDEX, index)
                )
            } catch (_: Throwable) {
            }
        }
    }

    private data class Item(val name: String, val url: String, val path: String)

    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var video: VLCVideoLayout
    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleTv: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var curTv: TextView
    private lateinit var totalTv: TextView
    private lateinit var playTv: TextView
    private lateinit var bigPlay: TextView
    private lateinit var rateTv: TextView

    private var items: List<Item> = emptyList()
    private var index = 0
    private var lengthMs = 0L
    private var pendingSeek = 0L
    private var pendingSeekChecked = false
    private var dragging = false
    private var controlsShown = true
    private var rateIdx = 1                      // RATES 的下标，默认 1.0×
    private var restoredFrom = 0L

    private val rates = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val ui = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { setControls(false) }
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREF, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 视频横着看舒服，但不锁死：手指把手机转回来还是要能竖着看
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        // 铺到状态栏和导航栏下面，不然全屏黑底里会露出两条系统色
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        root = findViewById(R.id.p_root)
        video = findViewById(R.id.p_video)
        topBar = findViewById(R.id.p_top)
        bottomBar = findViewById(R.id.p_bottom)
        titleTv = findViewById(R.id.p_title)
        seekBar = findViewById(R.id.p_seek)
        curTv = findViewById(R.id.p_cur)
        totalTv = findViewById(R.id.p_total)
        playTv = findViewById(R.id.p_play)
        bigPlay = findViewById(R.id.p_bigplay)
        rateTv = findViewById(R.id.p_rate)

        readList()
        if (items.isEmpty()) {
            Toast.makeText(this, "没有可播放的视频", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // --avcodec-hw=mediacodec 必须显式给：不看这条时 libVLC 会用软解，
        // 1080p 勉强、4K 直接卡成幻灯片（投影仪那个项目踩过一模一样的坑）。
        libVlc = LibVLC(
            this, arrayListOf(
                "--avcodec-hw=mediacodec",
                "--audio-time-stretch",       // 变速不变调
                "--network-caching=1500",
                "--no-drop-late-frames",
                "--no-skip-frames"
            )
        )
        player = MediaPlayer(libVlc)
        // 第三个参数 true = 让 VLC 自己把字幕画到画面上（ASS 特效靠它）
        player.attachViews(video, null, true, false)
        player.setEventListener { ev -> onPlayerEvent(ev) }

        bindControls()
        bindGestures()
        play(index)
    }

    // ------------------------------------------------------------ 数据

    private fun readList() {
        val raw = intent.getStringExtra(EXTRA_LIST) ?: ""
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        val list = ArrayList<Item>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                list.add(
                    Item(
                        o.optString("name", "视频"),
                        url,
                        o.optString("path")
                    )
                )
            }
        } catch (_: Throwable) {
        }
        items = list
        if (index !in items.indices) index = 0
    }

    // ------------------------------------------------------------ 播放

    private fun play(i: Int) {
        if (i !in items.indices) return
        saveProgress()
        index = i
        val it0 = items[index]
        titleTv.text = it0.name
        rateIdx = 1
        rateTv.text = "1.0×"
        seekBar.progress = 0
        curTv.text = "00:00"
        totalTv.text = "00:00"
        lengthMs = 0
        pendingSeekChecked = false
        pendingSeek = readProgress(it0.path)
        restoredFrom = pendingSeek

        val media = Media(libVlc, Uri.parse(it0.url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=2000")
            addOption(":file-caching=1500")
            // 让 VLC 把整段字幕轨都读出来再决定显示哪条，
            // 否则开头几秒常常是空的（ASS 尤其明显）
            addOption(":sub-track=0")
        }
        player.media = media
        media.release()
        player.play()

        // 外挂字幕要和视频同一个 origin，服务端模式是 127.0.0.1，
        // 连电脑时是电脑的地址，所以从视频 URL 里推
        loadExternalSubs(it0)
        setControls(true)
    }

    private fun onPlayerEvent(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                playTv.text = "❚❚"
                bigPlay.visibility = View.GONE
                scheduleHide()
                // 轨道要等 Playing 之后才齐，早一点去取会是空的
                ui.postDelayed({ pickChineseSubtitle(); applyRestore() }, 420)
            }

            MediaPlayer.Event.Paused -> {
                playTv.text = "▶"
                bigPlay.visibility = View.VISIBLE
                setControls(true)
                ui.removeCallbacks(hideTask)
                saveProgress()
            }

            MediaPlayer.Event.EndReached -> {
                // 播放位置清零，免得下一集开场从这个进度跳进来
                saveProgress(forceZero = true)
                nextEpisode(auto = true)
            }

            MediaPlayer.Event.EncounteredError -> {
                Toast.makeText(this, "这个文件放不了（编码可能不支持）", Toast.LENGTH_LONG).show()
            }

            MediaPlayer.Event.LengthChanged -> {
                lengthMs = ev.lengthChanged
                if (lengthMs > 0) totalTv.text = fmt(lengthMs)
            }

            MediaPlayer.Event.TimeChanged -> {
                if (!dragging && lengthMs > 0) {
                    seekBar.progress = (ev.timeChanged * 1000 / lengthMs).toInt().coerceIn(0, 1000)
                    curTv.text = fmt(ev.timeChanged)
                }
            }

            MediaPlayer.Event.Buffering -> {
                if (ev.buffering >= 100f) bigPlay.visibility = View.GONE
            }
        }
    }

    /** 有记忆就从记忆处接着放。记忆点已经到片尾 30 秒内的，视为看完了，从头来 */
    private fun applyRestore() {
        if (pendingSeekChecked) return
        pendingSeekChecked = true
        val p = pendingSeek
        if (p <= 5000 || lengthMs <= 0) return
        if (p > lengthMs - 30_000) {
            restoredFrom = 0
            return
        }
        player.time = p
        toastBrief("已从 ${fmt(p)} 继续")
    }

    private fun nextEpisode(auto: Boolean) {
        if (index + 1 <= items.lastIndex) {
            play(index + 1)
            if (auto) toastBrief("下一集：" + items[index].name)
        } else if (auto) {
            toastBrief("已经是最后一个了")
            setControls(true)
        }
    }

    // ------------------------------------------------------------ 字幕

    /**
     * 外挂字幕：问服务端同目录下有没有同名（或前缀相同）的字幕文件。
     *
     * 为什么不在前端算：分类页（图片/视频/音乐）里根本看不到 .srt 文件，
     * 前端拿不到这份信息，只能让服务端去翻目录。
     */
    private fun loadExternalSubs(it0: Item) {
        val path = it0.path
        if (path.isBlank()) return
        val origin = originOf(it0.url) ?: return
        thread(name = "subs") {
            val subs = try {
                val u = URL("$origin/api/subs?path=" + URLEncoder.encode(path, "UTF-8"))
                val c = u.openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val text = c.inputStream.bufferedReader().use { r -> r.readText() }
                c.disconnect()
                JSONArray(text)
            } catch (_: Throwable) {
                null
            } ?: return@thread
            if (subs.length() == 0) return@thread
            ui.post {
                if (isFinishing || isDestroyed) return@post
                for (i in 0 until subs.length()) {
                    val rel = subs.optString(i)
                    if (rel.isBlank()) continue
                    try {
                        // 中文优先选中：字幕组通常在文件名里写 chs/cht/zh/sc/tc
                        val lower = rel.lowercase(Locale.ROOT)
                        val zh = lower.contains("chs") || lower.contains("cht") ||
                            lower.contains("zh") || lower.contains("sc.") ||
                            lower.contains("tc.") || lower.contains("中")
                        player.addSlave(SLAVE_SUBTITLE, Uri.parse(origin + rel), zh)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /**
     * 内嵌字幕里挑中文轨。
     *
     * libVLC 的轨道描述只给 name/language 两个字段，name 常常是
     * 「Track 1 - [Chinese]」这种，所以按关键字匹配，匹配不到就保持 VLC 自己的选择。
     */
    private fun pickChineseSubtitle() {
        try {
            val desc = player.spuTracks ?: return
            if (desc.isEmpty()) return
            val zhKeys = arrayOf("chinese", "chi", "zho", "zh", "chs", "cht", "中文", "简体", "繁體", "繁体")
            for (d in desc) {
                val n = (d.name ?: "").lowercase(Locale.ROOT)
                if (zhKeys.any { n.contains(it) }) {
                    // id 为 -1 时是「关闭字幕」，跳过
                    if (d.id >= 0) player.setSpuTrack(d.id)
                    return
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun showSubtitleMenu() {
        val desc = try {
            player.spuTracks
        } catch (_: Throwable) {
            null
        }
        val names = ArrayList<String>()
        val ids = ArrayList<Int>()
        names.add("关闭字幕")
        ids.add(-1)
        if (desc != null) {
            for (d in desc) {
                if (d.id < 0) continue
                names.add(d.name ?: "字幕 ${d.id}")
                ids.add(d.id)
            }
        }
        if (names.size == 1) {
            Toast.makeText(this, "这个视频没有内嵌字幕", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("字幕")
            .setItems(names.toTypedArray()) { _, which ->
                try {
                    player.setSpuTrack(ids[which])
                } catch (_: Throwable) {
                }
            }
            .show()
    }

    private fun showAudioMenu() {
        val desc = try {
            player.audioTracks
        } catch (_: Throwable) {
            null
        }
        if (desc == null || desc.size <= 1) {
            Toast.makeText(this, "这个视频只有一条音轨", Toast.LENGTH_SHORT).show()
            return
        }
        val names = desc.map { it.name ?: "音轨 ${it.id}" }.toTypedArray()
        val ids = desc.map { it.id }
        AlertDialog.Builder(this)
            .setTitle("音轨")
            .setSingleChoiceItems(names, -1) { d, which ->
                try {
                    player.setAudioTrack(ids[which])
                } catch (_: Throwable) {
                }
                d.dismiss()
            }
            .show()
    }

    private fun showEpisodeMenu() {
        if (items.size <= 1) {
            Toast.makeText(this, "这个目录里只有这一个视频", Toast.LENGTH_SHORT).show()
            return
        }
        val names = items.mapIndexed { i, it0 ->
            (if (i == index) "▶ " else "   ") + it0.name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选集 · ${items.size} 个")
            .setItems(names) { _, which -> play(which) }
            .show()
    }

    // ------------------------------------------------------------ 控件

    private fun bindControls() {
        findViewById<ImageButton>(R.id.p_back).setOnClickListener { finish() }
        findViewById<View>(R.id.p_sub).setOnClickListener { showSubtitleMenu() }
        findViewById<View>(R.id.p_aud).setOnClickListener { showAudioMenu() }
        findViewById<View>(R.id.p_list).setOnClickListener { showEpisodeMenu() }
        findViewById<View>(R.id.p_share).setOnClickListener { copyPlayUrl() }
        findViewById<View>(R.id.p_dl).setOnClickListener { onDownloadTap() }

        playTv.setOnClickListener { togglePlay() }
        bigPlay.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.p_back10).setOnClickListener { seekBy(-10_000) }
        findViewById<View>(R.id.p_fwd10).setOnClickListener { seekBy(10_000) }
        rateTv.setOnClickListener {
            rateIdx = (rateIdx + 1) % rates.size
            val r = rates[rateIdx]
            try {
                player.rate = r
            } catch (_: Throwable) {
            }
            rateTv.text = String.format(Locale.US, "%.2f×", r).replace(".00", ".0")
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && lengthMs > 0) curTv.text = fmt(lengthMs * p / 1000)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
                ui.removeCallbacks(hideTask)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                if (lengthMs > 0) player.time = lengthMs * sb.progress / 1000
                scheduleHide()
            }
        })
    }

    private fun togglePlay() {
        try {
            if (player.isPlaying) player.pause() else player.play()
        } catch (_: Throwable) {
        }
        setControls(true)
    }

    private fun seekBy(ms: Long) {
        try {
            val t = (player.time + ms).coerceAtLeast(0)
            player.time = if (lengthMs > 0) t.coerceAtMost(lengthMs - 1000) else t
            toastBrief(if (ms < 0) "-10 秒" else "+10 秒")
        } catch (_: Throwable) {
        }
        setControls(true)
    }

    /**
     * 播放器内的「下载」。
     *
     * 服务端模式下文件本来就在这台手机上（播放器播的就是本机文件），
     * 再下载一遍是多余的；只有连电脑看的时候，视频才真的在电脑那头。
     */
    /**
     * 复制播放地址 —— 跨屏观看的入口。
     *
     * App 内 WebView 的 origin 是 http://127.0.0.1:8788，直接抄这个地址，
     * 投影仪 / 电视会去连它自己，永远播不出来。所以必须换成局域网 IP。
     * 给的是 items[i].url（带 inline=1）：保留真实 MIME，播放器认得出是视频；
     * 不带 inline 的地址会被服务端压成 octet-stream（那是给下载用的）。
     */
    private fun copyPlayUrl() {
        val cur = items.getOrNull(index) ?: return
        val ip = MobileServer.lanIps().firstOrNull()
        val url = if (ip != null) {
            cur.url.replaceFirst(Regex("//[^/:]+"), "//$ip")
        } else {
            cur.url
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("播放地址", url))
            Toast.makeText(
                this,
                if (ip == null) "没找到局域网 IP，请先连上 WiFi" else "播放地址已复制，粘到电视 / 投影仪的播放器里即可",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
            toastBrief(url)
        }
    }

    private fun onDownloadTap() {
        val url = items.getOrNull(index)?.url ?: return
        toastBrief(
            if (url.contains("127.0.0.1") || url.contains("localhost"))
                "文件已在本机：下载/简传Lite"
            else
                "视频在电脑上，用网页上的下载按钮存到手机"
        )
    }

    private fun bindGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControls(!controlsShown)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = root.width
                when {
                    e.x < w * 0.35f -> seekBy(-10_000)
                    e.x > w * 0.65f -> seekBy(10_000)
                    else -> togglePlay()
                }
                return true
            }
        })
        root.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            true
        }
    }

    private fun setControls(show: Boolean) {
        controlsShown = show
        ui.removeCallbacks(hideTask)
        if (show) {
            topBar.animate().alpha(1f).setDuration(150).start()
            bottomBar.animate().alpha(1f).setDuration(150).start()
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            scheduleHide()
        } else {
            topBar.animate().alpha(0f).setDuration(150).start()
            bottomBar.animate().alpha(0f).setDuration(150).start()
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
        }
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideTask)
        // 暂停时不自动收，否则想点播放还得先把工具栏叫回来
        if (player.isPlaying) ui.postDelayed(hideTask, 3800)
    }

    private fun toastBrief(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 进度记忆

    private fun readProgress(path: String): Long {
        if (path.isBlank()) return 0
        return try {
            prefs.getLong("p:$path", 0L)
        } catch (_: Throwable) {
            0L
        }
    }

    private fun saveProgress(forceZero: Boolean = false) {
        val cur = items.getOrNull(index) ?: return
        if (cur.path.isBlank()) return
        try {
            val t = if (forceZero) 0L else {
                try {
                    player.time
                } catch (_: Throwable) {
                    0L
                }
            }
            prefs.edit().putLong("p:${cur.path}", t.coerceAtLeast(0)).apply()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onPause() {
        super.onPause()
        try {
            if (player.isPlaying) player.pause()
        } catch (_: Throwable) {
        }
        saveProgress()
    }

    override fun onDestroy() {
        ui.removeCallbacks(hideTask)
        try {
            player.stop()
            player.detachViews()
            player.release()
        } catch (_: Throwable) {
        }
        try {
            libVlc.release()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------ 小工具

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** `http://1.2.3.4:8788/raw?path=x` → `http://1.2.3.4:8788` */
    private fun originOf(url: String): String? {
        return try {
            val u = URL(url)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.protocol}://${u.host}$port"
        } catch (_: Throwable) {
            null
        }
    }
}
