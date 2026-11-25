import SwiftUI
import UIKit
import WebKit

/// An uploaded image waiting to be placed in the editor (see insertImage in the HTML).
struct InlineImage: Equatable {
    let contentId: String
    let preview: String
    let alt: String
    let width: Int
}

/// Text dropped on the compose screen, waiting to be typed at the editor's
/// caret (see insertText in the HTML). The id makes the same words dropped
/// twice two insertions.
struct DroppedText: Equatable {
    let id = UUID()
    let text: String
}

struct ComposeFormattingEditor: UIViewRepresentable {
    let text: String
    let html: String?
    var pendingImage: InlineImage? = nil
    var onImageInserted: () -> Void = {}
    var onPickImage: (() -> Void)? = nil
    var droppedText: DroppedText? = nil
    var onTextInserted: () -> Void = {}
    /// Drops on the editor. A web view takes drag events itself, ahead of the
    /// SwiftUI screen around it, and the page refuses drops on purpose (no
    /// foreign HTML gets in that way), so drops here are answered natively and
    /// handed to the compose screen's own drop handler: files become
    /// attachments, text is typed at the caret. Nil: the editor takes none.
    var onDrop: (([NSItemProvider]) -> Bool)? = nil
    /// Whether a drop would be taken right now (not while sending/uploading).
    var dropEnabled = false
    var onDropHover: (Bool) -> Void = { _ in }
    let onChange: (String, String?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.userContentController.add(context.coordinator, name: "draftChanged")
        config.userContentController.add(context.coordinator, name: "pickImage")
        let view = WKWebView(frame: .zero, configuration: config)
        view.isOpaque = false
        view.navigationDelegate = context.coordinator
        Coordinator.removeWebKitDrops(view)
        view.addInteraction(UIDropInteraction(delegate: context.coordinator))
        if let resource = Bundle.main.url(forResource: "GrapMailComposeEditor", withExtension: "html"),
           let document = try? String(contentsOf: resource, encoding: .utf8) {
            view.loadHTMLString(document, baseURL: nil)
        }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.editor = self
        context.coordinator.sync(view)
        context.coordinator.insertPending(view)
        context.coordinator.insertPendingText(view)
    }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.configuration.userContentController.removeScriptMessageHandler(forName: "draftChanged")
        view.configuration.userContentController.removeScriptMessageHandler(forName: "pickImage")
        view.stopLoading()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler, UIDropInteractionDelegate {
        var editor: ComposeFormattingEditor
        private var ready = false
        private var renderedText: String?
        private var renderedHtml: String?
        private var insertedIds = Set<String>()
        private var insertedTextIds = Set<UUID>()

        init(_ editor: ComposeFormattingEditor) { self.editor = editor }

        func insertPending(_ view: WKWebView) {
            guard ready, let image = editor.pendingImage, insertedIds.insert(image.contentId).inserted,
                  let arguments = try? JSONSerialization.data(withJSONObject: [image.contentId, image.preview, image.alt, image.width]),
                  let serialized = String(data: arguments, encoding: .utf8) else { return }
            view.evaluateJavaScript("window.insertImage.apply(null, " + serialized + ")")
            let done = editor.onImageInserted
            DispatchQueue.main.async { done() }
        }

        func insertPendingText(_ view: WKWebView) {
            guard ready, let dropped = editor.droppedText, insertedTextIds.insert(dropped.id).inserted,
                  let arguments = try? JSONSerialization.data(withJSONObject: [dropped.text]),
                  let serialized = String(data: arguments, encoding: .utf8) else { return }
            view.evaluateJavaScript("window.insertText.apply(null, " + serialized + ")")
            let done = editor.onTextInserted
            DispatchQueue.main.async { done() }
        }

        /// WebKit's own drop handling lives on its content view, inside the
        /// scroll view. The page cancels every drop anyway; taking WebKit's
        /// interaction away lets the drop reach the one added on the web view,
        /// which hands it to the compose screen.
        static func removeWebKitDrops(_ view: WKWebView) {
            for child in view.scrollView.subviews {
                for interaction in child.interactions where interaction is UIDropInteraction {
                    child.removeInteraction(interaction)
                }
            }
        }

        // ── drops (UIDropInteractionDelegate) ──

        func dropInteraction(_ interaction: UIDropInteraction, canHandle session: UIDropSession) -> Bool {
            // A drag from inside the app (text dragged within the editor
            // itself, an attachment from the reader) is not taken: the page
            // refused those before, and Android refuses its own files too.
            editor.onDrop != nil && session.localDragSession == nil && !session.items.isEmpty
        }

        func dropInteraction(_ interaction: UIDropInteraction, sessionDidUpdate session: UIDropSession) -> UIDropProposal {
            UIDropProposal(operation: editor.dropEnabled ? .copy : .forbidden)
        }

        func dropInteraction(_ interaction: UIDropInteraction, sessionDidEnter session: UIDropSession) {
            if editor.dropEnabled { editor.onDropHover(true) }
        }

        func dropInteraction(_ interaction: UIDropInteraction, sessionDidExit session: UIDropSession) {
            editor.onDropHover(false)
        }

        func dropInteraction(_ interaction: UIDropInteraction, sessionDidEnd session: UIDropSession) {
            editor.onDropHover(false)
        }

        func dropInteraction(_ interaction: UIDropInteraction, performDrop session: UIDropSession) {
            editor.onDropHover(false)
            guard editor.dropEnabled else { return }
            _ = editor.onDrop?(session.items.map(\.itemProvider))
        }

        func sync(_ view: WKWebView) {
            guard ready, renderedText != editor.text || renderedHtml != editor.html,
                  let arguments = try? JSONSerialization.data(withJSONObject: [editor.text, editor.html ?? ""]),
                  let serialized = String(data: arguments, encoding: .utf8) else { return }
            renderedText = editor.text
            renderedHtml = editor.html
            view.evaluateJavaScript("window.setDraft.apply(null, " + serialized + ")")
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            ready = true
            Self.removeWebKitDrops(webView)
            sync(webView)
            insertPending(webView)
            insertPendingText(webView)
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            decisionHandler(ready || action.navigationType == .linkActivated ? .cancel : .allow)
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            if message.name == "pickImage" {
                guard message.frameInfo.isMainFrame else { return }
                editor.onPickImage?()
                return
            }
            guard message.frameInfo.isMainFrame, let body = message.body as? [String: Any],
                  let text = body["text"] as? String else { return }
            let html = body["html"] as? String
            renderedText = text
            renderedHtml = html
            editor.onChange(text, html)
        }
    }
}
