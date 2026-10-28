import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

// MARK: - Step 1 · Name — founder design verbatim

struct NameStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    var onSignIn: (() -> Void)? = nil
    let onNext: () -> Void

    @State private var showErrors = false

    private var firstError: String? { showErrors && model.state.firstName.isEmpty ? "First name is required" : nil }
    private var lastError: String? { showErrors && model.state.lastName.isEmpty ? "Last name is required" : nil }

    var body: some View {
        WizardScaffold(
            step: 1,
            title: "What's your name?",
            subtitle: Text("Let's get started. This will be used for your GrapMail account and profile."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: {
                Image("ill_step1_name")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: .infinity)
                    // No fixed height: the artwork keeps its intrinsic ratio
                    // and scales with the (centered, capped) form width.

                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        (Text("First name").foregroundColor(GM.textPrimary)
                         + Text(" *").foregroundColor(GM.error))
                            .font(.system(size: 14, weight: .semibold))
                        AppTextField(
                            value: Binding(get: { model.state.firstName }, set: { newValue in model.update { $0.firstName = newValue } }),
                            hint: "Enter your first name",
                            leadingIcon: "person"
                        )
                        if let firstError {
                            FieldErrorRow(text: firstError)
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        (Text("Last name").foregroundColor(GM.textPrimary)
                         + Text(" *").foregroundColor(GM.error))
                            .font(.system(size: 14, weight: .semibold))
                        AppTextField(
                            value: Binding(get: { model.state.lastName }, set: { newValue in model.update { $0.lastName = newValue } }),
                            hint: "Enter your last name",
                            leadingIcon: "person"
                        )
                        if let lastError {
                            FieldErrorRow(text: lastError)
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }
                    }
                }
                .padding(.top, 24)
            },
            bottom: {
                GradientButton(
                    title: "Next",
                    enabled: !model.state.firstName.isEmpty && !model.state.lastName.isEmpty,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) {
                    if model.state.firstName.isEmpty || model.state.lastName.isEmpty {
                        withAnimation(.easeOut(duration: 0.2)) { showErrors = true }
                    } else {
                        onNext()
                    }
                }
            }
        )
    }
}

// MARK: - Step 2 · Basic Info — founder design verbatim

