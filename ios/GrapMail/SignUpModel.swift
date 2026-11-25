import Foundation
import SwiftUI

/// The signup wizard's single source of truth — the iOS twin of the Android
/// SignUpViewModel. Every step validates locally with the backend's own
/// rules, then the final step creates the real account.
@MainActor
final class SignUpModel: ObservableObject {
    enum HandleStatus: Equatable {
        case idle, checking, available, taken(String?), invalid(String?), unknown
    }

    struct UiState {
        var step = 1
        var firstName = ""
        var lastName = ""
        var bMonth = ""
        var bDay = ""
        var bYear = ""
        var birthdayIso: String?
        var gender: String?
        var photo: Data?
        var photoSaved = false
        var chosenHandle = ""
        var customHandle = ""
        var customOpen = false
        var handleStatus: HandleStatus = .idle
        var suggestions: [String] = []
        var suggestionsLoading = false
        var password = ""
        var confirm = ""
        var termsAgreed = false
        var creating = false
        var error: String?
        var createdAddress: String?
        var createdName: String?
        var humanVerified = false
        // Human-verification gate (2026-11-17): server-confirmed only.
        var humanVerifying = false
        var humanVerifyError: String?
        var humanVerificationId: String?
    }

    @Published private(set) var state = UiState()

    private var checkTask: Task<Void, Never>?

    func update(_ transform: (inout UiState) -> Void) {
        var s = state
        transform(&s)
        state = s
    }

    /// Step 3: build + live-check suggestions from the name.
    func loadSuggestions() {
        let candidates = Array(Validators.handleCandidates(first: state.firstName, last: state.lastName).prefix(6))
        guard !candidates.isEmpty else { return }
        update { $0.suggestionsLoading = true; $0.suggestions = [] }
        Task {
            var available: [String] = []
            var unreachable = false
            for c in candidates where available.count < 2 {
                do {
                    if try await ApiClient.checkHandle(c).available { available.append(c) }
                } catch { unreachable = true }
            }
            update {
                $0.suggestionsLoading = false
                $0.suggestions = !available.isEmpty ? available : (unreachable ? Array(candidates.prefix(2)) : [])
            }
        }
    }

    /// Step 3: custom handle live-check with the same 450ms debounce as the site.
    func onCustomHandleChange(_ raw: String) {
        let cleaned = String(Validators.handleChars(raw).prefix(30))
        update { $0.customHandle = cleaned; $0.customOpen = true; $0.chosenHandle = "" }
        checkTask?.cancel()
        guard cleaned.count >= 3 else {
            update { $0.handleStatus = .idle }
            return
        }
        update { $0.handleStatus = .checking }
        checkTask = Task {
            try? await Task.sleep(nanoseconds: 450_000_000)
            guard !Task.isCancelled else { return }
            do {
                let resp = try await ApiClient.checkHandle(cleaned)
                guard !Task.isCancelled else { return }
                update {
                    if resp.available {
                        $0.handleStatus = .available
                        $0.chosenHandle = cleaned
                    } else if let suggestion = resp.suggestion {
                        $0.handleStatus = .taken(resp.reason)
                        $0.chosenHandle = ""
                        _ = suggestion
                    } else {
                        $0.handleStatus = .invalid(resp.reason ?? String(localized: "That address cannot be used.", comment: "Sign-up: the chosen address is not allowed"))
                        $0.chosenHandle = ""
                    }
                }
            } catch {
                guard !Task.isCancelled else { return }
                update { $0.handleStatus = .unknown; $0.chosenHandle = cleaned }
            }
        }
    }

    func pickSuggestion(_ handle: String) {
        checkTask?.cancel()
        update { $0.chosenHandle = handle; $0.customOpen = false; $0.handleStatus = .available; $0.error = nil }
    }

