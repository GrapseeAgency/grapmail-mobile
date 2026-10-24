import Foundation

/// Validation rules mirroring the real backend exactly — the UI enforces what
/// the server enforces, so no round-trip is ever wasted on a predictable 4xx.
enum Validators {
    static func firstName(_ v: String) -> String? {
        v.trimmingCharacters(in: .whitespaces).count < 2 ? "Please enter your first name (2+ characters)." : nil
    }

    static func lastName(_ v: String) -> String? {
        v.trimmingCharacters(in: .whitespaces).count < 2 ? "Please enter your last name (2+ characters)." : nil
    }

    /// ISO yyyy-MM-dd when the date is real and the person is 13+.
    static func birthday(year: Int?, month: Int?, day: Int?) -> (iso: String?, error: String?) {
        guard let year, let month, let day else { return (nil, nil) }
        guard year >= 1900, (1...12).contains(month), (1...31).contains(day),
              let date = Calendar.date(year: year, month: month, day: day) else {
            return (nil, "Enter a real birthday.")
        }
        guard date <= Date() else { return (nil, "Enter a real birthday.") }
        guard age(from: date) >= 13 else {
            return (nil, "You must be at least 13 years old to create a GrapMail account.")
        }
        return (String(format: "%04d-%02d-%02d", year, month, day), nil)
    }

    static func age(from date: Date) -> Int {
        let cal = Calendar.current
        let now = Date()
        var age = cal.component(.year, from: now) - cal.component(.year, from: date)
        let beforeBirthday = (cal.component(.month, from: now), cal.component(.day, from: now)) <
            (cal.component(.month, from: date), cal.component(.day, from: date))
        if beforeBirthday { age -= 1 }
        return age
    }

    static func handleChars(_ s: String) -> String {
        String(s.lowercased().filter { "abcdefghijklmnopqrstuvwxyz0123456789._-".contains($0) })
    }

    static func handleValid(_ h: String) -> Bool {
        h.count >= 3 && h.count <= 30 && !h.isEmpty && h.allSatisfy { "abcdefghijklmnopqrstuvwxyz0123456789._-".contains($0) }
    }

    /// Suggestions derived from the name, in the order people expect.
    static func handleCandidates(first: String, last: String) -> [String] {
        let f = first.lowercased().filter(\.isLetter)
        let l = last.lowercased().filter(\.isLetter)
        var out: [String] = []
        func push(_ v: String) {
            let h = handleChars(v)
            if h.count >= 3 && h.count <= 30 && !out.contains(h) { out.append(h) }
        }
        if !f.isEmpty && !l.isEmpty {
            push("\(f).\(l)"); push("\(f)\(l)"); push("\(f).\(l.first!)"); push("\(f.first!)\(l)")
        }
        push(f); push(l)
        if !f.isEmpty && !l.isEmpty {
            for n in 1...9 where out.count < 10 { push("\(f).\(l)\(n)") }
            for n in 1...9 where out.count < 10 { push("\(f)\(l)\(n)") }
        }
        return out
    }

    enum PasswordReq: String, CaseIterable, Identifiable {
        case length = "At least 8 characters"
        case casing = "One uppercase & lowercase letter"
        case digit = "One number"
        case special = "One special character"
        var id: String { rawValue }
    }

    static func passwordMet(_ req: PasswordReq, _ pw: String) -> Bool {
        switch req {
        case .length: return pw.count >= 8
        case .casing: return pw.contains(where: \.isUppercase) && pw.contains(where: { $0.isLowercase })
        case .digit: return pw.contains(where: \.isNumber)
        case .special: return pw.contains(where: { !$0.isLetter && !$0.isNumber })
        }
    }

    static func passwordStrength(_ pw: String) -> Double {
        let met = PasswordReq.allCases.filter { passwordMet($0, pw) }.count
        return Double(met) / 4.0
    }
}

extension Calendar {
    static func date(year: Int, month: Int, day: Int) -> Date? {
        var comps = DateComponents()
        comps.year = year; comps.month = month; comps.day = day
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: comps)
    }
}
