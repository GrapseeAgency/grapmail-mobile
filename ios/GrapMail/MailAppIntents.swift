import AppIntents
import Foundation

// MARK: - App Intents & Focus (F15, F20)
//
// Two ways the system reaches into GrapMail without the app being open:
// Siri and Shortcuts ask for a draft, and a Focus asks whether mail should
// stay quiet. Both land on flags the in-app paths already honor, so nothing
// here is a second implementation of anything.

/// "New message in GrapMail" - the voice half of the same door the
/// home-screen quick action knocks on.
@available(iOS 16.0, *)
struct MailComposeIntent: AppIntent {
    static var title: LocalizedStringResource = "New Message"
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        await MainActor.run {
            ShortcutRouter.shared.requestCompose()
        }
        return .result()
    }
}

@available(iOS 16.0, *)
struct MailAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: MailComposeIntent(),
            phrases: [
                "New message in \(.applicationName)",
                "Compose mail in \(.applicationName)",
                "Start an email in \(.applicationName)"
            ])
    }
}

/// A Focus can state what mail should do while it is on. The choice lands
/// in the same preference the app reads when deciding to stay quiet.
@available(iOS 16.0, *)
struct MailFocusFilter: SetFocusFilterIntent {
    static var title: LocalizedStringResource = "GrapMail"

    // Optional, with no default: AppIntents' @Parameter has no wrappedValue
    // initialiser for a non-optional that carries one, which is the "extra
    // argument 'wrappedValue'" the simulator build reported. A nil means the
    // person has not answered, and that is read as "not paused".
    @Parameter(title: "Pause mail notifications")
    var pauseNotifications: Bool?

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(
            title: "GrapMail",
            subtitle: (pauseNotifications ?? false) ? "Notifications paused" : "Notifications on")
    }

    func perform() async throws -> some IntentResult {
        UserDefaults.standard.set(pauseNotifications ?? false, forKey: "gm.focusQuiet")
        return .result()
    }
}
