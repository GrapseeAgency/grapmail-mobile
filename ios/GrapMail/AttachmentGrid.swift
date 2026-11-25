import ImageIO
import PDFKit
import QuickLookThumbnailing
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// The reader's attachments (round8-spec §5.6): pictures (and PDFs already on
// this device, as their first page) in a square thumbnail grid that opens the
// full-screen QuickLook viewer — swipe between files, pinch to zoom — and
// every other file as a 56 pt row: coloured type tile, name (middle
// ellipsis, so the extension stays visible), "TYPE · size", Save and Share.
//
// Kept from the rows this replaces: Save is the same
// `MailboxModel.downloadAttachment` (the row's own outcome line), and
// long-press drag-out is the same `attachmentDrag` (AttachmentDrag.swift) —
// only a file whose bytes are already on the device can be dragged.
//
// Downloads: a picture of at most 2 MB is fetched for its tile the first time
// the tile is drawn (Android's preview cache rule; the bytes come from the
// person's own mail server, never from a sender). A PDF thumbnail is drawn
// only for a PDF of at most 10 MB that is already on the device — nothing is
// downloaded just to draw one. Encrypted (E2E) messages fetch nothing for
// thumbnails and keep the lock mark.
//
// Tiles have their size from the start (the grid is a square Layout), and a
// thumbnail fades in inside a box that never resizes (§5.5).

// MARK: - File types

enum AttachmentKind {
    case image, pdf, document, spreadsheet, presentation, archive, audio, video, calendar, other

    static func fileExtension(_ a: MailAttachment) -> String {
        String(((a.name ?? "") as NSString).pathExtension.lowercased().prefix(8))
    }

    static func of(_ a: MailAttachment) -> AttachmentKind {
        let mime = (a.mimeType ?? "").lowercased()
        let ext = fileExtension(a)
        if (mime.hasPrefix("image/") && !mime.hasPrefix("image/svg"))
            || ["jpg", "jpeg", "png", "gif", "webp", "heic", "heif"].contains(ext) { return .image }
        if mime.hasPrefix("application/pdf") || ext == "pdf" { return .pdf }
        if ext == "ics" || mime.hasPrefix("text/calendar") { return .calendar }
        if ["xls", "xlsx", "csv", "ods", "numbers"].contains(ext)
            || mime.contains("spreadsheet") || mime.contains("excel") { return .spreadsheet }
        if ["ppt", "pptx", "odp", "key"].contains(ext)
            || mime.contains("presentation") || mime.contains("powerpoint") { return .presentation }
        if ["zip", "rar", "7z", "gz", "tar", "tgz"].contains(ext)
            || mime.contains("zip") || mime.contains("compressed") { return .archive }
        if ["mp3", "m4a", "wav", "ogg", "flac", "aac"].contains(ext) || mime.hasPrefix("audio/") { return .audio }
        if ["mp4", "mov", "webm", "mkv", "m4v"].contains(ext) || mime.hasPrefix("video/") { return .video }
        if ["doc", "docx", "odt", "rtf", "txt", "pages", "md"].contains(ext)
            || mime.contains("word") || mime.hasPrefix("text/") { return .document }
        return .other
    }

    /// The tile colour. The spec's per-type hues are the avatar palette's
    /// (§1.6.1) plus `errorFill` for PDF; those are the tokens used, because
    /// they are the ones built to carry white text in both themes.
    var tint: Color {
        func ramp(_ index: Int) -> Color {
            GM.avatarRamp.indices.contains(index) ? GM.avatarRamp[index] : GM.textSecondary
        }
        switch self {
        case .pdf: return GM.errorFill
        case .document: return ramp(0)       // Cobalt
        case .spreadsheet: return ramp(6)    // Fern
        case .presentation: return ramp(5)   // Ember
        case .audio: return ramp(1)          // Indigo
        case .video: return ramp(3)          // Berry
        case .calendar: return ramp(8)       // Ocean
        case .image: return ramp(7)          // Lagoon
        case .archive, .other: return ramp(9) // Slate
        }
    }

    var glyph: String {
        switch self {
        case .image: return "photo"
        case .pdf: return "doc.richtext"
        case .document: return "doc.text"
        case .spreadsheet: return "tablecells"
        case .presentation: return "chart.bar.doc.horizontal"
        case .archive: return "doc.zipper"
        case .audio: return "waveform"
        case .video: return "film"
        case .calendar: return "calendar"
        case .other: return "paperclip"
        }
    }

