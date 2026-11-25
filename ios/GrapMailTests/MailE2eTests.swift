import XCTest
@testable import GrapMail

/// Mirrors android/.../data/MailE2eTest.kt. The fixed vectors (E2eTestVectors)
/// were written by an independent implementation and are opened by BOTH mobile
/// suites: if either platform drifts by one byte from the format, its vector
/// tests fail.
final class MailE2eTests: XCTestCase {
    private typealias V = E2eTestVectors

    private func message(_ error: Error) -> String { (error as? MailE2e.E2eError)?.text ?? "\(error)" }

    private func object(_ json: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
    }
    private func json(_ obj: [String: Any]) -> String {
        String(decoding: try! JSONSerialization.data(withJSONObject: obj), as: UTF8.self)
    }
    /// Flip the lowest bit of byte `index` of a base64 field.
    private func flip(_ b64: String, _ index: Int) -> String {
        var d = Data(base64Encoded: b64)!
        d[index] ^= 0x01
        return d.base64EncodedString()
    }
    private func record(_ o: [String: Any]) -> MailE2e.ProtectedPrivateKey {
        MailE2e.ProtectedPrivateKey(kind: o["kind"] as! String, publicKey: o["publicKey"] as! String,
                                    iterations: o["iterations"] as! Int, salt: o["salt"] as! String,
                                    iv: o["iv"] as! String, ct: o["ct"] as! String)
    }

    // ── fixed cross-platform vectors ─────────────────────────────────────────

    func testVectorV1OpensAndItsSignatureVerifies() throws {
        XCTAssertEqual(try MailE2e.open(V.envelopeV1, recipientPrivateKeyB64: V.recipientPrivate), V.plaintext)
        XCTAssertEqual(try MailE2e.open(V.envelopeV1, recipientPrivateKeyB64: V.recipientPrivate, verifyKeyB64: V.signerPublic), V.plaintext)
    }

    func testVectorV2OpensForEitherRecipientWhicheverWrapIsTheirs() throws {
        XCTAssertEqual(try MailE2e.open(V.envelopeV2, recipientPrivateKeyB64: V.recipientPrivate, verifyKeyB64: V.signerPublic), V.plaintext)
        XCTAssertEqual(try MailE2e.open(V.envelopeV2, recipientPrivateKeyB64: V.otherPrivate), V.plaintext)
    }

    func testVectorFingerprintAndKeyId() {
        XCTAssertEqual(MailE2e.fingerprint(publicKeyB64: V.recipientPublic), V.recipientFingerprint)
        XCTAssertEqual(MailE2e.keyId(publicKeyB64: V.recipientPublic),
                       String(V.recipientFingerprint.replacingOccurrences(of: ":", with: "").suffix(16)))
        XCTAssertNil(MailE2e.fingerprint(publicKeyB64: "not base64!"))
        XCTAssertNil(MailE2e.fingerprint(publicKeyB64: V.signerPublic), "an Ed25519 key is not an X25519 key")
    }

    func testVectorV1RefusesTheWrongKey() {
        XCTAssertThrowsError(try MailE2e.open(V.envelopeV1, recipientPrivateKeyB64: V.otherPrivate))
    }

    func testProtectedKeyVectorUnlocksWithTheUtf8Passphrase() throws {
        let r = MailE2e.ProtectedPrivateKey(kind: "X25519", publicKey: V.recipientPublic, iterations: V.protectedIterations,
                                            salt: V.protectedSalt, iv: V.protectedIv, ct: V.protectedCt)
        XCTAssertEqual(try MailE2e.unlockPrivateKey(r, passphrase: V.protectedPassphrase), V.recipientPrivate)
        XCTAssertThrowsError(try MailE2e.unlockPrivateKey(r, passphrase: V.protectedPassphrase + " "))
    }

    func testRecoveryKitVectorOpensWithTheTypedRecoveryKey() throws {
        let canonical = MailE2e.normalizeRecoveryKey(V.recoveryKeyTyped)
        XCTAssertEqual(canonical, V.recoveryKeyCanonical)
        let kit = object(V.recoveryKit)
        XCTAssertEqual(kit["format"] as? String, MailE2e.recoveryKitFormat)
        XCTAssertEqual(try MailE2e.unlockPrivateKey(record(kit["encryption"] as! [String: Any]), passphrase: canonical!), V.recipientPrivate)
        XCTAssertEqual(try MailE2e.unlockPrivateKey(record(kit["signing"] as! [String: Any]), passphrase: canonical!), V.signerPrivate)
    }

