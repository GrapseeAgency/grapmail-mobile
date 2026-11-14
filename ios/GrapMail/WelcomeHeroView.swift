import SwiftUI
import WebKit

/// The welcome animation: the founder's `GrapMail_Autoplay_Mailbox.html`,
/// bundled with the app and drawn by a WKWebView.
///
/// It is a picture, not a browser. The file travels inside the app bundle, the
/// web view is non-persistent (no cookies, no local storage, no cache to keep),
/// every navigation except the initial `file://` load is refused, scrolling and
/// bouncing are off, and the whole view ignores touches so the welcome screen's
/// ScrollView keeps the drag. Nothing here needs input — the page runs its tour
/// by itself.
///
/// Sizing is the caller's job: the artwork is authored in a 400 x 440 box
/// (`getFrame()` in the page), and the bundled file carries a small host-mode
/// style block that makes the stage fill the view exactly and keeps the page
/// background transparent, so the welcome screen's backdrop is the only
/// background drawn.
struct WelcomeHeroView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // A demo animation has no business writing cookies or storage into the
        // person's app data, and it must not inherit anything from the mail
        // web view either.
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isUserInteractionEnabled = false
        webView.allowsLinkPreview = false
        webView.isAccessibilityElement = false
        webView.accessibilityElementsHidden = true

        if let url = Bundle.main.url(
            forResource: "GrapMail_Autoplay_Mailbox",
            withExtension: "html"
        ) {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        /// The page is one local file with no links. Anything that is not the
        /// initial file load is not navigation this screen offers, so it is
        /// cancelled rather than allowed to replace the hero with a web page.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            let url = navigationAction.request.url
            if navigationAction.navigationType == .other, url?.isFileURL == true {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }
    }
}
