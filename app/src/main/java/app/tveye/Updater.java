package app.tveye;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Self-update from this app's own GitHub releases. A release is accepted only if
 * (1) its APK matches the SHA-256 digest GitHub publishes for the asset, (2) the APK is this package
 * with a higher version, and (3) it is signed with the same certificate as the installed app.
 * Android checks the signature once more and the person confirms in the system installer.
 */
final class Updater {

    interface Callback {
        void onAvailable(Release r);
        void onReady(File apk);
        void onError(String message);
    }

    static final class Release {
        final int versionCode;
        final String name, apkUrl, sha256;
        Release(int versionCode, String name, String apkUrl, String sha256) {
            this.versionCode = versionCode; this.name = name; this.apkUrl = apkUrl; this.sha256 = sha256;
        }
    }

    private static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    Updater(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("tveye", Context.MODE_PRIVATE);
    }

    static boolean enabled() { return !BuildConfig.UPDATE_REPO.isEmpty(); }

    void check(boolean force, Callback cb) {
        if (!enabled()) return;
        long last = prefs.getLong("update_check", 0);
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            Release cached = cachedRelease();
            if (cached != null && cached.versionCode > BuildConfig.VERSION_CODE) cb.onAvailable(cached);
            return;
        }
        new Thread(() -> {
            try {
                String json = getText("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
                JSONObject o = new JSONObject(json);
                int code = Integer.parseInt(o.getString("tag_name").replaceAll("[^0-9]", ""));
                String apkUrl = null, sha = null;
                JSONArray assets = o.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url");
                        String d = a.optString("digest", "");
                        if (d.startsWith("sha256:")) sha = d.substring(7).toLowerCase();
                    }
                }
                String prefix = "https://github.com/" + BuildConfig.UPDATE_REPO + "/releases/download/";
                if (apkUrl == null || sha == null || !apkUrl.startsWith(prefix)) throw new Exception("release has no verifiable APK");
                prefs.edit().putLong("update_check", System.currentTimeMillis())
                        .putInt("u_code", code).putString("u_name", o.optString("name", "v" + code))
                        .putString("u_url", apkUrl).putString("u_sha", sha).apply();
                Release r = new Release(code, o.optString("name", "v" + code), apkUrl, sha);
                if (code > BuildConfig.VERSION_CODE) main.post(() -> cb.onAvailable(r));
            } catch (Exception e) {
                // Offline or no release yet: stay quiet, try again later.
            }
        }, "tveye-update-check").start();
    }

    private Release cachedRelease() {
        int code = prefs.getInt("u_code", 0);
        String url = prefs.getString("u_url", null), sha = prefs.getString("u_sha", null);
        if (code == 0 || url == null || sha == null) return null;
        return new Release(code, prefs.getString("u_name", "v" + code), url, sha);
    }

    void download(Release r, Callback cb) {
        new Thread(() -> {
            try {
                File dir = new File(ctx.getCacheDir(), "updates");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("no cache dir");
                for (File old : dir.listFiles()) //noinspection ResultOfMethodCallIgnored
                    old.delete();
                File apk = new File(dir, "tveye-" + r.versionCode + ".apk");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                HttpURLConnection c = open(r.apkUrl);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); }
                } finally { c.disconnect(); }
                if (!hex(md.digest()).equals(r.sha256)) { apk.delete(); throw new Exception("checksum mismatch"); }
                verifyArchive(apk, r.versionCode);
                main.post(() -> cb.onReady(apk));
            } catch (Exception e) {
                android.util.Log.w("tveye", "update rejected: " + e);
                // The release we knew may be gone or replaced: ask GitHub again next time instead of trusting the cache.
                prefs.edit().remove("update_check").remove("u_code").apply();
                main.post(() -> cb.onError(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }, "tveye-update-download").start();
    }

    /** True if the system installer was opened; false if the person must allow installs first. */
    boolean install(File apk) {
        PackageManager pm = ctx.getPackageManager();
        if (!pm.canRequestPackageInstalls()) {
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(s);
            return false;
        }
        Uri uri = FileProvider.getUriForFile(ctx, "app.tveye.updates", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    @SuppressWarnings("deprecation")
    private void verifyArchive(File apk, int expectedCode) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        // Ask for both: Android 9 leaves signingInfo empty for an APK that is not installed yet.
        int flags = PackageManager.GET_SIGNATURES | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : 0);
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), flags);
        PackageInfo ours = pm.getPackageInfo(ctx.getPackageName(), flags);
        if (theirs == null) throw new Exception("not an APK");
        if (!ctx.getPackageName().equals(theirs.packageName)) throw new Exception("wrong package " + theirs.packageName);
        if (theirs.versionCode < expectedCode || theirs.versionCode <= BuildConfig.VERSION_CODE) throw new Exception("not newer");
        Signature[] a = signers(theirs), b = signers(ours);
        if (a == null || a.length == 0) throw new Exception("APK carries no readable signature");
        if (!Arrays.equals(a, b)) throw new Exception("signature differs");
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) {
            Signature[] s = p.signingInfo.getApkContentsSigners();
            if (s != null && s.length > 0) return s;
        }
        return p.signatures;
    }

    private static HttpURLConnection open(String url) throws Exception {
        if (!url.startsWith("https://")) throw new Exception("https only");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "tveye-updater");
        c.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
        if (c.getResponseCode() / 100 != 2) throw new Exception("HTTP " + c.getResponseCode());
        if (!c.getURL().getProtocol().equals("https")) throw new Exception("redirected off https");
        return c;
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format("%02x", b));
        return s.toString();
    }
}
