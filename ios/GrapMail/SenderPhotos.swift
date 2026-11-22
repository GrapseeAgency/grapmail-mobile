import Foundation
import CryptoKit
import ImageIO
import UIKit

/// Sender photos for the list and the reader.
///
/// Every row used to draw an initial because the message rows carry no
/// picture. The server now says which senders have one (`POST
/// /avatars/resolve`, GrapMail accounts only, and only for people who have
/// written to this mailbox), and this store turns that into images:
///
///  - Requests are batched. A screen of rows asks within a few milliseconds of
///    each other, so they are collected for 60 ms and sent as one call of up
///    to 100.
///  - Answers are remembered per signed-in account for 30 minutes (10 for "no
///    photo"), so scrolling does not re-ask.
///  - Bytes are cached on disk under the photo's revision. A changed photo has
///    a new revision, so recipients see the new face on the next resolve and
///    an old file is never mistaken for it.
///  - Images are downsampled to avatar size before they are kept in memory.
///
/// Failure is quiet by design: any error leaves the initial in place, which is
/// what the row showed before this existed.
final class SenderPhotos: @unchecked Sendable {
    static let shared = SenderPhotos()

    private static let batchWindow: UInt64 = 60_000_000
    private static let maxBatch = 100
    private static let hitTTL: TimeInterval = 30 * 60
    private static let missTTL: TimeInterval = 10 * 60
    private static let targetPixels: CGFloat = 160

    private struct Answer { let revision: String?; let at: Date }

    private let lock = NSLock()
    private var answers: [String: Answer] = [:]
    private var lastKnown: [String: String] = [:]
    private var pending: [String: [CheckedContinuation<String?, Never>]] = [:]
    private var flushScheduled = false
    private let images: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 8 * 1024 * 1024
        return cache
    }()

    private static func key(_ address: String) -> String {
        address.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// An already-decoded photo, for the first frame of a recycled row.
    func cached(_ address: String) -> UIImage? {
        let key = Self.key(address)
        let revision = lock.withLock { lastKnown[key] }
        guard let revision else { return nil }
        return images.object(forKey: "\(key)|\(revision)" as NSString)
    }

    /// The sender's photo, or nil to keep the initial. Never throws.
    func photo(for address: String) async -> UIImage? {
        let key = Self.key(address)
        guard key.contains("@"), let token = SessionStore.token else { return nil }
        let viewer = (SessionStore.address ?? "").lowercased()
        guard let revision = await revision(viewer: viewer, key: key) else { return nil }
        if let hit = images.object(forKey: "\(key)|\(revision)" as NSString) { return hit }
        var data = readDisk(key: key, revision: revision)
        if data == nil {
            do {
                let fetched = try await ApiClient.mailAvatarBytes(token: token, address: key, revision: revision)
                guard !fetched.isEmpty else { return nil }
                writeDisk(key: key, revision: revision, data: fetched)
                data = fetched
            } catch {
                // A 409 means the photo changed: forget the answer so the next ask re-resolves.
                lock.withLock { answers["\(viewer)|\(key)"] = nil }
                return nil
            }
        }
        guard let data, let image = Self.downsample(data) else { return nil }
        images.setObject(image, forKey: "\(key)|\(revision)" as NSString,
                         cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
        return image
    }

    /// Forget everything, e.g. on sign-out or an account switch.
    func clear() {
        lock.withLock { answers.removeAll(); lastKnown.removeAll() }
        images.removeAllObjects()
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: - resolving, batched

    private func revision(viewer: String, key: String) async -> String? {
        let now = Date()
        if let answer = lock.withLock({ answers["\(viewer)|\(key)"] }) {
            let ttl = answer.revision != nil ? Self.hitTTL : Self.missTTL
            if now.timeIntervalSince(answer.at) < ttl { return answer.revision }
        }
        return await withCheckedContinuation { (waiter: CheckedContinuation<String?, Never>) in
            let schedule: Bool = lock.withLock {
                pending[key, default: []].append(waiter)
                if flushScheduled { return false }
                flushScheduled = true
                return true
            }
            if schedule {
                Task.detached { [weak self] in
                    try? await Task.sleep(nanoseconds: Self.batchWindow)
                    await self?.flush(viewer: viewer)
                }
            }
        }
    }

    private func flush(viewer: String) async {
        let batch: [(String, [CheckedContinuation<String?, Never>])] = lock.withLock {
            let taken = pending.prefix(Self.maxBatch).map { ($0.key, $0.value) }
            for (key, _) in taken { pending[key] = nil }
            flushScheduled = !pending.isEmpty
            return taken
        }
        if lock.withLock({ flushScheduled }) {
            Task.detached { [weak self] in await self?.flush(viewer: viewer) }
        }
        guard !batch.isEmpty else { return }

        var resolved: [String: MailAvatar]?
        if let token = SessionStore.token {
            resolved = try? await ApiClient.mailAvatars(token, addresses: batch.map { $0.0 }).avatars
        }
        let now = Date()
        for (key, waiters) in batch {
            var revision: String?
            if let avatar = resolved?[key], avatar.kind == "person" || avatar.kind == "brand",
               let value = avatar.revision, value.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil {
                revision = value
            }
            // Only a real answer is remembered; a failed call is asked again next time.
            if resolved != nil {
                lock.withLock {
                    answers["\(viewer)|\(key)"] = Answer(revision: revision, at: now)
                    lastKnown[key] = revision
                }
            }
            for waiter in waiters { waiter.resume(returning: revision) }
        }
    }

    // MARK: - disk

    private var directory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("sender-photos", isDirectory: true)
    }

    private func file(key: String, revision: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined().prefix(32)
        return directory.appendingPathComponent("\(digest)-\(revision)")
    }

    private func readDisk(key: String, revision: String) -> Data? {
        try? Data(contentsOf: file(key: key, revision: revision))
    }

    private func writeDisk(key: String, revision: String, data: Data) {
        let target = file(key: key, revision: revision)
        let fm = FileManager.default
        try? fm.createDirectory(at: directory, withIntermediateDirectories: true)
        // Older revisions of this sender are dead weight once a new one lands.
        let prefix = target.lastPathComponent.split(separator: "-").first.map { "\($0)-" } ?? ""
        if let names = try? fm.contentsOfDirectory(atPath: directory.path) {
            for name in names where name.hasPrefix(prefix) && name != target.lastPathComponent {
                try? fm.removeItem(at: directory.appendingPathComponent(name))
            }
        }
        try? data.write(to: target, options: .atomic)
    }

    private static func downsample(_ data: Data) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else { return nil }
        let thumb = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Self.targetPixels,
        ] as CFDictionary
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumb) else { return nil }
        return UIImage(cgImage: image)
    }
}
