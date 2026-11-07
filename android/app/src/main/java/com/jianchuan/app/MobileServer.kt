package com.jianchuan.app

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

data class FileItem(
    val name: String,
    val size: Long,
    val mime: String,
    val category: String,     // image / audio / video / doc / zip / apk / other
    val contentUri: Uri? = null,
    val filePath: String? = null,
    val mtime: Long = 0,
    /**
     * 相对存储根的目录，形如 `Movies/死神 千年血战篇`（不含文件名，根目录下为 ""）。
     *
     * 只给电视页归类用：同一个文件夹里 ≥2 个视频判为一部剧。之前扫描不取这一列，
     * 电视端拿到的是一张扁平列表，十几集混在几百个文件里根本没法挑。
     */
    val dir: String? = null
)

/** 打包下载时的一个条目：普通文件（带开流器）或空目录占位 */
private class ZipSource(
    val entry: String,
    val size: Long,
    val isDir: Boolean,
    val opener: (() -> InputStream?)? = null
)

/**
 * 手机内置的迷你 HTTP 服务器：别人扫码/输网址后，网页里浏览、下载手机文件，或上传文件给手机。
 *
 * [clip] 用于服务端模式下的剪贴板同步：
 *   GET  /api/clip → 返回手机当前剪贴板文本
 *   POST /api/clip → 把请求体文本写进手机剪贴板
 * 这样电脑即使没有蓝牙（或蓝牙没连上），只要在同一个局域网也能同步剪贴板。
 */
