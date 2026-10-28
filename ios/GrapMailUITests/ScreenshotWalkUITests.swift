import XCTest
import Foundation

/// The live signup walk on a real iOS simulator — the same walk the Android
/// CI runs. Creates a real account against the live API and screenshots
/// every screen into the result bundle (kept via XCTAttachment).
/// Assertions are HARD (continueAfterFailure = false): a screen that fails
/// to render or a control that never appears fails the run — the v1 test
/// soft-skipped every step and would pass green on a dead build.
final class ScreenshotWalkUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    private var app: XCUIApplication!

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Wait for an element and fail with its name if it never appears.
    /// On failure, dump the visible static texts and fields first — CI logs
    /// are the only place to see WHICH screen the walk actually reached
    /// (the 'missing: field yourname' failure was undiagnosable from the
    /// bare assertion alone).
    @discardableResult
    private func require(_ element: XCUIElement, _ what: String, timeout: TimeInterval = 6) -> XCUIElement {
        if !element.waitForExistence(timeout: timeout) {
            let texts = app.staticTexts.allElementsBoundByIndex.map { $0.label }
            let fields = app.textFields.allElementsBoundByIndex.map { $0.label }
            let secure = app.secureTextFields.allElementsBoundByIndex.count
            let buttons = app.buttons.allElementsBoundByIndex.map { $0.label }
            print("WALK-FAIL \(what) | texts: \(texts) | fields: \(fields) | secureCount: \(secure) | buttons: \(buttons)")
            snap("fail-\(what.replacingOccurrences(of: " ", with: "-"))")
        }
        XCTAssertTrue(element.waitForExistence(timeout: 1), "missing: \(what)")
        return element
    }

    private func tap(_ element: XCUIElement, _ what: String, timeout: TimeInterval = 6) {
        require(element, what, timeout: timeout).tap()
    }

    private func typeIntoTextField(_ identifier: String, _ value: String) {
        let field = app.textFields[identifier].firstMatch
        tap(field, "field \(identifier)")
        field.typeText(value)
        if app.keyboards.buttons["return"].exists { app.keyboards.buttons["return"].tap() }
        // Terminal fields close the keyboard on return themselves; if it
        // somehow stays up, resign focus on an inert tap before returning.
        inertDismiss()
    }

    /// Last-resort keyboard resignation that never touches a control: the
    /// CREATE ACCOUNT header band is inert static text on every wizard step,
    /// so tapping it can't advance the walk or toggle anything the way a
    /// blind 'Next' tap under the keyboard did ('missing: field yourname').
    private func inertDismiss() {
        guard app.keyboards.count > 0 else { return }
        tap(app.staticTexts["CREATE ACCOUNT"].firstMatch, "inert header band (keyboard dismiss)", timeout: 3)
        let deadline = Date().addingTimeInterval(2)
        while app.keyboards.count > 0 && Date() < deadline {
            usleep(100_000)
        }
    }

    func testSignupWalkScreenshots() {
        // One linear test on purpose: XCTest runs methods alphabetically, so
        // separate walk / restore / sign-in methods would execute out of
        // order (restore before signup). This mirrors the Android walk script.
        app = XCUIApplication()
        app.launch()

        // Welcome
        tap(app.buttons["Create Account"].firstMatch, "Create Account")
        typeIntoTextField("Enter your first name", "Arafat")
        typeIntoTextField("Enter your last name", "Hossain")
        snap("02-step1-name-light")
        tap(app.buttons["Next"].firstMatch, "Next (step 1)")

        // Step 2 — basic info: menu pickers + gender chip
        require(app.staticTexts["Basic Info"].firstMatch, "Step 2 header")
        // All three picks are index 1 — the panel's first item — at one shared
        // offset: January 1, 2013 is a valid 13-year-old date. pickInline
        // asserts the row label changed: a missed geometry tap used to leave
        // the birthday empty, Next silently disabled (GradientButton guards
        // its action), and the walk died two steps later on 'yourname'.
        pickInline("Month", "January")
        pickInline("Day", "1")
        pickInline("Year", "2013")
        tap(app.buttons["Female"].firstMatch, "Female chip")
        snap("03-step2-basicinfo-light")
        tap(app.buttons["Next"].firstMatch, "Next (step 2)")

        // Step 3 — address (custom unique handle)
        require(app.staticTexts["Choose your GrapMail address"].firstMatch, "Step 3 header", timeout: 10)
        typeIntoTextField("yourname", "e2eios" + String(Int(Date().timeIntervalSince1970) % 1000000))
        sleep(3) // live availability check
        snap("04-step3-address-light")
        tap(app.buttons["Next"].firstMatch, "Next (step 3)", timeout: 10)

        // Step 4 — password. SecureField has no onSubmit hook, so the
        // keyboard can't be closed from the app side here; resign focus on
        // the inert 'Password *' label before reaching for 'Next'.
        typeIntoSecureField("Create a strong password", "Grapmail#e2e2026")
        typeIntoSecureField("Re-enter your password", "Grapmail#e2e2026")
        inertDismiss()
        snap("05-step4-password-light")
        tap(app.buttons["Next"].firstMatch, "Next (step 4)")

        // Step 5 — terms: the gate is REAL scroll detection. The document is
        // a fixed 300pt card with its OWN inner ScrollView. Swiping the text
        // element or dragging a coordinate inside the card scrolls the OUTER
        // WizardScaffold page instead of the inner doc, so scrolledToEnd
        // never fires and the agree checkbox stays disabled. The reliable
        // primitive is XCUIElement.swipeUp() on the inner ScrollView itself —
        // identified as the only scroll view about 300pt tall (the outer page
        // fills the screen). Repeat until the gate opens.
        let docHeader = app.staticTexts["GrapMail Terms of Service"].firstMatch
        require(docHeader, "Terms document", timeout: 8)
        let docScroll = app.scrollViews.allElementsBoundByIndex.first { $0.frame.height < 340 }
        XCTAssertNotNil(docScroll, "terms document inner ScrollView not found")
        let create = app.buttons["Create Account"].firstMatch
        require(create, "Create Account (terms)", timeout: 6)
        for _ in 1..<8 {
            if create.isEnabled { break }
            docScroll?.swipeUp(velocity: .slow)
            usleep(500_000)
        }
        sleep(1)
        // The agree line is a CONCATENATED Text ("I agree to the " +
        // "Terms of Service" + " and " + "Privacy Policy"), which SwiftUI
        // exposes as ONE static text whose label is the whole sentence - so
        // an exact "I agree to the " query never matched (the 'missing: Agree
        // card' failure). Match on the stable prefix.
        let agree = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "I agree to the")).firstMatch
        tap(agree, "Agree card")
        // The tap only toggles once scrolledToEnd enabled the checkbox; prove
        // the gate opened by the Create Account button becoming enabled before
        // tapping it, so a stuck scroll fails HERE with a clear reason rather
        // than two steps later as 'missing: Human verification'.
        XCTAssertTrue(create.waitForExistence(timeout: 4) && create.isEnabled,
                      "Create Account still disabled — the agree gate never opened (scrolledToEnd false)")
        snap("06-step5-terms-light")
        create.tap()

        // Human verification — proof the whole gated flow above worked
        require(app.staticTexts["Just one more step!"].firstMatch, "Human verification", timeout: 25)
        snap("07-human-verify-light")
        tap(app.staticTexts["Skip for now"].firstMatch, "Skip for now", timeout: 8)

        // Account created (A) then (B)
        require(app.staticTexts["You're all set!"].firstMatch, "Account created (A)", timeout: 15)
        snap("08-created-a-light")
        tap(app.buttons["Continue to Onboarding"].firstMatch, "Continue to Onboarding")
        sleep(2)
        snap("09-created-b-light")
        tap(app.buttons["Go to Mailbox"].firstMatch, "Go to Mailbox")
        require(app.staticTexts["Signed in"].firstMatch, "Signed-in home", timeout: 8)

        // Security sector — the reference screens photographed in the app's
        // light theme. Read-only: the walk opens every hub row, snapshots it,
        // and backs out. It never submits a password change, never revokes a
        // session, never signs the fresh account out of everything — those
        // flows have their own screens and their own confirmations, and this
        // account has to survive to the session-restore leg below.
        tap(app.buttons["Account security"].firstMatch, "Account security (home)")
        require(app.staticTexts["Account Security"].firstMatch, "Security hub", timeout: 8)
        snap("20-sec-hub-light")
        openSecurityRow("Two-Step Verification", "21-sec-2fa-enable-light")
        openSecurityRow("Change Password", "22-sec-change-password-light")
        openSecurityRow("Backup Codes", "23-sec-backup-codes-light")
        openSecurityRow("Active Sessions", "24-sec-sessions-light")
        openSecurityRow("Recent Security Activity", "25-sec-activity-light")
        openSecurityRow("Sign Out All Devices", "26-sec-signout-all-light")
        backFromSecurityScreens()

        // Session restore: relaunch with the stored Keychain token must open
        // signed-in, not at Welcome. Then sign out returns to Welcome.
        app.terminate()
        app.launch()
        require(app.staticTexts["Signed in"].firstMatch, "session restored on launch", timeout: 8)
        snap("10-session-restored")
        tap(app.buttons["Sign out"].firstMatch, "Sign out")
        require(app.staticTexts["Welcome to"].firstMatch, "welcome after sign-out", timeout: 8)

        // Sign-in screen
        tap(app.buttons["Sign In"].firstMatch, "Sign In (welcome)")
        require(app.staticTexts["Welcome back 👋"].firstMatch, "Sign-in header")
        snap("11-signin-light")
    }

    /// Open an inline picker and choose `option` (always the panel's first
    /// item: January / 1 / 2013 = a valid 13-year-old date). Tap the option
    /// ELEMENT, not a measured coordinate: the option lives in a clipped
    /// animated ScrollView, and a coordinate tap taken from `opt.frame`
    /// landed just outside the clip and never selected (the walk stalled at
    /// step 2 with an empty birthday, disabling 'Next'). XCUITest's element
    /// tap hits the option's real center and closes the panel.
    ///
    /// The pick is NOT verified by re-reading the row: selecting an option
    /// changes the row's label away from `placeholder`, which makes the
    /// `buttons[placeholder]` query resolve to zero, and reading `.label` on
    /// it throws 'No matches found for ... IN identifiers' (that throw, not a
    /// real UI bug, failed the walk). Instead the whole birthday is proven
    /// valid downstream: 'Next (step 2)' only enables when it is, and the
    /// Step 3 header the walk requires right after is the pass/fail gate.
    private func pickInline(_ placeholder: String, _ option: String) {
        app.swipeDown() // page to top — stable frames, no drift between taps
        sleep(1)
        let row = app.buttons[placeholder].firstMatch
        tap(row, "picker \(placeholder)")
        sleep(1) // panel expansion settles
        let opt = app.buttons[option].firstMatch
        // If the panel didn't open on the first row tap, tap once more.
        if !opt.waitForExistence(timeout: 3) { tap(row, "re-open \(placeholder)") }
        require(opt, "option \(option) in \(placeholder) panel", timeout: 4).tap()
        sleep(1) // panel collapse settles before the next picker opens
    }

    /// Open one Account Security hub row, snapshot the screen it lands on,
    /// and return to the hub. Plain rows are buttons whose label carries the
    /// title; the Two-Step row is a switch (app.switches, identified by the
    /// row title) — a tap there fires onToggle, which routes into the enable
    /// flow exactly like the reference.
    private func openSecurityRow(_ title: String, _ shotName: String) {
        let row = app.buttons.matching(NSPredicate(
            format: "label CONTAINS %@", title)).firstMatch
        if row.waitForExistence(timeout: 4) {
            row.tap()
        } else {
            tap(app.switches[title].firstMatch, "security switch \(title)")
        }
        sleep(1) // the screen's own load (profile fetch) settles
        snap(shotName)
        tap(app.buttons["back"].firstMatch, "back from \(title)")
        sleep(1)
    }

    /// The hub sits under a NavigationStack path the walk appended; back out
    /// of it so the session-restore leg starts from the signed-in home.
    private func backFromSecurityScreens() {
        require(app.staticTexts["Account Security"].firstMatch, "Security hub (return)", timeout: 8)
        tap(app.buttons["back"].firstMatch, "back (hub → home)")
        require(app.buttons["Account security"].firstMatch, "Signed-in home (return)", timeout: 8)
    }

    private func typeIntoSecureField(_ identifier: String, _ value: String) {
        let sf = app.secureTextFields[identifier].firstMatch
        tap(sf, "secure field \(identifier)")
        sf.typeText(value)
    }
}
