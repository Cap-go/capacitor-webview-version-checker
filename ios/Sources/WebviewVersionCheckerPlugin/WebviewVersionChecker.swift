import Foundation
import UIKit

private let defaultIOSUpdateUrl = "https://support.apple.com/guide/iphone/update-ios-iph3e504502/ios"

@objc public class WebviewVersionChecker: NSObject {
    private let isoFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    func buildStatus(source: String, latestVersion: String?, minimumMajorVersion: Int?, updateUrl: String?) -> [String: Any] {
        let currentVersion = UIDevice.current.systemVersion
        let currentMajorVersion = parseMajorVersion(currentVersion)
        let normalizedLatestVersion = normalizeVersion(latestVersion)
        let latestMajorVersion = parseMajorVersion(normalizedLatestVersion)

        var status: [String: Any] = [
            "platform": "ios",
            "source": source,
            "checkedAt": isoFormatter.string(from: Date()),
            "currentVersion": currentVersion,
            "updateUrl": normalizedUpdateUrl(updateUrl)
        ]

        if let currentMajorVersion {
            status["currentMajorVersion"] = currentMajorVersion
        }

        if let normalizedLatestVersion {
            status["latestVersion"] = normalizedLatestVersion
        }

        if let latestMajorVersion {
            status["latestMajorVersion"] = latestMajorVersion
        }

        if let normalizedLatestVersion {
            let cmp = compareVersions(currentVersion, normalizedLatestVersion)
            let isLatest = cmp >= 0
            status["state"] = isLatest ? "latest" : "outdated"
            status["isLatest"] = isLatest
            status["reason"] = isLatest
                ? "Installed iOS WebKit version is at or above the requested latest version."
                : "Installed iOS WebKit version is behind the requested latest version."
            return status
        }

        if minimumMajorVersion != nil {
            status["state"] = "unknown"
            status["isLatest"] = false
            status["reason"] = "minimumMajorVersion applies to Android WebView only. Configure latestVersion on iOS to enforce a policy."
            return status
        }

        status["state"] = "unknown"
        status["isLatest"] = false
        status["reason"] = "iOS WebKit updates are tied to iOS updates. Configure latestVersion to enforce a policy."
        return status
    }

    func defaultPromptMessage(status: [String: Any]?) -> String {
        guard let status else {
            return "A newer iOS WebKit version may be available. Please update iOS for best compatibility."
        }

        let current = (status["currentVersion"] as? String) ?? "current version"
        let latest = (status["latestVersion"] as? String) ?? "the latest available version"

        return "Your iOS WebKit version (\(current)) may be outdated. Please update toward \(latest)."
    }

    func normalizedUpdateUrl(_ value: String?) -> String {
        let trimmed = normalizeVersion(value)
        return trimmed ?? defaultIOSUpdateUrl
    }

    func promptFingerprint(status: [String: Any]?, updateUrl: String) -> String {
        guard let status else {
            return updateUrl
        }

        let current = (status["currentVersion"] as? String) ?? ""
        let latest = (status["latestVersion"] as? String) ?? ""
        return "\(current)|\(latest)|\(updateUrl)"
    }

    private func parseMajorVersion(_ value: String?) -> Int? {
        guard let value, !value.isEmpty else {
            return nil
        }

        var digits = ""
        for character in value {
            if character.isNumber {
                digits.append(character)
            } else if !digits.isEmpty {
                break
            }
        }

        return Int(digits)
    }

    private func normalizeVersion(_ value: String?) -> String? {
        guard let value else {
            return nil
        }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func compareVersions(_ left: String, _ right: String) -> Int {
        let leftParts = tokenize(left)
        let rightParts = tokenize(right)
        let maxCount = max(leftParts.count, rightParts.count)

        for index in 0..<maxCount {
            let a = index < leftParts.count ? leftParts[index] : 0
            let b = index < rightParts.count ? rightParts[index] : 0
            if a > b {
                return 1
            }
            if a < b {
                return -1
            }
        }

        return 0
    }

    private func tokenize(_ value: String) -> [Int] {
        value
            .split(whereSeparator: { !$0.isNumber })
            .compactMap { Int($0) }
    }
}
