import QuickLook
import SwiftUI

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

/// The system share sheet for one downloaded file.
///
/// `ShareLink` wants a `Transferable`, and inventing a conformance for a
/// transient downloaded file is more machinery than the need. The URL is the
/// item; this hands exactly the file that was fetched, with its real name.
struct FileActivityView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