struct BasicInfoView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    var onSignIn: (() -> Void)? = nil
    let onNext: () -> Void

    // The real photo picker (the design ships the control; the old build
    // had a dead label). Held locally — the account API takes no photo.
    @State private var photoItem: PhotosPickerItem?
    @State private var photoData: Data?

    private let months = ["January", "February", "March", "April", "May", "June",
                          "July", "August", "September", "October", "November", "December"]
    private let days = Array(1...31).map(String.init)
    private var years: [String] {
        // Same rule as Android: under-13 years are not offered at all, and
        // the floor is 1936 — one shared contract, not two drifting lists.
        let maxYear = Calendar.current.component(.year, from: Date()) - 13
        return (1936...maxYear).map(String.init).reversed()
    }

    private var birthday: (iso: String?, error: String?) {
        Validators.birthday(
            year: Int(model.state.bYear),
            month: model.state.bMonth.isEmpty ? nil : months.firstIndex(of: model.state.bMonth).map { $0 + 1 },
            day: Int(model.state.bDay)
        )
    }

    var body: some View {
        WizardScaffold(
            step: 2,
            title: "Basic Info",
            subtitle: Text("Tell us a little more about yourself. This helps us make your experience more personal."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: { basicInfoContent },
            bottom: { basicInfoBottom }
        )
    }

    // Extracted from `body` into its own @ViewBuilder: the whole wizard body as
    // one expression is past the point the Swift type-checker resolves in
    // reasonable time. Splitting the content and the footer into separate units
    // keeps the rendered output identical while making each inferable.
    @ViewBuilder private var basicInfoContent: some View {
        photoCard
        birthdaySection
        genderSection
    }

    @ViewBuilder private var photoCard: some View {
                HStack(alignment: .top, spacing: 16) {
                    photoAvatar
                    photoCopy
                }
                .padding(16)
                .overlay(RoundedRectangle(cornerRadius: GMShape.card).strokeBorder(GM.border))
                .padding(.top, 4)
    }

    private var photoAvatar: some View {
        ZStack(alignment: .bottomTrailing) {
            Circle()
                .fill(GM.softFill)
                .frame(width: 72, height: 72)
                .overlay(
                    Group {
                        if let photoData, let ui = UIImage(data: photoData) {
                            Image(uiImage: ui)
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        } else {
                            Image(systemName: "person.fill")
                                .font(.system(size: 30))
                                .foregroundColor(Color(0x8FA8E8))
                        }
                    }
                )
                .clipShape(Circle())
            Circle()
                .fill(GM.primarySolid)
                .frame(width: 26, height: 26)
                .overlay(
                    Image(systemName: "camera.fill")
                        .font(.system(size: 11))
                        .foregroundColor(.white)
                )
        }
    }

    // The `Text + Text` concatenation is its own typed property: inline in the
    // VStack it is the expression the type-checker cannot resolve in time.
    private var photoTitle: Text {
        Text("Profile Photo ").font(.system(size: 15, weight: .semibold)).foregroundColor(GM.textPrimary)
        + Text("(Optional)").font(.system(size: 13)).foregroundColor(GM.textTertiary)
    }

    @ViewBuilder private var photoCopy: some View {
        VStack(alignment: .leading, spacing: 4) {
            photoTitle
            Text("Add a photo to make your account more personal. You can change this later in settings.")
                .font(GMFont.caption)
                .foregroundColor(GM.textSecondary)
            PhotosPicker(selection: $photoItem, allowedContentTypes: [.image]) {
                Text(photoData == nil ? "Choose Photo" : "Change Photo")
                    .font(GMFont.captionMedium)
                    .foregroundColor(GM.primarySolid)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(GM.border))
            }
            .buttonStyle(.plain)
            .onChange(of: photoItem) { item in
                Task {
                    if let item { photoData = try? await item.loadTransferable(type: Data.self) }
                }
            }
            .padding(.top, 8)
        }
    }

    @ViewBuilder private var birthdaySection: some View {
                // Birthday
                HStack(spacing: 8) {
                    Image(systemName: "calendar")
                        .font(.system(size: 15))
                        .foregroundColor(GM.textPrimary)
                    Text("Birthday ").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                    Text("(Required)").font(.system(size: 13)).foregroundColor(GM.textTertiary)
                }
                .padding(.top, 22)

                HStack(alignment: .top, spacing: 10) {
                    InlinePicker(
                        placeholder: "Month",
                        value: model.state.bMonth,
                        options: months
                    ) { pick in model.update { $0.bMonth = pick } }
                    .frame(maxWidth: .infinity)

                    InlinePicker(
                        placeholder: "Day",
                        value: model.state.bDay,
                        options: days
                    ) { pick in model.update { $0.bDay = pick } }
                    .frame(maxWidth: .infinity)

                    InlinePicker(
                        placeholder: "Year",
                        value: model.state.bYear,
                        options: years
                    ) { pick in model.update { $0.bYear = pick } }
                    .frame(maxWidth: .infinity)
                }

                if let err = birthday.error {
                    Text(err).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 8)
                } else {
                    HStack(spacing: 6) {
                        Image(systemName: "info.circle.fill")
                            .font(.system(size: 12))
                            .foregroundColor(GM.primarySolid)
                        Text("You must be 13 or older to create an account.")
                            .font(.system(size: 12))
                            .foregroundColor(GM.textTertiary)
                    }
                    .padding(.top, 8)
                }
    }

    @ViewBuilder private var genderSection: some View {
                // Gender
                HStack(spacing: 8) {
                    Image(systemName: "figure.dress.line.vertical.figure")
                        .font(.system(size: 15))
                        .foregroundColor(GM.textPrimary)
                    Text("Gender ").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                    Text("(Optional)").font(.system(size: 13)).foregroundColor(GM.textTertiary)
                }
                .padding(.top, 22)

                let genders = ["Female", "Male", "Rather not say", "Custom"]
                let icons = ["figure.arms.open", "figure.stand", "person", "person.badge.plus"]
                VStack(spacing: 10) {
                    ForEach(0..<2, id: \.self) { row in
                        HStack(spacing: 10) {
                            ForEach(0..<2, id: \.self) { col in
                                let idx = row * 2 + col
                                genderCard(genders[idx], icons[idx])
                            }
                        }
                    }
                }
                .padding(.top, 10)
    }

    private var basicInfoBottom: some View {
                GradientButton(
                    title: "Next",
                    enabled: birthday.iso != nil && birthday.error == nil,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) {
                    let b = birthday
                    model.update { $0.birthdayIso = b.iso }
                    if b.iso != nil && b.error == nil { onNext() }
                }
    }

    private func genderCard(_ label: String, _ icon: String) -> some View {
        let selected = model.state.gender == label
        return Button {
            model.update { $0.gender = selected ? nil : label }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 14))
                    .foregroundColor(selected ? GM.primarySolid : GM.textTertiary)
                Text(label)
                    .font(.system(size: 13, weight: selected ? .semibold : .regular))
                    .foregroundColor(selected ? GM.primarySolid : GM.textPrimary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .frame(height: 46)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(selected ? GM.primarySolid.opacity(0.06) : GM.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(selected ? GM.primarySolid : GM.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Step 3 · Address — founder design verbatim

struct AddressStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    var onSignIn: (() -> Void)? = nil
    let onNext: () -> Void

    var body: some View {
        WizardScaffold(
            step: 3,
            title: "Choose your GrapMail address",
            subtitle: Text("Pick an available address or create your own. You'll use this to sign in and receive emails."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: {
                // Suggested for you
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        Image(systemName: "sparkles")
                            .font(.system(size: 15))
                            .foregroundColor(GM.primarySolid)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Suggested for you").font(.system(size: 15, weight: .semibold)).foregroundColor(GM.textPrimary)
                            Text("Based on your name. You can change it later.")
                                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                        }
                    }
                    if model.state.suggestionsLoading {
                        Text("Finding available addresses…").font(GMFont.caption).foregroundColor(GM.textTertiary)
                    } else {
                        ForEach(model.state.suggestions, id: \.self) { handle in
                            radioRow("\(handle)@grapsee.com", selected: model.state.chosenHandle == handle && !model.state.customOpen) {
                                model.pickSuggestion(handle)
                            }
                        }
                        if model.state.suggestions.isEmpty {
                            Text("No suggestions from your name — create your own below.")
                                .font(GMFont.caption).foregroundColor(GM.textTertiary)
                        }
                    }
                }
                .padding(16)
                .overlay(RoundedRectangle(cornerRadius: GMShape.card).strokeBorder(GM.border))
                .padding(.top, 4)

                // Create your own
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        Image(systemName: "pencil")
                            .font(.system(size: 14))
                            .foregroundColor(GM.primarySolid)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Create your own").font(.system(size: 15, weight: .semibold)).foregroundColor(GM.textPrimary)
                            Text("Choose a unique address (3-30 characters).")
                                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                        }
                    }
                    HStack(spacing: 8) {
                        AppTextField(
                            value: Binding(get: { model.state.customHandle }, set: model.onCustomHandleChange),
                            hint: "yourname"
                        )
                        // The design's bordered domain chip with a chevron —
                        // the backend serves one domain today, so the menu
                        // lists one.
                        Menu {
                            Text("@grapsee.com")
                        } label: {
                            HStack(spacing: 6) {
                                Text("@grapsee.com")
                                    .font(.system(size: 14))
                                    .foregroundColor(GM.textSecondary)
                                Image(systemName: "chevron.down")
                                    .font(.system(size: 11))
                                    .foregroundColor(GM.textTertiary)
                            }
                            .padding(.horizontal, 12)
                            .frame(height: 54)
                            .background(
                                RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                                    .fill(GM.inputFill)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                                    .strokeBorder(GM.border)
                            )
                        }
                    }
                    statusText
                    Text("Use letters, numbers, dots (.), dashes (-) or underscores (_).\nAvoid reserved names like admin, support, postmaster, etc.")
                        .font(.system(size: 12))
                        .foregroundColor(GM.textTertiary)
                        .lineSpacing(3)
                }
                .padding(16)
                .overlay(RoundedRectangle(cornerRadius: GMShape.card).strokeBorder(GM.border))
                .padding(.top, 14)
            },
            bottom: {
                GradientButton(
                    title: "Next",
                    enabled: !model.state.chosenHandle.isEmpty,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { onNext() }
            }
        )
        .onAppear {
            if model.state.suggestions.isEmpty { model.loadSuggestions() }
        }
    }

    @ViewBuilder
    private var statusText: some View {
        switch model.state.handleStatus {
        case .idle: EmptyView()
        case .checking:
            Text("Checking…").font(GMFont.caption).foregroundColor(GM.textTertiary)
        case .available:
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 15))
                    .foregroundColor(GM.success)
                Text("This address is available!")
                    .font(GMFont.captionMedium).foregroundColor(GM.success)
            }
        case .taken:
            Text("That address is taken. Try \(model.state.customHandle)1@grapsee.com")
                .font(GMFont.caption).foregroundColor(GM.error)
        case .invalid(let reason):
            Text(reason ?? "That address cannot be used.")
                .font(GMFont.caption).foregroundColor(GM.error)
        case .unknown:
            Text("Could not check right now — confirmed when the account is created.")
                .font(GMFont.caption).foregroundColor(GM.textTertiary)
        }
    }

    private func radioRow(_ address: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Circle()
                    .strokeBorder(GM.primarySolid, lineWidth: selected ? 6 : 1.5)
                    .frame(width: 20, height: 20)
                Text(address)
                    .font(.system(size: 14))
                    .foregroundColor(GM.textPrimary)
                Spacer()
                Text("Available")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(GM.success)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(GM.success.opacity(0.1)))
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Step 4 · Password — founder design verbatim

