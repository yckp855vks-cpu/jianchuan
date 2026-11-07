package com.jianchuan.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 前台服务：后台保活的命根子。
 *
 * 为什么需要它：Android 会把「切到后台、长时间不交互」的进程当低优先级回收。
 * 简传要随时能被电脑连上（服务端模式）、随时收微信通知推给电脑，就要求进程常驻。
 * 前台服务是系统唯一认可的「合法常驻」方式——挂一条常驻通知，进程就不会被轻易杀。
 *
 * 职责边界（只做一件事）：挂前台通知，把进程优先级顶高，保证不被系统回收。
 * 它**不**搬运业务逻辑——服务端传输（MobileServer）、剪贴板同步（ClipBridge）等
 * 仍由 MainActivity 持有，只要进程活着，这些对象就继续工作。搬运反而会引入
 * ClipBridge 双实例冲突和大量 UI 回调解耦，得不偿失。
 *
 * 微信通知监听（WechatNotificationService）、跳广告（AdSkipService）是另外两个独立的
 * 系统服务，各自注册，不在这里实现，避免一个服务挂了把三个功能全拖垮。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "jianchuan_keepalive"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Android 14+ 必须显式声明前台服务类型，否则抛 MissingForegroundServiceTypeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, buildNotification("后台待命 · 随时收发"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("后台待命 · 随时收发"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被系统杀后尽量重启，保证保活不断
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "后台保活", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持简传在后台运行，随时收发文件与消息"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("简传 Lite")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
