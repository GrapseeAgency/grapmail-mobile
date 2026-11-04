import Foundation
import Crypto

/// GrapMail end-to-end encryption, the iOS half — the mirror of Android's
/// `MailE2e.kt` and the browser's `src/app/lib/mailE2e.ts`.
///
/// ── WHY THE FORMAT IS DUPLICATED A THIRD TIME ────────────────────────────────
/// Every byte here is written to match
/// `services/mail-service/src/e2e/envelope.ts` and the browser: the AAD
/// strings, the HKDF `info` labels, the fixed 32-zero-byte HKDF salt, the
/// field order, the version numbers, the ct/tag split, the SPKI/PKCS#8 DER
/// wrappers. The three halves run in three runtimes with no shared module,
/// so the format IS the contract: a client that invented its own envelope
/// shape would produce mail the server stores and nothing can open — and
/// "nothing" includes this app itself, on the very next launch.
///
/// ── WHY swift-crypto RATHER THAN CryptoKit ───────────────────────────────────
/// CryptoKit has no X25519 key agreement. swift-crypto is Apple's own package
/// carrying exactly that (`Curve25519.KeyAgreement`), built on the same
/// BoringSSL the OS itself uses. HKDF and PBKDF2 are hand-rolled on the
/// framework's HMAC, because neither ships as a public symbol in either
/// framework. PBKDF2 runs over the UTF-8 bytes of the passphrase — the same
/// bytes `crypto.importKey('raw', utf8(passphrase))` uses in the browser and
/// the same bytes Android's hand-rolled PBKDF2 feeds it. A key derivation that
/// differs by one byte makes the key written on one device unreadable on
/// another, which is why this file spells the encoding out rather than
/// trusting a convenience.
enum MailE2e {
    /// Mirrors the server's `ENVELOPE_VERSION` / `ENVELOPE_VERSION_V2`.
    static let envelopeVersion = 1
    static let envelopeVersionV2 = 2
    static let contentKeyBytes = 32
    static let ivBytes = 12
    static let gcmTagBytes = 16
    static let maxRecipients = 1_000

    static let hkdfSalt = Data(repeating: 0, count: 32)
    static let hkdfInfoV1 = Data("grapmail-e2e-v1".utf8)
    static let hkdfInfoV2Kek = Data("grapmail-e2e-v2-kek".utf8)

    static let algV1 = "X25519+HKDF-SHA256+AES-256-GCM"
    static let algV2 = "X25519+HKDF-SHA256+AES-256-GCM (multi-recipient)"

    /// The passphrase container, byte-compatible with the browser's records.
    static let protectedKeyFormat = "grapmail-e2e-protected-key/1"
    static let kdfName = "PBKDF2-SHA256"
    static let pbkdf2Iterations = 600_000
    static let minPassphraseLength = 8
    static let kdfSaltBytes = 16

    /// The platform can always do the scheme: swift-crypto carries the
    /// primitives into the app rather than depending on an OS version. The
    /// shape exists because Android's answer to this is a real refusal on
    /// API < 29, and the screens branch on it identically.
    static func available() -> Bool { true }
    static var unavailableReason: String? { nil }

    // ── DER wrappers ─────────────────────────────────────────────────────────
    //
    // The wire carries base64 SubjectPublicKeyInfo and PKCS#8, because that is
    // what `exportKey('spki'/'pkcs8')` answers in the browser and what
    // `getEncoded()` answers on the JCA. Both are a fixed ASN.1 header plus
    // the raw 32-byte key; the headers below are the canonical forms from
    // RFC 8410. Extract-then-rewrap is what makes fingerprints stable: an
    // equivalent-but-differently-encoded SPKI must not fingerprint twice, and
    // a recipient key that does not strip to 32 bytes is refused rather than
    // trusted.

    private static let spkiPrefixX25519 = Data([0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x03, 0x21, 0x00])
    private static let spkiPrefixEd25519 = Data([0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00])
    private static let pkcs8PrefixX25519 = Data([0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x04, 0x22, 0x04, 0x20])
    private static let pkcs8PrefixEd25519 = Data([0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20])

