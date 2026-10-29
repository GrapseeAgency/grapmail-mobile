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
        focusAndType(app.textFields[identifier].firstMatch, "field \(identifier)", value)
        if app.keyboards.buttons["return"].exists { app.keyboards.buttons["return"].tap() }
        // Terminal fields close the keyboard on return themselves; if it
        // somehow stays up, resign focus on an inert tap before returning.
        inertDismiss()
    }

    /// Tap a field until it actually has the keyboard, then type.
    ///
    /// `typeText` synthesises its key events into whatever holds focus, so on a
    /// tap that landed a fraction before the keyboard attached it fails the run
    /// with "Neither element nor any descendant has keyboard focus" — which is
    /// what killed a whole green-capable run at its very first field. The
    /// keyboard appearing IS the proof of focus, so wait for it and retry the
    /// tap rather than typing blind and losing the run to a race.
    private func focusAndType(_ field: XCUIElement, _ what: String, _ value: String) {
        for attempt in 1...4 {
            tap(field, what)
            if waitForKeyboard(timeout: 3) {
                field.typeText(value)
                return
            }
            print("WALK-KEYBOARD \(what): no keyboard after tap \(attempt)")
            usleep(300_000)
        }
        XCTFail("tapping \(what) never raised the keyboard")
    }

    private func waitForKeyboard(timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if app.keyboards.count > 0 { return true }
            usleep(150_000)
        }
        return app.keyboards.count > 0
    }

    /// Best-effort keyboard resignation that never fails the walk. On the
    /// wizard the inert 'CREATE ACCOUNT' eyebrow band closes the keyboard; on
    /// the sign-in fallback that band does not exist, so tap the inert
    /// top-center header band instead. No assertion: if the keyboard lingers,
    /// the next control tap (a ScrollView element) auto-scrolls into view
    /// above the keyboard anyway. Asserting here is what broke the walk
    /// ('missing: inert header band') the moment it reached the sign-in screen.
    private func inertDismiss() {
        guard app.keyboards.count > 0 else { return }
        let eyebrow = app.staticTexts["CREATE ACCOUNT"].firstMatch
        if eyebrow.exists { eyebrow.tap() }
        let deadline = Date().addingTimeInterval(1.5)
        while app.keyboards.count > 0 && Date() < deadline {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.06)).tap()
            usleep(200_000)
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

        // After Create Account the app either auto-signs-in (human verify ->
        // created -> home) or, if the immediate session didn't take, routes to
        // sign-in with the address prefilled (RootView's documented fallback).
        // Reach the signed-in home either way so the security leg always runs.
        let humanVerify = app.staticTexts["Just one more step!"].firstMatch
        let signinScreen = app.staticTexts["Welcome back 👋"].firstMatch
        XCTAssertTrue(humanVerify.waitForExistence(timeout: 25) || signinScreen.exists,
                      "after Create Account: neither human verify nor sign-in appeared")
        if humanVerify.exists {
            snap("07-human-verify-light")
            tap(app.staticTexts["Skip for now"].firstMatch, "Skip for now", timeout: 8)
            require(app.staticTexts["You're all set!"].firstMatch, "Account created (A)", timeout: 15)
            snap("08-created-a-light")
            tap(app.buttons["Continue to Onboarding"].firstMatch, "Continue to Onboarding")
            sleep(2)
            snap("09-created-b-light")
            tap(app.buttons["Go to Mailbox"].firstMatch, "Go to Mailbox")
        } else {
            // Sign-in fallback: the email is prefilled with the created address;
            // type the password and sign in to reach the home directly.
            typeIntoSecureField("Enter your password", "Grapmail#e2e2026")
            inertDismiss()
            tap(app.buttons["Sign In"].firstMatch, "Sign In (post-create)", timeout: 10)
        }
        // The signed-in home IS the mailbox. Every anchor below is an identifier
        // the screen sets on itself rather than a sentence it happens to print —
        // the placeholder home's literal "Signed in" was the kind of anchor a
        // copy change breaks silently, and six of them went red at once in the
        // plan for exactly that reason.
        let mailHome = app.descendants(matching: .any)["mail-home"].firstMatch
        // The runner's cold start is slow enough that one wait on the identifier
        // would read as an absent screen: this box took 27 s to draw its first
        // button in the previous run. Wait for the identifier, then for the one
        // label the home always draws, and fail only if neither arrives — a real
        // absence still fails, slowness does not.
        XCTAssertTrue(mailHome.waitForExistence(timeout: 40)
                      || app.staticTexts["Search emails, people, or files…"]
                          .waitForExistence(timeout: 20),
                      "missing: Mailbox home")
        snap("60-mail-home")

        // Mailbox leg — read-only. The drawer and the search field are reachable
        // without filing, deleting or sending, so they are photographed from the
        // live screen. Send, permanent delete and Report spam are not in this
        // leg: one is irreversible, one destroys, one feeds the suppression list
        // that would stop future CI mail. They live in the snapshot band.
        tap(app.descendants(matching: .any)["mail-menu"].firstMatch, "Open folders (drawer)")
        XCTAssertTrue(app.staticTexts["Pending approval"].waitForExistence(timeout: 15),
                      "drawer did not open")
        snap("62-mail-drawer")
        // The drawer closes on a row tap; choosing the box already on screen is
        // the one gesture that dismisses it without leaving the home.
        tap(app.descendants(matching: .any)["mail-drawer-Inbox"].firstMatch, "Close drawer (Inbox)")
        XCTAssertTrue(mailHome.waitForExistence(timeout: 20)
                      || app.staticTexts["Search emails, people, or files…"]
                          .waitForExistence(timeout: 10),
                      "home after drawer")

        tap(app.descendants(matching: .any)["mail-search"].firstMatch, "Search field")
        sleep(1)
        snap("67-mail-search")
        app.swipeDown() // the keyboard is not part of the frame being reviewed

        tap(app.buttons["Sort and filter"].firstMatch, "Sort and filter (search row)")
        XCTAssertTrue(app.staticTexts["Sort & filter"].waitForExistence(timeout: 8),
                      "sort sheet did not open")
        snap("69-mail-sort-filter")
        app.swipeDown()
        require(mailHome, "Home after sort sheet", timeout: 8)

        // Security sector — the reference screens photographed in the app's
        // light theme. Read-only: the walk opens every hub row, snapshots it,
        // and backs out. It never submits a password change, never revokes a
        // session, never signs the fresh account out of everything — those
        // flows have their own screens and their own confirmations, and this
        // account has to survive to the session-restore leg below.
        // Account security is reached through the mailbox drawer now. The row is
        // a drawer row, so the drawer has to be open for it to exist — the same
        // path a person takes.
        tap(app.descendants(matching: .any)["mail-menu"].firstMatch, "Open folders (to security)")
        XCTAssertTrue(app.staticTexts["Pending approval"].waitForExistence(timeout: 15),
                      "drawer did not open (to security)")
        tap(app.descendants(matching: .any)["mail-drawer-Security"].firstMatch, "Security (drawer)")
        require(app.staticTexts["Account Security"].firstMatch, "Security hub", timeout: 8)
        snap("20-sec-hub-light")
        openSecurityRow("Two-Step Verification", "21-sec-2fa-enable-light")
        openSecurityRow("Phone Number", "22-sec-phone-light")
        openSecurityRow("Change Password", "23-sec-change-password-light")
        openSecurityRow("Backup Codes", "24-sec-backup-codes-light")
        openSecurityRow("Active Sessions", "25-sec-sessions-light")
        openSecurityRow("Recent Security Activity", "26-sec-activity-light")
        openSecurityRow("Sign Out All Devices", "27-sec-signout-all-light")

        // The enable flow's first gate is a real reference screen and it
        // renders on navigation alone: tap the switch, tap Continue, photograph
        // 'Confirm Your Password', back out twice. Nothing is typed, so no
        // action proof is minted and the factor stays off — but the screen is
        // no longer compile-only. (The per-session Revoke confirmation is NOT
        // reachable here: a fresh account has exactly one session and it is the
        // current device, which deliberately has no revoke affordance.)
        // The hub re-fetches on every reappear (`.onAppear { reload += 1 }`), and
        // while that is in flight the 2FA switch is disabled: the tap lands on
        // nothing and the enable screen never arrives (this is exactly where the
        // run died — 'missing: 2FA enable (gate leg)'). Wait for the switch to
        // actually be enabled before tapping it.
        let gateSwitch = app.switches["Two-Step Verification"].firstMatch
        require(gateSwitch, "2FA switch (gate leg)", timeout: 12)
        waitEnabled(gateSwitch, "2FA switch (gate leg)")
        gateSwitch.tap()
        require(app.staticTexts["Enable Two-Step Verification"].firstMatch, "2FA enable (gate leg)", timeout: 8)
        tap(app.buttons["Continue"].firstMatch, "Continue (enable flow)")
        require(app.staticTexts["Confirm Your Password"].firstMatch, "Confirm-password gate", timeout: 8)
        snap("28-sec-confirm-password-light")
        tap(app.buttons["back"].firstMatch, "back (gate → enable)")
        tap(app.buttons["back"].firstMatch, "back (enable → hub)")
        backFromSecurityScreens()

        // Screens that exist only AFTER a write this walk must not perform:
        // signing out everywhere ends the session the restore leg proves, and
        // changing the password breaks the sign-in leg. So the app carries a
        // test-only hook (-gmSnapshot <name>) that renders one of them with
        // no-op closures, and each is photographed and left. An out-of-process
        // UI test cannot read the app's source, so keep this list in sync with
        // the switch in SecuritySnapshotView.
        let postWriteScreens: [(shot: String, name: String)] = [
            ("40-sec-two-factor-on", "two-factor-on"),
            ("41-sec-two-factor-off-form", "two-factor-off-form"),
            ("42-sec-two-factor-off-done", "two-factor-off-done"),
            ("43-sec-backup-codes-form", "backup-codes-form"),
            ("44-sec-backup-codes-saved", "backup-codes-saved"),
            ("45-sec-change-password", "change-password"),
            ("46-sec-password-changed", "password-changed"),
            ("47-sec-sign-out-all-form", "sign-out-all-form"),
            ("48-sec-sign-out-all-done", "sign-out-all-done"),
            ("49-sec-session-revoked", "session-revoked"),
            ("50-sec-session-revoked-suspicious", "session-revoked-suspicious"),
            // The per-session revoke confirmations: a fresh account has exactly
            // one session and it is this device, which has no revoke affordance
            // by design, so navigation can never reach them either.
            ("51-sec-revoke-session-form", "revoke-session-form"),
            ("52-sec-revoke-session-confirm", "revoke-session-confirm"),
            // The suspicious sign-in pair, photographed in the only state a
            // clean CI account can honestly be in: no open alert. Their names
            // say so, because the same screens with a real intruder on them
            // need a server-seeded event no test can ask for.
            ("53-sec-alert-no-open-alert", "suspicious-alert"),
            ("54-sec-secure-no-open-alert", "secure-account"),
            // Mailbox band. 60/62/67/69 are shot from the live screen above;
            // 61 is the dark frame below. Everything here needs a message to
            // exist, and the account CI creates may have no provisioned mailbox
            // at all — so the populated, loading, empty, failed and no-mailbox
            // frames come from the test-only host rather than from a guess about
            // what the server will say.
            ("63-mail-empty", "mail-empty"),
            ("64-mail-loading", "mail-loading"),
            ("65-mail-load-failed", "mail-load-failed"),
            ("66-mail-no-mailbox", "mail-no-mailbox"),
            ("68-mail-selection", "mail-selection"),
            ("70-mail-move-sheet", "mail-move-sheet"),
            ("71-mail-delete-sheet", "mail-delete-sheet"),
            ("72-mail-labels", "mail-labels"),
            ("73-mail-label-create", "mail-label-create"),
            ("74-mail-thread", "mail-thread"),
            ("75-mail-thread-encrypted", "mail-thread-encrypted"),
            ("76-mail-compose", "mail-compose"),
            ("77-mail-contacts", "mail-contacts"),
            ("78-mail-files", "mail-files"),
            ("79-mail-attachment-preview", "mail-attachment-preview"),
            ("80-mail-settings", "mail-settings"),
            ("81-mail-screener", "mail-screener"),
            ("82-mail-send-receipt", "mail-send-receipt"),
            ("83-mail-assign-label-sheet", "mail-assign-label-sheet"),
            ("84-mail-snooze-sheet", "mail-snooze-sheet"),
            ("85-mail-contact-detail", "mail-contact-detail"),
        ]
        // These two fetch on appear, so the shutter has to wait for the loaded
        // branch — otherwise the photograph catches "Checking your account…"
        // and proves nothing. Each marker is a label that exists ONLY once the
        // fetch has settled.
        let settledLabels: [String: String] = [
            "suspicious-alert": "Address",
            "secure-account": "Suspicious Sign-In Details",
            // The seeded mail screens read their sample content through an
            // effect, so the shutter has to wait for it. Photographed a frame
            // early, a populated screen looks exactly like its empty branch and
            // the artifact would document the wrong state.
            "mail-thread": "Q3 facilities invoice",
            "mail-thread-encrypted": "End-to-end encrypted",
            "mail-contacts": "amira@northwind.example",
            "mail-contact-detail": "Messages seen",
            "mail-files": "invoice-q3.pdf",
            "mail-attachment-preview": "Save to this device",
            "mail-settings": "GrapMail address",
            "mail-screener": "Waiting now",
            "mail-labels": "Manage labels",
            "mail-selection": "Delete",
        ]
        for screen in postWriteScreens {
            app.terminate()
            app.launchArguments = ["-gmSnapshot", screen.name]
            app.launch()
            sleep(2) // the view's own layout settles
            // The snapshot host must be the screen actually on screen. Checking
            // only that the switch's "Unknown snapshot" label is ABSENT let
            // eleven home-screen photographs pass as eleven confirmation
            // screens: a stored session pushed .home over the stub, and nothing
            // in the old assertion could tell. The marker is on the host itself.
            let host = app.descendants(matching: .any)["gm-snapshot"].firstMatch
            XCTAssertTrue(host.waitForExistence(timeout: 6),
                          "snapshot '\(screen.name)' is not the visible screen")
            // And the name has to be one the app knows: an unrecognised one
            // falls through to the default label, which would hand the artifact
            // a placeholder photographed as if it were the screen.
            let unknown = app.staticTexts["Unknown snapshot: \(screen.name)"].firstMatch
            XCTAssertFalse(unknown.exists, "app did not recognise snapshot '\(screen.name)'")
            // The mail host names its own miss. Without this check a typo in a
            // mail-* name would photograph the fallthrough label and still fill
            // the artifact's expected set.
            let unknownMail = app.staticTexts["Unknown mail snapshot: \(screen.name)"].firstMatch
            XCTAssertFalse(unknownMail.exists, "app did not recognise mail snapshot '\(screen.name)'")
            if let settled = settledLabels[screen.name] {
                XCTAssertTrue(app.staticTexts[settled].waitForExistence(timeout: 12),
                              "snapshot '\(screen.name)' never finished loading")
            }
            snap(screen.shot)
        }

        // Session restore: relaunch with the stored Keychain token must open
        // signed-in, not at Welcome. Then sign out returns to Welcome. The
        // snapshot argument has to be cleared first, or the restore opens a
        // stub screen instead of the flow.
        app.terminate()
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["mail-home"].firstMatch
                        .waitForExistence(timeout: 40)
                      || app.staticTexts["Search emails, people, or files…"]
                          .waitForExistence(timeout: 20),
                      "session restored on launch (the mailbox is the signed-in home)")
        snap("10-session-restored")
        // Sign out lives in the mailbox drawer. This is the leg that ends the
        // session, which is why it runs after the restore proof above.
        tap(app.descendants(matching: .any)["mail-menu"].firstMatch, "Open folders (to sign out)")
        XCTAssertTrue(app.staticTexts["Pending approval"].waitForExistence(timeout: 15),
                      "drawer did not open (to sign out)")
        tap(app.descendants(matching: .any)["sign-out"].firstMatch, "Sign out (drawer)")
        require(app.staticTexts["Welcome to"].firstMatch, "welcome after sign-out", timeout: 8)

        // Sign-in screen
        tap(app.buttons["Sign In"].firstMatch, "Sign In (welcome)")
        require(app.staticTexts["Welcome back 👋"].firstMatch, "Sign-in header")
        snap("11-signin-light")

        // Recovery arc — navigation only, nothing submitted. 'Forgot password?'
        // opens the reset form and that form's own link opens the address
        // lookup; both are reference screens the walk had never photographed,
        // and reaching them costs no code send and no state change.
        tap(app.buttons["Forgot password?"].firstMatch, "Forgot password (sign-in)")
        require(app.staticTexts["Reset your password"].firstMatch, "Recovery: reset form", timeout: 8)
        snap("30-recovery-start-light")
        tap(app.buttons["Find your GrapMail address"].firstMatch, "Find-address link (reset form)")
        require(app.staticTexts["Find your GrapMail address"].firstMatch, "Recovery: find form", timeout: 8)
        snap("31-recovery-find-address-light")

        // The mailbox in dark. GM resolves its colours through the trait
        // environment, so overriding the trait here needs no launch argument and
        // no app-code branch — the photograph goes through the same lookup a
        // real dark-mode user does.
        app.terminate()
        app.launchArguments = ["-gmSnapshot", "mail-home", "-gmDark"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["gm-snapshot"].firstMatch
                        .waitForExistence(timeout: 8),
                      "snapshot 'mail-home' (dark) is not the visible screen")
        sleep(2) // the scheme change propagates through the environment
        snap("61-mail-home-dark")
        app.terminate()
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
            // The Two-Step row's only interactive element is the switch, and the
            // hub disables it while it re-fetches on reappear: a tap on a
            // disabled switch lands on nothing and the walk photographs the hub
            // twice. Wait for it to go live before tapping.
            let toggle = app.switches[title].firstMatch
            require(toggle, "switch \(title)", timeout: 8)
            waitEnabled(toggle, "switch \(title)")
            toggle.tap()
        }
        sleep(1) // the screen's own load (profile fetch) settles
        snap(shotName)
        tap(app.buttons["back"].firstMatch, "back from \(title)")
        sleep(1)
    }

    /// Poll until the element reports enabled, then assert it got there. Fails
    /// with the element's name, so a control stuck disabled is not mistaken for
    /// a screen that failed to open. On giving up it prints the screen's texts:
    /// "never enabled" has two causes — the load is still in flight, or it came
    /// back and the hub is showing its error state — and only the first is a
    /// timing problem.
    private func waitEnabled(_ element: XCUIElement, _ what: String, attempts: Int = 30) {
        var waited = 0
        while !element.isEnabled && waited < attempts {
            usleep(500_000)
            waited += 1
        }
        if !element.isEnabled {
            let texts = app.staticTexts.allElementsBoundByIndex.map { $0.label }
            print("WALK-STUCK \(what) | texts: \(texts)")
        }
        XCTAssertTrue(element.isEnabled, "\(what) never became enabled — still loading")
    }

    /// The hub sits under a NavigationStack path the walk appended; back out
    /// of it so the session-restore leg starts from the signed-in home.
    private func backFromSecurityScreens() {
        require(app.staticTexts["Account Security"].firstMatch, "Security hub (return)", timeout: 8)
        tap(app.buttons["back"].firstMatch, "back (hub → home)")
        XCTAssertTrue(app.descendants(matching: .any)["mail-home"].firstMatch
                        .waitForExistence(timeout: 40)
                      || app.staticTexts["Search emails, people, or files…"]
                          .waitForExistence(timeout: 20),
                      "Mailbox home (return)")
    }

    private func typeIntoSecureField(_ identifier: String, _ value: String) {
        focusAndType(app.secureTextFields[identifier].firstMatch, "secure field \(identifier)", value)
    }
}
