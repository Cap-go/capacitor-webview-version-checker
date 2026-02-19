import { WebPlugin } from '@capacitor/core';

import type {
  CheckWebViewOptions,
  LastStatusResult,
  MonitoringStateResult,
  OpenUpdatePageOptions,
  OpenUpdatePageResult,
  ShowUpdatePromptOptions,
  ShowUpdatePromptResult,
  StartMonitoringOptions,
  WebViewVersionState,
  WebViewVersionStatus,
  WebviewVersionCheckerPlugin,
} from './definitions';
import { DEFAULT_VERSION_SHARE_BY_MAJOR, DEFAULT_VERSION_SHARE_DATA_SOURCE } from './generated-version-share';

const UNKNOWN_REASON =
  'Web implementation is a compatibility shim. Native Android/iOS implementations provide full WebView checks.';

export class WebviewVersionCheckerWeb extends WebPlugin implements WebviewVersionCheckerPlugin {
  private monitoring = false;
  private checkOnResume = true;
  private autoPromptOnOutdated = false;
  private lastStatus: WebViewVersionStatus | null = null;

  async check(options: CheckWebViewOptions = {}): Promise<WebViewVersionStatus> {
    const currentVersion = this.detectCurrentVersion();
    const currentMajorVersion = this.parseMajorVersion(currentVersion);
    const latestVersion = options.latestVersion;
    const latestMajorVersion = this.parseMajorVersion(latestVersion);
    const minimumDeviceSharePercent = this.normalizePercent(options.minimumDeviceSharePercent ?? 3);
    const normalizedInlineVersionShareByMajor = this.normalizeVersionShareByMajor(options.versionShareByMajor);
    const normalizedGeneratedVersionShareByMajor = this.normalizeVersionShareByMajor(DEFAULT_VERSION_SHARE_BY_MAJOR);
    const normalizedVersionShareByMajor = normalizedInlineVersionShareByMajor ?? normalizedGeneratedVersionShareByMajor;
    const versionShareSource = normalizedInlineVersionShareByMajor
      ? 'versionShareByMajor'
      : DEFAULT_VERSION_SHARE_DATA_SOURCE;
    const currentVersionSharePercent = this.resolveCurrentVersionSharePercent(
      currentMajorVersion,
      normalizedVersionShareByMajor,
    );
    let deviceShareError: string | undefined;

    let state: WebViewVersionState = 'unknown';
    let reason = UNKNOWN_REASON;

    if (typeof minimumDeviceSharePercent === 'number') {
      if (typeof currentVersionSharePercent === 'number') {
        state = currentVersionSharePercent >= minimumDeviceSharePercent ? 'latest' : 'outdated';
        reason =
          state === 'latest'
            ? 'Current browser engine major version meets the configured device-share threshold.'
            : 'Current browser engine major version is below the configured device-share threshold.';
      } else if (!normalizedVersionShareByMajor && options.versionShareApiUrl) {
        deviceShareError =
          'Web shim does not fetch version-share API data. Provide versionShareByMajor to evaluate minimumDeviceSharePercent.';
      } else if (!normalizedVersionShareByMajor) {
        deviceShareError = 'minimumDeviceSharePercent is set, but no versionShareByMajor dataset was provided.';
      } else {
        deviceShareError = 'No device-share entry was found for the current browser engine major version.';
      }
    }

    if (state === 'unknown' && latestVersion && currentVersion) {
      const cmp = this.compareVersions(currentVersion, latestVersion);
      state = cmp >= 0 ? 'latest' : 'outdated';
      reason =
        cmp >= 0
          ? 'Current browser engine is at or above the requested latest version.'
          : 'Current browser engine is below the requested latest version.';
    } else if (
      state === 'unknown' &&
      typeof options.minimumMajorVersion === 'number' &&
      typeof currentMajorVersion === 'number'
    ) {
      state = currentMajorVersion >= options.minimumMajorVersion ? 'latest' : 'outdated';
      reason =
        state === 'latest'
          ? 'Current browser engine major version meets the minimum requirement.'
          : 'Current browser engine major version is below the minimum requirement.';
    } else if (state === 'unknown' && deviceShareError) {
      reason = deviceShareError;
    }

    const status: WebViewVersionStatus = {
      platform: 'web',
      state,
      isLatest: state === 'latest',
      checkedAt: new Date().toISOString(),
      reason,
      currentVersion,
      currentMajorVersion,
      latestVersion,
      latestMajorVersion,
      currentVersionSharePercent,
      minimumDeviceSharePercent,
      versionShareSource: normalizedVersionShareByMajor ? versionShareSource : undefined,
      deviceShareError,
      updateUrl: options.updateUrl,
      source: options.source ?? 'web-shim',
    };

    this.lastStatus = status;
    this.notifyListeners('statusChanged', status);
    if (state === 'latest') {
      this.notifyListeners('webViewLatest', status);
    } else if (state === 'outdated') {
      this.notifyListeners('webViewOutdated', status);
      if (options.showPromptOnOutdated || this.autoPromptOnOutdated) {
        await this.showUpdatePrompt({ updateUrl: status.updateUrl });
      }
    }

    return status;
  }

