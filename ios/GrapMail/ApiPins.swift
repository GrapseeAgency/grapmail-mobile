// GENERATED FILE - DO NOT EDIT BY HAND.
// Source: security/api-pins.json   Generator: scripts/generate-pins.py
// Regenerate with `python3 scripts/generate-pins.py` and commit the result;
// .github/workflows/pin-expiry.yml fails when this file is stale.
//
// Read by PinnedSessionDelegate.swift. Android reads the same JSON in
// android/app/build.gradle.kts, so both apps pin the same keys.

import Foundation

enum ApiPins {
    /// Hosts whose TLS chain must contain one of `sha256` (exact host match).
    static let hosts: [String] = [
        "grapsee.com",
        "api.iqracsc.com",
    ]

    /// `enforce` from the JSON. While false the apps do not pin at all.
    static let enforce: Bool = false

    /// Pinning switches itself off from this day (UTC) on: fail-open, platform
    /// trust only, so installs that outlive their pin set are not locked out.
    static let expires: String = "2027-11-23"

    /// `expires` as seconds since 1970-01-01T00:00:00Z (2027-11-23T00:00:00Z).
    static let expiresAt: TimeInterval = 1826928000

    /// base64(SHA-256(DER SubjectPublicKeyInfo)): the OkHttp `sha256/` value
    /// without the prefix. Placeholders are omitted (1 in the JSON).
    static let sha256: [String] = [
        "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=", // intermediate: CN=WE1, O=Google Trust Services, C=US, notAfter 2029-02-20
        "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=", // intermediate: CN=GTS Root R4, O=Google Trust Services LLC, C=US, notAfter 2028-01-28
        "fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=", // intermediate: CN=Root YR, O=ISRG, C=US, notAfter 2032-09-02
        "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // backup: CN=Root YE, O=ISRG, C=US, notAfter 2032-09-02
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // backup: CN=ISRG Root X1, O=Internet Security Research Group, C=US, notAfter 2035-06-04
        "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // backup: CN=ISRG Root X2, O=Internet Security Research Group, C=US, notAfter 2040-09-17
    ]
}
