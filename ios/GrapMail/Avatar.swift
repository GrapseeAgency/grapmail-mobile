import SwiftUI
import UIKit

// ── GrapMail avatars (docs/design/round8-spec.md §1.6) ───────────────────────
//
// One disc for every person in the app: a sender's GrapMail photo or verified
// brand logo when there is one, otherwise their initials on a GrapMail hue.
// The rules themselves (palette order, FNV-1a key, initials, masking) are in
// `NotificationShared/AvatarIdentity.swift` so the notification extension
// draws the same colour; this file is the app's SwiftUI face of them.
//
// Workstream B (welcome-back) takes `GMAvatar.maskAddress` and
// `GMAvatarView(…, spokenName:)` from here.

/// The app-side avatar helpers. Everything is deterministic: the same address
/// gives the same slot on Android, iOS and web.
enum GMAvatar {
    /// The palette slot (0…9) for a sender — the address when there is one,
    /// the display name only when there is not.
    static func index(address: String, displayName: String = "") -> Int {
        AvatarIdentity.paletteIndex(address: address, displayName: displayName)
    }

    /// The sender's hue as a themed colour (the disc's bottom stop).
    static func color(address: String, displayName: String = "") -> Color {
        GM.avatarRamp[index(address: address, displayName: displayName) % GM.avatarRamp.count]
    }

    /// The disc fill: the hue lightened 8 % at the top to the hue at the bottom.
    static func fill(index i: Int) -> LinearGradient {
        let slot = i % GM.avatarRamp.count
        return LinearGradient(colors: [GM.avatarRampTop[slot], GM.avatarRamp[slot]],
                              startPoint: .top, endPoint: .bottom)
    }

    /// The hue as a fixed (non-dynamic) UIColor, for images rendered outside a
    /// view hierarchy (Siri / share-sheet sender images, notification avatars).
    static func uiColor(index i: Int, dark: Bool = false) -> UIColor {
        let table = dark ? AvatarIdentity.paletteDark : AvatarIdentity.paletteLight
        return UIColor(rgb: table[i % table.count])
    }

    /// The disc's letters for a disc of `size` points, or nil for the person
    /// glyph. Two letters only on discs of 56 pt and up.
    static func initials(displayName: String, address: String, size: CGFloat) -> String? {
        AvatarIdentity.initials(displayName: displayName, address: address, twoLetters: size >= 56)
    }

    /// Letter size: 0.42 × the disc for one letter, 0.36 × for two. Fixed —
    /// the disc does not follow Dynamic Type, so neither does its letter
    /// (callers that grow the disc with `@ScaledMetric` grow the letter too).
    static func letterSize(for letters: String, size: CGFloat) -> CGFloat {
        letters.count >= 2 ? size * 0.36 : size * 0.42
    }

    /// `ol•••r@grapmail.com` (§2.2). Shared by every platform's welcome-back.
    static func maskAddress(_ address: String) -> String {
        AvatarIdentity.maskAddress(address)
    }

    /// What VoiceOver says for a masked address: the visible start spelled out
    /// letter by letter, the visible end, and the domain — never the full
    /// address, on a screen whose point is privacy.
    static func maskedAddressAccessibilityLabel(_ address: String) -> String {
        let parts = AvatarIdentity.maskedParts(address)
        let spelled = parts.lead.map { String($0) }.joined(separator: " ")
        let domain = parts.domain.hasPrefix("@") ? String(parts.domain.dropFirst()) : parts.domain
        if parts.tail.isEmpty {
            return String(localized: "Address beginning \(spelled) at \(domain)", table: "Identity",
                          comment: "VoiceOver label for a masked email address with no visible last letter. First value: its first letters, spelled out. Second: the domain.")
        }
        return String(localized: "Address beginning \(spelled), ending \(parts.tail) at \(domain)", table: "Identity",
                      comment: "VoiceOver label for a masked email address. First value: its first letters, spelled out. Second: its last letter. Third: the domain.")
    }
}

extension GM {
    /// The disc's top stops: each `GM.avatarRamp` hue mixed with 8 % white,
    /// per theme (§1.6.4).
    static let avatarRampTop: [Color] = zip(AvatarIdentity.paletteLight, AvatarIdentity.paletteDark).map { pair in
        GM.color(light: AvatarIdentity.lightened(pair.0), dark: AvatarIdentity.lightened(pair.1))
    }

    /// The plate a brand (BIMI) logo sits on (§1.6.5).
    static let avatarPlate = color(light: 0xFFFFFF, dark: 0xF4F5F7)
}

