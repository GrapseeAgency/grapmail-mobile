import SwiftUI

// MARK: - Step 1: Name

struct NameStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    let onNext: () -> Void

    @State private var firstError: String?
    @State private var lastError: String?

    var body: some View {
        WizardScaffold(
            step: 1,
            title: "What's your name?",
            subtitle: "Tell us your name to create your GrapMail identity.",
            onBack: onBack,
            body: {
                Image("ill_step1_name")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: .infinity)
                    .frame(height: 170)

                VStack(spacing: 16) {
                    AppTextField(
                        label: "First Name",
                        value: Binding(
                            get: { model.state.firstName },
                            set: {
                                model.update { $0.firstName = $0 }
                                if firstError != nil { firstError = Validators.firstName($0) }
                            }
                        ),
                        error: firstError
                    )
                    AppTextField(
                        label: "Last Name",
                        value: Binding(
                            get: { model.state.lastName },
                            set: {
                                model.update { $0.lastName = $0 }
                                if lastError != nil { lastError = Validators.lastName($0) }
                            }
                        ),
                        error: lastError
                    )
                }
                .padding(.top, 28)
            },
            bottom: {
                GradientButton(
                    title: "Continue",
                    enabled: !model.state.firstName.isEmpty && !model.state.lastName.isEmpty
                ) {
                    firstError = Validators.firstName(model.state.firstName)
                    lastError = Validators.lastName(model.state.lastName)
                    if firstError == nil && lastError == nil { onNext() }
                }
            }
        )
    }
}

// MARK: - Step 2: Basic Info

struct BasicInfoView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    let onNext: () -> Void

    private let genders = ["Female", "Male", "Rather not say", "Custom"]

    private var birthday: (iso: String?, error: String?) {
        Validators.birthday(
            year: Int(model.state.bYear),
            month: Int(model.state.bMonth),
            day: Int(model.state.bDay)
        )
    }

    var body: some View {
        WizardScaffold(
            step: 2,
            title: "Basic Info",
            subtitle: "Tell us a bit about you.",
            onBack: onBack,
            body: {
                // Profile photo — optional by design
                HStack(spacing: 16) {
                    ZStack {
                        Circle().fill(GM.softFill)
                        Image(systemName: "person.crop.circle.badge.plus")
                            .font(.system(size: 24))
                            .foregroundColor(GM.primarySolid)
                    }
                    .frame(width: 64, height: 64)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Profile Photo").font(.system(size: 15, weight: .semibold)).foregroundColor(GM.textPrimary)
                        Text("Optional — you can add it later").font(GMFont.caption).foregroundColor(GM.textTertiary)
                    }
                    Spacer()
                }
                .padding(.bottom, 22)

                AppTextField(
                    label: "Birthday",
                    value: Binding(
                        get: {
                            [model.state.bMonth, model.state.bDay, model.state.bYear]
                                .filter { !$0.isEmpty }.joined(separator: " / ")
                        },
                        set: { raw in
                            let digits = raw.filter(\.isNumber)
                            model.update {
                                $0.bMonth = String(digits.prefix(2))
                                $0.bDay = String(digits.dropFirst(2).prefix(2))
                                $0.bYear = String(digits.dropFirst(4).prefix(4))
                            }
                        }
                    ),
                    hint: "MM / DD / YYYY",
                    keyboardType: .numbersAndPunctuation
                )
                Text("You must be at least 13 years old to use GrapMail.")
                    .font(.system(size: 12))
                    .foregroundColor(GM.textTertiary)
                    .padding(.top, 8)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Gender (optional)").font(GMFont.captionMedium).foregroundColor(GM.textSecondary)
                    HStack(spacing: 8) {
                        ForEach(genders.prefix(2), id: \.self) { g in
                            GenderChip(label: g, selected: model.state.gender == g) {
                                model.update { $0.gender = $0.gender == g ? nil : g }
                            }
                        }
                    }
                    HStack(spacing: 8) {
                        ForEach(genders.suffix(2), id: \.self) { g in
                            GenderChip(label: g, selected: model.state.gender == g) {
                                model.update { $0.gender = $0.gender == g ? nil : g }
                            }
                        }
                    }
                }
                .padding(.top, 26)
            },
            bottom: {
                GradientButton(
                    title: "Continue",
                    enabled: birthday.iso != nil && birthday.error == nil
                ) {
                    let b = birthday
                    model.update { $0.birthdayIso = b.iso }
                    if b.error == nil && b.iso != nil { onNext() }
                }
            }
        )
    }
}

