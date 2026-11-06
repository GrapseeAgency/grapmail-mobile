import SwiftUI
import UIKit

/// The reader's handoffs that leave the app, and the one opt-in that lets
/// something into it: the text a message becomes outside (share, print), and the
/// images the sender's own server hosts.
///
/// They live together because they are one subject — what a message is allowed
/// to do to the device once the person asks — and outside the screen file
/// because none of them is layout. Android's `MessageHandoff.kt` is the same
/// three decisions, and the sentences in them are deliberately the same.
///
/// ── WHY ONE TEXT, NOT TWO ───────────────────────────────────────────────────
/// Share and print would each have built their own copy of the same document.
/// Two builders drift, and the drift is invisible: the shared card would say the
/// mail came from somebody while the printed page said nothing, or one of them
/// would start carrying the HTML part. One function, both handoffs.
///
/// ── WHY NO HTML IN EITHER ───────────────────────────────────────────────────
/// The message's HTML part is not fetched, not inlined, and not handed over. A
/// receiving app, or the print renderer, would then be doing exactly what the
/// reader refuses to do: render a remote document, with its tracking requests
/// and its script, in a surface this app does not control. What goes out is the
/// header the server holds and the text part — the two things the screen shows.
/// This is also why the print path uses a text formatter and never a web view:
/// `UIPrintPageRenderer` with an HTML string is the iOS version of the same
/// mistake.

/// The document a message becomes outside this app: subject, the people it came
/// from and went to, its date, then the text part.
///
/// Every line is what the server answered with. A message with no text body says
/// that, rather than inventing a sentence for the gap, and a message whose only
/// part is HTML says it was printed as plain text — the same admission the
/// screen makes, one step further out.
func messageShareText(_ message: MailMessageFull) -> String {
    var lines: [String] = []
    lines.append(message.subject?.nilIfBlank() ?? "(no subject)")
    let from = message.from?.display.nilIfBlank() ?? (message.from?.address ?? "").nilIfBlank()
    lines.append("From: \(from ?? "Not recorded")")
    if !message.to.isEmpty { lines.append("To: \(message.to.joined(separator: ", "))") }
    if !message.cc.isEmpty { lines.append("Cc: \(message.cc.joined(separator: ", "))") }
    lines.append("Date: \(message.createdAt ?? "Not recorded")")
    lines.append("")
    if let text = message.bodyText?.nilIfBlank() {
        lines.append(text)
    } else if let html = message.bodyHtml?.nilIfBlank() {
        lines.append(HtmlStripper.strip(html))
        lines.append("")
        lines.append("(Printed as plain text: GrapMail does not render the sender's HTML.)")
    } else {
        lines.append("This message has no text body.")
    }
    return lines.joined(separator: "\n")
}

/// The `src` addresses an HTML part points at, http(s) only.
///
/// The part itself is never rendered, so this is not a sanitiser and does not
/// pretend to be one: it reads ONE attribute out of ONE tag, then applies a rule
/// that does not depend on the sender's markup being well-formed — a value is
/// kept when its scheme is http or https and it parses as an absolute URL with a
/// host, and everything else is dropped. That is the whole surface. `data:`
/// needs no consent because it asks nothing of the network; `cid:` is an
/// attachment's own address and belongs to the attachment rows; a `javascript:`
/// value reaches no request at all.
///
/// Duplicates collapse to their first occurrence, because a sender who repeats
/// one tracking pixel three times is asking for one request, not three.
func remoteImageUrls(_ html: String?) -> [String] {
    guard let html = html, !html.isEmpty,
          let pattern = try? NSRegularExpression(
            pattern: "(?is)<img[^>]*?\\bsrc\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    else { return [] }
    var found = [String]()
    for match in pattern.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
        guard let raw = imageSourceValue(match, in: html),
              let url = absoluteImageUrl(raw) else { continue }
        if !found.contains(url) { found.append(url) }
    }
    return found
}

/// Which of the three quoting shapes the regex actually matched, in order.
private func imageSourceValue(_ match: NSTextCheckingResult, in html: String) -> String? {
    for group in [2, 3, 4] {
        let range = match.range(at: group)
        if range.location != NSNotFound, let r = Range(range, in: html) {
            let value = String(html[r]).replacingOccurrences(of: "&amp;", with: "&")
            if !value.trimmingCharacters(in: .whitespaces).isEmpty { return value }
        }
    }
    return nil
}

/// The one rule that decides whether a sender's address is ever contacted: an
/// absolute http(s) URL with a host, or nothing.
private func absoluteImageUrl(_ raw: String) -> String? {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    let scheme = trimmed.components(separatedBy: ":").first?.lowercased() ?? ""
    guard scheme == "http" || scheme == "https" else { return nil }
    guard let url = URL(string: trimmed), url.isHTTP, url.host != nil else { return nil }
    return trimmed
}

extension URL {
    /// Whether this is one of the two schemes a sender's markup is ever allowed
    /// to reach. Written here rather than inline so the rule has a name.
    var isHTTP: Bool { scheme == "http" || scheme == "https" }
}

/// What a fetch can end as: bytes that decoded, or the reason they did not.
enum RemoteImageOutcome {
    case ready(UIImage)
    case problem(String)
}

