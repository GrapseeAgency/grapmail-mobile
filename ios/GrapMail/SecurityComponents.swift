import SwiftUI

/// Values the security flow carries between screens, in memory only.
///
/// A server-issued proof kept in a restored navigation path is a token on disk;
/// this holds it for the two screens that need it and nothing else.
enum SecurityFlow {
    static var enableProof: String?
    static var phoneProof: String?
    /// The ten one-time codes between generation and the screen that shows them.
    /// They live here, not in a view's @State, so sign-out can wipe them: a
    /// plaintext recovery code left in a root view's memory after the account is
    /// signed out is a code the next person to unlock the phone could read.
    static var backupCodes: [String] = []

    /// Sign-out clears the token; this clears what the sector was holding
    /// alongside it, so the next person to unlock the phone does not inherit ten
    /// plaintext recovery codes and a live action proof.
    static func reset() {
        enableProof = nil
        phoneProof = nil
        backupCodes = []
    }
}

/// The security hub's list row — the same control the 24 designs reuse.
///
/// It lives here rather than being redrawn per screen: a row that is two points
/// taller on the sessions screen than on the hub reads as a mistake, not a
/// variation.
struct SecurityRowView: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 40
    let icon: String
    let title: String
    var subtitle: String? = nil
    var chip: String? = nil
    var chipTone: ChipTone = .success
    var action: (() -> Void)? = nil

    var body: some View {
        let content = HStack(spacing: 12) {
            Image(systemName: icon)
                .gmFont(17, .medium, maxSize: 30)
                .foregroundColor(GM.primarySolid)
                .frame(width: tile, height: tile)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GM.softFill))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if textSize.isAccessibilitySize, let chip {
                    StatusChip(text: chip, tone: chipTone).padding(.top, 4)
                }
            }
            Spacer(minLength: 0)
            if !textSize.isAccessibilitySize, let chip {
                StatusChip(text: chip, tone: chipTone)
            } else if action != nil {
                Image(systemName: "chevron.forward")
                    .gmFont(13, .semibold, maxSize: 22)
                    .foregroundColor(GM.textTertiary)
            }
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())

        if let action {
            Button(action: action) { content }.buttonStyle(.plain)
        } else {
            content
        }
    }
}

/// A hub row with a real on/off switch, as the reference draws the 2FA row.
///
/// The switch is the entry point to the flow that proves the change (password,
/// then code), not a blind write — flipping it routes into enable/disable rather
/// than PATCHing the flag directly, so the credential gate is never skipped.
struct SecurityToggleRowView: View {
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 40
    let icon: String
    let title: String
    var subtitle: String? = nil
    var chip: String? = nil
    var chipTone: ChipTone = .success
    var isOn: Bool
    var enabled: Bool = true
    let onToggle: (Bool) -> Void

    var body: some View {
        // At the accessibility sizes the switch moves under the text so the
        // title keeps the full row width instead of wrapping a word per line.
        GMAdaptiveStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .gmFont(17, .medium, maxSize: 30)
                    .foregroundColor(GM.primarySolid)
                    .frame(width: tile, height: tile)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GM.softFill))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .gmFont(15, .semibold)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let subtitle {
                        Text(subtitle)
                            .gmFont(12)
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let chip {
                        StatusChip(text: chip, tone: chipTone).padding(.top, 6)
                    }
                }
                Spacer(minLength: 0)
            }
            Toggle(title,
                   isOn: Binding(get: { isOn }, set: { if enabled { onToggle($0) } }))
                .labelsHidden()
                .tint(GM.primarySolid)
                .disabled(!enabled)
                // The visual label is hidden but the a11y label carries the
                // row title, so XCUITest's switches[title] finds this switch
                // (the CI walk taps it to route into the enable flow).
                .accessibilityIdentifier(title)
        }
        .padding(.vertical, 12)
    }
}

/// Hairline between rows, inset past the icon tile so the list reads as a list.
struct SecurityDivider: View {
    /// Icon tile (40) + spacing (12), scaled with the tile.
    @ScaledMetric(relativeTo: .body) private var inset: CGFloat = 52
    var body: some View {
        Rectangle()
            .fill(GM.border)
            .frame(height: 1)
            .padding(.leading, inset)
    }
}

