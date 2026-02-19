import Foundation
import Capacitor
import UIKit

@objc(WebviewVersionCheckerPlugin)
public class WebviewVersionCheckerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "WebviewVersionCheckerPlugin"
    public let jsName = "WebviewVersionChecker"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "check", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLastStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "showUpdatePrompt", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openUpdatePage", returnType: CAPPluginReturnPromise)
    ]

    private let implementation = WebviewVersionChecker()

    private var runtimeOptions = RuntimeOptions()
    private var monitoringEnabled = false
    private var checkOnResume = true
    private var lastStatus: [String: Any]?
    private var lastPromptFingerprint: String?

    private var didBecomeActiveObserver: NSObjectProtocol?

    override public func load() {
        super.load()

        runtimeOptions = RuntimeOptions(config: getConfig())
        monitoringEnabled = runtimeOptions.autoCheckOnResume
        checkOnResume = runtimeOptions.autoCheckOnResume

        didBecomeActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            guard self.monitoringEnabled, self.checkOnResume else { return }
            self.runCheck(source: "resume", options: self.runtimeOptions, forcePrompt: false, completion: nil)
        }

        if runtimeOptions.autoCheckOnLoad {
            runCheck(source: "load", options: runtimeOptions, forcePrompt: false, completion: nil)
        }
    }

    deinit {
        if let observer = didBecomeActiveObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    @objc func check(_ call: CAPPluginCall) {
        let options = runtimeOptions.merged(with: call)
        let source = call.getString("source") ?? "manual"

        runCheck(source: source, options: options, forcePrompt: options.showPromptOnOutdated) { status in
            call.resolve(status)
        }
    }

    @objc func startMonitoring(_ call: CAPPluginCall) {
        runtimeOptions = runtimeOptions.merged(with: call)
        monitoringEnabled = true
        checkOnResume = call.getBool("checkOnResume", runtimeOptions.autoCheckOnResume)

        if call.getBool("checkOnStart", true) {
            runCheck(source: "startMonitoring", options: runtimeOptions, forcePrompt: runtimeOptions.showPromptOnOutdated, completion: nil)
        }

        call.resolve(monitoringStatePayload())
    }

    @objc func stopMonitoring(_ call: CAPPluginCall) {
        monitoringEnabled = false
        call.resolve(monitoringStatePayload())
    }

    @objc func getLastStatus(_ call: CAPPluginCall) {
        if let lastStatus {
            call.resolve(["status": lastStatus])
            return
        }

        call.resolve(["status": NSNull()])
    }

    @objc func showUpdatePrompt(_ call: CAPPluginCall) {
        let promptOptions = PromptOptions(call: call, defaults: runtimeOptions)
        showUpdatePrompt(promptOptions: promptOptions, force: true) { shown, opened in
            call.resolve([
                "shown": shown,
                "openedUpdatePage": opened
            ])
        }
    }

    @objc func openUpdatePage(_ call: CAPPluginCall) {
        let url = resolveUpdateUrl(explicitUpdateUrl: call.getString("updateUrl"), status: lastStatus)

        openUpdatePageInternal(url: url) { opened in
            call.resolve([
                "opened": opened,
                "url": url
            ])
        }
    }

    private func runCheck(
        source: String,
        options: RuntimeOptions,
        forcePrompt: Bool,
        completion: (([String: Any]) -> Void)?
    ) {
        let status = implementation.buildStatus(
            source: source,
            latestVersion: options.latestVersion,
            minimumMajorVersion: options.minimumMajorVersion,
            updateUrl: options.updateUrl
        )

        lastStatus = status

        DispatchQueue.main.async { [weak self] in
            guard let self else {
                completion?(status)
                return
            }

            self.notifyStatus(status)
            self.maybeAutoPrompt(status: status, options: options, forcePrompt: forcePrompt)
            completion?(status)
        }
    }

    private func notifyStatus(_ status: [String: Any]) {
        notifyListeners("statusChanged", data: status)

        guard let state = status["state"] as? String else { return }
        if state == "latest" {
            notifyListeners("webViewLatest", data: status)
        } else if state == "outdated" {
            notifyListeners("webViewOutdated", data: status)
        }
    }

    private func maybeAutoPrompt(status: [String: Any], options: RuntimeOptions, forcePrompt: Bool) {
        guard let state = status["state"] as? String, state == "outdated" else {
            return
        }

        guard forcePrompt || options.autoPromptOnOutdated else {
            return
        }

        showUpdatePrompt(promptOptions: PromptOptions(defaults: options), force: forcePrompt, completion: nil)
    }

    private func showUpdatePrompt(
        promptOptions: PromptOptions,
        force: Bool,
        completion: ((Bool, Bool) -> Void)?
    ) {
        let resolvedUrl = resolveUpdateUrl(explicitUpdateUrl: promptOptions.updateUrl, status: lastStatus)

        let fingerprint = implementation.promptFingerprint(status: lastStatus, updateUrl: resolvedUrl)
        if !force, fingerprint == lastPromptFingerprint {
            completion?(false, false)
            return
        }

        lastPromptFingerprint = fingerprint

        let title = promptOptions.title ?? "WebView update available"
        let message = promptOptions.message ?? implementation.defaultPromptMessage(status: lastStatus)
        let updateButtonText = promptOptions.updateButtonText ?? "Update"
        let cancelButtonText = promptOptions.cancelButtonText ?? "Later"

        DispatchQueue.main.async {
            guard let presenter = self.topViewController(from: self.bridge?.viewController) else {
                completion?(false, false)
                return
            }

            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: cancelButtonText, style: .cancel) { _ in
                completion?(true, false)
            })
            alert.addAction(UIAlertAction(title: updateButtonText, style: .default) { _ in
                self.openUpdatePageInternal(url: resolvedUrl) { opened in
                    completion?(true, opened)
                }
            })

            presenter.present(alert, animated: true)
        }
    }

    private func monitoringStatePayload() -> [String: Any] {
        [
            "monitoring": monitoringEnabled,
            "checkOnResume": checkOnResume,
            "autoPromptOnOutdated": runtimeOptions.autoPromptOnOutdated
        ]
    }

    private func resolveUpdateUrl(explicitUpdateUrl: String?, status: [String: Any]?) -> String {
        if let explicitUpdateUrl, !explicitUpdateUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return explicitUpdateUrl
        }

        if let statusUpdateUrl = status?["updateUrl"] as? String,
           !statusUpdateUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return statusUpdateUrl
        }

        return implementation.normalizedUpdateUrl(runtimeOptions.updateUrl)
    }

    private func openUpdatePageInternal(url: String, completion: @escaping (Bool) -> Void) {
        guard let parsedUrl = URL(string: url) else {
            completion(false)
            return
        }

        DispatchQueue.main.async {
            guard UIApplication.shared.canOpenURL(parsedUrl) else {
                completion(false)
                return
            }

            UIApplication.shared.open(parsedUrl, options: [:]) { opened in
                completion(opened)
            }
        }
    }

    private func topViewController(from controller: UIViewController?) -> UIViewController? {
        if let navigationController = controller as? UINavigationController {
            return topViewController(from: navigationController.visibleViewController)
        }

        if let tabController = controller as? UITabBarController,
           let selected = tabController.selectedViewController {
            return topViewController(from: selected)
        }

        if let presented = controller?.presentedViewController {
            return topViewController(from: presented)
        }

        return controller
    }
}

