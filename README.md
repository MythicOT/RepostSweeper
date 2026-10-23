# Repost Sweeper

Android app that removes your Instagram reposts in batches of 25, inside the Instagram app.

It runs as an accessibility service: it reads the Instagram screen and taps **Select**, the checkboxes, **Remove**, and the confirm button by their labels. It waits (up to 15 s) for each one to appear before tapping, so a laggy app only makes it slower.

## Build the APK

**Android Studio (on your PC):** File → Open → this folder. Let Gradle sync, then Build → Build App Bundle(s) / APK(s) → Build APK(s). The file lands in `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and open it to install.

**GitHub (no Android Studio):** push this folder to a new GitHub repo. The included workflow builds it automatically; open the repo's Actions tab, pick the latest run, and download `RepostSweeper-apk` from your phone.

## Use it

1. Open Repost Sweeper → Open Accessibility settings → Downloaded apps → Repost Sweeper → on.
   If Android calls it a restricted setting: Settings → Apps → Repost Sweeper → ⋮ → Allow restricted settings, then try again.
2. In Instagram: profile → ☰ → Your activity → Interactions → Reposts.
3. Tap **Start** on the dark panel. Tap **Stop** any time. Drag the panel by its text if it's in the way.

If it gets stuck, tap **Dump** on the stuck screen, then open the app and tap **Copy screen outline**. That text shows the labels Instagram is using, so the matching words in `SweepService.java` (`SELECT`, `REMOVE`) can be fixed.

Tuning lives at the top of `SweepService.java`: `BATCH`, `WAIT_MS`, `ROUND_PAUSE_MS`.