    /// Step 5: create the real account, then sign it in immediately.
    func createAccount(onCreated: @escaping (String, String) -> Void, onError: @escaping (String) -> Void) {
        guard state.termsAgreed else {
            onError(String(localized: "Please read and agree to the terms to continue.", comment: "Sign-up error: terms not accepted"))
            return
        }
        let s = state
        guard s.humanVerified, let verificationId = s.humanVerificationId,
              !verificationId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            onError(String(localized: "Complete the human verification check to continue.", comment: "Sign-up error: human verification not done"))
            return
        }
        update { $0.creating = true; $0.error = nil }
        Task {
            do {
                let resp = try await ApiClient.createAccount(
                    CreateRequest(
                        name: "\(s.firstName.trimmingCharacters(in: .whitespaces)) \(s.lastName.trimmingCharacters(in: .whitespaces))".trimmingCharacters(in: .whitespaces),
                        firstName: s.firstName.trimmingCharacters(in: .whitespaces),
                        lastName: s.lastName.trimmingCharacters(in: .whitespaces),
                        birthday: s.birthdayIso,
                        gender: s.gender,
                        grapmailHandle: s.chosenHandle.isEmpty ? nil : s.chosenHandle,
                        password: s.password,
                        acceptedTerms: true,
                        avatarPhoto: s.photo.map { "data:image/png;base64," + $0.base64EncodedString() },
                        humanVerificationId: verificationId
                    )
                )
                // The gateway answers `grapmail` (and no account object); the
                // old local route answered `account.address`.
                let fullName = "\(s.firstName) \(s.lastName)".trimmingCharacters(in: .whitespaces)
                let address = resp.grapmail ?? resp.account?.address ?? "\(s.chosenHandle)@grapsee.com"
                update {
                    $0.creating = false
                    $0.createdAddress = address
                    $0.createdName = resp.account?.name ?? fullName
                    $0.photoSaved = resp.avatarSaved
                }
                // Auto sign-in — the same call the website makes the moment the
                // account exists. The freshly-created credential can take a
                // beat to become usable, so retry a few times before giving up
                // and letting RootView fall back to the sign-in screen: without
                // a session the wizard strands the new user on sign-in instead
                // of the created → onboarding flow the design shows.
                for attempt in 0..<4 {
                    if attempt > 0 {
                        try? await Task.sleep(nanoseconds: 1_200_000_000)
                    }
                    if let session = try? await ApiClient.signIn(identifier: address, password: s.password),
                       let token = session.sessionToken ?? session.accessToken ?? session.token {
                        SessionStore.save(
                            token: token, refreshToken: session.refreshToken,
                            address: address,
                            name: resp.account?.name ?? s.firstName,
                            expiresAtRaw: session.expiresAtMillis
                        )
                        break
                    }
                }
                onCreated(address, resp.account?.name ?? fullName)
            } catch let ApiError.http(_, code, message) {
                update { $0.creating = false }
                // A spent or expired verification must not outlive its receipt:
                // the stale verdict would keep passing the Continue gate and the
                // bridge's idempotency guard for a check the server has already
                // refused — the retry the copy below asks for could never happen.
                if code == "HUMAN_VERIFICATION_REQUIRED" {
                    update { $0.humanVerified = false; $0.humanVerificationId = nil; $0.humanVerifyError = nil }
                }
                switch code {
                case "HANDLE_TAKEN": onError(String(localized: "That GrapMail address is already taken. Try another one.", comment: "Sign-up error: address taken"))
                // The server names which rule failed and the message is already in
                // hand; a hardcoded length string is wrong for a missing letter or
                // symbol now that the whole policy is enforced.
                case "WEAK_PASSWORD": onError(message.isEmpty ? String(localized: "Use at least 8 characters with a letter, a number and a symbol.", comment: "Sign-up error: weak password") : message)
                case "INVALID_NAME": onError(String(localized: "Enter your full name (at least 2 characters).", comment: "Sign-up error: invalid name"))
                case "HUMAN_VERIFICATION_REQUIRED": onError(String(localized: "Your verification expired or was already used — complete the check again.", comment: "Sign-up error: human verification expired"))
                default: onError(message.isEmpty ? String(localized: "Could not create the account. Please try again.", comment: "Sign-up error: generic") : message)
                }
            } catch {
                update { $0.creating = false }
                onError(apiMessage(error, fallback: String(localized: "Could not create your account. Try again.", comment: "Sign-up error when the server gives no reason")))
            }
        }
    }

    func markHumanVerified() { update { $0.humanVerified = true } }

    /// Human verification (2026-11-17 overhaul): exchange the reCAPTCHA token
    /// from the checkpoint page's JS bridge for a server verdict. Continue
    /// stays disabled until {success:true}. Idempotent: duplicates ignored.
    func submitHumanVerification(token: String) {
        guard !token.isEmpty, !state.humanVerified, !state.humanVerifying else { return }
        update { $0.humanVerifying = true; $0.humanVerifyError = nil }
        Task {
            do {
                let resp = try await ApiClient.verifyHuman(token: token)
                if resp.success, let verificationId = resp.verificationId,
                   !verificationId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    update {
                        $0.humanVerifying = false
                        $0.humanVerified = true
                        $0.humanVerificationId = verificationId
                    }
                } else {
                    update { $0.humanVerifying = false; $0.humanVerifyError = String(localized: "Verification failed — please try again.", comment: "Human verification error") }
                }
            } catch ApiError.http(let status, _, _) {
                let message: String
                switch status {
                case 500...599:
                    message = String(localized: "The verification service is temporarily unavailable. Try a new challenge in a moment.", comment: "Human verification error: service unavailable")
                case 429:
                    message = String(localized: "Too many verification attempts. Wait a moment and try again.", comment: "Human verification error: rate limited")
                default:
                    message = String(localized: "Verification failed. Get a new challenge and try again.", comment: "Human verification error: challenge rejected")
                }
                update { $0.humanVerifying = false; $0.humanVerifyError = message }
            } catch {
                update { $0.humanVerifying = false; $0.humanVerifyError = String(localized: "Could not complete verification. Get a new challenge and try again.", comment: "Human verification error: network or unknown failure") }
            }
        }
    }
}