    /// "PDF", "DOCX" — the extension, else the MIME subtype.
    static func typeLabel(_ a: MailAttachment) -> String? {
        let ext = fileExtension(a)
        if !ext.isEmpty, ext.count <= 6, ext.allSatisfy({ $0.isLetter || $0.isNumber }) { return ext.uppercased() }
        if let sub = a.mimeType?.split(separator: "/").last?.split(separator: ";").first,
           !sub.trimmingCharacters(in: .whitespaces).isEmpty {
            return String(sub.prefix(12)).uppercased()
        }
        return nil
    }

    /// The platform byte formatter ("2.4 MB").
    static func sizeText(_ a: MailAttachment) -> String? {
        a.sizeBytes.map { ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .file) }
    }

    static func displayName(_ a: MailAttachment) -> String {
        a.name?.nilIfBlank()
            ?? String(localized: "attachment", comment: "Fallback name for an attachment with no file name")
    }

    /// "{file name}, {type}, {size}" for VoiceOver.
    static func accessibilityLabel(_ a: MailAttachment) -> String {
        [displayName(a), typeLabel(a), sizeText(a)].compactMap { $0 }.joined(separator: ", ")
    }
}

// MARK: - Thumbnails

enum ReaderThumbnails {
    private static let cache = NSCache<NSString, UIImage>()

    static func cached(_ key: String) -> UIImage? { cache.object(forKey: key as NSString) }

    /// A downsampled picture for a square tile of `side` points.
    static func image(at url: URL, key: String, side: CGFloat, scale: CGFloat) async -> UIImage? {
        if let hit = cached(key) { return hit }
        // Twice the tile: the longer edge is capped, and the tile crops to fill.
        let maxPixel = max(64, side * max(scale, 1) * 2)
        let image: UIImage? = await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                let options: [CFString: Any] = [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceShouldCacheImmediately: true,
                    kCGImageSourceThumbnailMaxPixelSize: maxPixel,
                ]
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: UIImage(cgImage: cg))
            }
        }
        if let image { cache.setObject(image, forKey: key as NSString) }
        return image
    }

    /// A PDF's first page: QuickLook's thumbnail at 2× the tile, PDFKit's
    /// page thumbnail as the fallback. A locked or damaged PDF yields nil
    /// (the file row alone is shown), silently.
    static func pdfPage(at url: URL, key: String, side: CGFloat, scale: CGFloat) async -> UIImage? {
        if let hit = cached(key) { return hit }
        let size = CGSize(width: side * 2, height: side * 2)
        let request = QLThumbnailGenerator.Request(fileAt: url, size: size, scale: max(scale, 1),
                                                   representationTypes: .thumbnail)
        var image: UIImage? = await withCheckedContinuation { continuation in
            QLThumbnailGenerator.shared.generateBestRepresentation(for: request) { representation, _ in
                continuation.resume(returning: representation?.uiImage)
            }
        }
        if image == nil {
            image = await withCheckedContinuation { continuation in
                DispatchQueue.global(qos: .utility).async {
                    guard let document = PDFDocument(url: url), !document.isLocked,
                          let page = document.page(at: 0) else {
                        continuation.resume(returning: nil)
                        return
                    }
                    continuation.resume(returning: page.thumbnail(of: size, for: .mediaBox))
                }
            }
        }
        if let image { cache.setObject(image, forKey: key as NSString) }
        return image
    }
}

/// Pictures being fetched for their tile right now, so a redraw does not ask twice.
enum AttachmentPrefetch {
    static var inflight: Set<String> = []
    static let imageLimit = 2_000_000
    static let pdfThumbnailLimit = 10_000_000
}

extension MailboxModel {
    /// The attachment's local copy: the one this device already holds, else
    /// a fresh temporary one (`prepareAttachment`, which also makes it
    /// draggable). The error is the model's own sentence.
    func readerLocalCopy(of a: MailAttachment) async -> (URL?, String?) {
        if let url = localAttachmentFile(a.id) { return (url, nil) }
        return await withCheckedContinuation { continuation in
            prepareAttachment(a) { url, error in continuation.resume(returning: (url, error)) }
        }
    }
}

// MARK: - Grid layout

/// Square tiles: 2 columns in a compact reader, 4 once the card is about as
/// wide as a 600 pt pane. Sizes are fixed before anything loads.
struct ReaderTileGrid: Layout {
    var spacing: CGFloat = 6

    private func columns(for width: CGFloat) -> Int { width >= 520 ? 4 : 2 }

