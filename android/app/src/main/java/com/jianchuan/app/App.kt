package com.jianchuan.app

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获。
 *
 * 为什么需要：这个 App 装在自己手机上，崩溃时拿不到 logcat，只能靠「打开就闪退」这种
 * 一句话描述来猜。把堆栈写到文件里，下次启动能直接看到，省掉一轮盲猜。
 */
class App : Application() {

    companion object {
        /** 崩溃日志文件名，MainActivity 启动时会读它并弹给用户看 */
        const val CRASH_FILE = "crash.log"
    }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 写日志本身绝不能再抛异常盖掉原始崩溃
            runCatching { dump(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dump(thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("时间: ").append(stamp).append('\n')
            append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append("  Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("线程: ").append(thread.name).append("\n\n")
            append(sw.toString())
        }
        // 外部私有目录是首选：路径 Android/data/com.jianchuan.app/files/，
        // 能用数据线或部分文件管理器取到。内部目录只作兜底。
        for (dir in listOfNotNull(getExternalFilesDir(null), filesDir)) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                File(dir, CRASH_FILE).writeText(text)
            }
        }
    }
}
