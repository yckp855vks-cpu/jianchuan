package com.gathin.tvplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：WebView 套壳 + 顶部工具条。
 * 列表页点视频时，拦截 /play?path= 交给原生播放器。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "tvplayer";
    private static final String KEY_LAST = "last_url";
    private static final String KEY_RECENT = "recent_urls";
    /** 被顶掉的地址（曾经连过、当前不在线），"\n" 分隔。它重新上线时提示用户切回去 */
    private static final String KEY_OFFLINE = "offline_urls";
    /** 上次连接的服务类型（media / jianchuan），两台都在时按它决定连哪台 */
    private static final String KEY_LAST_KIND = "last_kind";
    private static final String DEFAULT_URL = "";   // 留空：启动后自动扫局域网

    /** 首次启动、还没扫到任何服务时的占位页（深色，跟播放器一致） */
    private static final String SPLASH_HTML =
            "<html><head><meta charset='utf-8'>"
            + "<style>html,body{height:100%;margin:0;background:#0b1018;color:#cfe0f0;"
            + "font-family:sans-serif;display:flex;align-items:center;justify-content:center}"
            + "div{text-align:center}h2{font-size:34px;font-weight:500;margin:0 0 14px}"
            + "p{font-size:20px;color:#7d90a4;margin:6px 0}</style></head><body><div>"
            + "<h2>正在扫描局域网…</h2>"
            + "<p>自动寻找电脑的 MediaShare(8848) 和手机的简传 Lite(8788)</p>"
            + "<p>没反应就长按「返回」键，切播放源或手动输入地址</p>"
            + "</div></body></html>";
    private static final int MAX_RECENT = 5;
    private static final int MAX_OFFLINE = 5;
    private static final long SCAN_BUDGET_MS = 6000L;
    private static final long OFFLINE_WATCH_MS = 20000L;
    /** 工具条停留时间。原来是 3 秒，遥控器还没挪到按钮上就没了，等于没法用 */
    private static final long TOOLBAR_HOLD_MS = 12000L;

    /** 深底配色，和电视页保持一致 */
    private static final int C_BG = 0xFF0B1016;
    private static final int C_CARD = 0xFF131B24;
    private static final int C_CARD_ON = 0xFF17222D;
    private static final int C_LINE = 0xFF1F2A36;
    private static final int C_TEXT = 0xFFE6EEF6;
    private static final int C_SUB = 0xFF8FA0B0;
    private static final int C_DIM = 0xFF61707F;
    private static final int C_ACCENT = 0xFF4FA8E8;
    private static final int C_OK = 0xFF3ECF8E;

    /**
     * 往网页里塞一个常驻的「切换源」按钮。
     * 只塞进真正的 MediaShare 页面（有 header），并且必须排在搜索框左边：
     * 搜索框一被聚焦就弹软键盘，遥控器会被键盘接管，放在它后面就按不过去。
     */
    private static final String INJECT_SRC_BTN =
            "(function(){try{"
                    + "var h=document.querySelector('header');if(!h)return;"
                    + "var old=document.getElementById('jcSrcBtn');"
                    + "if(old&&old.parentNode)old.parentNode.removeChild(old);"
                    + "var b=document.createElement('button');"
                    + "b.id='jcSrcBtn';b.type='button';b.textContent='\u5207\u6362\u6e90';"
                    + "b.style.cssText='flex:none;margin-right:10px;padding:10px 18px;border-radius:12px;"
                    + "border:1px solid #3a7ca8;background:rgba(47,127,184,.18);color:#cfe6f7;"
                    + "font:inherit;font-size:17px;line-height:1.1;cursor:pointer;';"
                    + "b.onclick=function(){try{window.TVBridge.openSources();}catch(e){}};"
                    + "b.onfocus=function(){b.style.background='#2f7fb8';b.style.color='#06121a';"
                    + "b.style.borderColor='#7cc4ee';};"
                    + "b.onblur=function(){b.style.background='rgba(47,127,184,.18)';"
                    + "b.style.color='#cfe6f7';b.style.borderColor='#3a7ca8';};"
                    + "if(document.body.classList.contains('tv')){"
                    + "b.style.fontSize='20px';b.style.padding='13px 22px';}"
                    + "var s=h.querySelector('.search');"
                    + "if(s){h.insertBefore(b,s);}else{h.appendChild(b);}"
                    + "}catch(e){}})();";

    private WebView web;
    private LinearLayout toolbar;
    private TextView addrView;
    private View topStrip;

    /** 播放源界面（全屏覆盖层），null 表示没开 */
    private FrameLayout sheet;
    private TextView banner;

    private final List<ServerFinder.Server> online = new ArrayList<>();
    private List<String> offline = new ArrayList<>();
    /** 已经提示过「X 回来了」的地址，避免每 20 秒重复弹 */
    private String noticedUrl;
    private boolean discovering;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideToolbarTask = new Runnable() {
        @Override
        public void run() {
            hideToolbar();
        }
    };

    private boolean toolbarVisible = false;
    private long lastBackTime = 0L;
    /** 返回键被长按过（用来区分长按开菜单 / 短按返回） */
    private boolean backLongPressed = false;
    private boolean backShortPending = false;
    private volatile boolean atRoot = true;   // 网页当前是否在根目录（由 JS bridge 同步）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupWeb();
        root.addView(web);

        topStrip = buildTopStrip();
        root.addView(topStrip);

        toolbar = buildToolbar();
        root.addView(toolbar);

        banner = buildBanner();
        root.addView(banner);

        // 播放源界面：平时隐藏，工具条里点「播放源」或找不到服务器时弹出来
        sheet = new FrameLayout(this);
        sheet.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.setBackgroundColor(C_BG);
        sheet.setClickable(true);
        sheet.setFocusable(true);
        sheet.setVisibility(View.GONE);
        root.addView(sheet);

        setContentView(root);

        String start = getIntent().getStringExtra("url");
        if (TextUtils.isEmpty(start)) start = prefs().getString(KEY_LAST, DEFAULT_URL);
        if (TextUtils.isEmpty(start)) start = DEFAULT_URL;

        // 直接给一个片源地址就开播（可以从外部用 intent 唤起）
        String media = getIntent().getStringExtra("media");
        if (!TextUtils.isEmpty(media)) {
            String t = getIntent().getStringExtra("title");
            PlayerActivity.start(this, media, t == null ? "" : t, "", "");
        } else if (TextUtils.isEmpty(start)) {
            // 没有任何历史地址：先放一页占位，等 autoDiscover() 扫到就自动跳过去
            web.loadData(SPLASH_HTML, "text/html; charset=utf-8", "utf-8");
        } else {
            loadUrl(start);
        }
        web.requestFocus();
        offline = getOffline();
        autoDiscover();

        // 首次启动提一次：遥控器上没有菜单键，长按返回才是入口。
        // 只提示一次，之后靠习惯，不再每次开机弹。
        if (!prefs().getBoolean("hint_menu", false)) {
            prefs().edit().putBoolean("hint_menu", true).apply();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showBanner("长按「返回」键打开菜单：切换播放源 / 改地址", 7000);
                }
            }, 3500);
        }
    }

    /**
     * 启动时把能用的源过一遍。
     *
     * 和旧版的区别：上次的地址只要端口还通就绝不动它。旧版扫到别的源会直接顶掉，
     * 用户改回手机之后，某次手机没开服务就被换回电脑，下次又得重新手输。
     */
    private void autoDiscover() {
        if (discovering) return;
        final String saved = prefs().getString(KEY_LAST, "");
        discovering = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean savedAlive = !TextUtils.isEmpty(saved) && ServerFinder.alive(saved);
                if (savedAlive) {
                    // 还通：保留当前页面，只顺手认一下它是谁（认不出也无所谓）
                    ServerFinder.Server s = ServerFinder.probeUrl(saved);
                    final List<ServerFinder.Server> one = new ArrayList<>();
                    if (s != null) one.add(s);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            discovering = false;
                            online.clear();
                            online.addAll(one);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showBanner("没连上上次的源，正在找局域网里的播放源…", 6000);
                    }
                });
                final List<ServerFinder.Server> found = ServerFinder.scan(SCAN_BUDGET_MS);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        discovering = false;
                        onScanDone(found, true);
                    }
                });
            }
        }).start();
    }
    /** 扫描结束。startup=true 表示这是启动时的自动发现，需要决定连哪台 */
    private void onScanDone(List<ServerFinder.Server> found, boolean startup) {
        online.clear();
        online.addAll(found);

        if (!startup) {
            showSheet(null);
            return;
        }
        if (found.isEmpty()) {
            showSheet(null);
            return;
        }
        // 当前连的那台就在结果里：什么都不做
        String cur = prefs().getString(KEY_LAST, "");
        for (ServerFinder.Server s : found) {
            if (sameTarget(s.url, cur)) return;
        }
        if (found.size() == 1) {
            connectTo(found.get(0), true);
            return;
        }
        // 多台：优先回上次用的那一类（上次用手机就还连手机）
        String prefer = prefs().getString(KEY_LAST_KIND, "");
        if (!TextUtils.isEmpty(prefer)) {
            for (ServerFinder.Server s : found) {
                if (s.kind.equals(prefer)) {
                    connectTo(s, true);
                    return;
                }
            }
        }
        // 多台且没有偏好：第一次用，让用户自己挑
        showSheet(null);
    }

    /**
     * 切到某个源。
     *
     * 被换下来的地址会记进「离线」清单，它重新上线时会有提示条把用户叫回来
     * ——这就是「手机后台掉了，等下怎么重连」的答案：不用手动做什么。
     */
    private void connectTo(ServerFinder.Server s, boolean notice) {
        String cur = prefs().getString(KEY_LAST, "");
        if (!TextUtils.isEmpty(cur) && !sameTarget(cur, s.url)) {
            addOffline(cur);
            noticedUrl = null;
        }
        loadUrl(s.url);
        prefs().edit().putString(KEY_LAST_KIND, s.kind).apply();
        removeOffline(s.url);
        if (notice) {
            showBanner("已连接 " + s.name + " · " + s.host + ":" + s.port, 3500);
        }
    }

    /** 在界面上重新扫一遍（不改变当前页面） */
    private void rescan() {
        if (discovering) return;
        discovering = true;
        showSheet("正在扫描局域网…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ServerFinder.Server> found = ServerFinder.scan(SCAN_BUDGET_MS);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        discovering = false;
                        onScanDone(found, false);
                    }
                });
            }
        }).start();
    }

    // ---------------- 离线源的回归监听 ----------------

    /**
     * 每 20 秒探一次「被顶掉的地址」，谁回来了就顶部提示一下。
     * 只提示、不自动切 —— 你要是正在电脑上看片，被强行切走会很烦。
     */
    private final Runnable offlineWatch = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            handler.removeCallbacks(this);
            handler.postDelayed(this, OFFLINE_WATCH_MS);

            final String cur = prefs().getString(KEY_LAST, "");
            final List<String> alts = getOffline();
            if (alts.isEmpty()) return;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (final String u : alts) {
                        if (sameTarget(u, cur)) continue;
                        if (u.equals(noticedUrl)) continue;
                        if (!ServerFinder.alive(u)) continue;
                        ServerFinder.Server s = ServerFinder.probeUrl(u);
                        final String label = s != null ? s.name : hostPortOf(u);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
                                if (sheet != null && sheet.getVisibility() == View.VISIBLE) return;
                                noticedUrl = u;
                                showBanner(label + " 已重新上线 · 点屏幕顶部切换", 9000);
                            }
                        });
                        return;
                    }
                }
            }).start();
        }
    };

    // ---------------- 播放源界面 ----------------

    /** 打开播放源界面。hint 非空时替换副标题（扫描中、找不到之类） */
    private void showSheet(String hint) {
        if (sheet == null) return;
        hideToolbar();
        sheet.removeAllViews();
        sheet.setVisibility(View.VISIBLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(48), dp(38), dp(48), dp(30));
        sheet.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("选择播放源");
        title.setTextColor(C_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText(TextUtils.isEmpty(hint) ? sheetSubtitle() : hint);
        sub.setTextColor(TextUtils.isEmpty(hint) ? C_SUB : C_ACCENT);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        box.addView(sub, slp);

        String cur = prefs().getString(KEY_LAST, "");
        TextView curView = new TextView(this);
        curView.setText("当前：" + (TextUtils.isEmpty(cur) ? "无" : cur));
        curView.setTextColor(C_DIM);
        curView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(6);
        box.addView(curView, clp);

        // 卡片横向排；设备多于 3 个时能左右滚
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrLp.topMargin = dp(26);
        box.addView(scroll, scrLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View first = null;
        for (ServerFinder.Server s : online) {
            View c = sourceCard(s, sameTarget(s.url, cur));
            row.addView(c);
            if (first == null) first = c;
        }
        // 被顶掉的那些：灰着摆出来，按 OK 就重连
        for (String u : getOffline()) {
            boolean dup = false;
            for (ServerFinder.Server s : online) {
                if (sameTarget(s.url, u)) dup = true;
            }
            if (dup || sameTarget(u, cur)) continue;
            View c = offlineCard(u);
            row.addView(c);
            if (first == null) first = c;
        }
        if (row.getChildCount() == 0) {
            View c = noteCard("一台都没找到", "确认手机开着简传、电脑开着媒体库，并且和投影仪连同一个 Wi-Fi",
                    C_DIM, null);
            row.addView(c);
            if (first == null) first = c;
        }

        View rescanCard = noteCard("重新扫描", "再找一遍局域网里的设备", C_ACCENT, new Runnable() {
            @Override
            public void run() {
                rescan();
            }
        });
        row.addView(rescanCard);

        View manualCard = noteCard("手动输入地址", "扫描找不到时用这个", C_SUB, new Runnable() {
            @Override
            public void run() {
                showAddressDialog();
            }
        });
        row.addView(manualCard);

        TextView footer = new TextView(this);
        footer.setText("方向键选择 · OK 连接 · 返回键关掉");
        footer.setTextColor(C_DIM);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(20);
        box.addView(footer, flp);

        if (first != null) first.requestFocus();
    }

    private String sheetSubtitle() {
        if (discovering) return "正在扫描局域网…";
        int n = online.size();
        if (n == 0) return "还没找到设备，按「重新扫描」找一找";
        if (n == 1) return "找到 1 台";
        return "找到 " + n + " 台 · 会记住你上次用的那台";
    }

    private void closeSheet() {
        if (sheet == null) return;
        sheet.setVisibility(View.GONE);
        if (web != null) web.requestFocus();
    }

    /** 在线设备卡 */
    private View sourceCard(final ServerFinder.Server s, boolean isCurrent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setFocusable(true);
        c.setClickable(true);
        c.setPadding(dp(20), dp(16), dp(20), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(300), dp(232));
        lp.rightMargin = dp(16);
        c.setLayoutParams(lp);

        boolean isPhone = ServerFinder.KIND_JIANCHUAN.equals(s.kind);
        int accent = isPhone ? C_OK : C_ACCENT;

        View strip = new View(this);
        strip.setBackgroundColor(accent);
        c.addView(strip, new LinearLayout.LayoutParams(dp(38), dp(4)));

        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTextColor(C_TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(18);
        c.addView(name, nlp);

        TextView addr = new TextView(this);
        addr.setText(s.host + ":" + s.port);
        addr.setTextColor(C_SUB);
        addr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(8);
        c.addView(addr, alp);

        TextView kind = new TextView(this);
        kind.setText(isPhone ? "简传Lite · 手机上的片子" : "MediaShare · 电脑上的片子");
        kind.setTextColor(C_DIM);
        kind.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(6);
        c.addView(kind, klp);

        TextView state = new TextView(this);
        state.setText(isCurrent ? "● 正在使用" : "● 在线 · 按 OK 连接");
        state.setTextColor(isCurrent ? C_OK : C_ACCENT);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(14);
        c.addView(state, stp);

        focusableCard(c, isCurrent);
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectTo(s, true);
                closeSheet();
            }
        });
        return c;
    }

    /** 掉线的设备卡：按 OK 现场重探一次 */
    private View offlineCard(final String url) {
        return noteCard("离线 · " + hostPortOf(url),
                "按 OK 重新连（连不上就是服务没开）", C_DIM, new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "正在连 " + hostPortOf(url), Toast.LENGTH_SHORT).show();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final ServerFinder.Server s = ServerFinder.probeUrl(url);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (s != null) {
                                            noticedUrl = null;
                                            connectTo(s, true);
                                            closeSheet();
                                        } else {
                                            Toast.makeText(MainActivity.this, "还是连不上，检查手机端是否开着服务",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                });
    }

    /** 通用卡片（重新扫描 / 手动地址 / 空状态） */
    private View noteCard(String title, String sub, int accent, final Runnable onClick) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(16), dp(20), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), dp(214));
        lp.rightMargin = dp(16);
        c.setLayoutParams(lp);

        View strip = new View(this);
        strip.setBackgroundColor(accent);
        c.addView(strip, new LinearLayout.LayoutParams(dp(38), dp(4)));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(18);
        c.addView(t, tlp);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(C_DIM);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        c.addView(s, slp);

        if (onClick != null) {
            c.setFocusable(true);
            c.setClickable(true);
            focusableCard(c, false);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClick.run();
                }
            });
        } else {
            c.setFocusable(false);
            c.setBackground(roundRect(C_CARD, C_LINE, dp(14)));
        }
        return c;
    }

    /** 焦点反馈：描边 + 微放大。少一层远距离都看不清焦点在哪 */
    private void focusableCard(final View c, boolean isCurrent) {
        final int base = isCurrent ? C_CARD_ON : C_CARD;
        final GradientDrawable normal = roundRect(base, isCurrent ? C_OK : C_LINE, dp(14));
        final GradientDrawable focused = roundRect(C_CARD_ON, C_ACCENT, dp(14));
        c.setBackground(normal);
        c.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? focused : normal);
                float sc = has ? 1.04f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start();
            }
        });
    }

    private GradientDrawable roundRect(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(2), stroke);
        return d;
    }

    // ---------------- 顶部提示条 ----------------

    private TextView buildBanner() {
        TextView t = new TextView(this);
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), dp(11), dp(22), dp(11));
        t.setBackground(roundRect(0xF21A2430, C_LINE, dp(24)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(14);
        t.setLayoutParams(lp);
        t.setAlpha(0f);
        t.setVisibility(View.GONE);
        t.setFocusable(false);
        t.setClickable(false);
        return t;
    }

    private void showBanner(String text, long ms) {
        if (banner == null) return;
        banner.setText(text);
        banner.setVisibility(View.VISIBLE);
        banner.animate().alpha(1f).setDuration(180).start();
        handler.removeCallbacks(hideBannerTask);
        handler.postDelayed(hideBannerTask, ms);
    }

    private final Runnable hideBannerTask = new Runnable() {
        @Override
        public void run() {
            if (banner == null) return;
            banner.animate().alpha(0f).setDuration(240).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (banner != null) banner.setVisibility(View.GONE);
                }
            }).start();
        }
    };

    // ---------------- 地址工具 ----------------

    /** 只比 host:port，路径差异（MediaShare 首页 / 简传 /tv）不算换设备 */
    private static boolean sameTarget(String a, String b) {
        String x = hostPortOf(a);
        return !x.isEmpty() && x.equals(hostPortOf(b));
    }

    /**
     * 屏幕上真正在显示的那个地址。
     *
     * 不能直接用 KEY_LAST：切换源时地址先被写进 KEY_LAST，但新页面可能加载失败
     * （对方服务没开），失败后 WebView 仍然停在旧页面上——这时候如果按 KEY_LAST 判断
     * "谁在用"，就会把没在显示的那台标成绿色「正在使用」。
     */
    private String liveUrl() {
        String u = (web == null) ? null : web.getUrl();
        if (TextUtils.isEmpty(u)) u = prefs().getString(KEY_LAST, "");
        return u == null ? "" : u;
    }

    private static String hostPortOf(String url) {
        if (url == null) return "";
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            String h = u.getHost();
            if (h == null) return "";
            int p = u.getPort();
            return h + ":" + (p > 0 ? p : 80);
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> getOffline() {
        List<String> list = new ArrayList<>();
        String raw = prefs().getString(KEY_OFFLINE, "");
        if (!TextUtils.isEmpty(raw)) {
            for (String s : raw.split("\\n")) {
                if (!TextUtils.isEmpty(s.trim())) list.add(s.trim());
            }
        }
        return list;
    }

    private void addOffline(String url) {
        if (TextUtils.isEmpty(url)) return;
        List<String> list = getOffline();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (sameTarget(list.get(i), url)) list.remove(i);
        }
        list.add(0, url);
        while (list.size() > MAX_OFFLINE) list.remove(list.size() - 1);
        prefs().edit().putString(KEY_OFFLINE, TextUtils.join("\n", list)).apply();
        offline = list;
    }

    private void removeOffline(String url) {
        List<String> list = getOffline();
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (sameTarget(list.get(i), url)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) {
            prefs().edit().putString(KEY_OFFLINE, TextUtils.join("\n", list)).apply();
        }
        offline = list;
    }

    // ---------------- WebView ----------------

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 服务器靠 UA 里的 Android/TV 判定电视大字号模式，追加 TV 标记
        String ua = s.getUserAgentString();
        if (ua == null) ua = "";
        if (!ua.contains(" TV")) {
            s.setUserAgentString(ua + " TV");
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handlePlayUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handlePlayUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面加载完就补一个「切换源」按钮，遥控器不用去翻隐藏工具条
                view.evaluateJavascript(INJECT_SRC_BTN, null);
            }

            /**
             * 主框架加载失败（服务关了、手机进程被杀了）时给个提示。
             * API 23+ 会走新重载，它的默认实现会落到这里，所以只覆盖旧签名就够。
             * 必须比对 failingUrl 和当前 URL，否则图片之类的子资源失败也会弹。
             */
            @Override
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                if (failingUrl != null && failingUrl.equals(view.getUrl())) {
                    showBanner("连不上 " + hostPortOf(failingUrl) + " · 点屏幕顶部换个源", 8000);
                }
            }
        });

        // 网页把「是否在根目录」同步给壳，Android 返回键据此判断回上一级还是退出
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void setAtRoot(boolean root) {
                atRoot = root;
            }

            /** 网页右上角那个「切换源」按钮点进来：直接开播放源界面 */
            @JavascriptInterface
            public void openSources() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (online.isEmpty() && !discovering) {
                            rescan();
                        } else {
                            showSheet(null);
                        }
                    }
                });
            }

            /**
             * 置顶名单（JSON 数组字符串）。
             * 存在壳里而不是网页 localStorage：localStorage 跟着 origin 走，
             * 手机换个 IP 就成了新站点，置顶全丢；壳里的键跟地址无关。
             */
            @JavascriptInterface
            public String getPins() {
                SharedPreferences sp = getSharedPreferences("tvplayer_pins", MODE_PRIVATE);
                return sp.getString("list", "");
            }

            @JavascriptInterface
            public void setPins(String json) {
                if (json == null) return;
                SharedPreferences sp = getSharedPreferences("tvplayer_pins", MODE_PRIVATE);
                sp.edit().putString("list", json).apply();
            }
        }, "TVBridge");
    }

    /** 命中 /play?path= 就启动原生播放页，返回 true 表示不加载网页 */
    private boolean handlePlayUrl(String url) {
        if (url == null) return false;
        if (url.indexOf("/play?path=") < 0 && url.indexOf("/play/?path=") < 0) return false;

        String abs = url;
        if (!abs.startsWith("http://") && !abs.startsWith("https://")) {
            String base = web.getUrl();
            if (base == null) return false;
            try {
                abs = new java.net.URL(new java.net.URL(base), url).toString();
            } catch (Exception e) {
                return false;
            }
        }

        Uri u = Uri.parse(abs);
        String path = u.getQueryParameter("path");
        if (TextUtils.isEmpty(path)) return false;
        String host = u.getHost();
        if (TextUtils.isEmpty(host)) return false;
        int port = u.getPort();

        StringBuilder sb = new StringBuilder("http://").append(host);
        if (port > 0 && port != 80) sb.append(':').append(port);
        sb.append("/media/");

        // 逐段编码：空格、中文、[] & 都必须逃逸，不能整串 encode
        String[] segs = path.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(Uri.encode(segs[i]));
        }
        String mediaUrl = sb.toString();

        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);

        // 网页会带上清洗过的片名（第 27 集 · BLEACH ...），没有才退回文件名
        String pretty = u.getQueryParameter("title");
        if (pretty != null && pretty.trim().length() > 0) name = pretty.trim();

        // 传服务器地址和文件相对路径，播放器才能拉集数列表
        String baseUrl = "http://" + host + (port > 0 && port != 80 ? ":" + port : "");

        PlayerActivity.start(this, mediaUrl, name, path, baseUrl);
        return true;
    }

    private void loadUrl(String raw) {
        String url = normalize(raw);
        if (url == null) return;
        addrView.setText(url);
        web.loadUrl(url);
        prefs().edit().putString(KEY_LAST, url).apply();
        pushRecent(url);
    }

    private String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        return s;
    }

    // ---------------- 工具条 ----------------

    private View buildTopStrip() {
        // 顶部一条透明可聚焦区域：遥控器按上/点击顶部都能唤出工具条
        View v = new View(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.gravity = Gravity.TOP;
        v.setLayoutParams(lp);
        // 不能设成 focusable：否则网页里按到最上面一行时焦点会被它抢走，遥控器就失灵了
        v.setFocusable(false);
        v.setClickable(true);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showToolbar(true);
            }
        });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) showToolbar(true);
            }
        });
        return v;
    }

    private LinearLayout buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE6101010);
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        bar.setLayoutParams(lp);
        bar.setVisibility(View.INVISIBLE);

        Button back = flatButton("←", 60);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web.canGoBack()) web.goBack();
                hideToolbar();
            }
        });
        bar.addView(back);

        addrView = new TextView(this);
        addrView.setTextColor(Color.WHITE);
        addrView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        addrView.setSingleLine(true);
        addrView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addrLp.leftMargin = dp(12);
        addrLp.rightMargin = dp(12);
        bar.addView(addrView, addrLp);

        Button refresh = flatButton("刷新", 72);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.reload();
                hideToolbar();
            }
        });
        bar.addView(refresh);

        // 播放源入口：自动发现之外，用户想主动换一台时走这里
        Button source = flatButton("播放源", 96);
        source.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (online.isEmpty() && !discovering) {
                    rescan();
                } else {
                    showSheet(null);
                }
            }
        });
        bar.addView(source);

        Button change = flatButton("改地址", 96);
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddressDialog();
            }
        });
        bar.addView(change);

        return bar;
    }

    private Button flatButton(String text, int widthDp) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // 原来按钮完全没有焦点反馈，遥控器按过去看不出停在哪，等于没法用
        final GradientDrawable off = roundRect(0x00000000, 0, dp(8));
        final GradientDrawable on = roundRect(0xFF2A3A4C, C_ACCENT, dp(8));
        b.setBackground(off);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? on : off);
            }
        });
        return b;
    }

    private void showToolbar(boolean moveFocus) {
        toolbarVisible = true;
        toolbar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideToolbarTask);
        handler.postDelayed(hideToolbarTask, TOOLBAR_HOLD_MS);
        if (moveFocus) {
            View first = toolbar.getChildAt(0);
            if (first != null) first.requestFocus();
        }
    }

    private void hideToolbar() {
        boolean wasVisible = toolbarVisible;
        toolbarVisible = false;
        toolbar.setVisibility(View.INVISIBLE);
        handler.removeCallbacks(hideToolbarTask);
        // 只有原本可见时才回收焦点，避免无谓地把焦点从网页元素上抢走
        if (wasVisible && web != null) {
            web.requestFocus();
        }
    }

    private void toggleToolbar() {
        if (toolbarVisible) {
            hideToolbar();
        } else {
            showToolbar(true);
        }
    }

    // ---------------- 改地址 ----------------

    private void showAddressDialog() {
        handler.removeCallbacks(hideToolbarTask);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        box.setPadding(p, dp(8), p, dp(8));

        final EditText input = new EditText(this);
        input.setText(web.getUrl() == null ? DEFAULT_URL : web.getUrl());
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = new TextView(this);
        tip.setText("最近使用：");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tip.setTextColor(0xFFAAAAAA);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(16);
        box.addView(tip, tipLp);

        List<String> recents = getRecents();
        for (int i = 0; i < recents.size(); i++) {
            final String u = recents.get(i);
            Button b = new Button(this);
            b.setText(u);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    input.setText(u);
                    input.setSelection(input.getText().length());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            box.addView(b, lp);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("输入地址")
                .setView(scroll)
                .setPositiveButton("打开", (d, which) -> loadUrl(input.getText().toString()))
                .setNegativeButton("取消", null)
                .create();
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dlg.show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private List<String> getRecents() {
        List<String> list = new ArrayList<>();
        String raw = prefs().getString(KEY_RECENT, "");
        if (!TextUtils.isEmpty(raw)) {
            for (String s : raw.split("\\n")) {
                if (!TextUtils.isEmpty(s.trim())) list.add(s.trim());
            }
        }
        return list;
    }

    private void pushRecent(String url) {
        List<String> list = getRecents();
        list.remove(url);
        list.add(0, url);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        prefs().edit().putString(KEY_RECENT, TextUtils.join("\n", list)).apply();
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (toolbarVisible && event.getAction() == KeyEvent.ACTION_DOWN) {
            // 工具条可见时，任何操作都重新计时
            handler.removeCallbacks(hideToolbarTask);
            handler.postDelayed(hideToolbarTask, TOOLBAR_HOLD_MS);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 菜单键：各家遥控器映射不一样，MENU / 设置 / 信息 都当菜单用
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_INFO) {
            toggleToolbar();
            return true;
        }
        // 投影仪遥控器大多没有菜单键，所以「长按返回」也打开工具条。
        // 返回键在这里自己吞掉，短按在 onKeyUp 里补发，否则长按会连带触发一次返回。
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0 && !backLongPressed) {
                event.startTracking();
                backShortPending = true;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backLongPressed = true;
            backShortPending = false;
            toggleToolbar();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            boolean wasShort = backShortPending && !backLongPressed;
            backShortPending = false;
            backLongPressed = false;
            if (wasShort) onBackPressed();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.getVisibility() == View.VISIBLE) {
            closeSheet();
            return;
        }
        if (toolbarVisible) {
            hideToolbar();
            return;
        }
        // 在子目录：让网页自己回上一级（网页维护目录栈，比 WebView 原生历史栈更可靠）
        if (!atRoot && web != null) {
            web.evaluateJavascript("window.__tvGoUp ? (window.__tvGoUp() ? 'up' : 'root') : 'none'",
                    null);
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000) {
            finish();
        } else {
            lastBackTime = now;
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            // 从播放器返回时把焦点还给网页，不然遥控器按键会落到窗口上
            web.requestFocus();
            // 播放页刚把进度写给服务器，让网页重拉一次列表，「继续观看」才有新数据
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (web != null) {
                        web.evaluateJavascript("window.__tvFocus&&window.__tvFocus();", null);
                    }
                }
            }, 700);
        }
        // 只在人看着屏幕的时候探掉线的源，省电
        handler.removeCallbacks(offlineWatch);
        handler.postDelayed(offlineWatch, 3000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(hideToolbarTask);
        handler.removeCallbacks(offlineWatch);
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
