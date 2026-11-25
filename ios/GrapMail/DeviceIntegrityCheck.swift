import Foundation
import MachO
import SwiftUI

/// Lightweight jailbreak heuristics (OWASP MASVS-RESILIENCE-1) — the iOS twin
/// of Android's `security/RootCheck.kt`.
///
/// WARN, NEVER BLOCK. Every check is defeatable by jailbreak-hiding tweaks, and
/// a positive is shown only as a dismissible note in the security hub. Nothing
/// is refused and nothing is sent anywhere from here. The signal that carries
/// weight server-side is App Attest (`AppAttestClient`), whose verdict the
/// server owns; this file does not duplicate it.
///
/// `evaluate` is pure (unit-tested in JailbreakCheckTests); `probe` gathers the
/// facts. The simulator is always reported clean.
enum JailbreakCheck {
    static let suspiciousPaths: [String] = [
        "/Applications/Cydia.app", "/Applications/Sileo.app", "/Applications/Zebra.app",
        "/Library/MobileSubstrate/MobileSubstrate.dylib", "/bin/bash", "/usr/sbin/sshd",
        "/etc/apt", "/private/var/lib/apt/", "/var/jb", "/usr/bin/ssh",
        "/private/var/stash", "/usr/libexec/cydia",
    ]

    /// Substrings of loaded image names that only injected tweaks carry.
    static let suspiciousDylibs: [String] = [
        "MobileSubstrate", "substrate", "TweakInject", "libhooker", "substitute",
        "SSLKillSwitch", "FridaGadget", "frida", "cynject", "libcycript", "ellekit",
    ]

    enum Finding: String, CaseIterable { case jailbreakFiles, sandboxWritable, injectedLibrary }

    struct Facts {
        var existingPaths: Set<String>
        var sandboxWriteSucceeded: Bool
        var loadedImages: [String]
    }

    static func evaluate(_ facts: Facts) -> Set<Finding> {
        var found = Set<Finding>()
        if suspiciousPaths.contains(where: facts.existingPaths.contains) { found.insert(.jailbreakFiles) }
        if facts.sandboxWriteSucceeded { found.insert(.sandboxWritable) }
        let lowered = facts.loadedImages.map { $0.lowercased() }
        if suspiciousDylibs.contains(where: { lib in lowered.contains { $0.contains(lib.lowercased()) } }) {
            found.insert(.injectedLibrary)
        }
        return found
    }

    static func shouldWarn(_ findings: Set<Finding>?, dismissed: Bool) -> Bool {
        guard let findings else { return false }
        return !findings.isEmpty && !dismissed
    }

    // ── device probe ────────────────────────────────────────────────────────
    static func probe() -> Set<Finding> {
        #if targetEnvironment(simulator)
        return []
        #else
        let fm = FileManager.default
        let existing = Set(suspiciousPaths.filter { fm.fileExists(atPath: $0) })
        // A sandboxed app cannot write outside its container.
        let probePath = "/private/grapmail-jb-probe-\(UUID().uuidString)"
        var wrote = false
        if (try? "x".write(toFile: probePath, atomically: false, encoding: .utf8)) != nil {
            wrote = true
            try? fm.removeItem(atPath: probePath)
        }
        var images: [String] = []
        for i in 0..<_dyld_image_count() {
            if let name = _dyld_get_image_name(i) { images.append(String(cString: name)) }
        }
        return evaluate(Facts(existingPaths: existing, sandboxWriteSucceeded: wrote, loadedImages: images))
        #endif
    }

    // ── the dismissible hub note ────────────────────────────────────────────
    static let dismissedKey = "grapmail.jailbreakWarning.dismissed"
    static let defaultDismissed = false

    static var isDismissed: Bool {
        UserDefaults.standard.object(forKey: dismissedKey) as? Bool ?? defaultDismissed
    }

    static func dismiss() { UserDefaults.standard.set(true, forKey: dismissedKey) }
}

/// The hub card. Probes off the main thread once per appearance.
struct JailbreakWarningCard: View {
    @State private var findings: Set<JailbreakCheck.Finding>?
    @State private var dismissed = JailbreakCheck.isDismissed

    var body: some View {
        Group {
            if JailbreakCheck.shouldWarn(findings, dismissed: dismissed) {
                InfoCard {
                    StatusChip(text: String(localized: "This device may be jailbroken", comment: "Security hub warning chip"), tone: .warning)
                    Text(String(localized: "GrapMail found signs that this device is jailbroken or runs injected code. Other software may be able to read data that is normally protected. GrapMail keeps working, but keep this in mind for sensitive mail.", comment: "Security hub jailbreak warning body"))
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                    AccountLink(title: String(localized: "Dismiss", comment: "Link-style button (security hub jailbreak warning)")) {
                        JailbreakCheck.dismiss()
                        dismissed = true
                    }
                    .accessibilityIdentifier("sec-jailbreak-dismiss")
                }
                .padding(.bottom, 12)
            }
        }
        .task {
            guard !dismissed else { return }
            findings = await Task.detached(priority: .utility) { JailbreakCheck.probe() }.value
        }
    }
}

/// App-switcher privacy: covers the window while the scene is inactive or in
/// the background, so the snapshot iOS takes for the app switcher shows this
/// instead of mail. iOS offers no way to BLOCK screenshots or screen
/// recording; this does not pretend to.
struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Rectangle().fill(.regularMaterial).ignoresSafeArea()
            VStack(spacing: 10) {
                Image(systemName: "lock.shield.fill")
                    .gmFont(34, .semibold, maxSize: 60)
                    .foregroundColor(Color.secondary)
                Text("GrapMail")
                    .gmFont(17, .semibold)
                    .foregroundColor(Color.primary)
            }
            .padding(20)
        }
        .accessibilityHidden(true)
    }
}
