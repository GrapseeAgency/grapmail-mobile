import SwiftUI
import WebKit

/// The reader's formatted-body island — the ONE iOS surface allowed to put a
/// message in a WKWebView, and the reason it can be one at all.
///
/// ── WHAT MAY REACH THIS VIEW ────────────────────────────────────────────────
/// Only the string the body endpoint answered with (`MailBodyResponse.bodyHtml`):
/// the server sanitized the sender's HTML part with the web's
/// `sanitizeEmailHtml` profile before it left the app's origin, so by the time
/// markup gets here there is no script, no iframe, no form, no event handler
/// and no live remote image in it. The raw `message.bodyHtml` the row carries
/// is never passed here — there is no sanitiser in this app's dependencies,
/// and the correct one is not a regex. A failure upstream falls back to the
/// stripped-text branch the reader always had; it never falls back to raw HTML.
///
/// The engine is belt-and-braces behind the sanitiser, not a substitute for it:
/// page script is off at the engine level (`allowsContentJavaScript = false`),
/// storage is non-persistent, and every link tap is refused inside the view —
/// handed to `onOpenLink` instead, so the message can never point the web view
/// at itself or keep the reader open.
///
/// The view sizes itself: the card it lives in is inside the reader's own
/// scroll, so the web view's internal scrolling is off and its height is
/// measured from the document — once on finish and once more after a beat,
/// because images landing late grow the document without a second finish.
struct MailHtmlBodyView: UIViewRepresentable {
    let html: String
    let allowImages: Bool
    let onOpenLink: (String) -> Void
    @Binding var contentHeight: CGFloat

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // No page script, ever. The sanitizer already stripped every script;
        // this is the engine agreeing with it.
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        configuration.websiteDataStore = .nonPersistent()
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        // The reader owns scrolling; this view is sized to its document.
        webView.scrollView.isScrollEnabled = false
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.parent = self
        // Re-feed only on a real change: update runs on every render pass, and
        // reloads would restart rendering mid-read.
        if context.coordinator.loadedHtml != html || context.coordinator.loadedAllow != allowImages {
            context.coordinator.loadedHtml = html
            context.coordinator.loadedAllow = allowImages
            context.coordinator.navigation = webView.loadHTMLString(mailDocument(html: html), baseURL: nil)
        }
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.navigation = nil
        webView.navigationDelegate = nil
        webView.stopLoading()
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var parent: MailHtmlBodyView
        var loadedHtml: String?
        var loadedAllow: Bool?
        var navigation: WKNavigation?

        init(_ parent: MailHtmlBodyView) { self.parent = parent }

        private func measure(_ webView: WKWebView, navigation: WKNavigation) {
            guard self.navigation === navigation else { return }
            webView.evaluateJavaScript(
                "Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)",
            ) { [weak self] value, _ in
                if let height = value as? CGFloat, height > 0 {
                    DispatchQueue.main.async {
                        guard let self, self.navigation === navigation,
                              self.parent.contentHeight != height else { return }
                        self.parent.contentHeight = height
                    }
                }
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let navigation, self.navigation === navigation else { return }
            measure(webView, navigation: navigation)
            // Images land after the document does; one more measure after a
            // beat catches the growth without polling.
            guard loadedAllow == true else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.65) { [weak self, weak webView] in
                if let webView { self?.measure(webView, navigation: navigation) }
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void,
        ) {
            // Everything is refused in here. A link tap goes to the person's
            // own browser; a non-link load is the document itself and passes.
            if navigationAction.navigationType == .linkActivated {
                if let url = navigationAction.request.url {
                    parent.onOpenLink(url.absoluteString)
                }
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }
    }
}

// MARK: - the document around the sanitized fragment

/// App-authored, constant except for the palette colours interpolated into it —
/// this is the island's own typography, not something a sender styled.
private func mailDocument(html: String) -> String {
    let text = GM.textPrimary.hexString
    let muted = GM.textSecondary.hexString
    let border = GM.border.hexString
    let link = GM.primarySolid.hexString
    let chip = GM.bgTinted.hexString
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
        "<style>" +
        "html,body{margin:0;padding:0;background:transparent;}" +
        "body{font-family:-apple-system,'Helvetica Neue',sans-serif;font-size:15px;line-height:1.55;color:" + text + ";" +
        "word-wrap:break-word;overflow-wrap:break-word;padding:0 2px;}" +
        "a{color:" + link + ";}" +
        "img{max-width:100%;height:auto;}" +
        "table{max-width:100%;border-collapse:collapse;}" +
        "td,th{word-wrap:break-word;}" +
        "pre,code{font-family:Menlo,monospace;font-size:13px;white-space:pre-wrap;}" +
        "blockquote{margin:8px 0;padding:2px 12px;border-left:3px solid " + border + ";color:" + muted + ";}" +
        // A blocked remote image has no `src` at all — WebKit draws its alt
        // text as an inline broken image, and this turns that into a
        // deliberate-looking chip instead of a broken-image glyph.
        "img[data-grapmail-blocked-src]{display:inline-block;min-height:16px;padding:4px 10px;" +
        "border:1px dashed " + border + ";border-radius:8px;background:" + chip + ";color:" + muted + ";font-size:12px;}" +
        "</style></head><body>" + html + "</body></html>"
}

extension UIColor {
    /// `#rrggbb` for the wrapper's CSS. Dynamic colours resolve to the CURRENT
    /// trait collection: the document is re-fed on theme change because
    /// `updateUIView` sees new html only — so the palette values are resolved
    /// once per feed, which is correct for a document that is reloaded whole.
    fileprivate var hexString: String {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { return "#000000" }
        return String(
            format: "#%02X%02X%02X",
            Int(round(red * 255)), Int(round(green * 255)), Int(round(blue * 255))
        )
    }
}

extension Color {
    fileprivate var hexString: String { UIColor(self).resolvedColor(with: UITraitCollection(userInterfaceStyle: UITraitCollection.current.userInterfaceStyle)).hexString }
}
