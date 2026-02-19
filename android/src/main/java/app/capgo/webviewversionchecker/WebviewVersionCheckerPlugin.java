package app.capgo.webviewversionchecker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.webkit.WebViewCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginConfig;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(name = "WebviewVersionChecker")
public class WebviewVersionCheckerPlugin extends Plugin {

    private static final String DEFAULT_ANDROID_LATEST_VERSION_API_URL =
        "https://versionhistory.googleapis.com/v1/chrome/platforms/android/channels/stable/versions?page_size=1";
    private static final String DEFAULT_PROMPT_TITLE = "WebView update available";
    private static final String DEFAULT_PROMPT_UPDATE_BUTTON = "Update";
    private static final String DEFAULT_PROMPT_CANCEL_BUTTON = "Later";
    private static final long LATEST_VERSION_CACHE_TTL_MS = 6L * 60L * 60L * 1000L;

    private static final ThreadLocal<SimpleDateFormat> ISO_FORMAT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf;
    });

    private final WebviewVersionChecker versionChecker = new WebviewVersionChecker();

    private RuntimeOptions runtimeOptions = new RuntimeOptions();
    private boolean monitoringEnabled;
    private boolean checkOnResume = true;

    @Nullable
    private volatile JSObject lastStatus;

    @Nullable
    private String lastPromptFingerprint;

    @Nullable
    private String cachedLatestVersion;

    @Nullable
    private String cachedLatestVersionUrl;

    private long cachedLatestVersionTimestamp;

    @Override
    public void load() {
        super.load();
        runtimeOptions = RuntimeOptions.fromConfig(getConfig());
        monitoringEnabled = runtimeOptions.autoCheckOnResume;
        checkOnResume = runtimeOptions.autoCheckOnResume;

        if (runtimeOptions.autoCheckOnLoad) {
            scheduleCheck("load", runtimeOptions, null, false);
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (!monitoringEnabled || !checkOnResume) {
            return;
        }
        scheduleCheck("resume", runtimeOptions, null, false);
    }

    @PluginMethod
    public void check(PluginCall call) {
        RuntimeOptions merged = runtimeOptions.merge(call);
        String source = call.getString("source", "manual");
        scheduleCheck(source, merged, call, merged.showPromptOnOutdated);
    }

    @PluginMethod
    public void startMonitoring(PluginCall call) {
        runtimeOptions = runtimeOptions.merge(call);
        monitoringEnabled = true;
        checkOnResume = call.getBoolean("checkOnResume", runtimeOptions.autoCheckOnResume);

        if (call.getBoolean("checkOnStart", true)) {
            scheduleCheck("startMonitoring", runtimeOptions, null, runtimeOptions.showPromptOnOutdated);
        }

        call.resolve(buildMonitoringStatePayload());
    }

    @PluginMethod
    public void stopMonitoring(PluginCall call) {
        monitoringEnabled = false;
        call.resolve(buildMonitoringStatePayload());
    }

    @PluginMethod
    public void getLastStatus(PluginCall call) {
        JSObject payload = new JSObject();
        payload.put("status", lastStatus != null ? lastStatus : JSONObject.NULL);
        call.resolve(payload);
    }

    @PluginMethod
    public void showUpdatePrompt(PluginCall call) {
        PromptOptions promptOptions = PromptOptions.fromCall(call, runtimeOptions);
        showUpdatePrompt(promptOptions, true, (shown, opened) -> {
            JSObject payload = new JSObject();
            payload.put("shown", shown);
            payload.put("openedUpdatePage", opened);
            call.resolve(payload);
        });
    }

    @PluginMethod
    public void openUpdatePage(PluginCall call) {
        String url = resolveUpdateUrl(call.getString("updateUrl"), lastStatus);
        if (isBlank(url)) {
            JSObject payload = new JSObject();
            payload.put("opened", false);
            payload.put("url", "");
            call.resolve(payload);
            return;
        }

        bridge.executeOnMainThread(() -> {
            boolean opened = openUpdateUrl(url);
            JSObject payload = new JSObject();
            payload.put("opened", opened);
            payload.put("url", url);
            call.resolve(payload);
        });
    }

    private void scheduleCheck(String source, RuntimeOptions options, @Nullable PluginCall call, boolean forcePrompt) {
        execute(() -> {
            JSObject status = evaluateStatus(source, options);
            lastStatus = status;

            notifyStatus(status);
            maybeAutoPrompt(status, options, forcePrompt);

            if (call != null) {
                bridge.executeOnMainThread(() -> call.resolve(status));
            }
        });
    }

    private JSObject evaluateStatus(String source, RuntimeOptions options) {
        CurrentWebViewInfo current = resolveCurrentWebViewInfo();
        LatestVersionResolution latestResolution = resolveLatestVersion(options);

        JSObject status = new JSObject();
        status.put("platform", "android");
        status.put("checkedAt", isoNow());
        status.put("source", source);
        status.put("providerPackage", current.packageName);
        status.put("currentVersion", current.versionName);

        Integer currentMajorVersion = versionChecker.parseMajorVersion(current.versionName);
        if (currentMajorVersion != null) {
            status.put("currentMajorVersion", currentMajorVersion);
        }

        String latestVersion = latestResolution.version;
        if (!isBlank(latestVersion)) {
            status.put("latestVersion", latestVersion);
            Integer latestMajorVersion = versionChecker.parseMajorVersion(latestVersion);
            if (latestMajorVersion != null) {
                status.put("latestMajorVersion", latestMajorVersion);
            }
        }

        String updateUrl = resolveUpdateUrl(options.updateUrl, status);
        if (!isBlank(updateUrl)) {
            status.put("updateUrl", updateUrl);
        }

        if (isBlank(current.versionName)) {
            status.put("state", "unknown");
            status.put("isLatest", false);
            status.put("reason", "Could not detect the active Android WebView package version.");
            return status;
        }

        if (!isBlank(latestVersion)) {
            int cmp = versionChecker.compareVersions(current.versionName, latestVersion);
            boolean isLatest = cmp >= 0;
            status.put("state", isLatest ? "latest" : "outdated");
            status.put("isLatest", isLatest);
            status.put(
                "reason",
                isLatest
                    ? "Installed WebView version is at or above the latest resolved version."
                    : "Installed WebView version is behind the latest resolved version."
            );
            return status;
        }

        Integer minimumMajorVersion = options.minimumMajorVersion;
        if (minimumMajorVersion != null && currentMajorVersion != null) {
            boolean isLatest = currentMajorVersion >= minimumMajorVersion;
            status.put("state", isLatest ? "latest" : "outdated");
            status.put("isLatest", isLatest);
            status.put(
                "reason",
                isLatest
                    ? "Installed WebView major version satisfies the minimum configured major version."
                    : "Installed WebView major version is below the configured minimum major version."
            );
            return status;
        }

        status.put("state", "unknown");
        status.put("isLatest", false);
        if (!isBlank(latestResolution.errorMessage)) {
            status.put("reason", latestResolution.errorMessage);
        } else {
            status.put("reason", "Unable to resolve latest WebView version. Configure latestVersion or minimumMajorVersion.");
        }

        return status;
    }

    private void notifyStatus(JSObject status) {
        bridge.executeOnMainThread(() -> {
            notifyListeners("statusChanged", status);
            String state = status.optString("state", "unknown");
            if ("latest".equals(state)) {
                notifyListeners("webViewLatest", status);
            } else if ("outdated".equals(state)) {
                notifyListeners("webViewOutdated", status);
            }
        });
    }

    private void maybeAutoPrompt(JSObject status, RuntimeOptions options, boolean forcePrompt) {
        String state = status.optString("state", "unknown");
        boolean shouldPrompt = "outdated".equals(state) && (forcePrompt || options.autoPromptOnOutdated);
        if (!shouldPrompt) {
            return;
        }

        PromptOptions promptOptions = PromptOptions.fromRuntime(options);
        showUpdatePrompt(promptOptions, forcePrompt, null);
    }

    private void showUpdatePrompt(PromptOptions promptOptions, boolean force, @Nullable PromptResultCallback callback) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) {
            if (callback != null) {
                callback.onResult(false, false);
            }
            return;
        }

        String updateUrl = resolveUpdateUrl(promptOptions.updateUrl, lastStatus);
        if (isBlank(updateUrl)) {
            if (callback != null) {
                callback.onResult(false, false);
            }
            return;
        }

        String fingerprint = buildPromptFingerprint(lastStatus, updateUrl);
        if (!force && !isBlank(fingerprint) && fingerprint.equals(lastPromptFingerprint)) {
            if (callback != null) {
                callback.onResult(false, false);
            }
            return;
        }

        if (!isBlank(fingerprint)) {
            lastPromptFingerprint = fingerprint;
        }

        String title = !isBlank(promptOptions.title) ? promptOptions.title : DEFAULT_PROMPT_TITLE;
        String message = !isBlank(promptOptions.message) ? promptOptions.message : buildDefaultPromptMessage(lastStatus);
        String updateButtonText = !isBlank(promptOptions.updateButtonText) ? promptOptions.updateButtonText : DEFAULT_PROMPT_UPDATE_BUTTON;
        String cancelButtonText = !isBlank(promptOptions.cancelButtonText) ? promptOptions.cancelButtonText : DEFAULT_PROMPT_CANCEL_BUTTON;

        bridge.executeOnMainThread(() -> {
            final boolean[] resolved = { false };
            AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(updateButtonText, (dialogInterface, which) -> {
                    boolean opened = openUpdateUrl(updateUrl);
                    if (callback != null && !resolved[0]) {
                        resolved[0] = true;
                        callback.onResult(true, opened);
                    }
                })
                .setNegativeButton(cancelButtonText, (dialogInterface, which) -> {
                    if (callback != null && !resolved[0]) {
                        resolved[0] = true;
                        callback.onResult(true, false);
                    }
                })
                .setOnCancelListener(dialogInterface -> {
                    if (callback != null && !resolved[0]) {
                        resolved[0] = true;
                        callback.onResult(true, false);
                    }
                })
                .create();
            dialog.show();
        });
    }

    private JSObject buildMonitoringStatePayload() {
        JSObject payload = new JSObject();
        payload.put("monitoring", monitoringEnabled);
        payload.put("checkOnResume", checkOnResume);
        payload.put("autoPromptOnOutdated", runtimeOptions.autoPromptOnOutdated);
        return payload;
    }

    private CurrentWebViewInfo resolveCurrentWebViewInfo() {
        try {
            PackageInfo packageInfo = WebViewCompat.getCurrentWebViewPackage(getContext());
            if (packageInfo != null) {
                return new CurrentWebViewInfo(packageInfo.packageName, packageInfo.versionName);
            }
        } catch (Throwable ignored) {
            // Fall through to platform APIs.
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PackageInfo packageInfo = WebView.getCurrentWebViewPackage();
                if (packageInfo != null) {
                    return new CurrentWebViewInfo(packageInfo.packageName, packageInfo.versionName);
                }
            } catch (Throwable ignored) {
                // Fall through to unknown state.
            }
        }

        return new CurrentWebViewInfo("", "");
    }

    private LatestVersionResolution resolveLatestVersion(RuntimeOptions options) {
        if (!isBlank(options.latestVersion)) {
            return new LatestVersionResolution(options.latestVersion, null);
        }

        String apiUrl = !isBlank(options.latestVersionApiUrl) ? options.latestVersionApiUrl : DEFAULT_ANDROID_LATEST_VERSION_API_URL;

        if (
            !isBlank(cachedLatestVersion) &&
            !isBlank(cachedLatestVersionUrl) &&
            apiUrl.equals(cachedLatestVersionUrl) &&
            (System.currentTimeMillis() - cachedLatestVersionTimestamp) < LATEST_VERSION_CACHE_TTL_MS
        ) {
            return new LatestVersionResolution(cachedLatestVersion, null);
        }

        try {
            String payload = readUrl(apiUrl);
            String version = extractLatestVersion(payload);
            if (isBlank(version)) {
                return new LatestVersionResolution(null, "Latest version API returned no version string.");
            }

            cachedLatestVersion = version;
            cachedLatestVersionUrl = apiUrl;
            cachedLatestVersionTimestamp = System.currentTimeMillis();

            return new LatestVersionResolution(version, null);
        } catch (Exception error) {
            return new LatestVersionResolution(null, "Could not resolve latest WebView version: " + error.getMessage());
        }
    }

    private String readUrl(String urlValue) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");

        try {
            InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                throw new IOException("No response body.");
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private String extractLatestVersion(String payload) throws Exception {
        JSONObject json = new JSONObject(payload);

        String version = normalizeVersion(json.optString("version", null));
        if (!isBlank(version)) {
            return version;
        }

        version = normalizeVersion(json.optString("latestVersion", null));
        if (!isBlank(version)) {
            return version;
        }

        JSONArray versions = json.optJSONArray("versions");
        if (versions != null && versions.length() > 0) {
            JSONObject first = versions.optJSONObject(0);
            if (first != null) {
                version = normalizeVersion(first.optString("version", null));
                if (!isBlank(version)) {
                    return version;
                }

                String name = first.optString("name", null);
                if (!isBlank(name)) {
                    int slash = name.lastIndexOf('/');
                    if (slash >= 0 && slash + 1 < name.length()) {
                        version = normalizeVersion(name.substring(slash + 1));
                        if (!isBlank(version)) {
                            return version;
                        }
                    }
                }
            }
        }

        return null;
    }

    @Nullable
    private String normalizeVersion(@Nullable String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private String resolveUpdateUrl(@Nullable String explicitUrl, @Nullable JSObject status) {
        if (!isBlank(explicitUrl)) {
            return explicitUrl;
        }

        if (status != null) {
            String statusUrl = status.optString("updateUrl", null);
            if (!isBlank(statusUrl)) {
                return statusUrl;
            }

            String providerPackage = status.optString("providerPackage", defaultProviderPackageForSdk());
            return buildStoreUrl(providerPackage);
        }

        return buildStoreUrl(defaultProviderPackageForSdk());
    }

    private String buildStoreUrl(@Nullable String packageName) {
        String resolvedPackage = isBlank(packageName) ? defaultProviderPackageForSdk() : packageName;
        return "market://details?id=" + resolvedPackage;
    }

    private String defaultProviderPackageForSdk() {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= Build.VERSION_CODES.N && sdk <= Build.VERSION_CODES.P) {
            return "com.android.chrome";
        }
        return "com.google.android.webview";
    }

    private boolean openUpdateUrl(String url) {
        Activity activity = getActivity();
        if (activity == null) {
            return false;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException error) {
            if (url.startsWith("market://")) {
                Uri uri = Uri.parse(url);
                String packageName = uri.getQueryParameter("id");
                if (!isBlank(packageName)) {
                    String fallbackUrl = "https://play.google.com/store/apps/details?id=" + packageName;
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                        return true;
                    } catch (ActivityNotFoundException ignored) {
                        return false;
                    }
                }
            }
            return false;
        }
    }

    private String buildDefaultPromptMessage(@Nullable JSObject status) {
        if (status == null) {
            return "A newer WebView version is available. Please update for best compatibility.";
        }

        String current = status.optString("currentVersion", "current version");
        String latest = status.optString("latestVersion", "the latest available version");
        return "Your WebView version (" + current + ") is outdated. Please update to " + latest + ".";
    }

    private String buildPromptFingerprint(@Nullable JSObject status, String updateUrl) {
        if (status == null) {
            return updateUrl;
        }

        String current = status.optString("currentVersion", "");
        String latest = status.optString("latestVersion", "");
        String provider = status.optString("providerPackage", "");
        return current + "|" + latest + "|" + provider + "|" + updateUrl;
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private String isoNow() {
        return ISO_FORMAT.get().format(new Date());
    }

    private interface PromptResultCallback {
        void onResult(boolean shown, boolean openedUpdatePage);
    }

    private static final class CurrentWebViewInfo {

        private final String packageName;
        private final String versionName;

        private CurrentWebViewInfo(String packageName, String versionName) {
            this.packageName = packageName != null ? packageName : "";
            this.versionName = versionName != null ? versionName : "";
        }
    }

    private static final class LatestVersionResolution {

        @Nullable
        private final String version;

        @Nullable
        private final String errorMessage;

        private LatestVersionResolution(@Nullable String version, @Nullable String errorMessage) {
            this.version = version;
            this.errorMessage = errorMessage;
        }
    }

    private static final class PromptOptions {

        @Nullable
        private String title;

        @Nullable
        private String message;

        @Nullable
        private String updateButtonText;

        @Nullable
        private String cancelButtonText;

        @Nullable
        private String updateUrl;

        static PromptOptions fromRuntime(RuntimeOptions options) {
            PromptOptions out = new PromptOptions();
            out.title = options.promptTitle;
            out.message = options.promptMessage;
            out.updateButtonText = options.promptUpdateButtonText;
            out.cancelButtonText = options.promptCancelButtonText;
            out.updateUrl = options.updateUrl;
            return out;
        }

        static PromptOptions fromCall(PluginCall call, RuntimeOptions defaults) {
            PromptOptions out = fromRuntime(defaults);
            out.title = emptyToNull(call.getString("title", out.title));
            out.message = emptyToNull(call.getString("message", out.message));
            out.updateButtonText = emptyToNull(call.getString("updateButtonText", out.updateButtonText));
            out.cancelButtonText = emptyToNull(call.getString("cancelButtonText", out.cancelButtonText));
            out.updateUrl = emptyToNull(call.getString("updateUrl", out.updateUrl));
            return out;
        }

        @Nullable
        private static String emptyToNull(@Nullable String value) {
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            return value;
        }
    }

    private static final class RuntimeOptions {

        private boolean autoCheckOnLoad = true;
        private boolean autoCheckOnResume = true;
        private boolean autoPromptOnOutdated;
        private boolean showPromptOnOutdated;

        @Nullable
        private String latestVersion;

        @Nullable
        private Integer minimumMajorVersion;

        @Nullable
        private String latestVersionApiUrl;

        @Nullable
        private String updateUrl;

        @Nullable
        private String promptTitle;

        @Nullable
        private String promptMessage;

        @Nullable
        private String promptUpdateButtonText;

        @Nullable
        private String promptCancelButtonText;

        static RuntimeOptions fromConfig(PluginConfig config) {
            RuntimeOptions out = new RuntimeOptions();
            out.autoCheckOnLoad = config.getBoolean("autoCheckOnLoad", true);
            out.autoCheckOnResume = config.getBoolean("autoCheckOnResume", true);
            out.autoPromptOnOutdated = config.getBoolean("autoPromptOnOutdated", false);
            out.latestVersion = emptyToNull(config.getString("latestVersion"));
            Integer minimum = config.getConfigJSON().has("minimumMajorVersion")
                ? config.getInt("minimumMajorVersion", 0)
                : null;
            out.minimumMajorVersion = minimum;
            out.latestVersionApiUrl = emptyToNull(config.getString("latestVersionApiUrl"));
            out.updateUrl = emptyToNull(config.getString("updateUrl"));
            out.promptTitle = emptyToNull(config.getString("promptTitle"));
            out.promptMessage = emptyToNull(config.getString("promptMessage"));
            out.promptUpdateButtonText = emptyToNull(config.getString("promptUpdateButtonText"));
            out.promptCancelButtonText = emptyToNull(config.getString("promptCancelButtonText"));
            return out;
        }

        RuntimeOptions merge(PluginCall call) {
            RuntimeOptions out = copy();

            if (call.hasOption("autoCheckOnLoad")) {
                out.autoCheckOnLoad = call.getBoolean("autoCheckOnLoad", out.autoCheckOnLoad);
            }
            if (call.hasOption("autoCheckOnResume")) {
                out.autoCheckOnResume = call.getBoolean("autoCheckOnResume", out.autoCheckOnResume);
            }
            if (call.hasOption("autoPromptOnOutdated")) {
                out.autoPromptOnOutdated = call.getBoolean("autoPromptOnOutdated", out.autoPromptOnOutdated);
            }
            if (call.hasOption("showPromptOnOutdated")) {
                out.showPromptOnOutdated = call.getBoolean("showPromptOnOutdated", out.showPromptOnOutdated);
            }
            if (call.hasOption("latestVersion")) {
                out.latestVersion = emptyToNull(call.getString("latestVersion"));
            }
            if (call.hasOption("minimumMajorVersion")) {
                out.minimumMajorVersion = call.getInt("minimumMajorVersion");
            }
            if (call.hasOption("latestVersionApiUrl")) {
                out.latestVersionApiUrl = emptyToNull(call.getString("latestVersionApiUrl"));
            }
            if (call.hasOption("updateUrl")) {
                out.updateUrl = emptyToNull(call.getString("updateUrl"));
            }
            if (call.hasOption("promptTitle")) {
                out.promptTitle = emptyToNull(call.getString("promptTitle"));
            }
            if (call.hasOption("promptMessage")) {
                out.promptMessage = emptyToNull(call.getString("promptMessage"));
            }
            if (call.hasOption("promptUpdateButtonText")) {
                out.promptUpdateButtonText = emptyToNull(call.getString("promptUpdateButtonText"));
            }
            if (call.hasOption("promptCancelButtonText")) {
                out.promptCancelButtonText = emptyToNull(call.getString("promptCancelButtonText"));
            }

            return out;
        }

        private RuntimeOptions copy() {
            RuntimeOptions out = new RuntimeOptions();
            out.autoCheckOnLoad = autoCheckOnLoad;
            out.autoCheckOnResume = autoCheckOnResume;
            out.autoPromptOnOutdated = autoPromptOnOutdated;
            out.showPromptOnOutdated = showPromptOnOutdated;
            out.latestVersion = latestVersion;
            out.minimumMajorVersion = minimumMajorVersion;
            out.latestVersionApiUrl = latestVersionApiUrl;
            out.updateUrl = updateUrl;
            out.promptTitle = promptTitle;
            out.promptMessage = promptMessage;
            out.promptUpdateButtonText = promptUpdateButtonText;
            out.promptCancelButtonText = promptCancelButtonText;
            return out;
        }

        @Nullable
        private static String emptyToNull(@Nullable String value) {
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            return value;
        }
    }
}
