import SwiftUI

// MARK: - Inbox Zero
//
// The primary Inbox with no messages after a load that answered -- never
// while loading, never over an error, never offline (MailboxView decides
// when; every other empty folder keeps MailStateBlock's own wording).
//
// The illustration is drawn in code in the onboarding artwork's vocabulary
// (Artwork.swift: the soft infoFill blobs, the primaryTop→primaryBottom
// gradient objects, the four-point sparkles, the round check badge). Those
// helpers are private to Artwork.swift, so the few shapes needed are drawn
// again here with the same proportions -- a calm tray with nothing in it,
// a low sun, and a check that draws itself once.
//
// Motion: one short entrance (≈1.2 s) and then the timeline pauses for good,
// the same "arrive, then hold" rule ArtScene follows. Under Reduce Motion
// the final frame is drawn at once. The confetti is the first Inbox Zero of
// the day only (per account), only when something was handled today (N > 0),
// never under Reduce Motion, and is part of the same entrance, so it is gone
// when the scene holds. An Inbox Zero that cannot celebrate (N == 0, Reduce
// Motion) does not use up the day's confetti.

struct InboxZeroView: View {
    /// Messages archived / trashed / reported / snoozed today on this device.
    let handledToday: Int
    /// Whose day it is (the once-a-day confetti is per account).
    var account: String? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var arrived = false
    @State private var confetti = false
    /// 260 pt wide at the default text size, growing a little with it.
    @ScaledMetric(relativeTo: .title2) private var artWidth: CGFloat = 260

    private var detail: String {
        switch handledToday {
        case 0: return String(localized: "Nothing waiting for you.", comment: "Inbox Zero detail when nothing was handled today")
        case 1: return String(localized: "You handled 1 email today.", comment: "Inbox Zero detail")
        default: return String(localized: "You handled \(handledToday) emails today.", comment: "Inbox Zero detail; number of emails archived, deleted or moved today")
        }
    }

    var body: some View {
        // As designed when it fits; scrolls at the accessibility sizes or in
        // a short window (landscape phone, Split View) instead of clipping.
        ViewThatFits(in: .vertical) {
            content
            ScrollView { content }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            if reduceMotion {
                arrived = true
                confetti = false
            } else {
                // `&&` short-circuits: nothing handled means the day's
                // confetti is not taken.
                confetti = handledToday > 0 && MailHandledToday.takeConfetti(account: account)
                withAnimation(GMMotion.emphasizedDecelerate(0.45)) { arrived = true }
            }
        }
    }

    private var content: some View {
        let width = min(artWidth, 340)
        return VStack(spacing: 12) {
            InboxZeroArt(confetti: confetti && !reduceMotion)
                .frame(width: width, height: width / 2)
                .accessibilityHidden(true)
            Text("All clear", comment: "Inbox Zero headline")
                .gmFont(22, .bold)
                .foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(detail)
                .gmFont(15)
                .foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .opacity(arrived ? 1 : 0)
        .scaleEffect(arrived ? 1 : 0.96)
        .offset(y: arrived ? 0 : 10)
        .frame(maxWidth: 380)
        .padding(.horizontal, 24)
        .padding(.top, 16)
        // Clear of the floating bottom controls.
        .padding(.bottom, 16 + MailBottomControls.clearance / 2)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("mail-inbox-zero")
    }
}

// MARK: - The illustration

private struct InboxZeroArt: View {
    let confetti: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start: Date?
    @State private var settled = false

    static let settle: Double = 1.4

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: settled || reduceMotion)) { timeline in
            Canvas { ctx, size in
                let elapsed: Double = {
                    if reduceMotion || settled { return Self.settle }
                    guard let start else { return 0 }
                    return min(Self.settle, max(0, timeline.date.timeIntervalSince(start)))
                }()
                InboxZeroDraw.scene(ctx, size, elapsed, confetti: confetti && !reduceMotion,
                                    pal: InboxZeroPalette.current)
            }
        }
        .onAppear {
            guard !reduceMotion else { settled = true; return }
            start = Date()
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.settle + 0.1) { settled = true }
        }
    }
}

