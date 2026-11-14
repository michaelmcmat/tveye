package app.tveye;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements CursorView.Listener {

    private static final long GESTURE_WINDOW_MS = 8000;

    private View root, toolbar;
    private ProgressBar progress;
    private GeckoView gecko;
    private ScrollView start;
    private EditText url, search;
    private TextView updateBanner, noBookmarks, bookmarksTitle, version;
    private GridLayout bookmarksGrid;
    private ImageButton btnStar, btnMode, btnShield;
    private CursorView cursor;

    private GeckoRuntime runtime;
    private GeckoSession session;
    private Bookmarks bookmarks;
    private Updater updater;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String currentUrl, currentTitle;
    private boolean canGoBack, fullscreen, playing, desktopMode, backLong;
    private MediaSession media;
    private long okDownTime = -1;
    private long lastUserInputMs;
    private Updater.Release pendingRelease;
    private File readyApk;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        toolbar = findViewById(R.id.toolbar);
        progress = findViewById(R.id.progress);
        gecko = findViewById(R.id.gecko);
        start = findViewById(R.id.start);
        url = findViewById(R.id.url);
        search = findViewById(R.id.search);
        updateBanner = findViewById(R.id.update_banner);
        noBookmarks = findViewById(R.id.no_bookmarks);
        bookmarksTitle = findViewById(R.id.bookmarks_title);
        bookmarksGrid = findViewById(R.id.bookmarks);
        version = findViewById(R.id.version);
        btnStar = findViewById(R.id.btn_star);
        btnMode = findViewById(R.id.btn_mode);
        btnShield = findViewById(R.id.btn_shield);
        cursor = findViewById(R.id.cursor);
        cursor.setListener(this);

        prefs = getSharedPreferences("tveye", Context.MODE_PRIVATE);
        desktopMode = prefs.getBoolean("desktop", true);
        bookmarks = new Bookmarks(this);
        updater = new Updater(this);

        runtime = Engine.get(this);
        newSession();

        findViewById(R.id.btn_home).setOnClickListener(v -> showStart(true));
        findViewById(R.id.btn_back).setOnClickListener(v -> back());
        findViewById(R.id.btn_reload).setOnClickListener(v -> { if (isWeb(currentUrl)) session.reload(); });
        btnStar.setOnClickListener(v -> toggleBookmark());
        btnShield.setOnClickListener(v -> toggleAdblock());
        btnMode.setOnClickListener(v -> toggleMode());
        updateModeIcon();

        TextView.OnEditorActionListener go = (v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                go(v.getText().toString());
                return true;
            }
            return false;
        };
        url.setOnEditorActionListener(go);
        search.setOnEditorActionListener(go);
        updateBanner.setOnClickListener(v -> onUpdateClicked());

        Engine.onUblockState = () -> main.post(this::showVersion);
        showVersion();
        showStart(true);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------------------------------------------------------- session

    private void newSession() {
        GeckoSessionSettings st = new GeckoSessionSettings.Builder()
                .usePrivateMode(true)
                .userAgentMode(desktopMode ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(desktopMode ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .suspendMediaWhenInactive(true)
                .build();
        session = new GeckoSession(st);
        session.setNavigationDelegate(navigation);
        session.setContentDelegate(content);
        session.setProgressDelegate(progressDelegate);
        session.setPermissionDelegate(permissions);
        session.setPromptDelegate(new Prompts(this));
        session.setMediaSessionDelegate(mediaDelegate);
        session.open(runtime);
        gecko.setSession(session);
    }

    private void recover() {
        String last = currentUrl;
        gecko.releaseSession();
        session.close();
        media = null;
        setPlaying(false);
        setFullscreen(false);
        newSession();
        if (isWeb(last)) {
            markUserIntent();
            session.loadUri(last);
            toast(getString(R.string.engine_restarted));
        } else {
            showStart(true);
        }
    }

    private void go(String text) {
        String target = toUrl(text);
        hideKeyboard();
        if (target == null) return;
        markUserIntent();
        showStart(false);
        gecko.requestFocus();
        session.loadUri(target);
    }

    private static String toUrl(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return t;
        if (!t.contains(" ") && t.contains(".") && !t.endsWith(".") && t.indexOf(':') < 0) return "https://" + t;
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- delegates

    private final GeckoSession.NavigationDelegate navigation = new GeckoSession.NavigationDelegate() {
        @Override
        public void onLocationChange(@NonNull GeckoSession s, @Nullable String u,
                                     @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     @NonNull Boolean hasUserGesture) {
            currentUrl = u;
            if (!url.hasFocus()) url.setText(isWeb(u) ? u : "");
            updateStar();
        }

        @Override
        public void onCanGoBack(@NonNull GeckoSession s, boolean b) { canGoBack = b; }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession s, @NonNull LoadRequest r) {
            return decide(r, true);
        }

        @Override
        public GeckoResult<AllowOrDeny> onSubframeLoadRequest(@NonNull GeckoSession s, @NonNull LoadRequest r) {
            return decide(r, false);
        }

        @Override
        public GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession s, @NonNull String uri) {
            // A new window from the same site opens here; pop-ups to other sites are dropped.
            if (isWeb(uri) && sameSite(uri, currentUrl)) {
                main.post(() -> session.loadUri(uri));
            } else {
                main.post(() -> toast(getString(R.string.popup_blocked)));
            }
            return null;
        }

        @Override
        public GeckoResult<String> onLoadError(@NonNull GeckoSession s, @Nullable String uri, @NonNull WebRequestError e) {
            return GeckoResult.fromValue(errorPage(uri, e));
        }
    };

    private GeckoResult<AllowOrDeny> decide(GeckoSession.NavigationDelegate.LoadRequest r, boolean topLevel) {
        String u = r.uri;
        String scheme = scheme(u);
        boolean allowedScheme = scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                || scheme.equals("data") || scheme.equals("blob") || scheme.equals("moz-extension");
        if (!allowedScheme) {
            if (topLevel) main.post(() -> toast(getString(R.string.scheme_blocked)));
            return GeckoResult.deny();
        }
        // A page that sends itself to another site on its own (no click, not a server redirect) is a classic
        // malvertising trick: refuse it. Server-side redirects and anything after a press of OK go through.
        if (topLevel && isWeb(u) && isWeb(currentUrl) && !r.isRedirect && !userActedRecently() && !sameSite(u, currentUrl)) {
            String host = host(u);
            main.post(() -> toast(getString(R.string.redirect_blocked, host)));
            return GeckoResult.deny();
        }
        return GeckoResult.allow();
    }

    private final GeckoSession.ContentDelegate content = new GeckoSession.ContentDelegate() {
        @Override
        public void onTitleChange(@NonNull GeckoSession s, @Nullable String title) { currentTitle = title; }

        @Override
        public void onFullScreen(@NonNull GeckoSession s, boolean full) { setFullscreen(full); }

        @Override
        public void onExternalResponse(@NonNull GeckoSession s, @NonNull WebResponse response) {
            try { if (response.body != null) response.body.close(); } catch (IOException ignored) { }
            toast(getString(R.string.download_blocked));
        }

        @Override
        public void onCloseRequest(@NonNull GeckoSession s) {
            if (canGoBack) session.goBack(); else showStart(true);
        }

        @Override
        public void onCrash(@NonNull GeckoSession s) { main.post(MainActivity.this::recover); }

        @Override
        public void onKill(@NonNull GeckoSession s) { main.post(MainActivity.this::recover); }
    };

    private final GeckoSession.ProgressDelegate progressDelegate = new GeckoSession.ProgressDelegate() {
        @Override
        public void onPageStart(@NonNull GeckoSession s, @NonNull String u) {
            progress.setProgress(5);
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageStop(@NonNull GeckoSession s, boolean success) { progress.setVisibility(View.INVISIBLE); }

        @Override
        public void onProgressChange(@NonNull GeckoSession s, int p) {
            progress.setProgress(p);
            if (p >= 100) progress.setVisibility(View.INVISIBLE);
        }
    };

    private final GeckoSession.PermissionDelegate permissions = new GeckoSession.PermissionDelegate() {
        @Override
        public void onAndroidPermissionsRequest(@NonNull GeckoSession s, @Nullable String[] perms, @NonNull Callback cb) {
            cb.reject();
        }

        @Override
        public GeckoResult<Integer> onContentPermissionRequest(@NonNull GeckoSession s, @NonNull ContentPermission p) {
            boolean autoplay = p.permission == PERMISSION_AUTOPLAY_AUDIBLE || p.permission == PERMISSION_AUTOPLAY_INAUDIBLE;
            return GeckoResult.fromValue(autoplay ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
        }

        @Override
        public void onMediaPermissionRequest(@NonNull GeckoSession s, @NonNull String uri, @Nullable MediaSource[] video,
                                             @Nullable MediaSource[] audio, @NonNull MediaCallback cb) {
            cb.reject();
        }
    };

    private final MediaSession.Delegate mediaDelegate = new MediaSession.Delegate() {
        @Override
        public void onActivated(@NonNull GeckoSession s, @NonNull MediaSession ms) { media = ms; }

        @Override
        public void onDeactivated(@NonNull GeckoSession s, @NonNull MediaSession ms) { media = null; setPlaying(false); }

        @Override
        public void onPlay(@NonNull GeckoSession s, @NonNull MediaSession ms) { media = ms; setPlaying(true); }

        @Override
        public void onPause(@NonNull GeckoSession s, @NonNull MediaSession ms) { setPlaying(false); }

        @Override
        public void onStop(@NonNull GeckoSession s, @NonNull MediaSession ms) { setPlaying(false); }
    };

    // ---------------------------------------------------------------- remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down || up) cursor.onArrow(code, down, e.getRepeatCount() > 0);
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Enter from a keyboard submits the field being typed in; the remote's OK is DPAD_CENTER.
                if (getCurrentFocus() instanceof EditText) return super.dispatchKeyEvent(e);
                // fall through
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (down && e.getRepeatCount() == 0) okDown();
                else if (up) okUp();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down && e.getRepeatCount() > 0 && e.isLongPress() && !backLong) {
                    backLong = true;
                    showStart(true);
                } else if (up) {
                    if (!backLong && !e.isCanceled()) back();
                    backLong = false;
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && e.getRepeatCount() == 0 && media != null) {
                    if (code == KeyEvent.KEYCODE_MEDIA_PLAY || (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !playing)) media.play();
                    else media.pause();
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down) pageScroll(-0.8f);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) pageScroll(0.8f);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void back() {
        View f = getCurrentFocus();
        if (f instanceof EditText && f.hasFocus() && keyboardOpen()) { hideKeyboard(); return; }
        if (fullscreen) { session.exitFullScreen(); return; }
        if (start.getVisibility() == View.VISIBLE) {
            if (isWeb(currentUrl)) showStart(false); else finish();
            return;
        }
        if (canGoBack) session.goBack(); else showStart(true);
    }

    private void okDown() {
        markUserIntent();
        cursor.wake();
        float x = cursor.cursorX(), y = cursor.cursorY();
        View target = viewAt(root, (int) x, (int) y);
        if (target == gecko || isInside(gecko, target)) gecko.requestFocus();
        okDownTime = SystemClock.uptimeMillis();
        sendTouch(MotionEvent.ACTION_DOWN, x, y);
    }

    private void okUp() {
        if (okDownTime < 0) return;
        float x = cursor.cursorX(), y = cursor.cursorY();
        sendTouch(MotionEvent.ACTION_UP, x, y);
        okDownTime = -1;
        View target = viewAt(root, (int) x, (int) y);
        if (target instanceof EditText) {
            EditText field = (EditText) target;
            field.requestFocus();
            // Like any browser: a click on the address selects it all, so typing replaces the old address.
            field.post(field::selectAll);
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void sendTouch(int action, float x, float y) {
        MotionEvent ev = MotionEvent.obtain(okDownTime, SystemClock.uptimeMillis(), action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(ev);
        ev.recycle();
    }

    @Override
    public void onCursorMoved(float x, float y) {
        if (okDownTime >= 0) { sendTouch(MotionEvent.ACTION_MOVE, x, y); return; }
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        root.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    @Override
    public void onCursorEdge(float dx, float dy) {
        if (start.getVisibility() == View.VISIBLE) { start.scrollBy(0, (int) dy); return; }
        wheel(dx, dy, cursor.cursorX());
    }

    private void pageScroll(float pages) {
        if (start.getVisibility() == View.VISIBLE) { start.smoothScrollBy(0, (int) (start.getHeight() * pages)); return; }
        wheel(0, gecko.getHeight() * pages, cursor.cursorX());
    }

    /** A mouse-wheel event over the page, so Gecko scrolls whatever is under the pointer. */
    private void wheel(float dx, float dy, float atX) {
        int[] g = new int[2], r = new int[2];
        gecko.getLocationInWindow(g);
        root.getLocationInWindow(r);
        float gx = Math.max(1, Math.min(gecko.getWidth() - 2, atX - (g[0] - r[0])));
        float gy = Math.max(1, Math.min(gecko.getHeight() - 2, cursor.cursorY() - (g[1] - r[1])));
        ViewConfiguration vc = ViewConfiguration.get(this);
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = gx;
        pc.y = gy;
        pc.setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / vc.getScaledVerticalScrollFactor());
        pc.setAxisValue(MotionEvent.AXIS_HSCROLL, dx / vc.getScaledHorizontalScrollFactor());
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        gecko.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    // ---------------------------------------------------------------- UI state

    private void showStart(boolean show) {
        if (show) {
            if (fullscreen) session.exitFullScreen();
            refreshBookmarks();
            search.setText("");
            start.scrollTo(0, 0);
            start.setVisibility(View.VISIBLE);
            updater.check(false, updateCallback);
        } else {
            start.setVisibility(View.GONE);
        }
    }

    private void setFullscreen(boolean full) {
        fullscreen = full;
        toolbar.setVisibility(full ? View.GONE : View.VISIBLE);
        if (full) progress.setVisibility(View.GONE);
        else if (progress.getVisibility() == View.GONE) progress.setVisibility(View.INVISIBLE);
        cursor.setAutoHide(fullscreen || playing);
    }

    private void setPlaying(boolean p) {
        playing = p;
        if (p) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cursor.setAutoHide(fullscreen || playing);
    }

    private void refreshBookmarks() {
        bookmarksGrid.removeAllViews();
        List<Bookmarks.Item> items = bookmarks.all();
        noBookmarks.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        bookmarksTitle.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        for (Bookmarks.Item it : items) {
            TextView t = new TextView(this);
            t.setText(it.title + "\n" + host(it.url));
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setBackgroundResource(R.drawable.tile_bg);
            int p = dp(16);
            t.setPadding(p, dp(12), p, dp(12));
            t.setClickable(true);
            t.setOnClickListener(v -> go(it.url));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(250);
            lp.height = dp(76);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            bookmarksGrid.addView(t, lp);
        }
    }

    private void toggleBookmark() {
        if (!isWeb(currentUrl)) return;
        boolean now = bookmarks.toggle(currentTitle, currentUrl);
        toast(getString(now ? R.string.bookmark_added : R.string.bookmark_removed));
        updateStar();
    }

    private void updateStar() {
        btnStar.setImageResource(bookmarks.contains(currentUrl) ? R.drawable.ic_star : R.drawable.ic_star_border);
    }

    private void toggleMode() {
        desktopMode = !desktopMode;
        prefs.edit().putBoolean("desktop", desktopMode).apply();
        session.getSettings().setUserAgentMode(desktopMode ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
        session.getSettings().setViewportMode(desktopMode ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        updateModeIcon();
        toast(getString(desktopMode ? R.string.mode_desktop : R.string.mode_mobile));
        if (isWeb(currentUrl)) { markUserIntent(); session.reload(); }
    }

    private void updateModeIcon() { btnMode.setImageResource(desktopMode ? R.drawable.ic_desktop : R.drawable.ic_mobile); }

    // ---------------------------------------------------------------- updates

    private final Updater.Callback updateCallback = new Updater.Callback() {
        @Override
        public void onAvailable(Updater.Release r) {
            pendingRelease = r;
            updateBanner.setText(getString(R.string.update_available, r.name));
            updateBanner.setVisibility(View.VISIBLE);
        }

        @Override
        public void onReady(File apk) {
            readyApk = apk;
            if (!updater.install(apk)) toast(getString(R.string.update_allow_install));
        }

        @Override
        public void onError(String message) {
            updateBanner.setEnabled(true);
            toast(getString(R.string.update_failed, message));
        }
    };

    private void onUpdateClicked() {
        if (readyApk != null && readyApk.exists()) {
            if (!updater.install(readyApk)) toast(getString(R.string.update_allow_install));
            return;
        }
        if (pendingRelease == null) return;
        updateBanner.setEnabled(false);
        toast(getString(R.string.update_downloading));
        updater.download(pendingRelease, updateCallback);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        if (session != null) session.setActive(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (session != null) session.setActive(false);
        cursor.stopAll();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && session != null) {
            gecko.releaseSession();
            session.close();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- helpers

    private void showVersion() {
        int st = Engine.adblockState;
        String ub;
        switch (st) {
            case Engine.AB_ON: ub = "uBlock Origin Lite включён"; break;
            case Engine.AB_OFF: ub = "блокировщик рекламы выключен (щит вверху)"; break;
            case Engine.AB_FAILED: ub = "⚠ uBlock Origin Lite не загрузился — реклама не блокируется"; break;
            default: ub = "uBlock Origin Lite загружается…";
        }
        version.setText("tveye " + BuildConfig.VERSION_NAME + " · " + ub);
        version.setTextColor(st == Engine.AB_FAILED ? 0xFFFFB74D : 0x40FFFFFF);
        btnShield.setImageResource(Engine.adblockWanted(this) ? R.drawable.ic_shield : R.drawable.ic_shield_off);
    }

    private void toggleAdblock() {
        boolean on = !Engine.adblockWanted(this);
        Engine.setAdblock(this, on);
        showVersion();
        toast(on ? "Блокировка рекламы включена. Первый сайт после запуска будет открываться дольше"
                 : "Блокировка рекламы выключена. Защита от слежки и всплывающих окон остаётся");
    }

    private void markUserIntent() { lastUserInputMs = SystemClock.uptimeMillis(); }

    private boolean userActedRecently() { return SystemClock.uptimeMillis() - lastUserInputMs < GESTURE_WINDOW_MS; }

    private boolean keyboardOpen() {
        Rect r = new Rect();
        root.getWindowVisibleDisplayFrame(r);
        return root.getRootView().getHeight() - r.height() > dp(120);
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f == null) f = root;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        if (f instanceof EditText) f.clearFocus();
    }

    private static View viewAt(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE) return null;
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.getWidth() || y >= loc[1] + v.getHeight()) return null;
        if (v instanceof CursorView) return null;
        if (v instanceof ViewGroup && !(v instanceof GeckoView)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View hit = viewAt(g.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return v;
    }

    private static boolean isInside(View parent, View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == parent) return true;
        return false;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static boolean isWeb(String u) { return u != null && (u.startsWith("https://") || u.startsWith("http://")); }

    private static String scheme(String u) {
        if (u == null) return "";
        int i = u.indexOf(':');
        return i <= 0 ? "" : u.substring(0, i).toLowerCase(Locale.ROOT);
    }

    private static String host(String u) {
        try {
            String h = Uri.parse(u).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** Registrable domain, roughly: example.com, example.co.uk. */
    private static String site(String u) {
        String h = host(u);
        if (h.startsWith("www.")) h = h.substring(4);
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        String sl = p[p.length - 2];
        boolean twoLevel = p[p.length - 1].length() == 2
                && (sl.equals("co") || sl.equals("com") || sl.equals("net") || sl.equals("org") || sl.equals("ac") || sl.equals("gov") || sl.equals("edu"));
        int keep = twoLevel ? 3 : 2;
        StringBuilder b = new StringBuilder();
        for (int i = p.length - keep; i < p.length; i++) { if (b.length() > 0) b.append('.'); b.append(p[i]); }
        return b.toString();
    }

    private static boolean sameSite(String a, String b) {
        if (!isWeb(a) || !isWeb(b)) return false;
        String sa = site(a);
        return !sa.isEmpty() && sa.equals(site(b));
    }

    private String errorPage(String uri, WebRequestError e) {
        String safe = uri == null ? "" : uri.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                + "<body style='background:#000;color:#ddd;font:22px sans-serif;display:flex;align-items:center;justify-content:center;height:90vh;text-align:center'>"
                + "<div><div style='font-size:34px;color:#fff;margin-bottom:16px'>" + getString(R.string.load_error) + "</div>"
                + "<div style='opacity:.6;font-size:18px;word-break:break-all'>" + safe + "</div>"
                + "<div style='opacity:.4;font-size:15px;margin-top:12px'>код " + e.code + "</div></div>";
        return "data:text/html;base64," + Base64.encodeToString(html.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
}
