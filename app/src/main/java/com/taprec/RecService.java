package com.taprec;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RecService extends AccessibilityService {

    public static RecService instance;

    private static class Gesture {
        long wait;
        long dur;
        final List<float[]> pts = new ArrayList<>();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View panel;
    private WindowManager.LayoutParams panelLp;
    private TextView status;
    private TextView repeatBtn;
    private View captureView;
    private WindowManager.LayoutParams captureLp;

    private boolean recording = false;
    private boolean playing = false;
    private int playToken = 0;
    private long lastEnd = 0;
    private List<Gesture> current = new ArrayList<>();
    private Gesture drag;
    private long dragStart;
    private final int[] repeatOptions = {1, 3, 5, 10, 30};
    private int repeatIdx = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createCapture();
        showPanel();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        instance = null;
        super.onDestroy();
    }

    private void cleanup() {
        playing = false;
        playToken++;
        recording = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (captureView != null) wm.removeViewImmediate(captureView);
        } catch (Exception ignored) {
        }
        captureView = null;
        try {
            if (panel != null) wm.removeViewImmediate(panel);
        } catch (Exception ignored) {
        }
        panel = null;
    }

    // ---------- 悬浮面板 ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView makeButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0xFF3949AB);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    public void showPanel() {
        if (panel != null || wm == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xCC202020);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        box.addView(status);

        box.addView(makeButton("● 录制", v -> startRecording()));
        box.addView(makeButton("■ 停止", v -> stopAll()));
        box.addView(makeButton("▶ 回放", v -> startPlaying()));
        repeatBtn = makeButton("次数 ×1", v -> {
            repeatIdx = (repeatIdx + 1) % repeatOptions.length;
            repeatBtn.setText("次数 ×" + repeatOptions[repeatIdx]);
        });
        box.addView(repeatBtn);
        box.addView(makeButton("✕ 隐藏", v -> hidePanel()));

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        panel = box;
        wm.addView(panel, panelLp);
        setStatus("已有 " + load().size() + " 个动作");
    }

    private void hidePanel() {
        stopAll();
        try {
            if (panel != null) wm.removeViewImmediate(panel);
        } catch (Exception ignored) {
        }
        panel = null;
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    // ---------- 全屏捕获层（录制时才可触摸） ----------

    private void createCapture() {
        captureView = new View(this);
        captureLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        captureView.setOnTouchListener(this::onCaptureTouch);
        wm.addView(captureView, captureLp);
    }

    private void setCapture(boolean touchable) {
        if (captureView == null) return;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        captureLp.flags = flags;
        captureView.setBackgroundColor(recording ? 0x22FF0000 : 0x00000000);
        try {
            wm.updateViewLayout(captureView, captureLp);
        } catch (Exception ignored) {
        }
    }

    private boolean onCaptureTouch(View v, MotionEvent e) {
        float x = e.getRawX();
        float y = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drag = new Gesture();
                dragStart = e.getEventTime();
                drag.wait = lastEnd == 0 ? 0 : Math.max(0, dragStart - lastEnd);
                drag.pts.add(new float[]{x, y});
                break;
            case MotionEvent.ACTION_MOVE:
                if (drag != null) {
                    float[] last = drag.pts.get(drag.pts.size() - 1);
                    if (Math.hypot(x - last[0], y - last[1]) >= 12) {
                        drag.pts.add(new float[]{x, y});
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (drag != null) {
                    Gesture g = drag;
                    drag = null;
                    g.pts.add(new float[]{x, y});
                    g.dur = Math.max(50, e.getEventTime() - dragStart);
                    lastEnd = e.getEventTime();
                    thin(g);
                    current.add(g);
                    setStatus("录制中：" + current.size() + " 个动作");
                    passThrough(g);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                drag = null;
                break;
            default:
                break;
        }
        return true;
    }

    private void thin(Gesture g) {
        int n = g.pts.size();
        if (n <= 40) return;
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            out.add(g.pts.get(i * (n - 1) / 39));
        }
        g.pts.clear();
        g.pts.addAll(out);
    }

    // 手指抬起后，把这次操作原样"转发"给下面的应用
    private void passThrough(Gesture g) {
        setCapture(false);
        dispatch(g, () -> {
            if (recording) setCapture(true);
        });
    }

    // ---------- 录制 / 回放 ----------

    private void startRecording() {
        if (recording || playing) return;
        recording = true;
        current = new ArrayList<>();
        lastEnd = 0;
        drag = null;
        setStatus("录制中…");
        setCapture(true);
    }

    private void stopRecording() {
        recording = false;
        setCapture(false);
        save(current);
        setStatus("已保存 " + current.size() + " 个动作");
    }

    private void stopAll() {
        if (recording) {
            stopRecording();
        } else if (playing) {
            playing = false;
            playToken++;
            setStatus("已停止");
        }
    }

    private void startPlaying() {
        if (recording || playing) return;
        final List<Gesture> list = load();
        if (list.isEmpty()) {
            setStatus("还没有录制");
            return;
        }
        playing = true;
        final int token = ++playToken;
        final int total = repeatOptions[repeatIdx];
        setStatus("3 秒后开始回放");
        handler.postDelayed(() -> {
            if (!playing || token != playToken) return;
            setStatus("第 1/" + total + " 次");
            playRound(list, 0, 1, total, token);
        }, 3000);
    }

    private void playRound(List<Gesture> list, int idx, int round, int total, int token) {
        if (!playing || token != playToken) return;
        if (idx >= list.size()) {
            if (round >= total) {
                playing = false;
                setStatus("回放完成");
                return;
            }
            setStatus("第 " + (round + 1) + "/" + total + " 次");
            handler.postDelayed(() -> playRound(list, 0, round + 1, total, token), 1500);
            return;
        }
        Gesture g = list.get(idx);
        long wait = idx == 0 ? 0 : g.wait;
        handler.postDelayed(() -> {
            if (!playing || token != playToken) return;
            dispatch(g, () -> playRound(list, idx + 1, round, total, token));
        }, wait);
    }

    private void dispatch(Gesture g, Runnable done) {
        GestureDescription gd;
        try {
            Path p = new Path();
            float[] first = g.pts.get(0);
            p.moveTo(first[0], first[1]);
            float[] prev = first;
            for (int i = 1; i < g.pts.size(); i++) {
                float[] q = g.pts.get(i);
                if (q[0] != prev[0] || q[1] != prev[1]) {
                    p.lineTo(q[0], q[1]);
                    prev = q;
                }
            }
            long dur = Math.max(1, Math.min(g.dur, GestureDescription.getMaxGestureDuration()));
            gd = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, dur))
                    .build();
        } catch (Exception ex) {
            done.run();
            return;
        }
        boolean ok = dispatchGesture(gd, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                done.run();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                done.run();
            }
        }, handler);
        if (!ok) done.run();
    }

    // ---------- 保存 / 读取 ----------

    private void save(List<Gesture> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Gesture g : list) {
                JSONObject o = new JSONObject();
                o.put("w", g.wait);
                o.put("d", g.dur);
                JSONArray pts = new JSONArray();
                for (float[] p : g.pts) {
                    JSONArray a = new JSONArray();
                    a.put((double) p[0]);
                    a.put((double) p[1]);
                    pts.put(a);
                }
                o.put("p", pts);
                arr.put(o);
            }
            getSharedPreferences("taprec", MODE_PRIVATE)
                    .edit().putString("rec", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private List<Gesture> load() {
        List<Gesture> list = new ArrayList<>();
        try {
            String s = getSharedPreferences("taprec", MODE_PRIVATE).getString("rec", "[]");
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Gesture g = new Gesture();
                g.wait = o.getLong("w");
                g.dur = o.getLong("d");
                JSONArray pts = o.getJSONArray("p");
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray a = pts.getJSONArray(j);
                    g.pts.add(new float[]{(float) a.getDouble(0), (float) a.getDouble(1)});
                }
                if (!g.pts.isEmpty()) list.add(g);
            }
        } catch (Exception ignored) {
        }
        return list;
    }
}
