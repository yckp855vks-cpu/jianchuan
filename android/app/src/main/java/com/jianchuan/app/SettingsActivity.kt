package com.jianchuan.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * 设置页：替换原来「更多」里那个老掉牙的 AlertDialog 菜单。
 *
 * 晨雾玻璃拟物风格。**整页配色跟随主界面当前主题**（读 theme_id → PALETTES），
 * 不再写死浅色——深色主题下也不刺眼。
 *
 * 四组：后台与提醒 / 去广告规则 / 保活设置 / 关于
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var pal: Palette
    private lateinit var pushSwitch: Switch
    private lateinit var adSwitch: Switch

    private val importRuleLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                if (text.isBlank()) {
                    toast("文件为空")
                    return@registerForActivityResult
                }
                saveRule(text)
            }.onFailure { toast("读取失败：${it.message}") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeId = getSharedPreferences("jc", MODE_PRIVATE).getString("theme_id", "light")
        pal = PALETTES.firstOrNull { it.id == themeId } ?: PALETTES[0]
        window.statusBarColor = pal.topbar
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (pal.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildUi())
    }

    /**
     * 从系统设置页回来时校正开关。
     *
     * 两个坑：
     * 1) 系统写入 `ENABLED_ACCESSIBILITY_SERVICES` / `enabled_notification_listeners`
     *    有几百毫秒延迟。以前 onResume 里立刻查，刚授权完的人会被当成"没授权"，
     *    开关当场自己弹回去 —— 用户看到的就是"开关自己关了"。
     *    所以延迟 800ms 再校正。
     * 2) 万一真被关掉了（系统清理、用户手滑），要**说出来**，不能悄悄改状态。
     */
    override fun onResume() {
        super.onResume()
        if (!::adSwitch.isInitialized) return
        window.decorView.postDelayed({ reconcileSwitches() }, 800)
    }

    private fun reconcileSwitches() {
        if (adSwitch.isChecked && !AdSkipService.isEnabled(this)) {
            adSwitch.isChecked = false
            toast("无障碍服务当前未开启，开关已同步关闭")
        }
        if (pushSwitch.isChecked && !WechatNotificationService.isEnabled(this)) {
            pushSwitch.isChecked = false
            toast("通知使用权当前未开启，开关已同步关闭")
        }
    }

    // ---------------------------------------------------------------- UI 构建
    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(pal.topbar) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(36))
        }

        // 顶部：返回 + 标题
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(pal.text)
            background = rounded(Color.TRANSPARENT, Color.TRANSPARENT, 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "返回"
            setOnClickListener { finish() }
        }
        back.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        val title = TextView(this).apply {
            text = "设置"
            textSize = 22f
            setTextColor(pal.text)
            setPadding(dp(6), 0, 0, 0)
        }
        head.addView(back)
        head.addView(title)
        content.addView(head)

        // ===== 分组 1：后台与提醒 =====
        content.addView(sectionTitle("后台与提醒"))
        val g1 = glassCard()
        pushSwitch = Switch(this).also { s ->
            g1.addView(makeSwitch(s, "push_wechat", "微信通知推送", "把微信消息弹到电脑上 · 仅局域网"))
        }
        g1.addView(divider())
        adSwitch = Switch(this).also { s ->
            g1.addView(makeSwitch(s, "ad_skip", "自动跳过广告", "开屏「跳过 / 关闭」自动点击"))
        }
        content.addView(g1)

        // ===== 分组 2：去广告规则 =====
        content.addView(sectionTitle("去广告规则"))
        val g2 = glassCard()
        g2.addView(makeRow("导入去广告规则", "粘贴或导入 JSON 规则文件") { showImportRuleDialog() })
        content.addView(g2)

        // ===== 分组 3：保活设置 =====
        content.addView(sectionTitle("保活设置"))
        val g3 = glassCard()
        g3.addView(makeRow("重新走一遍引导", "自启动 / 电池优化 / 通知 的设置教程") {
            startActivity(Intent(this, OnboardingActivity::class.java))
        })
        content.addView(g3)

        // ===== 分组 4：关于 =====
        content.addView(sectionTitle("关于"))
        val g4 = glassCard()
        g4.addView(makeRow("简传 Lite", versionText()) {})
        content.addView(g4)

        scroll.addView(content)
        return scroll
    }

    private fun versionText(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrElse { "" }.let { "版本 $it · 局域网文件与文字互传，纯本地不经过服务器" }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(pal.text3)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(26); bottomMargin = dp(8) }
    }

    private fun glassCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(pal.card, pal.line, 18)
        setPadding(dp(16), dp(6), dp(16), dp(6))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(pal.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun makeRow(title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(13), 0, dp(13))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(pal.text)
        })
        row.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(pal.text3)
            setPadding(0, dp(3), 0, 0)
        })
        return row
    }

    /** 拟物化开关行：标题 + 开关在左，副标题跟在下面 */
    private fun makeSwitch(sw: Switch, key: String, title: String, sub: String): LinearLayout {
        val prefs = getSharedPreferences("jc", MODE_PRIVATE)
        sw.apply {
            this.text = title
            textSize = 15f
            setTextColor(pal.text)
            isChecked = prefs.getBoolean(key, false)
            setPadding(0, dp(13), 0, dp(13))
            // 轨道上不要「打开/关闭」文字：那是 Switch 的老式画法，挤在滑块边上很脏，
            // 状态看颜色和滑块位置就够了
            showText = false
            // 轨道/滑块跟着主题走，深色主题下不会糊成一片
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    pal.brand,
                    if (pal.dark) 0xFF2A4157.toInt() else 0xFFC3CCD6.toInt()
                )
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    if (pal.dark) pal.text else Color.WHITE,
                    Color.WHITE
                )
            )
        }

        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            if (!checked) return@setOnCheckedChangeListener
            // 打开时立刻确认授权；已授权就什么都不做，绝不把人往设置页赶
            when (key) {
                "push_wechat" -> if (!WechatNotificationService.isEnabled(this)) {
                    toast("需要先授权「通知使用权」")
                    WechatNotificationService.openSettings(this)
                }
                "ad_skip" -> if (!AdSkipService.isEnabled(this)) {
                    toast("需要先授权「无障碍服务」")
                    AdSkipService.openSettings(this)
                }
            }
        }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sw)
        box.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(pal.text3)
            setPadding(0, 0, 0, dp(10))
        })
        return box
    }

    // ---------------------------------------------------------------- 导入规则
    private fun showImportRuleDialog() {
        val input = EditText(this).apply {
            hint = "粘贴 JSON 规则"
            minLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(pal.text)
            setHintTextColor(pal.text3)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(pal.input, pal.line, 10)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(input)
            addView(Button(this@SettingsActivity).apply {
                text = "选择文件导入"
                setOnClickListener { importRuleLauncher.launch("*/*") }
            })
        }
        AlertDialog.Builder(this)
            .setTitle("导入去广告规则")
            .setMessage("格式：\n{\"包名\":{\"popup_rules\":[{\"id\":\"识别文本\",\"action\":\"点击文本\"}]}}")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) saveRule(t)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveRule(text: String) {
        runCatching { JSONObject(text) }.onFailure {
            toast("JSON 格式错误，请检查")
            return
        }
        runCatching {
            openFileOutput("ad_rules.json", Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
        }.onSuccess { toast("规则已导入") }
            .onFailure { toast("保存失败：${it.message}") }
    }

    // ---------------------------------------------------------------- 小工具
    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(1, stroke)
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