private struct PromptOptions {
    var title: String?
    var message: String?
    var updateButtonText: String?
    var cancelButtonText: String?
    var updateUrl: String?

    init(defaults: RuntimeOptions) {
        title = defaults.promptTitle
        message = defaults.promptMessage
        updateButtonText = defaults.promptUpdateButtonText
        cancelButtonText = defaults.promptCancelButtonText
        updateUrl = defaults.updateUrl
    }

    init(call: CAPPluginCall, defaults: RuntimeOptions) {
        self.init(defaults: defaults)

        if Self.hasValue(call, key: "title") {
            title = call.getString("title")
        }
        if Self.hasValue(call, key: "message") {
            message = call.getString("message")
        }
        if Self.hasValue(call, key: "updateButtonText") {
            updateButtonText = call.getString("updateButtonText")
        }
        if Self.hasValue(call, key: "cancelButtonText") {
            cancelButtonText = call.getString("cancelButtonText")
        }
        if Self.hasValue(call, key: "updateUrl") {
            updateUrl = call.getString("updateUrl")
        }
    }

    private static func hasValue(_ call: CAPPluginCall, key: String) -> Bool {
        guard let value = call.options[key] else {
            return false
        }
        return !(value is NSNull)
    }
}

private struct RuntimeOptions {
    var autoCheckOnLoad = true
    var autoCheckOnResume = true
    var autoPromptOnOutdated = false
    var showPromptOnOutdated = false

