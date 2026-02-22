import type { PluginListenerHandle } from '@capacitor/core';

export type WebViewVersionState = 'latest' | 'outdated' | 'unknown';

export type WebViewPlatform = 'android' | 'ios' | 'web';

/**
 * Capacitor config shape for `plugins.WebviewVersionChecker`.
 *
 * Main use case (default, no plugin config required):
 * Browserslist-style compatibility checks with a built-in version-share dataset
 * generated from caniuse at plugin build time.
 *
 * ```ts
 * plugins: {
 *   WebviewVersionChecker: {}
 * }
 * ```
 *
 * Simple setup with automatic prompt:
 *
 * ```ts
 * plugins: {
 *   WebviewVersionChecker: {
 *     autoPromptOnOutdated: true,
 *   }
 * }
 * ```
 *
 * Advanced setup with custom dataset and threshold:
 *
 * ```ts
 * plugins: {
 *   WebviewVersionChecker: {
 *     minimumDeviceSharePercent: 3,
 *     versionShareByMajor: { '137': 58.2, '136': 21.3, '135': 4.6 },
 *   }
 * }
 * ```
 */
export interface WebviewVersionCheckerConfig {
  /**
   * Automatically run a version check once when the plugin loads.
   *
   * @default true
   */
  autoCheckOnLoad?: boolean;

  /**
   * Automatically run a version check each time the app returns to foreground.
   *
   * @default true
   */
  autoCheckOnResume?: boolean;

  /**
   * Automatically show a native update prompt whenever an outdated WebView is detected.
   *
   * @default false
   */
  autoPromptOnOutdated?: boolean;

  /**
   * Controls whether the automatic modal can be dismissed.
   *
   * When set to false, users cannot dismiss the automatic modal manually.
   * They can only exit by opening the update action and reopening the app after updating.
   *
   * @default true
   */
  autoPromptDismissible?: boolean;

  /**
   * Explicit latest version to compare with.
   * If provided, this value is used before any API lookup.
   */
  latestVersion?: string;

  /**
   * Fallback minimum allowed major version.
   * Used when `latestVersion` cannot be resolved.
   */
  minimumMajorVersion?: number;

  /**
   * Browserslist-style compatibility threshold in percentage points.
   *
   * Example: `3` means the installed WebView major version must represent at least 3%
   * of devices in the selected version-share dataset.
   *
   * Data source priority:
   * - `versionShareByMajor` (inline dataset) if provided
   * - else `versionShareApiUrl` if provided
   * - else built-in generated dataset (`generatedVersionShareByMajor`)
   *
   * If the threshold cannot be evaluated (missing dataset or missing major entry),
   * the plugin falls back to normal version checks (`latestVersion` / `latestVersionApiUrl`,
   * then `minimumMajorVersion`).
   *
   * @default 3
   */
  minimumDeviceSharePercent?: number;

  /**
   * Optional inline dataset for major-version device share.
   *
   * Keys are major versions (string or number-like), values are percentages (`0..100`).
   *
   * Example:
   * `{ "137": 54.2, "136": 23.8, "135": 7.1, "134": 2.4 }`
   *
   * Used when `minimumDeviceSharePercent` is set.
   * If both `versionShareByMajor` and `versionShareApiUrl` are provided,
   * this inline map is used first.
   */
  versionShareByMajor?: Record<string, number>;

  /**
   * Optional endpoint returning a version-share dataset.
   *
   * Used when `minimumDeviceSharePercent` is set and no inline `versionShareByMajor`
   * is provided. If omitted, the plugin uses its built-in generated dataset.
   *
   * Supported response shapes:
   * - `{ "versionShareByMajor": { "137": 54.2, "136": 23.8 } }`
   * - `{ "shareByMajor": { "137": 54.2, "136": 23.8 } }`
   * - `{ "versions": [{ "major": 137, "share": 54.2 }, { "version": "136.0.0.0", "percent": 23.8 }] }`
   * - Caniuse full dataset shape (`agents.*.usage_global`)
   */
  versionShareApiUrl?: string;

  /**
   * Optional endpoint returning a JSON payload with the latest version.
   *
   * Supported response shapes:
   * - `{ "version": "137.0.7151.44" }`
   * - `{ "latestVersion": "137.0.7151.44" }`
   * - Google version history format: `{ "versions": [{ "version": "137.0.7151.44" }] }`
   */
  latestVersionApiUrl?: string;

  /**
   * Optional URL opened when users tap update in the native prompt.
   *
   * Android default: Play Store listing of the active WebView package.
   * iOS default: Apple iOS update help page.
   */
  updateUrl?: string;

  /**
   * Native prompt title.
   */
  promptTitle?: string;

  /**
   * Native prompt message.
   */
  promptMessage?: string;

  /**
   * Native prompt update button text.
   */
  promptUpdateButtonText?: string;

  /**
   * Native prompt cancel button text.
   */
  promptCancelButtonText?: string;
}

/**
 * Options for running a WebView version check.
 */
export interface CheckWebViewOptions extends WebviewVersionCheckerConfig {
  /**
   * Force showing a native prompt if an outdated WebView is detected.
   *
   * @default false
   */
  showPromptOnOutdated?: boolean;

  /**
   * Optional tag included in the status payload so you can identify the check origin.
   *
   * @default "manual"
   */
  source?: string;
}

/**
 * Options for starting monitor mode.
 */
export interface StartMonitoringOptions extends CheckWebViewOptions {
  /**
   * Run a check immediately when monitoring starts.
   *
   * @default true
   */
  checkOnStart?: boolean;

