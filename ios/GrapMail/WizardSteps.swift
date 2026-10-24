import SwiftUI

// MARK: - Step 1 · Name — founder design verbatim

struct NameStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
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
            bodyContent: {
                Image("ill_step1_name")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: .infinity)
                    .frame(height: 180)

                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        (Text("First name").foregroundColor(GM.textPrimary)
                         + Text(" *").foregroundColor(GM.error))
                            .font(.system(size: 14, weight: .semibold))
                        AppTextField(
                            value: Binding(get: { model.state.firstName }, set: { model.update { $0.firstName = $0 } }),
                            hint: "Enter your first name",
                            leadingIcon: "person"
                        )
                        if let firstError { FieldErrorRow(text: firstError) }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        (Text("Last name").foregroundColor(GM.textPrimary)
                         + Text(" *").foregroundColor(GM.error))
                            .font(.system(size: 14, weight: .semibold))
                        AppTextField(
                            value: Binding(get: { model.state.lastName }, set: { model.update { $0.lastName = $0 } }),
                            hint: "Enter your last name",
                            leadingIcon: "person"
                        )
                        if let lastError { FieldErrorRow(text: lastError) }
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
                        showErrors = true
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
    let onNext: () -> Void

    private let months = ["January", "February", "March", "April", "May", "June",
                          "July", "August", "September", "October", "November", "December"]
    private let days = Array(1...31).map(String.init)
    private var years: [String] {
        (1940...Calendar.current.component(.year, from: Date())).map(String.init).reversed()
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
            bodyContent: {
                // Profile Photo card
                HStack(alignment: .top, spacing: 16) {
                    ZStack(alignment: .bottomTrailing) {
                        Circle()
                            .fill(GM.softFill)
                            .frame(width: 72, height: 72)
                            .overlay(
                                Image(systemName: "person.fill")
                                    .font(.system(size: 30))
                                    .foregroundColor(Color(0x8FA8E8))
                            )
                        Circle()
                            .fill(GM.primarySolid)
                            .frame(width: 26, height: 26)
                            .overlay(
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 11))
                                    .foregroundColor(.white)
                            )
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        (Text("Profile Photo ").font(.system(size: 15, weight: .semibold)).foregroundColor(GM.textPrimary)
                         + Text("(Optional)").font(.system(size: 13)).foregroundColor(GM.textTertiary))
                        Text("Add a photo to make your account more personal. You can change this later in settings.")
                            .font(GMFont.caption)
                            .foregroundColor(GM.textSecondary)
                        Text("Choose Photo")
                            .font(GMFont.captionMedium)
                            .foregroundColor(GM.primarySolid)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(GM.border))
                            .padding(.top, 8)
                    }
                }
                .padding(16)
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(GM.border))
                .padding(.top, 4)

                // Birthday
                HStack(spacing: 8) {
                    Image(systemName: "calendar")
                        .font(.system(size: 15))
                        .foregroundColor(GM.textPrimary)
                    Text("Birthday ").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                    Text("(Required)").font(.system(size: 13)).foregroundColor(GM.textTertiary)
                }
                .padding(.top, 22)

                HStack(spacing: 10) {
                    Picker("Month", selection: Binding(
                        get: { model.state.bMonth.isEmpty ? "" : model.state.bMonth },
                        set: { model.update { $0.bMonth = $0 } }
                    )) {
                        Text("Month").tag("")
                        ForEach(months, id: \.self) { Text($0).tag($0) }
                    }
                    .frame(maxWidth: .infinity)

                    Picker("Day", selection: Binding(
                        get: { model.state.bDay },
                        set: { model.update { $0.bDay = $0 } }
                    )) {
                        Text("Day").tag("")
                        ForEach(days, id: \.self) { Text($0).tag($0) }
                    }
                    .frame(maxWidth: .infinity)

                    Picker("Year", selection: Binding(
                        get: { model.state.bYear },
                        set: { model.update { $0.bYear = $0 } }
                    )) {
                        Text("Year").tag("")
                        ForEach(years, id: \.self) { Text($0).tag($0) }
                    }
                    .frame(maxWidth: .infinity)
                }
                .pickerStyle(.menu)
                .frame(height: 48)
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(GM.border))

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
            },
            bottom: {
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
        )
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
    let onNext: () -> Void

    var body: some View {
        WizardScaffold(
            step: 3,
            title: "Choose your GrapMail address",
            subtitle: Text("Pick an available address or create your own. You'll use this to sign in and receive emails."),
            onBack: onBack,
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
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(GM.border))
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
                        Text("@grapsee.com")
                            .font(.system(size: 14))
                            .foregroundColor(GM.textSecondary)
                    }
                    statusText
                    Text("Use letters, numbers, dots (.), dashes (-) or underscores (_).\nAvoid reserved names like admin, support, postmaster, etc.")
                        .font(.system(size: 12))
                        .foregroundColor(GM.textTertiary)
                        .lineSpacing(3)
                }
                .padding(16)
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(GM.border))
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
            Text(reason).font(GMFont.caption).foregroundColor(GM.error)
        case .unknown:
            Text("Could not check right now — confirmed when the account is created.")
                .font(GMFont.caption).foregroundColor(GM.textTertiary)
        }
    }

    private func radioRow(_ address: String, selected: Bool, action: @escaping () -> Unit) -> some View {
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
    let onNext: () -> Void

    private var matches: Bool {
        !model.state.confirm.isEmpty && model.state.confirm == model.state.password
    }
    private var mixed: Bool {
        let pw = model.state.password
        let hasLetter = pw.contains(where: \.isLetter)
        let hasDigitOrSymbol = pw.contains(where: { !$0.isLetter })
        return hasLetter && hasDigitOrSymbol
    }
    private var strength: Double { Validators.passwordStrength(model.state.password) }
    private var allMet: Bool { model.state.password.count >= 8 && mixed && matches }

    var body: some View {
        WizardScaffold(
            step: 4,
            title: "Create a strong password",
            subtitle: Text("Your password keeps your account safe. Make it unique, strong and easy for you to remember."),
            onBack: onBack,
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
                    Text("Edit").font(GMFont.captionMedium).foregroundColor(GM.primarySolid)
                }
                .padding(14)
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(GM.border))
                .padding(.top, 4)

                (Text("Password ").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                 + Text("*").font(.system(size: 14, weight: .semibold)).foregroundColor(GM.error))
                    .padding(.top, 20)

                AppTextField(
                    value: Binding(get: { model.state.password }, set: { model.update { $0.password = $0 } }),
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
                    value: Binding(get: { model.state.confirm }, set: { model.update { $0.confirm = $0 } }),
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
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(GM.border))
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
    let onCreated: (String, String) -> Void

    @State private var scrolledToEnd = false

    var body: some View {
        WizardScaffold(
            step: 5,
            title: "Terms & Privacy",
            subtitle: Text("Please read and agree to our Terms of Service and Privacy Policy to continue."),
            onBack: onBack,
            bodyContent: {
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
                }
                .frame(height: 300)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(GM.border))
                .padding(.top, 4)
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        withAnimation { scrolledToEnd = true }
                    }
                }

                Text("Read our Terms of Service ↗")
                    .font(GMFont.captionMedium).foregroundColor(GM.primarySolid)
                    .padding(.top, 12)
                Text("Read our Privacy Policy ↗")
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
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(GM.border))
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
