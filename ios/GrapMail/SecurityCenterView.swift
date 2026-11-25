import SwiftUI
import UIKit

// MARK: - Round 8 — Device & Security Center (docs/design/round8-spec.md §3)
//
// One screen over `GET /grapsee/auth/security/overview` (§6.2): the safety
// score header, the §3.6 / §3.7 banner slot, device cards, the 30-day event
// timeline, and the doors into "This wasn't me" (NotMeFlow.swift) and
// "Remove device". Every new English string lives in the `SecurityCenter`
// table (en.lproj/SecurityCenter.strings + .stringsdict).

/// A string from the `SecurityCenter` table. Interpolations become format
/// arguments exactly as `String(localized:)` makes them (`%@`, `%lld`).
func secL(_ value: String.LocalizationValue) -> String {
    String(localized: value, table: "SecurityCenter")
}

/// Round 8 colour roles mapped onto the §1.12 `GM.*` tokens (Theme.swift).
/// Text, links and icons use `primaryText` (#8EA8FF in dark), never the
/// `primarySolid` fill, which is under 4.5:1 on the dark canvas. No colour
/// literal anywhere in the security files.
enum SecPalette {
    static var primaryText: Color { GM.primaryText }
    static var primaryContainer: Color { GM.primaryContainer }
    static var onPrimaryContainer: Color { GM.onPrimaryContainer }
    static var success: Color { GM.successInk }
    static var successFill: Color { GM.successFill }
    static var successContainer: Color { GM.successChipBg }
    static var warning: Color { GM.warnInk }
    static var warningContainer: Color { GM.warnChipBg }
    static var error: Color { GM.error }
    static var errorContainer: Color { GM.errorContainer }
    /// §1.3 signature gradient, 135°, from the current brand tokens.
    static var signature: LinearGradient {
        LinearGradient(colors: [GM.primaryTop, GM.primarySolid, GM.violet],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static var confetti: [Color] { [GM.primaryTop, GM.primarySolid, GM.violet, GM.success, GM.warning] }
}

/// The `security_center` RemoteConfig flag (§7): on in debug, off in release
/// until QA turns it on server-side.
@MainActor
enum SecFeature {
    static var enabled: Bool {
        #if DEBUG
        RemoteConfig.shared.flag("security_center", default: true)
        #else
        RemoteConfig.shared.flag("security_center", default: false)
        #endif
    }
}

/// §1.6.2 normalisation + FNV-1a 32, for the per-account local keys
/// (`gm.safetyCard.lastShownAt.<fnv1a(address)>`, `gm.safety.celebrated.<…>`).
/// Workstream A ships the shared avatar copy; this one is private to the
/// security keys so neither depends on the other's merge order.
enum SecHash {
    static func normalize(_ raw: String) -> String {
        var s = Substring(raw)
        if let open = s.lastIndex(of: "<"), let close = s[open...].firstIndex(of: ">") {
            s = s[s.index(after: open)..<close]
        }
        let ascii: Set<UInt32> = [0x20, 0x09, 0x0A, 0x0D, 0x0B, 0x0C]
        let scalars = Array(s.unicodeScalars)
        var lo = 0, hi = scalars.count
        while lo < hi, ascii.contains(scalars[lo].value) { lo += 1 }
        while hi > lo, ascii.contains(scalars[hi - 1].value) { hi -= 1 }
        var out = String.UnicodeScalarView()
        for scalar in scalars[lo..<hi] {
            if (65...90).contains(scalar.value), let lower = Unicode.Scalar(scalar.value + 32) {
                out.append(lower)
            } else {
                out.append(scalar)
            }
        }
        return String(out)
    }

    static func fnv1a(_ key: String) -> UInt32 {
        var h: UInt32 = 0x811C9DC5
        for b in key.utf8 { h ^= UInt32(b); h = h &* 0x01000193 }
        return h
    }

    static func accountKey(_ address: String) -> String {
        String(fnv1a(normalize(address)), radix: 16)
    }
}

// MARK: - Cache + store

/// The last overview per account, on disk (Application Support, excluded from
/// backup, protected until first unlock). It renders the Center at once and
/// offline (§3.7) and feeds the mailbox slot (§3.6 / §4.3 B).
enum SecOverviewDisk {
    private struct Envelope: Codable {
        var fetchedAt: Double
        var overview: SecurityOverviewResponse
    }

    private static func url(_ address: String) -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        return base.appendingPathComponent("SecurityOverview", isDirectory: true)
            .appendingPathComponent("\(SecHash.accountKey(address)).json")
    }

    static func read(_ address: String) -> (overview: SecurityOverviewResponse, fetchedAt: Date)? {
        guard let url = url(address), let data = try? Data(contentsOf: url),
              let envelope = try? JSONDecoder().decode(Envelope.self, from: data) else { return nil }
        return (envelope.overview, Date(timeIntervalSince1970: envelope.fetchedAt))
    }

    static func write(_ address: String, _ overview: SecurityOverviewResponse, at date: Date) {
        guard var url = url(address),
              let data = try? JSONEncoder().encode(Envelope(fetchedAt: date.timeIntervalSince1970, overview: overview)) else { return }
        let dir = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }
}

/// One overview per signed-in account, shared by the Center, the Account
/// Security header, the recovery review and the mailbox slot.
@MainActor
final class SecurityOverviewStore: ObservableObject {
    static let shared = SecurityOverviewStore()

    @Published private(set) var overview: SecurityOverviewResponse?
    @Published private(set) var fetchedAt: Date?
    private(set) var address: String?
    private var lastBackgroundAttempt = Date.distantPast

    private init() {}

    /// Follow the active account: a switch swaps in that account's cache.
    func bind() {
        let current = SessionStore.address?.lowercased()
        guard current != address else { return }
        address = current
        lastBackgroundAttempt = .distantPast
        if let current, let cached = SecOverviewDisk.read(current) {
            overview = cached.overview
            fetchedAt = cached.fetchedAt
        } else {
            overview = nil
            fetchedAt = nil
        }
    }