struct PasswordStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    var onSignIn: (() -> Void)? = nil
    let onNext: () -> Void

    private var matches: Bool {
        !model.state.confirm.isEmpty && model.state.confirm == model.state.password
    }
    private var mixed: Bool {
        let pw = model.state.password
        // The same four rules the server enforces (`passwordProblem`), in ASCII
        // classes. Swift's Unicode-aware isLetter/isNumber would green-light a
        // non-ASCII letter the server's `[A-Za-z]` then rejects, so a valid-looking
        // password would come back WEAK_PASSWORD after the round-trip.
        let isLetter = { (c: Character) in (c >= "A" && c <= "Z") || (c >= "a" && c <= "z") }
        let isDigit = { (c: Character) in c >= "0" && c <= "9" }
        let hasLetter = pw.contains(where: isLetter)
        let hasDigit = pw.contains(where: isDigit)
        let hasSymbol = pw.contains { !isLetter($0) && !isDigit($0) }
        return hasLetter && hasDigit && hasSymbol
    }
    private var strength: Double { Validators.passwordStrength(model.state.password) }
    private var allMet: Bool { model.state.password.count >= 8 && mixed && matches }

    var body: some View {
        WizardScaffold(
            step: 4,
            title: "Create a strong password",
            subtitle: Text("Your password keeps your account safe. Make it unique, strong and easy for you to remember."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: {
                // Address summary card
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(GM.softFill)
                        .frame(width: 44, height: 44)
                        .overlay(Image(systemName: "envelope.fill").font(.system(size: 17)).foregroundColor(GM.primarySolid))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Your GrapMail address").font(GMFont.caption).foregroundColor(GM.textSecondary)
                        Text("\(model.state.chosenHandle)@grapsee.com")
                            .font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                    }
                    Spacer()
                    Button(action: onBack) {
                        Text("Edit").font(GMFont.captionMedium).foregroundColor(GM.primarySolid)
                    }
                    .buttonStyle(.plain)
                }
                .padding(14)
                .overlay(RoundedRectangle(cornerRadius: GMShape.card).strokeBorder(GM.border))
                .padding(.top, 4)

                (Text("Password ").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                 + Text("*").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.error))
                    .padding(.top, 20)

                AppTextField(
                    value: Binding(get: { model.state.password }, set: { newValue in model.update { $0.password = newValue } }),
                    hint: "Create a strong password",
                    isPassword: true
                )
                .padding(.top, 8)

                // 5-segment meter + Strong
                HStack(spacing: 10) {
                    HStack(spacing: 5) {
                        ForEach(0..<5, id: \.self) { i in
                            Capsule()
                                .fill(strength * 5 > Double(i) ? GM.success : GM.stepInactive)
                                .frame(height: 6)
                        }
                    }
                    Text(strengthLabel)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(strength >= 0.75 ? GM.success : GM.textTertiary)
                }
                .padding(.top, 10)

                (Text("Confirm password ").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                 + Text("*").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.error))
                    .padding(.top, 16)

                AppTextField(
                    value: Binding(get: { model.state.confirm }, set: { newValue in model.update { $0.confirm = newValue } }),
                    hint: "Re-enter your password",
                    isPassword: true
                )
                .padding(.top, 8)

                // Requirements card
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        Image(systemName: "lock").font(.system(size: 14)).foregroundColor(GM.textPrimary)
                        Text("Password requirements").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                    }
                    req("At least 8 characters", model.state.password.count >= 8)
                    req("A mix of letters, numbers and symbols", mixed)
                    req("Both passwords must match", matches)
                }
                .padding(16)
                .overlay(RoundedRectangle(cornerRadius: GMShape.card).strokeBorder(GM.border))
                .padding(.top, 18)
            },
            bottom: {
                GradientButton(
                    title: "Next",
                    enabled: allMet,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { onNext() }
            }
        )
    }

    private var strengthLabel: String {
        switch strength {
        case 1.0...: return "Strong"
        case 0.75...: return "Good"
        case 0.5..<0.75: return "Fair"
        default: return model.state.password.isEmpty ? "" : "Weak"
        }
    }

    private func req(_ label: String, _ met: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(met ? GM.success : GM.textTertiary)
            Text(label)
                .font(GMFont.caption)
                .foregroundColor(met ? GM.textPrimary : GM.textSecondary)
        }
    }
}

