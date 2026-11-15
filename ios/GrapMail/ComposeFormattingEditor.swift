import SwiftUI
import WebKit

struct ComposeFormattingEditor: UIViewRepresentable {
    let text: String
    let html: String?
    let onChange: (String, String?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.userContentController.add(context.coordinator, name: "draftChanged")
        let view = WKWebView(frame: .zero, configuration: config)
        view.isOpaque = false
        view.navigationDelegate = context.coordinator
        if let resource = Bundle.main.url(forResource: "GrapMailComposeEditor", withExtension: "html"),
           let document = try? String(contentsOf: resource, encoding: .utf8) {
            view.loadHTMLString(document, baseURL: nil)
        }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.editor = self
        context.coordinator.sync(view)
    }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.configuration.userContentController.removeScriptMessageHandler(forName: "draftChanged")
        view.stopLoading()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var editor: ComposeFormattingEditor
        private var ready = false
        private var renderedText: String?
        private var renderedHtml: String?

        init(_ editor: ComposeFormattingEditor) { self.editor = editor }

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
            sync(webView)
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            decisionHandler(ready || action.navigationType == .linkActivated ? .cancel : .allow)
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame, let body = message.body as? [String: Any],
                  let text = body["text"] as? String else { return }
            let html = body["html"] as? String
            renderedText = text
            renderedHtml = html
            editor.onChange(text, html)
        }
    }
}