    /// The live read. Throws the ApiError the screen words; a sign-out or
    /// account switch mid-flight throws `CancellationError` and writes nothing.
    @discardableResult
    func fetch(eventsCursor: String? = nil) async throws -> SecurityOverviewResponse {
        bind()
        guard let token = SessionStore.token else { throw ApiError.http(status: 401, code: nil, message: "") }
        let generation = SessionStore.generation
        let fresh = try await ApiClient.securityOverview(token: token, eventsCursor: eventsCursor)
        guard generation == SessionStore.generation else { throw CancellationError() }
        if eventsCursor == nil { accept(fresh) }
        return fresh
    }

    private func accept(_ fresh: SecurityOverviewResponse) {
        let now = Date()
        overview = fresh
        fetchedAt = now
        if let address { SecOverviewDisk.write(address, fresh, at: now) }
    }

    /// The mailbox's background read (§4.3 B.2): at most once a day, or every
    /// five minutes while the §3.6 banner is up so it clears promptly.
    func refreshIfDue() async {
        bind()
        guard SessionStore.token != nil else { return }
        let now = Date()
        let pending = overview?.account.passwordChangeRequired == true
        let stale = fetchedAt.map { now.timeIntervalSince($0) > 24 * 3600 } ?? true
        let gap: TimeInterval = pending ? 300 : 24 * 3600
        guard stale || pending, now.timeIntervalSince(lastBackgroundAttempt) > gap else { return }
        lastBackgroundAttempt = now
        _ = try? await fetch()
    }

    /// The not-me answer and a password change flip the §3.6 flag before the
    /// refetch lands, so the banner is right on the very next frame.
    func notePasswordChangeRequired(_ value: Bool) {
        guard var current = overview else { return }
        current.account.passwordChangeRequired = value
        overview = current
    }

    /// When the cache was written, if it is fresh enough for the nudge (§4.3 B.2).
    var freshForNudge: Bool {
        guard let fetchedAt else { return false }
        return Date().timeIntervalSince(fetchedAt) <= 24 * 3600
    }
}

// MARK: - Screen model

@MainActor
final class SecurityCenterModel: ObservableObject {
    @Published private(set) var overview: SecurityOverviewResponse?
    @Published private(set) var loading = false
    @Published private(set) var refreshFailed = false
    @Published private(set) var olderEvents: [SecEvent] = []
    @Published private(set) var cursor: String?
    @Published private(set) var loadingOlder = false
    @Published private(set) var olderFailed = false
    @Published var hiddenDeviceIDs: Set<String> = []
    /// §4.4 — this refetch crossed into 100 for the first time.
    @Published private(set) var celebrate = false
    @Published private(set) var celebrateFrom: Int?
    @Published private(set) var refreshedAt: Date?

    private let isPreview: Bool
    private let store = SecurityOverviewStore.shared

    init(preview: SecurityOverviewResponse? = nil) {
        isPreview = preview != nil
        if let preview {
            overview = preview
            cursor = preview.eventsNextCursor
            refreshedAt = Date()
        } else {
            store.bind()
            overview = store.overview
            cursor = store.overview?.eventsNextCursor
            refreshedAt = store.fetchedAt
        }
    }

    var hasData: Bool { overview != nil }
    var failedWithoutCache: Bool { overview == nil && refreshFailed && !loading }

    func load() async {
        guard !isPreview, !loading else { return }
        loading = true
        defer { loading = false }
        let previous = store.overview?.score.value
        do {
            let fresh = try await store.fetch()
            overview = fresh
            cursor = fresh.eventsNextCursor
            olderEvents = []
            olderFailed = false
            hiddenDeviceIDs = []
            refreshFailed = false
            refreshedAt = store.fetchedAt
            noteCelebration(previous: previous, now: fresh.score.value)
        } catch is CancellationError {
            return
        } catch {
            refreshFailed = true
        }
    }

    func loadOlder() async {
        guard !isPreview, let cursor, !loadingOlder else { return }
        loadingOlder = true
        defer { loadingOlder = false }
        do {
            let page = try await store.fetch(eventsCursor: cursor)
            let known = Set((overview?.events ?? []).map(\.id) + olderEvents.map(\.id))
            olderEvents += page.events.filter { !known.contains($0.id) }
            self.cursor = page.eventsNextCursor
            olderFailed = false
        } catch {
            olderFailed = true
        }
    }

    /// §3.5 — the endpoint `device.removal` names. Answers the error to word.
    func remove(_ device: SecDevice) async -> Error? {
        guard let removal = device.removal, let token = SessionStore.token else {
            return ApiError.http(status: 404, code: "NOT_FOUND", message: "")
        }
        do {
            switch removal.via {
            case "trusted_device": _ = try await ApiClient.revokeDevice(token: token, id: removal.id)
            case "session": _ = try await ApiClient.revokeSession(token: token, id: removal.id)
            default: return ApiError.http(status: 400, code: nil, message: "")
            }
            hiddenDeviceIDs.insert(device.id)
            Task { await load() }
            return nil
        } catch {
            return error
        }
    }

    /// "This was me" (§3.3) — the existing ack route.
    func acknowledge(_ event: SecEvent) async -> Error? {
        guard let token = SessionStore.token else { return ApiError.http(status: 401, code: nil, message: "") }
        do {
            _ = try await ApiClient.acknowledgeSecurityEvent(token: token, id: event.id)
            Task { await load() }
            return nil
        } catch {
            return error
        }
    }

    private func noteCelebration(previous: Int?, now: Int) {
        guard let address = SessionStore.address else { return }
        let key = "gm.safety.celebrated.\(SecHash.accountKey(address))"
        if now >= 100 {
            if (previous ?? 0) < 100, !UserDefaults.standard.bool(forKey: key) {
                UserDefaults.standard.set(true, forKey: key)
                celebrateFrom = previous
                celebrate = true
            }
        } else {
            UserDefaults.standard.removeObject(forKey: key)
            celebrate = false
        }
    }

