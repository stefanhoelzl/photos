import XCTest

/// Taps system alerts for an iOS scenario, which drives the app over HTTP and has no hands.
///
/// iOS asks before an app may delete library assets (§8), and on the simulator it can ask for full
/// photo access again mid-upload. Neither `simctl` nor the control server can answer an alert, so
/// a scenario starts this test beside itself with `xcodebuild test-without-building` and carries on.
///
/// It looks for the named buttons on Springboard and on the app, taps each one it finds, and returns
/// once the button named last has been tapped. It launches nothing: the app under test is the
/// scenario's, installed and launched by the scenario.
///
///   TEST_RUNNER_PHOTOS_TAP         labels to tap, comma-separated; the last one ends the test
///   TEST_RUNNER_PHOTOS_TAP_SECONDS how long to wait for it (default 300)
final class SystemAlerts: XCTestCase {

    func testTapAlerts() throws {
        let environment = ProcessInfo.processInfo.environment
        let labels = (environment["PHOTOS_TAP"] ?? "Delete").split(separator: ",").map(String.init)
        let last = try XCTUnwrap(labels.last)
        let seconds = Double(environment["PHOTOS_TAP_SECONDS"] ?? "") ?? 300
        let owners = [
            XCUIApplication(bundleIdentifier: "com.apple.springboard"),
            XCUIApplication(bundleIdentifier: "net.stho.photos"),
        ]
        // The scenario waits for this line before it goes on, so no alert can come too early.
        print("PHOTOS_TAPPER_READY", labels)

        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            for owner in owners where owner.state != .notRunning {
                for label in labels {
                    let button = owner.buttons[label]
                    guard button.exists, button.isHittable else { continue }
                    button.tap()
                    print("PHOTOS_TAPPED", label)
                    if label == last { return }
                }
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTFail("no \"\(last)\" button appeared within \(Int(seconds)) s")
    }
}