    private static func spkiPrefix(_ kind: String) -> Data {
        kind == "Ed25519" ? spkiPrefixEd25519 : spkiPrefixX25519
    }
    private static func pkcs8Prefix(_ kind: String) -> Data {
        kind == "Ed25519" ? pkcs8PrefixEd25519 : pkcs8PrefixX25519
    }

    /// LocalizedError, not just CustomStringConvertible: the models surface
    /// failures through `localizedDescription`, and Foundation does NOT consult
    /// CustomStringConvertible for it — without errorDescription the crafted
    /// sentence ("That passphrase did not unlock this key.") renders as
    /// "The operation couldn't be completed. (…E2eError error 0.)".
    struct E2eError: Error, LocalizedError, CustomStringConvertible {
        let text: String
        var description: String { text }
        var errorDescription: String? { text }
    }

    private static func stripPrefix(_ der: Data, _ prefix: Data, _ what: String) throws -> Data {
        guard der.count == prefix.count + 32, der.prefix(prefix.count) == prefix else {
            throw E2eError(text: "That is not a well-formed \(what) key.")
        }
        return der.suffix(32)
    }

    // ── base64 / hex ─────────────────────────────────────────────────────────

    static func b64(_ d: Data) -> String { d.base64EncodedString() }
    static func unb64(_ s: String) -> Data? { Data(base64Encoded: s) }
    private static func hex(_ d: Data) -> String { d.map { String(format: "%02X", $0) }.joined() }

    // ── fingerprints ─────────────────────────────────────────────────────────

    /// SHA-256 over the canonical SPKI DER, uppercase hex, colon-separated —
    /// byte-identical to the browser's `fingerprintOf` and the server's
    /// `fingerprint()`, so two people reading it aloud compare one thing.
    /// This is not an OpenPGP fingerprint and is never called one.
    static func fingerprint(publicKeyB64: String, kind: String = "X25519") -> String? {
        guard let der = unb64(publicKeyB64),
              let raw = try? stripPrefix(der, spkiPrefix(kind), "SPKI") else { return nil }
        let canonical = spkiPrefix(kind) + raw
        let digest = SHA256.hash(data: canonical)
        return hex(Data(digest)).chunked(2).joined(separator: ":")
    }

    /// The low-order 64 bits of the fingerprint, for list labels.
    static func keyId(publicKeyB64: String, kind: String = "X25519") -> String? {
        guard let fp = fingerprint(publicKeyB64: publicKeyB64, kind: kind) else { return nil }
        return String(fp.replacingOccurrences(of: ":", with: "").suffix(16))
    }

    // ── key material ─────────────────────────────────────────────────────────

    struct KeyPairB64 { let publicKey: String; let privateKey: String }

    /// An X25519 or Ed25519 pair, exported the way the other two halves export
    /// it: public as base64 SPKI DER (the only shape the directory accepts),
    /// private as base64 PKCS#8 DER (the only shape that ever gets encrypted).
    static func generateKeyPair(kind: String) throws -> KeyPairB64 {
        if kind == "Ed25519" {
            let k = Curve25519.Signing.PrivateKey()
            return KeyPairB64(
                publicKey: b64(spkiPrefixEd25519 + k.publicKey.rawRepresentation),
                privateKey: b64(pkcs8PrefixEd25519 + k.rawRepresentation))
        }
        let k = Curve25519.KeyAgreement.PrivateKey()
        return KeyPairB64(
            publicKey: b64(spkiPrefixX25519 + k.publicKey.rawRepresentation),
            privateKey: b64(pkcs8PrefixX25519 + k.rawRepresentation))
    }

    private static func x25519Public(_ publicKeyB64: String) throws -> Curve25519.KeyAgreement.PublicKey {
        guard let der = unb64(publicKeyB64) else {
            throw E2eError(text: "That recipient's public key is not a usable X25519 key.")
        }
        let raw = try stripPrefix(der, spkiPrefixX25519, "X25519 public")
        do { return try Curve25519.KeyAgreement.PublicKey(rawRepresentation: raw) }
        catch { throw E2eError(text: "That recipient's public key is not a usable X25519 key.") }
    }

