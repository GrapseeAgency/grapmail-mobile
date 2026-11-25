import SwiftUI
import WebKit

/// The reader's formatted-body island — the ONE iOS surface allowed to put a
/// message in a WKWebView, and the reason it can be one at all.
///
/// ── WHAT MAY REACH THIS VIEW ────────────────────────────────────────────────
/// Only the string the body endpoint answered with (`MailBodyResponse.bodyHtml`):
/// the server sanitized the sender's HTML and filtered its inline styles, so by the time
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
/// The reader script (below) also reports the height whenever the body
/// resizes, coalesced to one report per animation frame.
///
/// Dark mode: the sender's design is redrawn dark by `EmailDarkening` (light
/// fills dark, dark text light, brand fills and buttons kept, table lines kept
/// visible, images untouched, 4.5:1 text) instead of being drawn on a white
/// box. Its script is an app-installed user script in its own content world,
/// so page JavaScript stays off and the CSP below is unchanged. The body is
/// invisible over the dark card until the script finishes (one second at
/// most), so neither a white flash nor the undarkened design is ever shown.
///
/// Text size: the message's text follows the person's Dynamic Type setting.
/// The app's own style block sets `-webkit-text-size-adjust` to the body text
/// style's scale (`UIFontMetrics(.body)`: 100 % at the default size), clamped
/// to 80–200 % so a fixed-width newsletter keeps a usable layout at the
/// accessibility sizes, and the document is re-rendered when the size changes.
/// The percentage is applied with `!important` to every element because
/// newsletters routinely pin `-webkit-text-size-adjust:100%` inline on their
/// tables. Anything that still does not fit (a wide table, a long unbroken
/// line) scrolls sideways inside the body instead of running off the card.
/// The base is the reader's 16 px on a 1.55 line (round8-spec §5.1), so at the
/// default text size the HTML body and a plain-text body read the same.
///
/// Reader script (round8-spec §5.2, §5.5): a second app-installed user script,
/// in its OWN content world (`ReaderDocumentScript.world`), the same way the
/// darkening script runs — page JavaScript stays off and the CSP is
/// unchanged. It (1) gives every `<img>` with numeric width/height attributes
/// that aspect ratio before it decodes, so late images do not jump the card;
/// (2) folds the TRAILING quoted history (Gmail, Apple Mail, Outlook,
/// Thunderbird, Yahoo, Proton, or an "On … wrote:" line over a blockquote)
/// into a hidden `div.gm-quote` behind a "··· Show quoted text" button, inside
/// the document — never a forward, never when the message's own text would be
/// empty, never a quote under three lines; and (3) reports the body height
/// through a message handler that only that world can see. The fold's open
/// state is remembered per message for the session (`ReaderSession`).
struct MailHtmlBodyView: UIViewRepresentable {
    let html: String
    let allowImages: Bool
    let onOpenLink: (String) -> Void
    @Binding var contentHeight: CGFloat
    /// false = show the sender's original colours in a dark reader — the
    /// reader's "Show original colours" control (ReaderColourPrefs.swift).
    var darkeningEnabled: Bool = true
    /// The message the quoted-history fold belongs to (its open state is
    /// remembered under this key for the session); nil = never fold.
    var quoteKey: String? = nil
    /// The subject announces a forward: nothing folds (the "quote" IS the message).
    var forwarded: Bool = false
    /// Every measurement, including one equal to the current height: the
    /// reader uses it to drop the placeholder and remember the height.
    var onMeasure: ((CGFloat) -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // No page script, ever. The sanitizer already stripped every script;
        // this is the engine agreeing with it.
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        configuration.websiteDataStore = .nonPersistent()
        // The phone's layout rules on every device: an iPad's default desktop
        // mode ignores the document's viewport and the text-size percentage
        // below, which would leave the body unscaled there.
        configuration.defaultWebpagePreferences.preferredContentMode = .mobile
        // The app's own darkening script, in an isolated world — not page
        // script, so `allowsContentJavaScript = false` above still holds for
        // everything the message carries. It is a no-op on light documents.
        EmailDarkening.install(on: configuration.userContentController)
        // The reader script (quote fold, image boxes, height reports), in its
        // own isolated world, after the darkening script.
        ReaderDocumentScript.install(on: configuration.userContentController,
                                     handler: ReaderScriptHandler(context.coordinator))
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        // A long-press preview loads the linked page inside the app, which is
        // a fetch the sender can see and a page the person never chose to open.
        webView.allowsLinkPreview = false
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        // The reader owns scrolling; this view is sized to its document.
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.decelerationRate = .fast
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.parent = self
        // Re-feed only on a real change: update runs on every render pass, and
        // reloads would restart rendering mid-read.
        let textScale = emailTextScale(for: dynamicTypeSize)
        if context.coordinator.loadedHtml != html || context.coordinator.loadedAllow != allowImages
            || context.coordinator.loadedScheme != colorScheme
            || context.coordinator.loadedDarkening != darkeningEnabled
            || context.coordinator.loadedTextScale != textScale {
            context.coordinator.loadedHtml = html
            context.coordinator.loadedAllow = allowImages
            context.coordinator.loadedScheme = colorScheme
            context.coordinator.loadedDarkening = darkeningEnabled
            context.coordinator.loadedTextScale = textScale
            let traits = UITraitCollection(userInterfaceStyle: colorScheme == .dark ? .dark : .light)
            // The fold's state is read when the document is built, so a
            // re-render (dark mode, text size) keeps what the person chose.
            let fold = ReaderFold(enabled: quoteKey != nil, forwarded: forwarded,
                                  open: quoteKey.map { ReaderSession.openQuotes.contains($0) } ?? false)
            let document = mailDocument(html: html, allowImages: allowImages, traits: traits,
                                        darkeningEnabled: darkeningEnabled, textScale: textScale, fold: fold)
            context.coordinator.navigation = webView.loadHTMLString(document, baseURL: nil)
        }
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.navigation = nil
        webView.navigationDelegate = nil
        webView.configuration.userContentController
            .removeScriptMessageHandler(forName: ReaderDocumentScript.handlerName,
                                        contentWorld: ReaderDocumentScript.world)
        webView.stopLoading()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var parent: MailHtmlBodyView
        var loadedHtml: String?
        var loadedAllow: Bool?
        var loadedScheme: ColorScheme?
        var loadedDarkening: Bool?
        var loadedTextScale: Int?
        var navigation: WKNavigation?
        /// A quote fold was just toggled: the height change it causes
        /// animates (220 ms standard), unless motion is reduced.
        private var animateHeightUntil: Date?

        init(_ parent: MailHtmlBodyView) { self.parent = parent }

        /// One height for the document. Every measurement reaches
        /// `onMeasure`; the binding only changes when the height did.
        private func apply(_ height: CGFloat) {
            guard height > 0 else { return }
            parent.onMeasure?(height)
            guard parent.contentHeight != height else { return }
            let animate = (animateHeightUntil.map { Date() < $0 } ?? false) && !UIAccessibility.isReduceMotionEnabled
            if animate {
                withAnimation(GMMotion.standard(GMMotion.medium)) { parent.contentHeight = height }
            } else {
                parent.contentHeight = height
            }
        }

        private func measure(_ webView: WKWebView, navigation: WKNavigation) {
            guard self.navigation === navigation else { return }
            webView.evaluateJavaScript(
                "document.body ? Math.ceil(Math.max(document.body.offsetHeight, document.body.scrollHeight)) : 0",
            ) { [weak self] value, _ in
                if let height = value as? CGFloat, height > 0 {
                    DispatchQueue.main.async {
                        guard let self, self.navigation === navigation else { return }
                        self.apply(height)
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

        /// The reader script's reports: `{type: "height", value}` (already
        /// coalesced to one per animation frame) and `{type: "quote", open}`.
        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame, navigation != nil,
                  let body = message.body as? [String: Any],
                  let type = body["type"] as? String else { return }
            switch type {
            case "height":
                if let value = body["value"] as? Double { apply(CGFloat(value)) }
            case "quote":
                guard let open = body["open"] as? Bool else { return }
                if let key = parent.quoteKey {
                    if open { ReaderSession.openQuotes.insert(key) } else { ReaderSession.openQuotes.remove(key) }
                }
                animateHeightUntil = Date().addingTimeInterval(0.6)
                Haptics.selection()
            default:
                break
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void,
        ) {
            // Everything is refused in here. A link tap goes to the person's
            // own browser, and only for http(s) and mailto: a `tel:`, `sms:`,
            // app-scheme or `file:` link in a stranger's mail is not something
            // to hand to the system. The only load that passes is the document
            // itself (`loadHTMLString` with no base URL is about:blank); a
            // meta refresh, a form post or any other navigation goes nowhere.
            let url = navigationAction.request.url
            if navigationAction.navigationType == .linkActivated {
                if let url, ["http", "https", "mailto"].contains(url.scheme?.lowercased() ?? "") {
                    parent.onOpenLink(url.absoluteString)
                }
                decisionHandler(.cancel)
            } else if navigationAction.targetFrame?.isMainFrame == true,
                      url == nil || url?.absoluteString == "about:blank" {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }
    }
}

/// The content controller keeps its message handlers strongly; this
/// forwarder keeps the coordinator weakly, so a reader that leaves the screen
/// is released with its web view.
private final class ReaderScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?

    init(_ target: WKScriptMessageHandler) { self.target = target }

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

// MARK: - the document around the sanitized fragment

/// What the reader script is told about the quoted-history fold.
private struct ReaderFold {
    let enabled: Bool
    let forwarded: Bool
    let open: Bool
}

/// App-authored, constant except for the palette colours interpolated into it —
/// this is the island's own typography, not something a sender styled.
private func mailDocument(html: String, allowImages: Bool, traits: UITraitCollection,
                          darkeningEnabled: Bool, textScale: Int, fold: ReaderFold) -> String {
    // https only: the server upgrades http images, and a cleartext request
    // would show the network what was opened.
    // data: is only ever an inline image of this same message (the server draws
    // cid: parts from the message's own attachments), so it shows without the
    // remote-image opt-in and tells no sender anything.
    let imageSources = allowImages ? "data: https:" : "data:"
    let authoredColors = html.contains(" style=\"") || html.contains(" bgcolor=\"")
    let darkening = EmailDarkening.isActive(isDark: traits.userInterfaceStyle == .dark, enabled: darkeningEnabled)
    // A sender's light design keeps its own white canvas only when nothing is
    // going to darken it: light mode, or a dark reader with darkening off. Its
    // chrome (quote bar, image chips) is then drawn in the light palette too.
    let lightDesign = authoredColors && !darkening
    let chrome = lightDesign ? UITraitCollection(userInterfaceStyle: .light) : traits
    let canvas = lightDesign ? "#FFFFFF" : "transparent"
    let paletteText = UIColor(GM.textPrimary).resolvedColor(with: traits).hexString
    let text = lightDesign ? "#1F2937" : paletteText
    let muted = UIColor(GM.textSecondary).resolvedColor(with: chrome).hexString
    let border = UIColor(GM.border).resolvedColor(with: chrome).hexString
    let link = lightDesign ? "#174EA6" : UIColor(GM.primarySolid).resolvedColor(with: traits).hexString
    let chip = UIColor(GM.bgTinted).resolvedColor(with: chrome).hexString
    let pill = UIColor(GM.softFill).resolvedColor(with: chrome).hexString
    let surface = UIColor(GM.card).resolvedColor(with: traits).hexString
    // Plain String constants keep the concatenation below cheap to type-check.
    let rootAttributes: String = EmailDarkening.rootAttributes(active: darkening, surfaceHex: surface, textHex: paletteText)
    let readerAttributes: String = ReaderDocumentScript.rootAttributes(fold)
    let pendingCSS: String = EmailDarkening.pendingCSS(active: darkening)
    // Dynamic Type (see the type's comment). `html` hides its own overflow so
    // the body's sideways scroll belongs to the body, not to the (fixed) page.
    let scale = "\(textScale)%"
    let textSizeCSS: String = "html,body,body *{-webkit-text-size-adjust:" + scale + "!important;" +
        "text-size-adjust:" + scale + "!important;}" +
        "html{overflow:hidden;}body{overflow-x:auto;overflow-y:hidden;-webkit-overflow-scrolling:touch;}"
    // Code: 14/20 monospace; a `pre` block scrolls sideways inside itself
    // only (§5.1), inline code still wraps.
    let codeCSS: String = "pre,code{font-family:Menlo,monospace;font-size:14px;}" +
        "code{white-space:pre-wrap;overflow-wrap:anywhere;}" +
        "pre{white-space:pre;line-height:20px;overflow-x:auto;max-width:100%;-webkit-overflow-scrolling:touch;}" +
        "pre code{white-space:inherit;overflow-wrap:normal;}"
    // A fixed-width newsletter table is centred at its own width, never
    // stretched (§5.1); anything wider still fits through the rules above.
    let layoutCSS: String = "body>table[width],body>center>table[width]{margin-left:auto;margin-right:auto;}"
    // The quoted-history fold (§5.2): the pill is 32 high, pill-shaped,
    // softFill, CaptionMedium textSecondary, 12 above it.
    let quoteCSS: String = ".gm-quote[hidden]{display:none!important;}" +
        ".gm-quote-bar{display:block!important;margin:12px 0 0 0;}" +
        ".gm-quote-toggle{-webkit-appearance:none;appearance:none;display:inline-flex;align-items:center;" +
        "height:32px;padding:0 14px;margin:0;border:0;border-radius:999px;background:" + pill + ";color:" + muted + ";" +
        "font:500 13px -apple-system,'Helvetica Neue',sans-serif;cursor:pointer;}" +
        ".gm-quote-dots{font-weight:700;font-size:15px;margin-right:8px;letter-spacing:1px;}" +
        ".gm-quote{margin-top:8px;}"
    return "<!DOCTYPE html><html\(rootAttributes)\(readerAttributes)><head><meta charset=\"utf-8\"/>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
        "img-src \(imageSources); style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">" +
        "<style data-gm-own>" +
        "html,body{margin:0;padding:0;background:\(canvas);}" +
        "body{font-family:-apple-system,'Helvetica Neue',sans-serif;font-size:16px;line-height:1.55;color:" + text + ";" +
        "word-wrap:break-word;overflow-wrap:break-word;padding:0 2px;}" +
        "a{color:" + link + ";}" +
        "img{max-width:100%!important;height:auto;}" +
        "table,div{max-width:100%!important;}" +
        "table,div,td,th{min-width:0!important;overflow-wrap:anywhere;}" +
        codeCSS +
        "blockquote{margin:8px 0;padding:2px 12px;border-left:3px solid " + border + ";color:" + muted + ";}" +
        // A blocked remote image has no `src` at all — WebKit draws its alt
        // text as an inline broken image, and this turns that into a
        // deliberate-looking chip instead of a broken-image glyph.
        "img[data-blocked-src],img[data-grapmail-blocked-src],img[data-cid-src]{display:inline-block;min-height:16px;padding:4px 10px;" +
        "border:1px dashed " + border + ";border-radius:8px;background:" + chip + ";color:" + muted + ";font-size:12px;}" +
        layoutCSS +
        quoteCSS +
        pendingCSS +
        textSizeCSS +
        "</style></head><body>" + html + "</body></html>"
}

/// The body text style's scale at `size`, in percent (100 at the default
/// "Large"), clamped to 80–200 %: beyond about double, a 600 px newsletter
/// stops being a layout at all on a phone.
private func emailTextScale(for size: DynamicTypeSize) -> Int {
    let category: UIContentSizeCategory
    switch size {
    case .xSmall: category = .extraSmall
    case .small: category = .small
    case .medium: category = .medium
    case .large: category = .large
    case .xLarge: category = .extraLarge
    case .xxLarge: category = .extraExtraLarge
    case .xxxLarge: category = .extraExtraExtraLarge
    case .accessibility1: category = .accessibilityMedium
    case .accessibility2: category = .accessibilityLarge
    case .accessibility3: category = .accessibilityExtraLarge
    case .accessibility4: category = .accessibilityExtraExtraLarge
    case .accessibility5: category = .accessibilityExtraExtraExtraLarge
    @unknown default: category = .large
    }
    let traits = UITraitCollection(preferredContentSizeCategory: category)
    let scaled = UIFontMetrics(forTextStyle: .body).scaledValue(for: 100, compatibleWith: traits)
    return min(200, max(80, Int(scaled.rounded())))
}

extension UIColor {
    fileprivate var hexString: String {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { return "#000000" }
        return String(
            format: "#%02X%02X%02X",
            Int(round(red * 255)), Int(round(green * 255)), Int(round(blue * 255))
        )
    }
}

// MARK: - the reader script

/// The reader's own document script (see `MailHtmlBodyView`'s comment). Runs
/// at document end in an isolated content world after `EmailDarkening`; only
/// that world can see the `gmReader` message handler. Every step is wrapped so
/// a failure leaves the document exactly as the sender's markup drew it.
private enum ReaderDocumentScript {
    static let handlerName = "gmReader"

    /// The same name always returns the same world, so this is computed.
    static var world: WKContentWorld { .world(name: "GrapMailReader") }

    static func install(on controller: WKUserContentController, handler: WKScriptMessageHandler) {
        controller.addUserScript(WKUserScript(
            source: script,
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: true,
            in: world
        ))
        controller.add(handler, contentWorld: world, name: handlerName)
    }

    /// Attributes for the `<html>` tag: whether to fold, whether it starts
    /// open, and the button's two labels (localised, attribute-escaped).
    static func rootAttributes(_ fold: ReaderFold) -> String {
        guard fold.enabled else { return "" }
        var out = " data-gm-quote=\"1\""
        if fold.forwarded { out += " data-gm-fwd=\"1\"" }
        if fold.open { out += " data-gm-quote-open=\"1\"" }
        out += " data-gm-quote-show=\"" + escape(String(localized: "Show quoted text", table: "Reader")) + "\""
        out += " data-gm-quote-hide=\"" + escape(String(localized: "Hide quoted text", table: "Reader")) + "\""
        return out
    }

    private static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&#39;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    static let script = #"""
(function () {
  'use strict';
  var D = document, root = D.documentElement, body = D.body;
  if (!root || !body) return;
  function post(msg) {
    try { window.webkit.messageHandlers.gmReader.postMessage(msg); } catch (e) { }
  }

  // Height reports, one per animation frame at most.
  var last = -1, queued = false;
  function measure() {
    queued = false;
    var h = Math.ceil(Math.max(body.offsetHeight, body.scrollHeight));
    if (h > 0 && h !== last) { last = h; post({ type: 'height', value: h }); }
  }
  function report() {
    if (queued) return;
    queued = true;
    if (window.requestAnimationFrame) window.requestAnimationFrame(measure); else setTimeout(measure, 16);
  }

  // 1. Reserve each image's box before it decodes.
  try {
    var NUM = /^\s*(\d+(?:\.\d+)?)(?:px)?\s*$/i;
    var imgs = body.querySelectorAll('img[width][height]');
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i];
      if (im.hasAttribute('data-blocked-src') || im.hasAttribute('data-grapmail-blocked-src') ||
          im.hasAttribute('data-cid-src')) continue;
      var w = NUM.exec(im.getAttribute('width') || ''), h = NUM.exec(im.getAttribute('height') || '');
      if (!w || !h) continue;
      var wv = parseFloat(w[1]), hv = parseFloat(h[1]);
      if (wv > 0 && hv > 0) im.style.setProperty('aspect-ratio', wv + ' / ' + hv);
    }
  } catch (e) { }

  // 2. Fold the trailing quoted history.
  function fold() {
    if (root.getAttribute('data-gm-quote') !== '1') return;
    if (root.getAttribute('data-gm-fwd') === '1') return;
    var FWD = /(-{2,}\s*Forwarded message\s*-{2,}|Begin forwarded message|Weitergeleitete Nachricht|Message transf\u00e9r\u00e9|Mensaje reenviado|Mensagem encaminhada|Messaggio inoltrato)/i;
    if (FWD.test(body.textContent || '')) return;
    var ATTR = /^\s*(On|Am|Le|El|Em|Il|Op)\s[\s\S]{4,480}\s(wrote|schrieb|a\s+\u00e9crit|escribi\u00f3|escreveu|ha\s+scritto|schreef)[^:]{0,160}:\s*$/i;
    var STRONG = 'blockquote[type="cite"],.gmail_quote,.yahoo_quoted,#appendonsend,#divRplyFwdMsg,div.moz-cite-prefix,.protonmail_quote';
    var ANY = STRONG + ',blockquote';
    function flat(s) { return (s || '').replace(/[\s\u00a0]+/g, ' ').trim(); }
    function shows(n) {
      if (!n) return false;
      if (n.nodeType === 3) return /\S/.test((n.nodeValue || '').replace(/\u00a0/g, ' '));
      if (n.nodeType !== 1) return false;
      var t = n.localName;
      if (t === 'style' || t === 'script' || t === 'template' || t === 'meta' || t === 'link' || t === 'title') return false;
      if (t === 'img' || t === 'video' || t === 'svg' || t === 'picture') return true;
      if (flat(n.textContent)) return true;
      return !!n.querySelector('img,video,svg,picture');
    }
    function prevShown(n) {
      for (var s = n.previousSibling; s; s = s.previousSibling) {
        if (s.nodeType === 1 && s.localName === 'hr') return s;
        if (shows(s)) return s;
      }
      return null;
    }
    function nothingAfter(n) {
      for (var x = n; x && x !== body; x = x.parentNode) {
        for (var s = x.nextSibling; s; s = s.nextSibling) { if (shows(s)) return false; }
      }
      return true;
    }
    function isAttribution(n) {
      if (!n || (n.nodeType === 1 && n.localName === 'hr')) return false;
      var t = n.nodeType === 3 ? (n.nodeValue || '') : (n.innerText || n.textContent || '');
      t = flat(t);
      return t.length > 0 && t.length <= 600 && ATTR.test(t);
    }
    function wrap(first, last) {
      var parent = first.parentNode;
      if (!parent || last.parentNode !== parent) return false;
      var nodes = [], n, k;
      for (n = first; n; n = n.nextSibling) { nodes.push(n); if (n === last) break; }
      if (nodes[nodes.length - 1] !== last) return false;
      // A quote under three lines stays as it is.
      var text = '';
      for (k = 0; k < nodes.length; k++) {
        var nd = nodes[k];
        text += '\n' + (nd.nodeType === 1 ? (nd.innerText || nd.textContent || '') : (nd.nodeValue || ''));
      }
      var lines = 0, parts = text.split('\n');
      for (k = 0; k < parts.length; k++) { if (/\S/.test(parts[k].replace(/\u00a0/g, ' '))) lines++; }
      if (lines < 3) return false;
      var box = D.createElement('div');
      box.className = 'gm-quote';
      parent.insertBefore(box, first);
      for (k = 0; k < nodes.length; k++) box.appendChild(nodes[k]);
      box.setAttribute('hidden', '');
      // The message's own text must remain; otherwise show it whole.
      if (!flat(body.innerText || '')) {
        while (box.firstChild) parent.insertBefore(box.firstChild, box);
        parent.removeChild(box);
        return false;
      }
      var showLabel = root.getAttribute('data-gm-quote-show') || 'Show quoted text';
      var hideLabel = root.getAttribute('data-gm-quote-hide') || 'Hide quoted text';
      var bar = D.createElement('div');
      bar.className = 'gm-quote-bar';
      var btn = D.createElement('button');
      btn.type = 'button';
      btn.className = 'gm-quote-toggle';
      var dots = D.createElement('span');
      dots.className = 'gm-quote-dots';
      dots.setAttribute('aria-hidden', 'true');
      dots.textContent = '\u00b7\u00b7\u00b7';
      var label = D.createElement('span');
      btn.appendChild(dots);
      btn.appendChild(label);
      bar.appendChild(btn);
      function set(open) {
        if (open) box.removeAttribute('hidden'); else box.setAttribute('hidden', '');
        btn.setAttribute('aria-expanded', open ? 'true' : 'false');
        label.textContent = open ? hideLabel : showLabel;
        dots.style.display = open ? 'none' : '';
      }
      set(root.getAttribute('data-gm-quote-open') === '1');
      btn.addEventListener('click', function (ev) {
        if (ev) ev.preventDefault();
        var open = box.hasAttribute('hidden');
        set(open);
        post({ type: 'quote', open: open });
        report();
      });
      parent.insertBefore(bar, box);
      return true;
    }
    var list = body.querySelectorAll(ANY), cands = [], i, p;
    for (i = 0; i < list.length; i++) {
      var nested = false;
      for (p = list[i].parentElement; p && p !== body; p = p.parentElement) {
        if (p.matches(ANY)) { nested = true; break; }
      }
      if (!nested) cands.push(list[i]);
    }
    // The first candidate, in document order, whose block is trailing:
    // inline replies (text, quote, text, quote) keep every quote but the last.
    for (i = 0; i < cands.length; i++) {
      var el = cands[i], first = el, end = el, strong = el.matches(STRONG);
      if (el.id === 'appendonsend' || el.id === 'divRplyFwdMsg') {
        // Outlook: the header and everything after it, with the rule above it.
        end = el.parentNode.lastChild;
        var hr = prevShown(el);
        if (hr && hr.nodeType === 1 && hr.localName === 'hr') first = hr;
        if (el.parentNode !== body && !nothingAfter(el.parentNode)) continue;
      } else if (el.classList && el.classList.contains('moz-cite-prefix')) {
        // Thunderbird: the attribution div and the blockquote after it.
        var next = el.nextElementSibling;
        if (next && next.localName === 'blockquote') end = next;
        if (!nothingAfter(end)) continue;
      } else {
        var before = prevShown(el);
        if (isAttribution(before)) first = before;
        else if (!strong) continue;
        if (!nothingAfter(end)) continue;
      }
      if (wrap(first, end)) return;
    }
  }
  try { fold(); } catch (e) { }

  // 3. Height reports from now on.
  if (window.ResizeObserver) {
    try { new ResizeObserver(function () { report(); }).observe(body); } catch (e) { }
  }
  window.addEventListener('load', report);
  report();
})();
"""#
}