private struct InboxZeroPalette {
    let top: Color
    let bottom: Color
    let solid: Color
    let card: Color
    let infoFill: Color
    let successInk: Color
    let star: Color
    let danger: Color

    static var current: InboxZeroPalette {
        InboxZeroPalette(top: GM.primaryTop, bottom: GM.primaryBottom, solid: GM.primarySolid,
                         card: GM.card, infoFill: GM.infoFill, successInk: GM.successInk,
                         star: GM.star, danger: GM.error)
    }
}

private enum InboxZeroDraw {
    static func ease(_ p: Double) -> Double { 1 - pow(1 - min(1, max(0, p)), 3) }

    static func grad(_ top: Color, _ bottom: Color, _ size: CGSize) -> GraphicsContext.Shading {
        .linearGradient(Gradient(colors: [top, bottom]),
                        startPoint: .zero, endPoint: CGPoint(x: size.width, y: size.height))
    }

    static func sparkle(_ c: CGPoint, _ r: CGFloat) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: c.x, y: c.y - r))
        p.addQuadCurve(to: CGPoint(x: c.x + r, y: c.y), control: c)
        p.addQuadCurve(to: CGPoint(x: c.x, y: c.y + r), control: c)
        p.addQuadCurve(to: CGPoint(x: c.x - r, y: c.y), control: c)
        p.addQuadCurve(to: CGPoint(x: c.x, y: c.y - r), control: c)
        return p
    }

    static func scene(_ ctx: GraphicsContext, _ size: CGSize, _ t: Double, confetti: Bool,
                      pal: InboxZeroPalette) {
        let w = size.width, h = size.height
        let arrive = ease(t / 0.9)          // tray and sun
        let checkIn = ease((t - 0.45) / 0.6) // the check draws after the tray lands
        let glints = ease((t - 0.7) / 0.6)

        // Soft background blobs, the onboarding scenes' field.
        let blobs: [(CGFloat, CGFloat, CGFloat)] = [(0.18, 0.32, 0.30), (0.80, 0.24, 0.24),
                                                    (0.86, 0.78, 0.28), (0.12, 0.80, 0.20)]
        for b in blobs {
            let r = b.2 * w * CGFloat(0.9 + 0.1 * arrive)
            ctx.fill(Path(ellipseIn: CGRect(x: b.0 * w - r, y: b.1 * h - r, width: r * 2, height: r * 2)),
                     with: .color(pal.infoFill.opacity(0.18)))
        }

        // A low, quiet sun behind the tray.
        let sunR = h * 0.17
        let sun = CGPoint(x: w * 0.70, y: h * (0.36 + 0.06 * CGFloat(1 - arrive)))
        ctx.fill(Path(ellipseIn: CGRect(x: sun.x - sunR * 1.5, y: sun.y - sunR * 1.5,
                                        width: sunR * 3, height: sunR * 3)),
                 with: .color(pal.star.opacity(0.12 * arrive)))
        ctx.fill(Path(ellipseIn: CGRect(x: sun.x - sunR, y: sun.y - sunR, width: sunR * 2, height: sunR * 2)),
                 with: .color(pal.star.opacity(0.45 * arrive)))

        // The empty tray, rising into place.
        let tw = w * 0.40, th = h * 0.34
        let cx = w * 0.46
        let cy = h * 0.60 + h * 0.08 * CGFloat(1 - arrive)
        var trayCtx = ctx
        trayCtx.opacity = arrive
        let body = CGRect(x: cx - tw / 2, y: cy - th / 2, width: tw, height: th)
        trayCtx.fill(Path(roundedRect: body, cornerRadius: tw * 0.12), with: grad(pal.top, pal.bottom, size))
        // The tray's lip: the inbox symbol's dip, in the card colour.
        var lip = Path()
        let lipY = cy - th * 0.06
        lip.move(to: CGPoint(x: body.minX + tw * 0.08, y: lipY))
        lip.addLine(to: CGPoint(x: cx - tw * 0.20, y: lipY))
        lip.addQuadCurve(to: CGPoint(x: cx - tw * 0.12, y: lipY + th * 0.18),
                         control: CGPoint(x: cx - tw * 0.18, y: lipY + th * 0.18))
        lip.addLine(to: CGPoint(x: cx + tw * 0.12, y: lipY + th * 0.18))
        lip.addQuadCurve(to: CGPoint(x: cx + tw * 0.20, y: lipY),
                         control: CGPoint(x: cx + tw * 0.18, y: lipY + th * 0.18))
        lip.addLine(to: CGPoint(x: body.maxX - tw * 0.08, y: lipY))
        trayCtx.stroke(lip, with: .color(pal.card.opacity(0.85)),
                       style: StrokeStyle(lineWidth: max(1.5, h * 0.022), lineCap: .round, lineJoin: .round))
        // A clean inner panel above the lip: nothing waiting in it.
        trayCtx.fill(Path(roundedRect: CGRect(x: body.minX + tw * 0.10, y: body.minY + th * 0.12,
                                              width: tw * 0.80, height: th * 0.22),
                          cornerRadius: th * 0.08),
                     with: .color(.white.opacity(0.16)))

        // The check badge, drawn once.
        let br = h * 0.12
        let bc = CGPoint(x: body.maxX - tw * 0.04, y: body.minY + th * 0.02)
        var badgeCtx = ctx
        badgeCtx.opacity = checkIn
        badgeCtx.fill(Path(ellipseIn: CGRect(x: bc.x - br, y: bc.y - br, width: br * 2, height: br * 2)),
                      with: .color(pal.successInk))
        badgeCtx.stroke(Path(ellipseIn: CGRect(x: bc.x - br * 0.78, y: bc.y - br * 0.78,
                                               width: br * 1.56, height: br * 1.56)),
                        with: .color(.white.opacity(0.9)), lineWidth: br * 0.08)
        var check = Path()
        check.move(to: CGPoint(x: bc.x - br * 0.42, y: bc.y + br * 0.02))
        check.addLine(to: CGPoint(x: bc.x - br * 0.10, y: bc.y + br * 0.32))
        check.addLine(to: CGPoint(x: bc.x + br * 0.44, y: bc.y - br * 0.30))
        badgeCtx.stroke(check.trimmedPath(from: 0, to: CGFloat(checkIn)), with: .color(.white),
                        style: StrokeStyle(lineWidth: br * 0.20, lineCap: .round, lineJoin: .round))

        // Three still sparkles.
        let glintPts: [(CGFloat, CGFloat, CGFloat)] = [(0.16, 0.22, 0.045), (0.88, 0.58, 0.035), (0.26, 0.84, 0.03)]
        for g in glintPts {
            ctx.fill(sparkle(CGPoint(x: g.0 * w, y: g.1 * h), g.2 * h * CGFloat(0.6 + 0.4 * glints)),
                     with: .color(pal.solid.opacity(0.55 * glints)))
        }

        // First Inbox Zero of the day: a light fall of confetti that is gone
        // by the time the scene holds.
        guard confetti else { return }
        let fall = t / InboxZeroArt.settle
        guard fall < 1 else { return }
        let colors = [pal.solid, pal.successInk, pal.star, pal.danger.opacity(0.7)]
        for i in 0..<14 {
            let seed = Double(i)
            let x = w * CGFloat(0.06 + (seed * 0.0714).truncatingRemainder(dividingBy: 0.9))
                + CGFloat(sin(fall * 6 + seed)) * w * 0.015
            let speed = 0.75 + (seed * 0.37).truncatingRemainder(dividingBy: 0.45)
            let y = -h * 0.08 + h * 1.1 * CGFloat(min(1, fall * speed))
            let s = h * 0.03
            var piece = ctx
            piece.opacity = max(0, 1 - fall * fall)
            piece.translateBy(x: x, y: y)
            piece.rotate(by: .radians(fall * 5 + seed))
            let rect = CGRect(x: -s / 2, y: -s / 3, width: s, height: s * 0.66)
            if i % 2 == 0 {
                piece.fill(Path(roundedRect: rect, cornerRadius: s * 0.2), with: .color(colors[i % colors.count]))
            } else {
                piece.fill(Path(ellipseIn: rect), with: .color(colors[i % colors.count]))
            }
        }
    }
}