    func testRestoringTheVectorKitGivesBackTheSameKeysUnderANewPassphrase() throws {
        // Writes (overwrites) one identity file for a test-only account in the
        // simulator app's Application Support. Nothing is deleted.
        let identity = try MailE2e.restoreFromRecoveryKit(accountKey: "unit-tests@grapmail.invalid", kit: object(V.recoveryKit),
                                                          recoveryKey: V.recoveryKeyTyped, newPassphrase: "a brand new passphrase")
        XCTAssertEqual(identity.encryption.publicKey, V.recipientPublic)
        XCTAssertEqual(identity.encryption.iterations, MailE2e.pbkdf2Iterations)
        let unlocked = try MailE2e.unlock(identity, passphrase: "a brand new passphrase")
        XCTAssertEqual(unlocked.encryptionPrivateKey, V.recipientPrivate)
        XCTAssertEqual(unlocked.signingPrivateKey, V.signerPrivate)
        // …so mail already sealed to the key still opens.
        XCTAssertEqual(try MailE2e.open(V.envelopeV1, recipientPrivateKeyB64: unlocked.encryptionPrivateKey), V.plaintext)
    }

    func testRestoreRefusesBadInputsBeforeTouchingStorage() {
        let kit = object(V.recoveryKit)
        XCTAssertThrowsError(try MailE2e.restoreFromRecoveryKit(accountKey: "x", kit: kit, recoveryKey: V.recoveryKeyTyped, newPassphrase: "short"))
        XCTAssertThrowsError(try MailE2e.restoreFromRecoveryKit(accountKey: "x", kit: kit, recoveryKey: "nope", newPassphrase: "long enough pass"))
        var wrongFormat = kit; wrongFormat["format"] = "something-else/1"
        XCTAssertThrowsError(try MailE2e.restoreFromRecoveryKit(accountKey: "x", kit: wrongFormat, recoveryKey: V.recoveryKeyTyped, newPassphrase: "long enough pass"))
        let otherKey = MailE2e.generateRecoveryKey()
        XCTAssertThrowsError(try MailE2e.restoreFromRecoveryKit(accountKey: "x", kit: kit, recoveryKey: otherKey, newPassphrase: "long enough pass")) {
            XCTAssertTrue(self.message($0).contains("does not open"), self.message($0))
        }
    }

    func testGeneratedKeysUseTheRfc8410EncodingsAndroidAndTheWebUse() throws {
        func hexPrefix(_ b64: String, _ n: Int) -> String { Data(base64Encoded: b64)!.prefix(n).map { String(format: "%02x", $0) }.joined() }
        let x = try MailE2e.generateKeyPair(kind: "X25519")
        let e = try MailE2e.generateKeyPair(kind: "Ed25519")
        XCTAssertEqual(Data(base64Encoded: x.publicKey)!.count, 44)
        XCTAssertEqual(hexPrefix(x.publicKey, 12), "302a300506032b656e032100")
        XCTAssertEqual(hexPrefix(e.publicKey, 12), "302a300506032b6570032100")
        XCTAssertEqual(Data(base64Encoded: x.privateKey)!.count, 48)
        XCTAssertEqual(hexPrefix(x.privateKey, 16), "302e020100300506032b656e04220420")
        XCTAssertEqual(hexPrefix(e.privateKey, 16), "302e020100300506032b657004220420")
    }

    // ── signatures ───────────────────────────────────────────────────────────

    func testSignatureFromAnUnexpectedKeyIsRefused() throws {
        let mallory = try MailE2e.generateKeyPair(kind: "Ed25519")
        XCTAssertThrowsError(try MailE2e.open(V.envelopeV1, recipientPrivateKeyB64: V.recipientPrivate, verifyKeyB64: mallory.publicKey)) {
            XCTAssertTrue(self.message($0).contains("unexpected"), self.message($0))
        }
    }

    func testTamperedSignatureIsRefused() {
        for raw in [V.envelopeV1, V.envelopeV2] {
            var o = object(raw)
            o["sig"] = flip(o["sig"] as! String, 32)
            XCTAssertThrowsError(try MailE2e.open(json(o), recipientPrivateKeyB64: V.recipientPrivate, verifyKeyB64: V.signerPublic))
        }
    }