  /**
   * Whether foreground checks should run while monitoring is enabled.
   *
   * @default true
   */
  checkOnResume?: boolean;
}

/**
 * Options for showing the native update prompt.
 */
export interface ShowUpdatePromptOptions {
  /**
   * Prompt title.
   */
  title?: string;

  /**
   * Prompt message.
   */
  message?: string;

  /**
   * Update CTA label.
   */
  updateButtonText?: string;

  /**
   * Cancel CTA label.
   */
  cancelButtonText?: string;

  /**
   * Optional URL to open if the update action is selected.
   */
  updateUrl?: string;
}

/**
 * Options for opening the update page directly.
 */
export interface OpenUpdatePageOptions {
  /**
   * Optional URL override.
   */
  updateUrl?: string;
}

/**
 * Snapshot of the currently detected WebView status.
 */
export interface WebViewVersionStatus {
  /**
   * Native platform that generated the status.
   */
  platform: WebViewPlatform;

  /**
   * Resolved version state.
   */
  state: WebViewVersionState;

  /**
   * Convenience boolean equivalent to `state === 'latest'`.
   */
  isLatest: boolean;

  /**
   * ISO-8601 timestamp of the check.
   */
  checkedAt: string;

  /**
   * Human-readable explanation for the reported state.
   */
  reason: string;

  /**
   * Current WebView (or iOS system WebKit) version string.
   */
  currentVersion: string;

  /**
   * Current detected major version (if parseable).
   */
  currentMajorVersion?: number;

  /**
   * Resolved latest version used for comparison.
   */
  latestVersion?: string;

  /**
   * Resolved latest major version (if parseable).
   */
  latestMajorVersion?: number;

  /**
   * Device-share percentage for the installed WebView major version.
   *
   * Present only when a version-share dataset is available and includes the current major version.
   */
  currentVersionSharePercent?: number;

  /**
   * Configured threshold used by compatibility-threshold mode.
   *
   * Present only when `minimumDeviceSharePercent` was requested for that check.
   */
  minimumDeviceSharePercent?: number;

  /**
   * Source used for version-share data.
   *
   * Values:
   * - `versionShareByMajor` (inline dataset)
   * - `versionShareApiUrl` (remote dataset)
   * - `generatedVersionShareByMajor` (built-in dataset generated at build time)
   */
  versionShareSource?: string;

  /**
   * Diagnostic message for compatibility-threshold mode.
   *
   * Present when `minimumDeviceSharePercent` was requested but the threshold check
   * could not be fully evaluated.
   */
  deviceShareError?: string;

  /**
   * Android package name of the active WebView provider.
   */
  providerPackage?: string;

  /**
   * URL that should be opened to update when outdated.
   */
  updateUrl?: string;

  /**
   * Internal source identifier for the check implementation.
   */
  source: string;
}

/**
 * Result payload for monitor controls.
 */
export interface MonitoringStateResult {
  /**
   * Whether monitoring is currently active.
   */
  monitoring: boolean;

  /**
   * Whether checks run on app resume while monitoring is active.
   */
  checkOnResume: boolean;

  /**
   * Whether outdated checks auto-trigger the native prompt.
   */
  autoPromptOnOutdated: boolean;
}

/**
 * Last known check snapshot.
 */
export interface LastStatusResult {
  /**
   * Null until the first check completes.
   */
  status: WebViewVersionStatus | null;
}

/**
 * Result payload for `showUpdatePrompt()`.
 */
export interface ShowUpdatePromptResult {
  /**
   * Whether a native prompt was actually shown.
   */
  shown: boolean;

  /**
   * Whether the update page was opened from the prompt action.
   */
  openedUpdatePage: boolean;
}

/**
 * Result payload for `openUpdatePage()`.
 */
export interface OpenUpdatePageResult {
  /**
   * Whether opening the update URL succeeded.
   */
  opened: boolean;

  /**
   * URL that was attempted.
   */
  url: string;
}

/**
 * Public API for checking WebView freshness and guiding users to updates.
 */
export interface WebviewVersionCheckerPlugin {
  /**
   * Runs a version check and returns the latest known status.
   */
  check(options?: CheckWebViewOptions): Promise<WebViewVersionStatus>;

  /**
   * Enables background monitoring (typically on app resume).
   */
  startMonitoring(options?: StartMonitoringOptions): Promise<MonitoringStateResult>;

  /**
   * Disables monitoring.
   */
  stopMonitoring(): Promise<MonitoringStateResult>;

  /**
   * Returns the last resolved status, or `null` if no check was run yet.
   */
  getLastStatus(): Promise<LastStatusResult>;

  /**
   * Shows a native prompt asking the user to update the WebView.
   */
  showUpdatePrompt(options?: ShowUpdatePromptOptions): Promise<ShowUpdatePromptResult>;

  /**
   * Opens the configured update page directly.
   */
  openUpdatePage(options?: OpenUpdatePageOptions): Promise<OpenUpdatePageResult>;

  /**
   * Fired for every successful status evaluation.
   */
  addListener(
    eventName: 'statusChanged',
    listenerFunc: (status: WebViewVersionStatus) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Fired when the state resolves to `latest`.
   */
  addListener(
    eventName: 'webViewLatest',
    listenerFunc: (status: WebViewVersionStatus) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Fired when the state resolves to `outdated`.
   */
  addListener(
    eventName: 'webViewOutdated',
    listenerFunc: (status: WebViewVersionStatus) => void,
  ): Promise<PluginListenerHandle>;
}
