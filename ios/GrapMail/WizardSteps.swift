import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

// MARK: - Step 1 · Name — founder design verbatim

struct NameStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    var onSignIn: (() -> Void)? = nil
    let onNext: () -> Void

    private enum NameField: Hashable { case first, last }
    @FocusState private var activeField: NameField?
    @State private var showErrors = false

    private var firstError: String? { showErrors && model.state.firstName.isEmpty ? String(localized: "First name is required", comment: "Sign-up name step: field error") : nil }
    private var lastError: String? { showErrors && model.state.lastName.isEmpty ? String(localized: "Last name is required", comment: "Sign-up name step: field error") : nil }

    var body: some View {
        WizardScaffold(
            step: 1,
            title: String(localized: "What's your name?", comment: "Title (sign-up name step)"),
            subtitle: Text("This name appears on your GrapMail profile."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        (Text("First name").foregroundColor(GM.textPrimary)
                         + Text(" *").foregroundColor(GM.error))
                            .gmFont(14, .semibold)
                        nameInput(.first, value: Binding(
                            get: { model.state.firstName },
                            set: { value in model.update { $0.firstName = value } }
                        ))
                        if let firstError {
                            FieldErrorRow(text: firstError)
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        (Text("Last name").foregroundColor(GM.textPrimary)
                         + Text(" *").foregroundColor(GM.error))
                            .gmFont(14, .semibold)
                        nameInput(.last, value: Binding(
                            get: { model.state.lastName },
                            set: { value in model.update { $0.lastName = value } }
                        ))
                        if let lastError {
                            FieldErrorRow(text: lastError)
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }
                    }
                }
                .padding(.top, 4)
            },
            bottom: {
                GradientButton(
                    title: String(localized: "Next", comment: "Button title (sign-up name step)"),
                    enabled: !model.state.firstName.isEmpty && !model.state.lastName.isEmpty,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) {
                    advance()
                }
            }
        )
    }

    private func nameInput(_ field: NameField, value: Binding<String>) -> some View {
        TextField(field == .first
                  ? String(localized: "First name", comment: "Sign-up name step: field placeholder")
                  : String(localized: "Last name", comment: "Sign-up name step: field placeholder"), text: value)
            .font(.body)
            .textContentType(field == .first ? .givenName : .familyName)
            .textInputAutocapitalization(.words)
            .submitLabel(field == .first ? .next : .done)
            .focused($activeField, equals: field)
            .onSubmit {
                if field == .first { activeField = .last }
                else { advance() }
            }
            .padding(14)
            .frame(minHeight: 54)
            .background(GM.inputFill, in: RoundedRectangle(cornerRadius: GMShape.input))
            .overlay {
                RoundedRectangle(cornerRadius: GMShape.input)
                    .strokeBorder(activeField == field ? GM.primarySolid : GM.border)
            }
    }

    private func advance() {
        if model.state.firstName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            showErrors = true
            activeField = .first
        } else if model.state.lastName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            showErrors = true
            activeField = .last
        } else {
            activeField = nil
            onNext()
        }
    }

}

// MARK: - Step 2 · Basic Info — founder design verbatim

