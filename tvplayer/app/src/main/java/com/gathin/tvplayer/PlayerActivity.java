package com.gathin.tvplayer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 播放页：libVLC 软解播放 + 选集面板 + 自动连播。
 *
 * 为什么不用 ExoPlayer：这台投影仪（mt5862）的 MediaCodec 硬解是坏的，
 * 1080p H.264 直接抛 MediaCodecVideoDecoderException。VLC 自带 FFmpeg 软解扛得住。
 */
public class PlayerActivity extends Activity {

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_PATH = "path";        // 文件相对路径（拉集数用）
    private static final String EXTRA_BASE = "base";        // 服务器 http://ip:port
    private static final long SEEK_STEP = 10_000L;   // 左右键快进/快退 10 秒（电视遥控最顺手的步长）

    private String mediaUrl;
    private String title;
    private String filePath;    // 相对路径，如 "死神 27-40/xxx.mp4"
    private String baseUrl;     // 如 "http://192.168.0.19:8848"

    private LibVLC libVLC;
    private MediaPlayer vlc;
    private VLCVideoLayout videoLayout;

    private FrameLayout root;
    private View controls;
    private TextView titleView, infoView, timeView, statusView, seekHint, errorMsg, errorDetail;
    private TextView playIcon;   // 播放/暂停状态图标（▶ 暂停 / ❚❚ 播放中）
    private ProgressBar progress;
    // 底部操作条：快退 10 秒 / 播放暂停 / 快进 10 秒 / 重播
    private LinearLayout ctrlRow;
    private ImageView btnPlayBig, btnNextEp, btnReplay;
    private boolean ctrlRowFocused = false;
    private View errorOverlay, btnRetry, btnMx;

    // 选集面板
    private LinearLayout epPanel;
    private ScrollView epScroll;
    private LinearLayout epList;
    private TextView epTitle;
    private View epAutoNext;        // 自动连播倒计时浮层
    private TextView epAutoText;
    private boolean epPanelVisible = false;
    private int epSelected = 0;     // 面板中高亮的集
    private int epCurrent = 0;      // 正在播放的集
    private List<Episode> episodes = new ArrayList<>();

    // 自动连播
    private boolean autoNextPending = false;
    private int autoNextCountdown = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean errorShown;
    private long resumePosition;
    private Runnable repeatTask;
    private boolean resumeChecked = false;      // 是否已查过服务器进度
    private long resumeRemote = 0;              // 服务器记的续播位置（毫秒）
    private long lastProgressReport = 0;        // 上次上报进度的时间
    private int spuTrackCount = 0;              // 字幕轨数量
    private int spuTrackIndex = -1;             // 当前字幕轨（-1=关闭）
    private int audioTrackCount = 0;            // 音轨数量
    private int audioTrackIndex = -1;           // 当前音轨（-1=默认）

