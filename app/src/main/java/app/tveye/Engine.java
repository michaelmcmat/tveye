package app.tveye;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

/** One Gecko runtime per process (main process only: it is created from the activity, never from Application). */
final class Engine {
    private static final String TAG = "tveye";
    private static final String UBLOCK_ID = "uBOLiteRedux@raymondhill.net";
    private static GeckoRuntime runtime;

    /** State of the ad blocker as the engine really has it. */
    static final int AB_LOADING = 0, AB_ON = 1, AB_OFF = 2, AB_FAILED = 3;
    static volatile int adblockState = AB_LOADING;
    static volatile Runnable onUblockState;
    private static WebExtension blocker;

    private Engine() { }

    private static void setState(int s) {
        adblockState = s;
        Runnable r = onUblockState;
        if (r != null) r.run();
    }

    /** The person's choice; on by default. Off makes the first page after launch much faster on a slow TV. */
    static boolean adblockWanted(Context ctx) {
        return ctx.getSharedPreferences("tveye", Context.MODE_PRIVATE).getBoolean("adblock", true);
    }

    static void setAdblock(Context ctx, boolean on) {
        ctx.getSharedPreferences("tveye", Context.MODE_PRIVATE).edit().putBoolean("adblock", on).apply();
        if (runtime == null || blocker == null) return;
        WebExtensionController wec = runtime.getWebExtensionController();
        setState(AB_LOADING);
        if (on) {
            wec.enable(blocker, WebExtensionController.EnableSource.APP).accept(
                    e -> { blocker = e; allowPrivate(wec, e); },
                    t -> { Log.w(TAG, "enable failed", t); setState(AB_FAILED); });
        } else {
            wec.disable(blocker, WebExtensionController.EnableSource.APP).accept(
                    e -> { blocker = e; setState(AB_OFF); },
                    t -> { Log.w(TAG, "disable failed", t); setState(AB_FAILED); });
        }
    }

    private static void allowPrivate(WebExtensionController wec, WebExtension ext) {
        wec.setAllowedInPrivateBrowsing(ext, true).accept(
                e -> { Log.i(TAG, "uBlock Origin Lite active"); setState(AB_ON); },
                t -> { Log.w(TAG, "uBlock Origin Lite private mode failed", t); setState(AB_FAILED); });
    }

    static synchronized GeckoRuntime get(Context ctx) {
        if (runtime != null) return runtime;
        ContentBlocking.Settings blocking = new ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.STRICT)
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .strictSocialTrackingProtection(true)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .cookiePurging(true)
                .queryParameterStrippingPrivateBrowsingEnabled(true)
                .bounceTrackingProtectionMode(ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED)
                .build();
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .configFilePath(writeConfig(ctx))
                .contentBlocking(blocking)
                .consoleOutput(false)
                .remoteDebuggingEnabled(false)
                .aboutConfigEnabled(false)
                .javaScriptEnabled(true)
                .webManifest(false)
                .loginAutofillEnabled(false)
                .inputAutoZoomEnabled(false)
                .doubleTapZoomingEnabled(false)
                .globalPrivacyControlEnabled(true)
                .extensionsWebAPIEnabled(false)
                // HTTPS only; a site without it gets Firefox's "continue anyway" page instead of plain HTTP.
                .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
                // Pages may not reach devices on the home network (router, this TV, other boxes).
                .setLnaEnabled(true)
                .setLnaBlocking(true)
                .setLnaBlockTrackers(true)
                // The TV has 1.4 GB of RAM: let Gecko react to memory pressure.
                .lowMemoryDetection(true)
                .build();
        runtime = GeckoRuntime.create(ctx.getApplicationContext(), settings);
        installUblock(runtime, adblockWanted(ctx));
        return runtime;
    }

    /**
     * Gecko preferences. The slow-script watchdog is off: on this TV's CPU uBlock's first filter compile takes
     * longer than the 10 s limit, and GeckoView cannot show the "stop script?" prompt for an extension, so the
     * watchdog kills the compile and uBlock keeps the network blocked. Home and Back stay responsive regardless,
     * since pages run in their own process.
     */
    private static String writeConfig(Context ctx) {
        String yaml = "prefs:\n"
                + "  dom.max_script_run_time: 0\n"
                + "  dom.max_chrome_script_run_time: 0\n"
                + "  dom.max_ext_content_script_run_time: 0\n";
        File f = new File(ctx.getFilesDir(), "geckoview-config.yaml");
        try {
            byte[] want = yaml.getBytes(StandardCharsets.UTF_8);
            if (!f.exists() || f.length() != want.length) {
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(want); }
            }
        } catch (IOException e) {
            Log.w(TAG, "could not write Gecko config", e);
        }
        return f.getAbsolutePath();
    }

    private static void installUblock(GeckoRuntime rt, boolean wanted) {
        WebExtensionController wec = rt.getWebExtensionController();
        // Only our own blocker may live in the engine: remove anything else (an older bundled blocker included).
        wec.list().accept(list -> {
            if (list == null) return;
            for (WebExtension e : list) {
                if (!UBLOCK_ID.equals(e.id)) {
                    Log.i(TAG, "removing extension " + e.id);
                    wec.uninstall(e);
                }
            }
        }, t -> Log.w(TAG, "could not list extensions", t));
        wec.ensureBuiltIn("resource://android/assets/extensions/ubol/", UBLOCK_ID).accept(
                ext -> {
                    if (ext == null) { setState(AB_FAILED); return; }
                    blocker = ext;
                    boolean enabled = ext.metaData.enabled;
                    if (wanted && !enabled) {
                        wec.enable(ext, WebExtensionController.EnableSource.APP).accept(
                                e -> { blocker = e; allowPrivate(wec, e); },
                                t -> { Log.w(TAG, "enable failed", t); setState(AB_FAILED); });
                    } else if (wanted) {
                        allowPrivate(wec, ext);
                    } else if (enabled) {
                        wec.disable(ext, WebExtensionController.EnableSource.APP).accept(
                                e -> { blocker = e; setState(AB_OFF); },
                                t -> { Log.w(TAG, "disable failed", t); setState(AB_FAILED); });
                    } else {
                        setState(AB_OFF);
                    }
                },
                t -> { Log.w(TAG, "uBlock Origin Lite install failed", t); setState(AB_FAILED); });
    }
}