    private func side(_ width: CGFloat, _ columns: Int) -> CGFloat {
        max(0, (width - spacing * CGFloat(columns - 1)) / CGFloat(columns))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        let cols = columns(for: width)
        let tile = side(width, cols)
        let rows = (subviews.count + cols - 1) / cols
        let height = rows == 0 ? 0 : CGFloat(rows) * tile + CGFloat(rows - 1) * spacing
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let cols = columns(for: bounds.width)
        let tile = side(bounds.width, cols)
        for (index, subview) in subviews.enumerated() {
            let row = index / cols
            let col = index % cols
            subview.place(at: CGPoint(x: bounds.minX + CGFloat(col) * (tile + spacing),
                                      y: bounds.minY + CGFloat(row) * (tile + spacing)),
                          anchor: .topLeading,
                          proposal: ProposedViewSize(width: tile, height: tile))
        }
    }
}

// MARK: - The section

private struct AttachmentSharePayload: Identifiable {
    let id = UUID()
    let urls: [URL]
}

@MainActor
struct ReaderAttachmentsView: View {
    @ObservedObject var vm: MailboxModel
    let message: MailMessageFull
    /// Owned by the thread (ThreadView), so a Save outcome belongs to the row that asked.
    let downloading: Set<String>
    let notices: [String: String]
    let onDownload: (MailAttachment) -> Void

    /// "open:<id>", "share:<id>", "all": what is being fetched right now.
    @State private var busy: Set<String> = []
    /// Open / Share / Save-all outcomes, by attachment id (or "all").
    @State private var notes: [String: String] = [:]
    @State private var share: AttachmentSharePayload?
    @Environment(\.dynamicTypeSize) private var dts

    private var encrypted: Bool { message.isE2eEncrypted || message.e2eEnvelope != nil }
    private var images: [MailAttachment] { message.attachments.filter { AttachmentKind.of($0) == .image } }
    private var files: [MailAttachment] { message.attachments.filter { AttachmentKind.of($0) != .image } }
    /// PDFs this device already holds (≤ 10 MB): drawn as a first-page tile too.
    private var localPdfs: [MailAttachment] {
        guard !encrypted else { return [] }
        return files.filter {
            AttachmentKind.of($0) == .pdf
                && ($0.sizeBytes ?? Int.max) <= AttachmentPrefetch.pdfThumbnailLimit
                && vm.localAttachmentFile($0.id) != nil
        }
    }
    /// What the viewer pages through: every picture, then the local PDFs.
    private var gallery: [MailAttachment] { images + localPdfs }

    var body: some View {
        if !message.attachments.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Text(String(localized: "\(message.attachments.count) attachments", table: "Reader"))
                        .gmFont(12, .semibold)
                        .foregroundColor(GM.textSecondary)
                    Spacer(minLength: 0)
                    if message.attachments.count >= 2 { saveAllButton }
                }
                if let note = notes["all"] {
                    Text(note).gmFont(12).foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !gallery.isEmpty { grid }
                ForEach(gallery.filter { AttachmentKind.of($0) == .image }) { a in
                    if let note = notices[a.id] ?? notes[a.id] {
                        Text(note).gmFont(12).foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                ForEach(files) { a in fileRow(a) }
            }
            .sheet(item: $share) { payload in
                FileActivityView(urls: payload.urls)
            }
        }
    }

    // ── grid ────────────────────────────────────────────────────────────────

    private var grid: some View {
        let items = gallery
        let shown = Array(items.prefix(4))
        let more = items.count - shown.count
        return ReaderTileGrid(spacing: 6) {
            ForEach(Array(shown.enumerated()), id: \.element.id) { index, a in
                gridTile(a, index: index, total: items.count,
                         more: index == shown.count - 1 ? more : 0)
            }
        }
    }

    private func gridTile(_ a: MailAttachment, index: Int, total: Int, more: Int) -> some View {
        let kind = AttachmentKind.of(a)
        let label = kind == .image
            ? String(localized: "Image \(index + 1) of \(total), \(AttachmentKind.displayName(a))", table: "Reader")
            : AttachmentKind.accessibilityLabel(a)
        return Button { openGallery(at: index) } label: {
            AttachmentTile(vm: vm, attachment: a, kind: kind,
                           localFile: vm.localAttachmentFile(a.id),
                           encrypted: encrypted, more: more,
                           opening: busy.contains("open:\(a.id)"))
        }
        .buttonStyle(.plain)
        .contextMenu { menuItems(a) }
        .attachmentDrag(vm.localAttachmentFile(a.id), name: a.name, mimeType: a.mimeType)
        .accessibilityLabel(Text(label))
        .accessibilityValue(more > 0 ? Text(verbatim: "+\(more)") : Text(verbatim: ""))
        .accessibilityIdentifier("mail-attachment-image")
    }