// MARK: - Step 5 · Terms — founder design verbatim

struct TermsStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    var onSignIn: (() -> Void)? = nil
    let onCreated: (String, String) -> Void

    @State private var scrolledToEnd = false

    // Real scroll detection: the content's bottom edge in the document's own
    // coordinate space, compared against the viewport height. The old timer
    // unlocked the gate 0.5s after appear — consent without reading.
    private struct BottomKey: PreferenceKey {
        static var defaultValue: CGFloat = .greatestFiniteMagnitude
        static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = min(value, nextValue()) }
    }

    var body: some View {
        WizardScaffold(
            step: 5,
            title: "Terms & Privacy",
            subtitle: Text("Please read and agree to our Terms of Service and Privacy Policy to continue."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: {
                GeometryReader { viewport in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                        Text("GrapMail Terms of Service")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                        Text("1.  Your Account")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                        (Text("By creating an account, you agree to our ")
                         + Text("Terms of Service").foregroundColor(GM.primarySolid)
                         + Text(" and confirm that you are at least 13 years old. You are responsible for keeping your account secure and for all activity that happens under your account."))
                            .font(GMFont.caption)
                            .foregroundColor(GM.textSecondary)
                            .lineSpacing(4)
                        Text("2.  Privacy Policy")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                        (Text("We collect and process your data to provide and improve GrapMail services. Your information will not be shared with third parties without your consent, except as required by law. You can read the full details in our ")
                         + Text("Privacy Policy").foregroundColor(GM.primarySolid)
                         + Text("."))
                            .font(GMFont.caption)
                            .foregroundColor(GM.textSecondary)
                            .lineSpacing(4)
                        }
                        .padding(18)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(GeometryReader { g in
                            Color.clear.preference(
                                key: BottomKey.self,
                                value: g.frame(in: .named("termsDoc")).maxY
                            )
                        })
                    }
                    .coordinateSpace(name: "termsDoc")
                    .onPreferenceChange(BottomKey.self) { bottom in
                        if bottom <= viewport.size.height + 24 {
                            withAnimation { scrolledToEnd = true }
                        }
                    }
                }
                .frame(height: 300)
                .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
                .padding(.top, 4)

                Link("Read our Terms of Service ↗", destination: URL(string: "https://grapsee.com/legal/terms-of-service")!)
                    .font(GMFont.captionMedium).foregroundColor(GM.primarySolid)
                    .padding(.top, 12)
                Link("Read our Privacy Policy ↗", destination: URL(string: "https://grapsee.com/legal/privacy-policy")!)
                    .font(GMFont.captionMedium).foregroundColor(GM.primarySolid)
                    .padding(.top, 8)

                // Agree card
                HStack(alignment: .top, spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 6)
                            .fill(model.state.termsAgreed ? GM.primarySolid : GM.card)
                            .frame(width: 22, height: 22)
                            .overlay(
                                RoundedRectangle(cornerRadius: 6)
                                    .strokeBorder(model.state.termsAgreed ? GM.primarySolid : GM.textTertiary, lineWidth: 1.5)
                            )
                        if model.state.termsAgreed {
                            Image(systemName: "checkmark")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.white)
                        }
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        (Text("I agree to the ")
                         + Text("Terms of Service").foregroundColor(GM.primarySolid)
                         + Text(" and ")
                         + Text("Privacy Policy").foregroundColor(GM.primarySolid))
                            .font(GMFont.caption)
                            .foregroundColor(GM.textPrimary)
                        Text("You must scroll to the bottom of this document to enable the checkbox.")
                            .font(.system(size: 11))
                            .foregroundColor(GM.textTertiary)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
                .padding(.top, 16)
                .onTapGesture {
                    guard scrolledToEnd else { return }
                    model.update { $0.termsAgreed.toggle() }
                }

                if let error = model.state.error {
                    Text(error).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 12)
                }
            },
            bottom: {
                GradientButton(
                    title: "Create Account",
                    enabled: model.state.termsAgreed && scrolledToEnd && !model.state.creating,
                    busy: model.state.creating
                ) {
                    model.createAccount(onCreated: onCreated) { message in
                        model.update { $0.error = message }
                    }
                }
            }
        )
    }
}


