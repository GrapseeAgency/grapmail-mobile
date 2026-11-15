import SwiftUI

struct MailSwipeAction {
    let title: String
    var restoresToInbox = false
    let perform: () -> Void
}

struct MailSwipeRow<Content: View>: View {
    private struct Drag {
        enum Axis: Equatable { case undecided, horizontal, vertical }
        var axis = Axis.undecided
        var distance: CGFloat = 0
    }

    let right: MailSwipeAction?
    let left: MailSwipeAction?
    @ViewBuilder let content: () -> Content

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @GestureState(resetTransaction: Transaction(animation: .spring(response: 0.32, dampingFraction: 0.88)))
    private var drag = Drag()
    @State private var width: CGFloat = 0
    @State private var exitOffset: CGFloat?

    private var offset: CGFloat { exitOffset ?? drag.distance }
    private var threshold: CGFloat { min(126, max(84, width * 0.31)) }

    var body: some View {
        let row = ZStack {
            GeometryReader { geometry in
                let reveal = max(0, min(geometry.size.width - 8, abs(offset) - 8))
                let isTrash = offset < 0
                let progress = min(1, max(0, (abs(offset) - 16) / 92))
                if reveal > 0 {
                    Capsule()
                        .fill(isTrash ? Color(red: 1, green: 0.725, blue: 0.769)
                                      : Color(red: 0.631, green: 0.914, blue: 0.722))
                        .overlay {
                            if reveal >= 24 {
                                SwipeActionIcon(trash: isTrash, restore: !isTrash && right?.restoresToInbox == true,
                                                progress: reduceMotion ? 1 : progress)
                                    .frame(width: 24, height: 24)
                                    .scaleEffect(reduceMotion ? 1 : 0.82 + progress * 0.18)
                                    .opacity(reduceMotion ? 1 : max(0.12, progress))
                            }
                        }
                        .frame(width: reveal, height: max(0, geometry.size.height - 10))
                        .position(x: isTrash ? geometry.size.width - 4 - reveal / 2 : 4 + reveal / 2,
                                  y: geometry.size.height / 2)
                }
            }
            .accessibilityHidden(true)

            content()
                .background(GM.cardAlt)
                .contentShape(Rectangle())
                .offset(x: offset)
        }
        .clipped()
        .transaction { if reduceMotion { $0.animation = nil } }
        .background {
            GeometryReader { geometry in
                Color.clear
                    .onAppear { width = geometry.size.width }
                    .onChange(of: geometry.size.width) { width = $0 }
            }
        }
        .accessibilityActions {
            if let right { Button(right.title, action: right.perform) }
            if let left { Button(left.title, action: left.perform) }
        }

        if right != nil || left != nil {
            row.simultaneousGesture(swipe)
        } else {
            row
        }
    }

    private func travel(_ dx: CGFloat) -> CGFloat {
        min(width, max(0, abs(dx) - 8))
    }

    private func resistedOffset(_ dx: CGFloat) -> CGFloat {
        let distance = travel(dx)
        let resisted = min(distance, threshold) + max(0, distance - threshold) * 0.58
        return dx > 0 ? resisted : -resisted
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .updating($drag) { value, state, transaction in
                guard exitOffset == nil, width > 0 else { return }
                transaction.animation = nil
                if state.axis == .undecided {
                    state.axis = abs(value.translation.width) > abs(value.translation.height) * 1.15
                        ? .horizontal : .vertical
                }
                guard state.axis == .horizontal else { return }
                let raw = value.translation.width
                guard (raw > 0 ? right : left) != nil else { state.distance = 0; return }
                state.distance = resistedOffset(raw)
            }
            .onEnded { value in
                let dx = value.translation.width
                guard exitOffset == nil, width > 0, drag.axis != .vertical,
                      travel(dx) >= threshold, abs(dx) > abs(value.translation.height) * 1.15,
                      let action = dx > 0 ? right : left else { return }
                if reduceMotion {
                    action.perform()
                    return
                }
                exitOffset = resistedOffset(dx)
                withAnimation(.timingCurve(0.2, 0.65, 0.3, 1, duration: 0.17)) {
                    exitOffset = dx > 0 ? width : -width
                }
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 170_000_000)
                    action.perform()
                    try? await Task.sleep(nanoseconds: 75_000_000)
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.88)) {
                        exitOffset = nil
                    }
                }
            }
    }
}

private struct SwipeActionIcon: View {
    let trash: Bool
    let restore: Bool
    let progress: CGFloat

    var body: some View {
        Canvas { context, size in
            var context = context
            context.scaleBy(x: size.width / 24, y: size.height / 24)
            let ink = trash ? Color(red: 0.329, green: 0.102, blue: 0.149)
                            : Color(red: 0.067, green: 0.231, blue: 0.157)
            let style = StrokeStyle(lineWidth: 1.65, lineCap: .round, lineJoin: .round)
            func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
            func line(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) -> Path {
                Path { path in
                    path.move(to: point(x1, y1))
                    path.addLine(to: point(x2, y2))
                }
            }
            if trash {
                let bin = Path { path in
                    path.move(to: point(6, 9))
                    path.addLine(to: point(7, 19))
                    path.addQuadCurve(to: point(9, 21), control: point(7.2, 21))
                    path.addLine(to: point(15, 21))
                    path.addQuadCurve(to: point(17, 19), control: point(16.8, 21))
                    path.addLine(to: point(18, 9))
                    path.addPath(line(10, 11, 10.4, 17.5))
                    path.addPath(line(14, 11, 13.6, 17.5))
                }
                context.stroke(bin, with: .color(ink), style: style)
                var lidContext = context
                lidContext.translateBy(x: 6, y: 7)
                lidContext.rotate(by: .degrees(Double(-11 * progress)))
                lidContext.translateBy(x: -6, y: -7 - progress * 0.9)
                let lid = Path { path in
                    path.addPath(line(4.5, 7, 19.5, 7))
                    path.move(to: point(9, 7))
                    path.addLine(to: point(9, 5))
                    path.addQuadCurve(to: point(10, 4), control: point(9, 4))
                    path.addLine(to: point(14, 4))
                    path.addQuadCurve(to: point(15, 5), control: point(15, 4))
                    path.addLine(to: point(15, 7))
                }
                lidContext.stroke(lid, with: .color(ink), style: style)
            } else {
                let archive = Path { path in
                    path.addRoundedRect(in: CGRect(x: 4, y: 4, width: 16, height: 5),
                                        cornerSize: CGSize(width: 1.45, height: 1.45))
                    path.move(to: point(5.5, 9))
                    path.addLine(to: point(5.5, 18.5))
                    path.addQuadCurve(to: point(7, 20), control: point(5.5, 20))
                    path.addLine(to: point(17, 20))
                    path.addQuadCurve(to: point(18.5, 18.5), control: point(18.5, 20))
                    path.addLine(to: point(18.5, 9))
                    let shift = 1.2 * (1 - progress)
                    let tip = restore ? 11.8 + shift : 16.5 - shift
                    let shoulder = tip + (restore ? 2.3 : -2.3)
                    path.addPath(line(12, tip + (restore ? 4.7 : -4.7), 12, tip))
                    path.move(to: point(9.7, shoulder))
                    path.addLine(to: point(12, tip))
                    path.addLine(to: point(14.3, shoulder))
                }
                context.stroke(archive, with: .color(ink), style: style)
            }
        }
        .accessibilityHidden(true)
    }
}
