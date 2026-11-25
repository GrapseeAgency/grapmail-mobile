import SwiftUI
import UniformTypeIdentifiers

// ── attachment drag-out ──────────────────────────────────────────────────────

/// Long-press an attachment and drag it into another app (Split View, Slide
/// Over, Stage Manager). The iOS side of Android's `attachmentDragSource`
/// (AttachmentThumb.kt), with the same rule: only a file whose bytes are
/// already on the device can be dragged -- a drag has to start inside the
/// long press, and a download cannot happen there. Android limits that to its
/// small (≤ 2 MB) image preview cache; iOS keeps no such cache, but it does
/// hold the WHOLE file once "Open in another app" or "Save" fetched it
/// (`MailboxModel.localAttachmentFile`), so any size of that is fine.
///
/// The app's own files never leave: when the receiving app asks for the
/// bytes, a fresh copy is made in a new temporary directory and that copy is
/// what is handed over (and copied again by the system on the receiver's
/// side). The saved copy in Documents, and its path, stay where they are.
enum AttachmentDrag {
    static func provider(file: URL, name: String?, mimeType: String?) -> NSItemProvider {
        let display = displayName(name, fallback: file.lastPathComponent)
        let ext = (display as NSString).pathExtension
        let type = mimeType.flatMap { UTType(mimeType: $0) }
            ?? UTType(filenameExtension: ext)
            ?? .data
        let provider = NSItemProvider()
        provider.suggestedName = display
        provider.registerFileRepresentation(forTypeIdentifier: type.identifier,
                                            fileOptions: [],
                                            visibility: .all) { completion in
            do {
                let files = FileManager.default
                let directory = files.temporaryDirectory
                    .appendingPathComponent("drag-\(UUID().uuidString)", isDirectory: true)
                try files.createDirectory(at: directory, withIntermediateDirectories: true)
                let copy = directory.appendingPathComponent(display)
                try files.copyItem(at: file, to: copy)
                completion(copy, false, nil)
            } catch {
                completion(nil, false, error)
            }
            return nil
        }
        return provider
    }

    /// The attachment's own name, made safe as one path component.
    private static func displayName(_ name: String?, fallback: String) -> String {
        let cleaned = String((name ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
            .prefix(120))
        return cleaned.isEmpty || cleaned == "." || cleaned == ".." ? fallback : cleaned
    }
}

extension View {
    /// Offers the attachment for drag-out when `file` (its local copy) exists;
    /// no drag at all otherwise. A drag lifts on a long press, so a tap on the
    /// same view (a button, QuickLook's Open) is untouched.
    @ViewBuilder
    func attachmentDrag(_ file: URL?, name: String?, mimeType: String?) -> some View {
        if let file {
            onDrag { AttachmentDrag.provider(file: file, name: name, mimeType: mimeType) }
        } else {
            self
        }
    }
}
