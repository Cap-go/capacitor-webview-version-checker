# @capgo/capacitor-webview-version-checker
 <a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin_webview_version_checker"> ➡️ Get Instant updates for your App with Capgo</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin_webview_version_checker"> Missing a feature? We’ll build the plugin for you 💪</a></h2>
</div>

Capacitor plugin to detect whether the app WebView engine is up to date, emit realtime status events, and optionally show a native update prompt that redirects users to the proper update destination.

## Why WebView Version Checker?

Capacitor already provides a built-in WebView minimum check through config:

```ts
import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  android: {
    minWebViewVersion: 124,
  },
  server: {
    errorPath: 'unsupported-webview.html',
  },
};

export default config;
```

### Why we're doing this instead

- `android.minWebViewVersion` is static config; it does not give runtime plugin events like `webViewOutdated`.
- With `server.errorPath`, users are redirected to a static error page (hard block UX) instead of seeing a native modal in your normal app flow.
- Without `server.errorPath`, Capacitor only logs the issue, but does not provide a native update prompt + store deep link UX.
- This plugin gives soft enforcement: users can still open/use the app, get a native update modal, and you decide whether to require update later.

## Install

```bash
bun add @capgo/capacitor-webview-version-checker
bunx cap sync
```

## Usage

Simple setup (config only):

```ts
import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  plugins: {
    WebviewVersionChecker: {
      autoCheckOnLoad: true,
      autoCheckOnResume: true,
      autoPromptOnOutdated: true,
    },
  },
};

export default config;
```

Advanced usage with JavaScript (manual check, listeners, custom prompt):

```ts
import { WebviewVersionChecker } from '@capgo/capacitor-webview-version-checker';

const listener = await WebviewVersionChecker.addListener('webViewOutdated', (status) => {
  console.log('Outdated WebView detected', status);
});

const status = await WebviewVersionChecker.check({
  latestVersionApiUrl:
    'https://versionhistory.googleapis.com/v1/chrome/platforms/android/channels/stable/versions?page_size=1',
  minimumMajorVersion: 124,
  showPromptOnOutdated: true,
  promptTitle: 'Update WebView',
  promptMessage: 'Your WebView is outdated. Please update to continue safely.',
  promptUpdateButtonText: 'Update now',
  promptCancelButtonText: 'Later',
});

console.log('WebView status', status);

// later
listener.remove();
```

## Android Provider Handling

The plugin resolves the active WebView provider package and version, then routes update links to the correct package:
- Android 5-6 and 10+: Android System WebView (`com.google.android.webview`)
- Android 7-9: Google Chrome (`com.android.chrome`)

When provider detection is unavailable, fallback routing follows the same API-range rule above.

## API

<docgen-index>