struct BasicInfoView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    var onSignIn: (() -> Void)? = nil
    let onNext: () -> Void

    @Environment(\.dynamicTypeSize) private var dts
    @State private var photoItem: PhotosPickerItem?
    @State private var photoError = false
    private var photoPreview: UIImage? { model.state.photo.flatMap(UIImage.init(data:)) }

    // Shown in the picker and matched back to a month number by position, so
    // the stored value never leaves this screen; only the ISO date is sent.
    private let months = [
        String(localized: "January", comment: "Month name in the birthday picker"),
        String(localized: "February", comment: "Month name in the birthday picker"),
        String(localized: "March", comment: "Month name in the birthday picker"),
        String(localized: "April", comment: "Month name in the birthday picker"),
        String(localized: "May", comment: "Month name in the birthday picker"),
        String(localized: "June", comment: "Month name in the birthday picker"),
        String(localized: "July", comment: "Month name in the birthday picker"),
        String(localized: "August", comment: "Month name in the birthday picker"),
        String(localized: "September", comment: "Month name in the birthday picker"),
        String(localized: "October", comment: "Month name in the birthday picker"),
        String(localized: "November", comment: "Month name in the birthday picker"),
        String(localized: "December", comment: "Month name in the birthday picker"),
    ]
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
            title: String(localized: "Basic Info", comment: "Title (sign-up details step)"),
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
                GMAdaptiveStack(spacing: 16, verticalAlignment: .top) {
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
                        if let ui = photoPreview {
                            Image(uiImage: ui)
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        } else {
                            Image(systemName: "person.fill")
                                .gmFont(30, maxSize: 44)
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
                        .gmFont(11, maxSize: 16)
                        .foregroundColor(.white)
                )
        }
    }

    // The `Text + Text` concatenation is its own typed property: inline in the
    // VStack it is the expression the type-checker cannot resolve in time.
    private var photoTitle: Text {
        Text("Profile Photo ").font(.gm(15, .semibold, scaledFor: dts)).foregroundColor(GM.textPrimary)
        + Text("(Optional)").font(.gm(13, scaledFor: dts)).foregroundColor(GM.textTertiary)
    }

    @ViewBuilder private var photoCopy: some View {
        VStack(alignment: .leading, spacing: 4) {
            photoTitle
            Text("Add a photo to make your account more personal. You can change this later in settings.")
                .gmFont(.caption)
                .foregroundColor(GM.textSecondary)
            PhotosPicker(selection: $photoItem, matching: .images) {
                (photoPreview == nil ? Text("Choose Photo") : Text("Change Photo"))
                    .gmFont(.captionMedium)
                    .foregroundColor(GM.primarySolid)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(GM.border))
            }
            .buttonStyle(.plain)
            .task(id: photoItem) {
                guard let item = photoItem else { return }
                defer { if photoItem == item { photoItem = nil } }
                let chosen = try? await item.loadTransferable(type: SelectedAvatar.self)
                guard !Task.isCancelled else { return }
                photoError = chosen == nil
                if let chosen { model.update { $0.photo = chosen.png } }
            }
            .padding(.top, 8)
            if photoError {
                Text("Couldn’t open that photo. Choose another image.")
                    .gmFont(12).foregroundStyle(GM.error)
            }
        }
    }

    @ViewBuilder private var birthdaySection: some View {
                // Birthday
                HStack(spacing: 8) {
                    Image(systemName: "calendar")
                        .gmFont(15)
                        .foregroundColor(GM.textPrimary)
                    Text("Birthday ").gmFont(14, .semibold).foregroundColor(GM.textPrimary)
                    Text("(Required)").gmFont(13).foregroundColor(GM.textTertiary)
                }
                .padding(.top, 22)

                // Month, day and year side by side; stacked at the
                // accessibility sizes, where "September" no longer fits a third.
                GMAdaptiveStack(spacing: 10, verticalAlignment: .top) {
                    InlinePicker(
                        placeholder: String(localized: "Month", comment: "Birthday picker placeholder"),
                        value: model.state.bMonth,
                        options: months
                    ) { pick in model.update { $0.bMonth = pick } }
                    .frame(maxWidth: .infinity)

                    InlinePicker(
                        placeholder: String(localized: "Day", comment: "Birthday picker placeholder"),
                        value: model.state.bDay,
                        options: days
                    ) { pick in model.update { $0.bDay = pick } }
                    .frame(maxWidth: .infinity)

                    InlinePicker(
                        placeholder: String(localized: "Year", comment: "Birthday picker placeholder"),
                        value: model.state.bYear,
                        options: years
                    ) { pick in model.update { $0.bYear = pick } }
                    .frame(maxWidth: .infinity)
                }

                if let err = birthday.error {
                    Text(err).gmFont(.caption).foregroundColor(GM.error).padding(.top, 8)
                } else {
                    HStack(spacing: 6) {
                        Image(systemName: "info.circle.fill")
                            .gmFont(12)
                            .foregroundColor(GM.primarySolid)
                        Text("You must be 13 or older to create an account.")
                            .gmFont(12)
                            .foregroundColor(GM.textTertiary)
                    }
                    .padding(.top, 8)
                }
    }

    @ViewBuilder private var genderSection: some View {
                // Gender
                HStack(spacing: 8) {
                    Image(systemName: "figure.dress.line.vertical.figure")
                        .gmFont(15)
                        .foregroundColor(GM.textPrimary)
                    Text("Gender ").gmFont(14, .semibold).foregroundColor(GM.textPrimary)
                    Text("(Optional)").gmFont(13).foregroundColor(GM.textTertiary)
                }
                .padding(.top, 22)

                // The account stores the code (GenderOption, ProfileOptions.swift);
                // only the label is translated.
                let genders = GenderOption.all
                let genderLabels: [String] = genders.map { GenderOption.label($0) }
                let icons = ["figure.arms.open", "figure.stand", "person", "person.badge.plus"]
                VStack(spacing: 10) {
                    ForEach(0..<2, id: \.self) { row in
                        GMAdaptiveStack(spacing: 10) {
                            ForEach(0..<2, id: \.self) { col in
                                let idx = row * 2 + col
                                genderCard(genders[idx], genderLabels[idx], icons[idx])
                            }
                        }
                    }
                }
                .padding(.top, 10)
    }

    private var basicInfoBottom: some View {
                GradientButton(
                    title: String(localized: "Next", comment: "Button title (sign-up details step)"),
                    enabled: birthday.iso != nil && birthday.error == nil && photoItem == nil,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) {
                    let b = birthday
                    model.update { $0.birthdayIso = b.iso }
                    if b.iso != nil && b.error == nil && photoItem == nil { onNext() }
                }
    }

    private func genderCard(_ code: String, _ title: String, _ icon: String) -> some View {
        let selected = GenderOption.normalize(model.state.gender) == code
        return Button {
            model.update { $0.gender = selected ? nil : code }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .gmFont(14)
                    .foregroundColor(selected ? GM.primarySolid : GM.textTertiary)
                Text(title)
                    .gmFont(13, selected ? .semibold : .regular)
                    .foregroundColor(selected ? GM.primarySolid : GM.textPrimary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(minHeight: 46)
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
    @Environment(\.dynamicTypeSize) private var dts
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    var onSignIn: (() -> Void)? = nil
    let onNext: () -> Void

    var body: some View {
        WizardScaffold(
            step: 3,
            title: String(localized: "Choose your GrapMail address", comment: "Title (sign-up address step)"),
            subtitle: Text("Pick an available address or create your own. You'll use this to sign in and receive emails."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: {
                // Suggested for you
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        Image(systemName: "sparkles")
                            .gmFont(15)
                            .foregroundColor(GM.primarySolid)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Suggested for you").gmFont(15, .semibold).foregroundColor(GM.textPrimary)
                            Text("Based on your name. You can change it later.")
                                .gmFont(12).foregroundColor(GM.textTertiary)
                        }
                    }
                    if model.state.suggestionsLoading {
                        Text("Finding available addresses…").gmFont(.caption).foregroundColor(GM.textTertiary)
                    } else {
                        ForEach(model.state.suggestions, id: \.self) { handle in
                            radioRow("\(handle)@grapsee.com", selected: model.state.chosenHandle == handle && !model.state.customOpen) {
                                model.pickSuggestion(handle)
                            }
                        }
                        if model.state.suggestions.isEmpty {
                            Text("No suggestions from your name — create your own below.")
                                .gmFont(.caption).foregroundColor(GM.textTertiary)
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
                            .gmFont(14)
                            .foregroundColor(GM.primarySolid)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Create your own").gmFont(15, .semibold).foregroundColor(GM.textPrimary)
                            Text("Choose a unique address (3-30 characters).")
                                .gmFont(12).foregroundColor(GM.textTertiary)
                        }
                    }
                    GMAdaptiveStack(spacing: 8) {
                        AppTextField(
                            value: Binding(get: { model.state.customHandle }, set: model.onCustomHandleChange),
                            hint: String(localized: "yourname", comment: "Sign-up address step: placeholder for the part before @grapsee.com"),
                            dismissOnReturn: true
                        )
                        // The design's bordered domain chip with a chevron —
                        // the backend serves one domain today, so the menu
                        // lists one.
                        Menu {
                            Text("@grapsee.com")
                        } label: {
                            HStack(spacing: 6) {
                                Text("@grapsee.com")
                                    .gmFont(14)
                                    .foregroundColor(GM.textSecondary)
                                Image(systemName: "chevron.down")
                                    .gmFont(11, maxSize: 24)
                                    .foregroundColor(GM.textTertiary)
                            }
                            .padding(.horizontal, 12)
                            .frame(minHeight: 54)
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
                        .gmFont(12)
                        .foregroundColor(GM.textTertiary)
                        .lineSpacing(3)
                }
                .padding(16)
                .overlay(RoundedRectangle(cornerRadius: GMShape.card).strokeBorder(GM.border))
                .padding(.top, 14)
            },
            bottom: {
                GradientButton(
                    title: String(localized: "Next", comment: "Button title (sign-up address step)"),
                    enabled: !model.state.chosenHandle.isEmpty,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
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
            Text("Checking…").gmFont(.caption).foregroundColor(GM.textTertiary)
        case .available:
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .gmFont(15)
                    .foregroundColor(GM.success)
                Text("This address is available!")
                    .gmFont(.captionMedium).foregroundColor(GM.success)
            }
        case .taken:
            Text("That address is taken. Try \(model.state.customHandle)1@grapsee.com")
                .gmFont(.caption).foregroundColor(GM.error)
        case .invalid(let reason):
            Text(reason ?? String(localized: "That address cannot be used.", comment: "Sign-up address step: the chosen address is not allowed"))
                .gmFont(.caption).foregroundColor(GM.error)
        case .unknown:
            Text("Could not check right now — confirmed when the account is created.")
                .gmFont(.caption).foregroundColor(GM.textTertiary)
        }
    }

    private func radioRow(_ address: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Circle()
                    .strokeBorder(GM.primarySolid, lineWidth: selected ? 6 : 1.5)
                    .frame(width: 20, height: 20)
                // The badge moves under the address at the accessibility
                // sizes, so the address is never squeezed into a sliver.
                GMAdaptiveStack(spacing: 8) {
                    Text(address)
                        .gmFont(14)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    if !dts.isAccessibilitySize { Spacer() }
                    Text("Available")
                        .gmFont(12, .medium)
                        .foregroundColor(GM.success)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(GM.success.opacity(0.1)))
                }
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
            title: String(localized: "Create a strong password", comment: "Title (sign-up password step)"),
            subtitle: Text("Your password keeps your account safe. Make it unique, strong and easy for you to remember."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: {
                // Address summary card
                GMAdaptiveStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(GM.softFill)
                        .frame(width: 44, height: 44)
                        .overlay(Image(systemName: "envelope.fill").gmFont(17, maxSize: 26).foregroundColor(GM.primarySolid))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Your GrapMail address").gmFont(.caption).foregroundColor(GM.textSecondary)
                        Text("\(model.state.chosenHandle)@grapsee.com")
                            .gmFont(14, .semibold).foregroundColor(GM.textPrimary)
                    }
                    Spacer()
                    Button(action: onBack) {
                        Text("Edit").gmFont(.captionMedium).foregroundColor(GM.primarySolid)
                    }
                    .buttonStyle(.plain)
                }
                .padding(14)
                .overlay(RoundedRectangle(cornerRadius: GMShape.card).strokeBorder(GM.border))
                .padding(.top, 4)

                (Text("Password ").foregroundColor(GM.textPrimary)
                 + Text("*").foregroundColor(GM.error))
                    .gmFont(14, .semibold)
                    .padding(.top, 20)

                // Round 8 — B: the new account's address, so the password
                // manager saves this password under it.
                CredentialUsernameField(username: "\(model.state.chosenHandle)@grapsee.com")
                AppTextField(
                    value: Binding(get: { model.state.password }, set: { newValue in model.update { $0.password = newValue } }),
                    hint: String(localized: "Create a strong password", comment: "Text field placeholder (sign-up password step)"),
                    isPassword: true,
                    contentType: .newPassword
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
                        .gmFont(13, .semibold)
                        .foregroundColor(strength >= 0.75 ? GM.success : GM.textTertiary)
                }
                .padding(.top, 10)

                (Text("Confirm password ").foregroundColor(GM.textPrimary)
                 + Text("*").foregroundColor(GM.error))
                    .gmFont(14, .semibold)
                    .padding(.top, 16)

                AppTextField(
                    value: Binding(get: { model.state.confirm }, set: { newValue in model.update { $0.confirm = newValue } }),
                    hint: String(localized: "Re-enter your password", comment: "Text field placeholder (sign-up password step)"),
                    isPassword: true,
                    contentType: .newPassword
                )
                .padding(.top, 8)

                // Requirements card
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        Image(systemName: "lock").gmFont(14).foregroundColor(GM.textPrimary)
                        Text("Password requirements").gmFont(14, .semibold).foregroundColor(GM.textPrimary)
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
                    title: String(localized: "Next", comment: "Button title (sign-up password step)"),
                    enabled: allMet,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { onNext() }
            }
        )
    }

    private var strengthLabel: String {
        switch strength {
        case 1.0...: return String(localized: "Strong", comment: "Password strength")
        case 0.75...: return String(localized: "Good", comment: "Password strength")
        case 0.5..<0.75: return String(localized: "Fair", comment: "Password strength")
        default: return model.state.password.isEmpty ? "" : String(localized: "Weak", comment: "Password strength")
        }
    }

    private func req(_ label: LocalizedStringKey, _ met: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark")
                .gmFont(12, .semibold)
                .foregroundColor(met ? GM.success : GM.textTertiary)
            Text(label)
                .gmFont(.caption)
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
    @Environment(\.verticalSizeClass) private var verticalSizeClass

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
            title: String(localized: "Terms & Privacy", comment: "Title (sign-up terms step)"),
            subtitle: Text("Please read and agree to our Terms of Service and Privacy Policy to continue."),
            onBack: onBack,
            onSignIn: onSignIn,
            bodyContent: {
                GeometryReader { viewport in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                        Text("GrapMail Terms of Service")
                            .gmFont(15, .semibold)
                            .foregroundColor(GM.textPrimary)
                        Text("1.  Your Account")
                            .gmFont(14, .semibold)
                            .foregroundColor(GM.textPrimary)
                        (Text("By creating an account, you agree to our ")
                         + Text("Terms of Service").foregroundColor(GM.primarySolid)
                         + Text(" and confirm that you are at least 13 years old. You are responsible for keeping your account secure and for all activity that happens under your account."))
                            .gmFont(.caption)
                            .foregroundColor(GM.textSecondary)
                            .lineSpacing(4)
                        Text("2.  Privacy Policy")
                            .gmFont(14, .semibold)
                            .foregroundColor(GM.textPrimary)
                        (Text("We collect and process your data to provide and improve GrapMail services. Your information will not be shared with third parties without your consent, except as required by law. You can read the full details in our ")
                         + Text("Privacy Policy").foregroundColor(GM.primarySolid)
                         + Text("."))
                            .gmFont(.caption)
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
                // A landscape phone has no room for a 300-point document box.
                .frame(height: verticalSizeClass == .compact ? 200 : 300)
                .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
                .padding(.top, 4)

                Link("Read our Terms of Service ↗", destination: URL(string: "https://grapsee.com/legal/terms-of-service")!)
                    .gmFont(.captionMedium).foregroundColor(GM.primarySolid)
                    .padding(.top, 12)
                Link("Read our Privacy Policy ↗", destination: URL(string: "https://grapsee.com/legal/privacy-policy")!)
                    .gmFont(.captionMedium).foregroundColor(GM.primarySolid)
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
                                .gmFont(13, .bold, maxSize: 18)
                                .foregroundColor(.white)
                        }
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        // One sentence with the two document names inside it, so a
                        // translation can reorder them; the names keep their colour.
                        Text("I agree to the \(Text("Terms of Service").foregroundColor(GM.primarySolid)) and \(Text("Privacy Policy").foregroundColor(GM.primarySolid))")
                            .gmFont(.caption)
                            .foregroundColor(GM.textPrimary)
                        Text("You must scroll to the bottom of this document to enable the checkbox.")
                            .gmFont(11)
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
                    Text(error).gmFont(.caption).foregroundColor(GM.error).padding(.top, 12)
                }
            },
            bottom: {
                GradientButton(
                    title: String(localized: "Create Account", comment: "Button title (sign-up terms step)"),
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
                        .gmFont(13)
                        .foregroundColor(value.isEmpty ? GM.textTertiary : GM.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: open ? "chevron.up" : "chevron.down")
                        .gmFont(11, maxSize: 24)
                        .foregroundColor(GM.textTertiary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .frame(minHeight: 46)
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
                                    .gmFont(13, opt == value ? .semibold : .regular)
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
