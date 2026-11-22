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
            encrypted.body = "New encrypted message"
            encrypted.attachments = []
            var info = encrypted.userInfo
            for key in ["previewTicket", "sender", "subject"] { info.removeValue(forKey: key) }
            encrypted.userInfo = info
            contentHandler(encrypted)
            return
        }
        guard let content = original.mutableCopy() as? UNMutableNotificationContent,
              let ticket = original.userInfo["previewTicket"] as? String,
              let mailbox = original.userInfo["mailbox"] as? String,
              let registration = PreviewCredential.read(mailbox: mailbox) else {
            contentHandler(original)
            return
        }
        var info = content.userInfo
        info.removeValue(forKey: "previewTicket")
        content.userInfo = info
        let url = URL(string: "https://grapsee.com/api/grapsee/grapmail/notification-preview")!
        var fetch = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 6)
        let secret = PreviewCredential.secret(from: registration)
        fetch.setValue("GrapMailPreview \(ticket).\(secret)", forHTTPHeaderField: "Authorization")
        let download = URLSession.shared.dataTask(with: fetch) { [weak self] data, response, _ in
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
            self.finish(with: Self.withSender(content, address: preview.senderAddress), matching: (mailbox, registration))
        }
        lock.lock()
        handler = contentHandler
        fallback = original
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

    private static func withSender(_ content: UNMutableNotificationContent, address: String) -> UNNotificationContent {
        guard !address.isEmpty, let initial = content.title.first else { return content }
        let avatar = UIGraphicsImageRenderer(size: CGSize(width: 96, height: 96)).image { context in
            UIColor.systemBlue.setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: 96, height: 96))
            let text = String(initial).uppercased() as NSString
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 44, weight: .semibold), .foregroundColor: UIColor.white,
            ]
            let size = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (96 - size.width) / 2, y: (96 - size.height) / 2), withAttributes: attributes)
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
}
