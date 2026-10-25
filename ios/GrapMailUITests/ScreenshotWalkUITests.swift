import XCTest

/// The live signup walk on a real iOS simulator — the same walk the Android
/// CI runs. Creates a real account against the live API and screenshots
/// every screen into the result bundle (kept via XCTAttachment).
/// Assertions are soft: whatever the screen shows at each stage gets
/// captured, so the artifact set is complete even if a step stalls.
final class ScreenshotWalkUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = true
    }

    private var app: XCUIApplication!

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func typeIntoTextField(_ identifier: String, _ value: String) {
        let field = app.textFields[identifier].firstMatch
        if field.waitForExistence(timeout: 4) {
            field.tap()
            field.typeText(value)
            if app.keyboards.buttons["return"].exists { app.keyboards.buttons["return"].tap() }
        }
    }

    func testSignupWalkScreenshots() {
        app = XCUIApplication()
        app.launch()

        // Welcome
        _ = app.staticTexts["Welcome to"].waitForExistence(timeout: 10)
        snap("01-welcome-light")

        // Step 1 — name
        let create = app.buttons["Create Account"].firstMatch
        if create.waitForExistence(timeout: 6) { create.tap() }
        typeIntoTextField("Enter your first name", "Arafat")
        typeIntoTextField("Enter your last name", "Hossain")
        snap("02-step1-name-light")
        let next = app.buttons["Next"].firstMatch
        if next.waitForExistence(timeout: 4) { next.tap() }

        // Step 2 — basic info: menu pickers + gender chip
        _ = app.staticTexts["Basic Info"].waitForExistence(timeout: 6)
        pickInline("Month", "April")
        pickInline("Day", "1")   // top of the list — always hittable (Apr 1 2013 is 13+)
        pickInline("Year", "2013")
        let female = app.buttons["Female"].firstMatch
        if female.waitForExistence(timeout: 3) { female.tap() }
        snap("03-step2-basicinfo-light")
        let next2 = app.buttons["Next"].firstMatch
        if next2.waitForExistence(timeout: 4) { next2.tap() }

        // Step 3 — address (custom unique handle)
        typeIntoTextField("yourname", "e2eios" + String(Int(Date().timeIntervalSince1970) % 1000000))
        sleep(3) // live availability check
        snap("04-step3-address-light")
        let next3 = app.buttons["Next"].firstMatch
        if next3.waitForExistence(timeout: 4) { next3.tap() }

        // Step 4 — password
        typeIntoSecureField("Password", "Grapmail#e2e2026")
        typeIntoSecureField("Confirm password", "Grapmail#e2e2026")
        snap("05-step4-password-light")
        let next4 = app.buttons["Next"].firstMatch
        if next4.waitForExistence(timeout: 4) { next4.tap() }

        // Step 5 — terms: scroll the document, then agree
        let termsCard = app.staticTexts["1.  Your Account"]
        if termsCard.waitForExistence(timeout: 5) {
            termsCard.swipeUp(velocity: .fast)
            termsCard.swipeUp(velocity: .fast)
        }
        let agree = app.staticTexts["I agree to the "].firstMatch
        if agree.exists { agree.tap() }
        snap("06-step5-terms-light")
        let createAccount = app.buttons["Create Account"].firstMatch
        if createAccount.waitForExistence(timeout: 4) { createAccount.tap() }

        // Human verification
        _ = app.staticTexts["Just one more step!"].waitForExistence(timeout: 20)
        snap("07-human-verify-light")
        let skip = app.staticTexts["Skip for now"].firstMatch
        if skip.waitForExistence(timeout: 5) { skip.tap() }

        // Account created (A) then (B)
        _ = app.staticTexts["You're all set!"].waitForExistence(timeout: 12)
        snap("08-created-a-light")
        let cont = app.buttons["Continue to Onboarding"].firstMatch
        if cont.waitForExistence(timeout: 5) { cont.tap() }
        sleep(2)
        snap("09-created-b-light")
    }

    func testSignInScreenScreenshot() {
        app = XCUIApplication()
        app.launch()
        let signIn = app.buttons["Sign In"].firstMatch
        if signIn.waitForExistence(timeout: 8) { signIn.tap() }
        _ = app.staticTexts["Welcome back 👋"].waitForExistence(timeout: 6)
        snap("10-signin-light")
    }

    /// Inline picker: tap the collapsed row, then the option rendered in-window.
    /// Coordinate taps bypass hittability checks (panel items can be clipped).
    private func pickInline(_ placeholder: String, _ option: String) {
        let row = app.buttons[placeholder].firstMatch
        guard row.waitForExistence(timeout: 4) else { return }
        row.tap()
        let opt = app.buttons[option].firstMatch
        if opt.waitForExistence(timeout: 4) {
            if opt.isHittable { opt.tap() } else {
                opt.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            }
        }
    }

    private func typeIntoSecureField(_ identifier: String, _ value: String) {
        let sf = app.secureTextFields[identifier].firstMatch
        if sf.waitForExistence(timeout: 4) {
            sf.tap()
            sf.typeText(value)
        }
    }
}
