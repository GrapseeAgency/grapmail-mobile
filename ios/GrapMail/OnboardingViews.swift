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
        ctaText: "Get Started →",
        filename: "onboarding_scene_1"
    ),
    OnboardingSceneModel(
        index: 1,
        eyebrow: "SCREENER GATE",
        title: "You Decide Who Reaches You",
        description: "Unknown senders wait in the Screener until you decide.",
        ctaText: "Next →",
        filename: "onboarding_scene_2"
    ),
    OnboardingSceneModel(
        index: 2,
        eyebrow: "SUBSCRIPTIONS",
        title: "Tame the Clutter",
        description: "Unsubscribe, snooze, and return to what matters.",
        ctaText: "Next →",
        filename: "onboarding_scene_3"
    ),
    OnboardingSceneModel(
        index: 3,
        eyebrow: "QUIET BY DEFAULT",
        title: "Choose Your Rhythm",
        description: "Set your theme and decide when GrapMail can interrupt you.",
        ctaText: "Next →",
        filename: "onboarding_scene_4"
    ),
    OnboardingSceneModel(
        index: 4,
        eyebrow: "YOUR SANCTUARY",
        title: "Your Inbox Is Yours Again",
        description: "Welcome home; your new mailbox is ready.",
        ctaText: "Open My Mailbox →",
        filename: "onboarding_scene_5"
    ),
]

struct OnboardingHeroView: UIViewRepresentable {
    let filename: String
    let onOpenMailbox: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onOpenMailbox: onOpenMailbox)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.userContentController.add(context.coordinator, name: "grapmail")

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsLinkPreview = false

        loadScene(filename, into: webView)
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if context.coordinator.lastLoadedFilename != filename {
            context.coordinator.lastLoadedFilename = filename
            loadScene(filename, into: uiView)
        }
    }

    private func loadScene(_ name: String, into webView: WKWebView) {
        let url = Bundle.main.url(forResource: name, withExtension: "html", subdirectory: "onboarding")
            ?? Bundle.main.url(forResource: name, withExtension: "html")
        if let url = url {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var lastLoadedFilename: String = ""
        let onOpenMailbox: () -> Void

        init(onOpenMailbox: @escaping () -> Void) {
            self.onOpenMailbox = onOpenMailbox
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            if message.name == "grapmail" {
                DispatchQueue.main.async {
                    self.onOpenMailbox()
                }
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            let url = navigationAction.request.url
            if let scheme = url?.scheme, scheme == "grapmail" {
                decisionHandler(.cancel)
                DispatchQueue.main.async {
                    self.onOpenMailbox()
                }
                return
            }
            if navigationAction.navigationType == .other, url?.isFileURL == true {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }
    }
}

struct OnboardingView: View {
    let onFinish: () -> Void
    @State private var sceneIndex: Int = 0

    private var currentScene: OnboardingSceneModel {
        onboardingScenes[sceneIndex]
    }

    var body: some View {
        GeometryReader { viewport in
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 16)

                    // Top Bar
                    HStack {
                        HStack(spacing: 8) {
                            GrapMailMark(size: 32)
                            Wordmark()
                        }
                        Spacer()
                        if sceneIndex > 0 {
                            Button("Skip") {
                                onFinish()
                            }
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(GM.primarySolid)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                        }
                    }
                    .padding(.horizontal, 24)

                    Spacer(minLength: 12)

                    // Hero Artwork
                    OnboardingHeroView(
                        filename: currentScene.filename,
                        onOpenMailbox: onFinish
                    )
                    .frame(maxWidth: 440)
                    .frame(height: min(viewport.size.height * 0.44, 380))
                    .aspectRatio(440.0 / 560.0, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .padding(.horizontal, 20)

                    Spacer(minLength: 16)

                    // Eyebrow
                    Text(currentScene.eyebrow)
                        .font(.system(size: 11, weight: .bold))
                        .tracking(1.6)
                        .foregroundColor(GM.primarySolid)
                        .multilineTextAlignment(.center)

                    Spacer(minLength: 6)

                    // Title
                    Text(currentScene.title)
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(GM.textPrimary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)

                    Spacer(minLength: 8)

                    // Description
                    Text(currentScene.description)
                        .font(.system(size: 14))
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                        .padding(.horizontal, 28)

                    Spacer(minLength: 20)

                    // 5-dot page indicator
                    HStack(spacing: 6) {
                        ForEach(0..<5, id: \.self) { i in
                            Capsule()
                                .fill(i == sceneIndex ? GM.primarySolid : GM.border)
                                .frame(width: i == sceneIndex ? 22 : 7, height: 7)
                        }
                    }

                    Spacer(minLength: 24)

                    // Primary CTA button
                    GradientButton(
                        title: currentScene.ctaText,
                        action: {
                            if sceneIndex < 4 {
                                sceneIndex += 1
                            } else {
                                onFinish()
                            }
                        }
                    )
                    .padding(.horizontal, 24)

                    Spacer(minLength: 24)
                }
                .frame(minHeight: viewport.size.height)
            }
        }
        .background(GM.bg.ignoresSafeArea())
        .navigationBarHidden(true)
    }
}