* [`check(...)`](#check)
* [`startMonitoring(...)`](#startmonitoring)
* [`stopMonitoring()`](#stopmonitoring)
* [`getLastStatus()`](#getlaststatus)
* [`showUpdatePrompt(...)`](#showupdateprompt)
* [`openUpdatePage(...)`](#openupdatepage)
* [`addListener('statusChanged', ...)`](#addlistenerstatuschanged-)
* [`addListener('webViewLatest', ...)`](#addlistenerwebviewlatest-)
* [`addListener('webViewOutdated', ...)`](#addlistenerwebviewoutdated-)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

Public API for checking WebView freshness and guiding users to updates.

### check(...)

```typescript
check(options?: CheckWebViewOptions | undefined) => Promise<WebViewVersionStatus>
```

Runs a version check and returns the latest known status.

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#checkwebviewoptions">CheckWebViewOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#webviewversionstatus">WebViewVersionStatus</a>&gt;</code>

--------------------


### startMonitoring(...)

```typescript
startMonitoring(options?: StartMonitoringOptions | undefined) => Promise<MonitoringStateResult>
```

Enables background monitoring (typically on app resume).

| Param         | Type                                                                      |
| ------------- | ------------------------------------------------------------------------- |
| **`options`** | <code><a href="#startmonitoringoptions">StartMonitoringOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#monitoringstateresult">MonitoringStateResult</a>&gt;</code>

--------------------


### stopMonitoring()

```typescript
stopMonitoring() => Promise<MonitoringStateResult>
```

Disables monitoring.

**Returns:** <code>Promise&lt;<a href="#monitoringstateresult">MonitoringStateResult</a>&gt;</code>

--------------------


### getLastStatus()

```typescript
getLastStatus() => Promise<LastStatusResult>
```

Returns the last resolved status, or `null` if no check was run yet.

**Returns:** <code>Promise&lt;<a href="#laststatusresult">LastStatusResult</a>&gt;</code>

--------------------


### showUpdatePrompt(...)

```typescript
showUpdatePrompt(options?: ShowUpdatePromptOptions | undefined) => Promise<ShowUpdatePromptResult>
```

Shows a native prompt asking the user to update the WebView.

| Param         | Type                                                                        |
| ------------- | --------------------------------------------------------------------------- |
| **`options`** | <code><a href="#showupdatepromptoptions">ShowUpdatePromptOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#showupdatepromptresult">ShowUpdatePromptResult</a>&gt;</code>

--------------------


### openUpdatePage(...)

```typescript
openUpdatePage(options?: OpenUpdatePageOptions | undefined) => Promise<OpenUpdatePageResult>
```

Opens the configured update page directly.

| Param         | Type                                                                    |
| ------------- | ----------------------------------------------------------------------- |
| **`options`** | <code><a href="#openupdatepageoptions">OpenUpdatePageOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#openupdatepageresult">OpenUpdatePageResult</a>&gt;</code>

--------------------


### addListener('statusChanged', ...)

```typescript
addListener(eventName: 'statusChanged', listenerFunc: (status: WebViewVersionStatus) => void) => Promise<PluginListenerHandle>
```

Fired for every successful status evaluation.

| Param              | Type                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'statusChanged'</code>                                                               |
| **`listenerFunc`** | <code>(status: <a href="#webviewversionstatus">WebViewVersionStatus</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webViewLatest', ...)

```typescript
addListener(eventName: 'webViewLatest', listenerFunc: (status: WebViewVersionStatus) => void) => Promise<PluginListenerHandle>
```

Fired when the state resolves to `latest`.

| Param              | Type                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'webViewLatest'</code>                                                               |
| **`listenerFunc`** | <code>(status: <a href="#webviewversionstatus">WebViewVersionStatus</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webViewOutdated', ...)

```typescript
addListener(eventName: 'webViewOutdated', listenerFunc: (status: WebViewVersionStatus) => void) => Promise<PluginListenerHandle>
```

Fired when the state resolves to `outdated`.

| Param              | Type                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'webViewOutdated'</code>                                                             |
| **`listenerFunc`** | <code>(status: <a href="#webviewversionstatus">WebViewVersionStatus</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### Interfaces


#### WebViewVersionStatus

Snapshot of the currently detected WebView status.

| Prop                      | Type                                                                | Description                                              |
| ------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------- |
| **`platform`**            | <code><a href="#webviewplatform">WebViewPlatform</a></code>         | Native platform that generated the status.               |
| **`state`**               | <code><a href="#webviewversionstate">WebViewVersionState</a></code> | Resolved version state.                                  |
| **`isLatest`**            | <code>boolean</code>                                                | Convenience boolean equivalent to `state === 'latest'`.  |
| **`checkedAt`**           | <code>string</code>                                                 | ISO-8601 timestamp of the check.                         |
| **`reason`**              | <code>string</code>                                                 | Human-readable explanation for the reported state.       |
| **`currentVersion`**      | <code>string</code>                                                 | Current WebView (or iOS system WebKit) version string.   |
| **`currentMajorVersion`** | <code>number</code>                                                 | Current detected major version (if parseable).           |
| **`latestVersion`**       | <code>string</code>                                                 | Resolved latest version used for comparison.             |
| **`latestMajorVersion`**  | <code>number</code>                                                 | Resolved latest major version (if parseable).            |
| **`providerPackage`**     | <code>string</code>                                                 | Android package name of the active WebView provider.     |
| **`updateUrl`**           | <code>string</code>                                                 | URL that should be opened to update when outdated.       |
| **`source`**              | <code>string</code>                                                 | Internal source identifier for the check implementation. |


#### CheckWebViewOptions

Options for running a WebView version check.

| Prop                       | Type                 | Description                                                                       | Default               |
| -------------------------- | -------------------- | --------------------------------------------------------------------------------- | --------------------- |
| **`showPromptOnOutdated`** | <code>boolean</code> | Force showing a native prompt if an outdated WebView is detected.                 | <code>false</code>    |
| **`source`**               | <code>string</code>  | Optional tag included in the status payload so you can identify the check origin. | <code>"manual"</code> |


#### MonitoringStateResult

Result payload for monitor controls.

| Prop                       | Type                 | Description                                                  |
| -------------------------- | -------------------- | ------------------------------------------------------------ |
| **`monitoring`**           | <code>boolean</code> | Whether monitoring is currently active.                      |
| **`checkOnResume`**        | <code>boolean</code> | Whether checks run on app resume while monitoring is active. |
| **`autoPromptOnOutdated`** | <code>boolean</code> | Whether outdated checks auto-trigger the native prompt.      |


#### StartMonitoringOptions

Options for starting monitor mode.

| Prop                | Type                 | Description                                                       | Default           |
| ------------------- | -------------------- | ----------------------------------------------------------------- | ----------------- |
| **`checkOnStart`**  | <code>boolean</code> | Run a check immediately when monitoring starts.                   | <code>true</code> |
| **`checkOnResume`** | <code>boolean</code> | Whether foreground checks should run while monitoring is enabled. | <code>true</code> |


#### LastStatusResult

Last known check snapshot.

| Prop         | Type                                                                          | Description                           |
| ------------ | ----------------------------------------------------------------------------- | ------------------------------------- |
| **`status`** | <code><a href="#webviewversionstatus">WebViewVersionStatus</a> \| null</code> | Null until the first check completes. |


#### ShowUpdatePromptResult

Result payload for `showUpdatePrompt()`.

| Prop                   | Type                 | Description                                                |
| ---------------------- | -------------------- | ---------------------------------------------------------- |
| **`shown`**            | <code>boolean</code> | Whether a native prompt was actually shown.                |
| **`openedUpdatePage`** | <code>boolean</code> | Whether the update page was opened from the prompt action. |


#### ShowUpdatePromptOptions

Options for showing the native update prompt.

| Prop                   | Type                | Description                                            |
| ---------------------- | ------------------- | ------------------------------------------------------ |
| **`title`**            | <code>string</code> | Prompt title.                                          |
| **`message`**          | <code>string</code> | Prompt message.                                        |
| **`updateButtonText`** | <code>string</code> | Update CTA label.                                      |
| **`cancelButtonText`** | <code>string</code> | Cancel CTA label.                                      |
| **`updateUrl`**        | <code>string</code> | Optional URL to open if the update action is selected. |


#### OpenUpdatePageResult

Result payload for `openUpdatePage()`.

| Prop         | Type                 | Description                               |
| ------------ | -------------------- | ----------------------------------------- |
| **`opened`** | <code>boolean</code> | Whether opening the update URL succeeded. |
| **`url`**    | <code>string</code>  | URL that was attempted.                   |


#### OpenUpdatePageOptions

Options for opening the update page directly.

| Prop            | Type                | Description            |
| --------------- | ------------------- | ---------------------- |
| **`updateUrl`** | <code>string</code> | Optional URL override. |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


### Type Aliases


#### WebViewPlatform

<code>'android' | 'ios' | 'web'</code>


#### WebViewVersionState

<code>'latest' | 'outdated' | 'unknown'</code>

</docgen-api>
