import SwiftUI

/// GrapMail's own human verification — the slide puzzle from the design,
/// replacing the Telegram gate entirely. The piece follows the finger; on
/// release it must land within tolerance of the notch cut into the photo.
/// Target randomized per challenge. Genuine interaction test: no passive
/// bypass, no third-party messenger, no phone number.
struct PuzzleSlider: View {
    @Binding var verified: Bool
    var onVerified: () -> Void

    @State private var dragX: CGFloat = 0
    @State private var dragging = false
    @State private var failed = false
    @State private var targetFraction: CGFloat = 0.55
    @State private var trackWidth: CGFloat = 1
    @State private var appeared = false

    private var maxX: CGFloat { max(trackWidth - 44 - 12, 1) }
    private var targetX: CGFloat { maxX * targetFraction }
    private let tolerance: CGFloat = 28

    var body: some View {
        VStack(spacing: 12) {
            photoStrip
                .frame(height: 132)

            GeometryReader { geo in
                track
                    .onAppear { trackWidth = geo.size.width }
                    .onChange(of: geo.size.width) { trackWidth = $0 }
            }
            .frame(height: 56)
        }
        .onAppear {
            if !appeared {
                targetFraction = 0.45 + CGFloat.random(in: 0...0.35)
                appeared = true
            }
        }
    }

    private var photoStrip: some View {
        GeometryReader { geo in
            ZStack {
                Image("puzzle_photo")
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: geo.size.width, height: 132)
                    .clipped()
                PuzzlePieceShape()
                    .fill(Color.black.opacity(0.55))
                    .frame(width: notchSide, height: notchSide)
                    .position(x: targetX + 22 + 6, y: geo.size.height / 2)
                PuzzlePieceShape()
                    .stroke(Color.white.opacity(0.85), lineWidth: 2)
                    .frame(width: notchSide, height: notchSide)
                    .position(x: targetX + 22 + 6, y: geo.size.height / 2)
                if verified {
                    PuzzlePieceShape()
                        .fill(Color.brandGradient)
                        .frame(width: notchSide, height: notchSide)
                        .position(x: targetX + 22 + 6, y: geo.size.height / 2)
                    PuzzlePieceShape()
                        .stroke(GM.success, lineWidth: 3)
                        .frame(width: notchSide, height: notchSide)
                        .position(x: targetX + 22 + 6, y: geo.size.height / 2)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous))
        .onAppear { trackWidth = trackWidth } // measured by the track's GeometryReader
    }

    private var notchSide: CGFloat { 132 * 0.62 }

    private var track: some View {
        ZStack {
            RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                .fill(verified ? GM.success.opacity(0.12) : GM.softFill)

            HStack(spacing: 8) {
                if verified {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(GM.success)
                    Text("Verified — you're human!")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(GM.success)
                } else {
                    Image(systemName: "arrow.right")
                        .font(.system(size: 14))
                        .foregroundColor(GM.textTertiary)
                    Text(failed ? "Almost — try again" : "Slide to verify")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(failed ? GM.error : GM.textSecondary)
                }
            }

            PuzzlePieceShape()
                .fill(verified ? AnyShapeStyle(GM.success) : AnyShapeStyle(Color.brandGradient))
                .frame(width: 30, height: 30)
                .position(x: dragX + 6 + 22, y: 28)
                .gesture(
                    DragGesture(minimumDistance: 1)
                        .onChanged { value in
                            guard !verified else { return }
                            failed = false
                            dragX = min(max(value.location.x - 22, 0), maxX)
                        }
                        .onEnded { _ in
                            guard !verified else { return }
                            if abs(dragX - targetX) <= tolerance {
                                withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                                    dragX = targetX
                                }
                                UINotificationFeedbackGenerator().notificationOccurred(.success)
                                verified = true
                                onVerified()
                            } else {
                                failed = true
                                UINotificationFeedbackGenerator().notificationOccurred(.error)
                                withAnimation(.spring(response: 0.45, dampingFraction: 0.65)) {
                                    dragX = 0
                                }
                            }
                        }
                )
        }
    }
}

/// Jigsaw piece silhouette — rounded square with right tab and bottom notch.
struct PuzzlePieceShape: Shape {
    func path(in rect: CGRect) -> Path {
        let side = min(rect.width, rect.height)
        let x = rect.minX + (rect.width - side) / 2
        let y = rect.minY + (rect.height - side) / 2
        let r = side * 0.14
        let tab = side * 0.22
        var p = Path()
        p.move(to: CGPoint(x: x + r, y: y))
        p.addLine(to: CGPoint(x: x + side - r, y: y))
        p.addQuadCurve(to: CGPoint(x: x + side, y: y + r), control: CGPoint(x: x + side, y: y))
        p.addLine(to: CGPoint(x: x + side, y: y + side * 0.45))
        p.addCurve(
            to: CGPoint(x: x + side, y: y + side * 0.55),
            control1: CGPoint(x: x + side + tab, y: y + side * 0.38),
            control2: CGPoint(x: x + side + tab, y: y + side * 0.62)
        )
        p.addLine(to: CGPoint(x: x + side, y: y + side - r))
        p.addQuadCurve(to: CGPoint(x: x + side - r, y: y + side), control: CGPoint(x: x + side, y: y + side))
        p.addLine(to: CGPoint(x: x + side * 0.55, y: y + side))
        p.addCurve(
            to: CGPoint(x: x + side * 0.45, y: y + side),
            control1: CGPoint(x: x + side * 0.62, y: y + side + tab),
            control2: CGPoint(x: x + side * 0.38, y: y + side + tab)
        )
        p.addLine(to: CGPoint(x: x + r, y: y + side))
        p.addQuadCurve(to: CGPoint(x: x, y: y + side - r), control: CGPoint(x: x, y: y + side))
        p.addLine(to: CGPoint(x: x, y: y + r))
        p.addQuadCurve(to: CGPoint(x: x + r, y: y), control: CGPoint(x: x, y: y))
        p.closeSubpath()
        return p
    }
}