    /// §3.1 order — current first, then most recently active — and §3.7: the
    /// current device is always present, drawn locally if the server omitted it.
    var devices: [SecDevice] {
        var list = (overview?.devices ?? []).filter { !hiddenDeviceIDs.contains($0.id) }
        if overview != nil, !list.contains(where: \.current) {
            var local = SecDevice()
            local.id = "local-this-device"
            local.kind = "app"
            local.platform = "ios"
            local.formFactor = UIDevice.current.userInterfaceIdiom == .pad ? "tablet" : "phone"
            local.label = UIDevice.current.model
            local.current = true
            local.lastActiveAt = refreshedAt.map { ISO8601DateFormatter().string(from: $0) }
            list.append(local)
        }
        return list.sorted { lhs, rhs in
            if lhs.current != rhs.current { return lhs.current }
            return (SecTime.date(lhs.lastActiveAt) ?? .distantPast) > (SecTime.date(rhs.lastActiveAt) ?? .distantPast)
        }
    }

    var events: [SecEvent] {
        ((overview?.events ?? []) + olderEvents).sorted {
            (SecTime.date($0.occurredAt) ?? .distantPast) > (SecTime.date($1.occurredAt) ?? .distantPast)
        }
    }

    func device(_ id: String?) -> SecDevice? {
        guard let id else { return nil }
        return overview?.devices.first { $0.id == id }
    }
}

// MARK: - Time wording (§3.2)

enum SecTime {
    static func date(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty else { return nil }
        return ServerDate.parse(iso)
    }

    private static func relative(_ date: Date, now: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.dateTimeStyle = .named
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: now)
    }

    static func lastActive(_ device: SecDevice, now: Date = Date()) -> String {
        if device.current { return secL("Active now") }
        guard let at = date(device.lastActiveAt) else { return secL("Last active date unknown") }
        let age = now.timeIntervalSince(at)
        if age < 120 { return secL("Active now") }
        if age < 7 * 24 * 3600 { return secL("Active \(relative(at, now: now))") }
        return secL("Last active \(at.formatted(date: .abbreviated, time: .omitted))")
    }

    static func firstSeen(_ device: SecDevice) -> String? {
        guard let at = date(device.firstSeenAt) else { return nil }
        return secL("First seen \(at.formatted(date: .abbreviated, time: .omitted))")
    }

    static func dayHeader(_ date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return secL("Today") }
        if calendar.isDateInYesterday(date) { return secL("Yesterday") }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEEMMMd")
        return formatter.string(from: date)
    }

    static func timeOfDay(_ iso: String) -> String {
        date(iso)?.formatted(date: .omitted, time: .shortened) ?? ""
    }

    static func full(_ iso: String) -> String {
        date(iso)?.formatted(date: .long, time: .shortened) ?? iso
    }
}

// MARK: - Device presentation

enum SecDeviceCopy {
    static func icon(_ device: SecDevice) -> String {
        switch device.formFactor {
        case "phone": return "iphone"
        case "tablet": return "ipad"
        case "desktop": return "laptopcomputer"
        default: return device.kind == "browser" ? "laptopcomputer" : "questionmark.circle"
        }
    }

    static func title(_ device: SecDevice) -> String {
        device.label.isEmpty ? secL("Unknown device") : device.label
    }

    static func subtitle(_ device: SecDevice) -> String? {
        if device.kind == "app" {
            switch device.platform {
            case "android": return secL("GrapMail for Android")
            case "ios": return secL("GrapMail for iOS")
            default: return secL("GrapMail app")
            }
        }
        let parts = [device.browser, device.os].compactMap { $0?.nilIfBlank() }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    static func location(_ location: SecLocation?) -> String {
        location?.label?.nilIfBlank() ?? secL("Location unavailable")
    }

    static func thisDeviceBadge(_ device: SecDevice) -> String {
        device.kind == "browser" ? secL("This browser") : secL("This device")
    }
}

// MARK: - The screen

struct SecurityCenterView: View {
    let onBack: () -> Void
    /// A checklist row's destination (RootView routes it). `second_device`
    /// and `alerts_reviewed` are answered on this screen instead.
    let onAction: (SecChecklistID, Bool) -> Void
    /// "This wasn't me" succeeded: push the "Account secured" screen.
    let onSecured: (String) -> Void
    /// §3.6 banner → the existing change-password screen in required mode.
    let onChangePassword: () -> Void
    /// The current device's "Sign out" (the existing sign-out flow).
    let onSignOut: () -> Void
    /// Snapshot host / previews only: render this and never fetch.
    var preview: SecurityOverviewResponse? = nil

    @StateObject private var model: SecurityCenterModel
    @ObservedObject private var network = NetworkMonitor.shared
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var didAppear = false
    @State private var showAllDevices = false
    @State private var openEvent: SecEvent?
    @State private var notMe: NotMeRequest?
    @State private var securedLabel: String?
    @State private var pendingRemove: SecDevice?
    @State private var confirmSignOut = false
    @State private var howToSecondDevice = false
    @State private var snack: String?
    @State private var snackTask: Task<Void, Never>?
    @State private var scrollTarget: String?

    init(onBack: @escaping () -> Void,
         onAction: @escaping (SecChecklistID, Bool) -> Void,
         onSecured: @escaping (String) -> Void,
         onChangePassword: @escaping () -> Void,
         onSignOut: @escaping () -> Void,
         preview: SecurityOverviewResponse? = nil) {
        self.onBack = onBack
        self.onAction = onAction
        self.onSecured = onSecured
        self.onChangePassword = onChangePassword
        self.onSignOut = onSignOut
        self.preview = preview
        _model = StateObject(wrappedValue: SecurityCenterModel(preview: preview))
    }

    private var offline: Bool { network.online == false }
    /// §3.7: destructive actions need a live, fresh read.
    private var actionsDisabled: Bool { preview == nil && (offline || model.refreshFailed) }

