import SwiftUI

/// Code-drawn artwork, the iOS half of the same system as Android's
/// `ui/artwork/Artwork.kt`.
///
/// The account and security flows shipped ~42 `ill_*.imageset` PNG folders
/// cropped out of reference designs — including a landscape photograph
/// behind the puzzle slider. The project's own convention is vector shapes
/// drawn in code with programmatic motion; this file is that convention.
///
/// Scene names match Android's dispatch string for string, and the drawing
/// coordinates are fractions of the canvas, so one `Art("welcome")` is
/// correct at 150pt and 460pt and sharp on every display density.
enum Art {
    /// A scene at a fixed width, 2:1 like the artwork it replaces.
    /// Unknown names fall back to the welcome scene, the same default
    /// `illRes` had.
    static func view(_ name: String, width: CGFloat) -> some View {
        ArtScene(name: name)
            .frame(width: width, height: width / 2)
            .accessibilityHidden(true)
    }

    /// A scene that takes whatever width the layout offers and keeps the
    /// 2:1 field — the shape the old `Image(…).resizable()
    /// .aspectRatio(.fit)` call sites had, without the raster.
    static func fill(_ name: String) -> some View {
        ArtScene(name: name)
            .aspectRatio(2, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .accessibilityHidden(true)
    }

    /// A scene at an exact size, for the one place that is not a 2:1 field:
    /// the puzzle slider's strip, which is wider than tall and clips.
    static func sized(_ name: String, width: CGFloat, height: CGFloat) -> some View {
        ArtScene(name: name)
            .frame(width: width, height: height)
            .clipped()
            .accessibilityHidden(true)
    }
}

private struct ArtScene: View {
    let name: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: reduceMotion ? 3600 : 1.0 / 30.0,
                                paused: false)) { timeline in
            Canvas { ctx, size in
                let t: Double = reduceMotion ? 0.0
                    : timeline.date.timeIntervalSinceReferenceDate
                        .truncatingRemainder(dividingBy: 1000)
                ArtDraw.scene(name, ctx, size, t, palette: ArtPalette.current)
            }
        }
    }
}

/// Brand colors resolved for the current trait — the artwork follows the
/// palette, so dark mode is the same code, not a second `_dark` PNG.
private struct ArtPalette {
    let top: Color
    let bottom: Color
    let solid: Color
    let card: Color
    let infoFill: Color
    let softFill: Color
    let textTertiary: Color
    let successInk: Color
    let warnInk: Color
    let star: Color
    let danger: Color

    static var current: ArtPalette {
        ArtPalette(top: GM.primaryTop, bottom: GM.primaryBottom, solid: GM.primarySolid,
                   card: GM.card, infoFill: GM.infoFill, softFill: GM.softFill,
                   textTertiary: GM.textTertiary, successInk: GM.successInk,
                   warnInk: GM.warnInk, star: GM.star, danger: GM.error)
    }
}

// MARK: - drawing helpers

private enum ArtDraw {
    static func pulse(_ t: Double, _ period: Double = 2.4, _ phase: Double = 0) -> Double {
        0.5 + 0.5 * sin(2 * .pi * ((t + phase) / period))
    }
    static func floatY(_ t: Double, _ amp: Double, _ period: Double = 3.2, _ phase: Double = 0) -> Double {
        amp * sin(2 * .pi * ((t + phase) / period))
    }
    static func grad(_ top: Color, _ bottom: Color, _ size: CGSize) -> GraphicsContext.Shading {
        .linearGradient(Gradient(colors: [top, bottom]),
                        startPoint: .zero, endPoint: CGPoint(x: size.width, y: size.height))
    }

    // ── background vocabulary ────────────────────────────────────────────

    static func blobs(_ ctx: GraphicsContext, _ size: CGSize, _ base: Color, _ t: Double) {
        let list: [(CGFloat, CGFloat, CGFloat)] = [(0.18, 0.30, 0.34), (0.78, 0.22, 0.26),
                                                   (0.86, 0.74, 0.30), (0.12, 0.80, 0.22)]
        for (i, b) in list.enumerated() {
            let drift = floatY(t, 0.012 * Double(size.height), 5 + Double(i), Double(i) * 0.7)
            let r = b.2 * size.width
            let rect = CGRect(x: b.0 * size.width - r, y: b.1 * size.height - r + CGFloat(drift),
                              width: r * 2, height: r * 2)
            ctx.fill(Path(ellipseIn: rect),
                     with: .color(base.opacity(0.16 + 0.05 * pulse(t, 5, Double(i) * 0.9))))
        }
    }

