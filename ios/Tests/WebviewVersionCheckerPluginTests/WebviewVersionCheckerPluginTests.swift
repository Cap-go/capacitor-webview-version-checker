import XCTest
@testable import WebviewVersionCheckerPlugin

class WebviewVersionCheckerPluginTests: XCTestCase {
    func testBuildStatusIgnoresMinimumMajorVersionOnIOS() {
        let implementation = WebviewVersionChecker()
        let status = implementation.buildStatus(source: "unit-test", latestVersion: nil, minimumMajorVersion: 140, updateUrl: nil)

        XCTAssertEqual(status["platform"] as? String, "ios")
        XCTAssertEqual(status["state"] as? String, "unknown")
        XCTAssertEqual(status["isLatest"] as? Bool, false)
        XCTAssertNotNil(status["currentVersion"] as? String)
    }

    func testBuildStatusWithLatestVersion() {
        let implementation = WebviewVersionChecker()
        let status = implementation.buildStatus(source: "unit-test", latestVersion: "1.0", minimumMajorVersion: nil, updateUrl: nil)

        XCTAssertEqual(status["state"] as? String, "latest")
        XCTAssertEqual(status["isLatest"] as? Bool, true)
    }

    func testBuildStatusWithoutPolicyIsUnknown() {
        let implementation = WebviewVersionChecker()
        let status = implementation.buildStatus(source: "unit-test", latestVersion: nil, minimumMajorVersion: nil, updateUrl: nil)

        XCTAssertEqual(status["state"] as? String, "unknown")
        XCTAssertEqual(status["isLatest"] as? Bool, false)
    }
}