    func testRequiringASignatureRefusesAnUnsignedEnvelope() throws {
        var o = object(V.envelopeV1)
        o["sig"] = nil
        o["signerPub"] = nil
        XCTAssertEqual(try MailE2e.open(json(o), recipientPrivateKeyB64: V.recipientPrivate), V.plaintext)
        XCTAssertThrowsError(try MailE2e.open(json(o), recipientPrivateKeyB64: V.recipientPrivate, verifyKeyB64: V.signerPublic)) {
            XCTAssertTrue(self.message($0).contains("not signed"))
        }
    }

    func testRewritingTheCiphertextBreaksTheSignature() {
        var o = object(V.envelopeV1)
        o["ct"] = flip(o["ct"] as! String, 0)
        XCTAssertThrowsError(try MailE2e.open(json(o), recipientPrivateKeyB64: V.recipientPrivate, verifyKeyB64: V.signerPublic))
    }

    // ── v2 round trips ───────────────────────────────────────────────────────

    func testV2SealsOnceAndEveryRecipientOpens() throws {
        let people = try (0..<3).map { _ in try MailE2e.generateKeyPair(kind: "X25519") }
        let signer = try MailE2e.generateKeyPair(kind: "Ed25519")
        let text = "Ünïcødé ✉ 日本語 — quarterly numbers"
        let env = try MailE2e.sealToRecipients(plaintext: text, recipientPublicKeys: people.map(\.publicKey), signing: signer)
        XCTAssertEqual(env.recipients.count, 3)
        XCTAssertEqual(Set(env.recipients.map(\.epk)).count, 3, "one ephemeral key per recipient")
        let wire = MailE2e.envelopeJson(env)
        XCTAssertEqual(object(wire)["alg"] as? String, MailE2e.algV2)
        XCTAssertEqual(object(wire)["v"] as? Int, 2)
        for p in people {
            XCTAssertEqual(try MailE2e.open(wire, recipientPrivateKeyB64: p.privateKey, verifyKeyB64: signer.publicKey), text)
        }
    }

    func testV2ReorderingOrDroppingRecipientsBreaksItForEveryone() throws {
        let people = try (0..<3).map { _ in try MailE2e.generateKeyPair(kind: "X25519") }
        var o = object(MailE2e.envelopeJson(try MailE2e.sealToRecipients(plaintext: "hello", recipientPublicKeys: people.map(\.publicKey))))
        let wraps = o["recipients"] as! [[String: Any]]
        o["recipients"] = Array(wraps.reversed())
        let reordered = json(o)
        o["recipients"] = Array(wraps.dropFirst())
        let dropped = json(o)
        for p in people { XCTAssertThrowsError(try MailE2e.open(reordered, recipientPrivateKeyB64: p.privateKey)) }
        for p in people.dropFirst() { XCTAssertThrowsError(try MailE2e.open(dropped, recipientPrivateKeyB64: p.privateKey)) }
    }

    func testV2TamperedBodyOrTagFailsAndStrangersAreToldSo() throws {
        let bob = try MailE2e.generateKeyPair(kind: "X25519")
        let eve = try MailE2e.generateKeyPair(kind: "X25519")
        let wire = MailE2e.envelopeJson(try MailE2e.sealToRecipients(plaintext: "for bob", recipientPublicKeys: [bob.publicKey]))
        for field in ["ct", "tag"] {
            var o = object(wire)
            o[field] = flip(o[field] as! String, 0)
            XCTAssertThrowsError(try MailE2e.open(json(o), recipientPrivateKeyB64: bob.privateKey), field)
        }
        XCTAssertThrowsError(try MailE2e.open(wire, recipientPrivateKeyB64: eve.privateKey)) {
            XCTAssertTrue(self.message($0).contains("not encrypted to your key"), self.message($0))
        }
    }

    func testSealRefusesNoRecipientsTooManyAndNonX25519Keys() throws {
        XCTAssertThrowsError(try MailE2e.sealToRecipients(plaintext: "x", recipientPublicKeys: []))
        XCTAssertThrowsError(try MailE2e.sealToRecipients(plaintext: "x", recipientPublicKeys: Array(repeating: "k", count: MailE2e.maxRecipients + 1)))
        let signing = try MailE2e.generateKeyPair(kind: "Ed25519")
        XCTAssertThrowsError(try MailE2e.sealToRecipients(plaintext: "x", recipientPublicKeys: [signing.publicKey]))
    }