/// A destructive action: solid red, the same height as the primary button.
struct DestructiveActionButton: View {
    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 54
    let title: String
    var enabled: Bool = true
    var busy: Bool = false
    let action: () -> Void

    var body: some View {
        Button {
            guard enabled, !busy else { return }
            action()
        } label: {
            ZStack {
                RoundedRectangle(cornerRadius: GMShape.button, style: .continuous)
                    .fill(enabled && !busy ? GM.error : GM.error.opacity(0.45))
                if busy {
                    ProgressView().tint(.white)
                } else {
                    Text(title)
                        .gmFont(16, .semibold)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 12)
                }
            }
            // Exact 54 at the default size, the same greedy-floor cure as
            // Components.swift's buttons: a floor grows into any leftover a
            // parent hands out. The exact value scales with the text size so a
            // large label is never clipped.
            .frame(height: height)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PressScaleStyle())
        .disabled(!enabled || busy)
    }
}

/// The red "this cannot be undone" warning the revoke confirmations show.
struct CannotBeUndoneNote: View {
    var text: String = String(localized: "This action cannot be undone. The device will be signed out immediately and will need to sign in again to access your account.", comment: "Warning shown before signing a device out")
    var body: some View {
        HStack(alignment: .top, spacing: 9) {
            Image(systemName: "exclamationmark.triangle.fill")
                .gmFont(14)
                .foregroundColor(GM.error)
            Text(text)
                .gmFont(12)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.error.opacity(0.08)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(GM.error.opacity(0.35)))
    }
}

/// One signed-in device, as the sessions screen prints it.
struct SessionCardView: View {
    @Environment(\.dynamicTypeSize) private var dts
    let session: SessionDto
    var onRevoke: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GMAdaptiveStack(spacing: 8) {
                Text(session.device.isEmpty ? String(localized: "Unknown device", comment: "Session card title when the device name is not known") : session.device)
                    .gmFont(15, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .lineLimit(dts.isAccessibilitySize ? 4 : 2)
                    .fixedSize(horizontal: false, vertical: true)
                if !dts.isAccessibilitySize { Spacer(minLength: 0) }
                if session.current {
                    Text("This device")
                        .gmFont(11, .bold)
                        .foregroundColor(GM.successInk)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(GM.successChipBg))
                }
            }
            HStack(spacing: 6) {
                Image(systemName: "clock")
                    .gmFont(11)
                    .foregroundColor(GM.textTertiary)
                // The reference row is "browser · city" over an absolute date and
                // time. There is no GeoIP source, so the city is omitted rather than
                // invented; the browser and masked IP are real, and an absolute time
                // is what a person compares against a suspicious sign-in.
                Text([session.browser, session.ip].filter { !$0.isEmpty }.joined(separator: " · ")
                     .isEmpty ? securityAbsolute(session.lastSeenAt)
                     : [session.browser, session.ip].filter { !$0.isEmpty }.joined(separator: " · "))
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(securityAbsolute(session.createdAt))
                .gmFont(11)
                .foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if let onRevoke, !session.current {
                Button(action: onRevoke) {
                    Text("Sign out this device")
                        .gmFont(13, .semibold)
                        .foregroundColor(GM.error)
                }
                .buttonStyle(.plain)
                .padding(.top, 2)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(GM.card))
        .overlay(
            RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).strokeBorder(GM.border)
        )
    }
}

/// A row on the activity timeline; the tone is the event's own severity.
struct ActivityRowView: View {
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 34
    let event: ActivityDto

    private var icon: (String, Color) {
        if event.severity == "critical" { return ("exclamationmark.octagon", GM.error) }
        if event.severity == "warning" { return ("exclamationmark.triangle", GM.warnInk) }
        if event.type == "signin" { return ("checkmark.circle", GM.success) }
        return ("info.circle", GM.primarySolid)
    }