/// The design's dropdown, as an inline animated panel — the iOS twin of the
/// Android inline picker: same look closed, and the options render in the
/// main window (screen-reader and automation friendly).
struct InlinePicker: View {
    let placeholder: String
    let value: String
    let options: [String]
    let onPick: (String) -> Void

    @State private var open = false

    var body: some View {
        VStack(spacing: 4) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { open.toggle() }
            } label: {
                HStack {
                    Text(value.isEmpty ? placeholder : value)
                        .font(.system(size: 13))
                        .foregroundColor(value.isEmpty ? GM.textTertiary : GM.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: open ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11))
                        .foregroundColor(GM.textTertiary)
                }
                .padding(.horizontal, 12)
                .frame(height: 46)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(GM.inputFill)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(open ? GM.primarySolid : GM.border, lineWidth: 1)
                )
            }
            .buttonStyle(.plain)

            if open {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(options, id: \.self) { opt in
                            Button {
                                onPick(opt)
                                withAnimation { open = false }
                            } label: {
                                Text(opt)
                                    .font(.system(size: 13, weight: opt == value ? .semibold : .regular))
                                    .foregroundColor(opt == value ? GM.primarySolid : GM.textPrimary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 9)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .frame(maxHeight: 210)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(GM.border, lineWidth: 1))
            }
        }
    }
}