    var body: some View {
        VStack(spacing: 0) {
            SecTopBar(title: secL("Devices & security"), onBack: onBack)
            if model.loading && model.hasData {
                ProgressView()
                    .progressViewStyle(.linear)
                    .tint(SecPalette.primaryText)
                    .frame(height: 2)
                    .accessibilityLabel(Text(secL("Refreshing")))
            }
            content
        }
        .background(GM.bg.ignoresSafeArea())
        .task {
            await model.load()
        }
        .onAppear {
            FeatureTips.securityCenterAppeared()
            // Return from any child screen refetches (§3.7); the first
            // appearance is the `.task` above.
            if didAppear { Task { await model.load() } } else { didAppear = true }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active, didAppear { Task { await model.load() } }
        }
        .sheet(item: $openEvent) { event in
            SecEventSheet(
                event: event,
                deviceLabel: model.device(event.deviceId).map(SecDeviceCopy.title) ?? event.deviceLabel,
                actionsDisabled: actionsDisabled,
                onWasMe: { await wasMe(event) },
                onNotMe: {
                    openEvent = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        notMe = NotMeRequest(target: .event(event.id),
                                             label: model.device(event.deviceId).map(SecDeviceCopy.title) ?? event.deviceLabel)
                    }
                },
                onClose: { openEvent = nil }
            )
            .grapMailSheetSurface()
        }
        .sheet(item: $notMe, onDismiss: {
            if let label = securedLabel {
                securedLabel = nil
                onSecured(label)
            }
        }) { request in
            NotMeConfirmSheet(
                request: request,
                onSecured: { label in
                    securedLabel = label
                    notMe = nil
                },
                onGone: { Task { await model.load() } },
                onCancel: { notMe = nil }
            )
            .grapMailSheetSurface()
        }
        .sheet(isPresented: $howToSecondDevice) {
            SecSecondDeviceSheet(onClose: { howToSecondDevice = false })
                .grapMailSheetSurface()
        }
        .alert(pendingRemove.map { secL("Remove \(SecDeviceCopy.title($0))?") } ?? "",
               isPresented: Binding(get: { pendingRemove != nil }, set: { if !$0 { pendingRemove = nil } })) {
            Button(secL("Cancel"), role: .cancel) { pendingRemove = nil }
            Button(secL("Remove"), role: .destructive) {
                if let device = pendingRemove { remove(device) }
                pendingRemove = nil
            }
        } message: {
            Text(secL("It will be signed out and will need your password to sign in again."))
        }
        .alert(secL("Sign out of this device?"), isPresented: $confirmSignOut) {
            Button(secL("Cancel"), role: .cancel) {}
            Button(secL("Sign out"), role: .destructive) { onSignOut() }
        }
        .overlay(alignment: .bottom) {
            if let snack {
                SecSnackbar(text: snack)
                    .padding(.bottom, 16)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sec-center")
    }

    // MARK: states

    @ViewBuilder private var content: some View {
        if let overview = model.overview {
            loaded(overview)
        } else if model.failedWithoutCache {
            errorState
        } else {
            skeleton
        }
    }

    private var skeleton: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SafetyScoreSkeleton()
                ForEach(0..<2, id: \.self) { _ in
                    SecSkeletonShape(height: 112, radius: GMShape.card)
                }
                ForEach(0..<4, id: \.self) { _ in
                    HStack(spacing: 12) {
                        SecSkeletonShape(width: 32, height: 32, radius: 16)
                        VStack(alignment: .leading, spacing: 6) {
                            SecSkeletonShape(width: 160, height: 12)
                            SecSkeletonShape(width: 220, height: 10)
                        }
                    }
                }
            }
            .padding(16)
            .gmMaxWidth(GMLayout.readable)
        }
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(secL("Loading your security info")))
        .accessibilityAddTraits(.updatesFrequently)
        .onAppear { GMAnnounce.say(secL("Loading your security info")) }
    }

    private var errorState: some View {
        ScrollView {
            VStack(spacing: 12) {
                Art.view("ill_sec_sessions", width: 220)
                Text(secL("Couldn't load your security info"))
                    .gmFont(.title)
                    .foregroundColor(GM.textPrimary)
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(secL("Check your connection and try again."))
                    .gmFont(.caption)
                    .foregroundColor(GM.textSecondary)
                    .multilineTextAlignment(.center)
                GradientButton(title: secL("Try again")) { Task { await model.load() } }
                    .frame(maxWidth: 320)
                    .padding(.top, 8)
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("gm-state-error")
    }

    private func loaded(_ overview: SecurityOverviewResponse) -> some View {
        GeometryReader { geo in
            ScrollViewReader { proxy in
                ScrollView {
                    let wide = geo.size.width >= 900
                    VStack(alignment: .leading, spacing: 16) {
                        SafetyScoreHeader(overview: overview,
                                          celebrate: model.celebrate,
                                          celebrateFrom: model.celebrateFrom,
                                          tipAnchor: preview == nil,
                                          onItem: { handle($0) })
                        bannerSlot(overview)
                        if wide {
                            HStack(alignment: .top, spacing: 24) {
                                devicesSection
                                    .frame(minWidth: 360, maxWidth: 480, alignment: .topLeading)
                                activitySection
                                    .frame(maxWidth: .infinity, alignment: .topLeading)
                            }
                        } else {
                            devicesSection
                            activitySection
                        }
                        footerNote
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 16)
                    .frame(maxWidth: wide ? 1100 : GMLayout.readable)
                    .frame(maxWidth: .infinity)
                }
                .refreshable { await model.load() }
                .onChange(of: scrollTarget) { target in
                    guard let target else { return }
                    withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) {
                        proxy.scrollTo(target, anchor: .top)
                    }
                    scrollTarget = nil
                }
            }
        }
    }

    @ViewBuilder private func bannerSlot(_ overview: SecurityOverviewResponse) -> some View {
        if overview.account.passwordChangeRequired {
            SecPasswordRequiredBanner(onChangePassword: onChangePassword)
        } else if actionsDisabled {
            SecStaleBanner(offline: offline, at: model.refreshedAt) { Task { await model.load() } }
        }
    }

    private var devicesSection: some View {
        let all = model.devices
        let shown = showAllDevices ? all : Array(all.prefix(5))
        return VStack(alignment: .leading, spacing: 12) {
            SecSectionLabel(text: secL("YOUR DEVICES · \(all.count)"))
                .id("sec-devices")
            ForEach(shown) { device in
                SecDeviceCard(
                    device: device,
                    actionsDisabled: actionsDisabled,
                    onNotMe: { notMe = NotMeRequest(target: .device(device.id), label: SecDeviceCopy.title(device)) },
                    onRemove: { pendingRemove = device },
                    onSignOut: signOutThisDevice
                )
                .transition(reduceMotion ? .identity : .opacity)
            }
            if all.count > 5 && !showAllDevices {
                Button { showAllDevices = true } label: {
                    Text(secL("Show all \(all.count) devices"))
                        .gmFont(.bodyMedium)
                        .foregroundColor(SecPalette.primaryText)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
            }
            if actionsDisabled {
                Text(secL("Connect to the internet to do this."))
                    .gmFont(.caption)
                    .foregroundColor(GM.textSecondary)
            }
        }
    }

    private var activitySection: some View {
        let events = model.events
        let firstReview = events.first(where: \.needsReview)?.id
        return VStack(alignment: .leading, spacing: 12) {
            SecSectionLabel(text: secL("RECENT SECURITY ACTIVITY"))
                .id("sec-activity")
            if events.isEmpty {
                VStack(spacing: 8) {
                    Art.view("ill_sec_activity", width: 320)
                        .frame(height: 160)
                    Text(secL("No security activity in the last 30 days"))
                        .gmFont(.title)
                        .foregroundColor(GM.textPrimary)
                        .multilineTextAlignment(.center)
                    Text(secL("Sign-ins, password changes and new devices will show up here."))
                        .gmFont(.caption)
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            } else {
                SecTimeline(events: events, firstReviewID: firstReview,
                            label: { model.device($0.deviceId).map(SecDeviceCopy.title) ?? $0.deviceLabel },
                            onOpen: { openEvent = $0 })
                if model.cursor != nil {
                    Button { Task { await model.loadOlder() } } label: {
                        HStack(spacing: 8) {
                            if model.loadingOlder { ProgressView() }
                            Text(model.olderFailed ? secL("Couldn't load older activity. Try again") : secL("Show older activity"))
                                .gmFont(.bodyMedium)
                                .foregroundColor(SecPalette.primaryText)
                        }
                        .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .disabled(model.loadingOlder)
                }
            }
        }
    }

    private var footerNote: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "shield")
                .gmFont(15, maxSize: 24)
                .foregroundColor(GM.textSecondary)
                .accessibilityHidden(true)
            Text(secL("Locations are approximate and based on your network."))
                .gmFont(.caption)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 8)
    }

    // MARK: actions

    /// The current device's "Sign out". With welcome-back on, RootView's
    /// sign-out sheet (with "Also remove from this device") is the one
    /// confirmation, so this screen must not ask first as well. With it off,
    /// RootView signs out at once, so this screen's own alert is the only
    /// confirmation and stays.
    private func signOutThisDevice() {
        // The same test RootView.signOutOfMailbox uses to decide whether it
        // shows its sheet.
        if WelcomeBackGate.isEnabled, SessionStore.address?.contains("@") == true {
            onSignOut()
        } else {
            confirmSignOut = true
        }
    }

    private func handle(_ item: SecChecklistItem) {
        switch item.id {
        case .secondDevice:
            scrollTarget = "sec-devices"
            if !item.isDone { howToSecondDevice = true }
        case .alertsReviewed:
            scrollTarget = model.events.first(where: \.needsReview)?.id ?? "sec-activity"
        default:
            onAction(item.id, item.isDone)
        }
    }

    private func remove(_ device: SecDevice) {
        Task {
            let error: Error?
            if preview != nil { error = nil } else { error = await model.remove(device) }
            if let error {
                GMHaptics.failure()
                show(apiMessage(error, fallback: secL("Couldn't reach GrapMail. Nothing was changed.")))
            } else {
                GMHaptics.success()
                withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) {
                    model.hiddenDeviceIDs.insert(device.id)
                }
                show(secL("\(SecDeviceCopy.title(device)) removed"))
            }
        }
    }

    private func wasMe(_ event: SecEvent) async {
        if preview != nil { openEvent = nil; return }
        if let error = await model.acknowledge(event) {
            GMHaptics.failure()
            show(apiMessage(error, fallback: secL("Couldn't reach GrapMail. Nothing was changed.")))
        } else {
            GMHaptics.success()
            openEvent = nil
        }
    }

    private func show(_ text: String) {
        snackTask?.cancel()
        GMAnnounce.say(text)
        withAnimation(GMMotion.gated(GMMotion.emphasizedDecelerate(), reduced: reduceMotion)) { snack = text }
        snackTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) { snack = nil }
        }
    }
}