    var body: some View {
        let (name, tint) = icon
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: name)
                .gmFont(15, .medium, maxSize: 26)
                .foregroundColor(tint)
                .frame(width: tile, height: tile)
                .background(Circle().fill(GM.softFill))
            VStack(alignment: .leading, spacing: 3) {
                Text(eventTitle(event.type))
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if !event.detail.isEmpty {
                    Text(event.detail)
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text([event.browser.isEmpty ? event.device : event.browser, event.ip].filter { !$0.isEmpty }.joined(separator: " · "))
                    .gmFont(11)
                    .foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                if dts.isAccessibilitySize {
                    // No room for a right-hand column at these sizes: the
                    // timestamp joins the text column.
                    Text(securityAbsolute(event.at))
                        .gmFont(11)
                        .foregroundColor(GM.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
            // The reference timeline carries an absolute date and time on the right,
            // so events compare to the minute; relative time cannot.
            if !dts.isAccessibilitySize {
                Text(securityAbsolute(event.at))
                    .gmFont(11)
                    .foregroundColor(GM.textTertiary)
                    .multilineTextAlignment(.trailing)
            }
        }
        .padding(.vertical, 8)
    }
}

/// The event's own name, not the backend's identifier.
func eventTitle(_ type: String) -> String {
    switch type {
    case "signin": return String(localized: "Signed in", comment: "Security activity event title")
    case "password_changed": return String(localized: "Password changed", comment: "Security activity event title")
    case "two_factor_enabled": return String(localized: "Two-step verification turned on", comment: "Security activity event title")
    case "two_factor_disabled": return String(localized: "Two-step verification turned off", comment: "Security activity event title")
    case "backup_codes_generated": return String(localized: "Backup codes regenerated", comment: "Security activity event title")
    case "backup_code_used": return String(localized: "A backup code was used", comment: "Security activity event title")
    case "session_revoked": return String(localized: "A device was signed out", comment: "Security activity event title")
    case "all_sessions_signed_out": return String(localized: "Signed out of all devices", comment: "Security activity event title")
    case "phone_changed": return String(localized: "Phone number changed", comment: "Security activity event title")
    default: return String(localized: "Account activity", comment: "Security activity event title for an unrecognised event")
    }
}

/// "2 minutes ago" from the API's ISO timestamps. Coarse on purpose: the
/// question a security list answers is "was this me, and was it just now".
func securityRelative(_ iso: String) -> String {
    guard let date = parseIso(iso) else { return String(localized: "recently", comment: "Relative time when the exact time is unknown") }
    let seconds = Date().timeIntervalSince(date)
    if seconds < 0 { return String(localized: "just now", comment: "Relative time for less than a minute ago") }
    let minutes = Int(seconds / 60)
    if minutes < 1 { return String(localized: "just now", comment: "Relative time for less than a minute ago") }
    if minutes < 60 { return String(localized: "\(minutes) min ago", comment: "Relative time in minutes") }
    if minutes < 24 * 60 { return String(localized: "\(minutes / 60) hr ago", comment: "Relative time in hours") }
    if minutes < 48 * 60 { return String(localized: "yesterday", comment: "Relative time for the previous day") }
    return String(localized: "\(minutes / (24 * 60)) days ago", comment: "Relative time in days")
}

private func parseIso(_ iso: String) -> Date? {
    guard !iso.isEmpty else { return nil }
    let withFraction = ISO8601DateFormatter()
    withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = withFraction.date(from: iso) { return date }
    let plain = ISO8601DateFormatter()
    plain.formatOptions = [.withInternetDateTime]
    return plain.date(from: iso)
}

/// "Apr 26, 2025 · 10:24 AM" in the device's own zone.
///
/// The sessions and activity references print an absolute date and time, not a
/// relative one: "3 days ago" cannot be compared against a suspicious sign-in's
/// timestamp, which is the judgement these screens ask for. An unparseable value
/// degrades to the raw string rather than a fabricated date.
func securityAbsolute(_ iso: String) -> String {
    guard let date = parseIso(iso) else { return iso }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US")
    formatter.dateFormat = "MMM d, yyyy · h:mm a"
    return formatter.string(from: date)
}
