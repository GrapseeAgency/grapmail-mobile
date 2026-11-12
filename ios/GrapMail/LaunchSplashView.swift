import SwiftUI

/// The launch splash, mirroring the Android one: the mark's own diagonal
/// gradient so the screen reads as one continuous blue, and "Grapsee" beneath
/// as the brand's nine-slice shimmer — every slice carries the whole word at
/// its own scale and opacity, clipped to one vertical band, sliding through.
/// The system launch screen has already shown the mark; this page adds only
/// the word. Shown once per launch, then faded away.
struct LaunchSplashView: View {
    @Binding var visible: Bool

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0x3E / 255.0, green: 0x89 / 255.0, blue: 0xF8 / 255.0),
                    Color(red: 0x7F / 255.0, green: 0xAF / 255.0, blue: 0xFB / 255.0),
                    Color(red: 0xC6 / 255.0, green: 0xD9 / 255.0, blue: 0xFD / 255.0),
                ],
                startPoint: .topLeading, endPoint: .bottomTrailing)
            GrapseeShimmer()
                .frame(width: 210, height: 38)
                .padding(.bottom, 48)
                .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .ignoresSafeArea()
        .task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            withAnimation(.easeOut(duration: 0.42)) {
                visible = false
            }
        }
    }
}

/// The nine-slice shimmer. TimelineView supplies a frame clock; the Canvas
/// draws each slice clipped to its band, the whole word at its own scale and
/// opacity, sliding left → right — the same three moving parts the brand's CSS
/// has: band clip, per-slice scale/opacity, and the sliding word itself.
struct GrapseeShimmer: View {
    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { ctx, size in
                let period = 2.0
                let t = timeline.date.timeIntervalSinceReferenceDate
                    .truncatingRemainder(dividingBy: period) / period
                let slice = size.width / 9
                let sizes: [CGFloat] = [20, 16, 13, 11, 10, 11, 13, 16, 20]
                let alphas: [CGFloat] = [0.6, 0.7, 0.8, 0.9, 1, 0.9, 0.8, 0.7, 0.6]
                let stops: [(CGFloat, CGFloat)?] = [
                    (4, 7), (9, 13), (15, 18), (20, 23),
                    nil,
                    (29, 32), (34, 37), (39, 42), (45, 48),
                ]
                let white = Color.white
                let grey = Color(red: 0.604, green: 0.604, blue: 0.604)
                // The word slides from fully left (off screen) to fully right
                // (off screen); every band shows its own scaled copy, anchored
                // at the same LEFT edge — centering was the mangling.
                let slide = t * (size.width + size.width * 0.62) - size.width * 0.62
                let base = size.height * 0.9

                for i in 0..<9 {
                    let rect = CGRect(x: CGFloat(i) * slice, y: 0,
                                      width: slice, height: size.height)
                    var sliceCtx = ctx
                    sliceCtx.clip(to: rect)
                    let fontSize = base * (10.0 / sizes[i])
                    let textX = slide
                    let textY = size.height / 2
                    var text = Text("Grapsee")
                        .font(.system(size: fontSize, weight: .black))
                        .foregroundColor(white)
                    if let pair = stops[i] {
                        let ride = CGFloat(t * 2 - 1 + 1) * 0.22
                        let a = min(max(pair.0 / 100 + ride, 0), 1)
                        let b = min(max(pair.1 / 100 + ride, 0), 1)
                        text = text.foregroundStyle(LinearGradient(
                            stops: [
                                .init(color: grey, location: 0),
                                .init(color: grey, location: a),
                                .init(color: white, location: b),
                                .init(color: white, location: 1),
                            ],
                            startPoint: .leading, endPoint: .trailing))
                    }
                    sliceCtx.opacity = alphas[i]
                    sliceCtx.draw(text, at: CGPoint(x: textX, y: textY), anchor: .leading)
                }
            }
        }
    }
}
