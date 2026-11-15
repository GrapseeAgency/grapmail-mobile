import SwiftUI
import WebKit

struct OnboardingSceneModel {
    let index: Int
    let eyebrow: String
    let title: String
    let description: String
    let ctaText: String
    let filename: String
}

let onboardingScenes: [OnboardingSceneModel] = [
    OnboardingSceneModel(
        index: 0,
        eyebrow: "WELCOME",
        title: "Welcome to Your Sanctuary",
        description: "Your new address is ready; welcome to a cleaner way to communicate.",
        ctaText: "Get Started",
        filename: "onboarding_scene_1"
    ),
    OnboardingSceneModel(
        index: 1,
        eyebrow: "SCREENER GATE",
        title: "You Decide Who Reaches You",
        description: "Unknown senders wait in the Screener until you decide.",
        ctaText: "Next",
        filename: "onboarding_scene_2"
    ),
    OnboardingSceneModel(
        index: 2,
        eyebrow: "SUBSCRIPTIONS",
        title: "Tame the Clutter",
        description: "Unsubscribe, snooze, and return to what matters.",
        ctaText: "Next",
        filename: "onboarding_scene_3"
    ),
    OnboardingSceneModel(
        index: 3,
        eyebrow: "QUIET BY DEFAULT",
        title: "Choose Your Rhythm",
        description: "Set your theme and decide when GrapMail can interrupt you.",
        ctaText: "Next",
        filename: "onboarding_scene_4"
    ),
    OnboardingSceneModel(
        index: 4,
        eyebrow: "YOUR SANCTUARY",
        title: "Your Inbox Is Yours Again",
        description: "Welcome home; your new mailbox is ready.",
        ctaText: "Open My Mailbox",
        filename: "onboarding_scene_5"
    ),
]

// The artwork's own outer colour. The page is painted this colour so the scene
// dissolves into the screen instead of sitting inside a visible plate.
private enum Scene {
    static let bg       = Color(red: 0x02 / 255, green: 0x08 / 255, blue: 0x17 / 255)
    static let ink      = Color(red: 0xF3 / 255, green: 0xF9 / 255, blue: 0xFF / 255)
    static let sub      = Color(red: 0xA0 / 255, green: 0xB8 / 255, blue: 0xD6 / 255)
    static let accent   = Color(red: 0x63 / 255, green: 0xCB / 255, blue: 0xFF / 255)
    static let hairline = Color(red: 0x17 / 255, green: 0x2B / 255, blue: 0x47 / 255)
}

/// The five onboarding scenes. The artwork is the founder's standalone HTML,
/// bundled untouched, and drawn by a WKWebView; only the host is written here
/// (headline, walkthrough controls, Skip, CTA).
///
/// The one thing the artwork needs from its host is a viewport. A web view
/// parses the document before SwiftUI has laid it out, so the initial
/// containing block is 0 tall and never grows: `height:100%` on `html`/`body`
/// resolves to 0 (every scene root uses it), `100vh` resolves to 0 in that
/// state too, and `body{overflow:hidden}` then clips the whole scene away — the
/// blank panel this screen used to be. A pixel height measured from the live
/// viewport is the only value that resolves, so `fitScript` pins it, retries
/// while layout settles, and re-pins on resize.
///
/// It writes two lengths and nothing else: no colour, font, transform, timing
/// or markup of the artwork is touched. Each scene still runs the exact
/// stylesheet and script it was authored with, offline, with no navigation.
struct OnboardingHeroView: UIViewRepresentable {
    let filename: String
    let onOpenMailbox: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onOpenMailbox: onOpenMailbox)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // A demo animation has no business writing cookies or storage into the
        // person's app data. Non-persistent keeps it out of the shared jar.
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.userContentController.add(context.coordinator, name: "grapmail")
        configuration.userContentController.addUserScript(
            WKUserScript(
                source: Self.fitScript,
                injectionTime: .atDocumentEnd,
                forMainFrameOnly: true
            )
        )

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsLinkPreview = false
        webView.isUserInteractionEnabled = true

        context.coordinator.lastLoadedFilename = filename
        context.coordinator.load(filename, into: webView)
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if context.coordinator.lastLoadedFilename != filename {
            context.coordinator.lastLoadedFilename = filename
            context.coordinator.load(filename, into: uiView)
        }
    }

    static let fitScript = """
    (function () {
      var html = document.documentElement;
      function fit() {
        var h = window.innerHeight || html.clientHeight || 0;
        if (!h || !document.body) return false;
        html.style.height = h + 'px';
        document.body.style.height = h + 'px';
        return true;
      }
      var frames = 0;
      (function settle() {
        fit();
        if (++frames < 40) requestAnimationFrame(settle);
      })();
      window.addEventListener('resize', fit, { passive: true });
      window.addEventListener('orientationchange', fit, { passive: true });
    })();
    """

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var lastLoadedFilename: String = ""
        let onOpenMailbox: () -> Void

        init(onOpenMailbox: @escaping () -> Void) {
            self.onOpenMailbox = onOpenMailbox
        }

        /// The scene is handed to the view as text with the viewport fit already
        /// inside it, so the document never resolves a file path and the fit is
        /// in place before the first layout — the same delivery the bundled
        /// welcome animation uses.
        func load(_ name: String, into webView: WKWebView) {
            guard
                let url = Bundle.main.url(forResource: name, withExtension: "html", subdirectory: "onboarding")
                    ?? Bundle.main.url(forResource: name, withExtension: "html"),
                let source = try? String(contentsOf: url, encoding: .utf8)
            else {
                return
            }
            let html: String
            if let bodyEnd = source.range(of: "</body>") {
                html = source.replacingCharacters(in: bodyEnd, with: "<script>\(Self.fitScript)</script></body>")
            } else {
                html = source + "<script>\(Self.fitScript)</script>"
            }
            webView.loadHTMLString(html, baseURL: url.deletingLastPathComponent())
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "grapmail" else { return }
            // The scene posts `{type:'grapmail:open-mailbox'}`; anything else the
            // artwork may announce is not a host action yet, so it is ignored
            // rather than guessed at.
            if let body = message.body as? [String: Any],
               let type = body["type"] as? String,
               type.contains("open-mailbox") {
                DispatchQueue.main.async { self.onOpenMailbox() }
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // Belt and braces for a first layout that arrives late: the injected
            // script already retried, this catches the case where the view was
            // still 0-sized then.
            webView.evaluateJavaScript(Self.fitScript, completionHandler: nil)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            let url = navigationAction.request.url
            if let scheme = url?.scheme, scheme == "grapmail" {
                decisionHandler(.cancel)
                DispatchQueue.main.async { self.onOpenMailbox() }
                return
            }
            // The scene is a picture, not a browser: only the initial load is
            // allowed, and every other navigation is refused.
            if navigationAction.navigationType == .other, url?.isFileURL == true {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }
    }
}

