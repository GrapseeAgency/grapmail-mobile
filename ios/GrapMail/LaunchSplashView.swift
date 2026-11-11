import SwiftUI

/// The launch splash, mirroring the Android one: the envelope mark centered on
/// the brand blue, and "Grapsee" beneath as the brand's nine-slice shimmer —
/// every slice carries the whole word at its own scale and opacity, clipped to
/// one vertical band, with the white→grey boundary riding the sweep. Shown once
/// per launch while the first mailbox read settles, then faded away.
struct LaunchSplashView: View {
    @Binding var visible: Bool

    var body: some View {
        ZStack {
            Color(red: 0x1A / 255.0, green: 0x6D / 255.0, blue: 0xF0 / 255.0)
            Image("LaunchBrand")
                .resizable()
                .scaledToFit()
                .frame(width: 132, height: 130)
            GrapseeShimmer()
                .frame(maxWidth: 260, maxHeight: 52)
                .padding(.bottom, 64)
                .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .ignoresSafeArea()
        .task {
            try? await Task.sleep(nanoseconds: 2_100_000_000)
            withAnimation(.easeOut(duration: 0.42)) {
                visible = false
            }
        }
    }
}

/// The nine-slice shimmer. TimelineView supplies a frame clock; the Canvas
/// draws each slice clipped to its band, the whole word at its own scale and
/// opacity, and the white→grey boundary riding the sweep — the same three
/// moving parts the brand's CSS has.
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
                let shift = CGFloat(t * 2 - 1)
                let base = size.height * 0.52

                for i in 0..<9 {
                    let rect = CGRect(x: CGFloat(i) * slice, y: 0,
                                      width: slice, height: size.height)
                    var sliceCtx = ctx
                    sliceCtx.clip(to: rect)
                    let fontSize = base * (10.0 / sizes[i])
                    let textX = size.width / 2 - shift * slice * 1.6
                    let textY = size.height / 2
                    var text = Text("Grapsee")
                        .font(.system(size: fontSize, weight: .black))
                        .foregroundColor(white)
                    if let pair = stops[i] {
                        let ride = (shift + 1) * 0.22
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
                    sliceCtx.draw(text, at: CGPoint(x: textX, y: textY), anchor: .center)
                }
            }
        }
    }
}
