import Foundation

/// "Show original colours": the reader's per-sender memory of whether a
/// sender's HTML mail opens darkened (EmailDarkening.swift) or in the colours
/// the sender designed, in a dark reader. Light mode never consults it.
///
/// Stored on this device only (UserDefaults), per signed-in account, as the
/// set of sender addresses — lowercased — whose mail the person last chose to
/// see in its original colours. Darkening is the default, so a sender who was
/// switched back to dark colours simply leaves the set. The set is capped, the
/// oldest entries dropping first, so years of toggling cannot grow it without
/// bound. A message with no sender address can still be flipped for as long as
/// it is open; it just has nothing to be remembered under.
enum ReaderColourPrefs {
    private static let keyPrefix = "gm.reader.originalColours.v1."
    private static let cap = 500

    private static var key: String {
        keyPrefix + (SessionStore.address?.lowercased() ?? "signed-out")
    }

    private static func normalized(_ sender: String?) -> String? {
        let value = sender?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        return value.isEmpty ? nil : value
    }

    /// True (darken) unless the person last chose original colours for this sender.
    static func darkening(for sender: String?) -> Bool {
        guard let sender = normalized(sender) else { return true }
        let list = UserDefaults.standard.stringArray(forKey: key) ?? []
        return !list.contains(sender)
    }

    static func setDarkening(_ darkening: Bool, for sender: String?) {
        guard let sender = normalized(sender) else { return }
        var list = UserDefaults.standard.stringArray(forKey: key) ?? []
        list.removeAll { $0 == sender }
        if !darkening {
            list.append(sender)
            if list.count > cap { list.removeFirst(list.count - cap) }
        }
        UserDefaults.standard.set(list, forKey: key)
    }

    /// Forget every choice remembered for `account`. Called by sign-out and
    /// account removal (`SessionStore.clear()`) while the address is still
    /// known, so the next person on this device starts from the default.
    static func clear(account: String?) {
        guard let id = account?.lowercased(), !id.isEmpty else { return }
        UserDefaults.standard.removeObject(forKey: keyPrefix + id)
    }
}