/// The walkthrough host. Layout, headings, Skip and the CTA belong to the app;
/// the animated artwork is the founder's five scenes, drawn as authored.
///
/// Scene 1 is the required welcome: it has no Skip. Scenes 2–5 can be skipped
/// from the top bar, and Next/Skip never wait for an animation to finish.
///
/// The page is deliberately dark on both system appearances: the five scenes are
/// authored on one continuous airy navy field, and a light page would put a
/// visible plate around artwork that is meant to dissolve into the screen.
struct OnboardingView: View {
    let onFinish: () -> Void
    /// Test-only: the snapshot host opens directly on one scene.
    var initialScene: Int = 0

    @State private var sceneIndex: Int

    init(onFinish: @escaping () -> Void, initialScene: Int = 0) {
        self.onFinish = onFinish
        self.initialScene = initialScene
        _sceneIndex = State(initialValue: min(max(initialScene, 0), onboardingScenes.count - 1))
    }

    private var currentScene: OnboardingSceneModel {
        onboardingScenes[sceneIndex]
    }

    private var canSkip: Bool { sceneIndex > 0 }

    var body: some View {
        GeometryReader { viewport in
            // The artwork is authored 440 x 560. It gets that ratio at full
            // width, but never more than a little over half the screen, so the
            // words and the CTA always have room on a short phone.
            let heroHeight = min(viewport.size.width * 560.0 / 440.0, viewport.size.height * 0.54)

            VStack(spacing: 0) {
                topBar

                OnboardingHeroView(
                    filename: currentScene.filename,
                    onOpenMailbox: onFinish
                )
                .frame(width: viewport.size.width, height: heroHeight)
                .accessibilityLabel("Animated scene: \(currentScene.title)")

                copyBlock
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                pageDots

                GradientButton(
                    title: currentScene.ctaText,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                    ),
                    action: {
                        if sceneIndex < onboardingScenes.count - 1 {
                            sceneIndex += 1
                        } else {
                            onFinish()
                        }
                    }
                )
                .padding(.horizontal, 24)
                .accessibilityIdentifier("onboarding-cta")

                Spacer().frame(height: 28)
            }
        }
        .background(Scene.bg.ignoresSafeArea())
        .environment(\.colorScheme, .dark)
        .preferredColorScheme(.dark)
        .navigationBarHidden(true)
    }

    private var topBar: some View {
        HStack(spacing: 0) {
            HStack(spacing: 8) {
                Wordmark()
            }
            Spacer(minLength: 0)
            if canSkip {
                Button("Skip") { onFinish() }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(Scene.sub)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                    .accessibilityIdentifier("onboarding-skip")
            } else {
                // Scene 1 cannot be skipped; the empty box keeps the wordmark in
                // the same place when the control appears on scene 2.
                Color.clear.frame(width: 56, height: 1)
            }
        }
        .padding(.leading, 20)
        .padding(.trailing, 12)
        .padding(.top, 14)
        .padding(.bottom, 4)
    }

    private var copyBlock: some View {
        VStack(spacing: 0) {
            Text(currentScene.eyebrow)
                .font(.system(size: 11, weight: .bold))
                .tracking(1.8)
                .foregroundColor(Scene.accent)

            Spacer().frame(height: 8)

            Text(currentScene.title)
                .font(.system(size: 27, weight: .semibold))
                .foregroundColor(Scene.ink)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.85)

            Spacer().frame(height: 10)

            Text(currentScene.description)
                .font(.system(size: 15))
                .foregroundColor(Scene.sub)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
        }
        .padding(.horizontal, 24)
    }

    private var pageDots: some View {
        HStack(spacing: 7) {
            ForEach(onboardingScenes.indices, id: \.self) { i in
                let active = i == sceneIndex
                Capsule(style: .continuous)
                    .fill(active ? Scene.accent : Scene.hairline)
                    .frame(width: active ? 22 : 6, height: 5)
                    .overlay(
                        Capsule(style: .continuous)
                            .stroke(active ? Color.clear : Scene.hairline, lineWidth: 0.5)
                    )
            }
        }
        .padding(.bottom, 20)
    }
}

/// "Shown once, on this device."
///
/// The walkthrough is tied to the device, not to an account: somebody who
/// creates a second mailbox has already met the product, and replaying five
/// scenes at them is an interruption. Cleared by uninstalling the app.
enum OnboardingGate {
    private static let key = "grapmail.onboarding.completed"

    static var isDone: Bool {
        UserDefaults.standard.bool(forKey: key)
    }

    static func markDone() {
        UserDefaults.standard.set(true, forKey: key)
    }
}
