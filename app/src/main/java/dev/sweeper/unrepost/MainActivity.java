package dev.sweeper.unrepost;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView serviceState;
    private TextView dumpView;
    private int fg, muted, accent, surface, bg;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF141118 : 0xFFF6F4F8;
        surface = dark ? 0xFF1E1A23 : 0xFFFFFFFF;
        fg = dark ? 0xFFEFEAF3 : 0xFF1C1622;
        muted = dark ? 0xFFA59CB0 : 0xFF6B6274;
        accent = dark ? 0xFFF0628F : 0xFFC2185B;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(28), dp(20), dp(40));
        scroll.addView(col);

        TextView title = text("Repost Sweeper", 30, fg);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        col.addView(title);
        col.addView(space(6));
        col.addView(text("Clears your Instagram reposts in batches of 25. It reads the screen and waits for each "
                + "button to appear, so a slow app just makes it slower, not broken.", 15, muted));

        col.addView(space(24));
        serviceState = text("", 15, fg);
        serviceState.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(serviceState);
        col.addView(space(10));
        col.addView(button("Open Accessibility settings", true, v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        col.addView(space(8));
        col.addView(button("Open Instagram", false, v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(SweepService.IG);
            if (i != null) startActivity(i);
            else Toast.makeText(this, "Instagram isn't installed", Toast.LENGTH_SHORT).show();
        }));

        col.addView(space(28));
        col.addView(heading("How to use it"));
        col.addView(steps(
                "Tap Open Accessibility settings, find Repost Sweeper under Downloaded apps, and turn it on. "
                        + "If Android says it's a restricted setting, go to Settings → Apps → Repost Sweeper → ⋮ → "
                        + "Allow restricted settings, then try again.",
                "Open Instagram and go to your profile → ☰ → Your activity → Interactions → Reposts.",
                "A small dark panel appears. Drag it by its text if it covers anything.",
                "Tap Start. Leave the phone alone; tap Stop whenever you want.",
                "If Instagram says \"Try again later\", stop and come back in a few hours."));

        col.addView(space(28));
        col.addView(heading("If it gets stuck"));
        col.addView(text("Tap Dump on the panel while Instagram is on the stuck screen. Then come back here, "
                + "tap Copy screen outline, and paste it to Claude so the labels can be fixed.", 15, muted));
        col.addView(space(10));
        col.addView(button("Copy screen outline", false, v -> {
            String d = getSharedPreferences(SweepService.PREFS, MODE_PRIVATE).getString("dump", "");
            if (d.isEmpty()) {
                Toast.makeText(this, "Nothing saved yet. Tap Dump in Instagram first.", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("screen outline", d));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }));
        col.addView(space(10));
        dumpView = text("", 11, muted);
        dumpView.setTypeface(Typeface.MONOSPACE);
        dumpView.setTextIsSelectable(true);
        col.addView(dumpView);

        setContentView(scroll);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = serviceEnabled();
        serviceState.setText(on ? "✓ Sweeper is on" : "Sweeper is off. Turn it on below.");
        serviceState.setTextColor(on ? (fg == 0xFF1C1622 ? 0xFF1E7A4C : 0xFF5FD39A) : accent);
        String d = getSharedPreferences(SweepService.PREFS, MODE_PRIVATE).getString("dump", "");
        dumpView.setText(d.length() > 4000 ? d.substring(0, 4000) + "\n…" : d);
    }

    private boolean serviceEnabled() {
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        String me = new ComponentName(this, SweepService.class).flattenToString();
        String meShort = new ComponentName(this, SweepService.class).flattenToShortString();
        for (String s : list.split(":")) if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        return false;
    }

    // ---------------------------------------------------------------- small view helpers

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 19, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private LinearLayout steps(String... items) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView n = text((i + 1) + ".", 15, accent);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setMinWidth(dp(26));
            TextView body = text(items[i], 15, fg);
            row.addView(n);
            row.addView(body, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            l.addView(row);
        }
        return l;
    }

    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(primary ? Color.WHITE : fg);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(primary ? accent : surface);
        if (!primary) d.setStroke(dp(1), muted & 0x55FFFFFF);
        b.setBackground(d);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setOnClickListener(click);
        return b;
    }
}