    // ── file rows ───────────────────────────────────────────────────────────

    @ViewBuilder private func fileRow(_ a: MailAttachment) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Group {
                if dts.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 6) {
                        openTarget(a)
                        HStack(spacing: 4) {
                            Spacer(minLength: 0)
                            saveButton(a)
                            shareButton(a)
                        }
                    }
                } else {
                    HStack(spacing: 4) {
                        openTarget(a)
                        saveButton(a)
                        shareButton(a)
                    }
                }
            }
            .frame(minHeight: 56)
            .contextMenu { menuItems(a) }
            // Long-press to drag the file into another app once this device
            // holds it (AttachmentDrag.swift); Save's tap is untouched.
            .attachmentDrag(vm.localAttachmentFile(a.id), name: a.name, mimeType: a.mimeType)
            if let note = notices[a.id] ?? notes[a.id] {
                Text(note).gmFont(12).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 52)
            }
        }
        .accessibilityIdentifier("mail-attachment-file")
    }

    private func openTarget(_ a: MailAttachment) -> some View {
        let kind = AttachmentKind.of(a)
        let meta = [AttachmentKind.typeLabel(a), AttachmentKind.sizeText(a)].compactMap { $0 }.joined(separator: " · ")
        return Button { open(a) } label: {
            HStack(spacing: 12) {
                FileTypeTile(attachment: a, kind: kind, encrypted: encrypted)
                VStack(alignment: .leading, spacing: 2) {
                    Text(AttachmentKind.displayName(a))
                        .gmFont(15, .medium)
                        .foregroundColor(GM.textPrimary)
                        .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                        .truncationMode(.middle)
                    Text(meta.isEmpty ? String(localized: "File", table: "Reader") : meta)
                        .gmFont(13)
                        .foregroundColor(GM.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if busy.contains("open:\(a.id)") {
                    ProgressView().controlSize(.small)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(AttachmentKind.accessibilityLabel(a)))
        .accessibilityHint(Text("Opens a preview", tableName: "Reader"))
    }

    private func saveButton(_ a: MailAttachment) -> some View {
        let name = AttachmentKind.displayName(a)
        return Button { onDownload(a) } label: {
            Group {
                if downloading.contains(a.id) {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "square.and.arrow.down")
                        .gmFont(17, .medium, maxSize: 26)
                        .foregroundColor(GM.primaryText)
                }
            }
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(downloading.contains(a.id))
        .accessibilityLabel(Text(String(localized: "Save \(name)", table: "Reader")))
        .accessibilityIdentifier("mail-attachment-save")
    }

    private func shareButton(_ a: MailAttachment) -> some View {
        let name = AttachmentKind.displayName(a)
        let key = "share:\(a.id)"
        return Button { shareFiles([a], key: key, noteKey: a.id) } label: {
            Group {
                if busy.contains(key) {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "square.and.arrow.up")
                        .gmFont(17, .medium, maxSize: 26)
                        .foregroundColor(GM.primaryText)
                }
            }
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy.contains(key))
        .accessibilityLabel(Text(String(localized: "Share \(name)", table: "Reader")))
        .accessibilityIdentifier("mail-attachment-share")
    }

    @ViewBuilder private func menuItems(_ a: MailAttachment) -> some View {
        Button { open(a) } label: {
            Label(String(localized: "Open", table: "Reader"), systemImage: "eye")
        }
        Button { onDownload(a) } label: {
            Label(String(localized: "Save", table: "Reader"), systemImage: "square.and.arrow.down")
        }
        .disabled(downloading.contains(a.id))
        Button { shareFiles([a], key: "share:\(a.id)", noteKey: a.id) } label: {
            Label(String(localized: "Share", table: "Reader"), systemImage: "square.and.arrow.up")
        }
    }

    private var saveAllButton: some View {
        let working = busy.contains("all")
        return Button {
            shareFiles(message.attachments, key: "all", noteKey: "all")
        } label: {
            Text(working ? String(localized: "Preparing…", table: "Reader")
                         : String(localized: "Save all", table: "Reader"))
                .gmFont(13, .semibold)
                .foregroundColor(GM.primaryText)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(working)
        .accessibilityHint(Text("Opens the share sheet, where Save to Files keeps every attachment", tableName: "Reader"))
        .accessibilityIdentifier("mail-attachments-save-all")
    }

    // ── actions ─────────────────────────────────────────────────────────────

    /// The full-screen viewer over the grid's files, starting at the tapped
    /// one. Pictures not yet on the device are fetched first (the tapped tile
    /// shows progress); a picture that cannot be fetched is left out, and the
    /// tapped one failing says so under the grid.
    private func openGallery(at index: Int) {
        let items = gallery
        guard items.indices.contains(index) else { return }
        let tapped = items[index]
        let key = "open:\(tapped.id)"
        guard !busy.contains(key) else { return }
        busy.insert(key)
        notes[tapped.id] = nil
        Task { @MainActor in
            var pages: [QuickLookGalleryItem] = []
            var start = 0
            var tappedError: String?
            var tappedReady = false
            for (i, a) in items.enumerated() {
                let (url, error) = await vm.readerLocalCopy(of: a)
                if let url {
                    if i == index { start = pages.count; tappedReady = true }
                    pages.append(QuickLookGalleryItem(url: url, title: AttachmentKind.displayName(a)))
                } else if i == index {
                    tappedError = error
                }
            }
            busy.remove(key)
            guard tappedReady else {
                notes[tapped.id] = tappedError ?? String(localized: "This attachment could not be opened.")
                return
            }
            if !QuickLookGallery.present(pages, startAt: start) {
                notes[tapped.id] = String(localized: "This attachment could not be opened.")
            }
        }
    }

    /// One file in the viewer (file rows, the context menu's Open).
    private func open(_ a: MailAttachment) {
        if let index = gallery.firstIndex(where: { $0.id == a.id }) {
            openGallery(at: index)
            return
        }
        let key = "open:\(a.id)"
        guard !busy.contains(key) else { return }
        busy.insert(key)
        notes[a.id] = nil
        Task { @MainActor in
            let (url, error) = await vm.readerLocalCopy(of: a)
            busy.remove(key)
            guard let url else {
                notes[a.id] = error ?? String(localized: "This attachment could not be opened.")
                return
            }
            if !QuickLookGallery.present([QuickLookGalleryItem(url: url, title: AttachmentKind.displayName(a))], startAt: 0) {
                notes[a.id] = String(localized: "This attachment could not be opened.")
            }
        }
    }

    /// The system share sheet for one or more files ("Save to Files" lives
    /// there, for one file or for all of them).
    private func shareFiles(_ list: [MailAttachment], key: String, noteKey: String) {
        guard !busy.contains(key), !list.isEmpty else { return }
        busy.insert(key)
        notes[noteKey] = nil
        Task { @MainActor in
            var urls: [URL] = []
            var lastError: String?
            for a in list {
                let (url, error) = await vm.readerLocalCopy(of: a)
                if let url { urls.append(url) } else { lastError = error }
            }
            busy.remove(key)
            if urls.isEmpty {
                notes[noteKey] = lastError ?? String(localized: "This attachment could not be opened.")
            } else {
                if urls.count < list.count, let lastError { notes[noteKey] = lastError }
                share = AttachmentSharePayload(urls: urls)
            }
        }
    }
}

// MARK: - Tiles

/// A square grid tile: the picture (or a PDF's first page, labelled with its
/// name over a bottom gradient), else the type glyph and name. "+N" over the
/// last tile when more files wait in the viewer.
@MainActor
private struct AttachmentTile: View {
    let vm: MailboxModel
    let attachment: MailAttachment
    let kind: AttachmentKind
    let localFile: URL?
    let encrypted: Bool
    let more: Int
    let opening: Bool

    @State private var thumbnail: UIImage?
    @Environment(\.displayScale) private var displayScale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let side: CGFloat = 160

    init(vm: MailboxModel, attachment: MailAttachment, kind: AttachmentKind, localFile: URL?,
         encrypted: Bool, more: Int, opening: Bool) {
        self.vm = vm
        self.attachment = attachment
        self.kind = kind
        self.localFile = localFile
        self.encrypted = encrypted
        self.more = more
        self.opening = opening
        _thumbnail = State(initialValue: ReaderThumbnails.cached(Self.key(attachment, kind)))
    }

    private static func key(_ a: MailAttachment, _ kind: AttachmentKind) -> String {
        "\(a.id)|\(kind == .pdf ? "pdf" : "img")"
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: GMShape.thumb, style: .continuous)
        shape
            .fill(GM.softFill)
            .overlay {
                if let thumbnail {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                        .accessibilityHidden(true)
                } else {
                    VStack(spacing: 4) {
                        Image(systemName: kind.glyph)
                            .gmFont(22, .medium, maxSize: 30)
                            .foregroundColor(kind.tint)
                        Text(AttachmentKind.displayName(attachment))
                            .gmFont(11)
                            .foregroundColor(GM.textSecondary)
                            .lineLimit(2)
                            .truncationMode(.middle)
                            .multilineTextAlignment(.center)
                    }
                    .padding(8)
                    .accessibilityHidden(true)
                }
            }
            .overlay(alignment: .bottom) {
                if kind == .pdf, thumbnail != nil {
                    Text(AttachmentKind.displayName(attachment))
                        .gmFont(11, .medium)
                        .foregroundColor(GM.onPrimary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .padding(.horizontal, 8)
                        .padding(.top, 18)
                        .padding(.bottom, 6)
                        .frame(maxWidth: .infinity)
                        .background(LinearGradient(colors: [Color.clear, GM.scrim], startPoint: .top, endPoint: .bottom))
                        .accessibilityHidden(true)
                }
            }
            .overlay {
                if more > 0 {
                    ZStack {
                        GM.scrim
                        Text(verbatim: "+\(more)")
                            .gmFont(17, .semibold)
                            .foregroundColor(GM.onPrimary)
                    }
                    .accessibilityHidden(true)
                }
            }
            .overlay {
                if opening {
                    ProgressView()
                        .padding(10)
                        .background(Circle().fill(GM.card))
                }
            }
            .overlay(alignment: .topTrailing) {
                if encrypted {
                    Image(systemName: "lock.fill")
                        .gmFont(10, .semibold, maxSize: 16)
                        .foregroundColor(GM.successInk)
                        .padding(5)
                        .background(Circle().fill(GM.card))
                        .padding(6)
                        .accessibilityHidden(true)
                }
            }
            .clipShape(shape)
            .overlay(shape.strokeBorder(GM.border).allowsHitTesting(false))
            .contentShape(shape)
            .task(id: localFile) { await load() }
    }

    private func load() async {
        guard !encrypted else { return }
        if let file = localFile {
            guard thumbnail == nil else { return }
            let key = Self.key(attachment, kind)
            let image: UIImage?
            if kind == .pdf {
                image = await ReaderThumbnails.pdfPage(at: file, key: key, side: Self.side, scale: displayScale)
            } else {
                image = await ReaderThumbnails.image(at: file, key: key, side: Self.side, scale: displayScale)
            }
            guard !Task.isCancelled, let image else { return }
            withAnimation(GMMotion.gated(GMMotion.standard(GMMotion.short), reduced: reduceMotion)) {
                thumbnail = image
            }
        } else if kind == .image,
                  (attachment.sizeBytes ?? Int.max) <= AttachmentPrefetch.imageLimit,
                  !AttachmentPrefetch.inflight.contains(attachment.id) {
            // The model records the copy when it lands; `localFile` then
            // changes and this task runs again with the file.
            let id = attachment.id
            AttachmentPrefetch.inflight.insert(id)
            vm.prepareAttachment(attachment) { _, _ in
                AttachmentPrefetch.inflight.remove(id)
            }
        }
    }
}

/// The 40 × 40 type tile of a file row: the uppercase extension (up to four
/// characters) in 10 bold white on the type's colour, else its glyph.
private struct FileTypeTile: View {
    let attachment: MailAttachment
    let kind: AttachmentKind
    let encrypted: Bool

    var body: some View {
        let ext = AttachmentKind.fileExtension(attachment)
        let shape = RoundedRectangle(cornerRadius: GMShape.small, style: .continuous)
        ZStack {
            shape.fill(kind.tint)
            if !ext.isEmpty, ext.count <= 4, ext.allSatisfy({ $0.isLetter || $0.isNumber }) {
                Text(verbatim: ext.uppercased())
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(GM.onPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .padding(.horizontal, 2)
            } else {
                Image(systemName: kind.glyph)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(GM.onPrimary)
            }
        }
        .frame(width: 40, height: 40)
        .overlay(alignment: .bottomTrailing) {
            if encrypted {
                Image(systemName: "lock.fill")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundColor(GM.successInk)
                    .padding(3)
                    .background(Circle().fill(GM.card))
                    .offset(x: 4, y: 4)
            }
        }
        .accessibilityHidden(true)
    }
}
