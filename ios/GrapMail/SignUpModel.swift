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
                        $0.handleStatus = .invalid(resp.reason ?? "That address cannot be used.")
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
            onError("Please read and agree to the terms to continue.")
            return
        }
        let s = state
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
                        acceptedTerms: true
                    )
                )
                let address = resp.account?.address ?? "\(s.chosenHandle)@grapsee.com"
                update { $0.creating = false; $0.createdAddress = address; $0.createdName = resp.account?.name }
                // Auto sign-in — the same call the website makes the moment the
                // account exists.
                if let session = try? await ApiClient.signIn(identifier: address, password: s.password),
                   let token = session.sessionToken ?? session.accessToken ?? session.token {
                    SessionStore.save(
                        token: token, address: address,
                        name: resp.account?.name ?? s.firstName,
                        expiresAtRaw: session.expiresAt.map(Double.init)
                    )
                }
                onCreated(address, resp.account?.name ?? s.firstName)
            } catch let ApiError.http(_, code, message) {
                update { $0.creating = false }
                switch code {
                case "HANDLE_TAKEN": onError("That GrapMail address is already taken. Try another one.")
                // The server names which rule failed and the message is already in
                // hand; a hardcoded length string is wrong for a missing letter or
                // symbol now that the whole policy is enforced.
                case "WEAK_PASSWORD": onError(message.isEmpty ? "Use at least 8 characters with a letter, a number and a symbol." : message)
                case "INVALID_NAME": onError("Enter your full name (at least 2 characters).")
                default: onError(message.isEmpty ? "Could not create the account. Please try again." : message)
                }
            } catch {
                update { $0.creating = false }
                onError(error.localizedDescription)
            }
        }
    }

    func markHumanVerified() { update { $0.humanVerified = true } }
}