  async startMonitoring(options: StartMonitoringOptions = {}): Promise<MonitoringStateResult> {
    this.monitoring = true;
    this.checkOnResume = options.checkOnResume ?? true;
    this.autoPromptOnOutdated = options.autoPromptOnOutdated ?? false;

    if (options.checkOnStart ?? true) {
      await this.check({ ...options, source: options.source ?? 'startMonitoring' });
    }

    return {
      monitoring: this.monitoring,
      checkOnResume: this.checkOnResume,
      autoPromptOnOutdated: this.autoPromptOnOutdated,
    };
  }

  async stopMonitoring(): Promise<MonitoringStateResult> {
    this.monitoring = false;
    return {
      monitoring: this.monitoring,
      checkOnResume: this.checkOnResume,
      autoPromptOnOutdated: this.autoPromptOnOutdated,
    };
  }

  async getLastStatus(): Promise<LastStatusResult> {
    return {
      status: this.lastStatus,
    };
  }

  async showUpdatePrompt(options: ShowUpdatePromptOptions = {}): Promise<ShowUpdatePromptResult> {
    const url = options.updateUrl ?? this.lastStatus?.updateUrl;

    if (!url) {
      return {
        shown: false,
        openedUpdatePage: false,
      };
    }

    const opened = this.openUrl(url);

    return {
      shown: true,
      openedUpdatePage: opened,
    };
  }

  async openUpdatePage(options: OpenUpdatePageOptions = {}): Promise<OpenUpdatePageResult> {
    const url = options.updateUrl ?? this.lastStatus?.updateUrl ?? '';
    if (!url) {
      return {
        opened: false,
        url,
      };
    }

    return {
      opened: this.openUrl(url),
      url,
    };
  }

  private detectCurrentVersion(): string {
    const chromiumMatch = navigator.userAgent.match(/Chrome\/([\d.]+)/i);
    if (chromiumMatch?.[1]) {
      return chromiumMatch[1];
    }

    const appleWebKitMatch = navigator.userAgent.match(/AppleWebKit\/([\d.]+)/i);
    if (appleWebKitMatch?.[1]) {
      return appleWebKitMatch[1];
    }

    return navigator.userAgent;
  }

  private openUrl(url: string): boolean {
    try {
      window.open(url, '_blank', 'noopener,noreferrer');
      return true;
    } catch {
      return false;
    }
  }

  private parseMajorVersion(version?: string): number | undefined {
    if (!version) {
      return undefined;
    }

    const match = version.match(/(\d+)/);
    if (!match) {
      return undefined;
    }

    const major = Number.parseInt(match[1], 10);
    return Number.isFinite(major) ? major : undefined;
  }

  private compareVersions(left: string, right: string): number {
    const leftParts = left
      .split(/[^0-9]+/)
      .filter(Boolean)
      .map((part) => Number.parseInt(part, 10));
    const rightParts = right
      .split(/[^0-9]+/)
      .filter(Boolean)
      .map((part) => Number.parseInt(part, 10));
    const maxLength = Math.max(leftParts.length, rightParts.length);

    for (let i = 0; i < maxLength; i += 1) {
      const a = leftParts[i] ?? 0;
      const b = rightParts[i] ?? 0;
      if (a > b) {
        return 1;
      }
      if (a < b) {
        return -1;
      }
    }

    return 0;
  }

  private normalizePercent(value?: number): number | undefined {
    if (typeof value !== 'number' || Number.isNaN(value) || !Number.isFinite(value)) {
      return undefined;
    }
    return Math.min(100, Math.max(0, value));
  }

  private normalizeVersionShareByMajor(shareByMajor?: Record<string, number>): Record<number, number> | undefined {
    if (!shareByMajor) {
      return undefined;
    }

    const normalized: Record<number, number> = {};
    for (const [rawKey, rawShare] of Object.entries(shareByMajor)) {
      const major = this.parseMajorVersion(rawKey);
      const share = this.normalizePercent(rawShare);
      if (typeof major === 'number' && typeof share === 'number') {
        normalized[major] = share;
      }
    }

    return Object.keys(normalized).length > 0 ? normalized : undefined;
  }

  private resolveCurrentVersionSharePercent(
    currentMajorVersion: number | undefined,
    normalizedShareByMajor: Record<number, number> | undefined,
  ): number | undefined {
    if (typeof currentMajorVersion !== 'number' || !normalizedShareByMajor) {
      return undefined;
    }

    return normalizedShareByMajor[currentMajorVersion];
  }
}
