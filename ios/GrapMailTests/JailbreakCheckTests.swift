import XCTest
@testable import GrapMail

/// Mirrors android/.../security/RootCheckTest.kt: the pure judgement of
/// DeviceIntegrityCheck.swift and the defaults of the hub note.
final class JailbreakCheckTests: XCTestCase {
    private func facts(paths: Set<String> = [], wrote: Bool = false,
                       images: [String] = ["/usr/lib/libSystem.B.dylib"]) -> JailbreakCheck.Facts {
        JailbreakCheck.Facts(existingPaths: paths, sandboxWriteSucceeded: wrote, loadedImages: images)
    }

    func testCleanDeviceHasNoFindings() {
        XCTAssertTrue(JailbreakCheck.evaluate(facts()).isEmpty)
    }

    func testPackageManagerPathsAreFound() {
        XCTAssertEqual(JailbreakCheck.evaluate(facts(paths: ["/Applications/Cydia.app"])), [.jailbreakFiles])
        XCTAssertEqual(JailbreakCheck.evaluate(facts(paths: ["/Applications/Sileo.app"])), [.jailbreakFiles])
        XCTAssertEqual(JailbreakCheck.evaluate(facts(paths: ["/var/jb"])), [.jailbreakFiles])
    }

    func testUnknownPathIsIgnored() {
        XCTAssertTrue(JailbreakCheck.evaluate(facts(paths: ["/Applications/Notes.app"])).isEmpty)
    }

    func testSandboxWriteIsFound() {
        XCTAssertEqual(JailbreakCheck.evaluate(facts(wrote: true)), [.sandboxWritable])
    }

    func testInjectedLibraryIsFoundCaseInsensitively() {
        XCTAssertEqual(JailbreakCheck.evaluate(facts(images: ["/usr/lib/TweakInject/x.dylib"])), [.injectedLibrary])
        XCTAssertEqual(JailbreakCheck.evaluate(facts(images: ["/private/frida-gadget.dylib"])), [.injectedLibrary])
    }

    func testWarningOnlyWhenSuspiciousAndNotDismissed() {
        XCTAssertFalse(JailbreakCheck.defaultDismissed)
        XCTAssertTrue(JailbreakCheck.shouldWarn([.jailbreakFiles], dismissed: JailbreakCheck.defaultDismissed))
        XCTAssertFalse(JailbreakCheck.shouldWarn([.jailbreakFiles], dismissed: true))
        XCTAssertFalse(JailbreakCheck.shouldWarn([], dismissed: false))
        XCTAssertFalse(JailbreakCheck.shouldWarn(nil, dismissed: false))
    }

    func testPinsStayUnenforcedAsDesigned() {
        // security/api-pins.json keeps enforce:false (Cloudflare edge certs).
        XCTAssertFalse(ApiPins.enforce)
        XCTAssertFalse(PinPolicy.isEnforcing())
    }
}
