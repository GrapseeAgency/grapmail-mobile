import SwiftUI

struct AccountSwitcher: View {
    let activeAddress: String?
    let accountName: String?
    let accountPhoto: Data?
    let onClose: () -> Void
    let onSwitch: (String) -> Void
    let onAdd: () -> Void
    let onProfile: () -> Void
    let onSettings: () -> Void
    let onSignOut: () -> Void

    @State private var accounts: [SessionStore.Account] = []
    @State private var unreads: [String: Int] = [:]
    @State private var savedPhotos: [String: Data] = [:]
    @State private var accountsExpanded = true

    private var displayedAddress: String? { activeAddress ?? accounts.first?.address }

    var body: some View {
        ScrollView {
            VStack(spacing: 3) {
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) { accountsExpanded.toggle() }
                } label: {
                    HStack(spacing: 12) {
                        AccountAvatar(photo: accountPhoto, name: accountName ?? "", address: displayedAddress ?? "", size: 42)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(displayedAddress ?? "GrapMail").font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(GM.textPrimary).lineLimit(1).truncationMode(.middle)
                            Text("GrapMail Account").font(.system(size: 12)).foregroundStyle(GM.textSecondary)
                        }
                        Spacer(minLength: 2)
                        Image(systemName: accountsExpanded ? "chevron.up" : "chevron.down")
                            .font(.system(size: 13, weight: .semibold)).foregroundStyle(GM.textSecondary)
                    }
                    .frame(minHeight: 54)
                    .padding(.horizontal, 9)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(accountsExpanded ? "Hide accounts" : "Show accounts")

                rule
                if accountsExpanded {
                    ForEach(accounts) { account in accountRow(account) }
                    action("Add another account", icon: "plus", handler: onAdd)
                    rule
                }

                action("Profile", icon: "person.crop.circle", chevron: true, handler: onProfile)
                action("Settings", icon: "gearshape", chevron: true, handler: onSettings)
                action("Sign out", icon: "rectangle.portrait.and.arrow.right", destructive: true, handler: onSignOut)
            }
            .padding(12)
        }
        .scrollBounceWhenNeeded()
        .frame(width: 320, height: min(478, CGFloat(278 + (accountsExpanded ? accounts.count * 48 + 59 : 0))))
        .background {
            LinearGradient(colors: [GM.cardAlt, GM.card], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
        .task {
            let current = SessionStore.accounts()
            accounts = current
            await withTaskGroup(of: (String, Int?).self) { group in
                for account in current where account.id != displayedAddress?.lowercased() {
                    let address = account.address
                    group.addTask { (address, await ApiClient.parkedAccountUnread(address)) }
                }
                for await (address, unread) in group {
                    if let unread { unreads[address] = unread }
                }
            }
        }
    }

    private var rule: some View {
        Rectangle().fill(GM.border).frame(height: 1).padding(.horizontal, 5).padding(.vertical, 5)
            .accessibilityHidden(true)
    }

    private func accountRow(_ account: SessionStore.Account) -> some View {
        let active = account.id == displayedAddress?.lowercased()
        let shape = RoundedRectangle(cornerRadius: 15, style: .continuous)
        return Button {
            if active { onClose() } else { onSwitch(account.address) }
        } label: {
            HStack(spacing: 11) {
                AccountAvatar(photo: active ? accountPhoto : savedPhotos[account.id], name: account.name, address: account.address, size: 32)
                Text(account.address).font(.system(size: 14, weight: active ? .semibold : .medium))
                    .lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 2)
                if active {
                    Image(systemName: "checkmark").font(.system(size: 15, weight: .bold))
                        .foregroundStyle(GM.successInk).accessibilityHidden(true)
                } else if let unread = unreads[account.address], unread > 0 {
                    Text(unread > 99 ? "99+" : String(unread)).font(.system(size: 11, weight: .semibold))
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(GM.softFill, in: Capsule())
                        .accessibilityLabel("\(unread) unread")
                }
            }
            .foregroundStyle(GM.textPrimary)
            .padding(.horizontal, 10)
            .frame(minHeight: 45)
            .contentShape(shape)
            .background(active ? GM.successChipBg : Color.clear, in: shape)
            .overlay { if active { shape.strokeBorder(GM.successInk, lineWidth: 1).allowsHitTesting(false) } }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(active ? .isSelected : [])
        // The active account is watched by RootView. Every other saved account
        // needs its own watcher for a photo changed from web or another device.
        .task(id: active) {
            if !active { await watchPhoto(account) }
        }
    }

    private func action(_ title: String, icon: String, chevron: Bool = false, destructive: Bool = false,
                        handler: @escaping () -> Void) -> some View {
        Button(action: handler) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 16, weight: .medium)).frame(width: 29)
                Text(title).font(.system(size: 14, weight: destructive ? .semibold : .medium))
                Spacer()
                if chevron { Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)) }
            }
            .foregroundStyle(destructive ? GM.error : GM.textPrimary)
            .padding(.horizontal, 10)
            .frame(minHeight: 44)
            .background(destructive ? GM.error.opacity(0.11) : Color.clear,
                        in: RoundedRectangle(cornerRadius: 15, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func watchPhoto(_ account: SessionStore.Account) async {
        if let cached = await AccountPhotoCache.read(token: account.token), !Task.isCancelled {
            savedPhotos[account.id] = cached
        }
        var revision = ""
        while !Task.isCancelled, let token = await ApiClient.pushBearer(for: account.address) {
            do {
                let latest = try await ApiClient.watchAvatar(token: token, revision: revision)
                if latest != revision {
                    let photo = latest == "none" ? nil : try await ApiClient.avatarBytes(token: token, revision: latest)
                    guard !Task.isCancelled else { return }
                    savedPhotos[account.id] = photo
                    AccountPhotoCache.store(photo, token: token)
                    revision = latest
                }
            } catch {
                if let apiError = error as? ApiError,
                   case let .http(_, code, _) = apiError, code == "AVATAR_CHANGED" { continue }
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }
}

extension View {
    @ViewBuilder
    func keepAccountPopover() -> some View {
        if #available(iOS 16.4, *) {
            presentationCompactAdaptation(.popover)
                .presentationBackground(GM.card)
        } else {
            self
        }
    }

    @ViewBuilder
    func grapMailSheetSurface() -> some View {
        if #available(iOS 16.4, *) {
            presentationBackground(GM.card)
                .presentationCornerRadius(28)
        } else {
            self
        }
    }
}

private extension View {
    @ViewBuilder
    func scrollBounceWhenNeeded() -> some View {
        if #available(iOS 16.4, *) { scrollBounceBehavior(.basedOnSize) }
        else { self }
    }
}