// MARK: - Step 3: Address

struct AddressStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    let onNext: () -> Void

    var body: some View {
        WizardScaffold(
            step: 3,
            title: "Choose your address",
            subtitle: "Your GrapMail address is your identity.",
            onBack: onBack,
            body: {
                if model.state.suggestionsLoading {
                    Text("Finding available addresses…")
                        .font(GMFont.caption).foregroundColor(GM.textTertiary)
                        .padding(.bottom, 14)
                }
                ForEach(model.state.suggestions, id: \.self) { handle in
                    SuggestionCard(
                        address: "\(handle)@grapsee.com",
                        selected: model.state.chosenHandle == handle && !model.state.customOpen
                    ) {
                        model.pickSuggestion(handle)
                    }
                    .padding(.bottom, 10)
                }
                if !model.state.suggestionsLoading && model.state.suggestions.isEmpty {
                    Text("No suggestions from your name — create your own below.")
                        .font(GMFont.caption).foregroundColor(GM.textTertiary)
                        .padding(.bottom, 14)
                }

                if !model.state.customOpen {
                    OutlineActionButton(title: "Create your own") {
                        model.update { $0.customOpen = true }
                    }
                } else {
                    AppTextField(
                        label: "Custom address",
                        value: Binding(
                            get: { model.state.customHandle },
                            set: model.onCustomHandleChange
                        ),
                        trailing: "@grapsee.com"
                    )
                    statusText.padding(.top, 8)
                    Button {
                        model.update {
                            $0.customOpen = false
                            $0.customHandle = ""
                            $0.chosenHandle = $0.suggestions.first ?? ""
                        }
                    } label: {
                        Text("Back to suggestions")
                            .font(GMFont.captionMedium)
                            .foregroundColor(GM.primarySolid)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 10)
                }
            },
            bottom: {
                GradientButton(
                    title: "Continue",
                    enabled: !model.state.chosenHandle.isEmpty
                ) {
                    onNext()
                }
            }
        )
        .onAppear {
            if model.state.suggestions.isEmpty {
                model.loadSuggestions()
            }
        }
    }

    @ViewBuilder
    private var statusText: some View {
        switch model.state.handleStatus {
        case .idle: EmptyView()
        case .checking:
            Text("Checking…").font(GMFont.caption).foregroundColor(GM.textTertiary)
        case .available:
            Text("\(model.state.customHandle)@grapsee.com is free ✓")
                .font(GMFont.captionMedium).foregroundColor(GM.success)
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
}

private struct SuggestionCard: View {
    let address: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(address)
                    .font(.system(size: 15, weight: selected ? .semibold : .regular))
                    .foregroundColor(selected ? GM.primarySolid : GM.textPrimary)
                Spacer()
                Image(systemName: selected ? "checkmark" : "square.and.pencil")
                    .font(.system(size: 14))
                    .foregroundColor(selected ? GM.primarySolid : GM.textTertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
            .background(
                RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                    .fill(selected ? GM.primarySolid.opacity(0.08) : GM.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                    .strokeBorder(selected ? GM.primarySolid.opacity(0.4) : GM.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Step 4: Password

struct PasswordStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    let onNext: () -> Void

    @State private var confirmError: String?

    private var reqs: [(label: String, met: Bool)] {
        Validators.PasswordReq.allCases.map { ($0.rawValue, Validators.passwordMet($0, model.state.password)) }
    }

    private var strength: Double { Validators.passwordStrength(model.state.password) }

    private var meterColor: Color {
        switch strength {
        case 1.0...: return GM.success
        case 0.75...: return GM.success
        case 0.5..<0.75: return GM.violet
        case 0.25..<0.5: return GM.warning
        default: return GM.error
        }
    }

    var body: some View {
        WizardScaffold(
            step: 4,
            title: "Create a password",
            subtitle: "Use 8 characters or more.",
            onBack: onBack,
            body: {
                VStack(spacing: 16) {
                    AppTextField(label: "Password", value: Binding(
                        get: { model.state.password },
                        set: { model.update { $0.password = $0 } }
                    ), isPassword: true)
                    AppTextField(label: "Confirm password", value: Binding(
                        get: { model.state.confirm },
                        set: {
                            model.update { $0.confirm = $0 }
                            if confirmError != nil {
                                confirmError = $0 != model.state.password ? "The two passwords do not match." : nil
                            }
                        }
                    ), isPassword: true, error: confirmError)
                }

                // Strength meter — 4 segments, animated fill color
                HStack(spacing: 6) {
                    ForEach(0..<4, id: \.self) { i in
                        Capsule()
                            .fill(strength * 4 > Double(i) ? meterColor : GM.stepInactive)
                            .frame(height: 5)
                            .animation(.easeOut(duration: 0.3), value: strength)
                    }
                }
                .padding(.top, 22)

                Text(strengthLabel)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(meterColor)
                    .padding(.top, 8)

                VStack(alignment: .leading, spacing: 10) {
                    ForEach(reqs, id: \.label) { req in
                        RequirementRow(label: req.label, met: req.met)
                    }
                }
                .padding(.top, 20)

                InfoBanner(text: "Your GrapMail address: \(model.state.chosenHandle)@grapsee.com — you'll sign in with this and your password.")
                    .padding(.top, 24)
            },
            bottom: {
                GradientButton(
                    title: "Continue",
                    enabled: reqs.allSatisfy(\.met)
                        && model.state.confirm == model.state.password
                        && !model.state.password.isEmpty
                ) {
                    confirmError = model.state.confirm != model.state.password
                        ? "The two passwords do not match." : nil
                    if confirmError == nil && reqs.allSatisfy(\.met) { onNext() }
                }
            }
        )
    }

    private var strengthLabel: String {
        switch strength {
        case 1.0...: return "Excellent password"
        case 0.75...: return "Strong password"
        case 0.5..<0.75: return "Good password"
        case 0.25..<0.5: return "Okay — keep going"
        default: return model.state.password.isEmpty ? "" : "Too weak"
        }
    }
}

// MARK: - Step 5: Terms

struct TermsStepView: View {
    @ObservedObject var model: SignUpModel
    let onBack: () -> Void
    let onCreated: (String, String) -> Void

    @State private var scrolledToEnd = false

    private let paragraphs = [
        "A GrapMail address is your identity on Grapsee. It comes with a mailbox, and it is the name you sign in with.",
        "What we keep — your name, birthday and gender. The birthday is used only to check you are old enough for the account. Nothing else is inferred from it.",
        "How your mailbox is used — mail is stored for your account only. The contents of your messages are never read to build advertising profiles, and your address is never sold.",
        "Your responsibilities — keep your password to yourself. Do not use the mailbox for spam, bulk unsolicited mail, fraud, or anything unlawful. Accounts that do may be suspended.",
        "You can delete the account and its mailbox at any time from your account settings.",
    ]

    var body: some View {
        WizardScaffold(
            step: 5,
            title: "Review & agree",
            subtitle: "One last step to create your account.",
            onBack: onBack,
            body: {
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Terms of Service & Privacy Policy")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                        ForEach(paragraphs, id: \.self) { para in
                            Text(para)
                                .font(GMFont.caption)
                                .foregroundColor(GM.textSecondary)
                                .lineSpacing(4)
                        }
                        Text("The binding documents are the full Terms of Service and Privacy Policy, published at grapsee.com/legal.")
                            .font(GMFont.caption)
                            .foregroundColor(GM.textSecondary)
                            .lineSpacing(4)
                    }
                    .padding(16)
                }
                .frame(height: 250)
                .background(
                    RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                        .fill(GM.softFill)
                )
                .onAppear {
                    // A document that fits without scrolling is, by definition, read.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        withAnimation { scrolledToEnd = true }
                    }
                }

                Text(scrolledToEnd ? "Thanks — you can agree now." : "Scroll to the bottom to continue.")
                    .font(.system(size: 12))
                    .foregroundColor(GM.textTertiary)
                    .padding(.top, 14)

                HStack(alignment: .top, spacing: 10) {
                    Toggle(isOn: Binding(
                        get: { model.state.termsAgreed },
                        set: { model.update { $0.termsAgreed = $0 } }
                    )) {
                        Text("I have read and agree to the Terms of Service and the Privacy Policy")
                            .font(GMFont.caption)
                            .foregroundColor(GM.textSecondary)
                    }
                    .toggleStyle(.switch)
                    .tint(GM.primarySolid)
                }
                .padding(.top, 8)

                if let error = model.state.error {
                    Text(error).font(GMFont.caption).foregroundColor(GM.error)
                        .padding(.top, 12)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            },
            bottom: {
                GradientButton(
                    title: "Create Account",
                    enabled: model.state.termsAgreed && scrolledToEnd && !model.state.creating,
                    busy: model.state.creating
                ) {
                    model.createAccount(
                        onCreated: { address, name in
                            onCreated(address, name)
                        },
                        onError: { message in
                            model.update { $0.error = message }
                        }
                    )
                }
            }
        )
    }
}
