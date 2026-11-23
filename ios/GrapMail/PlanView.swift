import SwiftUI

/// Your plan, read-only: what it allows and what this month has used.
///
/// GrapMail plans are sold through Google Play only, so there is nothing to buy
/// here; a plan bought on Android applies to this account on every device.
struct PlanView: View {
    let onClose: () -> Void
    @State private var entitlement: BillingEntitlement?
    @State private var failure: String?

    var body: some View {
        NavigationView {
            List {
                if let e = entitlement {
                    Section("Current plan") {
                        Text(e.planName).font(.title2.weight(.semibold))
                        if e.plan != "free", let end = e.expiresAt?.prefix(10) {
                            Text(e.autoRenewing ? "Renews \(end)" : "Ends \(end)")
                                .font(.footnote).foregroundColor(.secondary)
                        }
                        if e.badges.contains("subscriber") {
                            Text("Your messages show a Subscriber badge to other GrapMail users. " +
                                 "It means you support GrapMail; it is not identity verification.")
                                .font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Section("This month") {
                        let a = e.allowance
                        Text("\(a.used) of \(a.monthlyRecipients) outside recipients")
                        ProgressView(value: a.monthlyRecipients > 0
                                     ? min(1, Double(a.used) / Double(a.monthlyRecipients)) : 0)
                        Text("Counts people outside GrapMail. Mail to GrapMail addresses is never limited. " +
                             "Resets \(a.resetsAt.prefix(10)).")
                            .font(.caption).foregroundColor(.secondary)
                    }
                } else if let failure {
                    Text(failure).foregroundColor(.secondary)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Your plan")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: onClose) }
            }
        }
        .task {
            guard let token = SessionStore.token else { failure = "Sign in again."; return }
            do { entitlement = try await ApiClient.billingEntitlement(token) }
            catch { failure = "Your plan could not be read. Try again later." }
        }
    }
}
