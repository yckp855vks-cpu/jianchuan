package com.jianchuan.app

import android.content.Context

/**
 * 电视端页面（`/tv`）：给投影仪上的播放器外壳用的浏览页。
 *
 * 单独放 assets 而不是写成 Kotlin 字符串常量，是因为常量池里单个字符串有 64KB 上限 ——
 * 这个页面带上中文注释很容易顶到线，一旦超了报的是编译期错误，很难一眼看出原因。
 *
 * 读一次就缓存：页面在 APK 里不会变，每次请求都读一遍 assets 纯属浪费。
 * 读失败不缓存，避免一个偶发 IO 错误把整台电视永久钉在错误页上。
 */
object TvPage {

    @Volatile
    private var cached: String? = null

    fun html(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val s = try {
                context.assets.open("tv.html").use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (_: Throwable) {
                "<!doctype html><meta charset=\"utf-8\">" +
                    "<div style=\"font:600 24px sans-serif;color:#ccc;background:#0b1016;" +
                    "padding:40px;height:100vh\">电视页加载失败：assets/tv.html 读不到</div>"
            }
            if (s.isNotEmpty()) cached = s
            return s
        }
    }
}