// MARK: - Pieces

/// Back + title, 56 high, the shape of the app's own bars.
struct SecTopBar: View {
    let title: String
    let onBack: () -> Void
    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 56

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .gmFont(17, .semibold, maxSize: 28)
                    .foregroundColor(GM.textPrimary)
                    .flipsForRightToLeftLayoutDirection(true)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(Text(secL("Back")))
            Text(title)
                .gmFont(.title)
                .foregroundColor(GM.textPrimary)
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8)
        .frame(minHeight: height)
    }
}

struct SecSectionLabel: View {
    let text: String
    var body: some View {
        Text(text)
            .gmFont(.micro)
            .kerning(1.2)
            .foregroundColor(GM.textSecondary)
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

/// §3.7 — cached data shown after a failed or offline refresh.
struct SecStaleBanner: View {
    let offline: Bool
    let at: Date?
    let onRetry: () -> Void
    @Environment(\.dynamicTypeSize) private var dts

    private var text: String {
        let time = at?.formatted(date: .omitted, time: .shortened) ?? "—"
        return offline ? secL("You're offline. Showing what we last saw at \(time).")
                       : secL("Couldn't refresh. Showing what we last saw at \(time).")
    }

    var body: some View {
        GMAdaptiveStack(spacing: 8) {
            Text(text)
                .gmFont(.caption)
                .foregroundColor(SecPalette.warning)
                .fixedSize(horizontal: false, vertical: true)
            if !dts.isAccessibilitySize { Spacer(minLength: 4) }
            Button(action: onRetry) {
                Text(secL("Retry"))
                    .gmFont(.captionMedium)
                    .foregroundColor(SecPalette.primaryText)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(SecPalette.warningContainer))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("sec-stale-banner")
    }
}

struct SecBadge: View {
    let text: String
    var icon: String? = nil
    let fill: Color
    let ink: Color
    @ScaledMetric(relativeTo: .caption) private var height: CGFloat = 24

    var body: some View {
        HStack(spacing: 4) {
            if let icon {
                Image(systemName: icon).gmFont(12, .semibold, maxSize: 20).accessibilityHidden(true)
            }
            Text(text).gmFont(.captionMedium).lineLimit(1)
        }
        .foregroundColor(ink)
        .padding(.horizontal, 10)
        .frame(minHeight: height)
        .background(Capsule().fill(fill))
    }
}

/// §3.2 device card: icon disc, label, subtitle, badges, location, last
/// active · first seen, and the ⋮ menu. One accessibility element for the
/// card; the menu is its own element, its actions also exposed as custom
/// actions on the card.
struct SecDeviceCard: View {
    let device: SecDevice
    let actionsDisabled: Bool
    let onNotMe: () -> Void
    let onRemove: () -> Void
    let onSignOut: () -> Void
    @ScaledMetric(relativeTo: .body) private var disc: CGFloat = 40

    private var badges: [String] {
        var out: [String] = []
        if device.current { out.append(SecDeviceCopy.thisDeviceBadge(device)) }
        if device.trusted { out.append(secL("Trusted")) }
        return out
    }

    private var metaLine: String {
        [SecTime.lastActive(device), SecTime.firstSeen(device)].compactMap { $0 }.joined(separator: " · ")
    }

    private var cardLabel: String {
        [SecDeviceCopy.title(device), SecDeviceCopy.subtitle(device), badges.isEmpty ? nil : badges.joined(separator: ", "),
         SecDeviceCopy.location(device.location), SecTime.lastActive(device)]
            .compactMap { $0 }.joined(separator: ", ")
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: SecDeviceCopy.icon(device))
                        .gmFont(20, .medium, maxSize: 32)
                        .foregroundColor(GM.textSecondary)
                        .frame(width: disc, height: disc)
                        .background(Circle().fill(GM.softFill))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(SecDeviceCopy.title(device))
                            .gmFont(.title)
                            .foregroundColor(GM.textPrimary)
                            .lineLimit(1)
                        if let subtitle = SecDeviceCopy.subtitle(device) {
                            Text(subtitle)
                                .gmFont(.caption)
                                .foregroundColor(GM.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                if device.current || device.trusted {
                    HStack(spacing: 8) {
                        if device.current {
                            SecBadge(text: SecDeviceCopy.thisDeviceBadge(device),
                                     fill: SecPalette.primaryContainer, ink: SecPalette.onPrimaryContainer)
                        }
                        if device.trusted {
                            SecBadge(text: secL("Trusted"), icon: "checkmark.shield",
                                     fill: SecPalette.successContainer, ink: SecPalette.success)
                        }
                    }
                    .padding(.top, 2)
                }
                HStack(spacing: 6) {
                    Image(systemName: "mappin.and.ellipse")
                        .gmFont(13, maxSize: 20)
                        .accessibilityHidden(true)
                    Text(SecDeviceCopy.location(device.location))
                        .gmFont(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundColor(GM.textSecondary)
                Text(metaLine)
                    .gmFont(.caption)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(cardLabel))
            .modifier(SecDeviceActions(device: device, enabled: !actionsDisabled,
                                       onNotMe: onNotMe, onRemove: onRemove, onSignOut: onSignOut))
            Spacer(minLength: 0)
            menu
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
        .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sec-device-\(device.id)")
    }

    private var menu: some View {
        Menu {
            if device.current {
                Button(role: .destructive, action: onSignOut) {
                    Label(secL("Sign out"), systemImage: "rectangle.portrait.and.arrow.right")
                }
            } else {
                if device.notMeAllowed {
                    Button(role: .destructive, action: onNotMe) {
                        Label(secL("This wasn't me"), systemImage: "exclamationmark.shield")
                    }
                    .disabled(actionsDisabled)
                }
                if device.removal != nil {
                    Button(action: onRemove) {
                        Label(secL("Remove device"), systemImage: "minus.circle")
                    }
                    .disabled(actionsDisabled)
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .rotationEffect(.degrees(90))
                .gmFont(17, .semibold, maxSize: 28)
                .foregroundColor(GM.textSecondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(Text(secL("More options for \(SecDeviceCopy.title(device))")))
    }
}

/// The card's custom accessibility actions (§3.2).
private struct SecDeviceActions: ViewModifier {
    let device: SecDevice
    let enabled: Bool
    let onNotMe: () -> Void
    let onRemove: () -> Void
    let onSignOut: () -> Void

    func body(content: Content) -> some View {
        if device.current {
            content.accessibilityAction(named: Text(secL("Sign out")), onSignOut)
        } else if enabled {
            content
                .accessibilityAction(named: Text(secL("This wasn't me"))) { if device.notMeAllowed { onNotMe() } }
                .accessibilityAction(named: Text(secL("Remove device"))) { if device.removal != nil { onRemove() } }
        } else {
            content
        }
    }
}

// MARK: - Timeline (§3.3)

enum SecEventCopy {
    static func title(_ type: SecEventType) -> String {
        switch type {
        case .signin: return secL("Signed in")
        case .signinNewDevice: return secL("New device signed in")
        case .signinSuspicious: return secL("Unusual sign-in attempt")
        case .signinBlocked: return secL("Sign-in blocked")
        case .passwordChanged: return secL("Password changed")
        case .passkeyAdded: return secL("Passkey added")
        case .passkeyRemoved: return secL("Passkey removed")
        case .twoStepEnabled: return secL("2-step verification turned on")
        case .twoStepDisabled: return secL("2-step verification turned off")
        case .recoveryRequested: return secL("Account recovery requested")
        case .recoveryUsed: return secL("Recovery used to sign in")
        case .recoveryCodesGenerated: return secL("New recovery codes created")
        case .deviceAdded: return secL("Device added as trusted")
        case .deviceRemoved: return secL("Device removed")
        case .sessionSignedOut: return secL("Signed out remotely")
        case .notMeReported: return secL("You reported activity as not you")
        case .accountCreated: return secL("Account created")
        default: return secL("Security activity")
        }
    }

    static func icon(_ type: SecEventType) -> String {
        switch type {
        case .signin: return "arrow.right.circle"
        case .signinNewDevice: return "plus.app"
        case .signinSuspicious: return "exclamationmark.shield"
        case .signinBlocked: return "hand.raised"
        case .passwordChanged: return "key"
        case .passkeyAdded: return "person.badge.key"
        case .passkeyRemoved: return "person.crop.circle.badge.minus"
        case .twoStepEnabled: return "checkmark.shield"
        case .twoStepDisabled: return "shield.slash"
        case .recoveryRequested, .recoveryUsed: return "lifepreserver"
        case .recoveryCodesGenerated: return "list.bullet.rectangle"
        case .deviceAdded: return "lock.shield"
        case .deviceRemoved: return "minus.circle"
        case .sessionSignedOut: return "rectangle.portrait.and.arrow.right"
        case .notMeReported: return "flag"
        case .accountCreated: return "envelope.badge"
        default: return "shield"
        }
    }

    enum Tone { case soft, primary, warning, error, success }

    static func tone(_ event: SecEvent) -> Tone {
        let base: Tone
        switch event.type {
        case .signinNewDevice, .accountCreated: base = .primary
        case .signinSuspicious, .twoStepDisabled, .recoveryRequested, .recoveryUsed: base = .warning
        case .signinBlocked, .notMeReported: base = .error
        case .passkeyAdded, .twoStepEnabled: base = .success
        default: base = .soft
        }
        switch event.risk.lowercased() {
        case "high": return .error
        case "medium": return base == .error ? .error : .warning
        default: return base
        }
    }

    static func fill(_ tone: Tone) -> Color {
        switch tone {
        case .soft: return GM.softFill
        case .primary: return SecPalette.primaryContainer
        case .warning: return SecPalette.warningContainer
        case .error: return SecPalette.errorContainer
        case .success: return SecPalette.successContainer
        }
    }

    static func ink(_ tone: Tone) -> Color {
        switch tone {
        case .soft: return GM.textSecondary
        case .primary: return SecPalette.primaryText
        case .warning: return SecPalette.warning
        case .error: return SecPalette.error
        case .success: return SecPalette.success
        }
    }
}

struct SecTimeline: View {
    let events: [SecEvent]
    let firstReviewID: String?
    let label: (SecEvent) -> String?
    let onOpen: (SecEvent) -> Void

    private var days: [(day: Date, events: [SecEvent])] {
        let calendar = Calendar.current
        var order: [Date] = []
        var groups: [Date: [SecEvent]] = [:]
        for event in events {
            let day = calendar.startOfDay(for: SecTime.date(event.occurredAt) ?? .distantPast)
            if groups[day] == nil { order.append(day) }
            groups[day, default: []].append(event)
        }
        return order.map { ($0, groups[$0] ?? []) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(days, id: \.day) { group in
                Text(SecTime.dayHeader(group.day))
                    .gmFont(.captionMedium)
                    .foregroundColor(GM.textSecondary)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 4)
                VStack(spacing: 0) {
                    ForEach(Array(group.events.enumerated()), id: \.element.id) { index, event in
                        SecEventRow(event: event, deviceLabel: label(event),
                                    isFirst: index == 0, isLast: index == group.events.count - 1,
                                    onOpen: { onOpen(event) })
                            .id(event.id)
                    }
                }
                .padding(.horizontal, 12)
                .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
            }
        }
    }
}

struct SecEventRow: View {
    let event: SecEvent
    let deviceLabel: String?
    let isFirst: Bool
    let isLast: Bool
    let onOpen: () -> Void
    @ScaledMetric(relativeTo: .body) private var disc: CGFloat = 32

    private var detail: String {
        [deviceLabel?.nilIfBlank(), event.location?.label?.nilIfBlank(), SecTime.timeOfDay(event.occurredAt).nilIfBlank()]
            .compactMap { $0 }.joined(separator: " · ")
    }

    private var chip: (String, Color, Color)? {
        if event.reported { return (secL("Reported"), SecPalette.errorContainer, SecPalette.error) }
        if event.needsReview { return (secL("Review"), SecPalette.warningContainer, SecPalette.warning) }
        return nil
    }

    var body: some View {
        let tone = SecEventCopy.tone(event)
        Button(action: onOpen) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: SecEventCopy.icon(event.type))
                    .gmFont(14, .semibold, maxSize: 24)
                    .foregroundColor(SecEventCopy.ink(tone))
                    .frame(width: disc, height: disc)
                    .background(Circle().fill(SecEventCopy.fill(tone)))
                    .padding(.top, 12)
                VStack(alignment: .leading, spacing: 2) {
                    Text(SecEventCopy.title(event.type))
                        .gmFont(.bodyMedium)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    if !detail.isEmpty {
                        Text(detail)
                            .gmFont(.caption)
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.vertical, 12)
                Spacer(minLength: 8)
                if let chip {
                    SecBadge(text: chip.0, fill: chip.1, ink: chip.2)
                        .padding(.top, 14)
                }
            }
            .contentShape(Rectangle())
            .background(alignment: .topLeading) {
                // The 2 pt connector between discs, broken at the day's ends.
                VStack(spacing: 0) {
                    Rectangle().fill(isFirst ? Color.clear : GM.border).frame(width: 2, height: 12 + disc / 2)
                    Rectangle().fill(isLast ? Color.clear : GM.border).frame(width: 2)
                }
                .padding(.leading, disc / 2 - 1)
                .accessibilityHidden(true)
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text([SecEventCopy.title(event.type), detail, chip?.0].compactMap { $0?.nilIfBlank() }.joined(separator: ", ")))
        .accessibilityAddTraits(.isButton)
    }
}

/// §3.3 event sheet: When, Device, Location, Approximate network; "This was
/// me" / "This wasn't me" when allowed, otherwise Close.
struct SecEventSheet: View {
    let event: SecEvent
    let deviceLabel: String?
    let actionsDisabled: Bool
    let onWasMe: () async -> Void
    let onNotMe: () -> Void
    let onClose: () -> Void
    @State private var busy = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(SecEventCopy.title(event.type))
                    .gmFont(.h2)
                    .foregroundColor(GM.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                VStack(alignment: .leading, spacing: 0) {
                    row(secL("When"), SecTime.full(event.occurredAt))
                    row(secL("Device"), deviceLabel?.nilIfBlank() ?? secL("Unknown device"))
                    row(secL("Location"), SecDeviceCopy.location(event.location))
                    if let ip = event.ipMasked?.nilIfBlank() { row(secL("Approximate network"), ip) }
                }
                .padding(.horizontal, 16)
                .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
                if event.notMeAllowed {
                    OutlineActionButton(title: busy ? secL("Saving…") : secL("This was me")) {
                        guard !busy else { return }
                        busy = true
                        Task { await onWasMe(); busy = false }
                    }
                    .disabled(actionsDisabled || busy)
                    DestructiveActionButton(title: secL("This wasn't me"), enabled: !actionsDisabled, action: onNotMe)
                    if actionsDisabled {
                        Text(secL("Connect to the internet to do this."))
                            .gmFont(.caption)
                            .foregroundColor(GM.textSecondary)
                    }
                } else {
                    OutlineActionButton(title: secL("Close"), action: onClose)
                }
            }
            .padding(24)
            .gmMaxWidth(GMLayout.form)
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ label: String, _ value: String) -> some View {
        GMAdaptiveStack(spacing: 8, verticalAlignment: .top) {
            Text(label)
                .gmFont(.caption)
                .foregroundColor(GM.textSecondary)
            Spacer(minLength: 8)
            Text(value)
                .gmFont(.bodyMedium)
                .foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }
}

/// §4.2 `second_device` how-to.
struct SecSecondDeviceSheet: View {
    let onClose: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(secL("Sign in on a second device"))
                .gmFont(.h2)
                .foregroundColor(GM.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Text(secL("Install GrapMail on another phone or tablet and sign in. It becomes trusted automatically."))
                .gmFont(.body)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            GradientButton(title: secL("Got it"), action: onClose)
        }
        .padding(24)
        .gmMaxWidth(GMLayout.form)
        .presentationDetents([.medium])
    }
}

struct SecSnackbar: View {
    let text: String
    var body: some View {
        Text(text)
            .gmFont(.bodyMedium)
            .foregroundColor(GM.bg)
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous).fill(GM.textPrimary))
            .padding(.horizontal, 16)
            .gmMaxWidth(GMLayout.form)
            .accessibilityAddTraits(.isStaticText)
    }
}

// MARK: - Snapshot / preview fixture (§6.2 example)

extension SecurityOverviewResponse {
    /// The §6.2 example body, for the CI snapshot host and previews. Never
    /// used by a real launch.
    static var sample: SecurityOverviewResponse {
        let json = """
        {"success":true,"generatedAt":"2026-11-24T07:00:00Z",
         "account":{"address":"olivier@grapmail.com","displayName":"Olivier Pricious","hasPassword":true,"passwordChangeRequired":false},
         "score":{"version":1,"value":55,"level":"needs_attention","earnedPoints":55,"possiblePoints":100},
         "checklist":[
          {"id":"two_step","state":"done","points":25,"earned":25,"action":"security.two_step"},
          {"id":"passkey","state":"todo","points":20,"earned":0,"action":"security.passkeys","meta":{"count":0}},
          {"id":"recovery_codes","state":"todo","points":15,"earned":0,"action":"security.recovery_codes","meta":{"remaining":0}},
          {"id":"recovery_email","state":"done","points":10,"earned":10,"action":"account.recovery_email"},
          {"id":"second_device","state":"done","points":10,"earned":10,"action":"security.devices","meta":{"count":2}},
          {"id":"password_health","state":"todo","points":10,"earned":0,"action":"security.password","meta":{"status":"breached","lastChangedAt":"2025-03-01T10:00:00Z"}},
          {"id":"recovery_phone","state":"done","points":5,"earned":5,"action":"account.phone"},
          {"id":"alerts_reviewed","state":"done","points":5,"earned":5,"action":"security.activity","meta":{"count":0}}],
         "devices":[
          {"id":"td_snapshot","kind":"app","platform":"ios","formFactor":"phone","label":"iPhone 15","browser":null,"os":"iOS 18",
           "current":true,"trusted":true,"attestation":"app_attest","location":{"label":"Dhaka, Bangladesh","city":"Dhaka","region":"Dhaka Division","country":"BD"},
           "firstSeenAt":"2026-03-12T09:14:00Z","lastActiveAt":"2026-11-24T06:59:10Z","activeSessionCount":1,"removal":null,"notMeAllowed":false},
          {"id":"ss_snapshot","kind":"browser","platform":"windows","formFactor":"desktop","label":"Chrome on Windows","browser":"Chrome","os":"Windows",
           "current":false,"trusted":false,"attestation":null,"location":{"label":"Chattogram, Bangladesh","city":"Chattogram","region":null,"country":"BD"},
           "ipMasked":"27.147.x.x","firstSeenAt":"2026-11-20T15:02:00Z","lastActiveAt":"2026-11-23T18:40:00Z","activeSessionCount":1,
           "removal":{"via":"session","id":"snapshot-session"},"notMeAllowed":true}],
         "events":[
          {"id":"ev_snapshot","type":"signin_new_device","occurredAt":"2026-11-20T15:02:00Z","deviceId":"ss_snapshot","deviceLabel":"Chrome on Windows",
           "location":{"label":"Chattogram, Bangladesh","city":"Chattogram","region":null,"country":"BD"},"risk":"medium","action":"ALERT","factor":null,
           "acknowledged":false,"reported":false,"notMeAllowed":true}],
         "eventsNextCursor":null}
        """
        return (try? JSONDecoder().decode(SecurityOverviewResponse.self, from: Data(json.utf8))) ?? SecurityOverviewResponse()
    }
}
