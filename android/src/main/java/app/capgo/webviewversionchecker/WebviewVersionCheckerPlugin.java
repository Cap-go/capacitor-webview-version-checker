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
import java.util.Iterator;
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

    @Nullable
    private JSONObject cachedVersionShareByMajor;

    @Nullable
    private String cachedVersionShareApiUrl;

    private long cachedVersionShareTimestamp;

    private boolean isPromptShowing;

    @Nullable
    private AlertDialog activePromptDialog;

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
        showUpdatePrompt(promptOptions, true, runtimeOptions.autoPromptDismissible, (shown, opened) -> {
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
            dismissAutoPromptIfUpToDate(status);
            maybeAutoPrompt(status, options, forcePrompt);

            if (call != null) {
                bridge.executeOnMainThread(() -> call.resolve(status));
            }
        });
    }

    private JSObject evaluateStatus(String source, RuntimeOptions options) {
        CurrentWebViewInfo current = resolveCurrentWebViewInfo();

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

        String deviceShareErrorMessage = null;
        Double currentVersionSharePercent = null;
        if (options.minimumDeviceSharePercent != null) {
            status.put("minimumDeviceSharePercent", options.minimumDeviceSharePercent);
            if (currentMajorVersion == null) {
                deviceShareErrorMessage =
                    "Could not evaluate device-share threshold because the installed WebView major version could not be parsed.";
            } else {
                VersionShareResolution versionShareResolution = resolveVersionShare(options);
                if (!isBlank(versionShareResolution.source)) {
                    status.put("versionShareSource", versionShareResolution.source);
                }

                currentVersionSharePercent = readShareForMajor(versionShareResolution.shareByMajor, currentMajorVersion);
                if (currentVersionSharePercent != null) {
                    status.put("currentVersionSharePercent", currentVersionSharePercent);
                } else if (!isBlank(versionShareResolution.errorMessage)) {
                    deviceShareErrorMessage = versionShareResolution.errorMessage;
                } else {
                    deviceShareErrorMessage =
                        "No version-share data was found for installed WebView major version " + currentMajorVersion + ".";
                }
            }
            if (!isBlank(deviceShareErrorMessage)) {
                status.put("deviceShareError", deviceShareErrorMessage);
            }
        }

        LatestVersionResolution latestResolution = resolveLatestVersion(options);
        String latestVersion = latestResolution.version;
        if (!isBlank(latestVersion)) {
            status.put("latestVersion", latestVersion);
            Integer latestMajorVersion = versionChecker.parseMajorVersion(latestVersion);
            if (latestMajorVersion != null) {
                status.put("latestMajorVersion", latestMajorVersion);
            }
        }

        WebviewVersionChecker.CompatibilityEvaluation evaluation = versionChecker.evaluateCompatibility(
            current.versionName,
            currentMajorVersion,
            latestVersion,
            options.minimumMajorVersion,
            options.minimumDeviceSharePercent,
            currentVersionSharePercent
        );
        if (evaluation != null) {
            status.put("state", evaluation.state);
            status.put("isLatest", evaluation.isLatest);
            status.put("reason", evaluation.reason);
            return status;
        }

        status.put("state", "unknown");
        status.put("isLatest", false);
        if (!isBlank(latestResolution.errorMessage)) {
            status.put("reason", latestResolution.errorMessage);
        } else if (!isBlank(deviceShareErrorMessage)) {
            status.put("reason", deviceShareErrorMessage);
        } else {
            status.put(
                "reason",
                "Unable to resolve compatibility status. Configure latestVersion, minimumMajorVersion, or minimumDeviceSharePercent."
            );
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
        showUpdatePrompt(promptOptions, forcePrompt, options.autoPromptDismissible, null);
    }

    private void showUpdatePrompt(
        PromptOptions promptOptions,
        boolean force,
        boolean dismissible,
        @Nullable PromptResultCallback callback
    ) {
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
        if (dismissible && !force && !isBlank(fingerprint) && fingerprint.equals(lastPromptFingerprint)) {
            if (callback != null) {
                callback.onResult(false, false);
            }
            return;
        }

        if (isPromptShowing) {
            if (callback != null) {
                callback.onResult(false, false);
            }
            return;
        }

        if (dismissible && !isBlank(fingerprint)) {
            lastPromptFingerprint = fingerprint;
        } else {
            lastPromptFingerprint = null;
        }

        String title = !isBlank(promptOptions.title) ? promptOptions.title : DEFAULT_PROMPT_TITLE;
        String message = !isBlank(promptOptions.message) ? promptOptions.message : buildDefaultPromptMessage(lastStatus);
        String updateButtonText = !isBlank(promptOptions.updateButtonText) ? promptOptions.updateButtonText : DEFAULT_PROMPT_UPDATE_BUTTON;
        String cancelButtonText = !isBlank(promptOptions.cancelButtonText) ? promptOptions.cancelButtonText : DEFAULT_PROMPT_CANCEL_BUTTON;

        bridge.executeOnMainThread(() -> {
            final boolean[] resolved = { false };
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(updateButtonText, (dialogInterface, which) -> {
                    boolean opened = openUpdateUrl(updateUrl);
                    if (callback != null && !resolved[0]) {
                        resolved[0] = true;
                        callback.onResult(true, opened);
                    }
                });

            if (dismissible) {
                builder
                    .setNegativeButton(cancelButtonText, (dialogInterface, which) -> {
                        if (callback != null && !resolved[0]) {
                            resolved[0] = true;
                            callback.onResult(true, false);
                        }
                    })
                    .setOnCancelListener((dialogInterface) -> {
                        if (callback != null && !resolved[0]) {
                            resolved[0] = true;
                            callback.onResult(true, false);
                        }
                    });
            }

            AlertDialog dialog = builder.setCancelable(dismissible).create();
            isPromptShowing = true;
            activePromptDialog = dialog;
            dialog.setOnDismissListener((dialogInterface) -> {
                isPromptShowing = false;
                if (activePromptDialog == dialog) {
                    activePromptDialog = null;
                }
            });
            dialog.show();
            if (!dismissible) {
                dialog
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener((view) -> {
                        boolean opened = openUpdateUrl(updateUrl);
                        if (callback != null && !resolved[0]) {
                            resolved[0] = true;
                            callback.onResult(true, opened);
                        }
                    });
            }
        });
    }

    private void dismissAutoPromptIfUpToDate(JSObject status) {
        if (!"latest".equals(status.optString("state", "unknown"))) {
            return;
        }
        AlertDialog dialog = activePromptDialog;
        if (dialog == null) {
            return;
        }

        bridge.executeOnMainThread(() -> {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            if (activePromptDialog == dialog) {
                activePromptDialog = null;
            }
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

    private VersionShareResolution resolveVersionShare(RuntimeOptions options) {
        if (options.versionShareByMajor != null && options.versionShareByMajor.length() > 0) {
            return new VersionShareResolution(copyJsonObject(options.versionShareByMajor), "versionShareByMajor", null);
        }

        if (!isBlank(options.versionShareApiUrl)) {
            String apiUrl = options.versionShareApiUrl;

            if (
                cachedVersionShareByMajor != null &&
                !isBlank(cachedVersionShareApiUrl) &&
                apiUrl.equals(cachedVersionShareApiUrl) &&
                (System.currentTimeMillis() - cachedVersionShareTimestamp) < LATEST_VERSION_CACHE_TTL_MS
            ) {
                return new VersionShareResolution(copyJsonObject(cachedVersionShareByMajor), "versionShareApiUrl", null);
            }

            try {
                String payload = readUrl(apiUrl);
                JSONObject shareByMajor = extractVersionShareByMajor(payload);
                if (shareByMajor == null || shareByMajor.length() == 0) {
                    return new VersionShareResolution(null, "versionShareApiUrl", "Version-share API returned no compatible dataset.");
                }

                cachedVersionShareByMajor = copyJsonObject(shareByMajor);
                cachedVersionShareApiUrl = apiUrl;
                cachedVersionShareTimestamp = System.currentTimeMillis();

                return new VersionShareResolution(shareByMajor, "versionShareApiUrl", null);
            } catch (Exception error) {
                return new VersionShareResolution(
                    null,
                    "versionShareApiUrl",
                    "Could not resolve version-share dataset: " + error.getMessage()
                );
            }
        }

        JSONObject defaultDataset = GeneratedVersionShareData.getDefaultVersionShareByMajor();
        if (defaultDataset != null && defaultDataset.length() > 0) {
            return new VersionShareResolution(defaultDataset, GeneratedVersionShareData.getDataSource(), null);
        }

        return new VersionShareResolution(
            null,
            GeneratedVersionShareData.getDataSource(),
            "Built-in version-share dataset is unavailable."
        );
    }

    @Nullable
    private Double readShareForMajor(@Nullable JSONObject shareByMajor, int majorVersion) {
        if (shareByMajor == null) {
            return null;
        }

        String directKey = String.valueOf(majorVersion);
        if (shareByMajor.has(directKey) && !shareByMajor.isNull(directKey)) {
            return parsePercentFromAny(shareByMajor.opt(directKey));
        }

        Iterator<String> keys = shareByMajor.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Integer mappedMajor = parseMajorFromShareKey(key);
            if (mappedMajor != null && mappedMajor == majorVersion) {
                Double share = parsePercentFromAny(shareByMajor.opt(key));
                if (share != null) {
                    return share;
                }
            }
        }

        return null;
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
    private JSONObject extractVersionShareByMajor(String payload) throws Exception {
        JSONObject json = new JSONObject(payload);
        return extractVersionShareByMajor(json);
    }

    @Nullable
    private JSONObject extractVersionShareByMajor(JSONObject json) {
        JSONObject out = new JSONObject();

        JSONObject explicitMap = json.optJSONObject("versionShareByMajor");
        if (explicitMap != null) {
            mergeVersionShareMap(out, explicitMap);
        }

        if (out.length() == 0) {
            JSONObject secondaryMap = json.optJSONObject("shareByMajor");
            if (secondaryMap != null) {
                mergeVersionShareMap(out, secondaryMap);
            }
        }

        if (out.length() == 0) {
            JSONArray versionsArray = json.optJSONArray("versions");
            if (versionsArray != null) {
                mergeVersionShareArray(out, versionsArray);
            }
        }

        if (out.length() == 0) {
            JSONArray dataArray = json.optJSONArray("data");
            if (dataArray != null) {
                mergeVersionShareArray(out, dataArray);
            }
        }

        if (out.length() == 0) {
            JSONObject caniuseShareByMajor = extractCaniuseVersionShareByMajor(json);
            if (caniuseShareByMajor != null) {
                mergeVersionShareMap(out, caniuseShareByMajor);
            }
        }

        if (out.length() == 0) {
            mergeVersionShareMap(out, json);
        }

        if (out.length() == 0) {
            return null;
        }
        return out;
    }

    @Nullable
    private JSONObject extractCaniuseVersionShareByMajor(JSONObject json) {
        JSONObject agents = json.optJSONObject("agents");
        if (agents == null) {
            return null;
        }

        JSONObject out = new JSONObject();

        // Prefer Android-specific buckets first.
        mergeVersionShareMap(out, getUsageGlobalMap(agents, "and_chr"));
        mergeVersionShareMap(out, getUsageGlobalMap(agents, "android"));

        // If Android-specific buckets are sparse, complete with Chrome global data.
        if (out.length() < 5) {
            mergeVersionShareMap(out, getUsageGlobalMap(agents, "chrome"));
        }

        return out.length() > 0 ? out : null;
    }

    @Nullable
    private JSONObject getUsageGlobalMap(JSONObject agents, String agentKey) {
        JSONObject agent = agents.optJSONObject(agentKey);
        if (agent == null) {
            return null;
        }
        return agent.optJSONObject("usage_global");
    }

    private void mergeVersionShareMap(JSONObject target, JSONObject source) {
        if (source == null) {
            return;
        }
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Integer majorVersion = parseMajorFromShareKey(key);
            Double sharePercent = parsePercentFromAny(source.opt(key));
            putShareEntry(target, majorVersion, sharePercent);
        }
    }

    private void mergeVersionShareArray(JSONObject target, JSONArray source) {
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) {
                continue;
            }

            Integer majorVersion = parseMajorFromAny(item.opt("major"));
            if (majorVersion == null) {
                majorVersion = parseMajorFromAny(item.opt("version"));
            }
            if (majorVersion == null) {
                majorVersion = parseMajorFromAny(item.opt("name"));
            }

            Double sharePercent = parsePercentFromAny(item.opt("share"));
            if (sharePercent == null) {
                sharePercent = parsePercentFromAny(item.opt("sharePercent"));
            }
            if (sharePercent == null) {
                sharePercent = parsePercentFromAny(item.opt("percent"));
            }
            if (sharePercent == null) {
                sharePercent = parsePercentFromAny(item.opt("usage"));
            }
            if (sharePercent == null) {
                sharePercent = parsePercentFromAny(item.opt("value"));
            }

            putShareEntry(target, majorVersion, sharePercent);
        }
    }

    private void putShareEntry(JSONObject target, @Nullable Integer majorVersion, @Nullable Double sharePercent) {
        if (majorVersion == null || sharePercent == null) {
            return;
        }
        try {
            target.put(String.valueOf(majorVersion), sharePercent);
        } catch (Exception ignored) {
            // Ignore invalid JSON insertions for malformed input.
        }
    }

    @Nullable
    private Integer parseMajorFromShareKey(@Nullable String key) {
        if (isBlank(key)) {
            return null;
        }

        String normalized = key.trim();
        char first = normalized.charAt(0);
        if (Character.isDigit(first)) {
            return versionChecker.parseMajorVersion(normalized);
        }

        if ((first == 'v' || first == 'V') && normalized.length() > 1 && Character.isDigit(normalized.charAt(1))) {
            return versionChecker.parseMajorVersion(normalized.substring(1));
        }

        return null;
    }

    @Nullable
    private Integer parseMajorFromAny(@Nullable Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            int major = ((Number) value).intValue();
            return major >= 0 ? major : null;
        }

        if (value instanceof String) {
            return versionChecker.parseMajorVersion((String) value);
        }

        return null;
    }

    @Nullable
    private Double parsePercentFromAny(@Nullable Object value) {
        if (value == null) {
            return null;
        }

        Double percent = null;
        if (value instanceof Number) {
            percent = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                percent = Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return normalizePercentValue(percent);
    }

    @Nullable
    private Double normalizePercentValue(@Nullable Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    @Nullable
    private JSONObject copyJsonObject(@Nullable JSONObject value) {
        if (value == null) {
            return null;
        }
        try {
            return new JSONObject(value.toString());
        } catch (Exception error) {
            return null;
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

    private static final class VersionShareResolution {

        @Nullable
        private final JSONObject shareByMajor;

        @Nullable
        private final String source;

        @Nullable
        private final String errorMessage;

        private VersionShareResolution(@Nullable JSONObject shareByMajor, @Nullable String source, @Nullable String errorMessage) {
            this.shareByMajor = shareByMajor;
            this.source = source;
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
        private boolean autoPromptDismissible = true;

        @Nullable
        private String latestVersion;

        @Nullable
        private Integer minimumMajorVersion;

        @Nullable
        private Double minimumDeviceSharePercent = 3.0;

        @Nullable
        private JSONObject versionShareByMajor;

        @Nullable
        private String versionShareApiUrl;

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
            out.autoPromptDismissible = config.getBoolean("autoPromptDismissible", true);
            out.latestVersion = emptyToNull(config.getString("latestVersion"));
            Integer minimum = config.getConfigJSON().has("minimumMajorVersion") ? config.getInt("minimumMajorVersion", 0) : null;
            out.minimumMajorVersion = minimum;
            if (config.getConfigJSON().has("minimumDeviceSharePercent")) {
                out.minimumDeviceSharePercent = normalizePercent(readOptionalDouble(config.getConfigJSON(), "minimumDeviceSharePercent"));
            }
            out.versionShareByMajor = copyJsonObject(config.getObject("versionShareByMajor"));
            out.versionShareApiUrl = emptyToNull(config.getString("versionShareApiUrl"));
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
            if (call.hasOption("autoPromptDismissible")) {
                out.autoPromptDismissible = call.getBoolean("autoPromptDismissible", out.autoPromptDismissible);
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
            if (call.hasOption("minimumDeviceSharePercent")) {
                out.minimumDeviceSharePercent = normalizePercent(call.getDouble("minimumDeviceSharePercent"));
            }
            if (call.hasOption("versionShareByMajor")) {
                out.versionShareByMajor = copyJsonObject(call.getObject("versionShareByMajor"));
            }
            if (call.hasOption("versionShareApiUrl")) {
                out.versionShareApiUrl = emptyToNull(call.getString("versionShareApiUrl"));
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
            out.autoPromptDismissible = autoPromptDismissible;
            out.showPromptOnOutdated = showPromptOnOutdated;
            out.latestVersion = latestVersion;
            out.minimumMajorVersion = minimumMajorVersion;
            out.minimumDeviceSharePercent = minimumDeviceSharePercent;
            out.versionShareByMajor = copyJsonObject(versionShareByMajor);
            out.versionShareApiUrl = versionShareApiUrl;
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

        @Nullable
        private static Double readOptionalDouble(JSONObject source, String key) {
            if (!source.has(key) || source.isNull(key)) {
                return null;
            }

            Object value = source.opt(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }

            if (value instanceof String) {
                try {
                    return Double.parseDouble(((String) value).trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }

            return null;
        }

        @Nullable
        private static Double normalizePercent(@Nullable Double value) {
            if (value == null || value.isNaN() || value.isInfinite()) {
                return null;
            }
            return Math.max(0.0, Math.min(100.0, value));
        }

        @Nullable
        private static JSONObject copyJsonObject(@Nullable JSONObject value) {
            if (value == null || value.length() == 0) {
                return null;
            }
            try {
                return new JSONObject(value.toString());
            } catch (Exception error) {
                return null;
            }
        }
    }
}