    // 倍速（跨集、跨启动记忆）
    private static final String PREFS_SPEED = "tvplayer";
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f};
    private int speedIndex = 0;                 // 默认 1.0x

    /** 集数数据 */
    static class Episode {
        String name;    // 文件名
        String path;    // 相对路径
        String title;   // 清洗后标题
        String sizeText;
        boolean current;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshProgress();
            reportProgress(false);
            ui.postDelayed(this, 500);
        }
    };

    private final Runnable hideControlsTask = new Runnable() {
        @Override
        public void run() {
            if (vlc != null && vlc.isPlaying() && !epPanelVisible) hideControls();
        }
    };

    private final Runnable seekHintTask = new Runnable() {
        @Override
        public void run() {
            if (seekHint != null) seekHint.setVisibility(View.GONE);
        }
    };

    // 自动连播倒计时
    private final Runnable autoNextTick = new Runnable() {
        @Override
        public void run() {
            if (!autoNextPending) return;
            autoNextCountdown--;
            if (autoNextCountdown <= 0) {
                hideAutoNext();
                playEpisode(epCurrent + 1);
            } else {
                if (epAutoText != null) {
                    epAutoText.setText("即将播放第 " + (epCurrent + 2) + " 集 · " + autoNextCountdown + " 秒\n按 OK 立即播放，按返回取消");
                }
                ui.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        mediaUrl = getIntent().getStringExtra(EXTRA_URL);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        filePath = getIntent().getStringExtra(EXTRA_PATH);
        baseUrl = getIntent().getStringExtra(EXTRA_BASE);
        if (TextUtils.isEmpty(mediaUrl)) {
            Toast.makeText(this, "没有播放地址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        root = buildUi();
        setContentView(root);
        loadSpeedPref();
        initPlayer(resumePosition);

        // 有路径就拉集数列表
        if (!TextUtils.isEmpty(filePath) && !TextUtils.isEmpty(baseUrl)) {
            loadEpisodes();
        }
    }

    public static void start(Activity from, String url, String titleText, String path, String base) {
        Intent i = new Intent(from, PlayerActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_TITLE, titleText);
        i.putExtra(EXTRA_PATH, path);
        i.putExtra(EXTRA_BASE, base);
        from.startActivity(i);
    }

    // ---------------- 集数加载 ----------------

    private void loadEpisodes() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String apiUrl = baseUrl + "/api/episodes?path=" +
                            URLEncoder.encode(filePath, "UTF-8");
                    HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray arr = json.optJSONArray("episodes");
                    if (arr == null) return;

                    final List<Episode> list = new ArrayList<>();
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject o = arr.getJSONObject(j);
                        Episode ep = new Episode();
                        ep.name = o.optString("name");
                        ep.path = o.optString("path");
                        ep.title = o.optString("title");
                        ep.sizeText = o.optString("size_text");
                        ep.current = o.optBoolean("current");
                        list.add(ep);
                    }

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            episodes.clear();
                            episodes.addAll(list);
                            for (int k = 0; k < episodes.size(); k++) {
                                if (episodes.get(k).current) {
                                    epCurrent = k;
                                    epSelected = k;
                                    break;
                                }
                            }
                            buildEpList();
                            refreshCtrlRow();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void buildEpList() {
        if (epList == null) return;
        epList.removeAllViews();
        for (int i = 0; i < episodes.size(); i++) {
            final int idx = i;
            Episode ep = episodes.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setFocusable(true);
            row.setClickable(true);

            // 集数编号
            TextView num = new TextView(this);
            num.setText(String.valueOf(i + 1));
            num.setTextColor(0xFF22D3EE);
            num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            num.setGravity(Gravity.CENTER);
            num.setWidth(dp(42));
            num.setHeight(dp(42));
            num.setBackgroundColor(0x3322D3EE);
            row.addView(num);

            // 标题
            TextView name = new TextView(this);
            name.setText(TextUtils.isEmpty(ep.title) ? ep.name : ep.title);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(14), 0, 0, 0);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, nameLp);

            // 当前播放标记
            if (ep.current) {
                TextView badge = new TextView(this);
                badge.setText(" ▶ 播放中");
                badge.setTextColor(0xFF22D3EE);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                row.addView(badge);
            }

            // 选中高亮
            if (i == epSelected) {
                row.setBackgroundColor(0x2A22D3EE);
            } else {
                row.setBackgroundColor(0x00000000);
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    epSelected = idx;
                    hideEpPanel();
                    playEpisode(idx);
                }
            });
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        epSelected = idx;
                        refreshEpHighlight();
                        v.setBackgroundColor(0x2A22D3EE);
                    } else {
                        if (idx != epSelected) v.setBackgroundColor(0x00000000);
                    }
                }
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(4);
            epList.addView(row, rowLp);
        }

        if (epTitle != null) {
            epTitle.setText("📺 选集 · 共 " + episodes.size() + " 集");
        }

        // 滚到当前集
        final int scrollTo = epCurrent;
        if (epScroll != null) {
            epScroll.post(new Runnable() {
                @Override
                public void run() {
                    View child = epList.getChildAt(scrollTo);
                    if (child != null) {
                        epScroll.scrollTo(0, child.getTop() - epScroll.getHeight() / 3);
                    }
                }
            });
        }
    }

    private void refreshEpHighlight() {
        for (int i = 0; i < epList.getChildCount(); i++) {
            View row = epList.getChildAt(i);
            row.setBackgroundColor(i == epSelected ? 0x2A22D3EE : 0x00000000);
        }
    }

    private void playEpisode(int idx) {
        if (idx < 0 || idx >= episodes.size()) return;
        Episode ep = episodes.get(idx);
        epCurrent = idx;
        epSelected = idx;

        // 构造新媒体 URL
        String newUrl = baseUrl + "/media/";
        String[] segs = ep.path.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) newUrl += "/";
            try { newUrl += URLEncoder.encode(segs[i], "UTF-8").replace("+", "%20"); }
            catch (Exception e) { newUrl += segs[i]; }
        }

        mediaUrl = newUrl;
        title = TextUtils.isEmpty(ep.title) ? ep.name : ep.title;
        if (titleView != null) titleView.setText(title);
        if (infoView != null) infoView.setText("第 " + (idx + 1) + " / " + episodes.size() + " 集");
        refreshCtrlRow();

        resumePosition = 0;
        hideAutoNext();
        errorShown = false;
        if (errorOverlay != null) errorOverlay.setVisibility(View.GONE);
        resumeChecked = false;      // 新一集，重新查服务器进度
        resumeRemote = 0;
        spuTrackCount = 0;
        spuTrackIndex = -1;
        audioTrackCount = 0;
        audioTrackIndex = -1;
        initPlayer(0);
        checkResume();
        buildEpList();  // 刷新"播放中"标记
    }

    // ---------------- 自动连播 ----------------

    private void onVideoEnd() {
        reportProgress(true);   // 看完就清掉进度，下次从头放
        if (episodes.size() > 1 && epCurrent < episodes.size() - 1) {
            // 有下一集 → 倒计时连播
            autoNextPending = true;
            autoNextCountdown = 5;
            if (epAutoNext != null) {
                epAutoNext.setVisibility(View.VISIBLE);
                if (epAutoText != null) {
                    epAutoText.setText("即将播放第 " + (epCurrent + 2) + " 集 · 5 秒\n按 OK 立即播放，按返回取消");
                }
            }
            ui.removeCallbacks(autoNextTick);
            ui.postDelayed(autoNextTick, 1000);
        } else if (episodes.size() > 1) {
            // 最后一集
            showStatus("🎉 全部看完了");
        }
    }

    private void hideAutoNext() {
        autoNextPending = false;
        ui.removeCallbacks(autoNextTick);
        if (epAutoNext != null) epAutoNext.setVisibility(View.GONE);
    }

    // ---------------- 播放器 ----------------

    private void initPlayer(long startPosition) {
        releasePlayer();
        errorShown = false;
        if (errorOverlay != null) errorOverlay.setVisibility(View.GONE);

        try {
            ArrayList<String> options = new ArrayList<>();
            // 启用 MediaCodec 硬解（实测 MX Player 硬解能流畅播 4K HEVC，说明
            // 这台投影仪硬解是好的；之前的"硬解坏了"是误判）。
            options.add("--avcodec-hw=mediacodec");
            // 软解回退：硬解失败时仍可用 FFmpeg 软解兜底
            options.add("--avcodec-threads=2");
            options.add("--network-caching=2500");
            options.add("--http-reconnect");
            options.add("--no-drop-late-frames");

            libVLC = new LibVLC(this, options);
            vlc = new MediaPlayer(libVLC);

            Media media = new Media(libVLC, Uri.parse(mediaUrl));
            media.setHWDecoderEnabled(true, true);
            media.addOption(":http-user-agent=MediaShare/1.0");
            media.addOption(":network-caching=2500");

            vlc.setMedia(media);
            vlc.attachViews(videoLayout, null, false, false);
            vlc.setEventListener(new MediaPlayer.EventListener() {
                @Override
                public void onEvent(MediaPlayer.Event event) {
                    onVlcEvent(event);
                }
            });
            vlc.play();
            if (SPEEDS[speedIndex] != 1.0f) {
                try { vlc.setRate(SPEEDS[speedIndex]); } catch (Throwable ignored) {}
            }
            if (startPosition > 0) {
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (vlc != null) vlc.setTime(startPosition);
                    }
                }, 1200);
            }
            // 内嵌字幕：VLC 会自动读 MKV/MP4 里的字幕轨，起播后统计一下
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshSpuTracks();
                }
            }, 1500);
            // 音轨：起播后统计一下，供 A 键循环切换
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshAudioTracks();
                }
            }, 1800);
        } catch (Throwable t) {
            android.util.Log.e("tvplayer", "vlc init failed", t);
            showError();
            return;
        }
        ui.post(tick);
        showControls();
        paintPlayIcon();
    }

    private void onVlcEvent(MediaPlayer.Event event) {
        int type = event.type;
        if (type == MediaPlayer.Event.Playing) {
            if (statusView != null) statusView.setVisibility(View.GONE);
            showControls();
            paintPlayIcon();
        } else if (type == MediaPlayer.Event.Paused) {
            paintPlayIcon();
        } else if (type == MediaPlayer.Event.Buffering) {
            showStatus("缓冲中…");
        } else if (type == MediaPlayer.Event.EndReached) {
            // 自动连播
            onVideoEnd();
        } else if (type == MediaPlayer.Event.EncounteredError) {
            showError();
        }
    }

    private void showStatus(String text) {
        if (statusView == null) return;
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideStatusTask);
        ui.postDelayed(hideStatusTask, 2500);
    }

    private final Runnable hideStatusTask = new Runnable() {
        @Override
        public void run() {
            if (statusView != null) statusView.setVisibility(View.GONE);
        }
    };

    private void releasePlayer() {
        if (vlc != null) {
            try {
                vlc.setEventListener(null);
                vlc.stop();
                vlc.detachViews();
            } catch (Throwable ignored) {
            }
            try {
                vlc.release();
            } catch (Throwable ignored) {
            }
            vlc = null;
        }
        if (libVLC != null) {
            try {
                libVLC.release();
            } catch (Throwable ignored) {
            }
            libVLC = null;
        }
    }

    private void refreshProgress() {
        if (vlc == null) return;
        long len = 0;
        long pos = 0;
        try {
            len = vlc.getLength();
            pos = vlc.getTime();
        } catch (Throwable ignored) {
        }
        if (progress != null) {
            int p = (len > 0) ? (int) Math.max(0, Math.min(1000, pos * 1000L / len)) : 0;
            progress.setProgress(p);
        }
        if (timeView != null) timeView.setText(fmtTime(pos) + " / " + fmtTime(len));
    }

    private static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    // ---------------- 界面 ----------------

    private FrameLayout buildUi() {
        FrameLayout r = new FrameLayout(this);
        r.setBackgroundColor(Color.BLACK);
        r.setFocusableInTouchMode(true);
        r.setFocusable(true);

        videoLayout = new VLCVideoLayout(this);
        videoLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        r.addView(videoLayout);

        controls = buildControls();
        r.addView(controls);

        // 选集面板
        epPanel = buildEpPanel();
        r.addView(epPanel);

        // 自动连播浮层
        epAutoNext = buildAutoNextOverlay();
        r.addView(epAutoNext);

        errorOverlay = buildErrorOverlay();
        r.addView(errorOverlay);

        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleControls();
            }
        });
        return r;
    }

    private LinearLayout buildEpPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF00C1520);
        panel.setPadding(dp(24), dp(28), dp(24), dp(20));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(480),
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END;
        panel.setLayoutParams(lp);
        panel.setVisibility(View.GONE);

        epTitle = new TextView(this);
        epTitle.setText("📺 选集");
        epTitle.setTextColor(Color.WHITE);
        epTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        epTitle.setTypeface(Typeface.DEFAULT_BOLD);
        epTitle.setPadding(0, 0, 0, dp(16));
        panel.addView(epTitle);

        epScroll = new ScrollView(this);
        epScroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panel.addView(epScroll, scrollLp);

        epList = new LinearLayout(this);
        epList.setOrientation(LinearLayout.VERTICAL);
        epScroll.addView(epList);

        // 提示
        TextView hint = new TextView(this);
        hint.setText("▲▼ 选集 · OK 播放 · 返回关闭");
        hint.setTextColor(0x88FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(14), 0, 0);
        panel.addView(hint);

        return panel;
    }

    private View buildAutoNextOverlay() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0xB3000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        wrap.setLayoutParams(lp);
        wrap.setVisibility(View.GONE);

        epAutoText = new TextView(this);
        epAutoText.setTextColor(Color.WHITE);
        epAutoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        epAutoText.setGravity(Gravity.CENTER);
        epAutoText.setLineSpacing(dp(8), 1f);
        FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.gravity = Gravity.CENTER;
        wrap.addView(epAutoText, tLp);

        return wrap;
    }

    private View buildControls() {
        FrameLayout layer = new FrameLayout(this);
        layer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.setClickable(false);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xA6000000);
        top.setPadding(dp(28), dp(16), dp(28), dp(16));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        top.setLayoutParams(topLp);

        titleView = new TextView(this);
        titleView.setText(title == null ? "" : title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(titleView, titleLp);

        infoView = new TextView(this);
        infoView.setTextColor(0xCCFFFFFF);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        infoView.setSingleLine(true);
        infoView.setPadding(dp(16), 0, 0, 0);
        top.addView(infoView);

        layer.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xA6000000);
        bottom.setPadding(dp(28), dp(14), dp(28), dp(20));
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        botLp.gravity = Gravity.BOTTOM;
        bottom.setLayoutParams(botLp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setProgressTintList(ColorStateList.valueOf(0xFF4FC3F7));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        bottom.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        // 时间 + 播放/暂停图标放在同一行
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        bottom.addView(timeRow, rowLp);

        timeView = new TextView(this);
        timeView.setText("00:00 / 00:00");
        timeView.setTextColor(Color.WHITE);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        timeRow.addView(timeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 播放/暂停状态图标（青色圆底，起视觉提示作用；遥控器 OK 仍可切换）
        playIcon = new TextView(this);
        playIcon.setText("❚❚");
        playIcon.setTextColor(0xFF04121A);
        playIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        playIcon.setTypeface(Typeface.DEFAULT_BOLD);
        playIcon.setGravity(Gravity.CENTER);
        playIcon.setBackgroundColor(0xFF22D3EE);
        playIcon.setWidth(dp(52));
        playIcon.setHeight(dp(52));
        LinearLayout.LayoutParams piLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        piLp.leftMargin = dp(20);
        // playIcon 只作内部状态文本，不再往界面上放：下面操作条里已有「暂停/播放」按钮
        playIcon.setVisibility(View.GONE);

        // ---- 操作条：遥控器按「下」进入，左右选按钮，OK 执行 ----
        ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = dp(16);
        bottom.addView(ctrlRow, crLp);

        // 只留三个图标按钮：暂停/播放（第 1）· 下一集（第 2）· 重播（第 3）
        // 快进/快退靠遥控器左右键，不在条上占位
        btnPlayBig = mkIconBtn(R.drawable.ic_pause, "暂停/播放");
        btnPlayBig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
                showControls();
            }
        });
        ctrlRow.addView(btnPlayBig, cIconLp(0));

        btnNextEp = mkIconBtn(R.drawable.ic_skip_next, "下一集");
        btnNextEp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (episodes.size() > 1 && epCurrent < episodes.size() - 1) {
                    playEpisode(epCurrent + 1);
                } else if (episodes.size() <= 1) {
                    showStatus("当前只有一个视频，没有下一集");
                } else {
                    showStatus("已经是最后一集了");
                }
            }
        });
        ctrlRow.addView(btnNextEp, cIconLp(20));

        btnReplay = mkIconBtn(R.drawable.ic_replay, "重播");
        btnReplay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                replayFromStart();
            }
        });
        ctrlRow.addView(btnReplay, cIconLp(20));
        refreshCtrlRow();   // 集数还没拉到之前，「下一集」先按不可用显示

        layer.addView(bottom);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        statusView.setGravity(Gravity.CENTER);
        statusView.setVisibility(View.GONE);
        FrameLayout.LayoutParams stLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.gravity = Gravity.CENTER;
        layer.addView(statusView, stLp);

        seekHint = new TextView(this);
        seekHint.setTextColor(Color.WHITE);
        seekHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        seekHint.setBackgroundColor(0x99000000);
        seekHint.setPadding(dp(20), dp(10), dp(20), dp(10));
        seekHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams shLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shLp.gravity = Gravity.CENTER;
        layer.addView(seekHint, shLp);

        return layer;
    }

    private View buildErrorOverlay() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0xE6000000);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.setVisibility(View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(48), dp(40), dp(48), dp(40));
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.gravity = Gravity.CENTER;
        wrap.addView(panel, pLp);

        errorMsg = new TextView(this);
        errorMsg.setTextColor(Color.WHITE);
        errorMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        errorMsg.setGravity(Gravity.CENTER);
        panel.addView(errorMsg);

        errorDetail = new TextView(this);
        errorDetail.setTextColor(0xFFB9C7D8);
        errorDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        errorDetail.setGravity(Gravity.CENTER);
        errorDetail.setPadding(0, dp(12), 0, 0);
        panel.addView(errorDetail);

        btnRetry = mkButton("重试");
        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                initPlayer(0);
            }
        });
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(300), dp(64));
        rLp.topMargin = dp(24);
        panel.addView(btnRetry, rLp);

        btnMx = mkButton("用 MX Player 打开");
        btnMx.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWithMx();
            }
        });
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(dp(300), dp(64));
        mLp.topMargin = dp(18);
        panel.addView(btnMx, mLp);

        return wrap;
    }

    /** 操作条图标按钮：圆形底 + 白色图标 + 聚焦亮蓝描边。
     *  必须 setFocusableInTouchMode，否则从网页点进播放页时窗口处于 touch mode，
     *  requestFocus 会失败，遥控器进不来这排按钮。 */
    private ImageView mkIconBtn(int iconRes, String label) {
        ImageView b = new ImageView(this);
        b.setImageResource(iconRes);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(20), dp(20), dp(20), dp(20));
        b.setBackground(makeIconBg(false));
        b.setContentDescription(label);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(makeIconBg(hasFocus));
                if (hasFocus) {
                    ctrlRowFocused = true;
                    ui.removeCallbacks(hideControlsTask);   // 正在选按钮就别自己消失
                }
            }
        });
        return b;
    }

    private GradientDrawable makeIconBg(boolean focus) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (focus) {
            g.setColor(0xFF0A7EA4);
            g.setStroke(dp(3), 0xFF7FE3FF);
        } else {
            g.setColor(0x40FFFFFF);
            g.setStroke(dp(1), 0x33FFFFFF);
        }
        return g;
    }

    /** 图标按钮固定 64dp 见方，跟 20dp 间距一起排成一行 */
    private LinearLayout.LayoutParams cIconLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
        lp.leftMargin = dp(leftMargin);
        return lp;
    }

    private View mkButton(String text) {        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(0xFF1E5A73);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundColor(hasFocus ? 0xFF0288D1 : 0xFF1E5A73);
            }
        });
        return b;
    }

    private void showControls() {
        if (controls != null) controls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControlsTask);
        if (ctrlRowFocused) return;          // 正在选按钮，别自动收起来
        ui.postDelayed(hideControlsTask, 5000);
    }

    private void hideControls() {
        leaveCtrlRow();
        if (controls != null) controls.setVisibility(View.GONE);
        ui.removeCallbacks(hideControlsTask);
    }

    /** 把焦点放进操作条（遥控器按「下」） */
    private void enterCtrlRow() {
        showControls();
        ctrlRowFocused = true;
        ui.removeCallbacks(hideControlsTask);
        if (btnPlayBig != null) btnPlayBig.requestFocus();   // 焦点永远先落「暂停」
    }

    private void leaveCtrlRow() {
        ctrlRowFocused = false;
        View cur = getCurrentFocus();
        if (cur != null) cur.clearFocus();
    }

    /** 操作条内左右移动焦点：暂停 → 下一集 → 重播 */
    private void moveCtrlFocus(int dir) {
        ImageView[] all = {btnPlayBig, btnNextEp, btnReplay};
        ArrayList<ImageView> live = new ArrayList<>();
        for (int i = 0; i < all.length; i++) {
            if (all[i] != null && all[i].getVisibility() == View.VISIBLE) live.add(all[i]);
        }
        if (live.isEmpty()) return;
        int cur = -1;
        View f = getCurrentFocus();
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i) == f) { cur = i; break; }
        }
        if (cur < 0) { live.get(0).requestFocus(); return; }
        int nx = cur + dir;
        if (nx < 0) nx = 0;
        if (nx > live.size() - 1) nx = live.size() - 1;
        live.get(nx).requestFocus();
    }

    /** 「下一集」永远是第三个位置里的第二个按钮：不可用时变半透明（不隐藏，
     *  免得按钮位置乱跳），点了给一句提示。 */
    private void refreshCtrlRow() {
        if (btnNextEp == null) return;
        boolean canNext = episodes.size() > 1 && epCurrent < episodes.size() - 1;
        btnNextEp.setAlpha(canNext ? 1f : 0.32f);
    }

    /** 从头重播：进度归零并开播（播完后按它就是这个用途） */
    private void replayFromStart() {
        if (vlc == null) return;
        try {
            long len = vlc.getLength();
            if (len > 0) vlc.setTime(0);
            if (!vlc.isPlaying()) vlc.play();
        } catch (Throwable ignored) {
        }
        resumePosition = 0;
        showStatus("↺ 从头重播");
        showControls();
    }

    private void toggleControls() {
        if (controls != null && controls.getVisibility() == View.VISIBLE) hideControls();
        else showControls();
    }

    // ---------------- 选集面板 ----------------

    private void showEpPanel() {
        if (episodes.isEmpty()) {
            Toast.makeText(this, "没有集数信息", Toast.LENGTH_SHORT).show();
            return;
        }
        epPanelVisible = true;
        epSelected = epCurrent;
        buildEpList();
        if (epPanel != null) epPanel.setVisibility(View.VISIBLE);
        hideControls();
        // 聚焦到当前集
        if (epList != null && epList.getChildCount() > epCurrent) {
            epList.getChildAt(epCurrent).requestFocus();
        }
    }

    private void hideEpPanel() {
        epPanelVisible = false;
        if (epPanel != null) epPanel.setVisibility(View.GONE);
    }

    private void toggleEpPanel() {
        if (epPanelVisible) hideEpPanel();
        else showEpPanel();
    }

    // ---------------- 错误兜底 ----------------

    private void showError() {
        android.util.Log.e("tvplayer", "play error: " + mediaUrl);
        errorShown = true;
        ui.removeCallbacks(hideControlsTask);
        ui.removeCallbacks(seekHintTask);
        hideAutoNext();
        if (controls != null) controls.setVisibility(View.GONE);
        if (seekHint != null) seekHint.setVisibility(View.GONE);
        if (statusView != null) statusView.setVisibility(View.GONE);
        if (epPanel != null) epPanel.setVisibility(View.GONE);
        if (errorOverlay != null) errorOverlay.setVisibility(View.VISIBLE);
        if (errorMsg != null) errorMsg.setText("这个格式播不了");
        if (errorDetail != null) errorDetail.setText("换 MX Player 试试");
        if (btnRetry != null) btnRetry.requestFocus();
    }

    private void openWithMx() {
        Uri uri = Uri.parse(mediaUrl);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "video/*");
        i.putExtra("title", title == null ? "" : title);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        String[] packages = {"com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad", null};
        for (String pkg : packages) {
            try {
                Intent t = new Intent(i);
                if (pkg != null) t.setPackage(pkg);
                startActivity(t);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // 换下一个
            }
        }
        Toast.makeText(this, "系统里没有能播这个格式的播放器", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onStart() {
        super.onStart();
        if (vlc == null && !errorShown) initPlayer(resumePosition);
        ui.post(tick);
        checkResume();
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(tick);
        hideAutoNext();
        if (vlc != null) {
            try {
                resumePosition = Math.max(0, vlc.getTime());
            } catch (Throwable ignored) {
            }
            releasePlayer();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (handleKey(code, event)) return true;
        } else if (action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_MEDIA_REWIND
                    || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                stopRepeat();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleKey(int keyCode, KeyEvent event) {
        // 错误界面
        if (errorShown) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                View focused = getCurrentFocus();
                if (focused == btnMx) openWithMx();
                else initPlayer(0);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                View focused = getCurrentFocus();
                if (focused == btnRetry && btnMx != null) btnMx.requestFocus();
                else if (focused == btnMx && btnRetry != null) btnRetry.requestFocus();
                return true;
            }
            return false;
        }

        // 自动连播倒计时中
        if (autoNextPending) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                hideAutoNext();
                playEpisode(epCurrent + 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideAutoNext();
                showStatus("已取消连播");
                return true;
            }
            return true;  // 吞掉其他按键
        }

        // 选集面板打开时
        if (epPanelVisible) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (epSelected > 0) {
                        epSelected--;
                        refreshEpHighlight();
                        if (epList != null && epList.getChildCount() > epSelected) {
                            epList.getChildAt(epSelected).requestFocus();
                            epList.getChildAt(epSelected).setBackgroundResource(0);
                            epList.getChildAt(epSelected).setBackgroundColor(0x2A22D3EE);
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (epSelected < episodes.size() - 1) {
                        epSelected++;
                        refreshEpHighlight();
                        if (epList != null && epList.getChildCount() > epSelected) {
                            epList.getChildAt(epSelected).requestFocus();
                            epList.getChildAt(epSelected).setBackgroundColor(0x2A22D3EE);
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    hideEpPanel();
                    playEpisode(epSelected);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    hideEpPanel();
                    showControls();
                    return true;
                default:
                    return true;  // 面板打开时吞掉其他按键
            }
        }

        // 操作条（暂停 / 下一集 / 重播）拿到焦点时：左右换按钮，上/返回退出
        if (ctrlRowFocused && controls != null && controls.getVisibility() == View.VISIBLE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveCtrlFocus(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveCtrlFocus(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    leaveCtrlRow();
                    showControls();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    View fbtn = getCurrentFocus();
                    if (fbtn != null) fbtn.performClick();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    leaveCtrlRow();
                    hideControls();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                default:
                    break;   // 其他键（音量/菜单）继续走原逻辑
            }
        }

        // 正常播放中
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) togglePlay();
                showControls();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (event.getRepeatCount() == 0) {
                    seekBy(-SEEK_STEP);
                    startRepeat(-SEEK_STEP);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (event.getRepeatCount() == 0) {
                    seekBy(SEEK_STEP);
                    startRepeat(SEEK_STEP);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                // 上键：有多集就切上一集，单集就只是亮出控制条
                if (episodes.size() > 1 && epCurrent > 0) {
                    playEpisode(epCurrent - 1);
                } else {
                    showControls();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 下键永远进底部操作条：暂停 / 下一集 / 重播
                // （切集交给操作条里的「下一集」和菜单键选集面板，别再抢占下键）
                enterCtrlRow();
                return true;

            case KeyEvent.KEYCODE_MENU:
                toggleEpPanel();
                return true;

            case KeyEvent.KEYCODE_C:
                cycleSubtitle();
                return true;

            case KeyEvent.KEYCODE_A:
                cycleAudio();
                return true;

            case KeyEvent.KEYCODE_S:
                cycleSpeed();
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (epPanelVisible) {
                    hideEpPanel();
                    return true;
                }
                onBackPressed();
                return true;

            default:
                return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleKey(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                stopRepeat();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void togglePlay() {
        if (vlc == null) return;
        try {
            if (vlc.isPlaying()) vlc.pause();
            else vlc.play();
        } catch (Throwable ignored) {
        }
        paintPlayIcon();
    }

    /** 播放/暂停图标：播放中显示「暂停」双竖条，暂停时显示「播放」三角 */
    private void paintPlayIcon() {
        if (playIcon == null) return;
        boolean playing = false;
        try { playing = vlc != null && vlc.isPlaying(); } catch (Throwable ignored) {}
        playIcon.setText(playing ? "❚❚" : "▶");
        if (btnPlayBig != null) {
            btnPlayBig.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        }
    }

    private void seekBy(long deltaMs) {
        if (vlc == null) return;
        try {
            long len = vlc.getLength();
            long pos = vlc.getTime() + deltaMs;
            if (pos < 0) pos = 0;
            if (len > 0 && pos > len) pos = len;
            vlc.setTime(pos);
        } catch (Throwable ignored) {
        }
        showSeekHint(deltaMs);
        showControls();
    }

    private void showSeekHint(long deltaMs) {
        long abs = Math.abs(deltaMs);
        seekHint.setText((deltaMs > 0 ? "快进 " : "快退 ") + (abs / 1000) + " 秒");
        seekHint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(seekHintTask);
        ui.postDelayed(seekHintTask, 900);
    }

    // ---------------- 字幕 ----------------

    private void refreshSpuTracks() {
        if (vlc == null) return;
        try {
            MediaPlayer.TrackDescription[] spus = vlc.getSpuTracks();
            spuTrackCount = (spus == null) ? 0 : spus.length;
            if (spuTrackCount <= 0) {
                spuTrackIndex = -1;
                return;
            }
            // 默认显示第一条字幕（服务端已把中文轨排前面，但 VLC 拿到的是容器原始顺序，
            // 这里只负责"有字幕"，切换交给用户）
            if (spuTrackIndex < 0) {
                int cur = vlc.getSpuTrack();
                spuTrackIndex = (cur >= 0) ? cur : spus[0].id;
            }
            showStatus("字幕：" + (spuTrackIndex >= 0 ? "开" : "关") + "（按 C 切换）");
        } catch (Throwable ignored) {
        }
    }

    private void cycleSubtitle() {
        if (vlc == null || spuTrackCount <= 0) {
            showStatus("没有内嵌字幕");
            return;
        }
        try {
            MediaPlayer.TrackDescription[] spus = vlc.getSpuTracks();
            if (spus == null || spus.length == 0) {
                showStatus("没有内嵌字幕");
                return;
            }
            // 找到当前轨在列表里的位置
            int pos = -1;
            for (int i = 0; i < spus.length; i++) {
                if (spus[i].id == spuTrackIndex) { pos = i; break; }
            }
            if (pos < 0) {
                // 当前是关闭（-1），从头开始
                spuTrackIndex = spus[0].id;
                vlc.setSpuTrack(spuTrackIndex);
                showStatus("字幕：开");
            } else if (pos >= spus.length - 1) {
                // 已是最后一条 → 关闭
                spuTrackIndex = -1;
                vlc.setSpuTrack(-1);
                showStatus("字幕：关");
            } else {
                spuTrackIndex = spus[pos + 1].id;
                vlc.setSpuTrack(spuTrackIndex);
                showStatus("字幕：" + (pos + 2) + "/" + spus.length);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 音轨 ----------------

    private void refreshAudioTracks() {
        if (vlc == null) return;
        try {
            MediaPlayer.TrackDescription[] audios = vlc.getAudioTracks();
            audioTrackCount = (audios == null) ? 0 : audios.length;
            if (audioTrackCount <= 0) {
                audioTrackIndex = -1;
                return;
            }
            if (audioTrackIndex < 0) {
                int cur = vlc.getAudioTrack();
                audioTrackIndex = (cur >= 0) ? cur : audios[0].id;
            }
        } catch (Throwable ignored) {
        }
    }

    private void cycleAudio() {
        if (vlc == null || audioTrackCount <= 0) {
            showStatus("只有一条音轨");
            return;
        }
        try {
            MediaPlayer.TrackDescription[] audios = vlc.getAudioTracks();
            if (audios == null || audios.length <= 1) {
                showStatus("只有一条音轨");
                return;
            }
            int pos = -1;
            for (int i = 0; i < audios.length; i++) {
                if (audios[i].id == audioTrackIndex) { pos = i; break; }
            }
            int nextPos = (pos < 0) ? 0 : (pos + 1) % audios.length;
            audioTrackIndex = audios[nextPos].id;
            vlc.setAudioTrack(audioTrackIndex);
            String name = audios[nextPos].name;
            showStatus("音轨：" + (TextUtils.isEmpty(name) ? (nextPos + 1) + "/" + audios.length : name));
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 倍速（记忆在本地，跨集/跨启动保留） ----------------

    private void loadSpeedPref() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_SPEED, Context.MODE_PRIVATE);
            int idx = sp.getInt("speed_index", 0);
            if (idx >= 0 && idx < SPEEDS.length) {
                speedIndex = idx;
                applySpeed();
            }
        } catch (Throwable ignored) {
        }
    }

    private void applySpeed() {
        if (vlc != null) {
            try {
                vlc.setRate(SPEEDS[speedIndex]);
            } catch (Throwable ignored) {
            }
        }
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        applySpeed();
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_SPEED, Context.MODE_PRIVATE);
            sp.edit().putInt("speed_index", speedIndex).apply();
        } catch (Throwable ignored) {
        }
        showStatus("倍速 " + formatSpeed(SPEEDS[speedIndex]));
    }

    private static String formatSpeed(float f) {
        if (f == 1.0f) return "1.0×";
        if (f == 1.25f) return "1.25×";
        if (f == 1.5f) return "1.5×";
        if (f == 2.0f) return "2.0×";
        return f + "×";
    }

    // ---------------- 播放记忆（和手机/网页共用服务器进度） ----------------

    private void checkResume() {
        if (resumeChecked || TextUtils.isEmpty(filePath) || TextUtils.isEmpty(baseUrl)) return;
        resumeChecked = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = baseUrl + "/api/progress?path=" + URLEncoder.encode(filePath, "UTF-8");
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject o = new JSONObject(sb.toString());
                    double pos = o.optDouble("pos", 0);
                    if (pos >= 10) {
                        resumeRemote = (long) (pos * 1000);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (vlc != null && resumeRemote > 0) {
                                    vlc.setTime(resumeRemote);
                                    showStatus("继续上次 " + fmtTime(resumeRemote));
                                }
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void reportProgress(final boolean ended) {
        if (vlc == null || TextUtils.isEmpty(filePath) || TextUtils.isEmpty(baseUrl)) return;
        final long posMs;
        try {
            posMs = ended ? vlc.getLength() : vlc.getTime();
        } catch (Throwable t) {
            return;
        }
        if (posMs < 5000) return;
        if (!ended && System.currentTimeMillis() - lastProgressReport < 8000) return;
        lastProgressReport = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = baseUrl + "/api/progress";
                    JSONObject payload = new JSONObject();
                    payload.put("path", filePath);
                    payload.put("pos", posMs / 1000.0);
                    long len = 0;
                    try { len = vlc.getLength(); } catch (Throwable ignored) {}
                    payload.put("dur", len / 1000.0);
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.getOutputStream().write(payload.toString().getBytes("UTF-8"));
                    conn.getOutputStream().flush();
                    conn.getResponseCode();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void startRepeat(final long step) {
        stopRepeat();
        repeatTask = new Runnable() {
            @Override
            public void run() {
                seekBy(step);
                ui.postDelayed(this, 400);
            }
        };
        ui.postDelayed(repeatTask, 500);
    }

    private void stopRepeat() {
        if (repeatTask != null) {
            ui.removeCallbacks(repeatTask);
            repeatTask = null;
        }
    }

    @Override
    public void onBackPressed() {
        stopRepeat();
        hideAutoNext();
        if (epPanelVisible) {
            hideEpPanel();
            return;
        }
        finish();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