/// A person's avatar: photo → brand logo → initials disc (§1.6.5).
///
/// - The initials disc draws at once, with no shimmer. A GrapMail profile
///   photo or a verified brand logo (both come from `SenderPhotos`, which asks
///   `POST /grapmail/avatars/resolve`) crossfades in over 150 ms when it
///   arrives; with Reduce Motion it simply swaps in. A failed image leaves the
///   disc in place.
/// - A brand logo fills the circle on a white plate (`#F4F5F7` in dark) with a
///   1 pt `border` stroke. No verified badge: the server returns no VMC flag.
/// - In a row the avatar is decorative (the row already names the sender), so
///   it is hidden from VoiceOver. A standalone avatar (welcome-back, account
///   chooser) passes `spokenName` and is read as that name.
struct GMAvatarView: View, Equatable {
    let name: String
    let address: String
    var size: CGFloat = 40
    /// Look up the sender's photo / brand logo. Off where nothing may be
    /// fetched (the long-press preview).
    var loadsPhoto: Bool = true
    /// nil: decorative. Otherwise the label VoiceOver reads for this avatar.
    var spokenName: String? = nil

    static func == (lhs: GMAvatarView, rhs: GMAvatarView) -> Bool {
        lhs.name == rhs.name && lhs.address == rhs.address && lhs.size == rhs.size
            && lhs.loadsPhoto == rhs.loadsPhoto && lhs.spokenName == rhs.spokenName
    }

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var photo: UIImage?
    @State private var isBrand = false

    var body: some View {
        ZStack {
            disc
            if let photo {
                picture(photo)
                    .transition(.opacity)
            }
        }
        .frame(width: size, height: size)
        .modifier(GMAvatarAccessibility(spokenName: spokenName))
        .task(id: address) {
            guard loadsPhoto else { return }
            let wanted = address
            if let hit = SenderPhotos.shared.cached(wanted) {
                // A recycled row: show the decoded image straight away.
                isBrand = SenderPhotos.shared.isBrandLogo(wanted)
                photo = hit
                return
            }
            photo = nil
            guard !wanted.isEmpty else { return }
            let loaded = await SenderPhotos.shared.photo(for: wanted)
            guard !Task.isCancelled, wanted == address, let loaded else { return }
            let brand = SenderPhotos.shared.isBrandLogo(wanted)
            withAnimation(GMMotion.gated(.easeInOut(duration: GMMotion.short), reduced: reduceMotion)) {
                isBrand = brand
                photo = loaded
            }
        }
    }

    /// The initials disc on the sender's hue.
    private var disc: some View {
        let slot = GMAvatar.index(address: address, displayName: name)
        let letters = GMAvatar.initials(displayName: name, address: address, size: size)
        return ZStack {
            Circle().fill(GMAvatar.fill(index: slot))
            if let letters {
                Text(verbatim: letters)
                    // Fixed size on purpose (§1.6.3): see `GMAvatar.letterSize`.
                    .font(.system(size: GMAvatar.letterSize(for: letters, size: size), weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .foregroundColor(GM.onPrimary)
            } else {
                Image(systemName: "person.fill")
                    .resizable()
                    .scaledToFit()
                    .frame(width: size * 0.5, height: size * 0.5)
                    .foregroundColor(GM.onPrimary)
            }
            if colorScheme == .dark {
                // 1 pt inner hairline so the disc separates from #141416.
                Circle().strokeBorder(Color.white.opacity(0.10), lineWidth: 1)
            }
        }
    }

    @ViewBuilder
    private func picture(_ image: UIImage) -> some View {
        if isBrand {
            ZStack {
                Circle().fill(GM.avatarPlate)
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
                Circle().strokeBorder(GM.border, lineWidth: 1)
            }
        } else {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(Circle())
        }
    }
}

/// Decorative unless a name is given (§1.6.5 accessibility).
private struct GMAvatarAccessibility: ViewModifier {
    let spokenName: String?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let spokenName {
            content
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: spokenName))
        } else {
            content.accessibilityHidden(true)
        }
    }
}

extension MailUI {
    /// The sender's avatar hue. Kept for callers that only need the colour;
    /// it is `GMAvatar.color` — the cross-platform FNV-1a slot (§1.6.2) that
    /// replaced the old 31-multiplier accumulator.
    static func tint(address: String, display: String = "") -> Color {
        GMAvatar.color(address: address, displayName: display)
    }
}
