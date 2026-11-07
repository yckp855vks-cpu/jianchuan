package com.jianchuan.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 微信通知 → 电脑弹窗。
 *
 * 原理：注册为系统「通知使用权」服务后，任何 App 发通知，系统都会回调 onNotificationPosted。
 * 系统层面无法只申请微信一个 App（授权提示永远写「读取所有通知」），所以在这里**只挑微信的
 * 转发，其余一律丢弃**。
 *
 * 隐私边界（用户拍板）：
 * - 只推微信（包名 com.tencent.mm）；
 * - 只走局域网，绝不走公网（不经过任何云中转）；
 * - 默认关闭，用户主动开。
 */
class WechatNotificationService : NotificationListenerService() {

    companion object {
        private const val WECHAT_PKG = "com.tencent.mm"

        /** 是否已授权通知使用权（同样要兼容全名/短名两种写法） */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, WechatNotificationService::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            if (flat.isBlank()) return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            for (entry in splitter) {
                val cn = ComponentName.unflattenFromString(entry) ?: continue
                if (cn.packageName != expected.packageName) continue
                val cls = if (cn.className.startsWith(".")) cn.packageName + cn.className else cn.className
                if (cls == expected.className) return true
            }
            return false
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        // 只推微信，其余 App 的通知一律忽略
        if (sbn.packageName != WECHAT_PKG) return
        // 用户没主动打开这个功能就不推
        val prefs = getSharedPreferences("jc", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("push_wechat", false)) return
        if (!prefs.getBoolean("push_enabled", false)) return

        val n: Notification = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val lanUrl = prefs.getString("lan_url", "") ?: ""
        if (lanUrl.isBlank()) return
        if (!isPrivateLan(lanUrl)) return  // 只走局域网，公网地址直接丢弃

        push(lanUrl, title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    /** 只允许局域网地址（10./192.168./172.16-31.），公网域名/IP 一律不推 */
    private fun isPrivateLan(url: String): Boolean {
        val host = runCatching {
            val h = URL(url).host
            if (h.isBlank()) URL("http://$url").host else h
        }.getOrNull() ?: return false
        return host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            (host.startsWith("172.") && host.substringAfter("172.").substringBefore(".")
                .toIntOrNull()?.let { it in 16..31 } == true) ||
            host == "localhost" || host == "127.0.0.1"
    }

    private fun push(lanUrl: String, title: String, text: String) {
        thread {
            runCatching {
                val base = lanUrl.trimEnd('/')
                val conn = URL("$base/api/wechat_notify").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().apply {
                    put("app", "微信")
                    put("title", title)
                    put("text", text)
                }.toString()
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                conn.responseCode  // 触发请求
            }
        }
    }
}
