package com.taprec;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.TouchInteractionController;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RecService extends AccessibilityService {

    public static RecService instance;

    private static class Gesture {
        int kind;   // 0 = 触摸  1 = 返回  2 = 主页
        long wait;
        long dur;
        final List<float[]> pts = new ArrayList<>();
    }

    private static final long SCROLL_GAP = 350;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable flushRunnable = this::flushScroll;
    private WindowManager wm;
    private View panel;
    private WindowManager.LayoutParams panelLp;
    private TextView status;
    private TextView repeatBtn;
    private TextView modeBtn;
    private View captureView;
    private WindowManager.LayoutParams captureLp;

    // 0 = 快速  1 = 快速+左侧精确  2 = 快速+右侧精确  3 = 精确
    private int mode = 0;
    private boolean recording = false;
    private boolean playing = false;
    private int playToken = 0;
    private long lastEnd = 0;
    private List<Gesture> current = new ArrayList<>();
    private Gesture drag;
    private long dragStart;
    private final int[] repeatOptions = {1, 3, 5, 10, 30};
    private int repeatIdx = 0;

    // 快速模式：滚动合并
    private boolean scrollPending = false;
    private long scrollStart;
    private long scrollLast;
    private float scrollDx;
    private float scrollDy;
    private int scrollKey;
    private final Rect scrollRect = new Rect();
    private int lastKey;
    private int lastSx;
    private int lastSy;
    private int lastFrom;
    // 精确区域内最近一次触摸（用来和系统事件去重）
    private long lastPreciseTime = 0;
    private long lastPreciseSwipeTime = 0;
    private float lastPreciseX;
    private float lastPreciseY;
    // 全屏触摸捕获
    private TouchInteractionController ctl;
    private boolean ctlOn = false;
    private boolean tActive;
    private boolean tMoved;
    private boolean tDelegated;
    private boolean tIgnore;
    private boolean tMulti;
    private long tDown;
    private float tx;
    private float ty;
    private float tlx;
    private float tly;
    private boolean injecting = false;
    private Gesture swipeG;
    private long swipeDown;
    private long swipeEnd;
    private boolean haveScroll;
    private boolean scrollDuringTouch;
    private float scrollFx;
    private float scrollFy;
    private int failCount = 0;
    public String captureInfo = "";
    private final Runnable holdRunnable = this::onHold;
    // 快速模式：去重
    private long lastTapTime = 0;
    private float lastTapX;
    private float lastTapY;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        android.content.SharedPreferences sp = getSharedPreferences("taprec", MODE_PRIVATE);
        mode = 3;
        captureInfo = "全屏精确录制（点击几乎没有延迟）";
        if (captureView == null) createCapture();
        showPanel();
    }

    // ---------- 快速模式：根据系统事件录制 ----------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!recording || !eventsOn() || event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && getPackageName().contentEquals(pkg)) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onScrollEvent(event);
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            AccessibilityNodeInfo src = event.getSource();
            if (src == null) return;
            Rect r = new Rect();
            src.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) return;
            if (touchDedupe(r)) return;
            flushScroll();
            addFastTouch(r.exactCenterX(), r.exactCenterY(),
                    type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ? 700 : 60);
        }
    }

    private void addFastTouch(float x, float y, long dur) {
        long now = SystemClock.uptimeMillis();
        if (lastTapTime != 0 && now - lastTapTime < 250
                && Math.abs(x - lastTapX) < 20 && Math.abs(y - lastTapY) < 20) {
            return;
        }
        lastTapTime = now;
        lastTapX = x;
        lastTapY = y;
        Gesture g = new Gesture();
        g.dur = dur;
        g.pts.add(new float[]{x, y});
        g.wait = lastEnd == 0 ? 0 : Math.max(250, now - lastEnd);
        lastEnd = now;
        current.add(g);
        setStatus("录制中：" + current.size() + " 个动作");
    }

    private void onScrollEvent(AccessibilityEvent e) {
        AccessibilityNodeInfo src = e.getSource();
        if (src == null) return;
        Rect r = new Rect();
        src.getBoundsInScreen(r);
        if (r.width() < 100 || r.height() < 100) return;

        float dx = 0;
        float dy = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            dx = e.getScrollDeltaX();
            dy = e.getScrollDeltaY();
        }
        int key = r.left * 31 + r.top * 17 + r.right * 13 + r.bottom * 7;
        if (dx == 0 && dy == 0 && key == lastKey) {
            dx = e.getScrollX() - lastSx;
            dy = e.getScrollY() - lastSy;
            if (dx == 0 && dy == 0) {
                float rows = Math.max(1, e.getToIndex() - e.getFromIndex() + 1);
                dy = (e.getFromIndex() - lastFrom) * (r.height() / rows);
            }
        }
        lastKey = key;
        lastSx = e.getScrollX();
        lastSy = e.getScrollY();
        lastFrom = e.getFromIndex();
        if (dx == 0 && dy == 0) return;
        if (tActive && !tIgnore) scrollDuringTouch = true;

        long now = SystemClock.uptimeMillis();
        if (scrollPending && (now - scrollLast > SCROLL_GAP || key != scrollKey)) {
            flushScroll();
        }
        if (!scrollPending) {
            scrollPending = true;
            scrollStart = now;
            scrollDx = 0;
            scrollDy = 0;
            scrollKey = key;
            scrollRect.set(r);
        }
        scrollDx += dx;
        scrollDy += dy;
        scrollLast = now;
        handler.removeCallbacks(flushRunnable);
        handler.postDelayed(flushRunnable, SCROLL_GAP);
    }

    private void flushScroll() {
        handler.removeCallbacks(flushRunnable);
        if (!scrollPending) return;
        scrollPending = false;
        float dx = scrollDx;
        float dy = scrollDy;
        if (Math.abs(dx) < 5 && Math.abs(dy) < 5) return;
        if (ctlOn && absorbScroll(dx, dy)) return;

        float cx = scrollRect.exactCenterX();
        float cy = scrollRect.exactCenterY();
        float x1 = cx;
        float y1 = cy;
        float x2 = cx;
        float y2 = cy;
        if (Math.abs(dy) >= Math.abs(dx)) {
            float half = Math.min(Math.max(Math.abs(dy), 150f), scrollRect.height() * 0.7f) / 2f;
            y1 = cy + (dy > 0 ? half : -half);
            y2 = cy + (dy > 0 ? -half : half);
        } else {
            float half = Math.min(Math.max(Math.abs(dx), 150f), scrollRect.width() * 0.7f) / 2f;
            x1 = cx + (dx > 0 ? half : -half);
            x2 = cx + (dx > 0 ? -half : half);
        }
        Gesture g = new Gesture();
        g.dur = 300;
        g.pts.add(new float[]{x1, y1});
        g.pts.add(new float[]{x2, y2});
        g.wait = lastEnd == 0 ? 0 : Math.max(250, scrollStart - lastEnd);
        lastEnd = scrollLast;
        current.add(g);
        setStatus("录制中：" + current.size() + " 个动作");
    }

    // ---------- 全屏触摸捕获（Android 12 及以上） ----------
    // 系统先把每次触摸交给本服务，本服务记下位置后马上放行给应用，页面没有延迟。

    private CtlCallback ctlCb;

    private class CtlCallback implements TouchInteractionController.Callback {
        @Override
        public void onMotionEvent(MotionEvent e) {
            try {
                onCtlMotion(e);
            } catch (Throwable t) {
                delegateNow();
            }
        }

        @Override
        public void onStateChanged(int state) {
            try {
                onCtlState(state);
            } catch (Throwable t) {
                delegateNow();
            }
        }
    }

    private boolean startTouchCapture() {
        try {
            ctl = getTouchInteractionController(0);
            if (ctl == null) return false;
            ctlCb = new CtlCallback();
            ctl.registerCallback(r -> handler.post(r), ctlCb);
            ctlOn = true;
            return true;
        } catch (Throwable t) {
            ctl = null;
            ctlOn = false;
            return false;
        }
    }

    private void delegateNow() {
        if (ctl == null) return;
        try {
            int s = ctl.getState();
            if (s == TouchInteractionController.STATE_TOUCH_INTERACTING
                    || s == TouchInteractionController.STATE_TOUCH_EXPLORING) {
                ctl.requestDelegating();
            }
            tDelegated = true;
            failCount = 0;
        } catch (Throwable t) {
            failCount++;
            if (failCount >= 3) disableSelf();
        }
    }

    private void onHold() {
        delegateNow();
    }

    private boolean nearEdge(float x, float y) {
        int m = dp(18);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return x < m || y < m || x > w - m || y > h - m;
    }

    private boolean inPanel(float x, float y) {
        if (panel == null) return false;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        return x >= loc[0] - 8 && x <= loc[0] + panel.getWidth() + 8
                && y >= loc[1] - 8 && y <= loc[1] + panel.getHeight() + 8;
    }

    private void onCtlMotion(MotionEvent e) {
        int a = e.getActionMasked();
        float x = e.getRawX();
        float y = e.getRawY();
        if (a == MotionEvent.ACTION_DOWN) {
            handler.removeCallbacks(holdRunnable);
            handler.postDelayed(holdRunnable, 150);
            tActive = true;
            tDelegated = false;
            tMoved = false;
            tMulti = false;
            scrollDuringTouch = false;
            haveScroll = false;
            tDown = SystemClock.uptimeMillis();
            tx = x;
            ty = y;
            tlx = x;
            tly = y;
            tIgnore = !recording || playing || injecting || nearEdge(x, y) || inPanel(x, y);
            if (tIgnore) {
                lastPreciseTime = 0;
                delegateNow();
            } else {
                lastPreciseX = x;
                lastPreciseY = y;
                lastPreciseTime = tDown;
            }
            return;
        }
        if (!tActive) {
            delegateNow();
            return;
        }
        if (a == MotionEvent.ACTION_POINTER_DOWN) {
            tMulti = true;
            delegateNow();
            return;
        }
        if (a == MotionEvent.ACTION_CANCEL) {
            tActive = false;
            handler.removeCallbacks(holdRunnable);
            delegateNow();
            return;
        }
        if (tDelegated) return;
        if (a == MotionEvent.ACTION_MOVE) {
            tlx = x;
            tly = y;
            if (Math.hypot(x - tx, y - ty) > dp(10)) {
                tMoved = true;
                delegateNow();
            }
        } else if (a == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(holdRunnable);
            tActive = false;
            if (!tIgnore && !tMulti) quickTap(tx, ty);
        }
    }

    private void onCtlState(int state) {
        if (state == TouchInteractionController.STATE_TOUCH_INTERACTING) {
            // 保险：不管发生什么，150 毫秒后一定放行
            if (!tActive) handler.postDelayed(holdRunnable, 150);
        } else if (state == TouchInteractionController.STATE_CLEAR) {
            if (tActive) finishTouch();
        }
    }

    // 很快的点击：手指抬起时才知道是点击，由本服务代为点一下
    private void quickTap(float x, float y) {
        flushScroll();
        addTouchAt(x, y, 60, tDown, SystemClock.uptimeMillis());
        Gesture g = new Gesture();
        g.dur = 50;
        g.pts.add(new float[]{x, y});
        injecting = true;
        dispatch(g, () -> handler.postDelayed(() -> injecting = false, 30));
    }

    // 已经放行的触摸（长按、慢点、滑动）结束
    private void finishTouch() {
        flushScroll();
        tActive = false;
        handler.removeCallbacks(holdRunnable);
        if (tIgnore || tMulti || !recording) return;
        long now = SystemClock.uptimeMillis();
        if (tMoved || (scrollDuringTouch && haveScroll)) {
            addCtlSwipe(now);
        } else {
            addTouchAt(tx, ty, Math.max(50, Math.min(now - tDown, 1500)), tDown, now);
        }
    }

    private void addTouchAt(float x, float y, long dur, long tStart, long now) {
        Gesture g = new Gesture();
        g.dur = dur;
        g.pts.add(new float[]{x, y});
        g.wait = lastEnd == 0 ? 0 : Math.max(250, tStart - lastEnd);
        lastEnd = now;
        lastPreciseTime = now;
        swipeG = null;
        current.add(g);
        setStatus("录制中：" + current.size() + " 个动作");
    }

    private void addCtlSwipe(long now) {
        float fx;
        float fy;
        if (haveScroll) {
            fx = scrollFx;
            fy = scrollFy;
        } else {
            float ddx = tlx - tx;
            float ddy = tly - ty;
            float d = (float) Math.hypot(ddx, ddy);
            if (d < 1) {
                fx = 0;
                fy = -300;
            } else {
                fx = ddx / d * 300;
                fy = ddy / d * 300;
            }
        }
        Gesture g = new Gesture();
        g.dur = 300;
        g.pts.add(new float[]{tx, ty});
        applyVec(g, fx, fy);
        g.wait = lastEnd == 0 ? 0 : Math.max(250, tDown - lastEnd);
        lastEnd = now;
        lastPreciseTime = now;
        haveScroll = false;
        scrollDuringTouch = false;
        swipeG = g;
        swipeDown = tDown;
        swipeEnd = now;
        current.add(g);
        setStatus("录制中：" + current.size() + " 个动作");
    }

    // 以手指起点为准，按手指移动方向和距离确定终点
    private void applyVec(Gesture g, float fx, float fy) {
        float[] s = g.pts.get(0);
        float w = getResources().getDisplayMetrics().widthPixels;
        float h = getResources().getDisplayMetrics().heightPixels;
        float ax = Math.abs(fx);
        float ay = Math.abs(fy);
        if (Math.min(ax, ay) < 0.3f * Math.max(ax, ay)) {
            if (ay >= ax) {
                fx = 0;
            } else {
                fy = 0;
            }
        }
        float len = (float) Math.hypot(fx, fy);
        if (len < 1) return;
        float limit = 0.7f * (Math.abs(fx) > Math.abs(fy) ? w : h);
        float want = Math.min(Math.max(len, 150f), limit);
        float ex = s[0] + fx / len * want;
        float ey = s[1] + fy / len * want;
        ex = Math.max(10f, Math.min(w - 10f, ex));
        ey = Math.max(10f, Math.min(h - 10f, ey));
        g.pts.clear();
        g.pts.add(s);
        g.pts.add(new float[]{ex, ey});
    }

    // 滚动事件和触摸捕获的滑动是同一次操作，合并
    private boolean absorbScroll(float dx, float dy) {
        float fx = -dx;
        float fy = -dy;
        if (tActive && !tIgnore) {
            haveScroll = true;
            scrollFx = fx;
            scrollFy = fy;
            return true;
        }
        if (swipeG != null && scrollStart >= swipeDown - 300 && scrollStart <= swipeEnd + 400) {
            applyVec(swipeG, fx, fy);
            return true;
        }
        return false;
    }

    private boolean touchDedupe(Rect r) {
        if (!ctlOn) return false;
        boolean recent = (tActive && !tIgnore)
                || SystemClock.uptimeMillis() - lastPreciseTime < 800;
        if (!recent) return false;
        Rect big = new Rect(r);
        big.inset(-24, -24);
        return big.contains((int) lastPreciseX, (int) lastPreciseY);
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
            if (ctl != null && ctlCb != null) ctl.unregisterCallback(ctlCb);
        } catch (Throwable ignored) {
        }
        ctl = null;
        ctlOn = false;
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

    private boolean eventsOn() {
        return mode != 3;
    }

    private boolean captureOn() {
        return mode != 0;
    }

    private String modeText() {
        return "模式：全屏";
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
        box.addView(makeButton("↩ 返回", v -> navKey(1)));
        box.addView(makeButton("⌂ 主页", v -> navKey(2)));
        repeatBtn = makeButton("次数 ×" + repeatOptions[repeatIdx], v -> {
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

    // ---------- 精确模式：全屏捕获层（录制时才可触摸） ----------

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
        if (mode == 1 || mode == 2) {
            captureLp.width = getResources().getDisplayMetrics().widthPixels * 3 / 10;
            captureLp.gravity = (mode == 2 ? Gravity.END : Gravity.START) | Gravity.TOP;
        } else {
            captureLp.width = WindowManager.LayoutParams.MATCH_PARENT;
            captureLp.gravity = Gravity.START | Gravity.TOP;
        }
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
                    long held = Math.max(50, e.getEventTime() - dragStart);
                    boolean moved = false;
                    float[] p0 = g.pts.get(0);
                    for (float[] q : g.pts) {
                        if (Math.hypot(q[0] - p0[0], q[1] - p0[1]) > 24) moved = true;
                    }
                    boolean tap = !moved && held < 350;
                    g.dur = moved ? Math.min(held, 300) : (tap ? 60 : Math.min(held, 1200));
                    lastEnd = e.getEventTime();
                    lastPreciseTime = SystemClock.uptimeMillis();
                    lastPreciseX = x;
                    lastPreciseY = y;
                    if (moved) lastPreciseSwipeTime = lastPreciseTime;
                    thin(g);
                    current.add(g);
                    setStatus("录制中：" + current.size() + " 个动作");
                    passThrough(g, tap);
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
    private void passThrough(Gesture g, boolean tap) {
        Gesture inj = g;
        if (tap) {
            inj = new Gesture();
            inj.dur = 8;
            inj.pts.add(g.pts.get(0));
        }
        setCapture(false);
        dispatch(inj, () -> {
            if (recording && captureOn()) setCapture(true);
        });
    }

    // ---------- 录制 / 回放 ----------

    private void startRecording() {
        if (recording || playing) return;
        recording = true;
        current = new ArrayList<>();
        lastEnd = 0;
        lastTapTime = 0;
        drag = null;
        scrollPending = false;
        lastPreciseTime = 0;
        lastPreciseSwipeTime = 0;
        swipeG = null;
        haveScroll = false;
        scrollDuringTouch = false;
        setStatus("录制中（" + modeText().substring(3) + "）");
        if (captureOn()) setCapture(true);
    }

    private void stopRecording() {
        flushScroll();
        recording = false;
        setCapture(false);
        if (current.isEmpty()) {
            setStatus(mode == 0 ? "没录到动作，可换一种模式再试" : "没录到动作");
            return;
        }
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

    // 面板上的“返回”“主页”：立即执行；录制中同时记下来
    private void navKey(int kind) {
        if (playing) return;
        performGlobalAction(kind == 1 ? GLOBAL_ACTION_BACK : GLOBAL_ACTION_HOME);
        if (!recording) return;
        flushScroll();
        long now = SystemClock.uptimeMillis();
        Gesture g = new Gesture();
        g.kind = kind;
        g.wait = lastEnd == 0 ? 0 : Math.max(250, now - lastEnd);
        lastEnd = now;
        current.add(g);
        setStatus("录制中：" + current.size() + " 个动作");
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
            runAction(g, () -> playRound(list, idx + 1, round, total, token));
        }, wait);
    }

    private void runAction(Gesture g, Runnable done) {
        if (g.kind == 1) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(done, 400);
        } else if (g.kind == 2) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            handler.postDelayed(done, 600);
        } else {
            dispatch(g, done);
        }
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
                o.put("k", g.kind);
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
                g.kind = o.optInt("k", 0);
                g.wait = o.getLong("w");
                g.dur = o.getLong("d");
                JSONArray pts = o.getJSONArray("p");
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray a = pts.getJSONArray(j);
                    g.pts.add(new float[]{(float) a.getDouble(0), (float) a.getDouble(1)});
                }
                if (g.kind != 0 || !g.pts.isEmpty()) list.add(g);
            }
        } catch (Exception ignored) {
        }
        return list;
    }
}