class MobileServer(
    private val context: Context,
    private val clip: ClipBridge
) {

    companion object {
        // nanohttpd 2.3.1 的 SOCKET_READ_TIMEOUT 只有 5000ms，慢速大文件上传会在 read() 上抛
        // SocketTimeoutException → serve() 兜底 catch 删文件返回 500。
        // 60s 对 1GB+ 还不够：Android 11+ 公共目录走 FUSE，写盘只有 10-30MB/s，
        // 1GB 要 40-100 秒，中间任何一次 GC 卡顿都可能让 read() 超过 60s 无数据。
        // 抬到 180s，持续有数据就不会触发，真断流 3 分钟内回收连接。
        private const val SOCKET_READ_TIMEOUT_MS = 180_000

        /** 上传/下载缓冲区。64KB 在大文件上 syscall 次数偏多，256KB 更划算 */
        private const val IO_BUF = 256 * 1024

        /** 缩略图并发闸门（与前端 ThumbLoader 的并发上限对齐） */
        private const val THUMB_CONCURRENCY = 3

        /** 接收目录（手机存储/Download/简传Lite），与网页提示、桌面端保持一致 */
        const val UPLOAD_DIR_NAME = "简传Lite"

        /**
         * 广播出去的身份标识。投影仪端的 ServerFinder 拿到 /api/alive 后，
         * 只有看到这个值才认「这条是简传，不是别的东西」，从而列成可用播放源。
         * 改这个字符串要同步改 tvplayer/ServerFinder.java 的 FINGERPRINT。
         */
        const val APP_TAG = "jianchuan-lite"

        /** 设备名。播放源列表显示「简传Lite（P40）」，比一个光秃秃的 IP 好用 */
        fun deviceLabel(): String {
            val m = (android.os.Build.MODEL ?: "").trim()
            return if (m.isEmpty()) "Android" else m
        }

        /**
         * 本机局域网 IPv4，私有网段优先、去重。
         *
         * 给「复制播放地址」用：App 内 WebView 的 origin 是 http://127.0.0.1:8788，
         * 投影仪 / 电视拿到 127.0.0.1 是连自己的，永远播不出来。
         * 公网地址对局域网设备也没意义，直接排除。
         */
        fun lanIps(): List<String> {
            val out = ArrayList<String>()
            try {
                val ifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return out
                for (ni in ifs) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (addr in ni.inetAddresses) {
                        if (addr !is java.net.Inet4Address || addr.isLoopbackAddress) continue
                        val ip = addr.hostAddress ?: continue
                        val priv = ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip)
                        if (priv && ip !in out) out.add(ip)
                    }
                }
            } catch (_: Throwable) {
            }
            // 家用网段排前面：手机同时开热点又连着 WiFi 时，
            // 192.168 那条通常才是电视 / 投影仪所在的那个网
            out.sortBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.")) 1 else 2 }
            return out
        }
    }

    private var server: NanoHTTPD? = null

    /**
     * 文件索引。用「不可变快照 + 整体替换」而不是 MutableList：
     * HTTP 是多线程在读，上传线程要往里加，普通 ArrayList 并发读写会抛
     * ConcurrentModificationException（表现就是列表偶发 500、转圈）。
     * 快照读无锁，写只在扫描/上传完成后整体换一次，代价可以接受。
     *
     * 注意：新文件一律 **追加到末尾**，绝不插到头部 ——
     * 前端缓存的 /thumb/<idx>、/f/<idx> 全靠下标定位，插队会让所有旧链接指向错文件。
     */
    @Volatile private var files: List<FileItem> = emptyList()

    /** 服务端模式期间持有的锁，详见 acquireLocks() */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** 缩略图字节缓存：滚动列表会反复请求同一批图，不缓存就是反复解码 */
    private val thumbCache = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /**
     * 缩略图解码并发闸门。
     * 一个页面能一次挂 120 个卡片，浏览器会同时打出上百个 /thumb 请求；
     * 不限流的话几十个线程同时 BitmapFactory / 抽视频首帧，中低端机直接卡住。
     *
     * 用 tryAcquire 带超时而不是死等：nanohttpd 一个请求一个线程，
     * 死等会让上百个线程全挂在信号量上，连正常 API 都调度不动。
     * 拿不到就直接回 503，前端会退回类型图标，几秒后滚动再重试。
     */
    private val thumbPermits = java.util.concurrent.Semaphore(THUMB_CONCURRENCY)

    val isRunning: Boolean get() = server?.isAlive == true

    @Volatile var uploadListener: ((String, Long) -> Unit)? = null

    /** 电脑通过 HTTP 把文本写进手机剪贴板时回调（用于 UI 提示） */
    @Volatile var clipListener: ((String) -> Unit)? = null

    /**
     * 最近一次外部请求的来源（IP）与时间。
     * 排查「电脑连不上手机」时这是最有价值的一条信息：
     * 手机界面能实时看到「有没有请求真的到达」，比在两头猜网络强得多。
     */
    @Volatile var lastClientIp: String = ""
        private set
    @Volatile var lastClientAt: Long = 0L
        private set
    @Volatile var lastClientPath: String = ""
        private set
    @Volatile var requestCount: Int = 0
        private set

    /** 实际监听端口。前端要拼「复制播放地址」，这个地址必须是投影仪能连的那种。 */
    @Volatile var listenPort: Int = 8788
        private set

    private fun noteClient(ip: String, path: String) {
        lastClientIp = ip
        lastClientAt = System.currentTimeMillis()
        lastClientPath = path
        requestCount += 1
    }

    fun start(port: Int) {
        if (server != null) return
        listenPort = port
        scanFiles()
        server = HttpServer(port).apply {
            start(SOCKET_READ_TIMEOUT_MS, false)
        }
        acquireLocks()
    }

    // 权限授权后重新扫描（补上之前因没权限而漏掉的文档/压缩包等）
    fun rescan() {
        rootsCache = null      // 权限变了，可读目录可能也变了
        thumbCache.evictAll()
        scanFiles()
    }

    @Volatile private var rescanning = false

    /**
     * 后台重扫，给电视上的「刷新」用。
     *
     * 整库扫描要几百毫秒到几秒，同步做会让那个 HTTP 请求干等，
     * 遥控器按下去像卡死。所以：立刻返回旧数据，扫完下一次刷新就是新列表。
     * 正在扫的时候再按，直接忽略，免得几个线程同时重建同一个索引。
     */
    fun rescanAsync() {
        if (rescanning) return
        rescanning = true
        Thread {
            try { rescan() } catch (_: Throwable) {
            } finally { rescanning = false }
        }.apply { isDaemon = true; name = "tv-rescan" }.start()
    }

    fun stop() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        releaseLocks()
    }

    /**
     * 服务端模式必须持有唤醒锁。
     *
     * 原因是「超过 1GB 就传不完」最常见的一个真因：
     * 1GB 在 WiFi 下要 30-60 秒，用户这时候往往已经把手机放下了，
     * 屏幕一灭，Android 进入 Doze，CPU 被限频、WiFi 转入省电，
     * TCP 连接要么被挂起要么直接断，传一半就失败。
     *
     * - PARTIAL_WAKE_LOCK：息屏也保持 CPU 运行（不点屏，只保算力和网络）
     * - WifiLock：阻止 WiFi 在息屏后降速/断开
     *
     * 12 小时兜底超时，避免服务端忘记关、锁一直挂着耗电。
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock != null) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jianchuan:server").apply {
                setReferenceCounted(false)
                acquire(12L * 60 * 60 * 1000)
            }
        } catch (_: Throwable) {
            // 没有 WAKE_LOCK 权限或厂商限制，降级为不加锁，不影响服务本身
        }
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            // HIGH_PERF 在 API 29+ 已废弃，改用 LOW_LATENCY；老系统继续用旧常量
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "jianchuan:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
            // 手机当热点时不是 STA，拿不到 WifiLock，属正常情况
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wifiLock = null
    }

    fun fileCounts(): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (f in files) m[f.category] = (m[f.category] ?: 0) + 1
        return m
    }

    // ---------------------------------------------------------------- 文件扫描
    @Synchronized
    private fun scanFiles() {
        val out = ArrayList<FileItem>(1024)
        scanMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", out)
        scanMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", out)
        scanMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", out)
        scanDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "fs", out)
        scanDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "fs", out)
        scanInstalledApks(out)
        files = out          // 一次性换快照，读线程永远不会看到半成品
    }

    /**
     * 绝对路径 → 相对存储根的目录（`/storage/emulated/0/Movies/死神/a.mkv` → `Movies/死神`）。
     * 根目录下的文件返回 ""，路径不在存储根里返回 null。
     */
    private fun relDirOf(absPath: String): String? {
        val p = absPath.replace('\\', '/')
        val root = try {
            Environment.getExternalStorageDirectory().absolutePath.replace('\\', '/').trimEnd('/')
        } catch (_: Throwable) {
            return null
        }
        if (root.isEmpty() || !p.startsWith("$root/")) return null
        val rest = p.substring(root.length + 1)
        val slash = rest.lastIndexOf('/')
        return if (slash < 0) "" else rest.substring(0, slash)
    }

    private fun scanMedia(uri: Uri, category: String, out: MutableList<FileItem>) {
        try {
            // DATA 和 RELATIVE_PATH 都要：RELATIVE_PATH 只在 API 29+ 有，
            // 老机器只能靠 DATA 自己切父目录；而有的机型 DATA 返回空串，这时靠 RELATIVE_PATH 兜。
            // 用 getColumnIndex（会返回 -1）而不是 getColumnIndexOrThrow，
            // 否则某个 ROM 少一列就整条 query 抛异常，整个媒体分类直接空掉。
            val cols = arrayListOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cols.add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            val proj = cols.toTypedArray()
            context.contentResolver.query(
                uri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val n = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val s = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val m = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val d = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iRel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else -1
                while (c.moveToNext()) {
                    val name = c.getString(n) ?: continue
                    val size = c.getLong(s)
                    val mime = c.getString(m) ?: guessMime(name)
                    var abs: String? = null
                    if (iData >= 0) {
                        val v = try { c.getString(iData) } catch (_: Throwable) { null }
                        if (!v.isNullOrBlank()) abs = v
                    }
                    var rel: String? = null
                    if (iRel >= 0) {
                        val v = try { c.getString(iRel) } catch (_: Throwable) { null }
                        if (!v.isNullOrBlank()) rel = v.replace('\\', '/').trim('/')
                    }
                    // 关键：contentUri 必须是「单条记录」的 URI。
                    // 之前直接把 query 用的集合 URI（content://media/external/images/media）
                    // 存进 FileItem，openInputStream 拿它必抛 FileNotFoundException ——
                    // 表现就是网页上图片/音乐/视频点下载全部 404，只有目录里扫出来的文档能下。
                    val itemUri = ContentUris.withAppendedId(uri, c.getLong(iId))
                    out.add(
                        FileItem(
                            name, size, mime, category,
                            contentUri = itemUri,
                            filePath = abs,
                            mtime = c.getLong(d) * 1000L,
                            dir = rel ?: abs?.let { relDirOf(it) }
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun scanDir(dir: File, tag: String, out: MutableList<FileItem>) {
        if (!dir.exists()) return
        val stack = ArrayDeque<File>()
        stack.add(dir)
        var count = 0
        // 上限放宽到 20000：原来 5000 会静默截断，用户看到「文件数对不上」却查不出原因
        while (stack.isNotEmpty() && count < 20000) {
            val d = stack.removeLast()
            val list = try { d.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in list) {
                if (f.isDirectory) { stack.add(f); continue }
                if (isJunkFile(f.name)) continue
                val cat = categorize(f.name)
                if (cat != null) {
                    out.add(
                        FileItem(
                            f.name, f.length(), guessMime(f.name), cat,
                            filePath = f.absolutePath,
                            mtime = f.lastModified(),
                            dir = relDirOf(f.absolutePath)
                        )
                    )
                }
                count++
            }
        }
    }

    private fun scanInstalledApks(out: MutableList<FileItem>) {
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (ai in apps) {
                if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue  // 只列自己装的
                val name = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { ai.packageName }
                val src = ai.sourceDir ?: continue
                val f = File(src)
                if (f.exists()) {
                    out.add(
                        FileItem(
                            "$name.apk", f.length(), "application/vnd.android.package-archive", "apk",
                            filePath = src,
                            mtime = f.lastModified()
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun categorize(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif" -> "image"
            "mp3", "flac", "aac", "ogg", "wav", "m4a", "wma", "amr", "opus" -> "audio"
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "m4v", "ts", "rmvb" -> "video"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv",
            "rtf", "epub", "json", "xml", "html", "htm", "log" -> "doc"
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso" -> "zip"
            "apk" -> "apk"
            // 其余一律进「其他」，和文件闪传一致：不在已知分类里不等于不该看见
            "" -> null
            else -> "other"
        }
    }

    /** 目录扫描时要跳过的明显垃圾/系统文件，避免「其他」分类被缓存和日志淹没 */
    private fun isJunkFile(name: String): Boolean {
        if (name.startsWith(".")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("tmp", "temp", "db", "db-journal", "db-wal", "db-shm", "bak", "crdownload", "part", "lock")
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // ---------------------------------------------------------------- 文件夹共享
    /**
     * 可浏览的共享根目录。只保留真实存在且可读的，避免页面上出现点进去就是空白的入口。
     * 这些都是「公共目录」，有存储读权限就能用 File API 直接读，
     * 不需要 MANAGE_EXTERNAL_STORAGE 这种高危权限。
     */
    @Volatile private var rootsCache: List<Pair<String, File>>? = null

    private fun shareRoots(): List<Pair<String, File>> {
        rootsCache?.let { return it }
        val pub = { d: String -> Environment.getExternalStoragePublicDirectory(d) }
        val candidates = listOf(
            "下载" to pub(Environment.DIRECTORY_DOWNLOADS),
            "文档" to pub(Environment.DIRECTORY_DOCUMENTS),
            "图片" to pub(Environment.DIRECTORY_PICTURES),
            "相机" to pub(Environment.DIRECTORY_DCIM),
            "音乐" to pub(Environment.DIRECTORY_MUSIC),
            "视频" to pub(Environment.DIRECTORY_MOVIES),
            "屏幕录制" to File(pub(Environment.DIRECTORY_DCIM), "ScreenRecorder"),
            "录屏" to File(pub(Environment.DIRECTORY_MOVIES), "ScreenRecorder")
        )
        val out = ArrayList<Pair<String, File>>()
        val seen = HashSet<String>()
        for ((label, dir) in candidates) {
            val canonical = try { dir.canonicalPath } catch (_: Exception) { continue }
            if (!seen.add(canonical)) continue          // DIRECTORY_DOWNLOADS 与 Download 常是同一个
            if (!dir.isDirectory || !dir.canRead()) continue
            out.add(label to dir)
        }
        rootsCache = out
        return out
    }

    /**
     * 路径白名单。网页上任何 path 参数都必须过这一关：
     * 必须是某个共享根目录本身或它的后代，否则返回 null。
     * 少了这一步，一个构造好的 path 就能让浏览器读走整台手机的任意文件。
     */
    private fun resolveShared(raw: String?): File? {
        if (raw.isNullOrBlank()) return null
        val f = try { File(raw).canonicalFile } catch (_: Exception) { return null }
        for ((_, root) in shareRoots()) {
            val rc = try { root.canonicalFile } catch (_: Exception) { continue }
            if (f.path == rc.path || f.path.startsWith(rc.path + File.separator)) return f
        }
        return null
    }

    /**
     * 电视端取流的路径校验，比 [resolveShared] 稍宽松一点。
     *
     * 共享根只覆盖下载/文档/图片/相机/音乐/视频这几个公开目录，可微信、剪辑软件
     * 存下来的片子经常落在自己的目录里（`tencent/MicroMsg/...`），按共享根判会直接 403，
     * 表现就是"列表里有、点开播不了"。
     *
     * 这里补一条：不在共享根里时，只认媒体库索引里确实存在的音视频条目。
     * 等价于"系统已经认定是你自己的媒体文件"，并没有放开任意路径读取。
     */
    private fun resolveMediaFile(rel: String): File? {
        val abs = if (rel.startsWith("/")) rel else "/$rel"
        resolveShared(abs)?.let { return it }
        val want = try { File(abs).canonicalPath } catch (_: Throwable) { return null }
        for (it in files) {
            if (it.category != "video" && it.category != "audio") continue
            val p = it.filePath ?: continue
            val cp = try { File(p).canonicalPath } catch (_: Throwable) { continue }
            if (cp != want) continue
            val f = File(cp)
            return if (f.isFile) f else null
        }
        return null
    }

    private fun rootLabelOf(f: File): String {
        for ((label, root) in shareRoots()) {
            val rc = try { root.canonicalFile } catch (_: Exception) { continue }
            if (f.path == rc.path || f.path.startsWith(rc.path + File.separator)) return label
        }
        return ""
    }

    // ---------------------------------------------------------------- 分段传输
    /**
     * 只读前 remain 个字节的包装流。
     * 视频/音频要能拖动进度条，HTTP 必须支持 Range（206 Partial Content）：
     * 播放器 seek 时会重新发一个新 Range 请求，没有它就只能从头播、进度条拖不动。
     */
    private class RangeStream(private val src: InputStream, private var remain: Long) : InputStream() {
        override fun read(): Int {
            if (remain <= 0) return -1
            val b = src.read()
            if (b >= 0) remain--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remain <= 0) return -1
            val want = minOf(len.toLong(), remain).toInt()
            val n = src.read(b, off, want)
            if (n > 0) remain -= n
            return n
        }

        override fun available(): Int = minOf(remain, Int.MAX_VALUE.toLong()).toInt()
        override fun close() {
            try { src.close() } catch (_: Exception) {
            }
        }
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = try { input.skip(left) } catch (_: Exception) { 0L }
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) return
            left--
        }
    }

    private data class RangeHit(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
        val bad: Boolean get() = start > end
    }

    /** 只解析单段 Range（浏览器播放器/下载器也只发单段），多段按第一段处理 */
    private fun parseRange(header: String?, size: Long): RangeHit? {
        if (header == null || size <= 0) return null
        val h = header.trim()
        if (!h.startsWith("bytes=", ignoreCase = true)) return null
        val spec = h.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                // bytes=-N → 最后 N 个字节
                val suffix = b.toLongOrNull() ?: return null
                if (suffix <= 0) null else RangeHit(maxOf(0, size - suffix), size - 1)
            }
            else -> {
                val start = a.toLongOrNull() ?: return null
                if (start >= size) return RangeHit(size, size - 1)   // 故意造越界，交给调用方回 416
                val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: (size - 1))
                RangeHit(start, minOf(end, size - 1))
            }
        }
    }

    private fun urlEnc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun openStream(item: FileItem): InputStream? {
        return try {
            item.contentUri?.let { context.contentResolver.openInputStream(it) }
                ?: item.filePath?.let { FileInputStream(File(it)) }
        } catch (_: Exception) { null }
    }

    /**
     * 生成缩略图。
     *
     * 不只是图片和视频：音乐能出专辑封面，PDF 能出首页，APK 能出应用图标 ——
     * 这几种「格式里本来就存着画面」，抽出来几乎零成本。真正没有画面的
     * （压缩包、docx、txt）照旧返回 null，前端用类型图标兜底，不硬凑假封面。
     *
     * 内存有界：一律降采样，最长边控制在 720 以内。
     */
    private fun loadThumb(item: FileItem): Bitmap? {
        val uri = item.contentUri
        // 系统媒体库的缩略图最省事：图片出相册缩略图、视频出首帧、音频出专辑封面
        if (uri != null && Build.VERSION.SDK_INT >= 29 &&
            (item.category == "image" || item.category == "video" || item.category == "audio")
        ) {
            try {
                context.contentResolver.loadThumbnail(uri, Size(400, 400), null)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return when (item.category) {
            "image" -> imageThumb(item)
            "video" -> videoThumb(item)
            "audio" -> audioCover(item)
            "doc" -> pdfThumb(item)      // 非 pdf 直接 null，交给图标
            "apk" -> apkThumb(item)
            else -> null
        }
    }

    /** 图片：按 inSampleSize 降采样，避免几千万像素的图直接 OOM */
    private fun imageThumb(item: FileItem): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(item)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide > 0 && maxSide / sample > 720 && sample < 16) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        openStream(item)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }

    /** 视频：抽首帧。文件夹里的视频没有系统缩略图，只能自己抽 */
    private fun videoThumb(item: FileItem): Bitmap? {
        val mmr = android.media.MediaMetadataRetriever()
        val frame = try {
            if (item.contentUri != null) mmr.setDataSource(context, item.contentUri)
            else mmr.setDataSource(item.filePath ?: return null)
            mmr.getFrameAtTime(0)
        } catch (_: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {
            }
        }
        if (frame == null) return null
        // 4K 视频首帧直接塞进缓存会撑爆内存，统一压到最长边 720
        val maxSide = maxOf(frame.width, frame.height)
        if (maxSide <= 720) return frame
        val scale = 720f / maxSide
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        val scaled = try {
            Bitmap.createScaledBitmap(frame, w, h, true)
        } catch (_: Exception) {
            frame
        }
        if (scaled !== frame) frame.recycle()
        return scaled
    }

    /** 音乐：读 ID3 / FLAC 里内嵌的专辑封面。网络歌曲常常没封面，那就返回 null 用图标 */
    private fun audioCover(item: FileItem): Bitmap? {
        val mmr = android.media.MediaMetadataRetriever()
        val bytes = try {
            if (item.contentUri != null) mmr.setDataSource(context, item.contentUri)
            else mmr.setDataSource(item.filePath ?: return null)
            mmr.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {
            }
        }
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide > 0 && maxSide / sample > 720 && sample < 8) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
            null
        }
    }

    /** PDF 首页。docx / xlsx 本质是 zip 容器，抽不出可看的页面，只能交给图标 */
    private fun pdfThumb(item: FileItem): Bitmap? {
        if (!item.name.lowercase().endsWith(".pdf")) return null
        val pfd = try {
            if (item.contentUri != null) context.contentResolver.openFileDescriptor(item.contentUri, "r")
            else item.filePath?.let {
                android.os.ParcelFileDescriptor.open(
                    File(it), android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            pfd.use { p ->
                android.graphics.pdf.PdfRenderer(p).use { r ->
                    if (r.pageCount <= 0) return null
                    r.openPage(0).use { page ->
                        val scale = 520f / maxOf(page.width, page.height).coerceAtLeast(1)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // PDF 页面本身是透明的，不填白底的话在深色卡片上会是一团墨
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(
                            bmp, null, null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        bmp
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** APK 图标。解析包信息拿 launcher icon，比通用安装包图标好认得多 */
    private fun apkThumb(item: FileItem): Bitmap? {
        val path = item.filePath ?: return null
        return try {
            val pm = context.packageManager
            val ai = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return null
            // 不设这两个字段，loadIcon 会按包名去查「已安装」信息，装不上的 apk 取不到图标
            ai.sourceDir = path
            ai.publicSourceDir = path
            val d = pm.getApplicationIcon(ai) ?: return null
            val size = 200
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            // 老图标可能不是正方形，按原比例居中，别拉变形
            val iw = d.intrinsicWidth.takeIf { it > 0 } ?: size
            val ih = d.intrinsicHeight.takeIf { it > 0 } ?: size
            val k = minOf(size.toFloat() / iw, size.toFloat() / ih)
            val w = (iw * k).toInt().coerceAtLeast(1)
            val h = (ih * k).toInt().coerceAtLeast(1)
            d.setBounds((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
            d.draw(c)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** 文件名消毒：剥掉路径分隔符、控制字符、首部点号，截断到 180 字符。防路径穿越。 */
    private fun safeFileName(raw: String): String {
        var s = raw.replace('\\', '/').substringAfterLast('/')
        s = s.filter { !it.isISOControl() }.trim().trimStart('.')
        if (s.isBlank()) s = "upload_${System.currentTimeMillis()}"
        return s.take(180)
    }

    // ---------------------------------------------------------------- HTTP
    private inner class HttpServer(port: Int) : NanoHTTPD(port) {

        // 禁止改为 super.serve(session)：会触发 parseBody() 全量缓冲上传体，大文件必然 OOM。
        // v1.7 已改成手写路由 + 流式读 body，勿回退。
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            // nanohttpd 的 remoteIpAddress 形如 "/192.168.43.100"，去掉前导斜杠再记
            val peer = session.remoteIpAddress?.removePrefix("/")?.substringBefore(":") ?: ""
            noteClient(peer, uri)
            return try {
                when {
                    uri == "/" || uri == "/index.html" -> servePage()
                    uri == "/api/roots" -> serveRoots()
                    uri == "/api/list" -> {
                        // 带 path 参数 = 浏览文件夹；不带 = 按分类列媒体库
                        val p = session.parameters["path"]?.firstOrNull()
                        if (!p.isNullOrBlank()) serveDir(p) else serveList(session)
                    }
                    uri == "/zip" -> serveZip(session)
                    uri == "/raw" -> serveRaw(session)
                    uri == "/api/clip" -> serveClip(session)
                    uri == "/api/alive" -> serveAlive()
                    uri == "/api/subs" -> serveSubs(session)
                    uri.startsWith("/f/") -> serveFile(session, uri.substring(3))
                    uri == "/thumb" || uri.startsWith("/thumb/") -> serveThumb(
                        uri.removePrefix("/thumb").removePrefix("/"),
                        session.parameters["path"]?.firstOrNull()
                    )
                    uri.startsWith("/api/upload") -> serveUpload(session)
                    // ---- 电视端（投影仪）：页面 + 播放器要求的三个接口 ----
                    // 投影仪上的播放器外壳会把网页里的 /play?path=xxx 改写成 /media/xxx
                    // 交给内置 libVLC 播放，并自己来问 /api/episodes 和 /api/progress。
                    // 这三条路由就是照它的约定实现的，少一条电视端就播不了或没续播。
                    uri == "/tv" -> serveTvPage()
                    uri == "/api/tv" -> serveTvIndex(session)
                    uri == "/api/episodes" -> serveEpisodes(session)
                    uri == "/api/progress" -> serveProgress(session)
                    uri.startsWith("/media/") -> serveMedia(session, uri.substring(7))
                    else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            } catch (e: Exception) {
                NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }

        private fun servePage(): Response {
            val html = buildPage()
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
                // 网页是编进 APK 的，改版后必须让浏览器重新取。
                // 不设这条，Safari / Via 会拿启发式缓存一直用旧页面 ——
                // 用户在旧 UI 上怎么点都没反应，还以为代码没改。
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                addHeader("Pragma", "no-cache")
                addHeader("Expires", "0")
            }
        }

        /**
         * 心跳 + 局域网地址。
         *
         * 两个用途：
         * 1. 前端频道灯每 8 秒打一次，失败就是灰的点（未连接）。
         * 2. 顺手把本机局域网 IP 带回去 —— App 内 WebView 的 origin 是
         *    http://127.0.0.1:8788，"复制播放地址"如果直接用 location.origin，
         *    投影仪 / 电视拿到 127.0.0.1 永远连不上自己。必须换成真实局域网地址。
         */
        private fun serveAlive(): Response {
            val obj = org.json.JSONObject().apply {
                put("ok", true)
                // 投影仪端的 ServerFinder 靠 app 字段认「这个端口上是简传」，
                // 认出来才会把它列成可用播放源（电脑的 MediaShare 走 /api/list 另一套指纹）。
                // proto 是电视页协议版本：2 = 有 /tv + /api/tv，老客户端可以据此降级。
                put("app", APP_TAG)
                put("proto", 2)
                put("name", deviceLabel())
                put("port", listenPort)
                put("t", System.currentTimeMillis())
                put("ips", org.json.JSONArray(lanIps()))
            }
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8", obj.toString()
            ).apply { addHeader("Cache-Control", "no-store") }
        }

        /**
         * 外挂字幕索引：给定视频的绝对路径，返回同目录下文件名以它开头的字幕文件。
         *
         * 为什么要在服务端做：分类页（图片/视频/音乐）里根本看不到 .srt，
         * 前端就算想配对也没有这份数据；只有服务端翻得到目录。
         *
         * 匹配规则 `视频名.xxx.srt`：字幕组普遍这么命名（`影片.chs.srt`、
         * `影片.zh-CN.ass`），比"目录里所有字幕"精准得多，不会给 A 片配 B 片的字幕。
         */
        private fun serveSubs(session: IHTTPSession): Response {
            val path = session.parameters["path"]?.firstOrNull()
            val arr = org.json.JSONArray()
            if (path.isNullOrBlank()) return jsonResp(arr.toString())
            try {
                val f = File(path)
                val dir = f.parentFile ?: return jsonResp(arr.toString())
                val base = f.name.substringBeforeLast('.').lowercase()
                val exts = setOf("srt", "ass", "ssa", "vtt", "sub")
                val hits = ArrayList<File>()
                dir.listFiles()?.forEach { c ->
                    if (!c.isFile) return@forEach
                    if (c.extension.lowercase() !in exts) return@forEach
                    if (c.name.lowercase().startsWith("$base.")) hits.add(c)
                }
                // 中文轨排前面：前端 addSlave 时会按顺序选中，第一个命中的就是默认字幕
                hits.sortBy { c ->
                    val n = c.name.lowercase()
                    when {
                        n.contains("chs") || n.contains("zh-cn") || n.contains("简") -> 0
                        n.contains("cht") || n.contains("zh-tw") || n.contains("繁") -> 1
                        n.contains("chinese") || n.contains("zh") -> 2
                        n.contains("eng") || n.contains("en.") -> 9
                        else -> 5
                    }
                }
                for (c in hits) arr.put("/raw?path=" + urlEnc(c.absolutePath))
            } catch (_: Throwable) {
            }
            return jsonResp(arr.toString())
        }

        // ------------------------------------------------------------ 电视端（投影仪）
        //
        // 投影仪上那个播放器外壳 = WebView + libVLC，它把网页里的 /play?path=xxx
        // 改写成 /media/xxx 交给内置播放器，然后自己来问 /api/episodes 和 /api/progress。
        // 下面四条就是照它的约定实现的：/tv 页面、/api/tv 数据、/media 取流、集数与进度。
        // 约定里的 path 一律是「绝对路径去掉前导斜杠」，例如 storage/emulated/0/Movies/a.mkv。

        private fun serveTvPage(): Response {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8", TvPage.html(context)
            ).apply { addHeader("Cache-Control", "no-store") }
        }

        /**
         * 电视首页数据：把扁平的视频列表归成「一部剧一组」。
         * 优先看目录（同一目录 ≥2 个视频 = 一部剧），目录拿不到时退化到文件名主干。
         */
        private fun serveTvIndex(session: IHTTPSession): Response {
            if (session.parameters["rescan"]?.firstOrNull() == "1") rescanAsync()
            val prog = loadProgress()
            val snap = files
            val groups = LinkedHashMap<String, MutableList<FileItem>>()
            for (f in snap) {
                if (f.category != "video") continue
                if (f.filePath.isNullOrBlank()) continue
                val d = f.dir?.trim('/') ?: ""
                val key = if (d.isNotEmpty()) {
                    "d:" + d.lowercase()
                } else {
                    val core = showKeyOf(f.name).lowercase()
                    // 主干抽不出来就单独成组，绝不能靠空 key 把几百个散片并成一部"剧"
                    if (core.isEmpty()) "s:" + f.filePath else "n:" + core
                }
                groups.getOrPut(key) { ArrayList() }.add(f)
            }

            val showList = ArrayList<org.json.JSONObject>()
            val singleList = ArrayList<org.json.JSONObject>()
            for ((_, list) in groups) {
                val obj = tvGroupJson(list, prog)
                if (isShowGroup(list)) showList.add(obj) else singleList.add(obj)
            }
            showList.sortWith(
                compareByDescending<org.json.JSONObject> { it.optInt("count") }
                    .thenBy { it.optString("name") }
            )
            singleList.sortWith(compareByDescending<org.json.JSONObject> { it.optLong("ts") })

            val shows = org.json.JSONArray()
            for (o in showList) shows.put(o)
            val singles = org.json.JSONArray()
            for (o in singleList) singles.put(o)

            val root = org.json.JSONObject().apply {
                put("shows", shows)
                put("singles", singles)
                put("videos", snap.count { it.category == "video" })
                put("roots", shareRoots().size)
            }
            return jsonResp(root.toString())
        }

        /**
         * 这一组算不算「一部剧」。
         *
         * 只按「同一目录 ≥2 个视频」判会把相册毁掉：DCIM/Camera 里几百个随手拍的视频
         * 全都同目录，结果被并成一部叫 Camera 的"剧"，比扁平列表还难用。
         * 所以要求真的像剧：要么至少两个文件能提出集号，要么文件名主干重复。
         */
        private fun isShowGroup(list: List<FileItem>): Boolean {
            if (list.size < 2) return false
            var withNo = 0
            val coreCount = HashMap<String, Int>()
            for (f in list) {
                if (epNoOf(f.name) > 0) withNo++
                val core = showKeyOf(f.name).lowercase()
                if (core.isNotEmpty()) coreCount[core] = (coreCount[core] ?: 0) + 1
            }
            if (withNo >= 2) return true
            for (v in coreCount.values) if (v >= 2) return true
            return false
        }

        private fun tvGroupJson(
            list: List<FileItem>,
            prog: Map<String, org.json.JSONObject>
        ): org.json.JSONObject {
            val eps = list.sortedWith(
                compareBy<FileItem>(
                    { val n = epNoOf(it.name); if (n <= 0) Int.MAX_VALUE else n },
                    { it.name }
                )
            )
            val arr = org.json.JSONArray()
            var curIdx = -1
            var curPct = 0.0
            var watched = 0
            var lastTs = 0L
            eps.forEachIndexed { i, f ->
                val rel = mediaRelOf(f.filePath ?: "")
                val p = prog[rel]
                val pos = p?.optDouble("pos", 0.0) ?: 0.0
                val dur = p?.optDouble("dur", 0.0) ?: 0.0
                val rawPct = if (dur > 0) pos / dur else 0.0
                val done = dur > 0 && rawPct >= 0.95
                if (done) watched++
                if (pos > 5 && !done) {
                    curIdx = i
                    curPct = rawPct
                }
                val ts = p?.optLong("ts", 0L) ?: 0L
                if (ts > lastTs) lastTs = ts
                arr.put(
                    org.json.JSONObject().apply {
                        put("name", f.name)
                        put("path", rel)
                        put("abs", f.filePath ?: "")
                        put("no", epNoOf(f.name))
                        put("title", prettyTitle(f.name))
                        put("size", f.size)
                        put("size_text", humanSize(f.size))
                        put("pos", pos)
                        put("dur", dur)
                        put("pct", if (rawPct > 1.0) 1.0 else rawPct)
                        put("done", done)
                    }
                )
            }
            val first = eps.firstOrNull()
            val dirName = first?.dir?.trim('/') ?: ""
            val name = if (dirName.isNotEmpty()) {
                dirName.substringAfterLast('/')
            } else {
                prettyTitle(first?.name ?: "未命名")
            }
            val curNo = if (curIdx >= 0) epNoOf(eps[curIdx].name) else 0
            return org.json.JSONObject().apply {
                put("name", name)
                put("dir", dirName)
                put("count", eps.size)
                // 缩略图用第一集；电视上铺满格子靠它
                put("cover", first?.filePath ?: "")
                put("first", mediaRelOf(first?.filePath ?: ""))
                put("watched", watched)
                put("curIdx", curIdx)
                put("curNo", curNo)
                put("curPct", curPct)
                put("ts", lastTs)
                put("eps", arr)
            }
        }

        /** 播放器问「这个视频同目录还有哪几集」，用于选集与自动下一集 */
        private fun serveEpisodes(session: IHTTPSession): Response {
            val rel = (session.parameters["path"]?.firstOrNull() ?: "").trim().trimStart('/')
            if (rel.isEmpty()) return jsonResp("{\"episodes\":[]}")
            val f = resolveMediaFile(rel) ?: return err403("路径不在共享范围内")
            val dir = f.parentFile ?: return jsonResp("{\"episodes\":[]}")
            val prog = loadProgress()
            val kids = try { dir.listFiles() } catch (_: Throwable) { null } ?: emptyArray()
            val vids = kids
                .filter { it.isFile && categorize(it.name) == "video" }
                .sortedWith(
                    compareBy<File> { val n = epNoOf(it.name); if (n <= 0) Int.MAX_VALUE else n }
                        .thenBy { it.name }
                )
            val arr = org.json.JSONArray()
            for (v in vids) {
                val vrel = mediaRelOf(v.absolutePath)
                val p = prog[vrel]
                arr.put(
                    org.json.JSONObject().apply {
                        put("name", v.name)
                        put("path", vrel)
                        put("title", prettyTitle(v.name))
                        put("size", v.length())
                        put("size_text", humanSize(v.length()))
                        put("current", v.absolutePath == f.absolutePath)
                        put("pos", p?.optDouble("pos", 0.0) ?: 0.0)
                    }
                )
            }
            val obj = org.json.JSONObject().apply {
                put("episodes", arr)
                put("dir", dir.name)
            }
            return jsonResp(obj.toString())
        }

        /** 续播记忆：播放器开始播放前 GET，播放中每 8 秒 POST 一次 */
        private fun serveProgress(session: IHTTPSession): Response {
            if (session.method == NanoHTTPD.Method.POST) {
                val len = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (len <= 0 || len > 8 * 1024) return jsonResp("{\"ok\":false}")
                val buf = ByteArray(len)
                var off = 0
                try {
                    val input = session.inputStream
                    while (off < len) {
                        val n = input.read(buf, off, len - off)
                        if (n < 0) break
                        off += n
                    }
                } catch (_: Throwable) {
                }
                if (off <= 0) return jsonResp("{\"ok\":false}")
                return try {
                    val o = org.json.JSONObject(String(buf, 0, off, Charsets.UTF_8))
                    val p = o.optString("path", "").trim().trimStart('/')
                    val pos = o.optDouble("pos", 0.0)
                    val dur = o.optDouble("dur", 0.0)
                    if (p.isNotEmpty() && pos.isFinite() && dur.isFinite() && pos >= 0) {
                        writeProgress(p, pos, dur)
                    }
                    jsonResp("{\"ok\":true}")
                } catch (_: Throwable) {
                    jsonResp("{\"ok\":false}")
                }
            }
            val rel = (session.parameters["path"]?.firstOrNull() ?: "").trim().trimStart('/')
            if (rel.isEmpty()) return jsonResp("{}")
            val hit = loadProgress()[rel] ?: return jsonResp("{}")
            return jsonResp(hit.toString())
        }

        /**
         * 取流（支持 Range，拖进度条依赖 206）。
         *
         * 路径来自投影仪外壳拼的 /media/<相对路径>，中文和空格都在里面。
         * nanohttpd 已经对 URI 解码过一遍，这里不能再 decode，否则文件名含 % 会直接抛异常。
         */
        private fun serveMedia(session: IHTTPSession, rawRel: String): Response {
            val rel = rawRel.substringBefore('?').trim()
            if (rel.isEmpty()) return err404()
            val f = resolveMediaFile(rel) ?: return err403("路径不在共享范围内")
            if (!f.isFile) return err404()
            val item = FileItem(
                f.name, f.length(), guessMime(f.name), categorize(f.name) ?: "video",
                filePath = f.absolutePath, mtime = f.lastModified()
            )
            return streamResponse(item, session, inline = true)
        }

        private fun serveList(session: IHTTPSession): Response {
            val cat = session.parameters["cat"]?.firstOrNull() ?: "all"
            // 用下标遍历，别用 files.indexOf(it)：O(n²) 且 data class 相等时可能拿到错的索引
            val arr = org.json.JSONArray()
            files.forEachIndexed { idx, f ->
                if (cat != "all" && f.category != cat) return@forEachIndexed
                arr.put(
                    org.json.JSONObject().apply {
                        put("idx", idx)
                        put("name", f.name)
                        put("size", f.size)
                        put("cat", f.category)
                        put("t", f.mtime)
                        // 原生播放器要用它查同目录外挂字幕、记播放进度。
                        // MediaStore 来的条目有些没有真实路径，那就空着，不影响播放。
                        f.filePath?.let { put("path", it) }
                        // 电视页按这个分组：同一目录 ≥2 个视频算一部剧
                        f.dir?.let { put("dir", it) }
                    }
                )
            }
            return jsonResp(arr.toString())
        }

        /**
         * 缩略图：`/thumb/<idx>` 走媒体库索引，`/thumb?path=<绝对路径>` 走文件夹。
         * 只给 image / video 出图，其它类型 404，前端据此显示类型图标。
         *
         * 缓存命中走前台直接返回；未命中才去抢解码信号量，且只等 4 秒 ——
         * nanohttpd 一个请求一个线程，死等会让几十个线程挂在信号量上，
         * 把 /api/list 这种真正影响体感的请求也一起拖慢。
         */
        private fun serveThumb(idStr: String?, rawPath: String?): Response {
            val item = if (!rawPath.isNullOrBlank()) {
                // 用电视端那套校验：微信等目录里的片子也得能出图，否则电视上全是白块
                val f = resolveMediaFile(rawPath) ?: return err403("路径不在共享范围内")
                if (!f.isFile) return err404()
                FileItem(
                    f.name, f.length(), guessMime(f.name), categorize(f.name) ?: "other",
                    filePath = f.absolutePath, mtime = f.lastModified()
                )
            } else {
                val idx = idStr?.toIntOrNull() ?: return err404()
                files.getOrNull(idx) ?: return err404()
            }
            val key = "${item.contentUri}|${item.filePath}|${item.size}|${item.mtime}"
            thumbCache.get(key)?.let { cached ->
                return NanoHTTPD.newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg",
                    ByteArrayInputStream(cached), cached.size.toLong()
                ).apply { addHeader("Cache-Control", "max-age=86400") }
            }
            val got = try {
                thumbPermits.tryAcquire(4, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            if (!got) return err503("缩略图繁忙")
            val bmp = try { loadThumb(item) } catch (_: Exception) { null } finally { thumbPermits.release() }
            if (bmp == null) return err404()
            val bytes = try {
                ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
                    out.toByteArray()
                }
            } finally {
                bmp.recycle()
            }
            thumbCache.put(key, bytes)
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "image/jpeg",
                ByteArrayInputStream(bytes), bytes.size.toLong()
            ).apply { addHeader("Cache-Control", "max-age=86400") }
        }

        private fun serveFile(session: IHTTPSession, idStr: String): Response {
            val idx = idStr.toIntOrNull() ?: return err404()
            val item = files.getOrNull(idx) ?: return err404()
            val inline = session.parameters["inline"]?.firstOrNull() == "1"
            return streamResponse(item, session, inline)
        }

        /** 按绝对路径读文件（文件夹视图里的文件不在 files 索引里，走这条） */
        private fun serveRaw(session: IHTTPSession): Response {
            val f = resolveShared(session.parameters["path"]?.firstOrNull())
                ?: return err403("路径不在共享范围内")
            if (!f.isFile) return err404()
            val item = FileItem(
                f.name, f.length(), guessMime(f.name), categorize(f.name) ?: "other",
                filePath = f.absolutePath, mtime = f.lastModified()
            )
            val inline = session.parameters["inline"]?.firstOrNull() == "1"
            return streamResponse(item, session, inline)
        }

        /**
         * 统一的文件响应：支持 Range，`inline=1` 用于网页内预览。
         *
         * 定长响应而不是 chunked —— 浏览器下载时才有进度与剩余时间；
         * 视频 seek 也依赖 Content-Length 与 206。
         */
        private fun streamResponse(item: FileItem, session: IHTTPSession, inline: Boolean): Response {
            // 只要不是预览请求，一律按"下载"处理，把 Content-Type 降成 octet-stream。
            //
            // 这是让 iOS 真正下载图片/视频/音乐的唯一办法：iPhone 上光给
            // Content-Disposition: attachment 是不够的，浏览器看到 image/*、video/*、
            // audio/* 这类自己能渲染的类型依旧会打开预览、不下载。只有让它"渲染不了"，
            // 才会老实走下载。zip / docx / apk / epub 之所以一直正常，就是因为
            // 浏览本来也渲染不了这些类型。
            //
            // 判断放在服务端而不是前端参数，是为了不依赖 UA 探测和页面缓存：
            // 浏览器把旧版页面缓存着、或者第三方浏览器改了 UA，这里照样生效。
            // inline=1 是预览层专用的，保持原 MIME 正常显示。
            val forceSave = !inline || session.parameters["save"]?.firstOrNull() == "1"
            val mime = if (forceSave) "application/octet-stream" else item.mime

            // filename= 是 ASCII 兜底：部分 WebKit 版本解析 filename*=UTF-8'' 会失败，
            // 一旦拿不到文件名，它会把响应当成"要显示的内容"退回预览。
            val asciiName = item.name.map { c ->
                if (c.code in 32..126 && c != '"' && c != '\\') c else '_'
            }.joinToString("")

            val disposition = (if (inline) "inline" else "attachment") +
                "; filename=\"$asciiName\"; filename*=UTF-8''" + urlEnc(item.name)

            // 下载：一律 chunked + octet-stream，两条理由缺一不可 ——
            //
            // 1) iPhone 只对"自己渲染不了"的类型才下载。图片/视频/音乐的 MIME 会让它
            //    直接开预览页，所以必须降成 application/octet-stream。
            //
            // 2) 不能声明 Content-Length。分类页的图片/视频/音乐来自 MediaStore 索引，
            //    size 取的是 MediaStore 的 SIZE 字段，而实际字节数来自 openInputStream
            //    返回的流 —— 两者不一致时（索引过期、文件在写入、provider 做转换），
            //    声明错的长度会让浏览器判为"下载不完整"直接报无法下载。
            //    预览不受影响是因为 <img>/<video> 对长度不匹配有容错，下载是严格校验。
            //    /zip 一直是 chunked，所以它从来没出过这个问题 —— 这里照抄它。
            if (forceSave) {
                val s = openStream(item) ?: return err404()
                // 路径来的文件，长度取自 File.length()，可信 → 用定长，浏览器有进度和剩余时间。
                // 媒体库来的（contentUri）size 取自 MediaStore 索引，可能过期 →
                // 必须用不定长，宁可没进度条，也不能声明一个错的长度。
                val res = if (item.contentUri == null && item.size > 0) {
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.OK, "application/octet-stream", s, item.size
                    )
                } else {
                    NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/octet-stream", s)
                }
                return res.apply {
                    addHeader("Content-Disposition", disposition)
                    addHeader("Cache-Control", "no-store")
                }
            }

            if (item.size <= 0) {
                val s = openStream(item) ?: return err404()
                return NanoHTTPD.newChunkedResponse(Response.Status.OK, mime, s).apply {
                    addHeader("Content-Disposition", disposition)
                }
            }

            val hit = parseRange(session.headers["range"], item.size)
            if (hit != null) {
                if (hit.bad) {
                    return NanoHTTPD.newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range Not Satisfiable"
                    ).apply { addHeader("Content-Range", "bytes */${item.size}") }
                }
                val src = openStream(item) ?: return err404()
                skipFully(src, hit.start)
                return NanoHTTPD.newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mime, RangeStream(src, hit.length), hit.length
                ).apply {
                    addHeader("Content-Range", "bytes ${hit.start}-${hit.end}/${item.size}")
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Disposition", disposition)
                }
            }

            val src = openStream(item) ?: return err404()
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, mime, src, item.size
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Disposition", disposition)
            }
        }

        // ------------------------------------------------------------ 文件夹浏览
        private fun serveRoots(): Response {
            val arr = org.json.JSONArray()
            for ((label, dir) in shareRoots()) {
                val kids = try { dir.listFiles() } catch (_: Exception) { null } ?: emptyArray()
                var dirs = 0
                var fls = 0
                for (k in kids) {
                    if (isJunkFile(k.name)) continue
                    if (k.isDirectory) dirs++ else fls++
                }
                arr.put(
                    org.json.JSONObject().apply {
                        put("name", label)
                        put("path", dir.absolutePath)
                        put("dirs", dirs)
                        put("files", fls)
                    }
                )
            }
            return jsonResp("{\"roots\":$arr}")
        }

        private fun serveDir(rawPath: String): Response {
            val dir = resolveShared(rawPath) ?: return err403("路径不在共享范围内")
            if (!dir.isDirectory) return err404()
            val kids = try { dir.listFiles() } catch (_: Exception) { null } ?: emptyArray()
            val dirs = org.json.JSONArray()
            val fls = org.json.JSONArray()
            for (k in kids) {
                if (isJunkFile(k.name)) continue
                if (k.isDirectory) {
                    val sub = try { k.list() } catch (_: Exception) { null } ?: emptyArray<String>()
                    dirs.put(
                        org.json.JSONObject().apply {
                            put("name", k.name)
                            put("path", k.absolutePath)
                            put("n", sub.size)
                        }
                    )
                } else {
                    fls.put(
                        org.json.JSONObject().apply {
                            put("name", k.name)
                            put("path", k.absolutePath)
                            put("size", k.length())
                            put("t", k.lastModified())
                            put("cat", categorize(k.name) ?: "other")
                        }
                    )
                }
            }
            val parent = dir.parentFile?.let { resolveShared(it.absolutePath) }
            val obj = org.json.JSONObject().apply {
                put("name", dir.name.ifBlank { rootLabelOf(dir) })
                put("root", rootLabelOf(dir))
                put("path", dir.absolutePath)
                put("parent", parent?.absolutePath ?: "")
                put("dirs", dirs)
                put("files", fls)
            }
            return jsonResp(obj.toString())
        }

        /**
         * 打包下载。参数二选一或混用：
         *   p=<绝对路径>  可重复，文件或文件夹都行（文件夹递归打包）
         *   i=<索引>      可重复，媒体库里那些只有 contentUri 的条目走这条
         *
         * 用 PipedInputStream 边压边发，绝不先打包到本地临时文件 ——
         * 手机上几百 MB 的目录先落盘会直接把存储塞爆，而且还要等全部压完才有响应。
         */
        private fun serveZip(session: IHTTPSession): Response {
            val sources = ArrayList<ZipSource>()
            var baseName = ""

            for (r in session.parameters["p"] ?: emptyList()) {
                val f = resolveShared(r) ?: continue
                if (!f.exists()) continue
                if (baseName.isEmpty()) baseName = f.name
                if (f.isDirectory) collectDir(f, f.name, sources)
                else sources.add(
                    ZipSource(f.name, f.length(), false) { FileInputStream(f) }
                )
            }
            for (s in session.parameters["i"] ?: emptyList()) {
                val n = s.toIntOrNull() ?: continue
                val item = files.getOrNull(n) ?: continue
                if (baseName.isEmpty()) baseName = item.name
                sources.add(ZipSource(item.name, item.size, false) { openStream(item) })
            }
            if (sources.isEmpty()) return err403("没有可打包的内容")

            val zipName = if (sources.size == 1 && baseName.isNotEmpty()) {
                baseName + ".zip"
            } else {
                "简传Lite-${sources.count { !it.isDir }}项.zip"
            }

            val pipeIn = java.io.PipedInputStream(256 * 1024)
            val pipeOut = java.io.PipedOutputStream(pipeIn)
            Thread {
                var zos: java.util.zip.ZipOutputStream? = null
                try {
                    zos = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(pipeOut, 256 * 1024))
                    // 打包不做压缩（store）。图片/视频/音乐/APK 本来就是压缩格式，
                    // deflate 压不动，纯烧 CPU；手机上压缩只有 20-40MB/s，
                    // 比 WiFi 能跑的 40-80MB/s 还慢，反成了瓶颈。
                    // 用 NO_COMPRESSION 后速度只受磁盘读速和网络限制。
                    zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                    for (s in sources) {
                        if (s.isDir) {
                            val entry = if (s.entry.endsWith("/")) s.entry else s.entry + "/"
                            zos.putNextEntry(java.util.zip.ZipEntry(entry))
                            zos.closeEntry()
                            continue
                        }
                        try {
                            zos.putNextEntry(java.util.zip.ZipEntry(s.entry))
                            val input = s.opener?.invoke()
                            if (input == null) {
                                zos.closeEntry()
                                continue
                            }
                            input.use { ins ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n <= 0) break
                                    zos.write(buf, 0, n)
                                }
                            }
                            zos.closeEntry()
                        } catch (_: Exception) {
                            // 单个文件读失败（被占用/无权限）不该让整包失败
                            try { zos.closeEntry() } catch (_: Exception) {
                            }
                        }
                    }
                    zos.finish()
                } catch (_: Exception) {
                    // 客户端中途取消下载时会走到这里，属正常情况
                } finally {
                    try { zos?.close() } catch (_: Exception) {
                    }
                    try { pipeOut.close() } catch (_: Exception) {
                    }
                }
            }.apply { isDaemon = true; name = "zip-pack" }.start()

            return NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/zip", pipeIn).apply {
                addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + urlEnc(zipName))
            }
        }

        private fun collectDir(dir: File, prefix: String, out: MutableList<ZipSource>) {
            val kids = try { dir.listFiles() } catch (_: Exception) { null } ?: emptyArray()
            val kept = kids.filter { !isJunkFile(it.name) }
            if (kept.isEmpty()) {
                // 空目录也要留个 entry，否则解压后目录结构丢失
                out.add(ZipSource("$prefix/", 0, true))
                return
            }
            for (k in kept) {
                if (k.isDirectory) collectDir(k, "$prefix/${k.name}", out)
                else out.add(ZipSource("$prefix/${k.name}", k.length(), false) { FileInputStream(k) })
            }
        }

        /**
         * 剪贴板同步接口。
         * GET  拉手机剪贴板；POST 把 body 里的 UTF-8 文本写进手机剪贴板。
         * 写入统一走 ClipBridge.writeText，它会登记回声指纹，不会又被监听线程发回去。
         */
        private fun serveClip(session: IHTTPSession): Response {
            if (session.method == NanoHTTPD.Method.POST) {
                val len = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (len <= 0) return jsonResp("{\"ok\":false,\"error\":\"空内容\"}")
                // 剪贴板文本没必要太大，超过 256KB 直接拒，防止恶意撑爆内存
                if (len > 256 * 1024) return jsonResp("{\"ok\":false,\"error\":\"内容过大\"}")
                val buf = ByteArray(len)
                var off = 0
                try {
                    val input = session.inputStream
                    while (off < len) {
                        val n = input.read(buf, off, len - off)
                        if (n < 0) break
                        off += n
                    }
                } catch (_: Exception) {
                }
                if (off <= 0) return jsonResp("{\"ok\":false,\"error\":\"读取失败\"}")
                val text = String(buf, 0, off, Charsets.UTF_8)
                clip.writeText(text)
                clipListener?.invoke(text)
                return jsonResp("{\"ok\":true,\"len\":${off}}")
            }
            val text = clip.readText() ?: ""
            val obj = org.json.JSONObject()
            obj.put("ok", true)
            obj.put("text", text)
            obj.put("ts", System.currentTimeMillis())
            return jsonResp(obj.toString())
        }

        private fun jsonResp(body: String): Response =
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8", body
            ).apply {
                // 动态数据一律 no-store。Edge 有启发式缓存，某些情况下会把没有明确
                // 缓存指令的 200 响应也存下来 —— 表现就是「刚上传完刷新还是看不到」。
                addHeader("Cache-Control", "no-store")
            }

        private fun serveUpload(session: IHTTPSession): Response {
            // 注意：session.parameters 已由 nanohttpd 的 decodeParms 做过一次 URL 解码，
            // 这里不能再 URLDecoder.decode 一遍，否则文件名含 % 会抛 IllegalArgumentException（500）。
            val nameRaw = session.parameters["name"]?.firstOrNull() ?: "upload_${System.currentTimeMillis()}"
            val name = safeFileName(nameRaw)
            // 必须按 Content-Length 精确读取；readBytes() 会一直等连接关闭导致超时。
            // 用 Long：toIntOrNull 遇上 >2GB 的单个文件会直接返回 null（误报"空文件"）。
            val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
            if (len <= 0L) return jsonErr("空文件（请求缺少 Content-Length）")

            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                UPLOAD_DIR_NAME
            )
            if (!dir.exists() && !dir.mkdirs()) {
                return jsonErr("创建接收目录失败，请检查存储权限", Response.Status.INTERNAL_ERROR)
            }

            // 空间预检 —— 这是 1GB 以上文件「传到一半就失败」最常见的原因。
            // 原来直接开写，撑到 ENOSPC 才抛异常，错误信息还被 serve() 的兜底 catch 吞成光秃秃的 500，
            // 用户只看到「上传失败」，完全查不出是空间不够。预留 8MB 余量，别刚好把系统盘写满。
            val free = try { dir.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
            if (free in 1 until (len + 8L * 1024 * 1024)) {
                return jsonErr(
                    "手机剩余空间不足：需要 ${mb(len)} MB，可用 ${mb(free)} MB",
                    Response.Status.INTERNAL_ERROR
                )
            }

            var outName = name
            var f = File(dir, outName)
            var i = 1
            while (f.exists()) {
                val dot = name.lastIndexOf('.')
                // 与电脑端 server.py 的 unique_path 保持同一格式：名字 (1).ext
                outName = if (dot > 0) name.substring(0, dot) + " ($i)" + name.substring(dot) else "$name ($i)"
                f = File(dir, outName)
                i++
            }
            // 双保险：即使 safeFileName 漏了什么，也绝不能写出 下载/简传Lite/ 之外
            if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                return jsonErr("非法文件名")
            }

            // 先写 .part 再改名：中途断流/超时不会在用户目录里留半截「看起来正常」的文件。
            // 首字符是点号，scanDir 的 isJunkFile 会跳过它，不会被扫进「其他」分类。
            val tmp = File(dir, ".$outName.part")
            var read = 0L
            try {
                val input = session.inputStream
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(IO_BUF)
                    while (read < len) {
                        val want = minOf(buf.size.toLong(), len - read).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                    }
                    out.flush()
                }
                if (read != len) {
                    throw IOException("连接中断，只收到 ${mb(read)}/${mb(len)} MB")
                }
                if (!tmp.renameTo(f)) {
                    // 个别 FUSE 实现上同目录 renameTo 也会失败，退化成复制
                    FileInputStream(tmp).use { ins ->
                        FileOutputStream(f).use { o -> ins.copyTo(o, IO_BUF) }
                    }
                    tmp.delete()
                }
            } catch (e: Throwable) {
                // 必须 catch Throwable，不能只 catch Exception：
                // 大文件上真正致命的是 OutOfMemoryError 这类 Error，Exception 抓不到，
                // 会直接穿透 serve() 把连接搞成无响应，前端只能等到超时。
                try { tmp.delete() } catch (_: Exception) {}
                try { f.delete() } catch (_: Exception) {}
                return jsonErr(
                    "写入失败：${e.message ?: e.javaClass.simpleName}",
                    Response.Status.INTERNAL_ERROR
                )
            }

            val item = FileItem(
                outName, read, guessMime(outName), categorize(outName) ?: "other",
                filePath = f.absolutePath, mtime = System.currentTimeMillis()
            )
            val idx = appendFile(item)
            uploadListener?.invoke(outName, read)
            // 写到公共 Download 目录后必须主动通知媒体库。
            // 系统文件管理器的「下载」分类、相册、以及「按类型浏览」都只认 MediaStore 里
            // 登记过的条目 —— 用 File API 直接写盘的文件不会自己出现，
            // 表现就是电脑端明明上传成功，手机上翻遍文件夹也找不到。
            try {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(f.absolutePath), arrayOf(item.mime), null
                )
            } catch (_: Throwable) {
            }
            // path / dir 一定要回给前端：用户提的「传完了不知道在哪」就是缺这几个字段
            return jsonResp(
                org.json.JSONObject().apply {
                    put("ok", true)
                    put("name", outName)
                    put("size", read)
                    put("path", f.absolutePath)
                    put("dir", dir.absolutePath)
                    put("cat", item.category)
                    put("idx", idx)
                }.toString()
            )
        }

        private fun err404() = NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        private fun err403(msg: String) =
            NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain; charset=utf-8", msg)
        private fun err503(msg: String) =
            NanoHTTPD.newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain; charset=utf-8", msg)
        private fun err500() = NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error")

        /** 统一的失败响应体：前端能直接拿到可读原因，而不是只看到「上传失败」 */
        private fun jsonErr(
            msg: String,
            status: Response.Status = Response.Status.BAD_REQUEST
        ): Response = NanoHTTPD.newFixedLengthResponse(
            status, "application/json; charset=utf-8",
            org.json.JSONObject().apply {
                put("ok", false)
                put("error", msg)
            }.toString()
        )
    }

    /** 上传完成后把新文件追加进索引，让「全部 / 图片 / 文档」等分类立刻就能看到它 */
    @Synchronized
    private fun appendFile(item: FileItem): Int {
        files = files + item
        return files.size - 1
    }

    // ---------------------------------------------------------------- 电视端工具
    //
    // 这几个函数只服务 /tv，但放在外层是为了不跟 HttpServer 里那一堆 serveXxx 混在一起。

    /** 绝对路径 → /media/ 后面那一段（去前导斜杠），全站统一这个基准 */
    private fun mediaRelOf(abs: String): String = abs.replace('\\', '/').trimStart('/')

    private fun humanSize(b: Long): String {
        if (b <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "$b B" else (if (v >= 100) "%.0f %s" else "%.1f %s").format(v, units[i])
    }

    /**
     * 从文件名提取集号：`第27集`、`S01E27`、`EP27`、`E27`、末尾纯数字。
     * 提取不到返回 0（排序时排到最后）。
     */
    private fun epNoOf(name: String): Int {
        val n = name.substringBeforeLast('.', name)
        Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""").find(n)?.let {
            return it.groupValues[2].toIntOrNull() ?: 0
        }
        Regex("""第\s*(\d{1,4})\s*[集话話期]""").find(n)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("""(?i)\b[Ee][Pp]?[\s\-_.]?(\d{1,4})\b""").find(n)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("""(?:^|[\s\-.\[(（])(\d{1,4})\s*$""").find(n)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        return 0
    }

    /**
     * 去掉集号、分辨率、字幕组、编码之后剩下的「主干」，用来判断两个文件是不是同一部剧。
     * 返回空串表示判断不出来 —— 调用方必须把它当散片，不能靠空主干合并。
     */
    private fun showKeyOf(name: String): String {
        var s = name.substringBeforeLast('.', name)
        s = Regex("""[\[【(（][^\]】)）]*[\]】)）]""").replace(s, " ")
        s = Regex(
            """(?i)(第\s*\d{1,4}\s*[集话話期]|[Ss]\d{1,2}[Ee]\d{1,3}|[Ee][Pp]?[\s\-_.]?\d{1,4}\b|\d{1,4}\s*[集话話])"""
        ).replace(s, " ")
        s = Regex(
            """(?i)(2160p|1080p|720p|480p|4k|8k|hdr10?|web-?dl|webrip|bluray|bdrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|avc|aac|ddp?\d?(?:\.\d)?|flac|remux|10bit|ma10p)"""
        ).replace(s, " ")
        s = s.replace(Regex("""[\s._\-—–·:：]+"""), "")
        s = s.replace(Regex("""[^\p{L}\p{N}]"""), "")
        return s.trim()
    }

    /** 给电视上看的人看的名字：去掉扩展名和那些没人读的技术标记 */
    private fun prettyTitle(name: String): String {
        var s = name.substringBeforeLast('.', name)
        s = Regex("""[\[【][^\]】]*[\]】]""").replace(s, " ")
        s = Regex("""(?i)\b(2160p|1080p|720p|480p|4k|hdr10?|web-?dl|webrip|bluray|bdrip|hdtv|remux)\b.*$""")
            .replace(s, " ")
        s = Regex("""[_.]+""").replace(s, " ")
        s = Regex("""\s+""").replace(s, " ").trim()
        return if (s.isBlank()) name else s
    }

    private val progressLock = Any()

    private fun progressStore(): File = File(context.filesDir, "tv_progress.json")

    /** 进度表：key = 相对路径（与 /media、/api/progress 的 path 完全一致） */
    private fun loadProgress(): HashMap<String, org.json.JSONObject> {
        val m = HashMap<String, org.json.JSONObject>()
        val f = try { progressStore() } catch (_: Throwable) { return m }
        if (!f.isFile) return m
        // 几千条也就几百 KB，整读整写足够；不跟着用户视频数量走
        return try {
            val o = org.json.JSONObject(f.readText(Charsets.UTF_8))
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                o.optJSONObject(k)?.let { m[k] = it }
            }
            m
        } catch (_: Throwable) {
            m
        }
    }

    private fun writeProgress(rel: String, pos: Double, dur: Double) {
        synchronized(progressLock) {
            val m = loadProgress()
            m[rel] = org.json.JSONObject().apply {
                put("pos", pos)
                put("dur", dur)
                put("ts", System.currentTimeMillis())
            }
            if (m.size > 2000) {
                val keep = m.entries.sortedByDescending { it.value.optLong("ts") }.take(2000)
                m.clear()
                for (e in keep) m[e.key] = e.value
            }
            val root = org.json.JSONObject()
            for ((k, v) in m) root.put(k, v)
            try {
                val f = progressStore()
                val tmp = File(f.parentFile ?: context.filesDir, f.name + ".tmp")
                tmp.writeText(root.toString(), Charsets.UTF_8)
                // 先写临时文件再改名：写到一半被杀掉也不会留下半截 JSON，
                // 否则下次启动整份进度表解析失败，所有续播一起丢。
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun mb(b: Long): Long = if (b <= 0L) 0L else maxOf(1L, b / 1048576L)

    // ---------------------------------------------------------------- 网页
    private fun buildPage(): String {
        val counts = fileCounts()
        val n = { c: String -> counts[c] ?: 0 }
        val total = n("image") + n("audio") + n("video") + n("doc") + n("zip") + n("apk") + n("other")
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>简传 Lite · 手机文件</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#f2f4f8;--card:#fff;--line:#e6eaf0;--text:#1c2430;--text2:#68758a;--text3:#98a3b3;--brand:#3a6ff0;--soft:#eaf1ff}
html,body{height:100%}
body{background:var(--bg);color:var(--text);font:14px/1.5 "PingFang SC","Microsoft YaHei",system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased}
.app{display:flex;height:100vh;padding:16px;gap:16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px}
.side{width:236px;flex:none;display:flex;flex-direction:column;gap:14px;min-height:0}
.owner{padding:14px;display:flex;align-items:center;gap:11px}
.avatar{width:40px;height:40px;border-radius:12px;display:grid;place-items:center;background:linear-gradient(145deg,#4c8bff,#2c58d8);flex:none}
.avatar svg{width:21px;height:21px;color:#fff}
.owner b{font-size:14.5px;display:block}
.owner .ip{font-size:11.5px;color:var(--text3);margin-top:2px;word-break:break-all}
/* 频道灯：绿呼吸=局域网直连正常，灰=断了。手机服务端只可能被局域网设备访问，
   没有公网那一路，所以不做蓝灯 —— 别为了对称硬造一个不会亮的状态。 */
.chan{display:flex;align-items:center;gap:6px;margin-top:5px;font-size:11.5px;color:var(--text2)}
.chan .dot{width:7px;height:7px;border-radius:50%;background:#c3ccd6;flex:none}
.chan .dot.lan{background:#22a06b;box-shadow:0 0 0 3px rgba(34,160,107,.14);animation:dotBreath 2.4s ease-in-out infinite}
.chan.lost{color:var(--text3)}
.chan.lost .dot{background:#c3ccd6;box-shadow:none;animation:none}
/* 电视模式：投影仪上那个播放器只要输一次这个地址，以后打开就是大格子浏览页 */
.tvlink{display:flex;align-items:center;gap:6px;margin-top:7px;padding:6px 8px;border-radius:9px;background:#f4f8ff;color:#3a6ff0;font-size:11.5px;cursor:pointer;user-select:none}
.tvlink svg{width:14px;height:14px;flex:none}
.tvlink .tvaddr{margin-left:auto;color:#7a8aa0;font-size:10.5px;max-width:116px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.tvlink:hover{background:#e9f1ff}
@keyframes dotBreath{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.62;transform:scale(.84)}}
.nav{padding:8px;overflow:auto;flex:1;min-height:0}
.nav .ttl{font-size:11px;color:var(--text3);padding:9px 10px 6px;letter-spacing:.5px}
.nav a{display:flex;align-items:center;gap:10px;padding:9px 10px;border-radius:10px;color:var(--text2);cursor:pointer;font-size:13.5px;user-select:none}
.nav a:hover{background:#f5f7fb}
.nav a.on{background:var(--soft);color:var(--brand);font-weight:600}
.nav .ic{width:18px;display:grid;place-items:center;flex:none}
.nav .ic svg{width:16px;height:16px}
.nav .cnt{margin-left:auto;font-size:11.5px;color:var(--text3);font-variant-numeric:tabular-nums}
.nav a.on .cnt{color:var(--brand)}
.upbox{padding:10px}
.upbox button{width:100%;padding:10px;border-radius:11px;border:1px dashed #c6d4ea;background:#f8fbff;color:var(--brand);font-weight:600;font-size:13px;cursor:pointer;font-family:inherit;display:flex;align-items:center;justify-content:center;gap:7px}
.upbox button:hover{background:var(--soft)}
.upbox svg{width:15px;height:15px}
.main{flex:1;display:flex;flex-direction:column;gap:14px;min-width:0}
.toolbar{padding:11px 14px;display:flex;align-items:center;gap:9px;flex-wrap:wrap}
.toolbar h1{font-size:16px;font-weight:600}
.toolbar .sub{font-size:12px;color:var(--text3)}
.sp{flex:1;min-width:8px}
input.search{height:34px;padding:0 12px;border:1px solid var(--line);border-radius:10px;font-size:13px;outline:none;font-family:inherit;width:168px;background:#fbfcfe}
input.search:focus{border-color:var(--brand);background:#fff}
.btn{display:inline-flex;align-items:center;gap:6px;height:34px;padding:0 13px;border-radius:10px;border:1px solid var(--line);background:#fff;color:var(--text);font-size:13px;cursor:pointer;font-family:inherit;white-space:nowrap}
.btn:hover{border-color:#c9d5e6;background:#f8fafd}
.btn.primary{background:var(--brand);border-color:var(--brand);color:#fff}
.btn.primary:hover{background:#2a63d6}
.btn[disabled]{opacity:.4;cursor:not-allowed}
.btn svg{width:15px;height:15px}
.gridpanel{flex:1;overflow:auto;padding:14px;min-height:0}
.crumbs{display:flex;align-items:center;gap:4px;flex-wrap:wrap;padding:0 2px 12px;font-size:13px}
.crumbs:empty{display:none}
.crumb{border:0;background:none;color:var(--brand);cursor:pointer;font-family:inherit;font-size:13px;padding:3px 6px;border-radius:7px}
.crumb:hover{background:var(--soft)}
.crumb.cur{color:var(--text);font-weight:600;cursor:default}
.crumb.cur:hover{background:none}
.sep{color:var(--text3);font-size:12px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(138px,1fr));gap:12px}
.tile{position:relative;border:1.5px solid transparent;border-radius:14px;padding:9px;cursor:pointer;background:#fafbfd;transition:background .12s,border-color .12s;
  -webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
.tile img{-webkit-touch-callout:none;-webkit-user-drag:none}
.tile:hover{background:#f3f7fd;border-color:#dbe5f5}
.tile.sel{border-color:var(--brand);background:var(--soft)}
.thumb{aspect-ratio:1/1;border-radius:10px;overflow:hidden;background:#eef2f7;display:grid;place-items:center;position:relative}
.thumb img{width:100%;height:100%;object-fit:cover;display:block}
.thumb .ph{display:grid;place-items:center;color:#9aa8bd}
.thumb .ph svg{width:34px;height:34px}
.tile[data-cat=image] .thumb .ph,.tile[data-cat=video] .thumb .ph{color:#7f8ea6}
.tile[data-dir="1"] .thumb{background:linear-gradient(160deg,#fff6e2,#ffe9c2)}
.tile[data-dir="1"] .thumb .ph{color:#e8a33d}
.tile[data-dir="1"] .thumb .ph svg{width:42px;height:42px}
.tile[data-dir="1"] .thumb .cnt{position:absolute;right:6px;bottom:6px;background:rgba(232,163,61,.92);color:#fff;font-size:10.5px;padding:1px 7px;border-radius:9px;font-variant-numeric:tabular-nums}
.nm{margin-top:8px;font-size:12.5px;line-height:1.35;word-break:break-all;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:34px}
.mt{font-size:11px;color:var(--text3);margin-top:4px;font-variant-numeric:tabular-nums}
.dl{position:absolute;right:8px;top:8px;width:30px;height:30px;border-radius:9px;border:0;background:rgba(28,36,48,.66);color:#fff;display:grid;place-items:center;cursor:pointer;opacity:.9;transition:.12s;z-index:2}
.dl:hover{background:var(--brand);opacity:1}
.dl svg{width:15px;height:15px}
.chk{position:absolute;left:8px;top:8px;width:20px;height:20px;border-radius:7px;background:rgba(255,255,255,.9);border:1.5px solid #ccd6e4;display:none;place-items:center;z-index:2}
/* 多选模式：所有复选框常驻（空心），点一下才变实心。
   注意这条必须写在 .tile.sel .chk 前面，两者特异性相同，靠源码顺序决胜。 */
.grid.selmode .chk{display:grid}
.grid.selmode .tile:not(.sel) .chk svg{opacity:0}
.grid.selmode .tile:hover .chk{border-color:var(--brand)}
.grid.selmode .dl{display:none}
.tile.sel .chk{display:grid;background:var(--brand);border-color:var(--brand)}
.chk svg{width:12px;height:12px;color:#fff}
.grid.list{grid-template-columns:1fr;gap:8px}
.grid.list .tile{display:flex;align-items:center;gap:12px;padding:8px 12px}
.grid.list .thumb{width:42px;height:42px;aspect-ratio:auto;flex:none;border-radius:9px}
.grid.list .thumb .ph svg{width:20px;height:20px}
.grid.list .thumb .cnt{display:none}
.grid.list .nm{margin:0;flex:1;min-height:0;-webkit-line-clamp:1}
.grid.list .mt{margin:0;width:84px;text-align:right;flex:none}
.grid.list .dl{position:static;flex:none}
.grid.list .chk{position:static;display:grid;flex:none;background:#fff}
.grid.list .tile.sel .chk{background:var(--brand)}
.empty{grid-column:1/-1;text-align:center;color:var(--text3);padding:72px 0;font-size:13px}
.more{grid-column:1/-1;text-align:center;padding:14px}
/* 骨架屏：切分类时先把旧网格清掉。
   清 DOM 会让浏览器主动 abort 掉还在传的 <img>，把宝贵的连接让给 /api/list ——
   原来不清，120 张缩略图继续霸占 6 条连接，切分类就得干等它们排完。 */
.skel{grid-column:1/-1;display:grid;grid-template-columns:repeat(auto-fill,minmax(138px,1fr));gap:12px}
.skel i{aspect-ratio:1/1;border-radius:14px;background:linear-gradient(100deg,#eef2f7 30%,#f7f9fc 50%,#eef2f7 70%);background-size:220% 100%;animation:sk 1.05s linear infinite}
@keyframes sk{0%{background-position:130% 0}100%{background-position:-30% 0}}
/* 上传进度浮层 */
.upq{position:fixed;right:18px;bottom:18px;width:346px;max-width:calc(100vw - 36px);z-index:60;display:flex;flex-direction:column-reverse;gap:8px;pointer-events:none}
.upq .c{pointer-events:auto;background:#fff;border:1px solid var(--line);border-radius:14px;padding:11px 13px;box-shadow:0 12px 34px rgba(24,40,68,.14);transition:border-color .2s,background .2s}
.upq .r{display:flex;align-items:center;gap:9px}
.upq .nm{font-size:12.5px;flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.upq .pc{font-size:12px;color:var(--text3);font-variant-numeric:tabular-nums;flex:none}
.upq .bar{height:5px;border-radius:3px;background:#edf1f7;margin-top:9px;overflow:hidden}
.upq .bar i{display:block;height:100%;width:0;border-radius:3px;background:linear-gradient(90deg,#3a6ff0,#38c6d9);transition:width .18s linear}
.upq .st{display:flex;align-items:center;gap:8px;margin-top:8px;font-size:11.5px;color:var(--text3);font-variant-numeric:tabular-nums}
.upq .path{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.upq .c.done{border-color:#bfe8d3;background:#f5fdf9}
.upq .c.done .bar i{background:#2eb872}
.upq .c.done .pc{color:#1c9e5e;font-weight:600}
.upq .c.err{border-color:#f2c8c8;background:#fff9f9}
.upq .c.err .bar{display:none}          /* 失败时进度条没有信息量，直接让原因上移一行 */
.upq .c.err .st{margin-top:9px}
.upq .c.err .pc{color:#c94a4a;font-weight:600}
.upq .c.err .st{color:#b45858}
.upq .lnk{border:0;background:var(--soft);color:var(--brand);font-family:inherit;font-size:11.5px;padding:4px 10px;border-radius:8px;cursor:pointer;flex:none}
.upq .lnk:hover{background:#dbe8ff}
#toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%) translateY(12px);background:#1c2430;color:#fff;padding:10px 18px;border-radius:11px;font-size:13px;opacity:0;pointer-events:none;transition:.22s;z-index:90}
#toast.on{opacity:1;transform:translateX(-50%) translateY(0)}
.lb{position:fixed;inset:0;background:#0b1017;display:none;z-index:70}
.lb.on{display:block}
.lbbody{position:absolute;inset:54px 70px 62px;display:grid;place-items:center}
.lbbody img{max-width:100%;max-height:100%;border-radius:10px;object-fit:contain;box-shadow:0 20px 60px rgba(0,0,0,.5)}
.lbbody video{max-width:100%;max-height:100%;border-radius:10px;background:#000}
.lbbody .audio-wrap{text-align:center;width:min(520px,86vw)}
.lbbody .audio-ic{width:96px;height:96px;margin:0 auto 18px;border-radius:26px;display:grid;place-items:center;background:linear-gradient(145deg,#4c8bff,#2c58d8)}
.lbbody .audio-ic svg{width:44px;height:44px;color:#fff}
.lbbody .audio-nm{color:#e8eef8;font-size:14px;margin-bottom:16px;word-break:break-all}
.lbbody audio{width:100%}
.lb .x{position:absolute;right:20px;top:14px;width:40px;height:40px;border-radius:50%;border:0;background:rgba(255,255,255,.16);color:#fff;font-size:24px;cursor:pointer;line-height:1;z-index:2}
.lb .x:hover{background:rgba(255,255,255,.28)}
/* 预览页里也能直接进多选：看完一张想批量下旁边几张，不用退出去再长按 */
.lbsel{position:absolute;right:138px;top:18px;height:32px;padding:0 14px;border-radius:16px;
  border:1px solid rgba(255,255,255,.26);background:rgba(255,255,255,.12);color:#fff;
  font-size:12.5px;cursor:pointer;z-index:2;transition:background .15s,border-color .15s}
.lbsel:hover{background:rgba(255,255,255,.26);border-color:rgba(255,255,255,.4)}
.lbsel:active{transform:scale(.95)}
/* 预览页「保存」：iOS 上点卡片只会进预览、没有下载口，必须给一个明确按钮 */
.lbsave{position:absolute;right:72px;top:18px;height:32px;padding:0 14px;border-radius:16px;
  border:1px solid rgba(47,127,184,.5);background:rgba(47,127,184,.32);color:#fff;
  font-size:12.5px;cursor:pointer;z-index:2;transition:background .15s,border-color .15s}
.lbsave:hover{background:rgba(47,127,184,.55);border-color:rgba(47,127,184,.8)}
.lbsave:active{transform:scale(.95)}
/* iOS 专属提示：网页没法把文件写进「照片」，长按才是唯一进相册的路 */
.lbhint{position:absolute;left:0;right:0;bottom:38px;text-align:center;color:#8996a9;
  font-size:11.5px;padding:0 30px;display:none;line-height:1.5}
.lbhint.on{display:block}
.lbnav{position:absolute;top:50%;transform:translateY(-50%);width:46px;height:46px;border-radius:50%;border:0;background:rgba(255,255,255,.14);color:#fff;font-size:26px;cursor:pointer;line-height:1;z-index:2}
.lbnav:hover{background:rgba(255,255,255,.26)}
.lbnav[disabled]{opacity:.2;cursor:default}
.lbnav.prev{left:14px}
.lbnav.next{right:14px}
.lbinfo{position:absolute;left:0;right:0;bottom:16px;text-align:center;color:#b9c5d6;font-size:12.5px;padding:0 80px;word-break:break-all}
/* 复制播放地址：只在视频/音频时出现。
   App 内的视频会进原生播放器，所以这个按钮是给「手机网页」和「别的手机看片」用的 ——
   把地址粘到电视 / 投影仪的媒体播放器里，就能直接播这台手机上的片。 */
.lbcast{position:absolute;right:14px;bottom:12px;width:36px;height:36px;border-radius:50%;
  border:1px solid rgba(255,255,255,.26);background:rgba(255,255,255,.12);color:#fff;
  display:none;place-items:center;cursor:pointer;z-index:2;padding:0}
.lbcast.on{display:grid}
.lbcast:hover{background:rgba(255,255,255,.26)}
.lbcast:active{transform:scale(.94)}
.lbcast svg{width:17px;height:17px}
/* 动效：只动 transform / opacity，绝不改宽高 —— 一屏 120 张缩略图，改宽高就是重排风暴。
   用 backwards 而不是 both：动画跑完不留残留 transform，:active 的按压才接得上。 */
@keyframes tileIn{from{opacity:0;transform:translateY(7px) scale(.985)}to{opacity:1;transform:none}}
.grid:not(.list) .tile{animation:tileIn .26s cubic-bezier(.2,.9,.3,1) backwards}
.grid:not(.list) .tile:nth-child(1){animation-delay:0ms}
.grid:not(.list) .tile:nth-child(2){animation-delay:26ms}
.grid:not(.list) .tile:nth-child(3){animation-delay:52ms}
.grid:not(.list) .tile:nth-child(4){animation-delay:78ms}
.grid:not(.list) .tile:nth-child(5){animation-delay:104ms}
.grid:not(.list) .tile:nth-child(6){animation-delay:130ms}
.grid:not(.list) .tile:nth-child(7){animation-delay:156ms}
.grid:not(.list) .tile:nth-child(8){animation-delay:182ms}
.tile:active{transform:scale(.983)}
.tile{transition:background .12s,border-color .12s,transform .12s}
.upq .c{animation:tileIn .22s ease-out backwards}
.upq .c.done .pc{animation:popNum .34s cubic-bezier(.3,1.4,.5,1)}
@keyframes popNum{0%{transform:scale(1)}40%{transform:scale(1.16)}100%{transform:scale(1)}}
.btn:active,.upbox button:active{transform:scale(.97)}
.dl:active{transform:scale(.94)}
@media(prefers-reduced-motion:reduce){
  *{animation:none!important;transition:none!important}
}
@media(max-width:820px){
  .app{flex-direction:column;padding:10px;gap:10px;height:auto}
  .side{width:100%;flex-direction:column;gap:10px}
  .owner{flex:none}
  .nav{display:flex;flex-wrap:wrap;gap:4px;flex:none;overflow:visible;padding:4px}
  .nav .ttl{display:none}
  .nav a{white-space:nowrap;padding:7px 9px}
  .gridpanel{max-height:none}
  .lbbody{inset:52px 10px 60px}
  .lbnav{width:38px;height:38px;font-size:22px}
  .lbnav.prev{left:4px}
  .lbnav.next{right:4px}
}
</style>
</head>
<body>
<div class="app">
  <aside class="side">
    <div class="card owner">
      <div class="avatar"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M3.4 12.3 20.6 4l-4.9 15.6-4.3-6.1-8 .9 3.6-3.4-.7-3.7z"/></svg></div>
      <div><b>这台手机的共享文件</b><div class="ip" id="ip"></div>
        <div class="chan" id="chanRow"><span class="dot" id="chanDot"></span><span id="chanName">连接中</span></div>
        <a class="tvlink" id="tvLink" title="投影仪 / 电视的浏览地址，点一下复制">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="4" width="19" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>
          <span>电视模式</span><span class="tvaddr" id="tvAddr">···</span>
        </a>
      </div>
    </div>
    <nav class="card nav" id="nav">
      <div class="ttl">分类</div>
      <a data-c="all" class="on"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><rect x="3" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.6"/></svg></span>全部文件<span class="cnt">${total}</span></a>
      <a data-c="image"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="8.5" cy="9.5" r="1.6"/><path d="m4 17 5-5 4 4 3-3 4 4"/></svg></span>图片<span class="cnt">${n("image")}</span></a>
      <a data-c="video"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="5" width="14" height="14" rx="2.5"/><path d="m16.5 10 5-3v10l-5-3z"/></svg></span>视频<span class="cnt">${n("video")}</span></a>
      <a data-c="audio"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V6l11-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/></svg></span>音乐<span class="cnt">${n("audio")}</span></a>
      <a data-c="doc"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 17h4"/></svg></span>文档<span class="cnt">${n("doc")}</span></a>
      <a data-c="zip"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M21 9v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9"/><rect x="1.5" y="3" width="21" height="6" rx="1.5"/><path d="M10 13h4"/></svg></span>压缩包<span class="cnt">${n("zip")}</span></a>
      <a data-c="apk"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="M3.3 7 12 12l8.7-5"/><path d="M12 22V12"/></svg></span>安装包<span class="cnt">${n("apk")}</span></a>
      <a data-c="other"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.3-.9.8-.9 1.4v.3"/><path d="M12 17.2v.01"/></svg></span>其他<span class="cnt">${n("other")}</span></a>
      <div class="ttl">浏览</div>
      <a data-c="folder"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M3 10h18"/></svg></span>文件夹<span class="cnt" id="foldercnt">-</span></a>
    </nav>
    <div class="card upbox">
      <button id="up"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5"/><path d="m5 12 7-7 7 7"/></svg>上传文件到这台手机</button>
      <input type="file" id="f" hidden multiple>
    </div>
  </aside>
  <section class="main">
    <div class="card toolbar">
      <h1 id="title">全部文件</h1>
      <span class="sub" id="selinfo"></span>
      <span class="sp"></span>
      <input class="search" id="q" placeholder="搜索文件名">
      <button class="btn" id="sortbtn">按时间</button>
      <button class="btn" id="selmode">多选</button>
      <button class="btn" id="selall">全选</button>
      <button class="btn" id="selcancel" style="display:none">取消</button>
      <button class="btn primary" id="dlsel" disabled>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="m7 12 5 5 5-5"/><path d="M4 21h16"/></svg>
        下载<span id="dlcnt"></span>
      </button>
      <button class="btn" id="viewbtn">列表</button>
    </div>
    <div class="card gridpanel" id="panel">
      <div class="crumbs" id="crumbs"></div>
      <div class="grid" id="grid"></div>
    </div>
  </section>
</div>
<div id="upq" class="upq"></div>
<div id="toast"></div>
<div class="lb" id="lb">
  <button class="x" id="lbx">×</button>
  <button class="lbsave" id="lbsave">保存</button>
  <button class="lbsel" id="lbsel">选择</button>
  <button class="lbnav prev" id="lbprev">‹</button>
  <button class="lbnav next" id="lbnext">›</button>
  <div class="lbbody" id="lbbody"></div>
  <div class="lbhint" id="lbhint"></div>
  <div class="lbinfo" id="lbinfo"></div>
  <button class="lbcast" id="lbcast" title="复制播放地址">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v10"/><path d="m8 7 4-4 4 4"/><rect x="3" y="14" width="18" height="7" rx="2"/></svg>
  </button>
</div>
<script>
/* ---------------- 平台判定 ----------------
   iOS 上所有浏览器（Safari / Via / Chrome）都是 WebKit 套壳，行为一致：
   `<a download>` 只是"建议"，最终由浏览器按 Content-Type 决定下载还是预览。
   图片、视频这种它能内联渲染的类型 → 一律进预览页，永远不下。
   所以 iOS 单独走一条路：URL 加 save=1，让服务端回 application/octet-stream，
   浏览器渲染不了，只剩"下载"和"报错"两条路。 */
var IS_IOS=/iP(hone|ad|od)/.test(navigator.userAgent)||
  (/Macintosh/.test(navigator.userAgent)&&navigator.maxTouchPoints>1)||
  (navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
var SVG_DL='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="m7 12 5 5 5-5"/><path d="M4 21h16"/></svg>';
var SVG_OK='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7"/></svg>';
var SVG_FOLDER='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>';
var SVG_AUDIO='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V6l11-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/></svg>';
var ICON_PATH={
  image:'<rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="8.5" cy="9.5" r="1.6"/><path d="m4 17 5-5 4 4 3-3 4 4"/>',
  video:'<rect x="2.5" y="5" width="14" height="14" rx="2.5"/><path d="m16.5 10 5-3v10l-5-3z"/>',
  audio:'<path d="M9 18V6l11-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>',
  doc:'<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 17h4"/>',
  zip:'<path d="M21 9v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9"/><rect x="1.5" y="3" width="21" height="6" rx="1.5"/><path d="M10 13h4"/>',
  apk:'<path d="M21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="M3.3 7 12 12l8.7-5"/><path d="M12 22V12"/>',
  other:'<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>'
};
function iconSvg(cat){
  var p=ICON_PATH[cat]||ICON_PATH.other;
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">'+p+'</svg>';
}
var TITLE={all:'全部文件',image:'图片',video:'视频',audio:'音乐',doc:'文档',zip:'压缩包',apk:'安装包',other:'其他',folder:'文件夹'};
var MODE='cat',CAT='all',CUR='',DATA=[],SEL={},Q='',SORT='time',VIEW='grid',SHOWN=0,PAGE=120,ROOTS=[],DIRS={};
// 多选模式：长按任意缩略图进入。进去之后点哪都是勾选，不再"点缩略图预览、点边框选中"。
var SELMODE=false,LP_TIMER=null,LP_FIRED=false,LP_X=0,LP_Y=0;

function clearSel(){
  SEL={};
  if(!SELMODE)return;
  SELMODE=false;
  document.getElementById('grid').classList.remove('selmode');
  var b=document.getElementById('selcancel');
  if(b)b.style.display='none';
  var m=document.getElementById('selmode');
  if(m)m.style.display='';
}

function enterSelMode(){
  if(SELMODE)return;
  SELMODE=true;
  document.getElementById('grid').classList.add('selmode');
  var b=document.getElementById('selcancel');
  if(b)b.style.display='';
  // 进了多选就把入口按钮收起来：工具栏在手机上本来就挤，不能再多占一格
  var m=document.getElementById('selmode');
  if(m)m.style.display='none';
}
var SORTS=['time','name','size'],SORTNAME={time:'按时间',name:'按名称',size:'按大小'};

function toast(t){var e=document.getElementById('toast');e.textContent=t;e.className='on';clearTimeout(e._t);e._t=setTimeout(function(){e.className=''},2200)}

/** 复制文本。局域网 http 不是安全上下文，navigator.clipboard 直接用不了，
    必须留 execCommand 这条老路（iOS 上它也要求在点击手势里同步调用）。 */
function copyText(t,cb){
  function fb(){
    try{
      var ta=document.createElement('textarea');
      ta.value=t;ta.setAttribute('readonly','');
      ta.style.cssText='position:fixed;left:-9999px;top:0;opacity:0';
      document.body.appendChild(ta);
      ta.select();ta.setSelectionRange(0,t.length);
      var ok=document.execCommand('copy');
      ta.remove();
      return ok;
    }catch(e){return false}
  }
  if(navigator.clipboard&&window.isSecureContext){
    navigator.clipboard.writeText(t).then(function(){cb(true)},function(){cb(fb())});
    return;
  }
  cb(fb());
}

/* ---------------- 频道灯 ----------------
   手机当服务端时只可能被局域网设备访问，所以只有两态：绿呼吸=通，灰=断。
   每 8 秒打一次 /api/alive：既当心跳，也顺手把局域网地址拿回来 ——
   App 内 WebView 的 origin 是 127.0.0.1，投影仪拿到它连不上，
   复制播放地址时必须换成这里的真实 IP。 */
var LAN_IPS=[],SRV_PORT=8788;

/* 电视模式地址：投影仪上那个播放器只要输一次 http 地址，以后打开就是大格子浏览页。
   没有局域网 IP（比如还没连 WiFi）时保持省略号，点了会提示。 */
function updateTvLink(){
  var a=document.getElementById('tvAddr'); if(!a)return;
  if(!LAN_IPS.length)return;
  a.textContent='http://'+LAN_IPS[0]+':'+SRV_PORT+'/tv';
}
window.addEventListener('load',function(){
  var l=document.getElementById('tvLink');
  if(!l)return;
  l.onclick=function(){
    var a=document.getElementById('tvAddr');
    var t=a?a.textContent:'';
    if(t.indexOf('http')!==0){toast('还没拿到局域网地址，确认手机连着 WiFi');return;}
    copyText(t,function(ok){toast(ok?'电视地址已复制，粘到投影仪播放器里打开':'复制失败，请手动输入');});
  };
  updateTvLink();
});
var BEAT_FAIL=0;
function setChan(state){
  var row=document.getElementById('chanRow');
  if(!row)return;
  var dot=document.getElementById('chanDot'),nm=document.getElementById('chanName');
  row.className='chan'+(state==='lost'?' lost':'');
  if(dot)dot.className='dot'+(state==='lan'?' lan':'');
  if(nm)nm.textContent=state==='lan'?'局域网直连':(state==='lost'?'未连接':'连接中');
}
/* 一次心跳没回来不代表断了：WiFi 丢一个包、浏览器把标签页挂起、服务端正好在写盘，
   都会让单次 fetch 超时。以前一次失败就变灰、下一次成功又变绿，灯就在
   「未连接 / 局域网直连」之间来回跳。改成连续两次才算断，失败时先快速补打一次。 */
function beat(){
  var ctl=null,tm=null,opt={cache:'no-store'};
  if(typeof AbortController!=='undefined'){
    ctl=new AbortController();opt.signal=ctl.signal;
    tm=setTimeout(function(){try{ctl.abort()}catch(e){}},6000);
  }
  function fin(){if(tm){clearTimeout(tm);tm=null}}
  fetch('/api/alive',opt).then(function(r){
    fin();
    if(!r.ok)throw new Error('HTTP '+r.status);
    return r.json();
  }).then(function(d){
    BEAT_FAIL=0;
    if(d&&d.ips&&d.ips.length)LAN_IPS=d.ips;
    if(d&&d.port)SRV_PORT=d.port;
    updateTvLink();
    setChan('lan');
  }).catch(function(){
    fin();
    BEAT_FAIL++;
    if(BEAT_FAIL>=2)setChan('lost');
    else setTimeout(beat,1500);
  });
}
beat();
setInterval(beat,8000);
window.addEventListener('online',beat);
window.addEventListener('offline',function(){setChan('lost')});
function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function fmt(b){if(b<1024)return b+' B';if(b<1048576)return (b/1024).toFixed(1)+' KB';if(b<1073741824)return (b/1048576).toFixed(1)+' MB';return (b/1073741824).toFixed(2)+' GB'}
function enc(p){return encodeURIComponent(p)}
function isMedia(f){return f.cat==='image'||f.cat==='video'||f.cat==='audio'}
/* 有「画面」的类型才去请求缩略图。
   图片/视频/音乐/安装包一定能出图；文档里只有 PDF 能渲染首页，docx 那些
   请了也是 404，白占连接（同源只有 6 条），所以这里先挡掉。 */
function thumbable(f){
  if(f.cat==='image'||f.cat==='video'||f.cat==='audio'||f.cat==='apk')return true;
  return f.cat==='doc'&&/\.pdf$/i.test(f.name);
}

/* ---------------- 缩略图调度器 ----------------
   浏览器对同一站点只有 6 条并发连接（HTTP/1.1 的限制，跟服务端无关）。
   一屏 120 个卡片同时发 /thumb，6 条连接瞬间被占满，而且每条在手机端还要排队等
   解码信号量 —— 于是用户自己点的「切分类」只能排在第 121 位，等五六秒才轮到。
   这里做三件事：
     1. 限并发 3，永远留一半连接给 /api/list 这类真正影响体感的请求
     2. 可整体 abort，切分类/进目录时立刻松手（reset）
     3. 内存缓存，来回切不重复请求 */
var Thumb=(function(){
  var MAX=3,q=[],active=0,ctl=null,cache={};
  function attach(img,url,ac){
    active++;
    fetch(url,{signal:ac.signal,cache:'force-cache'}).then(function(r){
      if(!r.ok)throw new Error('HTTP '+r.status);
      return r.blob();
    }).then(function(b){
      if(ac!==ctl)return;
      var u=URL.createObjectURL(b);
      cache[url]=u;
      if(img.parentNode)img.src=u;
    }).catch(function(){
      if(ac!==ctl)return;
      // 文件被删 / 服务端繁忙 → 退回类型图标，别在网格里留个灰块
      var box=img.parentNode;
      if(!box||box.className!=='thumb')return;
      var tile=img.closest('.tile');
      box.innerHTML='<div class="ph">'+iconSvg(tile?tile.dataset.cat:'other')+'</div>';
    }).then(function(){
      if(ac===ctl){active--;pump()}
    });
  }
  function pump(){
    while(active<MAX){
      var img=q.shift();
      if(!img)return;
      var url=img.getAttribute('data-src');
      if(!url||img.dataset.st==='1'||!img.isConnected)continue;
      img.dataset.st='1';
      if(cache[url]){img.src=cache[url];continue}
      attach(img,url,ctl);
    }
  }
  return {
    reset:function(){
      if(ctl){try{ctl.abort()}catch(e){}}
      ctl=new AbortController();
      q=[];active=0;
    },
    collect:function(root){
      var im=root.querySelectorAll('img[data-src]');
      for(var i=0;i<im.length;i++)if(im[i].dataset.st!=='1')q.push(im[i]);
      pump();
    }
  };
})();

function showLoading(){
  document.getElementById('grid').innerHTML='<div class="skel">'+new Array(19).join('<i></i>')+'</div>';
}

function filtered(){
  var q=Q.toLowerCase();
  var a=DATA.filter(function(f){return !q||f.name.toLowerCase().indexOf(q)>=0});
  a.sort(function(x,y){
    if(x.isDir!==y.isDir)return x.isDir?-1:1;
    if(SORT==='name')return x.name.localeCompare(y.name,'zh');
    if(SORT==='size')return y.size-x.size;
    return (y.t||0)-(x.t||0);
  });
  return a;
}

function tileHtml(f){
  var thumb;
  if(f.isDir){
    thumb='<div class="ph">'+SVG_FOLDER+'</div><span class="cnt">'+f.n+' 项</span>';
  }else if(f.thumb){
    // 不直接写 src：交给 Thumb 调度器按并发上限拉，才能保证 /api/list 有连接可用
    thumb='<img data-src="'+esc(f.thumb)+'" alt="">';
  }else{
    thumb='<div class="ph">'+iconSvg(f.cat)+'</div>';
  }
  return '<div class="tile'+(SEL[f.key]?' sel':'')+'" data-key="'+esc(f.key)+'" data-cat="'+f.cat+'" data-dir="'+(f.isDir?1:0)+'">'
    +'<div class="chk">'+SVG_OK+'</div>'
    +'<button class="dl" data-dl="'+esc(f.key)+'" title="'+(f.isDir?'打包下载这个文件夹':'下载这个文件')+'">'+SVG_DL+'</button>'
    +'<div class="thumb">'+thumb+'</div>'
    +'<div class="nm">'+esc(f.name)+'</div>'
    +'<div class="mt">'+(f.isDir?'文件夹':fmt(f.size))+'</div>'
    +'</div>';
}

function appendChunk(){
  var list=filtered(),grid=document.getElementById('grid');
  var end=Math.min(SHOWN+PAGE,list.length),html='';
  for(var i=SHOWN;i<end;i++)html+=tileHtml(list[i]);
  if(SHOWN===0&&!list.length)html='<div class="empty">这里还没有文件</div>';
  if(end<list.length)html+='<div class="more"><button class="btn" id="morebtn">加载更多（还有 '+(list.length-end)+' 个）</button></div>';
  var old=document.getElementById('morebtn');if(old)old.parentNode.parentNode.removeChild(old.parentNode);
  grid.insertAdjacentHTML('beforeend',html);
  SHOWN=end;
  var mb=document.getElementById('morebtn');
  if(mb)mb.onclick=function(){appendChunk()};
  Thumb.collect(grid);
}

function render(){
  Thumb.reset();                       // 先掐断上一批缩略图，把连接立刻让出来
  document.getElementById('grid').innerHTML='';
  SHOWN=0;appendChunk();updateSel();
}

function updateSel(){
  var k=Object.keys(SEL).length,total=filtered().length;
  document.getElementById('dlsel').disabled=k===0;
  document.getElementById('dlcnt').textContent=k?'('+k+')':'';
  // 没进多选时顺手写一句"长按可多选"—— 藏起来的交互没人猜得到，
  // 之前"点边框才选中"就是栽在这上面。
  document.getElementById('selinfo').textContent=
    k?('已选 '+k+' 个 · 共 '+total+' 个'):('共 '+total+' 个'+(SELMODE?'':' · 长按可多选'));
  document.getElementById('selall').textContent=(k>0&&k>=total)?'取消全选':'全选';
}

function findByKey(k){
  for(var i=0;i<DATA.length;i++)if(DATA[i].key===k)return DATA[i];
  return null;
}

function saveUrl(f){
  var a=document.createElement('a');
  // save=1 一律带上，不再看 IS_IOS。
  // 理由：iPhone 上图片/视频/音乐的 MIME 会被浏览器直接渲染成预览页、根本不下载；
  // 服务端收到 save=1 就把 Content-Type 换成 octet-stream，浏览器渲染不了才肯下载。
  // 之前只在 IS_IOS 时加，一旦 UA 判断失效（第三方浏览器改 UA）媒体文件就下不动，
  // 而 zip/doc 这类本来渲染不了的类型照样能下 —— 正好是这个现象。
  // 安卓带这个参数没有副作用：Chrome 拿到 octet-stream + attachment 一样是下载。
  a.href=f.dl+((f.dl.indexOf('?')<0?'?':'&')+'save=1');
  if(f.name)a.download=f.name+(f.isDir?'.zip':'');
  // 不要加 target='_blank'：zip / docx 用一模一样的 a.click() 是能下成功的，
  // 说明跳转机制没问题，差异只在 MIME。而 WKWebView 里 _blank 要宿主单独实现
  // createWebViewWith，第三方浏览器常常不做 —— 加了反而可能一点反应都没有。
  document.body.appendChild(a);a.click();a.remove();
}

function enterDir(path){
  // 先松手：清空旧网格 + abort 在途缩略图，否则一次进目录要等上一屏的图排完
  Thumb.reset();
  showLoading();
  fetch('/api/list?path='+enc(path)).then(function(r){
    if(!r.ok)throw new Error('HTTP '+r.status);
    return r.json();
  }).then(function(d){
    DIRS[d.path]=d;
    MODE='dir';CUR=d.path;clearSel();Q='';document.getElementById('q').value='';
    markNav('folder');
    document.getElementById('title').textContent=(d.root||d.name||'文件夹');
    DATA=[];
    for(var i=0;i<d.dirs.length;i++){
      var s=d.dirs[i];
      DATA.push({key:'p:'+s.path,name:s.name,size:0,t:0,cat:'folder',isDir:true,n:s.n,
        thumb:'',url:'',dl:'/zip?p='+enc(s.path)});
    }
    for(var j=0;j<d.files.length;j++){
      var f=d.files[j];
      DATA.push({key:'p:'+f.path,name:f.name,size:f.size,t:f.t,cat:f.cat,isDir:false,n:0,
        thumb:thumbable(f)?('/thumb?path='+enc(f.path)):'',
        url:'/raw?path='+enc(f.path)+'&inline=1',dl:'/raw?path='+enc(f.path),path:f.path});
    }
    renderCrumbs(d);
    render();
  }).catch(function(e){toast('打开文件夹失败：'+e.message);DATA=[];render()});
}

function renderCrumbs(d){
  var box=document.getElementById('crumbs');
  if(MODE!=='dir'){box.innerHTML='';return;}
  var root=null;
  for(var i=0;i<ROOTS.length;i++){
    var r=ROOTS[i];
    if(d.path===r.path||d.path.indexOf(r.path+'/')===0){root=r;break;}
  }
  if(!root){box.innerHTML='';return;}
  var rel=d.path.slice(root.path.length).replace(/^\//,'');
  var segs=rel?rel.split('/'):[];
  var html='<button class="crumb" data-p="'+esc(root.path)+'">'+esc(root.name)+'</button>';
  var acc=root.path;
  for(var k=0;k<segs.length;k++){
    acc+='/'+segs[k];
    html+='<span class="sep">/</span><button class="crumb'+(k===segs.length-1?' cur':'')+'" data-p="'+esc(acc)+'">'+esc(segs[k])+'</button>';
  }
  box.innerHTML=html;
}

// 只取共享根目录清单（面包屑和计数要用），不动列表
function fetchRoots(){
  return fetch('/api/roots').then(function(r){return r.json()}).then(function(d){
    ROOTS=d.roots||[];
    document.getElementById('foldercnt').textContent=ROOTS.length;
  }).catch(function(){});
}

function loadFolder(retried){
  if(!ROOTS.length){
    if(retried){
      MODE='dir';CUR='';DATA=[];clearSel();
      document.getElementById('title').textContent='文件夹';
      document.getElementById('crumbs').innerHTML='';
      render();
      toast('没有可浏览的文件夹（可能还没给存储权限）');
      return;
    }
    fetchRoots().then(function(){loadFolder(true)});
    return;
  }
  MODE='dir';CUR='';clearSel();Q='';document.getElementById('q').value='';
  document.getElementById('title').textContent='文件夹';
  document.getElementById('crumbs').innerHTML='';
  DATA=[];
  for(var i=0;i<ROOTS.length;i++){
    var r=ROOTS[i];
    DATA.push({key:'p:'+r.path,name:r.name,size:0,t:0,cat:'folder',isDir:true,
      n:(r.dirs+r.files),thumb:'',url:'',dl:'/zip?p='+enc(r.path)});
  }
  render();
}

function loadCat(cat){
  MODE='cat';CAT=cat;CUR='';clearSel();Q='';document.getElementById('q').value='';
  document.getElementById('crumbs').innerHTML='';
  document.getElementById('title').textContent=TITLE[cat]||'全部文件';
  Thumb.reset();                       // 同上：切分类先放掉旧缩略图占用的连接
  showLoading();
  fetch('/api/list?cat='+cat).then(function(r){
    if(!r.ok)throw new Error('HTTP '+r.status);
    return r.json();
  }).then(function(list){
    DATA=list.map(function(f){
      return {key:'i:'+f.idx,name:f.name,size:f.size,t:f.t,cat:f.cat,isDir:false,n:0,
        thumb:thumbable(f)?('/thumb/'+f.idx):'',
        url:'/f/'+f.idx+'?inline=1',dl:'/f/'+f.idx,idx:f.idx,path:f.path||''};
    });
    render();
  }).catch(function(e){toast('读取文件列表失败：'+e.message);DATA=[];render()});
}

function markNav(cat){
  var navs=document.querySelectorAll('#nav a');
  for(var i=0;i<navs.length;i++)navs[i].className=navs[i].dataset.c===cat?'on':'';
}

function switchTo(cat){
  markNav(cat);
  if(cat==='folder')loadFolder();else loadCat(cat);
}

document.getElementById('nav').addEventListener('click',function(e){
  var a=e.target.closest('a');if(!a)return;switchTo(a.dataset.c);
});

document.getElementById('crumbs').addEventListener('click',function(e){
  var b=e.target.closest('.crumb');if(!b)return;
  if(b.className.indexOf('cur')>=0)return;
  enterDir(b.dataset.p);
});

document.getElementById('grid').addEventListener('click',function(e){
  var dl=e.target.closest('.dl');
  if(dl){
    e.stopPropagation();
    var f0=findByKey(dl.dataset.dl);
    if(f0){saveUrl(f0);toast(f0.isDir?'正在打包下载…':'开始下载 '+f0.name);}
    return;
  }
  var t=e.target.closest('.tile');if(!t)return;
  // 长按刚刚进了多选，这一次 click 是长按的尾巴，别再切一次勾选
  if(LP_FIRED){LP_FIRED=false;return;}
  var f=findByKey(t.dataset.key);if(!f)return;
  if(SELMODE){
    if(SEL[f.key])delete SEL[f.key];else SEL[f.key]=1;
    t.classList.toggle('sel',!!SEL[f.key]);
    updateSel();
    return;
  }
  // 普通模式：整块都能"打开"，不再区分缩略图和边框。
  // 旧逻辑是"点缩略图预览、点边框才选中"，没人猜得到，还容易误触预览。
  if(f.isDir){enterDir(f.key.slice(2));return;}
  // App 内点视频交给原生播放器（libVLC）：网页 <video> 放不了 MKV，
  // 也没有音轨切换和 ASS 特效字幕。用浏览器访问时没有这个桥，自动退回网页预览。
  if(f.cat==='video'&&openNative(f.key))return;
  if(isMedia(f)){preview(f.key);return;}
  saveUrl(f);toast('开始下载 '+f.name);
});

// 长按 460ms 进入多选（和系统相册一致）。
// 统一走 pointer 事件：mousedown + touchstart 混用会被触发两次。
(function(){
  var grid=document.getElementById('grid'),panel=document.getElementById('panel');
  function stopLP(){if(LP_TIMER){clearTimeout(LP_TIMER);LP_TIMER=null;}}
  function startLP(t){
    stopLP();
    LP_FIRED=false;
    LP_TIMER=setTimeout(function(){
      LP_TIMER=null;
      var f=findByKey(t.dataset.key);if(!f)return;
      LP_FIRED=true;
      enterSelMode();
      if(!SEL[f.key]){SEL[f.key]=1;t.classList.add('sel');}
      updateSel();
      if(navigator.vibrate){try{navigator.vibrate(14)}catch(err){}}
    },460);
  }
  grid.addEventListener('pointerdown',function(e){
    if(e.button&&e.button!==0)return;
    if(e.target.closest('.dl'))return;
    var t=e.target.closest('.tile');if(!t)return;
    LP_X=e.clientX;LP_Y=e.clientY;
    startLP(t);
  });
  grid.addEventListener('pointermove',function(e){
    if(!LP_TIMER)return;
    // 手指一滑就去滚动了，别把滚动当长按
    if(Math.abs(e.clientX-LP_X)>8||Math.abs(e.clientY-LP_Y)>8)stopLP();
  });
  // pointerup 挂在 document 上：手指在 tile 外抬起时 grid 收不到
  document.addEventListener('pointerup',stopLP);
  document.addEventListener('pointercancel',stopLP);
  if(panel)panel.addEventListener('scroll',stopLP,{passive:true});
  // 长按图片会弹"保存图片"菜单，压掉它
  grid.addEventListener('contextmenu',function(e){
    if(e.target.closest('.tile'))e.preventDefault();
  });
})();

document.getElementById('panel').addEventListener('scroll',function(e){
  var el=e.target;
  if(el.scrollTop+el.clientHeight>el.scrollHeight-320){
    if(SHOWN<filtered().length)appendChunk();
  }
});

// 显式「多选」入口。以前只有长按 460ms 才能进多选，没人猜得到，
// 用户反馈"视频点边框都是预览、没法多选"就是因为这个入口不存在。
document.getElementById('selmode').addEventListener('click',function(){
  enterSelMode();
  updateSel();
});

document.getElementById('selall').addEventListener('click',function(){
  var list=filtered(),k=Object.keys(SEL).length;
  if(k>0&&k>=list.length){SEL={}}
  else{enterSelMode();for(var i=0;i<list.length;i++){SEL[list[i].key]=1}}
  var tiles=document.querySelectorAll('#grid .tile');
  for(var j=0;j<tiles.length;j++)tiles[j].classList.toggle('sel',!!SEL[tiles[j].dataset.key]);
  updateSel();
});

// 退出多选：清空勾选 + 收起所有复选框
document.getElementById('selcancel').addEventListener('click',function(){
  clearSel();
  var tiles=document.querySelectorAll('#grid .tile');
  for(var i=0;i<tiles.length;i++)tiles[i].classList.remove('sel');
  updateSel();
});

document.getElementById('dlsel').addEventListener('click',function(){
  var keys=Object.keys(SEL);if(!keys.length)return;
  var items=[],hasDir=false,i;
  for(i=0;i<keys.length;i++){
    var f=findByKey(keys[i]);
    if(f){items.push(f);if(f.isDir)hasDir=true;}
  }
  if(!items.length)return;
  if(hasDir){
    // 选中里含文件夹 → 整包下载，一次请求，避免浏览器弹一堆「允许多文件下载」
    var qs=[];
    for(i=0;i<items.length;i++){
      if(items[i].isDir)qs.push('p='+enc(items[i].key.slice(2)));
      else if(items[i].idx!==undefined)qs.push('i='+items[i].idx);
      else qs.push('p='+enc(items[i].key.slice(2)));
    }
    var a=document.createElement('a');
    a.href='/zip?'+qs.join('&');
    a.download='';
    document.body.appendChild(a);a.click();a.remove();
    toast('正在打包 '+items.length+' 项…');
    return;
  }
  // 全是文件 → 安卓逐个下载（更顺手，拿到的是原文件不是 zip）
  // iOS 例外：setTimeout 里触发的下载脱离了用户手势，第二个起会被 WebKit 静默丢弃，
  // 所以只能合成一个 zip 一次请求发出去。
  if(IS_IOS&&items.length>1){
    var iq=[];
    for(i=0;i<items.length;i++){
      if(items[i].isDir)iq.push('p='+enc(items[i].key.slice(2)));
      else if(items[i].idx!==undefined)iq.push('i='+items[i].idx);
      else iq.push('p='+enc(items[i].key.slice(2)));
    }
    var az=document.createElement('a');
    az.href='/zip?'+iq.join('&');
    az.download='';
    document.body.appendChild(az);az.click();az.remove();
    toast('已打包 '+items.length+' 个文件，去「文件」App 里解压');
    return;
  }
  items.forEach(function(f,k){
    setTimeout(function(){saveUrl(f)},k*400);
  });
  toast('开始下载 '+items.length+' 个文件');
});

document.getElementById('sortbtn').addEventListener('click',function(){
  SORT=SORTS[(SORTS.indexOf(SORT)+1)%SORTS.length];
  this.textContent=SORTNAME[SORT];
  render();
});

document.getElementById('viewbtn').addEventListener('click',function(){
  VIEW=VIEW==='grid'?'list':'grid';
  // 重设 className 时别把 selmode 冲掉，否则切个视图多选就没了
  document.getElementById('grid').className='grid'+(VIEW==='list'?' list':'')+(SELMODE?' selmode':'');
  this.textContent=VIEW==='list'?'网格':'列表';
});

document.getElementById('q').addEventListener('input',function(){
  Q=this.value.trim();render();
});

document.getElementById('up').addEventListener('click',function(){document.getElementById('f').click()});

/* ---------------- 上传：串行队列 + 实时进度 + 明确落点 ----------------
   原来直接 x.send(file)，既没有 onprogress（所以完全没进度），
   onload 也不看 status（所以服务端 500 了照样弹「已上传」）。
   现在一个文件一张卡片，串行发（手机写盘才是瓶颈，并发只会互相抢带宽
   还让服务端多个线程同时写 FUSE），失败时把真实原因摆出来。 */
var UPQ=[],UPRunning=false,UPOK=0;

function closeCard(el){
  clearTimeout(el._t);
  if(!el.parentNode)return;
  el.style.transition='opacity .24s,transform .24s';
  el.style.opacity='0';
  el.style.transform='translateX(26px)';
  setTimeout(function(){if(el.parentNode)el.parentNode.removeChild(el)},260);
}
function holdCard(el,ms){clearTimeout(el._t);el._t=setTimeout(function(){closeCard(el)},ms)}

function upTrim(){
  var box=document.getElementById('upq');
  // 连传多个文件时卡片会一路往上堆到屏幕外。
  // 只挤掉已经结束的，正在进行中的绝不删 —— 那是用户唯一能看到进度的地方。
  while(box.children.length>4){
    var k=box.children[0];
    if(k.className.indexOf('done')<0&&k.className.indexOf('err')<0)break;
    clearTimeout(k._t);
    box.removeChild(k);
  }
}

function upCard(file){
  var box=document.getElementById('upq');
  var el=document.createElement('div');
  el.className='c';
  el.innerHTML='<div class="r"><span class="nm"></span><span class="pc">0%</span></div>'
    +'<div class="bar"><i></i></div><div class="st"><span class="path">准备中…</span></div>';
  var nm=el.querySelector('.nm');
  nm.textContent=file.name;
  nm.title=file.name;
  box.appendChild(el);
  upTrim();
  return el;
}

function upFail(el,msg){
  el.className='c err';
  el.querySelector('.pc').textContent='失败';
  var body=el.querySelector('.st');
  body.innerHTML='';
  var t=document.createElement('span');
  t.className='path';t.textContent=msg;t.title=msg;
  body.appendChild(t);
  var b=document.createElement('button');
  b.className='lnk';b.textContent='关闭';
  b.onclick=function(){closeCard(el)};
  body.appendChild(b);
  holdCard(el,15000);          // 失败原因要留够时间看清
}

/* /storage/emulated/0/Download/简传Lite/a.jpg 对手机用户是天书，
   转成他们看得懂的「手机/Download/简传Lite/a.jpg」。
   这是安卓对外置存储的固定前缀，各厂商 ROM 一致。 */
function prettyPath(p){
  if(!p)return '';
  return String(p).replace(/^\/storage\/emulated\/\d+\//,'手机/').replace(/^\/sdcard\//,'手机/');
}

function upDone(el,res,file){
  el.className='c done';
  el.querySelector('.bar i').style.width='100%';
  el.querySelector('.pc').textContent='完成';
  var body=el.querySelector('.st');
  body.innerHTML='';
  var t=document.createElement('span');
  t.className='path';
  // 只报目录，不重复文件名 —— 文件名就在上面那行，塞进去反而被省略号截掉关键信息
  var dir=res.dir||String(res.path||'').replace(/[^/]+$/,'');
  t.textContent='已存到 '+prettyPath(dir);
  t.title=res.path||dir;       // 悬停看完整路径
  body.appendChild(t);
  if(res.dir){
    var b=document.createElement('button');
    b.className='lnk';b.textContent='打开';
    b.onclick=function(){closeCard(el);enterDir(res.dir)};
    body.appendChild(b);
  }
  holdCard(el,9000);
}

function upErrText(x,res){
  if(res&&res.error)return res.error;
  if(x.status===0)return '连接中断（手机断网、App 被切后台或锁屏）';
  if(x.status===413)return '文件超过服务端允许的大小';
  if(x.status===503)return '手机端繁忙，请稍后重试';
  if(x.status===500)return '手机端写入失败（最常见是剩余空间不足）';
  return 'HTTP '+x.status+(x.responseText?' · '+String(x.responseText).slice(0,120):'');
}

function runUpload(){
  if(UPRunning)return;
  var file=UPQ.shift();
  if(!file){
    // 队列空了才刷新一次列表：新文件已追加进索引，按时间排序会排在最前面
    if(UPOK>0){UPOK=0;if(MODE==='cat')loadCat(CAT);else if(MODE==='dir'&&CUR)enterDir(CUR)}
    return;
  }
  UPRunning=true;
  var el=upCard(file);
  var bar=el.querySelector('.bar i');
  var pc=el.querySelector('.pc');
  var st=el.querySelector('.path');
  var lastT=0,lastB=0,spd=0;
  var x=new XMLHttpRequest();
  x.open('POST','/api/upload?name='+encodeURIComponent(file.name));
  x.timeout=0;                 // 不限总时长；断流由服务端 180s 读超时兜底
  x.upload.onprogress=function(e){
    if(!e.lengthComputable)return;
    var now=Date.now();
    if(now-lastT<180)return;   // 节流：不要每个 TCP 分段都去改 DOM
    var dt=(now-lastT)/1000;
    var inst=(e.loaded-lastB)/dt;
    spd=spd>0?spd*0.7+inst*0.3:inst;   // 速度平滑，避免数字乱跳
    lastT=now;
    lastB=e.loaded;
    bar.style.width=(e.loaded/e.total*100).toFixed(1)+'%';
    pc.textContent=(e.loaded/e.total*100).toFixed(0)+'%';
    st.textContent=fmt(e.loaded)+' / '+fmt(e.total)+' · '+fmt(spd)+'/s';
  };
  x.onload=function(){
    var res=null;
    try{res=JSON.parse(x.responseText||'{}')}catch(e){}
    if(x.status>=200&&x.status<300&&res&&res.ok){UPOK++;upDone(el,res,file)}
    else upFail(el,upErrText(x,res));       // 关键：失败必须看得出来，不能报「已上传」
  };
  x.onerror=function(){upFail(el,upErrText({status:0},null))};
  x.onabort=function(){upFail(el,'已取消')};
  x.onloadend=function(){UPRunning=false;setTimeout(runUpload,60)};
  x.send(file);
}

document.getElementById('f').addEventListener('change',function(){
  var fs=this.files;
  if(!fs.length)return;
  for(var i=0;i<fs.length;i++)UPQ.push(fs[i]);
  this.value='';
  runUpload();
});

// ---------------- 预览（图片左右切换 / 视频音频可拖动进度） ----------------
var LB={list:[],i:0};

/**
 * 点视频 → 交给 App 内的原生播放器。
 *
 * 返回 true 表示已接管；浏览器里没有 AndroidBridge，返回 false 让调用方走网页预览。
 * 选集列表取整个 DATA（不是 filtered()）：用户搜索时只想打开当前这个，
 * 但选集和自动下一集应该覆盖这个目录里的全部视频。
 */
function openNative(key){
  if(!window.AndroidBridge||!AndroidBridge.openPlayer)return false;
  var vids=[],idx=0;
  for(var i=0;i<DATA.length;i++){
    var x=DATA[i];
    if(x.isDir||x.cat!=='video')continue;
    if(x.key===key)idx=vids.length;
    vids.push(x);
  }
  if(!vids.length)return false;
  var origin=location.protocol+'//'+location.host;
  var arr=[];
  for(var j=0;j<vids.length;j++){
    var f=vids[j];
    arr.push({name:f.name,url:origin+f.url,path:f.path||''});
  }
  try{AndroidBridge.openPlayer(JSON.stringify(arr),idx);return true;}
  catch(e){return false;}
}

function preview(key){
  var vis=filtered().filter(isMedia),idx=-1;
  for(var i=0;i<vis.length;i++)if(vis[i].key===key){idx=i;break;}
  if(idx<0)return;
  LB.list=vis;LB.i=idx;drawLb();
}

function drawLb(){
  var f=LB.list[LB.i];
  if(!f){closeLb();return;}
  var body=document.getElementById('lbbody'),html='';
  var u=esc(f.url);
  if(f.cat==='image'){
    html='<img id="lbmedia" src="'+u+'" alt="">';
  }else if(f.cat==='video'){
    html='<video id="lbmedia" src="'+u+'" controls autoplay playsinline preload="metadata"></video>';
  }else{
    html='<div class="audio-wrap"><div class="audio-ic">'+SVG_AUDIO+'</div>'
      +'<div class="audio-nm">'+esc(f.name)+'</div>'
      +'<audio id="lbmedia" src="'+u+'" controls autoplay preload="metadata"></audio></div>';
  }
  body.innerHTML=html;
  document.getElementById('lbinfo').textContent=f.name+' · '+(LB.i+1)+'/'+LB.list.length+' · '+fmt(f.size);
  // iOS：网页没有任何 API 能把文件写进「照片」，长按是唯一进相册的路。
  // 不写这句，用户会一直以为"只能预览、存不下来"。
  var hint=document.getElementById('lbhint');
  if(IS_IOS){
    hint.textContent=f.cat==='image'
      ? '长按图片选「存储到照片」可直接进相册 · 点右上「保存」下载到「文件」App'
      : '点右上「保存」下载到「文件」App · 视频不能直接存进相册';
    hint.className='lbhint on';
  }else{
    hint.className='lbhint';
  }
  // 只有音视频能给播放地址：图片和文档拿给电视播放器没有意义
  document.getElementById('lbcast').className =
    'lbcast'+((f.cat==='video'||f.cat==='audio')?' on':'');
  document.getElementById('lbprev').disabled=LB.i<=0;
  document.getElementById('lbnext').disabled=LB.i>=LB.list.length-1;
  document.getElementById('lb').className='lb on';
}

function closeLb(){
  var m=document.getElementById('lbmedia');
  if(m){try{m.pause()}catch(e){}}
  document.getElementById('lbbody').innerHTML='';
  document.getElementById('lbinfo').textContent='';
  document.getElementById('lbhint').textContent='';
  document.getElementById('lbhint').className='lbhint';
  document.getElementById('lbcast').className='lbcast';
  document.getElementById('lb').className='lb';
}

function stepLb(d){
  var n=LB.i+d;
  if(n<0||n>=LB.list.length)return;
  LB.i=n;drawLb();
}

document.getElementById('lbx').addEventListener('click',closeLb);

// 复制播放地址：粘到电视 / 投影仪的媒体播放器里，直接播这台手机上的片。
// 用 f.url（带 inline=1）而不是 f.dl：前者保留真实 MIME，播放器认得出是视频；
// 后者会被服务端压成 octet-stream —— 那是给"下载"用的。
document.getElementById('lbcast').addEventListener('click',function(e){
  e.stopPropagation();
  var f=LB.list[LB.i];if(!f)return;
  var host = LAN_IPS.length ? LAN_IPS[0] : location.hostname;
  var url = 'http://'+host+':'+SRV_PORT+f.url;
  copyText(url,function(ok){
    if(ok){toast('播放地址已复制，粘到电视 / 投影仪的播放器里即可');}
    else{window.prompt('长按下面的地址复制',url);}
  });
});
// 排查用：双击顶部标题显示平台判定结果。
// iPhone 上下不了时，这一行能直接区分「UA 没认出来」还是「服务端响应有问题」，
// 不用再靠猜。平时不影响任何界面。
document.getElementById('title').addEventListener('dblclick',function(){
  toast('iOS:'+IS_IOS+' · touch:'+navigator.maxTouchPoints+' · '+navigator.userAgent.slice(0,46));
});
// 预览页「保存」：iOS 上点卡片只会预览，没有别的下载入口，这个按钮是唯一可点的。
document.getElementById('lbsave').addEventListener('click',function(e){
  e.stopPropagation();
  var f=LB.list[LB.i];if(!f)return;
  saveUrl(f);
  toast(IS_IOS?'已发起下载，去「文件」App →「下载」里找':'开始下载 '+f.name);
});
// 预览页「选择」：关掉预览、进多选、把当前这张勾上。
// 看完一张想顺手多下几张，不用退出去再长按。
document.getElementById('lbsel').addEventListener('click',function(e){
  e.stopPropagation();
  var f=LB.list[LB.i];
  closeLb();
  if(!f)return;
  enterSelMode();
  SEL[f.key]=1;
  var tiles=document.querySelectorAll('#grid .tile');
  for(var i=0;i<tiles.length;i++){
    if(tiles[i].dataset.key===f.key)tiles[i].classList.add('sel');
  }
  updateSel();
  toast('已选中 1 个，再点其它文件继续选');
});
document.getElementById('lbprev').addEventListener('click',function(e){e.stopPropagation();stepLb(-1)});
document.getElementById('lbnext').addEventListener('click',function(e){e.stopPropagation();stepLb(1)});
document.getElementById('lb').addEventListener('click',function(e){
  if(e.target===this)closeLb();
});
document.addEventListener('keydown',function(e){
  if(document.getElementById('lb').className!=='lb on')return;
  if(e.key==='Escape')closeLb();
  else if(e.key==='ArrowLeft')stepLb(-1);
  else if(e.key==='ArrowRight')stepLb(1);
});

// 缩略图加载失败（视频抽帧失败、文件被删）退回类型图标，别在网格里留裂图
document.addEventListener('error',function(e){
  var t=e.target;
  if(!t||t.tagName!=='IMG'||!t.parentNode)return;
  if(t.parentNode.className!=='thumb')return;
  var tile=t.closest('.tile');
  t.parentNode.innerHTML='<div class="ph">'+iconSvg(tile?tile.dataset.cat:'other')+'</div>';
},true);

document.getElementById('ip').textContent=location.host+location.pathname;
fetchRoots();
loadCat('all');
</script>
</body>
</html>"""
    }
}
