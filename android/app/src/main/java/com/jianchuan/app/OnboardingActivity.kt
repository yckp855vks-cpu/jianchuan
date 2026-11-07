package com.jianchuan.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity

/**
 * 首次启动引导页：欢迎 → 后台待命设置 → 完成（共 3 页）。
 *
 * 设计（用户拍板，v1.13.3）：
 * - **只有「通知」做一键跳转 + 真实检测打勾**（有 POST_NOTIFICATIONS 权限可查）。
 * - **「自启动」「电池优化」只显示教程文字**，用户自己按路径去开。
 *   原因：这两个既没有公开 API 能读到真实状态（点了打勾是假的，用户一眼看穿），
 *   而且厂商跳转 ComponentName 常变、经常跳不过去（点了没反应）。给教程反而更实在。
 * - 教程路径**按手机品牌自动选**（小米/华为/荣耀/OPPO/vivo/三星/原生）。
 * - 「跳过」= 直接跳过整个引导进主界面。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var dots: LinearLayout
    private lateinit var primary: TextView
    private lateinit var skip: TextView

    private var statusNotify: TextView? = null
    private var notifyDesc: TextView? = null

    private var step = 0
    private val totalSteps = 3  // 欢迎 + 设置 + 完成

    private val prefs by lazy { getSharedPreferences("jc", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        flipper = findViewById(R.id.onb_flipper)
        dots = findViewById(R.id.onb_dots)
        primary = findViewById(R.id.onb_primary)
        skip = findViewById(R.id.onb_skip)

        buildPages()
        buildDots()
        renderStep(0)

        primary.setOnClickListener { onPrimary() }
        skip.setOnClickListener { finishOnboarding() }
    }

    override fun onResume() {
        super.onResume()
        // 只停在第 2 页才刷新，否则会把欢迎页的「开始设置」冲掉（v1.13.1 的 bug）
        if (step == 1) refreshNotify()
    }

    // ---------------------------------------------------------------- 页面构建
    private fun buildPages() {
        flipper.removeAllViews()
        flipper.addView(welcomePage())
        flipper.addView(setupPage())
        flipper.addView(donePage())
    }

    private fun welcomePage(): View {
        val wrap = pageWrap()
        wrap.addView(TextView(this).apply {
            text = "简传"
            textSize = 40f
            setTextColor(0xFF2F7FB8.toInt())
            gravity = Gravity.CENTER
        })
        wrap.addView(TextView(this).apply {
            text = "欢迎使用简传 Lite"
            textSize = 22f
            setTextColor(0xFF15222E.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        wrap.addView(TextView(this).apply {
            text = "传文件、传文字，还能把微信消息弹到电脑"
            textSize = 14f
            setTextColor(0xFF51667A.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), 0)
        })

        val feats = listOf(
            Triple("传文件", "手机电脑互传，局域网直连不过云", "↔"),
            Triple("传文字", "剪贴板双向同步，复制即到手", "⌨"),
            Triple("微信消息", "弹到电脑，不错过重要消息", "💬")
        )
        for ((name, desc, sym) in feats) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.onb_row)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            card.addView(TextView(this).apply {
                text = sym
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0xFF2F7FB8.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(0xFF15222E.toInt())
            })
            col.addView(TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(0xFF8296A6.toInt())
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(col)
            wrap.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }
        return wrap
    }

    /** 第 2 页：通知（可一键开）+ 自启动/电池优化（纯教程） */
    private fun setupPage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }

        wrap.addView(TextView(this).apply {
            text = "后台待命设置"
            textSize = 21f
            setTextColor(0xFF15222E.toInt())
            gravity = Gravity.CENTER
        })
        wrap.addView(TextView(this).apply {
            text = "通知可以一键开启，另两项按下面的路径手动设置"
            textSize = 13f
            setTextColor(0xFF8296A6.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(2))
        })

        val guide = guideForDevice()

        // ① 通知：唯一可一键开启 + 真实检测
        wrap.addView(notifyCard(), topGap(16))

        // ② 自启动：纯教程
        wrap.addView(guideCard("允许后台自启动",
            "让系统开机时放行简传，不被进程回收", guide.autostart), topGap(12))

        // ③ 电池优化：纯教程
        wrap.addView(guideCard("忽略电池优化",
            "息屏后不被系统限制后台，连接不断", guide.battery), topGap(12))

        scroll.addView(wrap)
        return scroll
    }

    /** 通知卡：右侧「去开启」按钮，点整行跳权限；已授权显示绿勾 */
    private fun notifyCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.onb_row)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "允许通知"
            textSize = 15f
            setTextColor(0xFF15222E.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        statusNotify = TextView(this).apply {
            text = "去开启"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.onb_btn_brand)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        top.addView(statusNotify)
        card.addView(top)
        notifyDesc = TextView(this).apply {
            text = "简传要在通知栏留一条常驻通知，才能稳定待在后台；微信消息推电脑也靠它"
            textSize = 12f
            setTextColor(0xFF8296A6.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(notifyDesc)
        card.setOnClickListener { goNotification() }
        return card
    }

    /** 纯教程卡：只有标题 + 说明 + 编号步骤，不可点、不检测、不打勾 */
    private fun guideCard(title: String, desc: String, steps: List<String>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.onb_row)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF15222E.toInt())
        })
        card.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF8296A6.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        // 步骤区：淡一点的小色块衬底，和说明区分开
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x0D2F7FB8)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        steps.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (i > 0) setPadding(0, dp(6), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.onb_btn_brand)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
            row.addView(TextView(this).apply {
                text = s
                textSize = 12.5f
                setTextColor(0xFF3D5365.toInt())
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            box.addView(row)
        }
        card.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        return card
    }

    private fun donePage(): View {
        val wrap = pageWrap()
        wrap.addView(TextView(this).apply {
            text = "✓"
            textSize = 60f
            setTextColor(0xFF12B886.toInt())
            gravity = Gravity.CENTER
        })
        wrap.addView(TextView(this).apply {
            text = "都设置好啦"
            textSize = 22f
            setTextColor(0xFF15222E.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        wrap.addView(TextView(this).apply {
            text = "简传现在能在后台常驻了。\n另外两项（自启动/电池优化）随时可以在设置里回来看教程。"
            textSize = 14f
            setTextColor(0xFF51667A.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), 0)
        })
        return wrap
    }

    private fun pageWrap(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(28), dp(40), dp(28), dp(20))
    }

    private fun topGap(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(v) }

    // ---------------------------------------------------------------- 各品牌教程路径
    private class Guide(val autostart: List<String>, val battery: List<String>)

    /**
     * 按手机品牌给出当前系统的最新设置路径（2026 年查询整理）。
     * 拿不准就落到「原生 Android」通用路径——那里的入口一直没变过。
     */
    private fun guideForDevice(): Guide {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || b.contains("poco") -> Guide(
                listOf(
                    "设置 → 应用设置 → 授权管理",
                    "自启动管理 → 找到「简传 Lite」打开开关"
                ),
                listOf(
                    "设置 → 应用设置 → 应用管理",
                    "简传 Lite → 省电策略 → 选「无限制」"
                )
            )

            m.contains("huawei") || m.contains("honor") || b.contains("honor") -> Guide(
                listOf(
                    "设置 → 应用和服务 → 应用启动管理",
                    "找到「简传 Lite」关掉「自动管理」",
                    "勾选「允许自启动」和「允许后台活动」"
                ),
                listOf(
                    "设置顶部搜索「电池优化」直接进",
                    "点「不允许」旁的小三角 → 选「所有应用」",
                    "找到「简传 Lite」→ 设为「不允许」"
                )
            )

            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ||
                b.contains("realme") || b.contains("oneplus") -> Guide(
                listOf(
                    "设置 → 应用 → 应用管理",
                    "右上角「自启动管理」→ 打开简传"
                ),
                listOf(
                    "设置 → 电池 → 更多设置",
                    "耗电保护 → 简传 Lite → 允许后台运行"
                )
            )

            m.contains("vivo") || m.contains("iqoo") || b.contains("iqoo") -> Guide(
                listOf(
                    "设置 → 应用与权限 → 权限管理",
                    "右上角切到「权限」→ 自启动 → 打开简传"
                ),
                listOf(
                    "设置 → 电池 → 后台耗电管理",
                    "找到「简传 Lite」→ 允许后台高耗电"
                )
            )

            m.contains("samsung") -> Guide(
                listOf(
                    "设置 → 电池和设备维护 → 电池",
                    "后台使用限制 → 从「休眠应用」里移除简传"
                ),
                listOf(
                    "设置 → 电池和设备维护 → 电池",
                    "后台使用限制 → 确认简传不在休眠列表"
                )
            )

            // 原生 Android / 其他：入口多年未变
            else -> Guide(
                listOf(
                    "设置 → 应用 → 简传 Lite",
                    "电池 → 选「不受限制」"
                ),
                listOf(
                    "设置 → 应用 → 特殊应用权限",
                    "电池优化 → 简传 Lite → 选「不优化」"
                )
            )
        }
    }

    // ---------------------------------------------------------------- 进度点
    private fun buildDots() {
        dots.removeAllViews()
        for (i in 0 until totalSteps) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                if (i > 0) marginStart = dp(8)
            }
            dot.layoutParams = lp
            dot.setBackgroundResource(R.drawable.onb_dot)
            dots.addView(dot)
        }
    }

    private fun renderStep(index: Int) {
        step = index
        flipper.displayedChild = index
        for (i in 0 until dots.childCount) {
            val d = dots.getChildAt(i)
            val lp = d.layoutParams as LinearLayout.LayoutParams
            if (i == index) {
                lp.width = dp(20); lp.height = dp(8)
                d.setBackgroundResource(R.drawable.onb_dot_active)
            } else {
                lp.width = dp(8); lp.height = dp(8)
                d.setBackgroundResource(R.drawable.onb_dot)
            }
            d.layoutParams = lp
        }
        when (index) {
            0 -> {
                primary.text = "开始设置"
                primary.setBackgroundResource(R.drawable.onb_btn_brand)
                skip.text = "跳过引导"
                skip.visibility = View.VISIBLE
            }
            1 -> {
                skip.text = "跳过引导"
                skip.visibility = View.VISIBLE
                refreshNotify()
            }
            2 -> {
                primary.text = "开始使用"
                primary.setBackgroundResource(R.drawable.onb_btn_brand)
                skip.text = "以后再说，先进入"
                skip.visibility = View.VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- 通知检测
    /**
     * 通知是否真的开着。
     *
     * 不能只看 POST_NOTIFICATIONS 权限（那是 Android 13 才有的）：
     * 13 以下如果写 `return true`，就变成"一进页面假打勾"——用户一眼看出不对。
     * 所以统一先问系统 `areNotificationsEnabled()`（API 24+ 一直可用），
     * 它反映的是通知总开关的真实状态（用户在设置里关过也会是 false）。
     */
    private fun notifyGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 24) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) return false
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun refreshNotify() {
        val granted = notifyGranted()
        statusNotify?.let { tv ->
            val lp = tv.layoutParams
            if (granted) {
                tv.text = "✓"
                tv.setBackgroundResource(R.drawable.onb_check)
                tv.setPadding(0, 0, 0, 0)
                lp.width = dp(22); lp.height = dp(22)
            } else {
                tv.text = "去开启"
                tv.setBackgroundResource(R.drawable.onb_btn_brand)
                tv.setPadding(dp(12), dp(6), dp(12), dp(6))
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            tv.layoutParams = lp
        }
        // 状态文字说清楚，避免"绿勾是不是假的"这种疑惑
        notifyDesc?.text = if (granted)
            "已开启 · 点这里可以去系统设置里确认或调整"
        else
            "简传要在通知栏留一条常驻通知，才能稳定待在后台；微信消息推电脑也靠它"
        // 主按钮跟着通知状态走：没开→去开启通知，开了→开始使用
        if (step == 1) {
            if (granted) {
                primary.text = "开始使用"
                primary.setBackgroundResource(R.drawable.onb_btn_brand)
            } else {
                primary.text = "去开启通知"
                primary.setBackgroundResource(R.drawable.onb_btn_dim)
            }
        }
    }

    // ---------------------------------------------------------------- 主按钮动作
    private fun onPrimary() {
        when (step) {
            0 -> renderStep(1)
            1 -> if (notifyGranted()) finishOnboarding() else goNotification()
            else -> finishOnboarding()
        }
    }

    // ---------------------------------------------------------------- 跳转
    /**
     * 通知：没开就走运行时权限（13+）；已开或老系统就跳系统通知设置，
     * 让用户能亲眼去核对开关、而不是只能信这个绿勾。
     */
    private fun goNotification() {
        if (Build.VERSION.SDK_INT >= 33 && !notifyGranted()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName"))
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refreshNotify()
    }

    private fun finishOnboarding() {
        prefs.edit().putBoolean("onboarded", true).apply()
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(i)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
