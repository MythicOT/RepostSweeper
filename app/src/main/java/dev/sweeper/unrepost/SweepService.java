package dev.sweeper.unrepost;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reads the Instagram screen and taps by label, waiting for each element to
 * appear instead of tapping fixed spots on a timer. Lag only slows it down.
 */
public class SweepService extends AccessibilityService {

    static final String IG = "com.instagram.android";
    static final String PREFS = "sweeper";

    // Labels Instagram may use. Matched case-insensitively against text or content description.
    static final List<String> SELECT = Arrays.asList("select");
    static final List<String> REMOVE = Arrays.asList(
            "remove", "unrepost", "delete", "remove repost", "remove reposts", "undo repost", "undo reposts");
    static final List<String> SELECT_MODE_HINTS = Arrays.asList("cancel", "deselect all", "select all");

    static final int BATCH = 25;          // reposts per round
    static final long WAIT_MS = 15000;    // how long to wait for any one element before giving up
    static final long ROUND_PAUSE_MS = 4000; // breather between rounds so Instagram doesn't rate-limit

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private int removed = 0;

    private WindowManager wm;
    private LinearLayout overlay;
    private WindowManager.LayoutParams overlayLp;
    private TextView status;
    private Button startBtn;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (overlay == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(IG)) {
            overlay.setVisibility(View.VISIBLE);
        } else if (!running && !p.equals(getPackageName()) && !p.equals("com.android.systemui")
                && !p.contains("inputmethod") && !p.contains("keyboard")) {
            overlay.setVisibility(View.GONE);
        }
    }

    @Override
    public void onInterrupt() {
        running = false;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- overlay

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button makeButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        return b;
    }

    private void showOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(12), dp(10), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE16121A);
        bg.setCornerRadius(dp(16));
        overlay.setBackground(bg);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setMaxWidth(dp(230));
        status.setText("Open Your activity → Reposts, then tap Start. Drag me by this text.");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);

        startBtn = makeButton("Start", 0xFFC2185B);
        startBtn.setOnClickListener(v -> {
            if (running) {
                running = false;
                say("Stopping after this step…");
            } else {
                start();
            }
        });
        Button dump = makeButton("Dump", 0xFF3A3340);
        dump.setOnClickListener(v -> new Thread(this::dumpScreen).start());

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = dp(8);
        row.addView(startBtn, gap);
        row.addView(dump);

        overlay.addView(status);
        overlay.addView(row, rowLp);

        overlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        overlayLp.gravity = Gravity.TOP | Gravity.END;
        overlayLp.x = dp(8);
        overlayLp.y = dp(140);

        // Drag the panel up and down by its text so it never covers what it needs to tap.
        status.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            int startLpY;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = ev.getRawY();
                        startLpY = overlayLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        overlayLp.y = Math.max(0, startLpY + (int) (ev.getRawY() - startY));
                        try { wm.updateViewLayout(overlay, overlayLp); } catch (Exception ignored) { }
                        return true;
                    default:
                        return true;
                }
            }
        });

        overlay.setVisibility(View.GONE);
        wm.addView(overlay, overlayLp);
    }

    private void say(String s) {
        main.post(() -> { if (status != null) status.setText(s); });
    }

    private void setButton(String s) {
        main.post(() -> { if (startBtn != null) startBtn.setText(s); });
    }

    // ---------------------------------------------------------------- main loop

    private void start() {
        running = true;
        removed = 0;
        setButton("Stop");
        new Thread(() -> {
            try {
                loop();
            } catch (Exception e) {
                say("Something broke: " + e.getMessage() + ". Tap Dump and send me what it shows.");
            } finally {
                running = false;
                setButton("Start");
            }
        }).start();
    }

    private void loop() {
        int emptyRounds = 0;
        while (running) {
            say("Removed " + removed + ". Looking for reposts…");

            // 1. Get into select mode and find unticked items, waiting as long as the app needs.
            List<AccessibilityNodeInfo> items = selectable();
            if (items.isEmpty()) {
                AccessibilityNodeInfo sel = findLabel(SELECT, false);
                if (sel != null) {
                    tap(sel);
                    say("Tapped Select. Waiting for checkboxes…");
                }
                items = waitList(this::selectable, WAIT_MS);
            }
            if (!running) break;

            // 2. Nothing on screen: scroll to load more, give up after 3 empty tries.
            if (items.isEmpty()) {
                say("None visible. Scrolling to load more…");
                scrollDown();
                items = waitList(this::selectable, 6000);
                if (items.isEmpty()) {
                    if (++emptyRounds >= 3) {
                        say("Done. Removed " + removed + ". Go back and reopen Reposts to double-check.");
                        return;
                    }
                    continue;
                }
            }
            emptyRounds = 0;

            // 3. Tick a batch.
            int n = Math.min(BATCH, items.size());
            say("Selecting " + n + "…");
            for (int i = 0; i < n && running; i++) {
                tap(items.get(i));
                sleep(180);
            }
            if (!running) break;

            // 4. Tap Remove once it is enabled.
            AccessibilityNodeInfo rm = waitNode(() -> findLabel(REMOVE, true), WAIT_MS);
            if (rm == null) {
                say("Couldn't find the Remove button. Tap Dump, then send me what the app shows.");
                return;
            }
            final Rect rmBounds = bounds(rm);
            tap(rm);
            say("Confirming…");

            // 5. Confirm dialog: a second Remove-style button somewhere else on screen.
            AccessibilityNodeInfo ok = waitNode(() -> {
                AccessibilityNodeInfo x = findLabel(REMOVE, true);
                return (x != null && !bounds(x).equals(rmBounds)) ? x : null;
            }, 6000);
            if (ok != null) {
                final Rect okBounds = bounds(ok);
                tap(ok);
                // Wait for the dialog to close before counting it.
                waitUntil(() -> {
                    AccessibilityNodeInfo x = findLabel(REMOVE, true);
                    return x == null || !bounds(x).equals(okBounds);
                }, WAIT_MS);
            }

            removed += n;
            say("Removed " + removed + ". Short pause so Instagram doesn't block you…");
            sleep(ROUND_PAUSE_MS);
        }
        say("Stopped. Removed " + removed + ".");
    }

    // ---------------------------------------------------------------- finding things

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && IG.contentEquals(r.getPackageName())) out.add(r);
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && IG.contentEquals(r.getPackageName())) out.add(r);
        }
        return out;
    }

    private AccessibilityNodeInfo root() {
        List<AccessibilityNodeInfo> r = roots();
        return r.isEmpty() ? null : r.get(0);
    }

    private List<AccessibilityNodeInfo> allNodes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo r : roots()) collect(r, out, 0);
        return out;
    }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 80) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.toString().trim().isEmpty()) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim().toLowerCase();
    }

    private static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    /** Bottom-most visible node whose label is one of the words. */
    private AccessibilityNodeInfo findLabel(List<String> words, boolean mustBeEnabled) {
        AccessibilityNodeInfo best = null;
        int bestTop = -1;
        for (AccessibilityNodeInfo n : allNodes()) {
            if (!n.isVisibleToUser() || !words.contains(label(n))) continue;
            AccessibilityNodeInfo c = clickableSelfOrParent(n);
            if (mustBeEnabled && !(n.isEnabled() && (c == null || c.isEnabled()))) continue;
            int top = bounds(n).top;
            if (top >= bestTop) {
                best = n;
                bestTop = top;
            }
        }
        return best;
    }

    private boolean inSelectMode() {
        return findLabel(REMOVE, false) != null || findLabel(SELECT_MODE_HINTS, false) != null;
    }

    /** Unticked checkboxes; falls back to the list's tiles when Instagram doesn't expose real checkboxes. */
    private List<AccessibilityNodeInfo> selectable() {
        List<AccessibilityNodeInfo> boxes = new ArrayList<>();
        List<AccessibilityNodeInfo> nodes = allNodes();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            String l = label(n);
            boolean looksLikeBox = n.isCheckable() || cls.endsWith("CheckBox") || l.contains("checkbox");
            if (looksLikeBox && !n.isChecked() && !l.equals("selected") && !l.startsWith("selected,"))boxes.add(n);
        }
        if (boxes.isEmpty() && inSelectMode()) {
            // Fallback: clickable children of the main scrolling list.
            AccessibilityNodeInfo list = biggestScrollable(nodes);
            if (list != null) {
                for (int i = 0; i < list.getChildCount(); i++) {
                    AccessibilityNodeInfo c = list.getChild(i);
                    if (c != null && c.isVisibleToUser()) {
                        AccessibilityNodeInfo tgt = c.isClickable() ? c : firstClickable(c, 0);
                        if (tgt != null) boxes.add(tgt);
                    }
                }
            }
        }
        Collections.sort(boxes, (a, b) -> {
            Rect ra = bounds(a), rb = bounds(b);
            return ra.top != rb.top ? Integer.compare(ra.top, rb.top) : Integer.compare(ra.left, rb.left);
        });
        return boxes;
    }

    private AccessibilityNodeInfo firstClickable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) return null;
        if (n.isClickable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstClickable(n.getChild(i), depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo biggestScrollable(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isScrollable() || !n.isVisibleToUser()) continue;
            Rect r = bounds(n);
            long area = (long) r.width() * r.height();
            if (area > bestArea) {
                best = n;
                bestArea = area;
            }
        }
        return best;
    }

    private static AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 6; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    // ---------------------------------------------------------------- acting

    private void tap(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableSelfOrParent(n);
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        Rect r = bounds(n);
        gestureTap(r.centerX(), r.centerY());
    }

    private void gestureTap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        dispatchGesture(g, null, null);
        sleep(150);
    }

    private void scrollDown() {
        AccessibilityNodeInfo list = biggestScrollable(allNodes());
        if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Path p = new Path();
        p.moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.75f);
        p.lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.30f);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null);
    }

    // ---------------------------------------------------------------- waiting

    private AccessibilityNodeInfo waitNode(Supplier<AccessibilityNodeInfo> f, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < end) {
            AccessibilityNodeInfo n = f.get();
            if (n != null) return n;
            sleep(300);
        }
        return null;
    }

    private boolean waitUntil(BooleanSupplier f, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < end) {
            if (f.getAsBoolean()) return true;
            sleep(300);
        }
        return false;
    }

    private List<AccessibilityNodeInfo> waitList(Supplier<List<AccessibilityNodeInfo>> f, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < end) {
            List<AccessibilityNodeInfo> l = f.get();
            if (!l.isEmpty()) return l;
            sleep(300);
        }
        return new ArrayList<>();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ---------------------------------------------------------------- dump

    /** Saves a text outline of the current Instagram screen so it can be shared if a label changed. */
    private void dumpScreen() {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (AccessibilityNodeInfo r : roots()) count = dump(r, sb, 0, count);
        if (count == 0) sb.append("No Instagram screen found. Open Instagram first, then tap Dump.");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("dump", sb.toString()).apply();
        say("Screen saved (" + count + " items). Open the Repost Sweeper app to copy it.");
    }

    private int dump(AccessibilityNodeInfo n, StringBuilder sb, int depth, int count) {
        if (n == null || depth > 60 || count > 600) return count;
        if (n.isVisibleToUser()) {
            String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
            cls = cls.substring(cls.lastIndexOf('.') + 1);
            for (int i = 0; i < depth; i++) sb.append(' ');
            sb.append(cls);
            if (n.getText() != null) sb.append(" t=\"").append(n.getText()).append('"');
            if (n.getContentDescription() != null) sb.append(" d=\"").append(n.getContentDescription()).append('"');
            if (n.getViewIdResourceName() != null) {
                String id = n.getViewIdResourceName();
                sb.append(" id=").append(id.substring(id.indexOf('/') + 1));
            }
            if (n.isClickable()) sb.append(" [click]");
            if (n.isCheckable()) sb.append(n.isChecked() ? " [checked]" : " [unchecked]");
            if (n.isScrollable()) sb.append(" [scroll]");
            if (!n.isEnabled()) sb.append(" [off]");
            sb.append(' ').append(bounds(n).toShortString()).append('\n');
            count++;
        }
        for (int i = 0; i < n.getChildCount(); i++) count = dump(n.getChild(i), sb, depth + 1, count);
        return count;
    }
}