    // ── envelope shape ───────────────────────────────────────────────────────

    func testParseRecognisesTheVectorsAndRefusesEverythingElse() {
        XCTAssertEqual(MailE2e.parseEnvelope(V.envelopeV1)?.version, 1)
        XCTAssertEqual(MailE2e.parseEnvelope(V.envelopeV1)?.signed, true)
        XCTAssertEqual(MailE2e.parseEnvelope(V.envelopeV2)?.version, 2)
        let bad: [String?] = [
            nil, "", "not json", "[]",
            #"{"v":99,"epk":"a","iv":"b","ct":"c","tag":"d"}"#,
            #"{"v":1,"epk":"a","iv":"b","ct":"c"}"#,
            #"{"v":1,"epk":1,"iv":"b","ct":"c","tag":"d"}"#,
            #"{"v":2,"iv":"a","ct":"b","tag":"c","recipients":[]}"#,
            #"{"v":2,"iv":"a","ct":"b","tag":"c","recipients":[{"epk":"e","wrapIv":"w","wrappedKey":"k"}]}"#,
        ]
        for raw in bad { XCTAssertNil(MailE2e.parseEnvelope(raw), raw ?? "nil") }
    }

    func testOpenRefusesUnknownVersionsAndUnreadableInput() {
        XCTAssertThrowsError(try MailE2e.open(#"{"v":99}"#, recipientPrivateKeyB64: V.recipientPrivate)) {
            XCTAssertTrue(self.message($0).contains("version"))
        }
        XCTAssertThrowsError(try MailE2e.open("garbage", recipientPrivateKeyB64: V.recipientPrivate))
    }

    // ── passphrase-protected keys ────────────────────────────────────────────

    func testProtectAndUnlockRoundTripAndRefuseTheWrongPassphrase() throws {
        let pair = try MailE2e.generateKeyPair(kind: "X25519")
        let r = try MailE2e.protectPrivateKey(privateKeyB64: pair.privateKey, passphrase: "a long passphrase", kind: "X25519",
                                              publicKey: pair.publicKey, iterations: 1000)
        XCTAssertEqual(try MailE2e.unlockPrivateKey(r, passphrase: "a long passphrase"), pair.privateKey)
        XCTAssertThrowsError(try MailE2e.unlockPrivateKey(r, passphrase: "a long passphrasE")) {
            XCTAssertTrue(self.message($0).contains("passphrase"))
        }
    }

    func testTheClearPublicHalfAndKindAreBoundIntoTheRecord() throws {
        let pair = try MailE2e.generateKeyPair(kind: "X25519")
        let r = try MailE2e.protectPrivateKey(privateKeyB64: pair.privateKey, passphrase: "a long passphrase", kind: "X25519",
                                              publicKey: pair.publicKey, iterations: 1000)
        var swapped = r; swapped.publicKey = try MailE2e.generateKeyPair(kind: "X25519").publicKey
        XCTAssertThrowsError(try MailE2e.unlockPrivateKey(swapped, passphrase: "a long passphrase"))
        var kind = r; kind.kind = "Ed25519"
        XCTAssertThrowsError(try MailE2e.unlockPrivateKey(kind, passphrase: "a long passphrase"))
        var rsa = r; rsa.kind = "RSA"
        XCTAssertThrowsError(try MailE2e.unlockPrivateKey(rsa, passphrase: "a long passphrase"))
    }

    func testPassphraseLengthIsCountedInUtf16UnitsLikeAndroidAndTheWeb() throws {
        let pair = try MailE2e.generateKeyPair(kind: "X25519")
        XCTAssertThrowsError(try MailE2e.protectPrivateKey(privateKeyB64: pair.privateKey, passphrase: "seven77", kind: "X25519",
                                                           publicKey: pair.publicKey, iterations: 10))
        // Four emoji: 4 graphemes but 8 UTF-16 units — accepted on every client.
        let r = try MailE2e.protectPrivateKey(privateKeyB64: pair.privateKey, passphrase: "🔑🔑🔑🔑", kind: "X25519",
                                              publicKey: pair.publicKey, iterations: 10)
        XCTAssertEqual(try MailE2e.unlockPrivateKey(r, passphrase: "🔑🔑🔑🔑"), pair.privateKey)
    }

    func testASingleRoundRecordIsValid() throws {
        let pair = try MailE2e.generateKeyPair(kind: "Ed25519")
        let r = try MailE2e.protectPrivateKey(privateKeyB64: pair.privateKey, passphrase: "a long passphrase", kind: "Ed25519",
                                              publicKey: pair.publicKey, iterations: 1)
        XCTAssertEqual(try MailE2e.unlockPrivateKey(r, passphrase: "a long passphrase"), pair.privateKey)
    }

    func testChangingThePassphraseKeepsTheKeyAndRetiresTheOldOne() throws {
        let pair = try MailE2e.generateKeyPair(kind: "Ed25519")
        let r = try MailE2e.protectPrivateKey(privateKeyB64: pair.privateKey, passphrase: "old passphrase", kind: "Ed25519",
                                              publicKey: pair.publicKey, iterations: 1000)
        let next = try MailE2e.changePassphrase(r, oldPassphrase: "old passphrase", newPassphrase: "new passphrase")
        XCTAssertEqual(next.publicKey, r.publicKey)
        XCTAssertEqual(next.iterations, r.iterations)
        XCTAssertNotEqual(next.salt, r.salt)
        XCTAssertEqual(try MailE2e.unlockPrivateKey(next, passphrase: "new passphrase"), pair.privateKey)
        XCTAssertThrowsError(try MailE2e.unlockPrivateKey(next, passphrase: "old passphrase"))
    }

    // ── recovery key ─────────────────────────────────────────────────────────

    func testRecoveryKeysAreSevenGroupsOfCrockfordSymbols() {
        let keys = (0..<50).map { _ in MailE2e.generateRecoveryKey() }
        for k in keys {
            XCTAssertNotNil(k.range(of: "^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){6}$", options: .regularExpression), k)
            XCTAssertEqual(MailE2e.normalizeRecoveryKey(k), k.replacingOccurrences(of: "-", with: ""))
        }
        XCTAssertEqual(Set(keys).count, 50)
    }

    func testNormalizeForgivesCaseSpacingAndLookalikesButNotWrongLength() {
        XCTAssertEqual(MailE2e.normalizeRecoveryKey(V.recoveryKeyCanonical.lowercased()), V.recoveryKeyCanonical)
        XCTAssertNil(MailE2e.normalizeRecoveryKey(String(V.recoveryKeyCanonical.dropLast())))
        XCTAssertNil(MailE2e.normalizeRecoveryKey(V.recoveryKeyCanonical + "0"))
        XCTAssertNil(MailE2e.normalizeRecoveryKey("U" + V.recoveryKeyCanonical.dropFirst()))
        XCTAssertNil(MailE2e.normalizeRecoveryKey(""))
    }

    func testRecoveryKitBuiltHereOpensWithTheRecoveryKey() throws {
        let enc = try MailE2e.generateKeyPair(kind: "X25519")
        let unlocked = MailE2e.Unlocked(encryptionPrivateKey: enc.privateKey, encryptionPublicKey: enc.publicKey,
                                        signingPrivateKey: nil, signingPublicKey: nil)
        let key = MailE2e.generateRecoveryKey()
        let kit = try MailE2e.buildRecoveryKit(unlocked, recoveryKey: key.lowercased().replacingOccurrences(of: "-", with: " "))
        XCTAssertEqual(kit["format"] as? String, MailE2e.recoveryKitFormat)
        XCTAssertNil(kit["signing"])
        let e = kit["encryption"] as! [String: Any]
        XCTAssertEqual(e["format"] as? String, MailE2e.protectedKeyFormat)
        XCTAssertEqual(e["kdf"] as? String, MailE2e.kdfName)
        XCTAssertEqual(e["iterations"] as? Int, MailE2e.pbkdf2Iterations)
        XCTAssertEqual(try MailE2e.unlockPrivateKey(record(e), passphrase: MailE2e.normalizeRecoveryKey(key)!), enc.privateKey)
        XCTAssertThrowsError(try MailE2e.buildRecoveryKit(unlocked, recoveryKey: "not a key"))
    }
}