    var latestVersion: String?
    var minimumMajorVersion: Int?
    var latestVersionApiUrl: String?
    var updateUrl: String?

    var promptTitle: String?
    var promptMessage: String?
    var promptUpdateButtonText: String?
    var promptCancelButtonText: String?

    init() {}

    init(config: PluginConfig) {
        autoCheckOnLoad = config.getBoolean("autoCheckOnLoad", true)
        autoCheckOnResume = config.getBoolean("autoCheckOnResume", true)
        autoPromptOnOutdated = config.getBoolean("autoPromptOnOutdated", false)
        latestVersion = trimToNil(config.getString("latestVersion"))
        minimumMajorVersion = config.getConfigJSON()["minimumMajorVersion"] as? Int
        latestVersionApiUrl = trimToNil(config.getString("latestVersionApiUrl"))
        updateUrl = trimToNil(config.getString("updateUrl"))
        promptTitle = trimToNil(config.getString("promptTitle"))
        promptMessage = trimToNil(config.getString("promptMessage"))
        promptUpdateButtonText = trimToNil(config.getString("promptUpdateButtonText"))
        promptCancelButtonText = trimToNil(config.getString("promptCancelButtonText"))
    }

    func merged(with call: CAPPluginCall) -> RuntimeOptions {
        var out = self

        if Self.hasValue(call, key: "autoCheckOnLoad") {
            out.autoCheckOnLoad = call.getBool("autoCheckOnLoad", out.autoCheckOnLoad)
        }
        if Self.hasValue(call, key: "autoCheckOnResume") {
            out.autoCheckOnResume = call.getBool("autoCheckOnResume", out.autoCheckOnResume)
        }
        if Self.hasValue(call, key: "autoPromptOnOutdated") {
            out.autoPromptOnOutdated = call.getBool("autoPromptOnOutdated", out.autoPromptOnOutdated)
        }
        if Self.hasValue(call, key: "showPromptOnOutdated") {
            out.showPromptOnOutdated = call.getBool("showPromptOnOutdated", out.showPromptOnOutdated)
        }

        if Self.hasValue(call, key: "latestVersion") {
            out.latestVersion = trimToNil(call.getString("latestVersion"))
        }
        if Self.hasValue(call, key: "minimumMajorVersion") {
            out.minimumMajorVersion = call.getInt("minimumMajorVersion")
        }
        if Self.hasValue(call, key: "latestVersionApiUrl") {
            out.latestVersionApiUrl = trimToNil(call.getString("latestVersionApiUrl"))
        }
        if Self.hasValue(call, key: "updateUrl") {
            out.updateUrl = trimToNil(call.getString("updateUrl"))
        }

        if Self.hasValue(call, key: "promptTitle") {
            out.promptTitle = trimToNil(call.getString("promptTitle"))
        }
        if Self.hasValue(call, key: "promptMessage") {
            out.promptMessage = trimToNil(call.getString("promptMessage"))
        }
        if Self.hasValue(call, key: "promptUpdateButtonText") {
            out.promptUpdateButtonText = trimToNil(call.getString("promptUpdateButtonText"))
        }
        if Self.hasValue(call, key: "promptCancelButtonText") {
            out.promptCancelButtonText = trimToNil(call.getString("promptCancelButtonText"))
        }

        return out
    }

    private static func hasValue(_ call: CAPPluginCall, key: String) -> Bool {
        guard let value = call.options[key] else {
            return false
        }
        return !(value is NSNull)
    }

    private func trimToNil(_ value: String?) -> String? {
        guard let value else {
            return nil
        }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
