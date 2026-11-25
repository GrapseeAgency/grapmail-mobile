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
/// The art is pure illustration: the page draws the phone, envelope and badges
/// with a sample mailbox inside the phone, and no headline or tagline — the
/// welcome screen draws those as real, localized Dynamic Type text.
///
/// Sizing is the caller's job: the artwork is authored in a 400 x 440 box
/// (`getFrame()` in the page) and the caller gives this view that aspect ratio
/// (aspect-fit, so it is never cropped). The bundled file carries a small
/// host-mode style block that makes the stage fill the view exactly and keeps
/// the page background transparent, so the welcome screen's backdrop is the
/// only background drawn.
///
/// Reduce Motion: when it is on, the page is loaded with its animations and
/// transitions switched off and the tour paused on its first frame (the inbox
/// inside the phone), using the page's own "tab hidden" pause path. Turning the
/// setting on or off while the screen is visible reloads the page in the new
/// mode.
struct WelcomeHeroView: UIViewRepresentable {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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

        load(webView, reduceMotion: reduceMotion, coordinator: context.coordinator)
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if context.coordinator.loadedReduceMotion != reduceMotion {
            load(uiView, reduceMotion: reduceMotion, coordinator: context.coordinator)
        }
    }

    private func load(_ webView: WKWebView, reduceMotion: Bool, coordinator: Coordinator) {
        coordinator.loadedReduceMotion = reduceMotion
        let controller = webView.configuration.userContentController
        controller.removeAllUserScripts()
        if reduceMotion {
            controller.addUserScript(WKUserScript(
                source: Self.reduceMotionScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            ))
        }
        if let url = Bundle.main.url(
            forResource: "GrapMail_Autoplay_Mailbox",
            withExtension: "html"
        ) {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
    }

    /// Reduce Motion: no CSS animation or transition, and the tour holds its
    /// first frame. The page's `delay()` waits while `document.hidden` is true
    /// (its background-tab pause), so reporting the page as hidden keeps the
    /// first, fully drawn inbox frame on screen without editing the tour.
    private static let reduceMotionScript = """
    (function () {
      try {
        Object.defineProperty(document, 'hidden', { configurable: true, get: function () { return true; } });
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: function () { return 'hidden'; } });
      } catch (e) {}
      var style = document.createElement('style');
      style.textContent = '*,*:before,*:after{animation:none!important;transition:none!important}';
      (document.head || document.documentElement).appendChild(style);
    })();
    """

    final class Coordinator: NSObject, WKNavigationDelegate {
        /// The Reduce Motion mode the current page was loaded in.
        var loadedReduceMotion: Bool?

        /// The page is one local file with no links. Anything that is not the
        /// initial file load is not navigation this screen offers, so it is
        /// cancelled rather than allowed to replace the hero with a web page.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            let url = navigationAction.request.url
            if (navigationAction.navigationType == .other || navigationAction.navigationType == .reload),
               url?.isFileURL == true {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }
    }
}