/// The image loader's own session, deliberately not the API's.
///
/// A sender's server has no business holding a GrapMail session, so this
/// configuration carries no auth header, accepts no cookie, and caches nothing
/// that could outlive the message it was loaded for. `URLSession.shared` would
/// have attached the app's own cookie store and default headers to a request
/// whose entire purpose is to inform a third party.
private let imageSession: URLSession = {
    let config = URLSessionConfiguration.ephemeral
    config.httpShouldSetCookies = false
    config.httpCookieAcceptPolicy = .never
    config.httpAdditionalHeaders = nil
    config.urlCache = nil
    config.requestCachePolicy = .reloadIgnoringLocalCacheData
    config.timeoutIntervalForRequest = 10
    config.timeoutIntervalForResource = 20
    config.waitsForConnectivity = false
    return URLSession(configuration: config)
}()

/// Fetch one remote image, on the person's own opt-in.
func fetchRemoteImage(_ raw: String) async -> RemoteImageOutcome {
    guard let url = URL(string: raw), url.isHTTP else {
        return .problem("That address is not one this app can request.")
    }
    do {
        let (data, response) = try await imageSession.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status >= 400 {
            return .problem("The sender's server refused the image (\(status)).")
        }
        let mime = (response as? HTTPURLResponse)?.mimeType ?? ""
        if !mime.hasPrefix("image/") {
            // The answer that matters most: the address is not a picture. An
            // <img> tag does not license this app to hand a document to a
            // renderer, so the content type is checked, not trusted.
            return .problem("That address is not an image (\(mime.isEmpty ? "no type given" : mime)).")
        }
        guard let image = UIImage(data: data) else {
            return .problem("The image could not be read.")
        }
        return .ready(image)
    } catch {
        return .problem("The sender's server could not be reached.")
    }
}

/// One remote image, drawn after the message's own opt-in.
///
/// Three states are said, because two would a guess: nothing is requested until
/// this view appears (the caller only builds it after the person taps Show
/// images), a load that has not answered is loading, and a load that answered
/// badly names which of the real reasons it did — the host refused it, it was
/// not reachable, it was not an image, or the bytes would not decode. A blank
/// box reads as a broken app; a merged sentence reads as a lie.
struct RemoteImageView: View {
    let url: String
    let label: String

    private enum Phase {
        case loading
        case ready(UIImage)
        case problem(String)
    }

    @State private var phase: Phase = .loading

    var body: some View {
        Group {
            switch phase {
            case .loading:
                Text("Loading image…")
                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(GM.softFill))
            case .problem(let reason):
                Text(reason)
                    .font(.system(size: 12)).foregroundColor(GM.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .padding(.horizontal, 12)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(GM.softFill))
                    .accessibilityIdentifier("mail-remote-image-failed")
            case .ready(let image):
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 420)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityLabel(label)
            }
        }
        .task(id: url) {
            phase = .loading
            // The fetcher's outcome and the view's phase are different types on
            // purpose: what a request answered is not the same fact as what the
            // screen shows, and the mapping is where the reason gets its words.
            switch await fetchRemoteImage(url) {
            case .ready(let image): phase = .ready(image)
            case .problem(let reason): phase = .problem(reason)
            }
        }
    }
}

/// The system share sheet for one message, as the text this app built.
///
/// `ShareLink` wants a `Transferable`; the item here is a plain string the
/// reader already had to assemble honestly, and the sheet is the platform's
/// answer for handing it out. Mirrors Android's `ACTION_SEND` chooser, which
/// likewise receives text and nothing else.
struct MessageActivityView: UIViewControllerRepresentable {
    let text: String

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Print the message as the plain-text document the screen shows.
///
/// There is no web view in this path and there deliberately is not one: the
/// usual iOS recipe is `UIPrintInteractionController.printHTMLURL` or an
/// `UIPrintFormatter` over a web view, which is the exact thing the reader's
/// rule refuses — a remote document rendered with its own requests and its own
/// script. So the print formatter is the plain text the screen already shows.
/// The cost is a plainer page; the gain is that printing a message cannot make a
/// request the person did not ask for.
///
/// The completion is reported, not swallowed. `completed` means the person
/// pressed Print in the system's own panel; anything else — cancelled, or an
/// error — gets said, because a button that appeared to do nothing reads as a
/// broken app.
@MainActor
func printMessageText(_ message: MailMessageFull, onOutcome: @escaping (String?) -> Void) {
    let controller = UIPrintInteractionController.shared
    let info = UIPrintInfo(dictionary: nil)
    info.outputType = .grayscale
    info.orientation = .portrait
    info.jobName = String((message.subject?.nilIfBlank() ?? "GrapMail message").prefix(60))
    controller.printInfo = info
    controller.printFormatter = UISimpleTextPrintFormatter(
        text: messageShareText(message))

    guard let view = frontView() else {
        onOutcome("This device could not open its print screen.")
        return
    }
    controller.present(from: .zero, in: view, animated: true) { _, completed, error in
        if completed {
            // The document went to a printer. What the printer did with it is
            // not observable here, so nothing claims a page exists.
            onOutcome(nil)
        } else if let error = error {
            onOutcome("The print panel closed with a problem: \(error.localizedDescription)")
        } else {
            onOutcome("The print was cancelled before it was sent.")
        }
    }
}

/// The view the print panel anchors to.
///
/// On iPad the panel is a popover and needs a source view; asking the key
/// window's root view is the one answer that works on both idioms without the
/// caller threading a rect through the screen.
private func frontView() -> UIView? {
    for scene in UIApplication.shared.connectedScenes {
        guard let windowScene = scene as? UIWindowScene else { continue }
        if let window = windowScene.windows.first(where: { $0.isKeyWindow }) {
            return window.rootViewController?.view
        }
    }
    return nil
}
