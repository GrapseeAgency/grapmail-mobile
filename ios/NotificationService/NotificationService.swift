import Foundation
import ImageIO
import Intents
import UIKit
import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    private struct Preview: Decodable {
        let snippet: String
        let senderAddress: String
        let image: Data?
    }

    private let lock = NSLock()
    private var handler: ((UNNotificationContent) -> Void)?
    private var fallback: UNNotificationContent?
    private var task: URLSessionDataTask?

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        let original = request.content
        if original.userInfo["encrypted"] as? String == "1",
           let encrypted = original.mutableCopy() as? UNMutableNotificationContent {
            encrypted.title = "GrapMail"
            encrypted.subtitle = ""
            // Looked up in this extension's own bundle (its String Catalog membership).
            encrypted.body = String(localized: "New encrypted message")
            encrypted.attachments = []
            var info = encrypted.userInfo
            for key in ["previewTicket", "sender", "subject", CodeFinder.userInfoKey] { info.removeValue(forKey: key) }
            encrypted.userInfo = info
            contentHandler(encrypted)
            return
        }
        guard let content = original.mutableCopy() as? UNMutableNotificationContent,
              let ticket = original.userInfo["previewTicket"] as? String,
              let mailbox = original.userInfo["mailbox"] as? String,
              let registration = PreviewCredential.read(mailbox: mailbox) else {
            // No preview to fetch: the code (if any) is read from what the
            // server already put on the banner — the sender and the subject.
            contentHandler(Self.withCode(original, subject: original.body, snippet: nil))
            return
        }
        var info = content.userInfo
        info.removeValue(forKey: "previewTicket")
        content.userInfo = info
        let sender = content.title
        let subject = original.body
        let url = URL(string: "https://grapsee.com/api/grapsee/grapmail/notification-preview")!
        var fetch = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 6)
        let secret = PreviewCredential.secret(from: registration)
        fetch.setValue("GrapMailPreview \(ticket).\(secret)", forHTTPHeaderField: "Authorization")
        // Pinned like the app's API calls (GrapMail/PinnedSessionDelegate.swift,
        // shared into this target by project.yml). While pinning is off
        // (`ApiPins.enforce == false`) PinnedSession.shared IS URLSession.shared.
        let download = PinnedSession.shared.dataTask(with: fetch) { [weak self] data, response, _ in
            guard let self else { return }
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let data, data.count <= 3 * 1024 * 1024,
                  let preview = try? JSONDecoder().decode(Preview.self, from: data),
                  PreviewCredential.read(mailbox: mailbox) == registration else {
                self.finish()
                return
            }
            if !preview.snippet.isEmpty { content.body = original.body + "\n" + preview.snippet }
            if let image = preview.image, let attachment = Self.attachment(from: image) {
                content.attachments = [attachment]
            }
            let coded = Self.withCode(content, subject: subject, snippet: preview.snippet)
            let stamped = (coded.mutableCopy() as? UNMutableNotificationContent) ?? content
            self.finish(with: Self.withSender(stamped, address: preview.senderAddress, displayName: sender),
                        matching: (mailbox, registration))
        }
        lock.lock()
        handler = contentHandler
        // The fallback (timeout, refused fetch) still carries the code the
        // banner's own subject holds.
        fallback = Self.withCode(original, subject: original.body, snippet: nil)
        task = download
        lock.unlock()
        download.resume()
    }

    override func serviceExtensionTimeWillExpire() {
        finish()
    }

    private func finish(with content: UNNotificationContent? = nil, matching credential: (mailbox: String, secret: String)? = nil) {
        lock.lock()
        let callback = handler
        let valid = credential.map { PreviewCredential.read(mailbox: $0.mailbox) == $0.secret } ?? true
        let result = valid ? (content ?? fallback) : fallback
        handler = nil
        fallback = nil
        let pending = task
        task = nil
        lock.unlock()
        pending?.cancel()
        if let result { callback?(result) }
    }

    /// The sign-in code, when the message is neither sealed nor preview-hidden
    /// and CodeFinder finds one in the subject/snippet: it leads the title
    /// ("482913 · Sender"), rides in userInfo for the "Copy code" action, and
    /// switches the category to the one that shows that button (registered by
    /// the app's AppDelegate, which also handles the tap).
    private static func withCode(_ content: UNNotificationContent, subject: String?, snippet: String?) -> UNNotificationContent {
        let info = content.userInfo
        guard info["messageId"] as? String != nil,
              info["encrypted"] as? String != "1",
              info["privatePreview"] as? String != "1",
              let code = CodeFinder.find(subject: subject, body: snippet, encrypted: false),
              let coded = content.mutableCopy() as? UNMutableNotificationContent else { return content }
        let sender = content.title
        coded.title = sender.isEmpty ? code
            : String(localized: "\(code) · \(sender)", comment: "Notification title: a sign-in code, then the sender")
        coded.categoryIdentifier = CodeFinder.notificationCategory
        var stamped = coded.userInfo
        stamped[CodeFinder.userInfoKey] = code
        coded.userInfo = stamped
        return coded
    }

    private static func attachment(from data: Data) -> UNNotificationAttachment? {
        guard data.count <= 2 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 768,
              ] as CFDictionary),
              let jpeg = UIImage(cgImage: image).jpegData(compressionQuality: 0.84) else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        do {
            try jpeg.write(to: url, options: .completeFileProtectionUntilFirstUserAuthentication)
            return try UNNotificationAttachment(identifier: "image", url: url)
        } catch {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
    }

    /// `displayName` is the sender as the server named them — the initial is
    /// drawn from it (or, without one, from the address's local part), not
    /// from the title, which may now lead with a code. The person's shown name
    /// is the title itself, so a communication banner (which titles itself
    /// with the sender's name) keeps the code in front.
    ///
    /// The disc is the app's own avatar (round8-spec §1.6): the sender's
    /// GrapMail hue from the shared FNV-1a slot (NotificationShared/
    /// AvatarIdentity.swift), so the banner and the mailbox row agree, with
    /// the same 8 %-lighter top stop and white initials (AA on every hue).
    private static func withSender(_ content: UNMutableNotificationContent, address: String, displayName: String) -> UNNotificationContent {
        guard !address.isEmpty else { return content }
        let side: CGFloat = 96
        let slot = AvatarIdentity.paletteIndex(address: address, displayName: displayName)
        let hue = AvatarIdentity.paletteLight[slot]
        let letters = AvatarIdentity.initials(displayName: displayName, address: address, twoLetters: false)
        let avatar = UIGraphicsImageRenderer(size: CGSize(width: side, height: side)).image { context in
            let cg = context.cgContext
            let disc = CGRect(x: 0, y: 0, width: side, height: side)
            cg.addEllipse(in: disc)
            cg.clip()
            let stops = [rgbColor(AvatarIdentity.lightened(hue)).cgColor, rgbColor(hue).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: stops, locations: [0, 1]) {
                cg.drawLinearGradient(gradient, start: CGPoint(x: side / 2, y: 0),
                                      end: CGPoint(x: side / 2, y: side), options: [])
            } else {
                rgbColor(hue).setFill()
                cg.fill(disc)
            }
            if let letters {
                let text = letters as NSString
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: side * 0.42, weight: .semibold), .foregroundColor: UIColor.white,
                ]
                let size = text.size(withAttributes: attributes)
                text.draw(at: CGPoint(x: (side - size.width) / 2, y: (side - size.height) / 2), withAttributes: attributes)
            } else if let glyph = UIImage(systemName: "person.fill")?.withTintColor(.white, renderingMode: .alwaysOriginal) {
                // Nothing to spell: the person glyph at half the disc.
                let box = side * 0.5
                let scale = box / max(glyph.size.width, glyph.size.height, 1)
                let drawn = CGSize(width: glyph.size.width * scale, height: glyph.size.height * scale)
                glyph.draw(in: CGRect(x: (side - drawn.width) / 2, y: (side - drawn.height) / 2,
                                      width: drawn.width, height: drawn.height))
            }
        }
        let image = avatar.pngData().map { INImage(imageData: $0) }
        let person = INPerson(
            personHandle: INPersonHandle(value: address, type: .emailAddress), nameComponents: nil,
            displayName: content.title, image: image,
            contactIdentifier: nil, customIdentifier: address
        )
        let intent = INSendMessageIntent(
            recipients: nil, outgoingMessageType: .outgoingMessageText, content: content.body,
            speakableGroupName: nil, conversationIdentifier: content.threadIdentifier,
            serviceName: nil, sender: person, attachments: nil
        )
        if let image { intent.setImage(image, forParameterNamed: \.sender) }
        return (try? content.updating(from: intent)) ?? content
    }

    /// A 0xRRGGBB value as an opaque UIColor (the app's `UIColor(rgb:)` lives
    /// in Theme.swift, which this extension does not compile).
    private static func rgbColor(_ rgb: UInt32) -> UIColor {
        UIColor(red: CGFloat((rgb >> 16) & 0xFF) / 255.0,
                green: CGFloat((rgb >> 8) & 0xFF) / 255.0,
                blue: CGFloat(rgb & 0xFF) / 255.0,
                alpha: 1)
    }
}
