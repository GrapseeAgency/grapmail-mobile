import XCTest

/// The live signup walk on a real iOS simulator — the same walk the Android
/// CI runs. Creates a real account against the live API and screenshots
/// every screen into the result bundle (kept via XCTAttachment).
final class ScreenshotWalkUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    private func snap(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func type(into element: XCUIElement, _ value: String) {
        element.tap()
        element.typeText(value)
        if app.keyboards.buttons["return"].exists {
            app.keyboards.buttons["return"].tap()
        }
    }

    private var app: XCUIApplication!

    func testSignupWalkScreenshots() {
        app = XCUIApplication()
        app.launch()

        // Welcome
        XCTAssertTrue(app.staticTexts["Better Email."].waitForExistence(timeout: 8))
        snap(app, "01-welcome-light")

        // Step 1 — name
        app.buttons["Create Account"].firstMatch.tap()
        let first = app.textFields["First Name"]
        XCTAssertTrue(first.waitForExistence(timeout: 6))
        type(into: first, "Arafat")
        type(into: app.textFields["Last Name"], "Hossain")
        snap(app, "02-step1-name-light")
        app.buttons["Continue"].firstMatch.tap()

        // Step 2 — basic info
        let birthday = app.textFields["Birthday"]
        XCTAssertTrue(birthday.waitForExistence(timeout: 6))
        type(into: birthday, "04121998")
        app.staticTexts["Female"].tap()
        snap(app, "03-step2-basicinfo-light")
        app.buttons["Continue"].firstMatch.tap()

        // Step 3 — address (custom, unique handle)
        let customButton = app.buttons["Create your own"]
        if customButton.waitForExistence(timeout: 6) { customButton.tap() }
        let handle = "e2eios" + String(Int(Date().timeIntervalSince1970) % 1000000)
        let custom = app.textFields["Custom address"]
        XCTAssertTrue(custom.waitForExistence(timeout: 6))
        type(into: custom, handle)
        sleep(3) // live availability check
        snap(app, "04-step3-address-light")
        app.buttons["Continue"].firstMatch.tap()

        // Step 4 — password
        let pw = app.secureTextFields["Password"].firstMatch
        if !pw.exists { app.textFields["Password"].firstMatch.tap() }
        XCTAssertTrue(pw.waitForExistence(timeout: 6))
        type(into: pw, "Grapmail#e2e2026")
        let confirm = app.secureTextFields["Confirm password"].firstMatch
        type(into: confirm, "Grapmail#e2e2026")
        snap(app, "05-step4-password-light")
        app.buttons["Continue"].firstMatch.tap()

        // Step 5 — terms (real account creation follows)
        let agree = app.switches["I have read and agree to the Terms of Service and the Privacy Policy"]
        if !agree.waitForExistence(timeout: 6) {
            app.staticTexts["I have read and agree to the Terms of Service and the Privacy Policy"].tap()
        } else {
            agree.tap()
        }
        snap(app, "06-step5-terms-light")
        app.buttons["Create Account"].firstMatch.tap()

        // Human verification
        XCTAssertTrue(app.staticTexts["Human Verification"].waitForExistence(timeout: 20))
        snap(app, "07-human-verify-light")
        app.staticTexts["Skip for now →"].tap()

        // Account created (A) then (B)
        XCTAssertTrue(app.staticTexts["You're all set!"].waitForExistence(timeout: 10))
        snap(app, "08-created-a-light")
        app.buttons["Continue to Onboarding"].firstMatch.tap()
        sleep(2)
        snap(app, "09-created-b-light")
    }

    func testSignInScreenScreenshot() {
        app = XCUIApplication()
        app.launch()
        let signIn = app.buttons["Sign In"].firstMatch
        XCTAssertTrue(signIn.waitForExistence(timeout: 8))
        signIn.tap()
        XCTAssertTrue(app.textFields["Email or phone number"].waitForExistence(timeout: 6))
        snap(app, "10-signin-light")
    }
}