    private static func x25519Private(_ privateKeyB64: String) throws -> Curve25519.KeyAgreement.PrivateKey {
        guard let der = unb64(privateKeyB64) else {
            throw E2eError(text: "This key record is damaged.")
        }
        let raw = try stripPrefix(der, pkcs8PrefixX25519, "X25519 private")
        do { return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw) }
        catch { throw E2eError(text: "This key record is damaged.") }
    }

    private static func ed25519Private(_ privateKeyB64: String) throws -> Curve25519.Signing.PrivateKey {
        guard let der = unb64(privateKeyB64) else { throw E2eError(text: "This key record is damaged.") }
        let raw = try stripPrefix(der, pkcs8PrefixEd25519, "Ed25519 private")
        do { return try Curve25519.Signing.PrivateKey(rawRepresentation: raw) }
        catch { throw E2eError(text: "This key record is damaged.") }
    }

    private static func deriveSharedSecret(_ priv: Curve25519.KeyAgreement.PrivateKey,
                                           _ pub: Curve25519.KeyAgreement.PublicKey) throws -> Data {
        // swift-crypto's spelled-out name (identical in CryptoKit): the
        // `agreement(with:)` a browser-flavoured port reaches for does not
        // exist -- CI's compiler said so in as many words.
        let shared = try priv.sharedSecretFromKeyAgreement(with: pub)
        let bytes = shared.withUnsafeBytes { Data($0) }
        guard bytes.count == 32 else {
            throw E2eError(text: "X25519 agreed to \(bytes.count) bytes, not 32.")
        }
        return bytes
    }

    // ── HKDF / AES-GCM ───────────────────────────────────────────────────────

    /// RFC 5869 HKDF-SHA256, one 32-byte block: extract then expand once.
    /// Hand-rolled on the Crypto framework's HMAC rather than a library HKDF,
    /// because `HKDF` is not part of swift-crypto's public surface -- and
    /// 12 lines of RFC text are auditable in a way a convenience is not.
    /// Identical construction to Android's `MailE2e.kt` and the browser's.
    private static func hkdf(_ ikm: Data, _ info: Data) -> SymmetricKey {
        let prk = HMAC<SHA256>.authenticationCode(for: ikm, using: SymmetricKey(data: hkdfSalt))
        let prkData = Data(prk)
        var input = Data()
        input.append(contentsOf: info)
        input.append(1)
        let okm = HMAC<SHA256>.authenticationCode(for: input,
                                                  using: SymmetricKey(data: prkData))
        return SymmetricKey(data: Data(okm))
    }

    /// Ciphertext and tag apart, because the wire format stores them apart.
    private static func aesGcmEncrypt(_ key: SymmetricKey, _ iv: Data, _ aad: Data,
                                      _ plaintext: Data) throws -> (ct: Data, tag: Data) {
        let sealed = try AES.GCM.seal(plaintext, using: key,
                                      nonce: AES.GCM.Nonce(data: iv), authenticating: aad)
        return (sealed.ciphertext, sealed.tag)
    }

    private static func aesGcmDecrypt(_ key: SymmetricKey, _ iv: Data, _ aad: Data,
                                      _ ct: Data, _ tag: Data) throws -> Data {
        let box = try AES.GCM.SealedBox(combined: iv + ct + tag)
        return try AES.GCM.open(box, using: key, authenticating: aad)
    }

    // ── the frozen AAD strings ───────────────────────────────────────────────

    private static func aadV1(_ epk: String, _ iv: String) -> Data {
        Data("\(envelopeVersion)\u{0}\(epk)\u{0}\(iv)".utf8)
    }
    private static func contentAadV2(_ iv: String, _ epks: [String]) -> Data {
        Data("\(envelopeVersionV2)\u{0}\(iv)\u{0}\(epks.joined(separator: ","))".utf8)
    }
    private static func wrapAadV2(_ wrapIv: String, _ epk: String, _ contentIv: String) -> Data {
        Data("\(envelopeVersionV2)\u{0}\(wrapIv)\u{0}\(epk)\u{0}\(contentIv)".utf8)
    }
    private static func signedBytesV1(_ e: SealedEnvelopeV1) -> Data {
        Data("\(e.epk).\(e.iv).\(e.ct).\(e.tag)".utf8)
    }
    private static func signedBytesV2(_ e: SealedEnvelopeV2) -> Data {
        let wraps = e.recipients.map { "\($0.epk):\($0.wrapIv):\($0.wrappedKey):\($0.wrapTag)" }
            .joined(separator: ",")
        return Data("\(envelopeVersionV2).\(e.iv).\(e.ct).\(e.tag).\(wraps)".utf8)
    }

    // ── envelopes ────────────────────────────────────────────────────────────

    struct SealedEnvelopeV1 {
        var epk: String; var iv: String; var ct: String; var tag: String
        var sig: String?; var signerPub: String?
    }
    struct RecipientWrap {
        var epk: String; var wrapIv: String; var wrappedKey: String; var wrapTag: String
    }
    struct SealedEnvelopeV2 {
        var iv: String; var ct: String; var tag: String
        var recipients: [RecipientWrap]
        var sig: String?; var signerPub: String?
    }

    /// One recipient's wrap: an ephemeral pair, X25519 agreement, HKDF-KEK,
    /// AES-GCM over the content key with the v2 wrap AAD. Every recipient is
    /// validated and wrapped BEFORE the body is encrypted, so a bad key cannot
    /// leave a half-built envelope behind — the browser's order, kept.
    static func sealToRecipients(plaintext: String, recipientPublicKeys: [String],
                                 signing: KeyPairB64? = nil) throws -> SealedEnvelopeV2 {
        if recipientPublicKeys.isEmpty {
            throw E2eError(text: "An encrypted message needs at least one recipient, or nobody can open it.")
        }
        if recipientPublicKeys.count > maxRecipients {
            throw E2eError(text: "That is \(recipientPublicKeys.count) recipients; the limit is \(maxRecipients).")
        }
        let contentKey = SymmetricKey(size: .bits256)
        let contentKeyBytes = contentKey.withUnsafeBytes { Data($0) }
        let iv = Data((0..<ivBytes).map { _ in UInt8.random(in: 0...255) })
        let ivB64 = b64(iv)
        let wraps = try recipientPublicKeys.map { publicKey -> RecipientWrap in
            let recipient = try x25519Public(publicKey)
            let ephemeral = Curve25519.KeyAgreement.PrivateKey()
            let shared = try deriveSharedSecret(ephemeral, recipient)
            let kek = hkdf(shared, hkdfInfoV2Kek)
            let wrapIv = Data((0..<ivBytes).map { _ in UInt8.random(in: 0...255) })
            let wrapIvB64 = b64(wrapIv)
            let epkB64 = b64(spkiPrefixX25519 + ephemeral.publicKey.rawRepresentation)
            let (wk, wt) = try aesGcmEncrypt(kek, wrapIv, wrapAadV2(wrapIvB64, epkB64, ivB64),
                                             contentKeyBytes)
            return RecipientWrap(epk: epkB64, wrapIv: wrapIvB64, wrappedKey: b64(wk), wrapTag: b64(wt))
        }
        let (ct, tag) = try aesGcmEncrypt(contentKey, iv,
                                          contentAadV2(ivB64, wraps.map { $0.epk }),
                                          Data(plaintext.utf8))
        var envelope = SealedEnvelopeV2(iv: ivB64, ct: b64(ct), tag: b64(tag), recipients: wraps)
        if let signing {
            let key = try ed25519Private(signing.privateKey)
            envelope.sig = b64(try key.signature(for: signedBytesV2(envelope)))
            envelope.signerPub = signing.publicKey
        }
        return envelope
    }

    /// The envelope as it goes on the wire, in the server's field order.
    static func envelopeJson(_ e: SealedEnvelopeV2) -> String {
        var obj: [String: Any] = [
            "v": envelopeVersionV2, "alg": algV2,
            "iv": e.iv, "ct": e.ct, "tag": e.tag,
            "recipients": e.recipients.map {
                ["epk": $0.epk, "wrapIv": $0.wrapIv,
                 "wrappedKey": $0.wrappedKey, "wrapTag": $0.wrapTag]
            },
        ]
        if let sig = e.sig { obj["sig"] = sig }
        if let sp = e.signerPub { obj["signerPub"] = sp }
        let data = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }

    /// Recognise a stored envelope, shape only, no decryption — the reading
    /// view needs to know whether to render text at all before anything asks
    /// for a passphrase.
    static func parseEnvelope(_ raw: String?) -> (version: Int, signed: Bool)? {
        guard let raw, let data = raw.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let v = obj["v"] as? Int else { return nil }
        if v == envelopeVersionV2 {
            guard let iv = obj["iv"] as? String, let ct = obj["ct"] as? String,
                  let tag = obj["tag"] as? String,
                  let arr = obj["recipients"] as? [[String: Any]], !arr.isEmpty,
                  arr.allSatisfy({ e in ["epk", "wrapIv", "wrappedKey", "wrapTag"].allSatisfy { k in e[k] is String } })
            else { return nil }
            return (v, obj["sig"] is String)
        }
        if v == envelopeVersion {
            guard obj["epk"] is String, obj["iv"] is String, obj["ct"] is String,
                  obj["tag"] is String else { return nil }
            return (v, obj["sig"] is String)
        }
        return nil
    }

    /// Open an envelope with this device's X25519 private key. Version is
    /// checked before anything is attempted, and an unknown version is refused
    /// rather than guessed at. v2 tries every wrap in turn: the envelope
    /// carries an ephemeral key per recipient and nothing naming which one is
    /// ours — exactly as in the browser, for the same reason.
    static func open(_ raw: String, recipientPrivateKeyB64: String,
                     verifyKeyB64: String? = nil) throws -> String {
        guard let data = raw.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let v = obj["v"] as? Int else {
            throw E2eError(text: "This message's envelope is not readable.")
        }
        switch v {
        case envelopeVersionV2:
            guard let iv = obj["iv"] as? String, let ct = obj["ct"] as? String,
                  let tag = obj["tag"] as? String,
                  let arr = obj["recipients"] as? [[String: Any]] else {
                throw E2eError(text: "This message's envelope is not readable.")
            }
            let wraps = arr.compactMap { e -> RecipientWrap? in
                guard let epk = e["epk"] as? String, let wIv = e["wrapIv"] as? String,
                      let wk = e["wrappedKey"] as? String, let wt = e["wrapTag"] as? String
                else { return nil }
                return RecipientWrap(epk: epk, wrapIv: wIv, wrappedKey: wk, wrapTag: wt)
            }
            if let verify = verifyKeyB64 {
                try verifySignature(obj["signerPub"] as? String, obj["sig"] as? String, verify,
                                    signedBytesV2(SealedEnvelopeV2(iv: iv, ct: ct, tag: tag,
                                                                  recipients: wraps)))
            }
            return try openV2(iv, ct, tag, wraps, recipientPrivateKeyB64)
        case envelopeVersion:
            guard let epk = obj["epk"] as? String, let iv = obj["iv"] as? String,
                  let ct = obj["ct"] as? String, let tag = obj["tag"] as? String else {
                throw E2eError(text: "This message's envelope is not readable.")
            }
            if let verify = verifyKeyB64 {
                try verifySignature(obj["signerPub"] as? String, obj["sig"] as? String, verify,
                                    signedBytesV1(SealedEnvelopeV1(epk: epk, iv: iv, ct: ct, tag: tag)))
            }
            let priv = try x25519Private(recipientPrivateKeyB64)
            let shared = try deriveSharedSecret(priv, try x25519Public(epk))
            let key = hkdf(shared, hkdfInfoV1)
            guard let ivD = unb64(iv), let ctD = unb64(ct), let tagD = unb64(tag) else {
                throw E2eError(text: "This message's envelope is not readable.")
            }
            do { return String(decoding: try aesGcmDecrypt(key, ivD, aadV1(epk, iv), ctD, tagD), as: UTF8.self) }
            catch { throw E2eError(text: "Could not open this message: the wrong key, or the ciphertext is damaged.") }
        default:
            throw E2eError(text: "This message uses an envelope version this app does not understand.")
        }
    }

    private static func openV2(_ ivB64: String, _ ctB64: String, _ tagB64: String,
                               _ wraps: [RecipientWrap],
                               _ privateKeyB64: String) throws -> String {
        let priv = try x25519Private(privateKeyB64)
        var contentKey: SymmetricKey?
        for wrap in wraps {
            // A failed unwrap is a wrong entry, not an error: try the next.
            if let pub = try? x25519Public(wrap.epk),
               let shared = try? deriveSharedSecret(priv, pub),
               let wIv = unb64(wrap.wrapIv), let wk = unb64(wrap.wrappedKey),
               let wt = unb64(wrap.wrapTag),
               let opened = try? aesGcmDecrypt(hkdf(shared, hkdfInfoV2Kek), wIv,
                                               wrapAadV2(wrap.wrapIv, wrap.epk, ivB64), wk, wt) {
                contentKey = SymmetricKey(data: opened)
                break
            }
        }
        guard let contentKey else {
            throw E2eError(text: "This message was not encrypted to your key, so it cannot be opened here.")
        }
        guard let iv = unb64(ivB64), let ct = unb64(ctB64), let tag = unb64(tagB64),
              let plain = try? aesGcmDecrypt(contentKey, iv,
                                             contentAadV2(ivB64, wraps.map { $0.epk }), ct, tag)
        else {
            throw E2eError(text: "Could not open this message: the wrong key, or the ciphertext is damaged.")
        }
        return String(decoding: plain, as: UTF8.self)
    }

    /// Authenticity before plaintext, as the browser does it.
    private static func verifySignature(_ signerPub: String?, _ sig: String?,
                                        _ expectedKey: String, _ signed: Data) throws {
        guard let signerPub, let sig else { throw E2eError(text: "This message is not signed.") }
        guard signerPub == expectedKey else {
            throw E2eError(text: "This message is signed by an unexpected key.")
        }
        guard let pubDer = unb64(expectedKey),
              let raw = try? stripPrefix(pubDer, spkiPrefixEd25519, "Ed25519 public"),
              let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: raw),
              let sigData = unb64(sig) else {
            throw E2eError(text: "This message's signature did not verify.")
        }
        guard pub.isValidSignature(sigData, for: signed) else {
            throw E2eError(text: "This message's signature did not verify.")
        }
    }

    // ── passphrase-protected private keys ────────────────────────────────────

    /// A private key at rest. Only the public half is in the clear, so a
    /// fingerprint and a "published" state can be shown while the key is
    /// locked — the same reason the browser's record is shaped like this.
    struct ProtectedPrivateKey {
        var kind: String
        var publicKey: String
        var iterations: Int
        var salt: String
        var iv: String
        /// AES-256-GCM over the PKCS#8 DER with the tag appended, one field.
        var ct: String
    }

    private static func protectedKeyAad(_ kind: String, _ publicKey: String) -> Data {
        Data("\(protectedKeyFormat)\u{0}\(kind)\u{0}\(publicKey)".utf8)
    }

    /// PBKDF2-HMAC-SHA256 over the UTF-8 passphrase, 32 bytes out — the same
    /// bytes the browser's `importKey('raw', utf8(passphrase))` and Android's
    /// hand-rolled construction derive. Implemented on the Crypto framework's
    /// HMAC rather than CommonCrypto: the C enum constants do not bridge into
    /// Swift without a bridging header, and RFC 2898's single-block case is
    /// eight lines. A key derivation that differs by one byte makes the key
    /// written on one device unreadable on another, which is why the loop is
    /// spelled out here rather than trusted to a convenience.
    private static func derivePassphraseKey(_ passphrase: String, _ salt: Data,
                                            _ iterations: Int) -> Data {
        let pwd = SymmetricKey(data: Data(passphrase.utf8))
        var u = HMAC<SHA256>.authenticationCode(for: salt + Data([0, 0, 0, 1]), using: pwd)
        var result = Data(u)
        if iterations > 1 {  // `2...1` would be an invalid range on a 1-round record
            for _ in 2...iterations {
                u = HMAC<SHA256>.authenticationCode(for: Data(u), using: pwd)
                let next = Data(u)
                for j in 0..<32 { result[j] ^= next[j] }
            }
        }
        return result
    }

    static func protectPrivateKey(privateKeyB64: String, passphrase: String, kind: String,
                                  publicKey: String, iterations: Int = pbkdf2Iterations) throws -> ProtectedPrivateKey {
        if passphrase.count < minPassphraseLength {
            throw E2eError(text: "Use at least \(minPassphraseLength) characters for the passphrase.")
        }
        guard let der = unb64(privateKeyB64) else { throw E2eError(text: "This key record is damaged.") }
        let salt = Data((0..<kdfSaltBytes).map { _ in UInt8.random(in: 0...255) })
        let iv = Data((0..<ivBytes).map { _ in UInt8.random(in: 0...255) })
        let kek = SymmetricKey(data: derivePassphraseKey(passphrase, salt, iterations))
        let (ct, tag) = try aesGcmEncrypt(kek, iv, protectedKeyAad(kind, publicKey), der)
        return ProtectedPrivateKey(kind: kind, publicKey: publicKey, iterations: iterations,
                                   salt: b64(salt), iv: b64(iv), ct: b64(ct + tag))
    }

    /// Unlock a record, or explain why not. A wrong passphrase and a tampered
    /// record are one failure at the byte level and are not distinguished,
    /// because they cannot be. What the line avoids is the two misleading
    /// answers: it does not claim a wrong passphrase as though the record were
    /// known good, and it never surfaces a raw exception message.
    static func unlockPrivateKey(_ record: ProtectedPrivateKey, passphrase: String) throws -> String {
        guard record.kind == "X25519" || record.kind == "Ed25519" else {
            throw E2eError(text: "That is not a GrapMail key record.")
        }
        guard let salt = unb64(record.salt), let iv = unb64(record.iv),
              let blob = unb64(record.ct), blob.count > gcmTagBytes,
              unb64(record.publicKey) != nil else {
            throw E2eError(text: "That is not a GrapMail key record.")
        }
        let kek = SymmetricKey(data: derivePassphraseKey(passphrase, salt, record.iterations))
        let ct = blob.prefix(blob.count - gcmTagBytes)
        let tag = blob.suffix(gcmTagBytes)
        guard let der = try? aesGcmDecrypt(kek, iv, protectedKeyAad(record.kind, record.publicKey),
                                           Data(ct), Data(tag)) else {
            throw E2eError(text: "That passphrase did not unlock this key. Check it and try again.")
        }
        return b64(der)
    }

    static func changePassphrase(_ record: ProtectedPrivateKey, oldPassphrase: String,
                                 newPassphrase: String) throws -> ProtectedPrivateKey {
        let privateKey = try unlockPrivateKey(record, passphrase: oldPassphrase)
        return try protectPrivateKey(privateKeyB64: privateKey, passphrase: newPassphrase,
                                     kind: record.kind, publicKey: record.publicKey,
                                     iterations: record.iterations)
    }

    // ── the stored identity ──────────────────────────────────────────────────

    struct Identity {
        var encryption: ProtectedPrivateKey
        var signing: ProtectedPrivateKey?
        var createdAt: String
    }

    struct Unlocked {
        var encryptionPrivateKey: String
        var encryptionPublicKey: String
        var signingPrivateKey: String?
        var signingPublicKey: String?
    }

    /// The identity file, named by a digest rather than the account: the file
    /// lives in app-private storage anyway, but a filename is the one part a
    /// backup tool or a listing shows. Same scheme, same directory shape and
    /// same JSON as Android's `MailE2e.kt` and the browser's store.
    private static func fileURL(accountKey: String) -> URL {
        let digest = SHA256.hash(data: Data(accountKey.utf8))
        let id = hex(Data(digest)).prefix(32)
        let dir = FileManager.default.urls(for: .applicationSupportDirectory,
                                           in: .userDomainMask)[0]
            .appendingPathComponent("grapmail-e2e", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("identity-\(id).json")
    }

    static func loadIdentity(accountKey: String) -> Identity? {
        guard let data = try? Data(contentsOf: fileURL(accountKey: accountKey)),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        guard obj["format"] as? String == "grapmail-e2e-identity/1",
              let enc = protectedFrom(obj["encryption"] as? [String: Any]) else { return nil }
        let sig = protectedFrom(obj["signing"] as? [String: Any])
        return Identity(encryption: enc, signing: sig, createdAt: obj["createdAt"] as? String ?? "")
    }

    private static func protectedFrom(_ obj: [String: Any]?) -> ProtectedPrivateKey? {
        guard let obj, obj["format"] as? String == protectedKeyFormat,
              obj["kdf"] as? String == kdfName,
              let kind = obj["kind"] as? String, let publicKey = obj["publicKey"] as? String,
              let iterations = obj["iterations"] as? Int, let salt = obj["salt"] as? String,
              let iv = obj["iv"] as? String, let ct = obj["ct"] as? String else { return nil }
        return ProtectedPrivateKey(kind: kind, publicKey: publicKey, iterations: iterations,
                                   salt: salt, iv: iv, ct: ct)
    }

    private static func protectedToJson(_ r: ProtectedPrivateKey) -> [String: Any] {
        ["format": protectedKeyFormat, "kind": r.kind, "publicKey": r.publicKey,
         "kdf": kdfName, "iterations": r.iterations, "salt": r.salt, "iv": r.iv, "ct": r.ct]
    }

    @discardableResult
    static func saveIdentity(accountKey: String, identity: Identity) -> Bool {
        var obj: [String: Any] = [
            "format": "grapmail-e2e-identity/1",
            "createdAt": identity.createdAt,
            "encryption": protectedToJson(identity.encryption),
        ]
        if let signing = identity.signing { obj["signing"] = protectedToJson(signing) }
        guard let data = try? JSONSerialization.data(withJSONObject: obj) else { return false }
        do { try data.write(to: fileURL(accountKey: accountKey), options: .completeFileProtection) }
        catch { return false }
        return true
    }

    static func clearIdentity(accountKey: String) {
        try? FileManager.default.removeItem(at: fileURL(accountKey: accountKey))
    }

    /// Make this device's identity: an X25519 pair for encryption, an Ed25519
    /// pair so recipients can tell who sent what, both stored under the
    /// passphrase. The record is written BEFORE the caller publishes — a
    /// publish that fails over the network leaves a usable identity the user
    /// can publish again, rather than a published key nobody holds the half
    /// for.
    static func generateIdentity(accountKey: String, passphrase: String) throws -> Identity {
        if passphrase.count < minPassphraseLength {
            throw E2eError(text: "Use at least \(minPassphraseLength) characters for the passphrase.")
        }
        let encryption = try generateKeyPair(kind: "X25519")
        let signing = try generateKeyPair(kind: "Ed25519")
        let identity = Identity(
            encryption: try protectPrivateKey(privateKeyB64: encryption.privateKey,
                                              passphrase: passphrase, kind: "X25519",
                                              publicKey: encryption.publicKey),
            signing: try protectPrivateKey(privateKeyB64: signing.privateKey,
                                           passphrase: passphrase, kind: "Ed25519",
                                           publicKey: signing.publicKey),
            createdAt: ISO8601DateFormatter().string(from: Date()))
        guard saveIdentity(accountKey: accountKey, identity: identity) else {
            throw E2eError(text: "This device would not store the key. Without storage the key " +
                                "is lost when the app closes, making every message sealed to it " +
                                "unreadable — so nothing was published.")
        }
        return identity
    }

    /// The private halves, held only for as long as the session keeps them.
    static func unlock(_ identity: Identity, passphrase: String) throws -> Unlocked {
        let encPrivate = try unlockPrivateKey(identity.encryption, passphrase: passphrase)
        let sigPrivate = try identity.signing.map { try unlockPrivateKey($0, passphrase: passphrase) }
        return Unlocked(encryptionPrivateKey: encPrivate,
                        encryptionPublicKey: identity.encryption.publicKey,
                        signingPrivateKey: sigPrivate,
                        signingPublicKey: identity.signing?.publicKey)
    }
}

private extension String {
    func chunked(_ size: Int) -> [String] {
        var out: [String] = []
        var idx = startIndex
        while idx < endIndex {
            let end = index(idx, offsetBy: size, limitedBy: endIndex) ?? endIndex
            out.append(String(self[idx..<end]))
            idx = end
        }
        return out
    }
}
