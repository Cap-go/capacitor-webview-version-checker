import XCTest
@testable import WebviewVersionCheckerPlugin

class WebviewVersionCheckerPluginTests: XCTestCase {
    func testBuildStatusWithMinimumMajorVersion() {
        let implementation = WebviewVersionChecker()
        let status = implementation.buildStatus(source: "unit-test", latestVersion: nil, minimumMajorVersion: 1, updateUrl: nil)

        XCTAssertEqual(status["platform"] as? String, "ios")
        XCTAssertEqual(status["state"] as? String, "latest")
        XCTAssertEqual(status["isLatest"] as? Bool, true)
        XCTAssertNotNil(status["currentVersion"] as? String)
    }

    func testBuildStatusWithoutPolicyIsUnknown() {
        let implementation = WebviewVersionChecker()
        let status = implementation.buildStatus(source: "unit-test", latestVersion: nil, minimumMajorVersion: nil, updateUrl: nil)

        XCTAssertEqual(status["state"] as? String, "unknown")
        XCTAssertEqual(status["isLatest"] as? Bool, false)
    }
}
