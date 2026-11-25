import QuickLook
import SwiftUI
import UIKit

/// A URL that can drive `.sheet(item:)`.
///
/// `sheet(item:)` needs `Identifiable` so SwiftUI can tell "no presentation"
/// from "present this one", and `URL` deliberately conforms to nothing of the
/// sort. The path is the identity — the same file is the same presentation,
/// and a re-fetched file with a new name is a new one.
struct IdentifiableURL: Identifiable, Equatable {
    let url: URL
    var id: String { url.path }
}

/// QuickLook, wrapped for SwiftUI.
///
/// The attachment preview screen could save a file and say where it put it.
/// That is only half of an attachment: the person wants to READ the PDF or
/// LOOK at the image. QuickLook is Apple's own answer for the formats iOS can
/// render, and its toolbar carries the system share button, so the share half
/// arrives with it instead of being a second, separately half-wired path.
struct QuickLookPresenter: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

        func previewController(_ controller: QLPreviewController,
                               previewItemAt index: Int) -> QLPreviewItem {
            // NSURL is what conforms to QLPreviewItem (it supplies the URL and
            // the item title); a Swift URL by itself does not.
            url as NSURL
        }
    }
}

// MARK: - Full-screen viewer (reader attachments, round8-spec §5.6)

/// One page of the full-screen viewer: the local file, titled with the
/// attachment's own name rather than the sanitised file name it was saved as.
final class QuickLookGalleryItem: NSObject, QLPreviewItem {
    let previewItemURL: URL?
    let previewItemTitle: String?

    init(url: URL, title: String?) {
        previewItemURL = url
        previewItemTitle = title
    }
}

/// The reader's full-screen attachment viewer: QuickLook presented modally
/// over the whole window, paging through several files (swipe between them,
/// pinch and double-tap to zoom, share from its own toolbar, Done to close).
///
/// Presented through UIKit rather than a SwiftUI `.sheet`, because a sheet is
/// not full screen and QuickLook only offers its own Done button when it
/// presents itself. QuickLook's data source is a weak reference, so the
/// source is kept here until the viewer is dismissed.
enum QuickLookGallery {
    private static var current: Source?

    /// Presents `items` starting at `startAt`; false when there was nothing
    /// to show or no window to show it from.
    @MainActor @discardableResult
    static func present(_ items: [QuickLookGalleryItem], startAt index: Int) -> Bool {
        guard !items.isEmpty, let presenter = topViewController() else { return false }
        let source = Source(items: items)
        let controller = QLPreviewController()
        controller.dataSource = source
        controller.delegate = source
        controller.currentPreviewItemIndex = min(max(index, 0), items.count - 1)
        controller.modalPresentationStyle = .fullScreen
        current = source
        presenter.present(controller, animated: !UIAccessibility.isReduceMotionEnabled)
        return true
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let window = active?.windows.first { $0.isKeyWindow } ?? active?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }

    fileprivate static func release(_ source: Source) {
        if current === source { current = nil }
    }

    final class Source: NSObject, QLPreviewControllerDataSource, QLPreviewControllerDelegate {
        let items: [QuickLookGalleryItem]
        init(items: [QuickLookGalleryItem]) { self.items = items }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { items.count }

        func previewController(_ controller: QLPreviewController,
                               previewItemAt index: Int) -> QLPreviewItem {
            items[min(max(index, 0), items.count - 1)]
        }

        func previewControllerDidDismiss(_ controller: QLPreviewController) {
            QuickLookGallery.release(self)
        }
    }
}

/// The system share sheet for one or more downloaded files.
///
/// `ShareLink` wants a `Transferable`, and inventing a conformance for a
/// transient downloaded file is more machinery than the need. The URLs are the
/// items; this hands exactly the files that were fetched, with their names.
/// Several files at once is the reader's "Save all": the sheet's "Save to
/// Files" keeps every one of them.
struct FileActivityView: UIViewControllerRepresentable {
    let urls: [URL]

    init(url: URL) { urls = [url] }
    init(urls: [URL]) { self.urls = urls }

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: urls, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