    static func sparklePath(_ c: CGPoint, _ r: CGFloat) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: c.x, y: c.y - r))
        p.addQuadCurve(to: CGPoint(x: c.x + r, y: c.y), control: c)
        p.addQuadCurve(to: CGPoint(x: c.x, y: c.y + r), control: c)
        p.addQuadCurve(to: CGPoint(x: c.x - r, y: c.y), control: c)
        p.addQuadCurve(to: CGPoint(x: c.x, y: c.y - r), control: c)
        return p
    }

    static func sparkles(_ ctx: GraphicsContext, _ size: CGSize, _ color: Color, _ t: Double, _ seed: Double = 0) {
        let pts: [(CGFloat, CGFloat)] = [(0.10, 0.18), (0.90, 0.30), (0.16, 0.82), (0.82, 0.86), (0.55, 0.10)]
        for (i, pt) in pts.enumerated() {
            let a = 0.35 + 0.65 * pulse(t, 2.2, seed + Double(i) * 0.45)
            let r = (0.03 + 0.012 * Double(i)) * size.height
            ctx.fill(sparklePath(CGPoint(x: pt.0 * size.width, y: pt.1 * size.height), r),
                     with: .color(color.opacity(a)))
        }
    }

    static func confetti(_ ctx: GraphicsContext, _ size: CGSize, _ colors: [Color], _ t: Double) {
        let bits: [(CGFloat, CGFloat, Int)] = [(0.12, 0.22, 0), (0.88, 0.18, 1), (0.20, 0.78, 2),
                                               (0.80, 0.80, 3), (0.35, 0.12, 0), (0.68, 0.90, 1)]
        for (i, b) in bits.enumerated() {
            let drift = floatY(t, 0.02 * Double(size.height), 3.4, Double(i) * 0.5)
            let s = 0.022 * size.height
            let c = colors[b.2 % colors.count]
            let pt = CGPoint(x: b.0 * size.width, y: b.1 * size.height + CGFloat(drift))
            if i % 2 == 0 {
                ctx.fill(Path(roundedRect: CGRect(x: pt.x - s / 2, y: pt.y - s / 2, width: s, height: s),
                              cornerRadius: s / 3), with: .color(c))
            } else {
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - s / 2, y: pt.y - s / 2, width: s, height: s)),
                         with: .color(c))
            }
        }
    }

    static func concentric(_ ctx: GraphicsContext, _ c: CGPoint, _ maxR: CGFloat, _ color: Color, _ t: Double) {
        for i in 0..<3 {
            let ph = pulse(t, 2.8, Double(i) * 0.9)
            let r = maxR * (0.45 + 0.55 * CGFloat(ph))
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                       with: .color(color.opacity(0.22 * (1 - ph))), lineWidth: maxR * 0.03)
        }
    }

    // ── shared glyphs ────────────────────────────────────────────────────

    static func check(_ ctx: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ color: Color, _ w: CGFloat) {
        var p = Path()
        p.move(to: CGPoint(x: c.x - r, y: c.y))
        p.addLine(to: CGPoint(x: c.x - r * 0.25, y: c.y + r * 0.7))
        p.addLine(to: CGPoint(x: c.x + r, y: c.y - r * 0.75))
        ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: w, lineCap: .round))
    }

    static func key(_ ctx: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ color: Color, _ w: CGFloat) {
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r * 2.4, y: c.y - r, width: r * 2, height: r * 2)),
                   with: .color(color), lineWidth: w * 0.8)
        ctx.stroke(Path { $0.move(to: CGPoint(x: c.x - r * 0.4, y: c.y))
                          $0.addLine(to: CGPoint(x: c.x + r * 1.6, y: c.y)) },
                   with: .color(color), lineWidth: w)
        ctx.stroke(Path { $0.move(to: CGPoint(x: c.x + r * 1.1, y: c.y))
                          $0.addLine(to: CGPoint(x: c.x + r * 1.1, y: c.y + r * 0.7)) },
                   with: .color(color), lineWidth: w)
        ctx.stroke(Path { $0.move(to: CGPoint(x: c.x + r * 1.6, y: c.y))
                          $0.addLine(to: CGPoint(x: c.x + r * 1.6, y: c.y + r * 0.9)) },
                   with: .color(color), lineWidth: w)
    }

    static func clock(_ ctx: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ color: Color, _ w: CGFloat, _ t: Double) {
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                   with: .color(color), lineWidth: w)
        let a = (t * 0.6).truncatingRemainder(dividingBy: 2 * .pi)
        ctx.stroke(Path { $0.move(to: c)
                          $0.addLine(to: CGPoint(x: c.x + r * 0.55 * CGFloat(sin(a)),
                                                 y: c.y - r * 0.55 * CGFloat(cos(a)))) },
                   with: .color(color), lineWidth: w * 0.8)
        ctx.stroke(Path { $0.move(to: c); $0.addLine(to: CGPoint(x: c.x, y: c.y - r * 0.75)) },
                   with: .color(color), lineWidth: w * 0.8)
    }

    static func bell(_ ctx: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ color: Color) {
        var p = Path()
        p.move(to: CGPoint(x: c.x - r, y: c.y + r * 0.5))
        p.addQuadCurve(to: CGPoint(x: c.x, y: c.y - r * 1.2),
                       control: CGPoint(x: c.x - r, y: c.y - r * 1.2))
        p.addQuadCurve(to: CGPoint(x: c.x + r, y: c.y + r * 0.5),
                       control: CGPoint(x: c.x + r, y: c.y - r * 1.2))
        p.closeSubpath()
        ctx.fill(p, with: .color(color))
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r * 0.22, y: c.y + r * 0.63,
                                        width: r * 0.44, height: r * 0.44)), with: .color(color))
    }

    static func globe(_ ctx: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ color: Color, _ w: CGFloat) {
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                   with: .color(color), lineWidth: w)
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r * 0.45, y: c.y - r, width: r * 0.9, height: r * 2)),
                   with: .color(color), lineWidth: w * 0.7)
        ctx.stroke(Path { $0.move(to: CGPoint(x: c.x - r, y: c.y))
                          $0.addLine(to: CGPoint(x: c.x + r, y: c.y)) },
                   with: .color(color), lineWidth: w * 0.7)
    }

    static func listGlyph(_ ctx: GraphicsContext, _ c: CGPoint, _ w: CGFloat, _ h: CGFloat,
                          _ color: Color, _ stroke: CGFloat) {
        for i in 0..<3 {
            let y = c.y - h / 2 + h * CGFloat(0.15 + Double(i) * 0.35)
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - w / 2 - stroke, y: y - stroke,
                                            width: stroke * 2, height: stroke * 2)), with: .color(color))
            ctx.fill(Path(roundedRect: CGRect(x: c.x - w * 0.28, y: y - stroke * 0.5,
                                              width: w * 0.78, height: stroke),
                          cornerRadius: stroke * 0.5), with: .color(color))
        }
    }

    static func trash(_ ctx: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ color: Color, _ w: CGFloat) {
        ctx.stroke(Path { $0.move(to: CGPoint(x: c.x - r, y: c.y - r * 0.7))
                          $0.addLine(to: CGPoint(x: c.x + r, y: c.y - r * 0.7)) },
                   with: .color(color), lineWidth: w)
        var can = Path()
        can.move(to: CGPoint(x: c.x - r * 0.7, y: c.y - r * 0.4))
        can.addLine(to: CGPoint(x: c.x - r * 0.55, y: c.y + r))
        can.addLine(to: CGPoint(x: c.x + r * 0.55, y: c.y + r))
        can.addLine(to: CGPoint(x: c.x + r * 0.7, y: c.y - r * 0.4))
        can.closeSubpath()
        ctx.stroke(can, with: .color(color), lineWidth: w)
    }

    static func logout(_ ctx: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ color: Color,
                       _ w: CGFloat, _ t: Double) {
        let slide = floatY(t, 0.08 * Double(r), 2.6, 0.2)
        var door = Path()
        door.move(to: CGPoint(x: c.x - r * 0.2, y: c.y - r))
        door.addLine(to: CGPoint(x: c.x - r, y: c.y - r))
        door.addLine(to: CGPoint(x: c.x - r, y: c.y + r))
        door.addLine(to: CGPoint(x: c.x - r * 0.2, y: c.y + r))
        ctx.stroke(door, with: .color(color), style: StrokeStyle(lineWidth: w, lineCap: .round))
        let sy = c.y + CGFloat(slide)
        ctx.stroke(Path { $0.move(to: CGPoint(x: c.x, y: sy))
                          $0.addLine(to: CGPoint(x: c.x + r, y: sy)) },
                   with: .color(color), lineWidth: w)
        var head = Path()
        head.move(to: CGPoint(x: c.x + r * 0.55, y: sy - r * 0.35))
        head.addLine(to: CGPoint(x: c.x + r, y: sy))
        head.addLine(to: CGPoint(x: c.x + r * 0.55, y: sy + r * 0.35))
        ctx.stroke(head, with: .color(color), style: StrokeStyle(lineWidth: w, lineCap: .round))
    }

    static func activity(_ ctx: GraphicsContext, _ c: CGPoint, _ w: CGFloat, _ color: Color,
                         _ stroke: CGFloat, _ t: Double) {
        var p = Path()
        p.move(to: CGPoint(x: c.x - w / 2, y: c.y))
        p.addLine(to: CGPoint(x: c.x - w * 0.25, y: c.y))
        p.addLine(to: CGPoint(x: c.x - w * 0.1, y: c.y - w * 0.18))
        p.addLine(to: CGPoint(x: c.x + w * 0.05, y: c.y + w * 0.18))
        p.addLine(to: CGPoint(x: c.x + w * 0.18, y: c.y))
        p.addLine(to: CGPoint(x: c.x + w / 2, y: c.y))
        ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
        let sweep = (t * 0.5).truncatingRemainder(dividingBy: 1)
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - w / 2 + w * CGFloat(sweep) - stroke * 1.1,
                                        y: c.y - stroke * 1.1, width: stroke * 2.2, height: stroke * 2.2)),
                 with: .color(color.opacity(0.8)))
    }

    static func alert(_ ctx: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ color: Color,
                      _ w: CGFloat, _ t: Double) {
        let p = 1 + 0.05 * pulse(t, 1.6)
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r * 1.3 * CGFloat(p), y: c.y - r * 1.3 * CGFloat(p),
                                        width: r * 2.6 * CGFloat(p), height: r * 2.6 * CGFloat(p))),
                 with: .color(color.opacity(0.25)))
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r * CGFloat(p), y: c.y - r * CGFloat(p),
                                          width: r * 2 * CGFloat(p), height: r * 2 * CGFloat(p))),
                   with: .color(color), lineWidth: w)
        ctx.fill(Path(roundedRect: CGRect(x: c.x - w * 0.6, y: c.y - r * 0.5,
                                          width: w * 1.2, height: r * 1.3),
                      cornerRadius: w * 0.6), with: .color(color))
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - w * 0.8, y: c.y + r * 0.5 - w * 0.8,
                                        width: w * 1.6, height: w * 1.6)), with: .color(color))
    }

    // ── hero objects ─────────────────────────────────────────────────────

    static func envelope(_ ctx: GraphicsContext, _ c: CGPoint, _ w: CGFloat, _ h: CGFloat,
                         _ pal: ArtPalette, _ t: Double, letter: Bool, showCheck: Bool, size: CGSize) {
        let lift = floatY(t, 0.03 * Double(h), 3.6)
        let body = CGRect(x: c.x - w / 2, y: c.y - h / 2, width: w, height: h)
        if letter {
            let lw = w * 0.62, lh = h * 0.66
            let ly = c.y - h / 2 - lh * 0.55 + CGFloat(lift)
            ctx.fill(Path(roundedRect: CGRect(x: c.x - lw / 2, y: ly, width: lw, height: lh),
                          cornerRadius: lw * 0.08), with: .color(pal.card))
            for i in 0..<2 {
                ctx.fill(Path(roundedRect: CGRect(x: c.x - lw * 0.36, y: ly + lh * 0.25 + CGFloat(i) * lh * 0.22,
                                                  width: lw * (0.72 - CGFloat(i) * 0.18), height: lh * 0.09),
                              cornerRadius: lh * 0.045), with: .color(pal.textTertiary.opacity(0.35)))
            }
        }
        ctx.fill(Path(roundedRect: body, cornerRadius: w * 0.10),
                 with: grad(pal.top, pal.bottom, size))
        var flap = Path()
        flap.move(to: CGPoint(x: c.x - w / 2, y: c.y - h / 2))
        flap.addLine(to: CGPoint(x: c.x, y: c.y + h * 0.12))
        flap.addLine(to: CGPoint(x: c.x + w / 2, y: c.y - h / 2))
        flap.closeSubpath()
        ctx.fill(flap, with: .color(.white.opacity(0.18)))
        ctx.stroke(flap, with: .color(pal.bottom), lineWidth: w * 0.012)
        if showCheck {
            let bx = c.x + w * 0.42, by = c.y + h * 0.40
            let br = w * 0.14 * CGFloat(1 + 0.06 * pulse(t, 2.2, 0.3))
            ctx.fill(Path(ellipseIn: CGRect(x: bx - br, y: by - br, width: br * 2, height: br * 2)),
                     with: .color(pal.solid))
            ctx.stroke(Path(ellipseIn: CGRect(x: bx - br * 0.78, y: by - br * 0.78,
                                              width: br * 1.56, height: br * 1.56)),
                       with: .color(.white), lineWidth: br * 0.06)
            check(ctx, CGPoint(x: bx, y: by), br * 0.5, pal.solid, br * 0.22)
        }
    }

    static func shield(_ ctx: GraphicsContext, _ c: CGPoint, _ w: CGFloat, _ h: CGFloat,
                       _ pal: ArtPalette, _ glyph: String, _ t: Double, size: CGSize) {
        let bob = floatY(t, 0.02 * Double(h), 3.8)
        let cy = c.y + CGFloat(bob)
        var p = Path()
        p.move(to: CGPoint(x: c.x, y: cy - h / 2))
        p.addCurve(to: CGPoint(x: c.x + w * 0.42, y: cy + h * 0.2),
                   control1: CGPoint(x: c.x + w * 0.48, y: cy - h * 0.38),
                   control2: CGPoint(x: c.x + w * 0.5, y: cy - h * 0.1))
        p.addCurve(to: CGPoint(x: c.x, y: cy + h * 0.54),
                   control1: CGPoint(x: c.x + w * 0.32, y: cy + h * 0.42),
                   control2: CGPoint(x: c.x + w * 0.1, y: cy + h * 0.5))
        p.addCurve(to: CGPoint(x: c.x - w * 0.42, y: cy + h * 0.2),
                   control1: CGPoint(x: c.x - w * 0.1, y: cy + h * 0.5),
                   control2: CGPoint(x: c.x - w * 0.32, y: cy + h * 0.42))
        p.addCurve(to: CGPoint(x: c.x, y: cy - h / 2),
                   control1: CGPoint(x: c.x - w * 0.5, y: cy - h * 0.1),
                   control2: CGPoint(x: c.x - w * 0.48, y: cy - h * 0.38))
        p.closeSubpath()
        ctx.fill(p, with: grad(pal.top, pal.bottom, size))
        ctx.stroke(p, with: .color(.white.opacity(0.16)), lineWidth: w * 0.03)
        switch glyph {
        case "lock":
            let lw = w * 0.34, lh = h * 0.26
            ctx.fill(Path(roundedRect: CGRect(x: c.x - lw / 2, y: cy - lh * 0.1, width: lw, height: lh),
                          cornerRadius: lw * 0.18), with: .color(.white))
            ctx.stroke(Path(roundedRect: CGRect(x: c.x - lw * 0.32, y: cy - lh * 0.75,
                                                width: lw * 0.64, height: lh),
                            cornerRadius: lw * 0.5), with: .color(.white), lineWidth: lw * 0.16)
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - lw * 0.09, y: cy + lh * 0.06,
                                            width: lw * 0.18, height: lw * 0.18)),
                     with: .color(pal.bottom))
        case "check": check(ctx, CGPoint(x: c.x, y: cy), w * 0.16, .white, w * 0.07)
        case "person":
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - w * 0.11, y: cy - h * 0.08 - w * 0.11,
                                            width: w * 0.22, height: w * 0.22)), with: .color(.white))
            ctx.fill(Path(roundedRect: CGRect(x: c.x - w * 0.2, y: cy + h * 0.04,
                                              width: w * 0.4, height: h * 0.26),
                          cornerRadius: w * 0.18), with: .color(.white))
        case "alert":
            ctx.fill(Path(roundedRect: CGRect(x: c.x - w * 0.035, y: cy - h * 0.16,
                                              width: w * 0.07, height: h * 0.22),
                          cornerRadius: w * 0.035), with: .color(.white))
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - w * 0.045, y: cy + h * 0.095,
                                            width: w * 0.09, height: w * 0.09)), with: .color(.white))
        default: break
        }
    }

    static func paperPlane(_ ctx: GraphicsContext, _ c: CGPoint, _ s: CGFloat,
                           _ pal: ArtPalette, _ t: Double, size: CGSize) {
        let glide = floatY(t, 0.04 * Double(s), 4.2, 0.6)
        let cy = c.y + CGFloat(glide)
        var p = Path()
        p.move(to: CGPoint(x: c.x - s, y: cy))
        p.addLine(to: CGPoint(x: c.x + s, y: cy - s * 0.55))
        p.addLine(to: CGPoint(x: c.x - s * 0.15, y: cy + s * 0.75))
        p.closeSubpath()
        ctx.fill(p, with: grad(pal.top, pal.bottom, size))
        var fold = Path()
        fold.move(to: CGPoint(x: c.x - s, y: cy))
        fold.addLine(to: CGPoint(x: c.x + s * 0.1, y: cy - s * 0.1))
        ctx.stroke(fold, with: .color(pal.bottom), lineWidth: s * 0.06)
        var trail = Path()
        trail.move(to: CGPoint(x: c.x - s * 1.3, y: cy * 0.4 + c.y * 0.6 + s * 0.9))
        trail.addQuadCurve(to: CGPoint(x: c.x + s * 0.9, y: cy + s * 0.6),
                           control: CGPoint(x: c.x - s * 0.2, y: cy + s * 1.2))
        ctx.stroke(trail, with: .color(pal.solid.opacity(0.5)),
                   style: StrokeStyle(lineWidth: s * 0.05, dash: [s * 0.18, s * 0.22]))
    }

    static func devices(_ ctx: GraphicsContext, _ c: CGPoint, _ w: CGFloat, _ h: CGFloat,
                        _ pal: ArtPalette, _ t: Double, checkOn: Bool, size: CGSize) {
        let bob = floatY(t, 0.015 * Double(h), 4)
        let cy = c.y + CGFloat(bob)
        let lw = w * 0.62, lh = h * 0.52
        ctx.fill(Path(roundedRect: CGRect(x: c.x - lw / 2, y: cy - lh / 2, width: lw, height: lh),
                      cornerRadius: lw * 0.05), with: .color(pal.card))
        ctx.fill(Path(roundedRect: CGRect(x: c.x - lw * 0.46, y: cy - lh * 0.44,
                                          width: lw * 0.92, height: lh * 0.88),
                      cornerRadius: lw * 0.03), with: grad(pal.top, pal.bottom, size))
        ctx.fill(Path(roundedRect: CGRect(x: c.x - lw * 0.62, y: cy + lh / 2,
                                          width: lw * 1.24, height: lh * 0.10),
                      cornerRadius: lh * 0.05), with: .color(pal.card))
        let pw = w * 0.2, ph = h * 0.44
        let px = c.x + lw * 0.62, py = cy + h * 0.1 - CGFloat(bob)
        ctx.fill(Path(roundedRect: CGRect(x: px - pw / 2, y: py - ph / 2, width: pw, height: ph),
                      cornerRadius: pw * 0.18), with: .color(pal.card))
        ctx.fill(Path(roundedRect: CGRect(x: px - pw * 0.4, y: py - ph * 0.42,
                                          width: pw * 0.8, height: ph * 0.84),
                      cornerRadius: pw * 0.1), with: grad(pal.top, pal.bottom, size))
        if checkOn {
            check(ctx, CGPoint(x: c.x, y: cy), lw * 0.13, .white, lw * 0.05)
            check(ctx, CGPoint(x: px, y: py), pw * 0.16, .white, pw * 0.07)
        }
        let sw = w * 0.16
        let sc = CGPoint(x: c.x - lw * 0.85, y: cy + h * 0.18)
        ctx.fill(Path(ellipseIn: CGRect(x: sc.x - sw, y: sc.y - sw, width: sw * 2, height: sw * 2)),
                 with: .color(pal.card))
        ctx.fill(Path(roundedRect: CGRect(x: sc.x - sw * 0.4, y: sc.y - sw * 0.55,
                                          width: sw * 0.8, height: sw * 1.1),
                      cornerRadius: sw * 0.16), with: grad(pal.top, pal.bottom, size))
    }

    static func person(_ ctx: GraphicsContext, _ c: CGPoint, _ s: CGFloat,
                       _ pal: ArtPalette, _ t: Double) {
        let bob = floatY(t, 0.012 * Double(s), 4.4)
        let cy = c.y + CGFloat(bob)
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - s * 0.3, y: cy - s * 0.48,
                                        width: s * 0.6, height: s * 0.6)),
                 with: .color(Color(red: 0.91, green: 0.69, blue: 0.55)))
        ctx.fill(Path(roundedRect: CGRect(x: c.x - s * 0.32, y: cy - s * 0.52,
                                          width: s * 0.64, height: s * 0.4),
                      cornerRadius: s * 0.3),
                 with: .color(Color(red: 0.14, green: 0.23, blue: 0.37)))
        ctx.fill(Path(roundedRect: CGRect(x: c.x - s * 0.46, y: cy + s * 0.16,
                                          width: s * 0.92, height: s * 0.74),
                      cornerRadius: s * 0.42), with: .color(pal.solid))
    }

    static func card(_ ctx: GraphicsContext, _ c: CGPoint, _ w: CGFloat, _ h: CGFloat,
                     _ pal: ArtPalette, _ t: Double) {
        let bob = floatY(t, 0.02 * Double(h), 3.9, 1.1)
        let cy = c.y + CGFloat(bob)
        ctx.fill(Path(roundedRect: CGRect(x: c.x - w / 2, y: cy - h / 2, width: w, height: h),
                      cornerRadius: w * 0.10), with: .color(pal.card))
        for i in 0..<2 {
            ctx.fill(Path(roundedRect: CGRect(x: c.x - w * 0.34, y: cy - h * 0.22 + CGFloat(i) * h * 0.3,
                                              width: w * (0.68 - CGFloat(i) * 0.2), height: h * 0.12),
                          cornerRadius: h * 0.06), with: .color(pal.textTertiary))
        }
    }

    static func chip(_ ctx: GraphicsContext, _ c: CGPoint, _ w: CGFloat, _ h: CGFloat,
                     _ pal: ArtPalette, _ ink: Color, _ glyph: String, _ t: Double, size: CGSize) {
        let bob = floatY(t, 0.015 * Double(h), 3.3, 0.4)
        let cy = c.y + CGFloat(bob)
        ctx.fill(Path(roundedRect: CGRect(x: c.x - w / 2, y: cy - h / 2, width: w, height: h),
                      cornerRadius: h * 0.5), with: .color(pal.card))
        let gx = c.x - w * 0.3
        switch glyph {
        case "check":
            ctx.fill(Path(ellipseIn: CGRect(x: gx - h * 0.28, y: cy - h * 0.28,
                                            width: h * 0.56, height: h * 0.56)), with: .color(ink))
            check(ctx, CGPoint(x: gx, y: cy), h * 0.16, pal.card, h * 0.09)
        case "key": key(ctx, CGPoint(x: gx, y: cy), h * 0.3, ink, h * 0.08)
        default:
            ctx.fill(Path(ellipseIn: CGRect(x: gx - h * 0.22, y: cy - h * 0.22,
                                            width: h * 0.44, height: h * 0.44)), with: .color(ink))
        }
        ctx.fill(Path(roundedRect: CGRect(x: c.x - w * 0.08, y: cy - h * 0.06,
                                          width: w * 0.4, height: h * 0.12),
                      cornerRadius: h * 0.06), with: .color(ink.opacity(0.55)))
    }

    static func puzzleScene(_ ctx: GraphicsContext, _ size: CGSize, _ pal: ArtPalette, _ t: Double) {
        let w = size.width, h = size.height
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(pal.infoFill))
        var far = Path()
        far.move(to: CGPoint(x: 0, y: h * 0.62))
        let peaks: [(CGFloat, CGFloat)] = [(0.12, 0.34), (0.24, 0.55), (0.38, 0.22), (0.52, 0.58),
                                           (0.66, 0.30), (0.80, 0.56), (0.92, 0.38), (1.0, 0.60)]
        for p in peaks { far.addLine(to: CGPoint(x: w * p.0, y: h * p.1)) }
        far.addLine(to: CGPoint(x: w, y: h * 0.66))
        far.addLine(to: CGPoint(x: 0, y: h * 0.66))
        far.closeSubpath()
        ctx.fill(far, with: .color(pal.solid.opacity(0.55)))
        var snow = Path()
        snow.move(to: CGPoint(x: w * 0.38, y: h * 0.22))
        snow.addLine(to: CGPoint(x: w * 0.42, y: h * 0.32))
        snow.addLine(to: CGPoint(x: w * 0.38, y: h * 0.30))
        snow.addLine(to: CGPoint(x: w * 0.34, y: h * 0.32))
        snow.closeSubpath()
        ctx.fill(snow, with: .color(.white.opacity(0.85)))
        ctx.fill(Path(CGRect(x: 0, y: h * 0.66, width: w, height: h * 0.34)),
                 with: .color(pal.softFill))
        let ripple = floatY(t, 0.004 * Double(h), 2.8)
        for i in 0..<3 {
            let y = h * (0.74 + CGFloat(i) * 0.08) + CGFloat(ripple)
            ctx.stroke(Path { $0.move(to: CGPoint(x: w * 0.1, y: y))
                              $0.addLine(to: CGPoint(x: w * 0.9, y: y)) },
                       with: .color(pal.bottom.opacity(0.25)), lineWidth: h * 0.008)
        }
    }

    // ── scenes: the same name map as Android's Artwork.kt ────────────────

    static func scene(_ name: String, _ ctx: GraphicsContext, _ size: CGSize, _ t: Double,
                      pal: ArtPalette) {
        let w = size.width, h = size.height
        switch name {
        case "welcome", "hero":
            blobs(ctx, size, pal.infoFill, t)
            sparkles(ctx, size, pal.solid, t)
            envelope(ctx, CGPoint(x: w * 0.46, y: h * 0.55), w * 0.42, h * 0.52, pal, t,
                     letter: true, showCheck: false, size: size)
            shield(ctx, CGPoint(x: w * 0.16, y: h * 0.30), w * 0.16, h * 0.34, pal, "lock", t, size: size)
            paperPlane(ctx, CGPoint(x: w * 0.84, y: h * 0.22), w * 0.06, pal, t, size: size)
        case "step1_name":
            blobs(ctx, size, pal.infoFill, t)
            sparkles(ctx, size, pal.solid, t)
            person(ctx, CGPoint(x: w * 0.42, y: h * 0.52), h * 0.42, pal, t)
            chip(ctx, CGPoint(x: w * 0.74, y: h * 0.30), w * 0.16, h * 0.16, pal, pal.solid, "check", t, size: size)
            card(ctx, CGPoint(x: w * 0.72, y: h * 0.62), w * 0.26, h * 0.20, pal, t)
        case "verify":
            blobs(ctx, size, pal.infoFill, t)
            sparkles(ctx, size, pal.solid, t)
            shield(ctx, CGPoint(x: w * 0.36, y: h * 0.50), w * 0.30, h * 0.62, pal, "person", t, size: size)
            // The badge carries a glyph, not baked English words: the PNG put
            // "Human Verification" into pixels, which no screen reader reads
            // and no translation changes. The screen's own heading owns the text.
            chip(ctx, CGPoint(x: w * 0.72, y: h * 0.58), w * 0.24, h * 0.22, pal, pal.solid, "check", t, size: size)
        case "created_a", "account-forgot", "account-reset-ok":
            blobs(ctx, size, pal.infoFill, t)
            confetti(ctx, size, [pal.solid, pal.successInk, pal.star, pal.danger.opacity(0.7)], t)
            envelope(ctx, CGPoint(x: w * 0.48, y: h * 0.50), w * 0.40, h * 0.50, pal, t,
                     letter: false, showCheck: true, size: size)
        case "created_b":
            blobs(ctx, size, pal.infoFill, t)
            sparkles(ctx, size, pal.solid, t)
            envelope(ctx, CGPoint(x: w * 0.48, y: h * 0.50), w * 0.40, h * 0.50, pal, t,
                     letter: false, showCheck: true, size: size)
        case "puzzle":
            puzzleScene(ctx, size, pal, t)
        case "account-2fa", "ill_sec_hub_secure", "ill_sec_secure":
            blobs(ctx, size, pal.infoFill, t)
            concentric(ctx, CGPoint(x: w * 0.42, y: h * 0.48), h * 0.55, pal.solid, t)
            shield(ctx, CGPoint(x: w * 0.42, y: h * 0.48), w * 0.34, h * 0.68, pal, "lock", t, size: size)
            shield(ctx, CGPoint(x: w * 0.70, y: h * 0.62), w * 0.12, h * 0.24, pal, "check", t, size: size)
        case "ill_sec_hub":
            blobs(ctx, size, pal.infoFill, t)
            shield(ctx, CGPoint(x: w * 0.42, y: h * 0.48), w * 0.34, h * 0.68, pal, "lock", t, size: size)
        case "account-backup", "ill_sec_backup_generate", "ill_sec_backup_saved":
            blobs(ctx, size, pal.infoFill, t)
            sparkles(ctx, size, pal.solid, t)
            card(ctx, CGPoint(x: w * 0.42, y: h * 0.48), w * 0.30, h * 0.56, pal, t)
            if name == "ill_sec_backup_generate" {
                activity(ctx, CGPoint(x: w * 0.72, y: h * 0.42), w * 0.20, pal.solid, h * 0.02, t)
            } else if name == "ill_sec_backup_saved" {
                chip(ctx, CGPoint(x: w * 0.72, y: h * 0.58), w * 0.18, h * 0.18, pal, pal.successInk, "check", t, size: size)
            } else {
                key(ctx, CGPoint(x: w * 0.72, y: h * 0.44), h * 0.09, pal.solid, h * 0.02)
            }
        case "account-new-password":
            blobs(ctx, size, pal.infoFill, t)
            shield(ctx, CGPoint(x: w * 0.38, y: h * 0.48), w * 0.30, h * 0.60, pal, "check", t, size: size)
            key(ctx, CGPoint(x: w * 0.70, y: h * 0.52), h * 0.10, pal.solid, h * 0.022)
            sparkles(ctx, size, pal.solid, t, 1.2)
        case "account-recovery-code":
            blobs(ctx, size, pal.infoFill, t)
            card(ctx, CGPoint(x: w * 0.5, y: h * 0.5), w * 0.44, h * 0.44, pal, t)
            for (i, x) in [0.30, 0.42, 0.54, 0.66].enumerated() {
                let a = 0.4 + 0.6 * pulse(t, 2.4, Double(i) * 0.3)
                ctx.fill(Path(roundedRect: CGRect(x: w * CGFloat(x) - w * 0.03, y: h * 0.46,
                                                  width: w * 0.06, height: h * 0.10),
                              cornerRadius: w * 0.015), with: .color(pal.solid.opacity(a)))
            }
        case "account-pending":
            blobs(ctx, size, pal.infoFill, t)
            envelope(ctx, CGPoint(x: w * 0.44, y: h * 0.52), w * 0.34, h * 0.42, pal, t,
                     letter: true, showCheck: false, size: size)
            clock(ctx, CGPoint(x: w * 0.72, y: h * 0.34), h * 0.12, pal.warnInk, h * 0.022, t)
        case "account-find-address":
            findScene(ctx, size, pal, t, found: false)
        case "account-address-found":
            findScene(ctx, size, pal, t, found: true)
        case "account-phone", "ill_sec_phone":
            phoneScene(ctx, size, pal, t, ok: false)
        case "account-phone-ok":
            phoneScene(ctx, size, pal, t, ok: true)
        case "ill_sec_2fa", "ill_sec_2fa_on", "ill_sec_2fa_off", "ill_sec_2fa_off_confirm":
            blobs(ctx, size, pal.infoFill, t)
            let glyph = (name == "ill_sec_2fa_on" || name == "ill_sec_2fa_off_confirm") ? "check" : "lock"
            shield(ctx, CGPoint(x: w * 0.40, y: h * 0.48), w * 0.30, h * 0.60, pal, glyph, t, size: size)
            switch name {
            case "ill_sec_2fa_on":
                chip(ctx, CGPoint(x: w * 0.72, y: h * 0.36), w * 0.20, h * 0.18, pal, pal.successInk, "check", t, size: size)
            case "ill_sec_2fa_off":
                alert(ctx, CGPoint(x: w * 0.72, y: h * 0.38), h * 0.10, pal.warnInk, h * 0.02, t)
            case "ill_sec_2fa_off_confirm":
                alert(ctx, CGPoint(x: w * 0.74, y: h * 0.36), h * 0.09, pal.warnInk, h * 0.018, t)
                trash(ctx, CGPoint(x: w * 0.70, y: h * 0.66), h * 0.08, pal.danger, h * 0.016)
            default:
                key(ctx, CGPoint(x: w * 0.72, y: h * 0.40), h * 0.08, pal.solid, h * 0.018)
            }
            sparkles(ctx, size, pal.solid, t, 2)
        case "ill_sec_password", "ill_sec_password_confirm", "ill_sec_password_ok",
             "ill_sec_password_after", "ill_sec_password_updated":
            blobs(ctx, size, pal.infoFill, t)
            card(ctx, CGPoint(x: w * 0.40, y: h * 0.48), w * 0.30, h * 0.40, pal, t)
            for dx in [-0.08, 0.0, 0.08] {
                ctx.fill(Path(ellipseIn: CGRect(x: w * 0.40 + w * CGFloat(dx) - h * 0.018,
                                                y: h * 0.48 - h * 0.018, width: h * 0.036, height: h * 0.036)),
                         with: .color(pal.textTertiary))
            }
            switch name {
            case "ill_sec_password_ok", "ill_sec_password_updated":
                chip(ctx, CGPoint(x: w * 0.72, y: h * 0.42), w * 0.20, h * 0.18, pal, pal.successInk, "check", t, size: size)
            case "ill_sec_password_confirm":
                key(ctx, CGPoint(x: w * 0.72, y: h * 0.42), h * 0.09, pal.solid, h * 0.02)
            case "ill_sec_password_after":
                shield(ctx, CGPoint(x: w * 0.72, y: h * 0.44), w * 0.14, h * 0.28, pal, "lock", t, size: size)
            default:
                key(ctx, CGPoint(x: w * 0.72, y: h * 0.42), h * 0.09, pal.textTertiary, h * 0.02)
            }
            sparkles(ctx, size, pal.solid, t, 0.5)
        case "ill_sec_signout_all", "ill_sec_signout_all_ok":
            blobs(ctx, size, pal.infoFill, t)
            devices(ctx, CGPoint(x: w * 0.44, y: h * 0.50), w * 0.44, h * 0.60, pal, t,
                    checkOn: false, size: size)
            let ok = name == "ill_sec_signout_all_ok"
            logout(ctx, CGPoint(x: w * 0.78, y: h * 0.34), h * 0.10, ok ? pal.successInk : pal.solid,
                   h * 0.02, t)
            if ok { check(ctx, CGPoint(x: w * 0.78, y: h * 0.62), h * 0.05, pal.successInk, h * 0.016) }
        case "ill_sec_sessions", "ill_sec_session_revoke", "ill_sec_session_revoked",
             "ill_sec_session_alert", "ill_sec_revoke_confirm", "ill_sec_revoked_suspicious":
            blobs(ctx, size, pal.infoFill, t)
            switch name {
            case "ill_sec_sessions":
                devices(ctx, CGPoint(x: w * 0.48, y: h * 0.50), w * 0.52, h * 0.66, pal, t,
                        checkOn: true, size: size)
            case "ill_sec_session_revoked":
                devices(ctx, CGPoint(x: w * 0.46, y: h * 0.52), w * 0.46, h * 0.58, pal, t,
                        checkOn: false, size: size)
                check(ctx, CGPoint(x: w * 0.78, y: h * 0.34), h * 0.06, pal.successInk, h * 0.02)
            case "ill_sec_session_alert", "ill_sec_revoked_suspicious":
                devices(ctx, CGPoint(x: w * 0.46, y: h * 0.52), w * 0.46, h * 0.58, pal, t,
                        checkOn: false, size: size)
                alert(ctx, CGPoint(x: w * 0.78, y: h * 0.32), h * 0.09, pal.warnInk, h * 0.018, t)
            default:
                devices(ctx, CGPoint(x: w * 0.46, y: h * 0.52), w * 0.46, h * 0.58, pal, t,
                        checkOn: false, size: size)
                trash(ctx, CGPoint(x: w * 0.78, y: h * 0.34), h * 0.09, pal.danger, h * 0.018)
            }
            sparkles(ctx, size, pal.solid, t, 1.7)
        case "ill_sec_activity":
            blobs(ctx, size, pal.infoFill, t)
            card(ctx, CGPoint(x: w * 0.44, y: h * 0.50), w * 0.34, h * 0.56, pal, t)
            activity(ctx, CGPoint(x: w * 0.74, y: h * 0.44), w * 0.22, pal.solid, h * 0.02, t)
            listGlyph(ctx, CGPoint(x: w * 0.74, y: h * 0.66), w * 0.16, h * 0.16, pal.textTertiary, h * 0.016)
        case "ill_sec_alert":
            blobs(ctx, size, pal.infoFill, t)
            alert(ctx, CGPoint(x: w * 0.42, y: h * 0.46), h * 0.16, pal.warnInk, h * 0.026, t)
            bell(ctx, CGPoint(x: w * 0.70, y: h * 0.40), h * 0.10, pal.solid)
            card(ctx, CGPoint(x: w * 0.62, y: h * 0.68), w * 0.30, h * 0.20, pal, t)
            sparkles(ctx, size, pal.solid, t, 0.2)
        default:
            scene("welcome", ctx, size, t, pal: pal)
        }
    }

    private static func findScene(_ ctx: GraphicsContext, _ size: CGSize, _ pal: ArtPalette,
                                  _ t: Double, found: Bool) {
        let w = size.width, h = size.height
        blobs(ctx, size, pal.infoFill, t)
        let c = CGPoint(x: w * 0.44, y: h * 0.46)
        let r = h * 0.22
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                   with: .color(pal.solid), lineWidth: h * 0.028)
        ctx.stroke(Path { $0.move(to: CGPoint(x: c.x + r * 0.7, y: c.y + r * 0.7))
                          $0.addLine(to: CGPoint(x: c.x + r * 1.3, y: c.y + r * 1.3)) },
                   with: .color(pal.solid), style: StrokeStyle(lineWidth: h * 0.032, lineCap: .round))
        if found {
            check(ctx, c, r * 0.45, pal.solid, h * 0.028)
            confetti(ctx, size, [pal.successInk, pal.solid], t)
        } else {
            globe(ctx, c, r * 0.5, pal.textTertiary, h * 0.014)
        }
        card(ctx, CGPoint(x: w * 0.76, y: h * 0.66), w * 0.22, h * 0.18, pal, t)
    }

    private static func phoneScene(_ ctx: GraphicsContext, _ size: CGSize, _ pal: ArtPalette,
                                   _ t: Double, ok: Bool) {
        let w = size.width, h = size.height
        blobs(ctx, size, pal.infoFill, t)
        let pw = w * 0.22, ph = h * 0.62
        let c = CGPoint(x: w * 0.42, y: h * 0.50)
        ctx.fill(Path(roundedRect: CGRect(x: c.x - pw / 2, y: c.y - ph / 2, width: pw, height: ph),
                      cornerRadius: pw * 0.18), with: .color(pal.card))
        ctx.fill(Path(roundedRect: CGRect(x: c.x - pw * 0.4, y: c.y - ph * 0.42,
                                          width: pw * 0.8, height: ph * 0.84),
                      cornerRadius: pw * 0.1), with: grad(pal.top, pal.bottom, size))
        if ok {
            check(ctx, c, pw * 0.2, .white, pw * 0.08)
            chip(ctx, CGPoint(x: w * 0.72, y: h * 0.36), w * 0.20, h * 0.18, pal, pal.successInk, "check", t, size: size)
        } else {
            for (i, dx) in [-0.24, 0.0, 0.24].enumerated() {
                let a = 0.5 + 0.5 * pulse(t, 2.0, Double(i) * 0.35)
                ctx.fill(Path(roundedRect: CGRect(x: c.x + pw * CGFloat(dx) - pw * 0.06,
                                                  y: c.y - pw * 0.1, width: pw * 0.12, height: pw * 0.2),
                              cornerRadius: pw * 0.03), with: .color(.white.opacity(a)))
            }
        }
        sparkles(ctx, size, pal.solid, t, 0.8)
    }
}
